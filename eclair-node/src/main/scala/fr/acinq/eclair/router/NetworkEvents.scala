package fr.acinq.eclair.router

import lightning.channel_desc

/**
  * Created by PM on 26/08/2016.
  */
trait NetworkEvent

case class ChannelDiscovered(c: channel_desc) extends NetworkEvent

case class ChannelLost(c: channel_desc) extends NetworkEvent

