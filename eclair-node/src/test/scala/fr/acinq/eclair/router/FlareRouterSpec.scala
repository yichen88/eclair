package fr.acinq.eclair.router

import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair._
import fr.acinq.eclair.router.FlareRouter._
import lightning.neighbor_onion.Next.{Forward, Req}
import lightning._
import lightning.routing_table_update.update_type.OPEN
import org.jgrapht.graph.SimpleGraph
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

/**
  * Created by PM on 09/09/2016.
  */
@RunWith(classOf[JUnitRunner])
class FlareRouterSpec extends FunSuite {

  test("add channel") {
    val myself = BinaryData("01")
    val graph = new SimpleGraph[BinaryData, NamedEdge](classOf[NamedEdge])
    val channel = channel_desc(sha256_hash(1, 1, 2, 2), myself, BinaryData("02"))
    val updates = routing_table_update(channel, OPEN) :: Nil
    val (graph1, updates1) = include(myself, graph, updates, 3)
    assert(graph1.containsVertex(channel.nodeA))
    assert(graph1.containsVertex(channel.nodeB))
    assert(graph1.containsEdge(NamedEdge(channel.channelId)))
    assert(updates1 == updates)
  }

  test("ignore channel > radius") {
    val myself = BinaryData("01")
    val graph = new SimpleGraph[BinaryData, NamedEdge](classOf[NamedEdge])
    val channel1 = channel_desc(sha256_hash(1, 1, 2, 2), myself, BinaryData("02"))
    val channel2 = channel_desc(sha256_hash(2, 2, 3, 3), BinaryData("02"), BinaryData("03"))
    val channel3 = channel_desc(sha256_hash(3, 3, 4, 4), BinaryData("03"), BinaryData("04"))
    val updates = routing_table_update(channel1, OPEN) :: routing_table_update(channel2, OPEN) :: routing_table_update(channel3, OPEN) :: Nil
    val (graph1, updates1) = include(myself, graph, updates, 2)
    assert(graph1.containsVertex(BinaryData("01")))
    assert(graph1.containsVertex(BinaryData("02")))
    assert(graph1.containsVertex(BinaryData("03")))
    assert(!graph1.containsVertex(BinaryData("04")))
    assert(updates1 == updates.dropRight(1))
  }

  test("build onion") {
    val msg = neighbor_onion(Req(beacon_req(BinaryData("01"))))
    val onion = buildOnion(BinaryData("03") :: BinaryData("04") :: Nil, msg)
    assert(onion == neighbor_onion(Forward(beacon_forward(BinaryData("03"), neighbor_onion(Forward(beacon_forward(BinaryData("04"), msg)))))))
  }

  test("graph clone") {
    val g1 = new SimpleGraph[BinaryData, NamedEdge](classOf[NamedEdge])
    g1.addVertex(BinaryData("01"))
    g1.addVertex(BinaryData("02"))
    g1.addVertex(BinaryData("03"))
    g1.addEdge(BinaryData("01"), BinaryData("02"), NamedEdge("0102"))
    g1.addEdge(BinaryData("02"), BinaryData("03"), NamedEdge("0203"))

    val g2 = g1.clone()
    assert(g1 == g2)

  }

}
