package fr.acinq.eclair.router

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, File}
import java.math.BigInteger

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.testkit.TestKit
import akka.util.Timeout
import com.google.common.io.Files
import fr.acinq.bitcoin.{BinaryData, Crypto}
import fr.acinq.eclair._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.router.FlareRouter._
import lightning._
import org.graphstream.graph.Edge
import org.graphstream.graph.implementations.{MultiGraph, MultiNode}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, FunSuiteLike}

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Random

/**
  * Created by PM on 09/09/2016.
  */
@RunWith(classOf[JUnitRunner])
class FlareRouterSpec extends TestKit(ActorSystem("test")) with FunSuiteLike with BeforeAndAfterAll {

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  import FlareRouterSpec._

  /*def edge(bin: BinaryData): NamedEdge = {
    require(bin.size == 2)
    NamedEdge(sha256_hash(bin.data(0), bin.data(1), 0, 0))
  }

  test("add channel") {
    val myself = BinaryData("01")
    val graph = new SimpleGraph[bitcoin_pubkey, NamedEdge](classOf[NamedEdge])
    val channel = channel_desc(sha256_hash(1, 1, 2, 2), myself, BinaryData("02"))
    val updates = routing_table_update(channel, OPEN) :: Nil
    val (graph1, updates1) = include(myself, graph, updates, 3, Set())
    assert(graph1.containsVertex(channel.nodeA))
    assert(graph1.containsVertex(channel.nodeB))
    assert(graph1.containsEdge(NamedEdge(channel.channelId)))
    assert(updates1 == updates)
  }

  test("ignore channel > radius") {
    val myself = BinaryData("01")
    val graph = new SimpleGraph[bitcoin_pubkey, NamedEdge](classOf[NamedEdge])
    val channel1 = channel_desc(sha256_hash(1, 1, 2, 2), myself, BinaryData("02"))
    val channel2 = channel_desc(sha256_hash(2, 2, 3, 3), BinaryData("02"), BinaryData("03"))
    val channel3 = channel_desc(sha256_hash(3, 3, 4, 4), BinaryData("03"), BinaryData("04"))
    val updates = routing_table_update(channel1, OPEN) :: routing_table_update(channel2, OPEN) :: routing_table_update(channel3, OPEN) :: Nil
    val (graph1, updates1) = include(myself, graph, updates, 2, Set())
    assert(graph1.containsVertex(BinaryData("01")))
    assert(graph1.containsVertex(BinaryData("02")))
    assert(graph1.containsVertex(BinaryData("03")))
    assert(!graph1.containsVertex(BinaryData("04")))
    assert(updates1 == updates.dropRight(1))
  }

  test("build onion") {
    val msg = neighbor_onion(Req(beacon_req(BinaryData("01"))))
    val node3: bitcoin_pubkey = BinaryData("03")
    val node4: bitcoin_pubkey = BinaryData("04")
    val onion = buildOnion(node3 :: node4 :: Nil, msg)
    assert(onion == neighbor_onion(Forward(beacon_forward(BinaryData("03"), neighbor_onion(Forward(beacon_forward(BinaryData("04"), msg)))))))
  }

  test("graph clone") {
    val g1 = new SimpleGraph[bitcoin_pubkey, NamedEdge](classOf[NamedEdge])
    g1.addVertex(BinaryData("01"))
    g1.addVertex(BinaryData("02"))
    g1.addVertex(BinaryData("03"))
    g1.addEdge(BinaryData("01"), BinaryData("02"), edge("0102"))
    g1.addEdge(BinaryData("02"), BinaryData("03"), edge("0203"))

    val g2 = g1.clone()
    assert(g1 == g2)
  }

  test("include updates") {
    val nodeIds = (0 to 3).map(nodeId).map(bin2pubkey)
    val myself = nodeIds(0)
    val graph = new SimpleGraph[bitcoin_pubkey, NamedEdge](classOf[NamedEdge])
    /*
            0
           / \
          1   2
     */
    graph.addVertex(nodeIds(0))
    graph.addVertex(nodeIds(1))
    graph.addVertex(nodeIds(2))
    graph.addEdge(nodeIds(0), nodeIds(1), NamedEdge(channelId(nodeIds(0), nodeIds(1))))
    graph.addEdge(nodeIds(0), nodeIds(2), NamedEdge(channelId(nodeIds(0), nodeIds(2))))
    val updates = Seq(
      routing_table_update(channel_desc(channelId(nodeIds(3), nodeIds(1)), nodeIds(3), nodeIds(1)), OPEN),
      routing_table_update(channel_desc(channelId(nodeIds(1), nodeIds(0)), nodeIds(1), nodeIds(0)), OPEN)
    )
    val (graph1, _) = include(myself, graph, updates, 2, Set())
    assert(graph1.vertexSet().contains(nodeIds(3)))
  }*/

  test("basic routing and discovery") {
    val radius = 2
    val maxBeacons = 5
    val nodeIds = for (i <- 0 to 6) yield nodeId(i)
    val links = (0, 1) :: (0, 2) :: (1, 3) :: (1, 6) :: (2, 4) :: (4, 5) :: Nil
    val routers = nodeIds map (nodeId => system.actorOf(Props(new FlareRouter(nodeId, radius, maxBeacons, false)), nodeId.toString().take(6)))

    def createChannel(a: Int, b: Int): Unit = {
      routers(a) ! genChannelChangedState(routers(b), nodeIds(b), FlareRouterSpec.channelId(nodeIds(a), nodeIds(b)), 1000000000)
      routers(b) ! genChannelChangedState(routers(a), nodeIds(a), FlareRouterSpec.channelId(nodeIds(a), nodeIds(b)), 0)
    }

    links.foreach { case (a, b) => createChannel(a, b) }

    Thread.sleep(5000)

    implicit val timeout = Timeout(3 seconds)
    import system.dispatcher

    val futures = for (i <- 0 until nodeIds.length) yield (routers(i) ? 'info).mapTo[FlareInfo].map(info => i -> info)
    val future = Future.sequence(futures).map(_.toMap)
    val infos = Await.result(future, 3 seconds)
    assert(infos(0).neighbors === 2)
  }

  test("find remote beacons") {
    val length = 30
    val radius = 2
    val maxBeacons = 1
    val nodeIds = (0 to length).map(nodeId).map(bin2pubkey)
    val closestToFirst = nodeIds.sortBy(id => distance(nodeIds(0), id))
    val order = (closestToFirst.drop(1) :+ nodeIds(0)).reverse
    val links = order.sliding(2)
    // so we have A -> B -> C -> D -> .... -> Z with d(A,B) > d(A,C) > ... > d(A, Z)
    // in other words A's best beacon is Z
    val routers: Map[bitcoin_pubkey, ActorRef] = order.map(nodeId => (nodeId, system.actorOf(Props(new FlareRouter(nodeId, radius, maxBeacons, false))))).toMap
    def createChannel(a: bitcoin_pubkey, b: bitcoin_pubkey): Unit = {
      routers(a) ! genChannelChangedState(routers(b), b, FlareRouterSpec.channelId(a, b), 1000000000)
      routers(b) ! genChannelChangedState(routers(a), a, FlareRouterSpec.channelId(a, b), 0)
    }
    for (link <- links) createChannel(link.head, link.last)
    implicit val timeout = Timeout(1 second)
    awaitCond(Await.result(routers(order.head) ? 'beacons, 1 second).asInstanceOf[Set[Beacon]].map(_.id).contains(order.last), 10 seconds)
  }

  test("find directed route") {
    /*
            (10)      (2)       (5)
        A ---1--> B ---2--> C ---3--> D
         \        6(5)      ^
          \ (2)   v    (7)  |
           4----> E ----5---/
     */
    def channelId(i: Int) = sha256_hash(i, i, i, i)
    def nodeId(c: String) = bitcoin_pubkey(BinaryData(c * 33)) // "aa" => "aaaaaaaaaaa.....aaaaa"
    val states = channel_state_update(channelId(1), 0, nodeId("aa"), 10, 0) ::
      channel_state_update(channelId(1), 0, nodeId("bb"), 1, 0) ::
      channel_state_update(channelId(2), 0, nodeId("bb"), 2, 0) ::
      channel_state_update(channelId(2), 0, nodeId("cc"), 30, 0) ::
      channel_state_update(channelId(3), 0, nodeId("cc"), 5, 0) ::
      channel_state_update(channelId(3), 0, nodeId("dd"), 10, 0) ::
      channel_state_update(channelId(4), 0, nodeId("aa"), 2, 0) ::
      channel_state_update(channelId(4), 0, nodeId("ee"), 4, 0) ::
      channel_state_update(channelId(5), 0, nodeId("ee"), 7, 0) ::
      channel_state_update(channelId(5), 0, nodeId("cc"), 10, 0) ::
      channel_state_update(channelId(6), 0, nodeId("bb"), 5, 0) ::
      channel_state_update(channelId(6), 0, nodeId("ee"), 10, 0) :: Nil
    val path = findPaymentRoute(nodeId("aa"), nodeId("dd"), 4, states)
    assert(path === nodeId("aa") :: nodeId("bb") :: nodeId("ee") :: nodeId("cc") :: nodeId("dd") :: Nil)
  }

  test("display graph") {
    val g = new MultiGraph("test")
    val n01 = g.addNode[MultiNode]("01")
    val n02 = g.addNode[MultiNode]("02")
    val n03 = g.addNode[MultiNode]("03")
    val n04 = g.addNode[MultiNode]("04")
    val n05 = g.addNode[MultiNode]("05")
    val n06 = g.addNode[MultiNode]("06")
    val n07 = g.addNode[MultiNode]("07")
    val n08 = g.addNode[MultiNode]("08")
    val n09 = g.addNode[MultiNode]("09")
    def channelId(i: Int) = sha2562string(sha256_hash(i, i, i, i))
    g.addEdge(channelId(1), n01, n02, true)
    g.addEdge(channelId(2), n02, n03, true)
    g.addEdge(channelId(3), n03, n04, true)
    g.addEdge(channelId(4), n02, n05, true)
    g.addEdge(channelId(5), n05, n06, true)
    g.addEdge(channelId(6), n06, n07, true)
    g.addEdge(channelId(7), n01, n08, true)
    g.addEdge(channelId(8), n08, n09, true)
    val beacons: Set[Beacon] = Set(Beacon(bitcoin_pubkey(BinaryData("04")), null, 99), Beacon(bitcoin_pubkey(BinaryData("07")), null, 99))
    val dynamic: Map[ChannelOneEnd, channel_state_update] = Map(
      ChannelOneEnd(BinaryData(channelId(1)), BinaryData("01")) -> channel_state_update(BinaryData(channelId(1)), 0, BinaryData("01"), 90, 0),
      ChannelOneEnd(BinaryData(channelId(1)), BinaryData("02")) -> channel_state_update(BinaryData(channelId(1)), 0, BinaryData("02"), 10, 0)
    )

    val nodes = g.getNodeIterator[MultiNode].map(node => s""""${node.getId.take(6)}" [tooltip="${node.getId}"];""").mkString("\n")
    val centerColor = s""""${g.getNode[MultiNode](0).getId.take(6)}" [color=blue];"""
    val beaconsColor = beacons.map(beacon => s""""${g.getNode[MultiNode](pubkey2string(beacon.id)).getId.take(6)}" [color=red];""").mkString("\n")
    val edges = g.getEdgeSet[Edge]
      .flatMap(edge => {
        val src = ChannelOneEnd(BinaryData(edge.getId), BinaryData(edge.getSourceNode[MultiNode].getId))
        val srcAvail = dynamic.get(src).map(_.amountMsat)
        val tgt = ChannelOneEnd(BinaryData(edge.getId), BinaryData(edge.getTargetNode[MultiNode].getId))
        val tgtAvail = dynamic.get(tgt).map(_.amountMsat)
        s""" "${pubkey2string(src.node_id)}" -> "${pubkey2string(tgt.node_id)}" [fontsize=8 labeltooltip="${edge.getId}" label=${srcAvail.getOrElse("unknown")}];""" ::
          s""" "${pubkey2string(tgt.node_id)}" -> "${pubkey2string(src.node_id)}" [fontsize=8 labeltooltip="${edge.getId}" label=${tgtAvail.getOrElse("unknown")}];""" :: Nil
      }).mkString("\n")

    val dot =
      s"""digraph G {
          |rankdir=LR;
          |$nodes
          |$centerColor
          |$beaconsColor
          |$edges
          |}
    """.stripMargin

    println(dot)

    {
      import scala.sys.process._
      val input = new ByteArrayInputStream(dot.getBytes)
      val output = new ByteArrayOutputStream()
      "dot -Tpng" #< input #> output !

      //Files.write(img, new File("g.png"))
    }

    /*digraph G {
  rankdir=LR;
  "a" [color=blue];
  "a" [tooltip="titi"];
  "d" [color=red];
  "g" [color=red];
  "a" -> "b" [label = "90" ];
  "b" -> "a" [fontsize=8 label = "10" labeltooltip="toto"];
  "b" -> "c"
  "c" -> "d"
  "b" -> "e"
  "e" -> "f"
  "f" -> "g"
  "a" -> "h"
  "h" -> "i"
}*/
  }

}

object FlareRouterSpec {
  val random = new Random()

  def channelId(a: BinaryData, b: BinaryData): BinaryData = {
    if (Scripts.isLess(a, b)) Crypto.sha256(a ++ b) else Crypto.sha256(b ++ a)
  }

  def nodeId(i: Int): BinaryData = {
    val a = BigInteger.valueOf(i).toByteArray
    Crypto.sha256(a)
  }

  def genChannelChangedState(them: ActorRef, theirNodeId: BinaryData, channelId: BinaryData, available_amount: Int): ChannelChangedState =
    ChannelChangedState(null, them, theirNodeId, null, NORMAL, DATA_NORMAL(new Commitments(null, null,
      OurCommit(0, CommitmentSpec(Set(), 0, 0, 0, available_amount, 0), null),
      null, null, null, 0L, null, null, null, null) {
      override def anchorId: BinaryData = channelId // that's the only thing we need
    }, null, null))
}