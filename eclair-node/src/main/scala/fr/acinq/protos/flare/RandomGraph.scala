package fr.acinq.protos.flare

import scala.io.Source
import scala.reflect.io.File

/**
  * Created by PM on 12/09/2016.
  */
object RandomGraph extends App {
  def link(a: String, b: String) = if (a < b) (a, b) else (b, a)
  var links = Set.empty[(String, String)]
  for (line <- Source.fromFile("D:\\bin\\gengraph_win\\mygraph").getLines()) {
    println(line)
    val node = line.split(" ")(0)
    for (neighbor <- line.split(" ").drop(1)) {
      links = links + link(node, neighbor)
    }
  }

  val fout = File("graph.dot").printWriter()
  fout.println("graph G {")
  fout.println(s"""    overlap = false""")
  for (link <- links) {
    fout.println(s"""    "${link._1}" -- "${link._2}"""")
  }
  fout.println("}")
  fout.close()

  // then: dot -Tpng D:\git\eclair\graph.dot -O

}
