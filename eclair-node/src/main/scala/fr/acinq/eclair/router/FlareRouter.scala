package fr.acinq.eclair.router

import java.io.{ByteArrayOutputStream, OutputStreamWriter}
import java.util

import akka.actor.{Actor, ActorLogging, ActorSelection, Props}
import akka.pattern._
import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair._
import fr.acinq.eclair.channel.{ChannelChangedState, DATA_NORMAL, NORMAL}
import lightning.neighbor_onion.Next.{Ack, Forward, Req}
import lightning.routing_table_update.update_type._
import lightning.{channel_desc, _}
import org.jgrapht.alg.DijkstraShortestPath
import org.jgrapht.ext._
import org.jgrapht.graph.{DefaultEdge, SimpleGraph}

import scala.collection.JavaConversions._
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Random, Success, Try}

/**
  * Created by PM on 08/09/2016.
  */
class FlareRouter(myself: bitcoin_pubkey, radius: Int, beaconCount: Int) extends Actor with ActorLogging {

  val MAX_BEACON_DISTANCE = 100

  context.system.eventStream.subscribe(self, classOf[ChannelChangedState])

  import scala.concurrent.ExecutionContext.Implicits.global

  context.system.scheduler.schedule(10 seconds, 20 seconds, self, 'tick_beacons)
  context.system.scheduler.schedule(1 minute, 1 minute, self, 'tick_reset)

  import FlareRouter._

  override def receive: Receive = main(new SimpleGraph[bitcoin_pubkey, NamedEdge](classOf[NamedEdge]), Nil, Nil, Set())

  def main(graph: SimpleGraph[bitcoin_pubkey, NamedEdge], neighbors: List[Neighbor], updatesBatch: List[routing_table_update], beacons: Set[Beacon]): Receive = {
    case ChannelChangedState(channel, theirNodeId, _, NORMAL, d: DATA_NORMAL) =>
      val neighbor = Neighbor(theirNodeId, d.commitments.anchorId, context.actorSelection(channel.path.parent), Nil)
      val channelDesc = channel_desc(neighbor.channel_id, myself, neighbor.node_id)
      val updates = routing_table_update(channelDesc, OPEN) :: Nil
      val (graph1, updates1) = include(myself, graph, updates, radius, beacons.map(_.id))
      neighbor.actorSelection ! neighbor_hello(graph2table(graph1))
      log.debug(s"graph is now ${graph2string(graph1)}")
      context.system.scheduler.scheduleOnce(1 second, self, 'tick_updates)
      context become main(graph1, neighbors :+ neighbor, updatesBatch ++ updates1, beacons)
    case msg@neighbor_hello(table1) =>
      log.debug(s"received $msg from $sender")
      val graph1 = merge(myself, graph, table1, radius, beacons.map(_.id))
      // we send beacon req to every newly discovered node
      val new_nodes = graph1.vertexSet().toSet -- graph.vertexSet().toSet
      for (node <- new_nodes) {
        log.debug(s"sending beacon_req message to new node $node")
        val (channels1, route) = findRoute(graph1, myself, node)
        send(route, neighbors, neighbor_onion(Req(beacon_req(myself, channels1))))
      }
      log.debug(s"graph is now ${graph2string(graph1)}")
      context become main(graph1, neighbors, updatesBatch, beacons)
    case msg@neighbor_update(updates) =>
      log.debug(s"received $msg from $sender")
      val (graph1, updates1) = include(myself, graph, updates, radius, beacons.map(_.id))
      // we send beacon req to every newly discovered node
      val new_nodes = graph1.vertexSet().toSet -- graph.vertexSet().toSet
      for (node <- new_nodes) {
        log.debug(s"sending beacon_req message to new node $node")
        val (channels1, route) = findRoute(graph1, myself, node)
        send(route, neighbors, neighbor_onion(Req(beacon_req(myself, channels1))))
      }
      log.debug(s"graph is now ${graph2string(graph1)}")
      context.system.scheduler.scheduleOnce(3 second, self, 'tick_updates)
      context become main(graph1, neighbors, updatesBatch ++ updates1, beacons)
    case msg@neighbor_reset(channel_ids) =>
      log.debug(s"received neighbor_reset from $sender with ${channel_ids.size} channels")
      val diff = graph2table(graph).channels.filterNot(c => channel_ids.contains(c.channelId)).map(c => routing_table_update(c, OPEN))
      log.debug(s"sending back ${diff.size} channels to $sender")
      sender ! neighbor_update(diff)
    case 'tick_updates if !updatesBatch.isEmpty =>
      val neighbors1 = neighbors.map(neighbor => {
        val diff = updatesBatch.filterNot(neighbor.sent.contains(_))
        if (diff.size > 0) {
          log.debug(s"sending $diff to ${neighbor.node_id}")
          neighbor.actorSelection ! neighbor_update(diff)
        }
        neighbor.copy(sent = neighbor.sent ::: diff)
      })
      context become main(graph, neighbors1, Nil, beacons)
    case 'tick_updates => // nothing to do
    case 'tick_reset =>
      val channel_ids = graph2table(graph).channels.map(_.channelId)
      for (neighbor <- neighbors) {
        log.debug(s"sending nighbor_reset message to neighbor $neighbor")
        neighbor.actorSelection ! neighbor_reset(channel_ids)
      }
    case 'tick_beacons =>
      for (node <- Random.shuffle(graph.vertexSet().toSet - myself).take(1)) {
        log.debug(s"sending beacon_req message to random node $node")
        val (channels1, route) = findRoute(graph, myself, node)
        send(route, neighbors, neighbor_onion(Req(beacon_req(myself, channels1))))
      }
    case msg@neighbor_onion(onion) =>
      (onion: @unchecked) match {
        case lightning.neighbor_onion.Next.Forward(next) =>
          //log.debug(s"forwarding $msg to ${next.node}")
          val neighbor = next.node
          neighbor2channel(neighbor, neighbors) match {
            case Some(channel) => channel ! next.onion
            case None => log.error(s"could not find channel for neighbor $neighbor, cannot forward $msg")
          }
        case lightning.neighbor_onion.Next.Req(req) => self ! req
        case lightning.neighbor_onion.Next.Ack(ack) => self ! ack
      }
    case msg@beacon_req(origin, channels) =>
      log.debug(s"received beacon_req msg from $origin with channels=${channels2string(channels)}")
      // adding routes to my table so that I can reply to the origin
      val updates = channels.map(channelDesc => routing_table_update(channelDesc, OPEN)).toList
      // TODO : maybe we should allow an even larger radius, because their MAX_BEACON_DISTANCE could be larger than ours
      val (graph1, _) = include(myself, graph, updates, MAX_BEACON_DISTANCE, Set())
      val (channels1, route) = findRoute(graph1, myself, origin)
      // looking for a strictly better beacon in our neighborhood
      val nodes: Set[bitcoin_pubkey] = graph.vertexSet().toSet - origin - myself
      val distances = nodes.map(node => (node, distance(origin, node)))
      val distance2me = distance(origin, myself)
      val selected = distances
        .filter(_._2 < distance2me).toList
        .sortBy(_._2).map(_._1)
        .map(alternate => {
          val (channels2, _) = findRoute(graph1, myself, alternate)
          (alternate, channels2, channels1.size + channels2.size)
        })
      selected.headOption match {
        // order matters!
        case Some((alternate, channels2, hops)) if hops > MAX_BEACON_DISTANCE =>
          log.debug(s"alternate $alternate was better but it is too far from $origin (hops=$hops)")
          send(route, neighbors, neighbor_onion(Ack(beacon_ack(myself, None, channels1))))
        case Some((alternate, channels2, hops)) =>
          // we reply with a better beacon and give them a route
          // maybe not the shortest route but we want to be on the path (that's in our interest)
          log.debug(s"recommending alternate $alternate to $origin (hops=$hops)")
          send(route, neighbors, neighbor_onion(Ack(beacon_ack(myself, Some(alternate), channels1 ++ channels2))))
        case _ =>
          // we accept to be their beacon
          send(route, neighbors, neighbor_onion(Ack(beacon_ack(myself, None, channels1))))
      }
    case msg@beacon_ack(origin, alternative_opt, channels) =>
      log.debug(s"received beacon_ack msg from $origin with alternative=$alternative_opt channels=${channels2string(channels)}")
      val updates = channels.map(channelDesc => routing_table_update(channelDesc, OPEN)).toList
      val (graph1, _) = include(myself, graph, updates, MAX_BEACON_DISTANCE, Set())
      alternative_opt match {
        // order matters!
        case Some(alternative) if distance(myself, alternative) > distance(myself, origin) =>
          log.warning(s"$origin is lying ! dist(us, alt) > dist(us, origin)")
        // TODO we should probably blacklist them
        case Some(alternative) if !graph1.containsVertex(alternative) =>
          log.debug(s"the proposed alternative $alternative is not in our graph (probably too far away)")
        case Some(alternative) =>
          log.debug(s"sending alternative $alternative a beacon_req")
          val (channels1, route) = findRoute(graph1, myself, alternative)
          send(route, neighbors, neighbor_onion(Req(beacon_req(myself, channels1))))
        case None => {} // nothing to do
      }
      val beacons1 = if (!graph1.containsVertex(origin)) {
        log.debug(s"origin $origin is not in our graph (probably too far away)")
        beacons
      } else if (beacons.map(_.id).contains(origin)) {
        log.debug(s"we already have $origin as a beacon")
        beacons
      } else if (beacons.size < beaconCount) {
        log.info(s"adding $origin as a beacon")
        val (channels1, _) = findRoute(graph1, myself, origin)
        beacons + Beacon(origin, distance(myself, origin), channels1.size)
      } else if (beacons.exists(b => b.distance > distance(myself, origin))) {
        val deprecatedBeacon = beacons.find(b => b.distance > distance(myself, origin)).get
        log.info(s"replacing $deprecatedBeacon by $origin")
        val (channels1, _) = findRoute(graph1, myself, origin)
        beacons - deprecatedBeacon + Beacon(origin, distance(myself, origin), channels1.size)
      } else {
        log.debug(s"ignoring beacon candidate $origin")
        beacons
      }
      // this will cause old beacons to be removed from the graph
      val (graph2, _) = include(myself, graph1, Nil, radius, beacons1.map(_.id))
      log.debug(s"graph is now ${graph2string(graph2)}")
      log.debug(s"my beacons are now ${beacons1.map(b => s"${pubkey2string(b.id)}(${b.hops})").mkString(",")}")
      context become main(graph2, neighbors, updatesBatch, beacons1)
    case RouteRequest(target, targetTable) =>
      val g1 = graph.clone().asInstanceOf[SimpleGraph[bitcoin_pubkey, NamedEdge]]
      val g2 = merge(myself, g1, targetTable, 100, Set())
      Future(findRoute(g2, myself, target)) map (r => RouteResponse(r._2)) pipeTo sender
    case 'network =>
      sender ! graph2table(graph).channels
    case 'beacons =>
      sender ! beacons
    case 'info =>
      sender ! FlareInfo(neighbors.map(_.node_id).size, graph.vertexSet().size(), beacons)
    case 'dot =>
      sender ! graph2dot(myself, graph, beacons.map(_.id))
  }

  def send(route: Seq[bitcoin_pubkey], neighbors: List[Neighbor], msg: neighbor_onion): Unit = {
    require(route.size >= 2, s"$route should be of size >=2 (neighbors=$neighbors msg=$msg)")
    val onion = buildOnion(route.drop(2), msg)
    val neighbor = route(1)
    neighbor2channel(neighbor, neighbors) match {
      case Some(actorSelection) => actorSelection ! onion
      case None => log.error(s"could not find channel for neighbor $neighbor")
    }
  }

}


object FlareRouter {

  def props(radius: Int, beaconCount: Int) = Props(classOf[FlareRouter], radius, beaconCount)

  // @formatter:off
  case class NamedEdge(val id: sha256_hash) extends DefaultEdge { override def toString: String = id.toString() }
  case class Neighbor(node_id: BinaryData, channel_id: BinaryData, actorSelection: ActorSelection, sent: List[routing_table_update])
  case class Beacon(id: bitcoin_pubkey, distance: BigInt, hops: Int)
  case class FlareInfo(neighbors: Int, known_nodes: Int, beacons: Set[Beacon])
  case class RouteRequest(target: bitcoin_pubkey, targetTable: routing_table)
  case class RouteResponse(route: Seq[bitcoin_pubkey])
  // @formatter:on

  def graph2table(graph: SimpleGraph[bitcoin_pubkey, NamedEdge]): routing_table = {
    val channels = graph.edgeSet().map(edge => channel_desc(edge.id, graph.getEdgeSource(edge), graph.getEdgeTarget(edge)))
    routing_table(channels.toSeq)
  }

  def graph2string(graph: SimpleGraph[bitcoin_pubkey, NamedEdge]): String =
    s"node_count=${graph.vertexSet.size} edges: " + channels2string(graph.edgeSet().map(edge => channel_desc(edge.id, graph.getEdgeSource(edge), graph.getEdgeTarget(edge))).toSeq)

  def pubkey2string(pubkey: bitcoin_pubkey): String =
    pubkey.toString().take(6)

  def channels2string(channels: Seq[channel_desc]): String =
    channels.map(c => s"${pubkey2string(c.nodeA)}->${pubkey2string(c.nodeB)}").mkString(" ")

  def graph2dot(myself: bitcoin_pubkey, graph: SimpleGraph[bitcoin_pubkey, NamedEdge], beacons: Set[bitcoin_pubkey]): BinaryData = {
    val vertexIDProvider = new VertexNameProvider[bitcoin_pubkey]() {
      override def getVertexName(v: bitcoin_pubkey): String = s""""${v.toString().take(6)}""""
    }
    val edgeLabelProvider = new EdgeNameProvider[NamedEdge]() {
      override def getEdgeName(e: NamedEdge): String = e.id.toString().take(6)
    }
    val vertexAttributeProvider = new ComponentAttributeProvider[bitcoin_pubkey]() {

      import scala.collection.JavaConversions._

      override def getComponentAttributes(t: bitcoin_pubkey): util.Map[String, String] =
        if (t == myself) {
          Map("color" -> "blue")
        } else if (beacons.contains(t)) {
          Map("color" -> "red")
        } else {
          Map("color" -> "black")
        }
    }
    val exporter = new DOTExporter[bitcoin_pubkey, NamedEdge](vertexIDProvider, null, edgeLabelProvider, vertexAttributeProvider, null)
    val bos = new ByteArrayOutputStream()
    val writer = new OutputStreamWriter(bos)
    try {
      exporter.export(writer, graph)
      bos.toByteArray
    } finally {
      writer.close()
      bos.close()
    }
  }

  def merge(myself: bitcoin_pubkey, graph: SimpleGraph[bitcoin_pubkey, NamedEdge], table2: routing_table, radius: Int, beacons: Set[bitcoin_pubkey]): SimpleGraph[bitcoin_pubkey, NamedEdge] = {
    val updates = table2.channels.map(c => routing_table_update(c, OPEN))
    include(myself, graph, updates, radius, beacons)._1
  }

  def include(myself: bitcoin_pubkey, graph: SimpleGraph[bitcoin_pubkey, NamedEdge], updates: Seq[routing_table_update], radius: Int, beacons: Set[bitcoin_pubkey]): (SimpleGraph[bitcoin_pubkey, NamedEdge], Seq[routing_table_update]) = {
    val graph1 = graph.clone().asInstanceOf[SimpleGraph[bitcoin_pubkey, NamedEdge]]
    updates.collect {
      case routing_table_update(channel, OPEN) =>
        graph1.addVertex(channel.nodeA)
        graph1.addVertex(channel.nodeB)
        graph1.addEdge(channel.nodeA, channel.nodeB, NamedEdge(channel.channelId))
      case routing_table_update(channel, CLOSE) =>
        graph1.removeEdge(NamedEdge(channel.channelId))
    }
    // we whitelist all nodes on the path to beacons
    val whitelist: Set[bitcoin_pubkey] = beacons.map(beacon => Try(findRoute(graph1, myself, beacon))).collect {
      case Success((_, nodes)) => nodes
    }.flatten

    val distances = (graph1.vertexSet() -- whitelist - myself).collect {
      case vertex: bitcoin_pubkey if vertex != myself => (vertex, Try(new DijkstraShortestPath(graph1, myself, vertex).getPath.getEdgeList.size()))
    }
    distances.collect {
      case (vertex, Failure(t)) => graph1.removeVertex(vertex)
      case (vertex, Success(d)) if d > radius => graph1.removeVertex(vertex)
    }
    val origChannels = graph.edgeSet().map(_.id).toSet
    val updates1 = updates.collect {
      case u@routing_table_update(channel, OPEN) if !origChannels.contains(channel.channelId) && graph1.containsEdge(NamedEdge(channel.channelId)) => u
      case u@routing_table_update(channel, CLOSE) if origChannels.contains(channel.channelId) && !graph1.containsEdge(NamedEdge(channel.channelId)) => u
    }
    (graph1, updates1)
  }

  def findRoute(graph: SimpleGraph[bitcoin_pubkey, NamedEdge], source: bitcoin_pubkey, target: bitcoin_pubkey): (Seq[channel_desc], Seq[bitcoin_pubkey]) = {
    Option(new DijkstraShortestPath(graph, source, target).getPath) match {
      case Some(path) =>
        val channels = path.getEdgeList.map(edge => channel_desc(edge.id, graph.getEdgeSource(edge), graph.getEdgeTarget(edge)))
        val nodes = path.getEdgeList.foldLeft(List(path.getStartVertex)) {
          case (rest :+ v, edge) if graph.getEdgeSource(edge) == v => rest :+ v :+ graph.getEdgeTarget(edge)
          case (rest :+ v, edge) if graph.getEdgeTarget(edge) == v => rest :+ v :+ graph.getEdgeSource(edge)
        }
        (channels, nodes)
      case None => throw new RuntimeException(s"route not found to $target")
    }
  }

  def buildOnion(nodes: Seq[bitcoin_pubkey], msg: neighbor_onion): neighbor_onion = {
    nodes.reverse.foldLeft(msg) {
      case (o, next) => neighbor_onion(Forward(beacon_forward(next, o)))
    }
  }

  def neighbor2channel(node_id: bitcoin_pubkey, neighbors: List[Neighbor]): Option[ActorSelection] =
    neighbors.find(_.node_id == node_id).map(_.actorSelection)

  def distance(a: BinaryData, b: BinaryData): BigInt = {
    require(a.length == b.length)
    val c = new Array[Byte](a.length)
    for (i <- 0 until a.length) c(i) = ((a.data(i) ^ b.data(i)) & 0xff).toByte
    BigInt(1, c)
  }

}