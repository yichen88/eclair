package fr.acinq.eclair

import org.graphstream.algorithm.generator.WattsStrogatzGenerator
import org.graphstream.graph.{Edge, Node}
import org.graphstream.graph.implementations.SingleGraph

import scala.collection.JavaConversions._

object GenGraph extends App {
  var n = 0
  var k = 4
  var p = 0.2
  var show = false

  def usage = {
    println(
      """
        |options:
        |-n number of nodes
        |-k number of neighbours per node (default = 4)
        |-p rewiring probability (default = 0.2)
        |-show display thre resulting graph
      """.stripMargin)
  }

  def parse(arguments: List[String]): Unit = arguments match {
    case "-help" :: _ => usage; System.exit(1)
    case "-n" :: value :: tail => n = value.toInt; parse(tail)
    case "-k" :: value :: tail => k = value.toInt; parse(tail)
    case "-p" :: value :: tail => p = value.toDouble; parse(tail)
    case "-show" :: tail => show = true; parse(tail)
    case _ => ()
  }

  parse(args.toList)
  if (n == 0) {
    usage
    System.exit(1)
  }

  def genGraph(n: Int, k: Int, p: Double): SingleGraph = {
    val graph = new SingleGraph("This is a small world!")
    val gen = new WattsStrogatzGenerator(n, k, p)

    gen.addSink(graph)
    gen.begin()
    while (gen.nextEvents()) {}
    gen.end()
    graph
  }

  def convert(graph: SingleGraph): Map[Int, Set[Int]] = {
    graph.getNodeSet[Node].map(node => {
      val neighbours = node.getEachEdge[Edge].map(e => e.getOpposite[Node](node)).map(_.getIndex)
      (node.getIndex -> neighbours.toSet)
    }).toMap
  }

  val graph = genGraph(n, k, p)
  val edgemap = convert(graph)
  (0 until n).foreach(i => {
    print(i)
    edgemap(i).foreach(j => print(s" $j"))
    println()
  })

  if (show) graph.display(false)
}
