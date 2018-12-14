package test.group.helpers

import java.io.{File, PrintWriter}
import java.nio.file.{Files, StandardCopyOption}
import java.util.UUID

import scala.concurrent.ExecutionContext.Implicits.global
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import com.softwaremill.sttp.okhttp.OkHttpFutureBackend
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BasicBitcoinJsonRPCClient, BitcoinJsonRPCClient}
import akka.pattern.pipe
import akka.pattern.ask
import akka.util.Timeout
import akka.testkit.TestKit._

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class BitcoindHelper(benchmarkDir: String)(implicit val system: ActorSystem) {

	val executor = system.dispatcher
	implicit val sttpBackend = OkHttpFutureBackend()

	implicit val timeout = Timeout(30 seconds)

	import scala.sys.process._

	val PATH_BITCOIND = new File(".", "bitcoin-0.16.3/bin/bitcoind")
	val PATH_BITCOIND_DATADIR = new File(benchmarkDir, "datadir-bitcoin")

	var bitcoind: Process = null
	var bitcoinrpcclient: BitcoinJsonRPCClient = null
	var bitcoincli: ActorRef = null

	case class BitcoinReq(method: String, params: Any*)

	def startBitcoind(): Unit = {
		println("Starting bitcoind")
		Files.createDirectories(PATH_BITCOIND_DATADIR.toPath)
		val bitcoinConfFile = new File(PATH_BITCOIND_DATADIR.toString, "bitcoin.conf")
		if (!Files.exists(bitcoinConfFile.toPath)) {
			new PrintWriter(bitcoinConfFile) {
				write(ConfigHelper.rawBitcoindConf)
				close()
			}
		}

		bitcoind = s"$PATH_BITCOIND -datadir=$PATH_BITCOIND_DATADIR".run()
		bitcoinrpcclient = new BasicBitcoinJsonRPCClient(user = "foo", password = "bar", host = "localhost", port = 28332)
		bitcoincli = system.actorOf(Props(new Actor {
			override def receive: Receive = {
				case BitcoinReq(method) => bitcoinrpcclient.invoke(method) pipeTo sender
				case BitcoinReq(method, params) => bitcoinrpcclient.invoke(method, params) pipeTo sender
				case BitcoinReq(method, param1, param2) => bitcoinrpcclient.invoke(method, param1, param2) pipeTo sender
			}
		}))

		awaitCond({
			println(s"calling getnetworkinfo")
			val bitcoindReady = (bitcoincli ? BitcoinReq("getnetworkinfo")).map(_ => true).recover { case _ => false }
			Await.result(bitcoindReady, 2 seconds)
		}, max = 30 seconds, interval = 3 seconds)
		
		Await.result(bitcoincli ? BitcoinReq("generate", 500), 30 seconds)
		println("bitcond started")
	}

}
