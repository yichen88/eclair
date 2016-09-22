package fr.acinq.eclair.router

import java.io.{File, FileWriter}

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.util.Timeout
import com.google.common.io.Files
import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair._
import fr.acinq.eclair.router.FlareRouter.{Beacon, ChannelOpened, RouteRequest, RouteResponse}
import lightning.{channel_desc, routing_table}
import org.jgraph.graph.DefaultEdge
import org.jgrapht.alg.DijkstraShortestPath
import org.jgrapht.graph.SimpleGraph

import scala.concurrent.{Await, Future}
import scala.io.{Source, StdIn}
import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

/**
  * Created by fabrice on 19/09/16.
  */
object Simulator extends App {
  var n = 0
  var k = 4
  var radius = 2
  var maxBeacons = 5
  var p = 0.2
  var filename = ""
  var gen = false

  def parse(arguments: List[String]): Unit = arguments match {
    case "-n" :: value :: tail => n = value.toInt; parse(tail)
    case "-k" :: value :: tail => k = value.toInt; parse(tail)
    case "-p" :: value :: tail => p = value.toDouble; parse(tail)
    case "-r" :: value :: tail => radius = value.toInt; parse(tail)
    case "-nb" :: value :: tail => maxBeacons = value.toInt; parse(tail)
    case "-gen" :: tail => gen = true; parse(tail)
    case value :: tail => filename = value; parse(tail)
    case Nil => ()
  }

  parse(args.toList)

  case class Link(a: Int, b: Int)

  /**
    * read links from a text file. The format of the file is:
    * - node ids are integer from 0 to N -1 where N is the number of nodes
    * - for each node n there is a line tat starts with n followed by the list of all the other nodes it is connected to
    *
    * @param filename file name
    * @return a list of links
    */
  def readLinks(filename: String): Map[Int, Set[Int]] = {
    Source.fromFile(filename).getLines().toList.filterNot(_.startsWith("#")).map(line => {
      val a = line.split(" ").map(_.toInt)
      a.head -> a.tail.toSet
    }).toMap
  }

  val links = (gen, filename) match {
    case (true, "") =>
      println(s"running simulation with a generated graph(n = $n, k=$k, p=$p) with radius=$radius and number of beacons=$maxBeacons")
      GenGraph.convert(GenGraph.genGraph(n, k, p))
    case (true, _) => throw new IllegalArgumentException("you cannot specify a file name if you use the -gen option")
    case (false, "") => throw new IllegalArgumentException("you must specify a file name or use the -gen option")
    case (false, _) =>
      println(s"running simulation of $filename with radius=$radius and number of beacons=$maxBeacons")
      readLinks(filename)
  }

  // to display the graph use the circo layout: xdot -f circo simulator.dot
  val writer = new FileWriter(new File(s"simulator.dot"))
  writer.append("graph G {\n")
  links.foreach {
    case (source, targets) => targets.filter(_ > source).foreach(target => writer.append(s""""$source" -- "$target"\n"""))
  }
  writer.append("}\n")
  writer.close()


  val graph = new SimpleGraph[Int, DefaultEdge](classOf[DefaultEdge])
  links.foreach {
    case (source, targets) =>
      graph.addVertex(source)
      targets.filter(_ > source).foreach(target => {
        graph.addVertex(target)
        graph.addEdge(source, target)
      })
  }

  val maxId = links.keySet.max
  val nodeIds = (0 to maxId).map(FlareRouterSpec.nodeId)
  val indexMap = (0 to maxId).map(i => nodeIds(i) -> i).toMap

  val system = ActorSystem("mySystem")
  val routers = (0 to maxId).map(i => system.actorOf(FlareRouter.props(nodeIds(i), radius, maxBeacons), i.toString()))

  def createChannel(a: Int, b: Int): Unit = {
    routers(a) ! ChannelOpened(channel_desc(FlareRouterSpec.channelId(nodeIds(a), nodeIds(b)), nodeIds(a), nodeIds(b)), system.actorSelection(routers(b).path))
    routers(b) ! ChannelOpened(channel_desc(FlareRouterSpec.channelId(nodeIds(a), nodeIds(b)), nodeIds(b), nodeIds(a)), system.actorSelection(routers(a).path))
  }

  StdIn.readLine("Press enter to connect nodes")
  links.foreach { case (source, targets) => targets.filter(_ > source).foreach(target => createChannel(source, target)) }


  StdIn.readLine("Press enter to query nodes")
  implicit val timeout = Timeout(5 seconds)

  val futures = (0 to maxId).map(i => {
    val future = for {
      dot <- (routers(i) ? 'dot).mapTo[BinaryData]
    } yield Files.write(dot, new File(s"$i.dot"))

    future.onFailure {
      case t: Throwable =>
        println(s"cannot write routing table for $i: $t")
    }
    future
  })
  Await.ready(Future.sequence(futures), 15 second)


  var success = 0
  var failures = 0
  for (i <- 0 to maxId) {
    for (j <- (i + 1) to maxId) {
      val future = for {
        channels <- (routers(j) ? 'network).mapTo[Seq[channel_desc]]
        request = RouteRequest(nodeIds(j), routing_table(channels))
        response <- (routers(i) ? request).mapTo[RouteResponse]
      } yield response

      future.onComplete {
        case Success(response) => success = success + 1
        case Failure(t) =>
          println(s"cannot find route from $i to $j")
          Option(new DijkstraShortestPath(graph, i, j, 100).getPath).foreach(path => {
            println(path.getEdgeList.map(e => s"${graph.getEdgeSource(e)} -- ${graph.getEdgeTarget(e)}"))
          })
          failures = failures + 1
      }
      Await.ready(future, 5 seconds)
      println(s"success: $success failures : $failures rate: ${(100 * success) / (success + failures)}%")
    }
  }
}
