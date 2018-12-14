package fr.acinq.benchmark

import java.io.File

import akka.actor.{Actor, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import fr.acinq.benchmark.helpers.ConfigHelper._
import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.{NodeParams, _}
import fr.acinq.eclair.crypto.LocalKeyManager
import fr.acinq.eclair.router.{RouteRequest, RouteResponse, Router}
import org.openjdk.jmh.annotations._

import scala.concurrent.duration._
import scala.concurrent.{Await, Promise}

@State(value = Scope.Benchmark)
class GraphBenchmark {

	println("Preparing the benchmark")

	//mainnet nodes used for route calculation benchmark, there IS a route (of length 3) between them
	//03cb7983dc247f9f81a0fa2dfa3ce1c255365f7279c8dd143e086ca333df10e278 -> 036fc14cbad100a63ce4c058561d470e045318b6e9659484abcb3176ed7f0acbce -> 02a0bc43557fae6af7be8e3a29fdebda819e439bea9c0f8eb8ed6a0201f3471ca9
	val fairlycheap = PublicKey("03cb7983dc247f9f81a0fa2dfa3ce1c255365f7279c8dd143e086ca333df10e278")
	val LightningPeachHub = PublicKey("02a0bc43557fae6af7be8e3a29fdebda819e439bea9c0f8eb8ed6a0201f3471ca9")

	//for akka ask pattern
	implicit val timeout = Timeout(10 seconds)

	val mainnetDbFolder = new File("./mainnetdb-14122018")
	val akkaConf = ConfigFactory.parseString(rawAkkConf).resolve()

	implicit val system = ActorSystem.create("bench-actor-system", config = akkaConf)
	implicit val executorContext = system.dispatcher

	val noopActor = system.actorOf(Props(new {} with Actor {
		override def receive: Receive = {
			case _ =>
		}
	}))

	Runtime.getRuntime.addShutdownHook(new Thread(new Runnable {
		override def run(): Unit = {
			Await.result(system.terminate(), 1 seconds)
			System.exit(0)
		}
	}))

	val keyManager = new LocalKeyManager(seed = randomKey.toBin, chainHash = Block.LivenetGenesisBlock.hash)
	val params = NodeParams.makeNodeParams(mainnetDbFolder, eclairConf.getConfig("eclair"), keyManager)

	val routerInitialized = Promise[Unit]()
	val router = system.actorOf(Router.props(params, noopActor, Some(routerInitialized)))
	Await.result(routerInitialized.future, 10 seconds)

	println("Benchmark ready")

	@Benchmark
	@BenchmarkMode(value = Array(Mode.AverageTime))
	def routerLoadingTime(): Any = {
		val routerInitializedBench = Promise[Unit]()
		system.actorOf(Router.props(params, noopActor, Some(routerInitializedBench)))
		Await.result(routerInitializedBench.future, 10 seconds)
	}

	@Benchmark
	@BenchmarkMode(value = Array(Mode.AverageTime))
	def findPath() = {

		val routeFuture = router ? RouteRequest(fairlycheap, LightningPeachHub, 10000000) //DEFAULT_AMOUNT_MSAT

		Await.result(routeFuture, 10 seconds)
	}

}
