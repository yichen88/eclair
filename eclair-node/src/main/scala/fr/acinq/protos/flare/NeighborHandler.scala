package fr.acinq.protos.flare

import akka.actor.{Actor, ActorLogging, ActorRef}
import org.jgrapht.alg.DijkstraShortestPath
import org.jgrapht.graph.{DefaultEdge, SimpleGraph}

import scala.collection.JavaConversions._
import scala.concurrent.duration._

/**
  * Created by PM on 08/09/2016.
  */
class NeighborHandler(radius: Int, beaconCount: Int) extends Actor with ActorLogging {

  import scala.concurrent.ExecutionContext.Implicits.global

  context.system.scheduler.schedule(7 seconds, 1 minute, self, 'tick_beacons)

  import NeighborHandler._

  override def receive: Receive = main(RoutingTable(self.path.parent.name, Set()), Nil, Nil, Nil)

  def main(table: RoutingTable, adjacent: List[ActorRef], updatesBatch: List[RoutingTableUpdate], beacons: List[String]): Receive = {
    case ('newchannel, them: ActorRef) =>
      them ! NeighborHello(table)
      val desc = ChannelDesc.from(self.path.parent.name, them.path.parent.name)
      val updates = RoutingTableUpdate(desc, OPEN) :: Nil
      val (table1, updates1) = include(table, updates, radius)
      log.info(s"table is now $table1")
      context.system.scheduler.scheduleOnce(200 millis, self, 'tick_updates)
      context become main(table1, adjacent :+ them, updatesBatch ++ updates1, beacons)
    case msg@NeighborHello(table1) =>
      log.debug(s"received $msg from $sender")
      context become main(merge(table, table1, radius), adjacent, updatesBatch, beacons)
    case msg@NeighborUpdate(updates) =>
      log.debug(s"received $msg from $sender")
      val (table1, updates1) = include(table, updates, radius)
      log.info(s"table is now $table1")
      context.system.scheduler.scheduleOnce(200 millis, self, 'tick_updates)
      context become main(table1, adjacent, updatesBatch ++ updates1, beacons)
    case msg@NeighborRst() =>
      log.debug(s"received $msg from $sender")
      sender ! NeighborHello(table)
    case 'tick_updates if !updatesBatch.isEmpty =>
      adjacent.foreach(_ ! NeighborUpdate(updatesBatch))
      context become main(table, adjacent, Nil, beacons)
    case 'tick_updates => // nothing to do
    case 'tick_beacons =>
      val nodes = table.channels.flatMap(c => Set(c.a, c.b)) - table.myself
      val distances = nodes.map(node => (node, distance(table.myself, node)))
      val selected = distances.toList.sortBy(_._2).map(_._1).take(beaconCount)
      selected.foreach(node => {
        log.debug(s"sending BeaconReq message to ${selected.mkString(",")}")
        sendTo(node, table, adjacent, BeaconReq(table.myself, None))
      })
    case msg@NeighborBeaconMessage(route, beaconMsg) =>
      route match {
        case neighbor :: rest =>
          log.debug(s"forwarding $msg to $neighbor")
          val channel = adjacent.find(_.path.parent.name == neighbor).getOrElse(throw new RuntimeException(s"could not find neighbor $neighbor"))
          channel ! msg.copy(route = rest)
        case Nil => self ! beaconMsg
      }
    case msg@BeaconReq(origin, updates_opt) =>
      log.debug(s"received $msg from $origin")
      val nodes = table.channels.flatMap(c => Set(c.a, c.b)) - origin
      val distances = nodes.map(node => (node, distance(origin, node)))
      val selected = distances.toList.sortBy(_._2).map(_._1)
      selected.headOption match {
        case Some(node) if node != table.myself =>
          // we reply with a better beacon
          val route = findRoute(table, node)
          sendTo(origin, table, adjacent, BeaconAck(table.myself, Some(route)))
        case _ if updates_opt.isDefined =>
          // adding routes to my table so that I can reply to the origin
          val updates = updates_opt.get.sliding(2).map(segment => RoutingTableUpdate(ChannelDesc.from(segment.head, segment.last), OPEN)).toList
          // large radius so that we don't prune the beacon (it's ok if it is remote)
          val (table1, _) = include(table, updates, 1000)
          sendTo(origin, table1, adjacent, BeaconAck(table.myself))
          context become main(table1, adjacent, updatesBatch, beacons)
        case _ =>
          sendTo(origin, table, adjacent, BeaconAck(table.myself))
      }
    case msg@BeaconAck(origin, alternative) =>
      log.debug(s"received $msg from $origin")
      alternative match {
        case None =>
          log.info(s"$origin is my beacon")
          sendTo(origin, table, adjacent, BeaconSet(table.myself))
          context become main(table, adjacent, updatesBatch, beacons :+ origin)
        case Some(route0) =>
          val beacon = route0.last
          log.debug(s"looks like there is a better beacon $beacon")
          // adding routes to my table so that I can reach the beacon candidate later
          val updates = route0.sliding(2).map(segment => RoutingTableUpdate(ChannelDesc.from(segment.head, segment.last), OPEN)).toList
          // large radius so that we don't prune the beacon (it's ok if it is remote)
          val (table1, _) = include(table, updates, 1000)
          val route = findRoute(table1, beacon)
          sendTo(beacon, table1, adjacent, BeaconReq(table.myself, Some(route)))
          context become main(table1, adjacent, updatesBatch, beacons)
      }
    case msg@BeaconSet(origin) =>
      log.info(s"I am the beacon of $origin")
    case msg => log.warning(s"unhandled $msg")
  }

}


object NeighborHandler {

  // @formatter:off

  case class ChannelDesc(a: String, b: String) {
    require(a < b)
  }

  object ChannelDesc {
    def from(a: String, b: String) = if (a < b) ChannelDesc(a, b) else ChannelDesc(b, a)
  }

  case class RoutingTable(myself: String, channels: Set[ChannelDesc]) {
    override def toString: String = s"[$myself] " + channels.toList.sortBy(_.a).map(c => s"${c.a}->${c.b}").mkString(" ")
  }

  trait UpdateType
  case object OPEN extends UpdateType
  case object CLOSE extends UpdateType
  case class RoutingTableUpdate(channel: ChannelDesc, updateType: UpdateType)

  sealed trait NeighborMessage
  case class NeighborHello(table: RoutingTable) extends NeighborMessage
  case class NeighborUpdate(updates: List[RoutingTableUpdate]) extends NeighborMessage
  case class NeighborRst() extends NeighborMessage
  case class NeighborBeaconMessage(route: List[String], msg: BeaconMessage) extends NeighborMessage

  sealed trait BeaconMessage
  case class BeaconReq(origin: String, howToReachMe: Option[List[String]]) extends BeaconMessage
  case class BeaconAck(origin: String, alternative: Option[List[String]] = None) extends BeaconMessage
  case class BeaconSet(origin: String) extends BeaconMessage

  // @formatter:on

  def prune(table: RoutingTable, newEdges: Set[String], radius: Int): RoutingTable = {
    val g = new SimpleGraph[String, DefaultEdge](classOf[DefaultEdge])
    table.channels.foreach(x => {
      g.addVertex(x.a)
      g.addVertex(x.b)
      g.addEdge(x.a, x.b, new DefaultEdge)
    })
    val distances = newEdges.map(edge => (edge, Option(new DijkstraShortestPath(g, table.myself, edge).getPath)))
    val channels1 = distances.foldLeft(table.channels) {
      case (channels, (_, Some(path))) if path.getEdgeList.size() <= radius => channels
      case (channels, (edge, _)) => channels.filterNot(c => c.a == edge || c.b == edge)
    }
    table.copy(channels = channels1)
  }

  def merge(ref: RoutingTable, table2: RoutingTable, radius: Int): RoutingTable = {
    val full = ref.copy(channels = ref.channels ++ table2.channels)
    val newEdges = table2.channels.map(_.a) ++ table2.channels.map(_.b) -- ref.channels.map(_.a) -- ref.channels.map(_.b)
    prune(full, newEdges, radius)
  }

  def include(ref: RoutingTable, updates: List[RoutingTableUpdate], radius: Int): (RoutingTable, List[RoutingTableUpdate]) = {
    val table1 = ref.copy(channels = updates.foldLeft(ref.channels) {
      case (channels, RoutingTableUpdate(channel, OPEN)) => channels + channel
      case (channels, RoutingTableUpdate(channel, CLOSE)) => channels - channel
    })
    val newEdges = table1.channels.map(_.a) ++ table1.channels.map(_.b) -- ref.channels.map(_.a) -- ref.channels.map(_.b)
    val table2 = prune(table1, newEdges, radius)
    val updates1 = updates.collect {
      case r@RoutingTableUpdate(channel, OPEN) if !ref.channels.contains(channel) && table2.channels.contains(channel) => r
      // TODO : handle case when there are multiple channels bewteen a and b and only one of them is closed
      case r@RoutingTableUpdate(channel, CLOSE) if ref.channels.contains(channel) && !table2.channels.contains(channel) => r
    }
    (table2, updates1)
  }

  def findRoute(table: RoutingTable, target: String): List[String] = {
    // building the onion
    val g = new SimpleGraph[String, DefaultEdge](classOf[DefaultEdge])
    table.channels.foreach(x => {
      g.addVertex(x.a)
      g.addVertex(x.b)
      g.addEdge(x.a, x.b, new DefaultEdge)
    })
    Option(new DijkstraShortestPath(g, table.myself, target).getPath) match {
      case Some(path) =>
        val route = path.getEdgeList.foldLeft(List(path.getStartVertex)) {
          case (rest :+ v, edge) if g.getEdgeSource(edge) == v => rest :+ v :+ g.getEdgeTarget(edge)
          case (rest :+ v, edge) if g.getEdgeTarget(edge) == v => rest :+ v :+ g.getEdgeSource(edge)
        }
        route
      case None => throw new RuntimeException(s"route not found to $target")
    }
  }

  def sendTo(node: String, table: RoutingTable, adjacent: List[ActorRef], msg: BeaconMessage) = {
    val route = findRoute(table, node)
    val neighbor = route.drop(1).head
    val channel = adjacent.find(_.path.parent.name == neighbor).getOrElse(throw new RuntimeException(s"could not find neighbor $neighbor"))
    channel ! NeighborBeaconMessage(route.drop(2), msg)
  }

  def distance(a: String, b: String): Int = a.getBytes.sum + b.getBytes.sum

}