package fr.acinq.benchmark

import java.io.File

import akka.Done
import akka.actor.{Actor, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import fr.acinq.benchmark.helpers.ConfigHelper._
import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.{NodeParams, _}
import fr.acinq.eclair.crypto.LocalKeyManager
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.router.{Hop, RouteRequest, RouteResponse, Router}
import org.openjdk.jmh.annotations._

import scala.concurrent.duration._
import scala.concurrent.{Await, Promise}

@State(value = Scope.Benchmark)
class GraphBenchmark {

	println("Preparing the benchmark")

	//mainnet nodes used for route calculation benchmark, there IS a route (of length 7) between them
	/**
		* 032e000d7927b0d78f7ce4285b7c8e4db97300fb55f2833173d7c28289abbed5bf,
		* 025b9dcfe847cd704ca357f3890680b55989be2ae3cf044b5d1a04e3318d1fbf28,
		* 032b71cc07ea5ff346e7ce9eddad0b55d7e18b788a1e6b4dda3fbd3a7ddbf79bbc,
		* 0307243743f60637f090347f9f2c1c98017071fd8f6afd8d6f6f6a64c7393a858a,
		* 0237e39e60182b80817c7c1c432adf53d89cb256bd9d5a1017fd195dc6a5a8121e,
		* 02ad6fb8d693dc1e4569bcedefadf5f72a931ae027dc0f0c544b34c1c6f3b9a02b,
		* 022a94ee8d3b1dd52066b33a46a8f1f7ed0d4a9dcf5b0f310ce52e94a392a79299,
		* 02ddc0e653386315299a8ca788c2e659f1ca6d96833c8abccdc7dcd84f4fad9700
		*
		*/
	val BITCOINKOCU = PublicKey("032e000d7927b0d78f7ce4285b7c8e4db97300fb55f2833173d7c28289abbed5bf")
	val miningshed = PublicKey("02ddc0e653386315299a8ca788c2e659f1ca6d96833c8abccdc7dcd84f4fad9700")

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

	val routerInitialized = Promise[Done]()
	val router = system.actorOf(Router.props(params, noopActor, Some(routerInitialized)))
	Await.result(routerInitialized.future, 10 seconds)

	println("Benchmark ready")

	@Benchmark
	@BenchmarkMode(value = Array(Mode.AverageTime))
	def routerLoadingTime(): Any = {
		val routerInitializedBench = Promise[Done]()
		system.actorOf(Router.props(params, noopActor, Some(routerInitializedBench)))
		Await.result(routerInitializedBench.future, 10 seconds)
	}

	@Benchmark
	@BenchmarkMode(value = Array(Mode.AverageTime))
	def findPath() = {

		val routeFuture = router ? RouteRequest(BITCOINKOCU, miningshed, 10000000) //DEFAULT_AMOUNT_MSAT

		Await.result(routeFuture, 10 seconds)
	}

	@Benchmark
	@BenchmarkMode(value = Array(Mode.AverageTime))
	def findPathWithIgnoredChannels() = {

		val ignoreNodes = Set(PublicKey("0237e39e60182b80817c7c1c432adf53d89cb256bd9d5a1017fd195dc6a5a8121e"), PublicKey("02ad6fb8d693dc1e4569bcedefadf5f72a931ae027dc0f0c544b34c1c6f3b9a02b"))

		//there is still a route, of length 7
		val routeWithIgnoreNodes = router ? RouteRequest(
			BITCOINKOCU,
			miningshed,
			10000000,
			ignoreNodes = ignoreNodes)

		Await.result(routeWithIgnoreNodes, 10 seconds)
	}

	@Benchmark
	@BenchmarkMode(value = Array(Mode.AverageTime))
	def findPathWithExtraChannels() = {

		val assisted = Seq(ExtraHop(
			nodeId = PublicKey("02368951159b28b9bb2923f4d1b265856e559586f79575298385d5b228cc5757f9"),
			shortChannelId = ShortChannelId(10L),
			feeBaseMsat = 0,
			feeProportionalMillionths = 0,
			1
		), ExtraHop(
			nodeId = PublicKey("0217890e3aad8d35bc054f43acc00084b25229ecff0ab68debd82883ad65ee8266"),
			shortChannelId = ShortChannelId(20L),
			feeBaseMsat = 0,
			feeProportionalMillionths = 0,
			1
		))

		//route has length 5
		val routeWithAssisted = router ? RouteRequest(BITCOINKOCU, miningshed, 10000000, assistedRoutes = Seq(assisted))

		Await.result(routeWithAssisted, 10 seconds)
	}

}
