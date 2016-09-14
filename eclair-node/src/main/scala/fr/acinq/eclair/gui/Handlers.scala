package fr.acinq.eclair.gui


import java.util.Base64
import javafx.application.Platform
import javafx.scene.control.{TextArea, TextField}

import fr.acinq.bitcoin.{BinaryData, Satoshi}
import fr.acinq.eclair.io.Client
import fr.acinq.eclair.router.CreatePayment
import fr.acinq.eclair._
import grizzled.slf4j.Logging
import lightning.channel_desc

/**
  * Created by PM on 16/08/2016.
  */
class Handlers(setup: Setup) extends Logging {

  import setup._

  def open(hostPort: String, amount: Satoshi) = {
    val regex = "([a-zA-Z0-9\\.\\-_]+):([0-9]+)".r
    hostPort match {
      case regex(host, port) =>
        logger.info(s"connecting to $host:$port")
        system.actorOf(Client.props(host, port.toInt, amount, register))
      case _ => {}
    }
  }

  def send(paymentRequest: lightning.payment_request) = {
    logger.info(s"sending ${paymentRequest.amountMsat} to ${paymentRequest.hash} @ ${paymentRequest.nodeId}")
    paymentSpawner ! CreatePayment(paymentRequest.amountMsat.toInt, paymentRequest.hash, paymentRequest.nodeId, paymentRequest.routingTable)
  }

  def getH(textField: TextField): Unit = {
    import akka.pattern.ask
    (paymentHandler ? 'genh).mapTo[BinaryData].map { h =>
      Platform.runLater(new Runnable() {
        override def run(): Unit = {
          textField.setText(h.toString())
        }
      })
    }
  }

  def getPaymentRequest(amountMsat: Long, textField: TextArea): Unit = {
    import akka.pattern.ask
    val future1 = (paymentHandler ? 'genh).mapTo[BinaryData]
    val future2 = (router ? 'network).mapTo[Seq[channel_desc]]
    val future3 = for {
      h <- future1
      channels <- future2
    } yield lightning.payment_request(Globals.Node.publicKey, amountMsat, h, lightning.routing_table(channels))
    future3.map { r =>
      Platform.runLater(new Runnable() {
        override def run(): Unit = {
          textField.setText(Base64.getEncoder.encodeToString(r.toByteArray))
        }
      })
    }
  }
}
