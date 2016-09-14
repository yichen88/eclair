package fr.acinq.eclair

import javafx.application.Application

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.{BinaryData, BitcoinJsonRPCClient, OutPoint, Satoshi, Transaction, TxIn, TxOut}
import fr.acinq.eclair.api.Service
import fr.acinq.eclair.blockchain.peer.{NewBlock, NewTransaction}
import fr.acinq.eclair.blockchain.{ExtendedBitcoinClient, PeerWatcher}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.LightningCrypto
import fr.acinq.eclair.gui.MainWindow
import fr.acinq.eclair.io.{Client, Server}
import fr.acinq.eclair.router._
import grizzled.slf4j.Logging

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/**
  * Created by PM on 25/01/2016.
  */
object Boot extends App with Logging {
  args.toList match {
    case "headless" :: rest => new Setup()
    case _ => Application.launch(classOf[MainWindow])
  }
}

class FakeBitcoinClient()(implicit system: ActorSystem) extends ExtendedBitcoinClient(new BitcoinJsonRPCClient("", "", "", 0)) {

  client.client.close()

  import scala.concurrent.ExecutionContext.Implicits.global
  system.scheduler.schedule(100 milliseconds, 100 milliseconds, new Runnable {
    override def run(): Unit = system.eventStream.publish(NewBlock(null)) // blocks are not actually interpreted
  })

  override def makeAnchorTx(ourCommitPub: BinaryData, theirCommitPub: BinaryData, amount: Satoshi)(implicit ec: ExecutionContext): Future[(Transaction, Int)] = {
    val fakeTxid = LightningCrypto.randomKeyPair().priv
    val txIn = TxIn(OutPoint(fakeTxid, 0), BinaryData("00"), 0)
    val anchorTx = Transaction(version = 1,
      txIn = txIn :: Nil,
      txOut = TxOut(amount, Scripts.anchorPubkeyScript(ourCommitPub, theirCommitPub)) :: Nil,
      lockTime = 0
    )
    Future.successful((anchorTx, 0))
  }

  override def publishTransaction(tx: Transaction)(implicit ec: ExecutionContext): Future[String] = {
    system.eventStream.publish(NewTransaction(tx))
    Future.successful(tx.txid.toString())
  }

  override def getTxConfirmations(txId: String)(implicit ec: ExecutionContext): Future[Option[Int]] = Future.successful(Some(10))

  override def getTransaction(txId: String)(implicit ec: ExecutionContext): Future[Transaction] = ???

  override def fundTransaction(tx: Transaction)(implicit ec: ExecutionContext): Future[FundTransactionResponse] = ???

  override def signTransaction(tx: Transaction)(implicit ec: ExecutionContext): Future[SignTransactionResponse] = ???

}

class Setup extends Logging {

  logger.info(s"hello!")
  logger.info(s"nodeid=${Globals.Node.publicKey}")
  val config = ConfigFactory.load()

  implicit val formats = org.json4s.DefaultFormats
  implicit val ec = ExecutionContext.Implicits.global
  implicit lazy val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val timeout = Timeout(30 seconds)

  val bitcoin_client = new FakeBitcoinClient()
  val chain = "fake"
  val bitcoinVersion = "fake"

  val watcher = system.actorOf(PeerWatcher.props(bitcoin_client, 1000), name = "watcher")
  val paymentHandler = config.getString("eclair.payment-handler") match {
    case "local" => system.actorOf(Props[LocalPaymentHandler], name = "payment-handler")
    case "noop" => system.actorOf(Props[NoopPaymentHandler], name = "payment-handler")
  }
  val router = system.actorOf(FlareRouter.props(config.getInt("eclair.flare.radius"), config.getInt("eclair.flare.beacon-count")), name = "neighbor-handler")
  val register = system.actorOf(Register.props(watcher, paymentHandler, router), name = "register")
  val selector = system.actorOf(Props[ChannelSelector], name = "selector")
  val paymentSpawner = system.actorOf(PaymentSpawner.props(router, selector, 1000), "payment-spawner")
  val server = system.actorOf(Server.props(config.getString("eclair.server.host"), config.getInt("eclair.server.port"), register), "server")

  val _setup = this
  val api = new Service {
    override val register: ActorRef = _setup.register
    override val router: ActorRef = _setup.router
    override val paymentHandler: ActorRef = _setup.paymentHandler
    override val paymentSpawner: ActorRef = _setup.paymentSpawner

    override def connect(host: String, port: Int, amount: Satoshi): Unit = system.actorOf(Client.props(host, port, amount, register))
  }
  Http().bindAndHandle(api.route, config.getString("eclair.api.host"), config.getInt("eclair.api.port"))
}
