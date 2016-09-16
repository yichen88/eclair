package fr.acinq.eclair.router

import java.math.BigInteger

import akka.actor.{Actor, ActorLogging, ActorSelection, Props}
import akka.pattern._
import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair._
import fr.acinq.eclair.channel.{ChannelChangedState, DATA_NORMAL, NORMAL}
import lightning._
import lightning.neighbor_onion.Next.{Ack, Forward, Req}
import lightning.routing_table_update.update_type._
import org.jgrapht.alg.DijkstraShortestPath
import org.jgrapht.graph.{DefaultEdge, SimpleGraph}

import scala.collection.JavaConversions._
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Random, Success, Try}

/**
  * Created by PM on 08/09/2016.
  */
class FlareRouter(radius: Int, beaconCount: Int) extends Actor with ActorLogging {

  context.system.eventStream.subscribe(self, classOf[ChannelChangedState])

  import scala.concurrent.ExecutionContext.Implicits.global
  
  context.system.scheduler.schedule(10 seconds, 20 seconds, self, 'tick_beacons)

  import FlareRouter._

  val myself = Globals.Node.publicKey

  override def receive: Receive = main(new SimpleGraph[BinaryData, NamedEdge](classOf[NamedEdge]), Map(), Nil, Set())

  def main(graph: SimpleGraph[BinaryData, NamedEdge], adjacent: Map[BinaryData, (channel_desc, ActorSelection)], updatesBatch: List[routing_table_update], beacons: Set[Beacon]): Receive = {
    case ChannelChangedState(channel, theirNodeId, _, NORMAL, d: DATA_NORMAL) =>
      val neighbor = context.actorSelection(channel.path.parent)
      val channelDesc = channel_desc(d.commitments.anchorId, Globals.Node.publicKey, theirNodeId)
      val updates = routing_table_update(channelDesc, OPEN) :: Nil
      val (graph1, updates1) = include(myself, graph, updates, radius, beacons.map(_.id))
      neighbor ! neighbor_hello(graph2table(graph1))
      log.debug(s"graph is now ${graph2string(graph1)}")
      context.system.scheduler.scheduleOnce(200 millis, self, 'tick_updates)
      context become main(graph1, adjacent + (sha2562bin(channelDesc.channelId) -> (channelDesc, neighbor)), updatesBatch ++ updates1, beacons)
    case msg@neighbor_hello(table1) =>
      log.debug(s"received $msg from $sender")
      val graph1 = merge(myself, graph, table1, radius, beacons.map(_.id))
      log.debug(s"graph is now ${graph2string(graph1)}")
      context become main(graph1, adjacent, updatesBatch, beacons)
    case msg@neighbor_update(updates) =>
      log.debug(s"received $msg from $sender")
      val (graph1, updates1) = include(myself, graph, updates, radius, beacons.map(_.id))
      // we send beacon req to every discovered node
      val new_nodes = graph1.vertexSet().toSet -- graph.vertexSet().toSet
      for (node <- new_nodes) {
        log.debug(s"sending beacon_req message to new node $node")
        val (channels1, route) = findRoute(graph1, myself, node)
        send(route, adjacent, neighbor_onion(Req(beacon_req(myself, channels1))))
      }
      log.debug(s"graph is now ${graph2string(graph1)}")
      context.system.scheduler.scheduleOnce(200 millis, self, 'tick_updates)
      context become main(graph1, adjacent, updatesBatch ++ updates1, beacons)
    case msg@neighbor_reset(channel_ids) =>
      log.debug(s"received neighbor_reset from $sender with ${channel_ids.size} channels")
      val diff = graph2table(graph).channels.filterNot(c => channel_ids.contains(c.channelId)).map(c => routing_table_update(c, OPEN))
      log.debug(s"sending back ${diff.size} channels to $sender")
      sender ! neighbor_update(diff)
    case 'tick_updates if !updatesBatch.isEmpty =>
      adjacent.values.foreach(_._2 ! neighbor_update(updatesBatch))
      context become main(graph, adjacent, Nil, beacons)
    case 'tick_updates => // nothing to do
    case 'tick_beacons =>
      for (node <- Random.shuffle(graph.vertexSet().toSet - myself).take(1)) {
        log.debug(s"sending beacon_req message to random node $node")
        val (channels1, route) = findRoute(graph, myself, node)
        send(route, adjacent, neighbor_onion(Req(beacon_req(myself, channels1))))
      }
    case msg@neighbor_onion(onion) =>
      (onion: @unchecked) match {
        case lightning.neighbor_onion.Next.Forward(next) =>
          //log.debug(s"forwarding $msg to ${next.node}")
          val neighbor = next.node
          neighbor2channel(neighbor, adjacent) match {
            case Some(channel) => channel ! next.onion
            case None => log.error(s"could not find channel for neighbor $neighbor, cannot forward $msg")
          }
        case lightning.neighbor_onion.Next.Req(req) => self ! req
        case lightning.neighbor_onion.Next.Ack(ack) => self ! ack
      }
    case msg@beacon_req(origin, channels) =>
      log.debug(s"received beacon_req msg from $origin with channels=${channels2string(channels)}")
      val nodes: Set[BinaryData] = graph.vertexSet().toSet - origin
      val distances = nodes.map(node => (node, distance(origin, node)))
      val selected = distances.toList.sortBy(_._2).map(_._1)
      // adding routes to my table so that I can reply to the origin
      val updates = channels.map(channelDesc => routing_table_update(channelDesc, OPEN)).toList
      // large radius so that we don't prune the beacon (it's ok if it is remote)
      val (graph1, _) = include(myself, graph, updates, 100, Set())
      // TODO : we should check that origin has not been pruned
      selected.headOption match {
        case Some(alternate) if alternate != myself =>
          // we reply with a better beacon and give them a route
          // maybe not the shorted route but we want to be in it (incentive)
          val (channels1, _) = findRoute(graph1, myself, alternate)
          val hops = channels.size + channels1.size
          if (hops < 100) {
            log.debug(s"recommending alternate $alternate to $origin (hops=$hops)")
            val (_, route) = findRoute(graph1, myself, origin)
            send(route, adjacent, neighbor_onion(Ack(beacon_ack(myself, Some(alternate), channels ++ channels1))))
          } else {
            log.debug(s"alternate $alternate was better but it is too far from $origin (hops=$hops)")
            val (channels2, route) = findRoute(graph1, myself, origin)
            send(route, adjacent, neighbor_onion(Ack(beacon_ack(myself, None, channels2))))
          }
        case _ =>
          // we accept to be their beacon
          val (channels2, route) = findRoute(graph1, myself, origin)
          send(route, adjacent, neighbor_onion(Ack(beacon_ack(myself, None, channels2))))
      }
    case msg@beacon_ack(origin, alternative_opt, channels) =>
      log.debug(s"received beacon_ack msg from $origin with alternative=$alternative_opt channels=${channels2string(channels)}")
      val updates = channels.map(channelDesc => routing_table_update(channelDesc, OPEN)).toList
      val (graph1, _) = include(myself, graph, updates, 100, Set())
      // TODO : we should check that origin/alternative have not been pruned
      val beacons1 = alternative_opt match {
        // order matters!
        case Some(alternative) if distance(myself, alternative).compareTo(distance(myself, origin)) > 0 =>
          log.warning(s"$origin is lying ! dist(us, alt) > dist(us, origin)")
          // we should probably ignore whatever they are sending us
          beacons
        case Some(alternative) if beacons.size < beaconCount =>
          log.debug(s"sending alternative $alternative a beacon_req and adding origin $origin as beacon in the meantime")
          val (channels1, route) = findRoute(graph1, myself, alternative)
          send(route, adjacent, neighbor_onion(Req(beacon_req(myself, channels1))))
          val (channels2, _) = findRoute(graph1, myself, origin)
          beacons + Beacon(origin, distance(myself, origin), channels2.size)
        case Some(alternative) =>
          log.debug(s"sending alternative $alternative a beacon_req")
          val (channels1, route) = findRoute(graph1, myself, alternative)
          send(route, adjacent, neighbor_onion(Req(beacon_req(myself, channels1))))
          beacons
        case None if beacons.map(_.id).contains(origin) =>
          log.debug(s"we already have beacon $origin")
          beacons
        case None if beacons.size < beaconCount =>
          log.info(s"adding $origin as a beacon")
          val (channels1, _) = findRoute(graph1, myself, origin)
          beacons + Beacon(origin, distance(myself, origin), channels1.size)
        case None if beacons.exists(b => b.distance.compareTo(distance(myself, origin)) > 0) =>
          val deprecatedBeacon = beacons.find(b => b.distance.compareTo(distance(myself, origin)) > 0).get
          log.info(s"replacing $deprecatedBeacon by $origin")
          val (channels1, _) = findRoute(graph1, myself, origin)
          beacons - deprecatedBeacon + Beacon(origin, distance(myself, origin), channels1.size)
        case None =>
          log.debug(s"ignoring beacon candidate $origin")
          beacons
      }
      // this will cause old beacons to be removed from the graph
      val (graph2, _) = include(myself, graph1, Nil, radius, beacons1.map(_.id))
      log.debug(s"graph is now ${graph2string(graph2)}")
      log.debug(s"my beacons are now ${beacons1.map(b => s"${pubkey2string(b.id)}(${b.hops})").mkString(",")}")
      context become main(graph2, adjacent, updatesBatch, beacons1)
    case RouteRequest(target, targetTable) =>
      val g1 = graph.clone().asInstanceOf[SimpleGraph[BinaryData, NamedEdge]]
      val g2 = merge(myself, g1, targetTable, 100, Set())
      Future(findRoute(g2, myself, target)) map (r => RouteResponse(r._2)) pipeTo sender
    case 'network =>
      sender ! graph2table(graph).channels
    case 'beacons =>
      sender ! beacons
  }

  def send(route: Seq[BinaryData], adjacent: Map[BinaryData, (channel_desc, ActorSelection)], msg: neighbor_onion): Unit = {
    val onion = buildOnion(route.drop(2), msg)
    val neighbor = route(1)
    neighbor2channel(neighbor, adjacent) match {
      case Some(actorSelection) => actorSelection ! onion
      case None => log.error(s"could not find channel for neighbor $neighbor")
    }
  }

}


object FlareRouter {

  def props(radius: Int, beaconCount: Int) = Props(classOf[FlareRouter], radius, beaconCount)

  case class NamedEdge(val id: BinaryData) extends DefaultEdge {
    override def toString: String = id.toString()
  }

  case class Beacon(id: BinaryData, distance: BigInteger, hops: Int)

  // @formatter:off
  case class RouteRequest(target: BinaryData, targetTable: routing_table)
  case class RouteResponse(route: Seq[BinaryData])
  // @formatter:on

  def graph2table(graph: SimpleGraph[BinaryData, NamedEdge]): routing_table = {
    val channels = graph.edgeSet().map(edge => channel_desc(edge.id, graph.getEdgeSource(edge), graph.getEdgeTarget(edge)))
    routing_table(channels.toSeq)
  }

  def graph2string(graph: SimpleGraph[BinaryData, NamedEdge]): String =
    s"node_count=${graph.vertexSet.size} edges: " + channels2string(graph.edgeSet().map(edge => channel_desc(edge.id, graph.getEdgeSource(edge), graph.getEdgeTarget(edge))).toSeq)

  def pubkey2string(pubkey: BinaryData): String =
    pubkey.toString().take(6)

  def channels2string(channels: Seq[channel_desc]): String =
    channels.map(c => s"${pubkey2string(c.nodeA)}->${pubkey2string(c.nodeB)}").mkString(" ")

  def merge(myself: BinaryData, graph: SimpleGraph[BinaryData, NamedEdge], table2: routing_table, radius: Int, beacons: Set[BinaryData]): SimpleGraph[BinaryData, NamedEdge] = {
    val updates = table2.channels.map(c => routing_table_update(c, OPEN))
    include(myself, graph, updates, radius, beacons)._1
  }

  def include(myself: BinaryData, graph: SimpleGraph[BinaryData, NamedEdge], updates: Seq[routing_table_update], radius: Int, beacons: Set[BinaryData]): (SimpleGraph[BinaryData, NamedEdge], Seq[routing_table_update]) = {
    val graph1 = graph.clone().asInstanceOf[SimpleGraph[BinaryData, NamedEdge]]
    updates.collect {
      case routing_table_update(channel, OPEN) =>
        graph1.addVertex(channel.nodeA)
        graph1.addVertex(channel.nodeB)
        graph1.addEdge(channel.nodeA, channel.nodeB, NamedEdge(channel.channelId))
      case routing_table_update(channel, CLOSE) =>
        graph1.removeEdge(NamedEdge(channel.channelId))
    }
    // we whitelist all nodes on the path to beacons
    val whitelist: Set[BinaryData] = beacons.map(beacon => Try(findRoute(graph1, myself, beacon))).collect {
      case Success((_, nodes)) => nodes
    }.flatten

    val distances = (graph1.vertexSet() -- whitelist - myself).collect {
      case vertex: BinaryData if vertex != myself => (vertex, Try(new DijkstraShortestPath(graph1, myself, vertex).getPath.getEdgeList.size()))
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

  def findRoute(graph: SimpleGraph[BinaryData, NamedEdge], source: BinaryData, target: BinaryData): (Seq[channel_desc], Seq[BinaryData]) = {
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

  def buildOnion(nodes: Seq[BinaryData], msg: neighbor_onion): neighbor_onion = {
    nodes.reverse.foldLeft(msg) {
      case (o, next) => neighbor_onion(Forward(beacon_forward(next, o)))
    }
  }

  def neighbor2channel(neighbor: BinaryData, adjacent: Map[BinaryData, (channel_desc, ActorSelection)]): Option[ActorSelection] =
    adjacent.values.find(v => pubkey2bin(v._1.nodeA) == neighbor || pubkey2bin(v._1.nodeB) == neighbor).map(_._2)

  def distance(a: BinaryData, b: BinaryData): BigInteger = {
    require(a.length == b.length)
    val c = new Array[Byte](a.length)
    for (i <- 0 until a.length) c(i) = ((a.data(i) ^ b.data(i)) & 0xff).toByte
    new BigInteger(1, c)
  }

}