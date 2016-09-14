package fr.acinq.eclair.router

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import fr.acinq.eclair.blockchain.peer.CurrentBlockCount

/**
  * Created by PM on 29/08/2016.
  */
class PaymentSpawner(flareNeighborHandler: ActorRef, selector: ActorRef, initialBlockCount: Long) extends Actor with ActorLogging {

  context.system.eventStream.subscribe(self, classOf[CurrentBlockCount])

  override def receive: Receive = main(initialBlockCount)

  def main(currentBlockCount: Long): Receive = {
    case CurrentBlockCount(count) => context.become(main(currentBlockCount))
    case c: CreatePayment =>
      val payFsm = context.actorOf(PaymentManager.props(flareNeighborHandler, selector, initialBlockCount))
      payFsm forward c
  }

}

object PaymentSpawner {
  def props(flareNeighborHandler: ActorRef, selector: ActorRef, initialBlockCount: Long) = Props(classOf[PaymentSpawner], flareNeighborHandler, selector, initialBlockCount)
}
