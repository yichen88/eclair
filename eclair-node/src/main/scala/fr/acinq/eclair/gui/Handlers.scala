package fr.acinq.eclair.gui


import java.io._
import java.util.Base64
import javafx.application.Platform
import javafx.scene.control.{TextArea, TextField}
import javafx.stage.Stage

import fr.acinq.bitcoin.{BinaryData, Satoshi}
import fr.acinq.eclair._
import fr.acinq.eclair.io.Client
import grizzled.slf4j.Logging
import lightning.{channel_open, channel_state_update}

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
    logger.info(s"sending ${paymentRequest.amountMsat} to ${paymentRequest.rHash} @ ${paymentRequest.nodeId}")
    paymentSpawner ! paymentRequest
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

  def getPaymentRequest(amountMsat: Int, textField: TextArea): Unit = {
    import akka.pattern.ask
    val future1 = (paymentHandler ? 'genh).mapTo[BinaryData]
    val future2 = (router ? 'network).mapTo[Seq[channel_open]]
    val future3 = (router ? 'states).mapTo[Seq[channel_state_update]]
    val future4 = for {
      h <- future1
      channels <- future2
      states <- future3
    } yield lightning.payment_request(Globals.Node.publicKey, amountMsat, h, lightning.routing_table(channels), states)
    future4.map { r =>
      Platform.runLater(new Runnable() {
        override def run(): Unit = {
          textField.setText(Base64.getEncoder.encodeToString(r.toByteArray))
        }
      })
    }
  }

  def exportToDot(file: File): Unit = {
    import akka.pattern.ask
    (router ? 'dot).mapTo[String].map(dot => printToFile(file)(writer => writer.write(dot)))
  }

  def printToFile(f: java.io.File)(op: java.io.FileWriter => Unit) {
    val p = new FileWriter(f)
    try {
      op(p)
    } finally {
      p.close()
    }
  }

  def displayDot(stage: Stage): Unit = {
    import akka.pattern.ask
    (router ? 'dot)
      .mapTo[String]
      .map(dot => {
        import scala.sys.process._
        val input = new ByteArrayInputStream(dot.getBytes)
        val output = new ByteArrayOutputStream()
        "dot -Tpng" #< input #> output !
        val img = output.toByteArray
        Platform.runLater(new Runnable() {
          override def run(): Unit = {
            new DialogGraph(stage, img).show()
          }
        })
      })
  }
}
