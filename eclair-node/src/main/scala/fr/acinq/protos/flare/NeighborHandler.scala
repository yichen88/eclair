package fr.acinq.protos.flare

import akka.actor.{Actor, ActorLogging, ActorRef}
import org.jgrapht.alg.DijkstraShortestPath
import org.jgrapht.graph.{DefaultEdge, SimpleGraph}

/**
  * Created by PM on 08/09/2016.
  */
class NeighborHandler(radius: Int) extends Actor with ActorLogging {

  import NeighborHandler._

  override def receive: Receive = main(RoutingTable(self.path.parent.name, Set()), Nil)

  def main(table: RoutingTable, adjacent: List[ActorRef]): Receive = {
    case ('newchannel, them: ActorRef) =>
      them ! NeighborHello(table)
      val desc = ChannelDesc.from(self.path.parent.name, them.path.parent.name)
      val updates = RoutingTableUpdate(desc, OPEN) :: Nil
      val (table1, updates1) = include(table, updates, radius)
      if (!updates1.isEmpty) adjacent.foreach(_ ! NeighborUpdate(updates1))
      log.info(s"table is now $table1")
      context become main(table1, adjacent :+ them)
    case msg@NeighborHello(table1) =>
      log.debug(s"received $msg from $sender")
      context become main(merge(table, table1, radius), adjacent)
    case msg@NeighborUpdate(updates) =>
      log.debug(s"received $msg from $sender")
      val (table1, updates1) = include(table, updates, radius)
      if (!updates1.isEmpty) adjacent.foreach(_ ! NeighborUpdate(updates1))
      log.info(s"table is now $table1")
      context become main(table1, adjacent)
    case msg@NeighborRst() =>
      log.debug(s"received $msg from $sender")
      sender ! NeighborHello(table)
  }

}


object NeighborHandler {

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


  trait NeighborMessage

  case class NeighborHello(table: RoutingTable) extends NeighborMessage

  case class NeighborUpdate(updates: List[RoutingTableUpdate]) extends NeighborMessage

  case class NeighborRst() extends NeighborMessage


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

}