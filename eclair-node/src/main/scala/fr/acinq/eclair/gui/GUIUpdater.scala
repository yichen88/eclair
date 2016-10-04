package fr.acinq.eclair.gui

import javafx.application.Platform
import javafx.event.{ActionEvent, EventHandler}
import javafx.geometry.Orientation
import javafx.scene.control.Separator
import javafx.stage.Stage

import akka.actor.{Actor, ActorLogging, ActorRef}
import fr.acinq.eclair.Setup
import fr.acinq.eclair.channel._
import fr.acinq.eclair.router.ChannelDiscovered
import lightning.channel_open


/**
  * Created by PM on 16/08/2016.
  */
class GUIUpdater(primaryStage: Stage, helloWorld: MainWindow, setup: Setup) extends Actor with ActorLogging {

  def receive: Receive = main(Map())

  def main(m: Map[ActorRef, PaneChannel]): Receive = {

    case ChannelCreated(channel, params, theirNodeId) =>
      log.info(s"new channel: $channel")
      val pane = new PaneChannel()
      pane.textNodeId.setText(s"$theirNodeId")
      pane.textFunder.setText(params.anchorAmount.map(_ => "Yes").getOrElse("No"))
      pane.buttonClose.setOnAction(new EventHandler[ActionEvent] {
        override def handle(event: ActionEvent): Unit = channel ! CMD_CLOSE(None)
      })
      Platform.runLater(new Runnable() {
        override def run(): Unit = {
          helloWorld.vBoxPane.getChildren.addAll(pane, new Separator(Orientation.HORIZONTAL))
        }
      })
      context.become(main(m + (channel -> pane)))

    case ChannelIdAssigned(channel, channelId, capacity) =>
      val pane = m(channel)
      Platform.runLater(new Runnable() {
        override def run(): Unit = {
          pane.labelChannelId.setText(s"Channel id: #$channelId")
          pane.textCapacity.setText(s"$capacity")
          pane.textFunder.getText match {
            case "Yes" => pane.labelAmountUs.setText(s"$capacity")
            case "No" => pane.labelAmountUs.setText("0")
          }
        }
      })

    case ChannelChangedState(channel, _, _, previousState, currentState, currentData) =>
      val pane = m(channel)
      Platform.runLater(new Runnable() {
        override def run(): Unit = {
          pane.textState.setText(currentState.toString)
        }
      })

    case ChannelSignatureReceived(channel, commitments) =>
      val pane = m(channel)
      val bal = commitments.ourCommit.spec.amount_us_msat.toDouble / (commitments.ourCommit.spec.amount_us_msat.toDouble + commitments.ourCommit.spec.amount_them_msat.toDouble)
      Platform.runLater(new Runnable() {
        override def run(): Unit = {
          pane.labelAmountUs.setText(commitments.ourCommit.spec.amount_us_msat.toString)
          pane.progressBarBalance.setProgress(bal)
        }
      })

    case ChannelDiscovered(channel_open(id, a, b)) => {}
      /*graph.addVertex(pubkey2bin(a))
      graph.addVertex(pubkey2bin(b))
      graph.addEdge(a, b, new NamedEdge(id))
      val jgxAdapter = new JGraphXAdapter(graph)
      Platform.runLater(new Runnable() {
        override def run(): Unit = {
          val component = new mxGraphComponent(jgxAdapter)
          component.setDragEnabled(false)
          val lay = new mxCircleLayout(jgxAdapter)
          lay.execute(jgxAdapter.getDefaultParent())
          helloWorld.swingNode.setContent(component)
        }
      })*/

  }
}
