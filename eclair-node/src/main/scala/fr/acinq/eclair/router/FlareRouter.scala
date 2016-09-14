package fr.acinq.eclair.router

import java.math.BigInteger

import akka.actor.{Actor, ActorLogging, ActorSelection, Props}
import akka.pattern._
import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair._
import fr.acinq.eclair.channel.{ChannelChangedState, DATA_NORMAL, NORMAL}
import lightning._
import lightning.neighbor_onion.Next
import lightning.neighbor_onion.Next.{Ack, Forward, Req}
import lightning.routing_table_update.update_type._
import org.jgrapht.alg.DijkstraShortestPath
import org.jgrapht.graph.{DefaultEdge, SimpleGraph}

import scala.collection.JavaConversions._
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/**
  * Created by PM on 08/09/2016.
  */
class FlareRouter(radius: Int, beaconCount: Int) extends Actor with ActorLogging {

  context.system.eventStream.subscribe(self, classOf[ChannelChangedState])

  import scala.concurrent.ExecutionContext.Implicits.global

  context.system.scheduler.schedule(10 seconds, 1 minute, self, 'tick_beacons)

  import FlareRouter._

  val myself = Globals.Node.publicKey

  override def receive: Receive = main(new SimpleGraph[BinaryData, NamedEdge](classOf[NamedEdge]), Map(), Nil, Nil)

  def main(graph: SimpleGraph[BinaryData, NamedEdge], adjacent: Map[BinaryData, (channel_desc, ActorSelection)], updatesBatch: List[routing_table_update], beacons: List[bitcoin_pubkey]): Receive = {
    case ChannelChangedState(channel, theirNodeId, _, NORMAL, d: DATA_NORMAL) =>
      val neighbor = context.actorSelection(channel.path.parent)
      // TODO : should we include the channel just created in the table?
      neighbor ! neighbor_hello(graph2table(graph))
      val channelDesc = channel_desc(d.commitments.anchorId, Globals.Node.publicKey, theirNodeId)
      val updates = routing_table_update(channelDesc, OPEN) :: Nil
      val (graph1, updates1) = include(myself, graph, updates, radius)
      log.info(s"graph is now $graph1")
      context.system.scheduler.scheduleOnce(200 millis, self, 'tick_updates)
      context become main(graph1, adjacent + (sha2562bin(channelDesc.channelId) -> (channelDesc, neighbor)), updatesBatch ++ updates1, beacons)
    case msg@neighbor_hello(table1) =>
      log.debug(s"received $msg from $sender")
      context become main(merge(myself, graph, table1, radius), adjacent, updatesBatch, beacons)
    case msg@neighbor_update(updates) =>
      log.debug(s"received $msg from $sender")
      val (graph1, updates1) = include(myself, graph, updates, radius)
      log.info(s"graph is now $graph1")
      context.system.scheduler.scheduleOnce(200 millis, self, 'tick_updates)
      context become main(graph1, adjacent, updatesBatch ++ updates1, beacons)
    case msg@neighbor_reset() =>
      log.debug(s"received $msg from $sender")
      sender ! neighbor_hello(graph2table(graph))
    case 'tick_updates if !updatesBatch.isEmpty =>
      adjacent.values.foreach(_._2 ! neighbor_update(updatesBatch))
      context become main(graph, adjacent, Nil, beacons)
    case 'tick_updates => // nothing to do
    case 'tick_beacons =>
      val nodes: Set[BinaryData] = graph.vertexSet().toSet - myself
      val distances = nodes.map(node => (node, distance(myself, node)))
      val selected = distances.toList.sortBy(_._2).map(_._1).take(beaconCount)
      selected.foreach(node => {
        log.debug(s"sending BeaconReq message to ${selected.mkString(",")}")
        val (channelId, onion) = prepareSend(myself, node, graph, neighbor_onion(Req(beacon_req(myself))))
        adjacent(channelId)._2 ! onion
      })
    case msg@neighbor_onion(onion) =>
      (onion: @unchecked) match {
        case lightning.neighbor_onion.Next.Forward(next) =>
          log.debug(s"forwarding $msg to ${next.node}")
          val channel = adjacent.find(c => c._2._1.nodeA == next.node || c._2._1.nodeB == next.node).map(_._2._2).getOrElse(throw new RuntimeException(s"could not find neighbor ${next.node}"))
          channel ! next.onion
        case lightning.neighbor_onion.Next.Req(req) => self ! req
        case lightning.neighbor_onion.Next.Ack(ack) => self ! ack
        case lightning.neighbor_onion.Next.Set(set) => self ! set
      }
    case msg@beacon_req(origin, channels) =>
      log.debug(s"received $msg from $origin")
      val nodes: Set[BinaryData] = graph.vertexSet().toSet - origin
      val distances = nodes.map(node => (node, distance(origin, node)))
      val selected = distances.toList.sortBy(_._2).map(_._1)
      selected.headOption match {
        case Some(node) if node != myself =>
          // we reply with a better beacon
          val (channels, _) = findRoute(graph, myself, node)
          val (channelId, onion) = prepareSend(myself, origin, graph, neighbor_onion(Ack(beacon_ack(myself, Some(node), channels))))
          adjacent(channelId)._2 ! onion
        case _ if channels != Nil =>
          // adding routes to my table so that I can reply to the origin
          val updates = channels.map(channelDesc => routing_table_update(channelDesc, OPEN)).toList
          // large radius so that we don't prune the beacon (it's ok if it is remote)
          val (graph1, _) = include(myself, graph, updates, 1000)
          val (channelId, onion) = prepareSend(myself, origin, graph1, neighbor_onion(Ack(beacon_ack(myself))))
          adjacent(channelId)._2 ! onion
          context become main(graph1, adjacent, updatesBatch, beacons)
        case _ =>
          val (channelId, onion) = prepareSend(myself, origin, graph, neighbor_onion(Ack(beacon_ack(myself))))
          adjacent(channelId)._2 ! onion
      }
    case msg@beacon_ack(origin, alternative_opt, channels) =>
      log.debug(s"received $msg from $origin")
      alternative_opt match {
        case None =>
          log.info(s"$origin is my beacon")
          val (channelId, onion) = prepareSend(myself, origin, graph, neighbor_onion(Next.Set(beacon_set(myself))))
          adjacent(channelId)._2 ! onion
          context become main(graph, adjacent, updatesBatch, beacons :+ origin)
        case Some(beacon) =>
          log.debug(s"looks like there is a better beacon $beacon")
          // adding routes to my table so that I can reach the beacon candidate later
          val updates = channels.map(channelDesc => routing_table_update(channelDesc, OPEN)).toList
          // large radius so that we don't prune the beacon (it's ok if it is remote)
          val (graph1, _) = include(myself, graph, updates, 1000)
          val (channels1, _) = findRoute(graph1, myself, beacon)
          val (channelId, onion) = prepareSend(myself, beacon, graph1, neighbor_onion(Req(beacon_req(myself, channels1))))
          adjacent(channelId)._2 ! onion
          context become main(graph1, adjacent, updatesBatch, beacons)
      }
    case msg@beacon_set(origin) =>
      log.info(s"I am the beacon of $origin")
    case RouteRequest(target, targetTable) =>
      val g1 = graph.clone().asInstanceOf[SimpleGraph[BinaryData, NamedEdge]]
      val g2 = merge(myself, g1, targetTable, 100)
      Future(findRoute(g2, myself, target)) map (r => RouteResponse(r._2)) pipeTo sender
    case 'network =>
      sender ! graph2table(graph).channels
    case 'beacons =>
      sender ! beacons.map(pubkey2bin)
  }

}


object FlareRouter {

  def props(radius: Int, beaconCount: Int) = Props(classOf[FlareRouter], radius, beaconCount)

  case class NamedEdge(val id: BinaryData) extends DefaultEdge

  // @formatter:off
  case class RouteRequest(target: BinaryData, targetTable: routing_table)
  case class RouteResponse(route: Seq[BinaryData])
  // @formatter:on

  //case class RoutingTable(myself: String, channels: Set[ChannelDesc]) {
  //  override def toString: String = s"[$myself] " + channels.toList.sortBy(_.a).map(c => s"${c.a}->${c.b}").mkString(" ")
  //}

  def graph2table(graph: SimpleGraph[BinaryData, NamedEdge]): routing_table = {
    val channels = graph.edgeSet().map(edge => channel_desc(edge.id, graph.getEdgeSource(edge), graph.getEdgeTarget(edge)))
    routing_table(channels.toSeq)
  }

  def merge(myself: BinaryData, graph: SimpleGraph[BinaryData, NamedEdge], table2: routing_table, radius: Int): SimpleGraph[BinaryData, NamedEdge] = {
    val updates = table2.channels.map(c => routing_table_update(c, OPEN))
    include(myself, graph, updates, radius)._1
  }

  def include(myself: BinaryData, graph: SimpleGraph[BinaryData, NamedEdge], updates: Seq[routing_table_update], radius: Int): (SimpleGraph[BinaryData, NamedEdge], Seq[routing_table_update]) = {
    val origChannels = graph.edgeSet().map(_.id)
    updates.collect {
      case routing_table_update(channel, OPEN) =>
        graph.addVertex(channel.nodeA)
        graph.addVertex(channel.nodeB)
        graph.addEdge(channel.nodeA, channel.nodeB, NamedEdge(channel.channelId))
      case routing_table_update(channel, CLOSE) =>
        graph.removeEdge(NamedEdge(channel.channelId))
    }
    val distances = graph.vertexSet().collect {
      case vertex: BinaryData if vertex != myself => (vertex, Try(new DijkstraShortestPath(graph, myself, vertex).getPath.getEdgeList.size()))
    }
    distances.collect {
      case (vertex, Failure(t)) => graph.removeVertex(vertex)
      case (vertex, Success(d)) if d > radius => graph.removeVertex(vertex)
    }
    val updates1 = updates.collect {
      case u@routing_table_update(channel, OPEN) if !origChannels.contains(channel.channelId) && graph.containsEdge(NamedEdge(channel.channelId)) => u
      case u@routing_table_update(channel, CLOSE) if origChannels.contains(channel.channelId) && !graph.containsEdge(NamedEdge(channel.channelId)) => u
    }
    (graph, updates1)
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

  def prepareSend(myself: BinaryData, target: BinaryData, graph: SimpleGraph[BinaryData, NamedEdge], msg: neighbor_onion): (BinaryData, neighbor_onion) = {
    val (channels, nodes) = findRoute(graph, myself, target)
    val channelId = channels.head.channelId
    val onion = buildOnion(nodes.drop(2), msg)
    (channelId, onion)
  }

  def distance(a: BinaryData, b: BinaryData): BigInteger = {
    require(a.length == b.length)
    //    require(b.length == 32)
    val c = new Array[Byte](a.length)
    for (i <- 0 until a.length) c(i) = ((a.data(i) ^ b.data(i)) & 0xff).toByte
    new BigInteger(1, c)
  }

}