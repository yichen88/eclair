package fr.acinq.protos.flare

import akka.actor.{Actor, ActorLogging, ActorRef, Props}

/**
  * Created by PM on 08/09/2016.
  */
class FlareNode extends Actor with ActorLogging {

  val neighborHandler = context.actorOf(Props(new NeighborHandler(2, 1)), "neighbor-handler")

  override def receive: Receive = {
    case ('connect, node: ActorRef) =>
      val channel = context.actorOf(Props(new Channel(neighborHandler)))
      node ! ('accept, channel)
    case ('accept, their_channel: ActorRef) =>
      val channel = context.actorOf(Props(new Channel(neighborHandler)))
      channel ! their_channel
      their_channel ! channel
  }
}
