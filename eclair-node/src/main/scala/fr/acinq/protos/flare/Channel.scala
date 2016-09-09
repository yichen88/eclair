package fr.acinq.protos.flare

import akka.actor.{Actor, ActorLogging, ActorRef}
import NeighborHandler.NeighborMessage

/**
  * Created by PM on 08/09/2016.
  */
class Channel(neighborHandler: ActorRef) extends Actor with ActorLogging {

  override def receive: Receive = {
    case them: ActorRef =>
      log.debug(s"connected to $them")
      neighborHandler ! ('newchannel, them)
      context become main(them)
  }

  def main(them: ActorRef): Receive = {
    case msg: NeighborMessage => neighborHandler forward msg
  }

}
