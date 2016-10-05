package fr.acinq.protos.flare

import java.io.{BufferedOutputStream, File, FileOutputStream, FileWriter}
import java.math.BigInteger

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props, Stash}
import akka.io.Tcp.{Received, Write}
import akka.pattern.ask
import akka.util.Timeout
import fr.acinq.bitcoin.{BinaryData, Crypto, DeterministicWallet, Satoshi}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.PeerWatcher
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.LightningCrypto.KeyPair
import fr.acinq.eclair.io.AuthHandler
import fr.acinq.eclair.router.FlareRouter
import fr.acinq.eclair.router.FlareRouter.{FlareInfo, RouteRequest, RouteResponse}
import fr.acinq.protos.TestBitcoinClient
import lightning._
import lightning.locktime.Locktime.Blocks
import org.apache.commons.math3.stat.descriptive.SummaryStatistics
import org.graphstream.graph.implementations.SingleGraph
import org.graphstream.graph.{Edge, Node}

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

  val fullGraph = new SingleGraph("simulator")

  def getOrAddNode(i: Int): Node = Option(fullGraph.getNode[Node](i.toString)).getOrElse(fullGraph.addNode[Node](i.toString))

  links.foreach {
    case (source, targets) =>
      val srcNode = getOrAddNode(source)
      targets.filter(_ > source).foreach(target => {
        val tgtNode = getOrAddNode(target)
        fullGraph.addEdge[Edge](s"$source-$target", srcNode, tgtNode)
      })
  }

  val maxId = links.keySet.max
  val nodeIds = (0 to maxId).map(nodeId)
  val indexMap = (0 to maxId).map(i => nodeIds(i) -> i).toMap

  val system = ActorSystem("mySystem")
  val nodes = (0 to maxId).map(i => system.actorOf(Props(new MyNode(i)), s"node$i"))
  //val routers = (0 to maxId).map(i => nodes(i).actorOf(Props(new FlareRouter(nodeIds(i), radius, maxBeacons, false)), i.toString()))

  def keyPair(i: Int) = {
    val priv = Crypto.sha256(i.toString.getBytes)
    val pub = Crypto.publicKeyFromPrivateKey(priv :+ 1.toByte)
    KeyPair(pub = pub, priv = priv)
  }

  val blockchain = system.actorOf(Props(new PeerWatcher(new TestBitcoinClient()(system), 300)))
  val paymentHandler = system.settings.config.getString("eclair.payment-handler") match {
    case "local" => system.actorOf(Props[LocalPaymentHandler], name = "payment-handler")
    case "noop" => system.actorOf(Props[NoopPaymentHandler], name = "payment-handler")
  }

  def channelParams(i: Int): OurChannelParams = {
    val commitPrivKey = Crypto.sha256(i.toString.getBytes) :+ 1.toByte
    val finalPrivKey = Crypto.sha256(commitPrivKey) :+ 1.toByte
    val anchorAmount = 100000
    OurChannelParams(locktime(Blocks(300)), commitPrivKey, finalPrivKey, 1, 10000, Crypto.sha256("alice-seed".getBytes()), Some(Satoshi(anchorAmount)))
  }

  class MyNode(i: Int) extends Actor with ActorLogging {
    val router = context.actorOf(Props(new FlareRouter(nodeIds(i), radius, maxBeacons, false)), i.toString())

    def receive = {
      case ('connect, node: ActorRef) =>
        val pipe = context.actorOf(Props[MyPipe])
        val auth = context.actorOf(AuthHandler.props(pipe, blockchain, paymentHandler, router, channelParams(i), keyPair(i)))
        pipe ! auth
        node ! ('accept, pipe)
      case ('accept, pipe: ActorRef) =>
        val auth = context.actorOf(AuthHandler.props(pipe, blockchain, paymentHandler, router, channelParams(i).copy(anchorAmount = None), keyPair(i)))
        pipe ! auth
      case t => router forward t
    }
  }

  class MyPipe extends Actor with ActorLogging with Stash {

    override def unhandled(message: Any): Unit = {
      super.unhandled(message)
      log.warning(s"unhandled message $message")
    }

    def receive = {
      case (a: ActorRef, b: ActorRef) =>
        unstashAll()
        context become running(a, b)
      case a: ActorRef => context become waiting(a)
      case _ => stash()
    }

    def waiting(a: ActorRef): Receive = {
      case b: ActorRef =>
        unstashAll()
        context become running(a, b)
      case _ => stash()
    }

    def running(a: ActorRef, b: ActorRef): Receive = {
      case akka.io.Tcp.Register(_, _, _) => ()
      case Write(data, _) if sender == a => b.tell(Received(data), a)
      case Write(data, _) if sender == b => a.tell(Received(data), b)
      case msg if sender == a => b forward msg
      case msg if sender == b => a forward msg
    }
  }

  def createChannel(a: Int, b: Int): Unit = {
    nodes(a) ! ('connect, nodes(b))
    //    routers(a) ! genChannelChangedState(routers(b), nodeIds(b), channelId(nodeIds(a), nodeIds(b)), 1000000000)
    //    routers(b) ! genChannelChangedState(routers(a), nodeIds(a), channelId(nodeIds(a), nodeIds(b)), 1000000000)
  }

  links.foreach { case (source, targets) => targets.filter(_ > source).foreach(target => createChannel(source, target)) }

  def callToAction: Boolean = {
    println("'r' => send tick_reset to all actors")
    println("'b' => send tick_beacons to all actors")
    println("'s' => send tick_subscribe to all actors")
    println("'i' => get flare_info from all actors")
    println("'c' => continue")
    StdIn.readLine("?") match {
      case "r" =>
        for (router <- nodes) router ! 'tick_reset
        true
      case "b" =>
        for (router <- nodes) router ! 'tick_beacons
        true
      case "s" =>
        for (router <- nodes) router ! 'tick_subscribe
        true
      case "i" =>
        implicit val timeout = Timeout(1 minute)
        val futures = nodes.map(router => (router ? 'info).mapTo[FlareInfo])
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
        dot <- (nodes(i) ? 'dot).mapTo[String]
      } yield printToFile(new File(s"$i.dot"))(writer => writer.write(dot.getBytes))

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
        channels <- (nodes(j) ? 'network).mapTo[Seq[channel_open]]
        states <- (nodes(j) ? 'states).mapTo[Seq[channel_state_update]]
        req = payment_request(nodeIds(j), 1, sha256_hash(1, 2, 3, 4), routing_table(channels), states)
        request = RouteRequest(req)
        response <- (nodes(i) ? request).mapTo[RouteResponse]
      } yield {
        success = success + 1
        routeStats.addValue(response.route.size)
      })
        .recover {
          case t: Throwable =>
            println(s"cannot find route from $i to $j")
            /*Option(new DijkstraShortestPath(fullGraph, i, j, 100).getPath).foreach(path => {
              println(path.getEdgeList.map(e => s"${fullGraph.getEdgeSource(e)} -- ${fullGraph.getEdgeTarget(e)}"))
            })*/
            failures = failures + 1
        }
      Await.ready(future, 5 seconds)
      if ((success + failures) % 10 == 0) {
        val successRate = (100 * success) / (success + failures)
        val progress = 100 * i.toDouble / (maxId - 1)
        println(f"tested=${success + failures} success=$successRate%.2f%% avgLen=${routeStats.getMean}%.2f maxLen=${routeStats.getMax}%.2f varLen=${routeStats.getVariance}%.2f progress=$progress%.2f%%")
      }
    }
  }
  system.terminate()

  def channelId(a: BinaryData, b: BinaryData): BinaryData = {
    if (Scripts.isLess(a, b)) Crypto.sha256(a ++ b) else Crypto.sha256(b ++ a)
  }

  def nodeId(i: Int): bitcoin_pubkey = keyPair(i).pub

  def genChannelChangedState(them: ActorRef, theirNodeId: BinaryData, channelId: BinaryData, available_amount: Int): ChannelChangedState =
    ChannelChangedState(null, them, theirNodeId, null, NORMAL, DATA_NORMAL(new Commitments(null, null,
      OurCommit(0, CommitmentSpec(Set(), 0, 0, 0, available_amount, 0), null),
      null, null, null, 0L, null, null, null, null) {
      override def anchorId: BinaryData = channelId // that's the only thing we need
    }, null, null))

  def printToFile(f: java.io.File)(op: java.io.OutputStream => Unit) {
    val p = new BufferedOutputStream(new FileOutputStream(f))
    try {
      op(p)
    } finally {
      p.close()
    }
  }
}