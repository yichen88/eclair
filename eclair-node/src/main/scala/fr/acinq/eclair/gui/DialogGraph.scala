package fr.acinq.eclair.gui

import java.io.ByteArrayInputStream
import javafx.scene.Scene
import javafx.scene.image.{Image, ImageView}
import javafx.scene.layout.StackPane
import javafx.stage.{Modality, Stage, StageStyle}

/**
  * Created by PM on 16/08/2016.
  */
class DialogGraph(primaryStage: Stage, img: Array[Byte]) extends Stage() {
  initModality(Modality.NONE)
  initStyle(StageStyle.UTILITY)
  initOwner(primaryStage)
  setTitle("Graph")

  val image = new Image(new ByteArrayInputStream(img))
  val view = new ImageView(image)

  val pane = new StackPane()
  pane.getChildren.add(view)

  val scene = new Scene(pane)
  setScene(scene)
}
