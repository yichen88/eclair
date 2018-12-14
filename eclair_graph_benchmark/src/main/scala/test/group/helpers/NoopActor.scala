package test.group.helpers

import akka.actor.Actor

class NoopActor() extends Actor {

	override def receive: Receive = {
		case _ => //NOOP
	}

}
