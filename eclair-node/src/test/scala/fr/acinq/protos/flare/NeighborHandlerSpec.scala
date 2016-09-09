package fr.acinq.protos.flare

import NeighborHandler._
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

/**
  * Created by PM on 09/09/2016.
  */
@RunWith(classOf[JUnitRunner])
class NeighborHandlerSpec extends FunSuite {

  def c(a: String, b: String) = ChannelDesc.from(a, b)

  test("reproduce loop bug") {
    /*
      radius 2
      pov : d
      d knows a->c, c->d, d->e and receives a->b
      a -> c -> d -> e
       \
        -> b
     */
    val radius = 2
    val table0 = RoutingTable("d", Set() + ChannelDesc.from("a", "c") + ChannelDesc.from("c", "d") + ChannelDesc.from("d", "e"))
    val updates0 = RoutingTableUpdate(ChannelDesc.from("a", "b"), OPEN) :: Nil
    val (table1, updates1) = include(table0, updates0, radius)
    assert(updates1.isEmpty)
  }

  test("reproduce merge bug") {
    val radius = 2
    val table0 = RoutingTable("$k", Set() + c("$B", "$k") + c("$B", "$x") + c("$C", "$k") + c("$C", "$h") + c("$C", "$x") + c("$k", "$n") + c("$k", "$m") + c("$k", "$r"))
    val table1 = merge(table0, RoutingTable("$r", Set() + c("$a", "$r") + c("$q", "$z") + c("$r", "$z") + c("$t", "$z")), radius)

    assert(!table1.channels.contains(c("$t", "$z")))
  }

}
