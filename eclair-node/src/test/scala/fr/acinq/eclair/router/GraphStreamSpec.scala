package fr.acinq.eclair.router

import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair._
import org.graphstream.algorithm.Dijkstra
import org.graphstream.graph._
import org.graphstream.graph.implementations._
import org.junit.runner.RunWith
import org.scalatest.FunSuiteLike
import org.scalatest.junit.JUnitRunner

import scala.collection.JavaConversions._

/**
  * Created by PM on 26/09/2016.
  */
@RunWith(classOf[JUnitRunner])
class GraphStreamSpec extends FunSuiteLike {

  test("remove node") {
    val g = new MultiGraph("test")
    val a = g.addNode[MultiNode]("a")
    val b = g.addNode[MultiNode]("b")
    val c = g.addNode[MultiNode]("c")
    g.addEdge("a-b", a, b)
    g.addEdge("b-c", b, c)
    assert(g.getNodeCount === 3)
    assert(g.getEdgeCount === 2)
    g.removeNode("b")
    assert(g.getNodeCount === 2)
    assert(g.getEdgeCount === 0)
  }

  test("get nonexisting node") {
    val g = new MultiGraph("test")
    assert(Option(g.getNode("a")).isEmpty)
  }

  test("get nonexisting edge") {
    val g = new MultiGraph("test")
    assert(Option(g.getEdge("a-b")).isEmpty)
  }

  test("remove nonexisting edge") {
    val g = new MultiGraph("test")
    intercept[ElementNotFoundException] {
      g.removeEdge("a-b")
    }
  }

  test("duplicate node") {
    val g = new MultiGraph("test")
    val a = g.addNode[MultiNode]("a")
    val b = g.addNode[MultiNode]("b")
    g.addEdge("a-b", a, b)
    intercept[IdAlreadyInUseException] {
      g.addNode[MultiNode]("a")
    }
  }

  test("simple dijsktra") {
    val g = new MultiGraph("test")
    val a = g.addNode[MultiNode]("a")
    val b = g.addNode[MultiNode]("b")
    val c = g.addNode[MultiNode]("c")
    val ab = g.addEdge[Edge]("a-b", a, b)
    val bc = g.addEdge[Edge]("b-c", b, c)
    val dijkstra = new Dijkstra()
    dijkstra.init(g)
    dijkstra.setSource(a)
    dijkstra.compute()
    assert(dijkstra.getPathNodes[MultiNode](c).toList.reverse === a :: b :: c :: Nil)
    assert(dijkstra.getPathEdges[Edge](c).toList.reverse === ab :: bc :: Nil)
  }

  test("simple dijsktra after graph copy") {
    val g = new MultiGraph("test")
    val a = g.addNode[MultiNode]("a")
    val b = g.addNode[MultiNode]("b")
    val c = g.addNode[MultiNode]("c")
    val ab = g.addEdge[Edge]("a-b", a, b)
    val bc = g.addEdge[Edge]("b-c", b, c)

    val dijkstra = new Dijkstra()
    dijkstra.init(g)
    dijkstra.setSource(a)
    dijkstra.compute()
    assert(dijkstra.getPathNodes[MultiNode](c).toList.reverse === a :: b :: c :: Nil)
    assert(dijkstra.getPathEdges[Edge](c).toList.reverse === ab :: bc :: Nil)

    val g1 = FlareRouter.copy(g)
    val a1 = g1.getNode[MultiNode]("a")
    val b1 = g1.getNode[MultiNode]("b")
    val c1 = g1.getNode[MultiNode]("c")
    val ab1 = g1.getEdge[Edge]("a-b")
    val bc1 = g1.getEdge[Edge]("b-c")

    val dijkstra1 = new Dijkstra()
    dijkstra1.init(g1)
    dijkstra1.setSource(a1)
    dijkstra1.compute()
    assert(dijkstra1.getPathNodes[MultiNode](c1).toList.reverse === a1 :: b1 :: c1 :: Nil)
    assert(dijkstra1.getPathEdges[Edge](c1).toList.reverse === ab1 :: bc1 :: Nil)
  }

  test("dijkstra to unreachable") {
    val g = new MultiGraph("test")
    val a = g.addNode[MultiNode]("a")
    val b = g.addNode[MultiNode]("b")
    val c = g.addNode[MultiNode]("c")
    g.addEdge("a-b", a, b)

    val dijkstra = new Dijkstra()
    dijkstra.init(g)
    dijkstra.setSource(a)
    dijkstra.compute()
    assert(dijkstra.getPathLength(c) === Double.PositiveInfinity)
  }

  test("node factory") {
    val g = new MultiGraph("test")
    case class MyNode(_graph: AbstractGraph, _id: String) extends MultiNode(_graph, _id) {
      val pubkey = bin2pubkey(BinaryData(_id))
    }
    val nodeFactory = new NodeFactory[MyNode] {
      override def newInstance(id: String, graph: Graph): MyNode = MyNode(graph.asInstanceOf[AbstractGraph], id)
    }
    g.setNodeFactory(nodeFactory)
    val node = g.addNode[MyNode]("010203")
    assert(node === MyNode(g, "010203"))
  }

  test("edge factory") {
    val g = new MultiGraph("test")
    case class MyEdge(_id: String, _source: Node, _target: Node, _directed: Boolean) extends AbstractEdge(_id, _source.asInstanceOf[AbstractNode], _target.asInstanceOf[AbstractNode], _directed) {
      //val pubkey = bin2pubkey(BinaryData(_id))
    }
    val edgeFactory = new EdgeFactory[MyEdge] {
      override def newInstance(id: String, src: Node, dst: Node, directed: Boolean): MyEdge = MyEdge(id, src, dst, directed)
    }
    g.setEdgeFactory(edgeFactory)
    val a = g.addNode[Node]("a")
    val b = g.addNode[Node]("b")
    val edge = g.addEdge[MyEdge]("a-b", a, b)
    assert(edge === MyEdge("a-b", a, b, false))
  }

  test("clone graph") {
    val g1 = new MultiGraph("test")
    val a = g1.addNode[MultiNode]("a")
    val b = g1.addNode[MultiNode]("b")
    val c = g1.addNode[MultiNode]("c")
    g1.addEdge("a-b", a, b)
    g1.addEdge("b-c", b, c)
    val g2 = FlareRouter.copy(g1)
  }

}
