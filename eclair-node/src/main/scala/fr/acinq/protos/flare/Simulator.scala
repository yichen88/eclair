package fr.acinq.protos.flare

import java.io.{BufferedOutputStream, File, FileOutputStream, FileWriter}
import java.math.BigInteger

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import fr.acinq.bitcoin.{BinaryData, Crypto}
import fr.acinq.eclair._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.router.FlareRouter
import fr.acinq.eclair.router.FlareRouter.{FlareInfo, RouteRequest, RouteResponse}
import lightning.{channel_desc, routing_table}
import org.apache.commons.math3.stat.descriptive.SummaryStatistics
import org.jgraph.graph.DefaultEdge
import org.jgrapht.alg.DijkstraShortestPath
import org.jgrapht.graph.SimpleGraph

import scala.collection.JavaConversions._
import scala.collection.immutable.IndexedSeq
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.io.{Source, StdIn}
import scala.util.Random

/**
  * Created by fabrice on 19/09/16.
  */
object Simulator extends App {
  var n = 0
  var k = 4
  var radius = 2
  var maxBeacons = 5
  var beaconReactivateCount = 5
  var p = 0.2
  var filename = ""
  var gen = false
  var save = false

  def parse(arguments: List[String]): Unit = arguments match {
    case "-n" :: value :: tail => n = value.toInt; parse(tail)
    case "-k" :: value :: tail => k = value.toInt; parse(tail)
    case "-p" :: value :: tail => p = value.toDouble; parse(tail)
    case "-r" :: value :: tail => radius = value.toInt; parse(tail)
    case "-nb" :: value :: tail => maxBeacons = value.toInt; parse(tail)
    case "-nbr" :: value :: tail => beaconReactivateCount = value.toInt; parse(tail)
    case "-gen" :: tail => gen = true; parse(tail)
    case "-save" :: tail => save = true; parse(tail)
    case value :: tail => filename = value; parse(tail)
    case Nil => ()
  }

  parse(args.toList)

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

  println(s"flare parameters: radius=$radius beacons=$maxBeacons beacon-reactivate-count=$beaconReactivateCount")
  val links = (gen, filename) match {
    case (true, "") =>
      println(s"running simulation with a generated graph(n = $n, k=$k, p=$p)")
      GenGraph.convert(GenGraph.genGraph(n, k, p))
    case (true, _) => throw new IllegalArgumentException("you cannot specify a file name if you use the -gen option")
    case (false, "") => throw new IllegalArgumentException("you must specify a file name or use the -gen option")
    case (false, _) =>
      println(s"running simulation of $filename")
      readLinks(filename)
  }

  def saveAsDot(graph: Map[Int, Set[Int]], filename: String): Unit = {
    val writer = new FileWriter(new File(filename))
    writer.append("graph G {\n")
    graph.foreach {
      case (source, targets) => targets.filter(_ > source).foreach(target => writer.append(s""""$source" -- "$target"\n"""))
    }
    writer.append("}\n")
    writer.close()
  }

  def saveAsText(graph: Map[Int, Set[Int]], filename: String): Unit = {
    val writer = new FileWriter(new File(filename))
    graph.foreach {
      case (source, targets) =>
        writer.append(s"$source")
        targets.filter(_ > source).foreach(target => writer.append(s" $target"))
        writer.append("\n")
    }
    writer.close()
  }

  // to display the graph use the circo layout: xdot -f circo simulator.dot
  if (save) {
    saveAsDot(links, "simulator.dot")
    saveAsText(links, "simulator.txt")
  }

  val fullGraph = new SimpleGraph[Int, DefaultEdge](classOf[DefaultEdge])
  links.foreach {
    case (source, targets) =>
      fullGraph.addVertex(source)
      targets.filter(_ > source).foreach(target => {
        fullGraph.addVertex(target)
        fullGraph.addEdge(source, target)
      })
  }

  val maxId = links.keySet.max
  val nodeIds = (0 to maxId).map(nodeId)
  val indexMap = (0 to maxId).map(i => nodeIds(i) -> i).toMap

  val system = ActorSystem("mySystem")
  val routers = (0 to maxId).map(_ match {
    case i if i == 0 =>
      val actor = system.actorOf(Props(new FlareRouter(nodeIds(i), radius, maxBeacons, false)), i.toString())
      system.actorOf(Props(new NetMetricsActor(actor)), "net-metrics")
    case i => system.actorOf(Props(new FlareRouter(nodeIds(i), radius, maxBeacons, false)), i.toString())
  })

  def createChannel(a: Int, b: Int): Unit = {
    routers(a) ! genChannelChangedState(routers(b), nodeIds(b), channelId(nodeIds(a), nodeIds(b)))
    routers(b) ! genChannelChangedState(routers(a), nodeIds(a), channelId(nodeIds(a), nodeIds(b)))
  }

  links.foreach { case (source, targets) => targets.filter(_ > source).foreach(target => createChannel(source, target)) }

  def callToAction: Boolean = {
    println("'r' => send tick_reset to all actors")
    println("'b' => send tick_beacons to all actors")
    println("'i' => get flare_info from all actors")
    println("'c' => continue")
    StdIn.readLine("?") match {
      case "r" =>
        for (router <- routers) router ! 'tick_reset
        true
      case "b" =>
        for (router <- routers) router ! 'tick_beacons
        true
      case "i" =>
        implicit val timeout = Timeout(1 minute)
        val futures = routers.map(router => (router ? 'info).mapTo[FlareInfo])
        val results = Await.result(Future.sequence(futures), 30 second)
        val neighborsStats = new SummaryStatistics()
        val knownStats = new SummaryStatistics()
        val beaconsHopsStats = new SummaryStatistics()
        results.foreach(result => {
          neighborsStats.addValue(result.neighbors)
          knownStats.addValue(result.known_nodes)
          result.beacons.foreach(beacon => beaconsHopsStats.addValue(beacon.hops))
        })
        println(f"neighborMin=${neighborsStats.getMin}%.2f neighborMax=${neighborsStats.getMax}%.2f neighborAvg=${neighborsStats.getMean}%.2f  neighborVar=${neighborsStats.getVariance}%.2f")
        println(f"knownMin=${knownStats.getMin}%.2f knownMax=${knownStats.getMax}%.2f knownAvg=${knownStats.getMean}%.2f knownVar=${knownStats.getVariance}%.2f")
        println(f"beaconHopsMin=${beaconsHopsStats.getMin}%.2f beaconHopsMax=${beaconsHopsStats.getMax}%.2f beaconHopsAvg=${beaconsHopsStats.getMean}%.2f  beaconHopsVar=${beaconsHopsStats.getVariance}%.2f")
        true
      case "c" => false
      case x =>
        println(s"'$x' not supported")
        callToAction
    }
  }

  do {
    Thread.sleep(10000)
  } while (callToAction)

  implicit val timeout = Timeout(5 second)

  if (save) {
    val futures = (0 to maxId).map(i => {
      val future = for {
        dot <- (routers(i) ? 'dot).mapTo[BinaryData]
      } yield printToFile(new File(s"$i.dot"))(writer => writer.write(dot))

      future.onFailure {
        case t: Throwable =>
          println(s"cannot write routing table for $i: $t")
      }
      future
    })
    Await.ready(Future.sequence(futures), 15 second)
  }

  var success = 0
  var failures = 0
  val routeStats = new SummaryStatistics()
  for (i <- 0 to maxId) {
    for (j <- Random.shuffle(i + 1 to maxId)) {
      val future = (for {
        channels <- (routers(j) ? 'network).mapTo[Seq[channel_desc]]
        request = RouteRequest(nodeIds(j), routing_table(channels))
        response <- (routers(i) ? request).mapTo[RouteResponse]
      } yield {
        success = success + 1
        routeStats.addValue(response.route.size)
      })
        .recover {
          case t: Throwable =>
            println(s"cannot find route from $i to $j")
            Option(new DijkstraShortestPath(fullGraph, i, j, 100).getPath).foreach(path => {
              println(path.getEdgeList.map(e => s"${fullGraph.getEdgeSource(e)} -- ${fullGraph.getEdgeTarget(e)}"))
            })
            failures = failures + 1
        }
      Await.ready(future, 5 seconds)
      if (success + failures % 10 == 0) {
        val successRate = (100 * success) / (success + failures)
        val progress = 100 * i.toDouble / (maxId - 1)
        println(f"tested=${success + failures} success=$successRate%.2f%% avgLen=${routeStats.getMean}%.2f maxLen=${routeStats.getMean}%.2f varLen=${routeStats.getVariance}%.2f progress=$progress%.2f%%")
      }
    }
  }
  system.terminate()

  def channelId(a: BinaryData, b: BinaryData): BinaryData = {
    if (Scripts.isLess(a, b)) Crypto.sha256(a ++ b) else Crypto.sha256(b ++ a)
  }

  def nodeId(i: Int): BinaryData = {
    val a = BigInteger.valueOf(i).toByteArray
    Crypto.sha256(a)
  }

  def genChannelChangedState(them: ActorRef, theirNodeId: BinaryData, channelId: BinaryData): ChannelChangedState =
    ChannelChangedState(null, them, theirNodeId, null, NORMAL, DATA_NORMAL(new Commitments(null, null, null, null, null, null, 0L, null, null, null, null) {
      override def anchorId: BinaryData = channelId // that's the only thing we need
    }, null, null))

  def printToFile(f: java.io.File)(op: java.io.OutputStream => Unit) {
    val p = new BufferedOutputStream(new FileOutputStream(f))
    try { op(p) } finally { p.close() }
  }
}