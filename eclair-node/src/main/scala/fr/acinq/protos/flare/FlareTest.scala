package fr.acinq.protos.flare

import akka.actor.{ActorSystem, Props}

import scala.reflect.io.File
import scala.util.Random

/**
  * Created by PM on 08/09/2016.
  */
object FlareTest extends App {

  val system = ActorSystem()


  /*val a = system.actorOf(Props[Node], name = "a")
  val b = system.actorOf(Props[Node], name = "b")
  val c = system.actorOf(Props[Node], name = "c")
  val d = system.actorOf(Props[Node], name = "d")
  val e = system.actorOf(Props[Node], name = "e")

  a ! ('connect, b)
  a ! ('connect, c)
  c ! ('connect, d)
  d ! ('connect, e)*/



  val nodes = for (i <- 0 until 30) yield system.actorOf(Props[Node])

  val random = new Random()
  val f = File("graph.dot").printWriter()
  f.println("digraph G {")
  for (i <- 0 until 60) {
    val a = nodes(random.nextInt(nodes.size))
    val b = (nodes.toSet - a).toList(random.nextInt(nodes.size - 1))
    a ! ('connect, b)
    f.println(s"""    "${a.path.name}" -> "${b.path.name}"""")
  }
  f.println("}")
  f.close()

}
