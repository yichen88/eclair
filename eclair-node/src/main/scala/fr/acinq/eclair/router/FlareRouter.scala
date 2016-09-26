package fr.acinq.eclair.router

import java.util.UUID

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.pattern.pipe
import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair._
import fr.acinq.eclair.channel.{ChannelChangedState, DATA_NORMAL, NORMAL}
import lightning.neighbor_onion.Next.{Ack, Forward, Req}
import lightning.routing_table_update.update_type._
import lightning.{channel_desc, _}
import org.graphstream.algorithm.Dijkstra
import org.graphstream.graph.Edge
import org.graphstream.graph.implementations.{MultiGraph, MultiNode}

import scala.collection.JavaConversions._
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Random, Try}

/**
  * Created by PM on 08/09/2016.
  */
class FlareRouter(myself: bitcoin_pubkey, radius: Int, beaconCount: Int, ticks: Boolean = true, beaconReactivateCount: Int = 5) extends Actor with ActorLogging {

  val MAX_BEACON_DISTANCE = 100

  context.system.eventStream.subscribe(self, classOf[ChannelChangedState])

  import scala.concurrent.ExecutionContext.Implicits.global

  if (ticks) {
    context.system.scheduler.schedule(10 seconds, 20 seconds, self, 'tick_beacons)
    context.system.scheduler.schedule(1 minute, 1 minute, self, 'tick_reset)
  }

  import FlareRouter._

  override def receive: Receive = main(new MultiGraph(UUID.randomUUID().toString), null, Nil, Nil, Set())

  def main(graph: MultiGraph, dijkstra: Dijkstra, neighbors: List[Neighbor], updatesBatch: List[routing_table_update], beacons: Set[Beacon]): Receive = {
    case ChannelChangedState(_, connection, theirNodeId, _, NORMAL, d: DATA_NORMAL) =>
      val neighbor = Neighbor(theirNodeId, d.commitments.anchorId, connection, Nil)
      val channelDesc = channel_desc(neighbor.channel_id, myself, neighbor.node_id)
      val updates = routing_table_update(channelDesc, OPEN) :: Nil
      val (graph1, updates1, dijkstra1) = include(myself, graph, updates, radius, beacons.map(_.id))
      neighbor.connection ! neighbor_hello(graph2table(graph1))
      log.debug(s"graph is now ${graph2string(graph1)}")
      context.system.scheduler.scheduleOnce(1 second, self, 'tick_updates)
      context become main(graph1, dijkstra1, neighbors :+ neighbor, updatesBatch ++ updates1, beacons)
    case msg@neighbor_hello(table1) =>
      log.debug(s"received $msg from $sender")
      val (graph1, dijkstra1) = merge(myself, graph, table1, radius, beacons.map(_.id))
      // we send beacon req to every newly discovered node
      val new_nodes = (graph1.getNodeSet[MultiNode].map(node2pubkey(_)).toSet -- graph.getNodeSet[MultiNode].map(node2pubkey(_)).toSet)
      for (node <- new_nodes) {
        log.debug(s"$myself sending beacon_req message to new node $node")
        val (channels1, route) = findRoute(dijkstra1, getOrAdd(graph1, myself), getOrAdd(graph1, node))
        send(route, neighbors, neighbor_onion(Req(beacon_req(myself, channels1))))
      }
      log.debug(s"graph is now ${graph2string(graph1)}")
      context become main(graph1, dijkstra1, neighbors, updatesBatch, beacons)
    case msg@neighbor_update(updates) =>
      log.debug(s"received $msg from $sender")
      val (graph1, updates1, dijkstra1) = include(myself, graph, updates, radius, beacons.map(_.id))
      // we send beacon req to every newly discovered node
      val new_nodes = (graph1.getNodeSet[MultiNode].map(node2pubkey(_)).toSet -- graph.getNodeSet[MultiNode].map(node2pubkey(_)).toSet)
      for (node <- new_nodes) {
        log.debug(s"sending beacon_req message to new node $node")
        val (channels1, route) = findRoute(dijkstra1, getOrAdd(graph1, myself), getOrAdd(graph1, node))
        send(route, neighbors, neighbor_onion(Req(beacon_req(myself, channels1))))
      }
      log.debug(s"graph is now ${graph2string(graph1)}")
      context.system.scheduler.scheduleOnce(3 second, self, 'tick_updates)
      context become main(graph1, dijkstra1, neighbors, updatesBatch ++ updates1, beacons)
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
          neighbor.connection ! neighbor_update(diff)
        }
        neighbor.copy(sent = neighbor.sent ::: diff)
      })
      context become main(graph, dijkstra, neighbors1, Nil, beacons)
    case 'tick_updates => // nothing to do
    case 'tick_reset =>
      val channel_ids = graph2table(graph).channels.map(_.channelId)
      for (neighbor <- neighbors) {
        log.debug(s"sending nighbor_reset message to neighbor $neighbor")
        neighbor.connection ! neighbor_reset(channel_ids)
      }
    case 'tick_beacons =>
      for (node <- Random.shuffle(graph.getNodeSet[MultiNode].toSet - graph.getNode[MultiNode](pubkey2string(myself))).take(beaconReactivateCount)) {
        log.debug(s"sending beacon_req message to random node $node")
        val (channels1, route) = findRoute(dijkstra, getOrAdd(graph, myself), node)
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
      val (graph1, _, dijkstra1) = include(myself, graph, updates, MAX_BEACON_DISTANCE, Set())
      val (channels1, route) = findRoute(dijkstra1, getOrAdd(graph1, myself), getOrAdd(graph1, origin))
      // looking for a strictly better beacon in our neighborhood
      val nodes: Set[bitcoin_pubkey] = graph.getNodeSet[MultiNode].map(n => node2pubkey(n)).toSet - origin - myself
      val distances = nodes.map(node => (node, distance(origin, node)))
      val distance2me = distance(origin, myself)
      val selected = distances
        .filter(_._2 < distance2me).toList
        .sortBy(_._2).map(_._1)
        .map(alternate => {
          val (channels2, _) = findRoute(dijkstra1, getOrAdd(graph1, myself), getOrAdd(graph1, alternate))
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
      val (graph1, _, dijkstra1) = include(myself, graph, updates, MAX_BEACON_DISTANCE, Set())
      alternative_opt match {
        // order matters!
        case Some(alternative) if distance(myself, alternative) > distance(myself, origin) =>
          log.warning(s"$origin is lying ! dist(us, alt) > dist(us, origin)")
        // TODO we should probably blacklist them
        case Some(alternative) if Option(graph1.getNode[MultiNode](pubkey2string(alternative))).isEmpty =>
          log.debug(s"the proposed alternative $alternative is not in our graph (probably too far away)")
        case Some(alternative) =>
          log.debug(s"sending alternative $alternative a beacon_req")
          val (channels1, route) = findRoute(dijkstra1, getOrAdd(graph1, myself), getOrAdd(graph1, alternative))
          send(route, neighbors, neighbor_onion(Req(beacon_req(myself, channels1))))
        case None => {} // nothing to do
      }
      val beacons1 = if (Option(graph1.getNode[MultiNode](pubkey2string(origin))).isEmpty) {
        log.debug(s"origin $origin is not in our graph (probably too far away)")
        beacons
      } else if (beacons.map(_.id).contains(origin)) {
        log.debug(s"we already have $origin as a beacon")
        beacons
      } else if (beacons.size < beaconCount) {
        log.info(s"adding $origin as a beacon")
        val (channels1, _) = findRoute(dijkstra1, getOrAdd(graph1, myself), getOrAdd(graph1, origin))
        beacons + Beacon(origin, distance(myself, origin), channels1.size)
      } else if (beacons.exists(b => b.distance > distance(myself, origin))) {
        val deprecatedBeacon = beacons.find(b => b.distance > distance(myself, origin)).get
        log.info(s"replacing $deprecatedBeacon by $origin")
        val (channels1, _) = findRoute(dijkstra1, getOrAdd(graph1, myself), getOrAdd(graph1, origin))
        beacons - deprecatedBeacon + Beacon(origin, distance(myself, origin), channels1.size)
      } else {
        log.debug(s"ignoring beacon candidate $origin")
        beacons
      }
      // this will cause old beacons to be removed from the graph
      val (graph2, _, dijkstra2) = include(myself, graph1, Nil, radius, beacons1.map(_.id))
      log.debug(s"graph is now ${graph2string(graph2)}")
      log.debug(s"my beacons are now ${beacons1.map(b => s"${pubkey2string(b.id).take(6)}(${b.hops})").mkString(",")}")
      context become main(graph2, dijkstra2, neighbors, updatesBatch, beacons1)
    case RouteRequest(target, targetTable) =>
      val g1 = copy(graph)
      val (g2, dijkstra2) = merge(myself, g1, targetTable, 100, Set())
      Future(findRoute(dijkstra2, getOrAdd(g2, myself), getOrAdd(g2, target))).map(r => RouteResponse(r._2)) pipeTo sender
    case 'network =>
      sender ! graph2table(graph).channels
    case 'beacons =>
      sender ! beacons
    case 'info =>
      sender ! FlareInfo(neighbors.map(_.node_id).size, graph.getNodeCount, beacons)
    case 'dot =>
      sender ! graph2dot(myself, graph, beacons.map(_.id))
  }

  def send(route: Seq[bitcoin_pubkey], neighbors: List[Neighbor], msg: neighbor_onion): Unit = {
    require(route.size >= 2, s"$route should be of size >=2 (neighbors=$neighbors msg=$msg)")
    val onion = buildOnion(route.drop(2), msg)
    if (route.size < 2) {
      log.warning(s"invalid route $route")
    }
    val neighbor = route(1)
    neighbor2channel(neighbor, neighbors) match {
      case Some(actorSelection) => actorSelection ! onion
      case None => log.error(s"could not find channel for neighbor $neighbor")
    }
  }

}


object FlareRouter {

  // @formatter:off
  case class Neighbor(node_id: bitcoin_pubkey, channel_id: BinaryData, connection: ActorRef, sent: List[routing_table_update])
  case class Beacon(id: bitcoin_pubkey, distance: BigInt, hops: Int)
  case class FlareInfo(neighbors: Int, known_nodes: Int, beacons: Set[Beacon])
  case class RouteRequest(target: bitcoin_pubkey, targetTable: routing_table)
  case class RouteResponse(route: Seq[bitcoin_pubkey])
  // @formatter:on

  def graph2table(graph: MultiGraph): routing_table =
    routing_table(graph.getEdgeSet[Edge].map(edge2ChannelDesc(_)).toSeq)

  def node2pubkey(node: MultiNode): bitcoin_pubkey =
    bin2pubkey(BinaryData(node.getId))

  def edge2ChannelDesc(edge: Edge): channel_desc =
    channel_desc(bin2sha256(BinaryData(edge.getId)), bin2pubkey(BinaryData(edge.getSourceNode[MultiNode].getId)), bin2pubkey(BinaryData(edge.getTargetNode[MultiNode].getId)))

  def channelId2sha256(edge: Edge): sha256_hash =
    bin2sha256(BinaryData(edge.getId))

  def graph2string(graph: MultiGraph): String =
    s"node_count=${graph.getNodeCount} edges: " + channels2string(graph2table(graph).channels)

  def pubkey2string(pubkey: bitcoin_pubkey): String =
    pubkey2bin(pubkey).toString()

  def sha2562string(hash: sha256_hash): String =
    sha2562bin(hash).toString()

  def channels2string(channels: Seq[channel_desc]): String =
    channels.map(c => s"${pubkey2string(c.nodeA).take(6)}->${pubkey2string(c.nodeB).take(6)}").mkString(" ")

  def graph2dot(myself: bitcoin_pubkey, graph: MultiGraph, beacons: Set[bitcoin_pubkey]): BinaryData = BinaryData("")

  /*{
     val vertexIDProvider = new VertexNameProvider[bitcoin_pubkey]() {
       override def getVertexName(v: bitcoin_pubkey): String = s""""${pubkey2bin(v).toString.take(6)}""""
     }
     val edgeLabelProvider = new EdgeNameProvider[NamedEdge]() {
       override def getEdgeName(e: NamedEdge): String = sha2562bin(e.id).toString().take(6)
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
   }*/

  def copy(graph: MultiGraph): MultiGraph = {
    val g1 = new MultiGraph(UUID.randomUUID().toString)
    graph.getNodeIterator[MultiNode].foreach(node => g1.addNode[MultiNode](node.getId))
    graph.getEdgeIterator[Edge].foreach(edge => g1.addEdge[Edge](edge.getId, g1.getNode[MultiNode](edge.getSourceNode[MultiNode].getId), g1.getNode[MultiNode](edge.getTargetNode[MultiNode].getId), edge.isDirected))
    g1
  }

  def merge(myself: bitcoin_pubkey, graph: MultiGraph, table2: routing_table, radius: Int, beacons: Set[bitcoin_pubkey]): (MultiGraph, Dijkstra) = {
    val updates = table2.channels.map(c => routing_table_update(c, OPEN))
    val (graph1, _, dijkstra1) = include(myself, graph, updates, radius, beacons)
    (graph1, dijkstra1)
  }

  def getOrAdd(g: MultiGraph, node: bitcoin_pubkey): MultiNode = {
    val id = pubkey2string(node)
    Option(g.getNode[MultiNode](id)).getOrElse(g.addNode[MultiNode](id))
  }

  def include(myself: bitcoin_pubkey, graph: MultiGraph, updates: Seq[routing_table_update], radius: Int, beacons: Set[bitcoin_pubkey]): (MultiGraph, Seq[routing_table_update], Dijkstra) = {
    val graph1 = copy(graph)
    updates.collect {
      case routing_table_update(channel, OPEN) =>
        val a = getOrAdd(graph1, channel.nodeA)
        val b = getOrAdd(graph1, channel.nodeB)
        // optimistic, doesn't matter if it fails
        Try(graph1.addEdge(sha2562string(channel.channelId), a, b))
      case routing_table_update(channel, CLOSE) =>
        // optimistic, doesn't matter if it fails
        Try(graph1.removeEdge(sha2562string(channel.channelId)))
    }
    val myselfNode = graph1.getNode[MultiNode](pubkey2string(myself))
    val dijkstra = new Dijkstra()
    dijkstra.init(graph1)
    dijkstra.setSource(myselfNode)
    dijkstra.compute()

    // we whitelist all nodes on the path to beacons
    val whitelist = beacons.flatMap(beacon => dijkstra.getPathNodes[MultiNode](graph1.getNode[MultiNode](pubkey2string(beacon))))
    val neighbors = graph1.getNodeSet[MultiNode].toSet -- whitelist - myselfNode
    // we remove all nodes farther than radius and not on the path to beacons
    neighbors.collect {
      // this also eliminates unreachable nodes (infinite path length)
      case node if (dijkstra.getPathLength(node) > radius) => graph1.removeNode(node)
    }

    val origChannels = graph.getEdgeSet[Edge].map(e => BinaryData(e.getId)).toSet
    val newChannels = graph1.getEdgeSet[Edge].map(e => BinaryData(e.getId)).toSet
    val updates1 = updates.collect {
      case u@routing_table_update(channel, OPEN) if !origChannels.contains(channel.channelId) && newChannels.contains(channel.channelId) => u
      case u@routing_table_update(channel, CLOSE) if origChannels.contains(channel.channelId) && !newChannels.contains(channel.channelId) => u
    }
    (graph1, updates1, dijkstra)
  }

  def findRoute(dijkstra: Dijkstra, source: MultiNode, target: MultiNode): (Seq[channel_desc], Seq[bitcoin_pubkey]) = {
    // make sure that the precomputed dijkstra was for the same source
    require(dijkstra.getSource[MultiNode] == source)
    // note: dijkstra path are given in reverse orders
    val channels = dijkstra.getPathEdges[Edge](target).map(edge => edge2ChannelDesc(edge)).toSeq.reverse
    val nodes = dijkstra.getPathNodes[MultiNode](target).map(node => node2pubkey(node)).toSeq.reverse
    (channels, nodes)
  }

  def buildOnion(nodes: Seq[bitcoin_pubkey], msg: neighbor_onion): neighbor_onion = {
    nodes.reverse.foldLeft(msg) {
      case (o, next) => neighbor_onion(Forward(beacon_forward(next, o)))
    }
  }

  def neighbor2channel(node_id: bitcoin_pubkey, neighbors: List[Neighbor]): Option[ActorRef] =
    neighbors.find(_.node_id == node_id).map(_.connection)

  def distance(a: BinaryData, b: BinaryData): BigInt = {
    require(a.length == b.length)
    val c = new Array[Byte](a.length)
    for (i <- 0 until a.length) c(i) = ((a.data(i) ^ b.data(i)) & 0xff).toByte
    BigInt(1, c)
  }

}