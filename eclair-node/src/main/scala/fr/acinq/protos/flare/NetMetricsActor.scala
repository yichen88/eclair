package fr.acinq.protos.flare

import akka.actor.{Actor, ActorLogging, ActorRef}
import com.trueaccord.scalapb.GeneratedMessage

import scala.concurrent.duration._

/**
  * Created by PM on 23/09/2016.
  */
class NetMetricsActor(actor: ActorRef) extends Actor with ActorLogging {

  import scala.concurrent.ExecutionContext.Implicits.global

  context.system.scheduler.schedule(1 minute, 1 minute, self, 'tick)

  override def receive: Receive = main(0)

  def main(in: Int): Receive = {
    case msg: GeneratedMessage =>
      actor forward msg
      val size = msg.toByteArray.size
      context become main(in + size)
    case 'tick =>
      log.info(s"$in bytes since last tick")
      context become main(0)
    case other =>
      // no-protobuf messages are internal and bypass the size count
      actor forward other

  }
}
