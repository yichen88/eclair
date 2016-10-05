package fr.acinq.eclair.router

import java.util.UUID

import akka.actor
import akka.actor.{Actor, ActorLogging, ActorRef}
import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair._
import fr.acinq.eclair.channel._
import lightning._
import lightning.neighbor_onion.Next._
import lightning.routing_table_update.Update.{ChannelClose, ChannelOpen}
import org.graphstream.algorithm.Dijkstra
import org.graphstream.graph.implementations.{MultiGraph, MultiNode}
import org.graphstream.graph.{Edge, Node}

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.util.{Random, Try}

/**
  * Created by PM on 08/09/2016.
  */
class FlareRouter(myself: bitcoin_pubkey, radius: Int, beaconCount: Int, ticks: Boolean = true, beaconReactivateCount: Int = 5) extends Actor with ActorLogging {

  val MAX_BEACON_DISTANCE = 100

  context.system.eventStream.subscribe(self, classOf[ChannelChangedState])
  context.system.eventStream.subscribe(self, classOf[ChannelSignatureReceived])

  import scala.concurrent.ExecutionContext.Implicits.global

  if (ticks) {
    context.system.scheduler.schedule(10 seconds, 20 seconds, self, 'tick_beacons)
    context.system.scheduler.schedule(1 minute, 1 minute, self, 'tick_reset)
    context.system.scheduler.schedule(1 minute, 1 minute, self, 'tick_subscribe)
  }

  import FlareRouter._

  override def receive: Receive = main({
    val g = new MultiGraph(UUID.randomUUID().toString)
    g.addNode[MultiNode](pubkey2string(myself))
    g
  }, null, Nil, Nil, Nil, Set(), Map(), Set(), Map(), 0, Map())

  def main(graph: MultiGraph, dijkstra: Dijkstra, neighbors: List[Neighbor], routingUpdatesBatch: List[routing_table_update], channelUpdatesBatch: List[channel_state_update], beacons: Set[Beacon], channelStates: Map[ChannelOneEnd, channel_state_update], subscribed: Set[bitcoin_pubkey], subscribers: Map[bitcoin_pubkey, Seq[bitcoin_pubkey]], mysequence: Int, promises: Map[sha256_hash, Promise[Seq[channel_state_update]]]): Receive = {
    case ChannelChangedState(channel, connection, theirNodeId, _, NORMAL, d: DATA_NORMAL) if channel == null || self.path.parent == channel.path.parent.parent || self.path.parent == channel.path.parent.parent.parent =>
      val stateUpdate = commitments2channelState(mysequence, myself, d.commitments)
      val neighbor = Neighbor(theirNodeId, stateUpdate.channelId, connection, Nil)
      val channelOpen = channel_open(neighbor.channel_id, myself, neighbor.node_id)
      val updates = routing_table_update(ChannelOpen(channelOpen)) :: Nil
      val (graph1, updates1, dijkstra1) = include(myself, graph, updates, radius, beacons.map(_.id))
      neighbor.connection ! neighbor_hello(graph2table(graph1))
      log.debug(s"graph is now ${graph2string(graph1)}")
      val channelStates1 = channelStates + (ChannelOneEnd(stateUpdate.channelId, stateUpdate.node) -> stateUpdate)
      log.debug(s"channel states are now ${states2string(channelStates1)}")
      context.system.scheduler.scheduleOnce(1 second, self, 'tick_updates)
      context become main(graph1, dijkstra1, neighbors :+ neighbor, routingUpdatesBatch ++ updates1, channelUpdatesBatch :+ stateUpdate, beacons, channelStates1, subscribed, subscribers, mysequence + 1, promises)
    case ChannelSignatureReceived(channel, commitments) if channel == null || self.path.parent == channel.path.parent.parent || self.path.parent == channel.path.parent.parent.parent =>
      val stateUpdate = commitments2channelState(mysequence, myself, commitments)
      val channelStates1 = channelStates + (ChannelOneEnd(stateUpdate.channelId, stateUpdate.node) -> stateUpdate)
      log.debug(s"channel states are now ${states2string(channelStates1)}")
      context.system.scheduler.scheduleOnce(1 second, self, 'tick_updates)
      context become main(graph, dijkstra, neighbors, routingUpdatesBatch, channelUpdatesBatch :+ stateUpdate, beacons, channelStates1, subscribed, subscribers, mysequence + 1, promises)
    case msg@neighbor_hello(table1) =>
      log.debug(s"received $msg from $sender")
      val (graph1, dijkstra1) = merge(myself, graph, table1, radius, beacons.map(_.id))
      // we send beacon req to every newly discovered node
      val new_nodes = (graph1.getNodeSet[MultiNode].map(node2pubkey(_)).toSet -- graph.getNodeSet[MultiNode].map(node2pubkey(_)).toSet)
      for (node <- new_nodes) {
        log.debug(s"$myself sending beacon_req message to new node $node")
        val (channels1, route) = findRoute(dijkstra1, getOrAdd(graph1, myself), getOrAdd(graph1, node))
        send(route, neighbors, neighbor_onion(Req(beacon_req(myself, channels1))))
      }
      log.debug(s"graph is now ${graph2string(graph1)}")
      context become main(graph1, dijkstra1, neighbors, routingUpdatesBatch, channelUpdatesBatch, beacons, channelStates, subscribed, subscribers, mysequence, promises)
    case msg@neighbor_update(routingTableUpdates, channelStateUpdates) =>
      log.debug(s"received $msg from $sender")
      // STEP 1: routing table updates
      val (graph1, updates1, dijkstra1) = include(myself, graph, routingTableUpdates, radius, beacons.map(_.id))
      // we send beacon req to every newly discovered node
      val new_nodes = (graph1.getNodeSet[MultiNode].map(node2pubkey(_)).toSet -- graph.getNodeSet[MultiNode].map(node2pubkey(_)).toSet)
      for (node <- new_nodes) {
        log.debug(s"sending beacon_req message to new node $node")
        val (channels1, route) = findRoute(dijkstra1, getOrAdd(graph1, myself), getOrAdd(graph1, node))
        send(route, neighbors, neighbor_onion(Req(beacon_req(myself, channels1))))
      }
      log.debug(s"graph is now ${graph2string(graph1)}")
      // STEP 2: channel state updates
      val channelStateUpdates1 = channelStateUpdates.collect {
        case u@channel_state_update(channelId, sequence, node, amountMsat, relayFee) if Option(graph.getEdge[Edge](sha2562string(channelId))).isDefined =>
          channelStates.get(ChannelOneEnd(u.channelId, u.node)) match {
            case Some(s) if s.sequence < sequence =>
              log.debug(s"received channel state update #$sequence for channelId=$channelId node=$node amountMsat=$amountMsat relayFee=$relayFee")
              u
            case Some(s) =>
              log.debug(s"ignoring channel state update #$sequence for channelId=$channelId node=$node (we are at sequence #${s.sequence}")
              ""
            case None =>
              log.debug(s"received first channel state update #$sequence for channelId=$channelId node=$node amountMsat=$amountMsat relayFee=$relayFee")
              u
          }
      } collect { case u: channel_state_update => u } // TODO ugly!
    val channelStates1 = channelStateUpdates1.foldLeft(channelStates) {
      case (states, u) => states + (ChannelOneEnd(u.channelId, u.node) -> u)
    }
      log.debug(s"channel states are now ${states2string(channelStates1)}")
      context.system.scheduler.scheduleOnce(3 second, self, 'tick_updates)
      context become main(graph1, dijkstra1, neighbors, routingUpdatesBatch ++ updates1, channelUpdatesBatch ++ channelStateUpdates1, beacons, channelStates1, subscribed, subscribers, mysequence, promises)
    case msg@neighbor_reset(channel_ids) =>
      log.debug(s"received neighbor_reset from $sender with ${channel_ids.size} channels")
      val diff = graph2table(graph).channels.filterNot(c => channel_ids.contains(c.channelId)).map(c => routing_table_update(ChannelOpen(c)))
      log.debug(s"sending back ${diff.size} channels to $sender")
      sender ! neighbor_update(diff)
    case 'tick_updates if !routingUpdatesBatch.isEmpty || !channelUpdatesBatch.isEmpty =>
      // to neighbors
      val neighbors1 = neighbors.map(neighbor => {
        val diff = routingUpdatesBatch.filterNot(neighbor.sent.contains(_))
        if (diff.size > 0 || channelUpdatesBatch.size > 0) {
          log.debug(s"sending $diff and $channelUpdatesBatch to ${neighbor.node_id}")
          neighbor.connection ! neighbor_update(diff, channelUpdatesBatch)
        }
        neighbor.copy(sent = neighbor.sent ::: diff)
      })
      // to suscribers
      channelUpdatesBatch
        .map(u => neighbor_onion(DynamicInfo(u)))
        .foreach(msg => subscribers.foreach(suscriber => send(suscriber._2, neighbors, msg)))
      context become main(graph, dijkstra, neighbors1, Nil, Nil, beacons, channelStates, subscribed, subscribers, mysequence, promises)
    case 'tick_updates => // nothing to do
    case 'tick_reset =>
      val channel_ids = graph2table(graph).channels.map(_.channelId)
      for (neighbor <- neighbors) {
        log.debug(s"sending nighbor_reset message to neighbor $neighbor")
        neighbor.connection ! neighbor_reset(channel_ids)
      }
    case 'tick_beacons =>
      for (node <- Random.shuffle(graph.getNodeSet[MultiNode].toSet - graph.getNode[MultiNode](pubkey2string(myself))).take(beaconReactivateCount)) {
        log.debug(s"sending beacon_req message to random node $node")
        val (channels1, route) = findRoute(dijkstra, getOrAdd(graph, myself), node)
        send(route, neighbors, neighbor_onion(Req(beacon_req(myself, channels1))))
      }
    case 'tick_subscribe =>
      val subscribed1 = beacons.flatMap(beacon => findRoute(dijkstra, getOrAdd(graph, myself), getOrAdd(graph, beacon.id))._2) - myself
      val remove = subscribed -- subscribed1
      val add = subscribed1 -- subscribed
      if (remove.size > 0) {
        log.info(s"unsubscribing to ${remove.map(n => pubkey2string(n).take(6)).mkString(",")}")
        val unsubscribeMsg = neighbor_onion(Unsubscribe(unsubscribe(myself)))
        remove.foreach(node => send(findRoute(dijkstra, getOrAdd(graph, myself), getOrAdd(graph, node))._2, neighbors, unsubscribeMsg))
      }
      if (add.size > 0) {
        log.info(s"subscribing to ${add.map(n => pubkey2string(n).take(6)).mkString(",")}")
        add.foreach(node => {
          val (channels, route) = findRoute(dijkstra, getOrAdd(graph, myself), getOrAdd(graph, node))
          val subscribeMsg = neighbor_onion(Subscribe(subscribe(myself, channels)))
          send(route, neighbors, subscribeMsg)
        })
      }
      context become main(graph, dijkstra, neighbors, routingUpdatesBatch, channelUpdatesBatch, beacons, channelStates, subscribed1, subscribers, mysequence, promises)
    case msg@neighbor_onion(onion) =>
      (onion: @unchecked) match {
        case lightning.neighbor_onion.Next.Forward(next) =>
          //log.debug(s"forwarding $msg to ${next.node}")
          val neighbor = next.toNode
          neighbor2channel(neighbor, neighbors) match {
            case Some(channel) => channel ! next.onion
            case None => log.error(s"could not find channel for neighbor $neighbor, cannot forward $msg")
          }
        case lightning.neighbor_onion.Next.Req(req) => self ! req
        case lightning.neighbor_onion.Next.Ack(ack) => self ! ack
        case lightning.neighbor_onion.Next.Subscribe(subscribe) => self ! subscribe
        case lightning.neighbor_onion.Next.Unsubscribe(unsubscribe) => self ! unsubscribe
        case lightning.neighbor_onion.Next.DynamicInfo(dynamic_info) => self ! dynamic_info
        case lightning.neighbor_onion.Next.ChannelStatesRequest(req) => self ! req
        case lightning.neighbor_onion.Next.ChannelStatesResponse(res) => self ! res
      }
    case msg@beacon_req(origin, channels) =>
      log.debug(s"received beacon_req msg from $origin with channels=${channels2string(channels)}")
      // adding routes to my table so that I can reply to the origin
      val updates = channels.map(channel => routing_table_update(ChannelOpen(channel))).toList
      // TODO : maybe we should allow an even larger radius, because their MAX_BEACON_DISTANCE could be larger than ours
      val (graph1, _, dijkstra1) = include(myself, graph, updates, MAX_BEACON_DISTANCE, Set())
      val (channels1, route) = findRoute(dijkstra1, getOrAdd(graph1, myself), getOrAdd(graph1, origin))
      // looking for a strictly better beacon in our neighborhood
      val nodes: Set[bitcoin_pubkey] = graph.getNodeSet[MultiNode].map(n => node2pubkey(n)).toSet - origin - myself
      val distances = nodes.map(node => (node, distance(origin, node)))
      val distance2me = distance(origin, myself)
      val selected = distances
        .filter(_._2 < distance2me).toList
        .sortBy(_._2).map(_._1)
        .map(alternate => {
          val (channels2, _) = findRoute(dijkstra1, getOrAdd(graph1, myself), getOrAdd(graph1, alternate))
          (alternate, channels2, channels1.size + channels2.size)
        })
      selected.headOption match {
        // order matters!
        case Some((alternate, channels2, hops)) if hops > MAX_BEACON_DISTANCE =>
          log.debug(s"alternate $alternate was better but it is too far from $origin (hops=$hops)")
          send(route, neighbors, neighbor_onion(Ack(beacon_ack(myself, None, channels1))))
        case Some((alternate, channels2, hops)) =>
          // we reply with a better beacon and give them a route
          // maybe not the shortest route but we want to be on the path (that's in our interest)
          log.debug(s"recommending alternate $alternate to $origin (hops=$hops)")
          send(route, neighbors, neighbor_onion(Ack(beacon_ack(myself, Some(alternate), channels1 ++ channels2))))
        case _ =>
          // we accept to be their beacon
          send(route, neighbors, neighbor_onion(Ack(beacon_ack(myself, None, channels1))))
      }
    case msg@beacon_ack(origin, alternative_opt, channels) =>
      log.debug(s"received beacon_ack msg from $origin with alternative=$alternative_opt channels=${channels2string(channels)}")
      val updates = channels.map(channel => routing_table_update(ChannelOpen(channel))).toList
      val (graph1, _, dijkstra1) = include(myself, graph, updates, MAX_BEACON_DISTANCE, Set())
      alternative_opt match {
        // order matters!
        case Some(alternative) if distance(myself, alternative) > distance(myself, origin) =>
          log.warning(s"$origin is lying ! dist(us, alt) > dist(us, origin)")
        // TODO we should probably blacklist them
        case Some(alternative) if Option(graph1.getNode[MultiNode](pubkey2string(alternative))).isEmpty =>
          log.debug(s"the proposed alternative $alternative is not in our graph (probably too far away)")
        case Some(alternative) =>
          log.debug(s"sending alternative $alternative a beacon_req")
          val (channels1, route) = findRoute(dijkstra1, getOrAdd(graph1, myself), getOrAdd(graph1, alternative))
          send(route, neighbors, neighbor_onion(Req(beacon_req(myself, channels1))))
        case None => {} // nothing to do
      }
      val beacons1 = if (Option(graph1.getNode[MultiNode](pubkey2string(origin))).isEmpty) {
        log.debug(s"origin $origin is not in our graph (probably too far away)")
        beacons
      } else if (beacons.map(_.id).contains(origin)) {
        log.debug(s"we already have $origin as a beacon")
        beacons
      } else if (beacons.size < beaconCount) {
        log.info(s"adding $origin as a beacon")
        val (channels1, _) = findRoute(dijkstra1, getOrAdd(graph1, myself), getOrAdd(graph1, origin))
        beacons + Beacon(origin, distance(myself, origin), channels1.size)
      } else if (beacons.exists(b => b.distance > distance(myself, origin))) {
        val deprecatedBeacon = beacons.find(b => b.distance > distance(myself, origin)).get
        log.info(s"replacing $deprecatedBeacon by $origin")
        val (channels1, _) = findRoute(dijkstra1, getOrAdd(graph1, myself), getOrAdd(graph1, origin))
        beacons - deprecatedBeacon + Beacon(origin, distance(myself, origin), channels1.size)
      } else {
        log.info(s"ignoring beacon candidate $origin")
        beacons
      }
      // this will cause old beacons to be removed from the graph
      val (graph2, _, dijkstra2) = include(myself, graph1, Nil, radius, beacons1.map(_.id))
      log.debug(s"graph is now ${graph2string(graph2)}")
      log.debug(s"my beacons are now ${beacons1.map(b => s"${pubkey2string(b.id).take(6)}(${b.hops})").mkString(",")}")
      context become main(graph2, dijkstra2, neighbors, routingUpdatesBatch, channelUpdatesBatch, beacons1, channelStates, subscribed, subscribers, mysequence, promises)
    case msg@subscribe(origin, channels) =>
      log.debug(s"received register msg from $origin with channels=${channels2string(channels)}")
      // adding routes to my table so that I can reply to the origin
      val updates = channels.map(channel => routing_table_update(ChannelOpen(channel))).toList
      // TODO : maybe we should allow an even larger radius, because their MAX_BEACON_DISTANCE could be larger than ours
      val (graph1, _, dijkstra1) = include(myself, graph, updates, MAX_BEACON_DISTANCE, Set())
      val (_, route) = findRoute(dijkstra1, getOrAdd(graph1, myself), getOrAdd(graph1, origin))
      // sending my current states to this new subscriber
      channelStates.values.filter(_.node == myself).foreach(u => send(route, neighbors, neighbor_onion(DynamicInfo(u))))
      val subscribers1 = subscribers + (origin -> route)
      log.debug(s"subscribers are now ${subscribers2string(subscribers1)}")
      context become main(graph, dijkstra, neighbors, routingUpdatesBatch, channelUpdatesBatch, beacons, channelStates, subscribed, subscribers1, mysequence, promises)
    case msg@unsubscribe(origin) =>
      log.info(s"received unregister msg from $origin")
      val subscribers1 = subscribers - origin
      log.debug(s"subscribers are now ${subscribers2string(subscribers1)}")
      context become main(graph, dijkstra, neighbors, routingUpdatesBatch, channelUpdatesBatch, beacons, channelStates, subscribed, subscribers1, mysequence, promises)
    case msg@channel_state_update(channelId, sequence, node, amountMsat, relayFee) =>
      log.debug(s"received dynamic info from $node for channelId=$channelId")
      val channelStates1 = if (Option(graph.getEdge[Edge](sha2562string(channelId))).isDefined) {
        channelStates.get(ChannelOneEnd(channelId, node)) match {
          case Some(s) if s.sequence < sequence =>
            log.debug(s"received channel state update #$sequence for channelId=$channelId node=$node amountMsat=$amountMsat relayFee=$relayFee")
            channelStates + (ChannelOneEnd(channelId, node) -> msg)
          case Some(s) =>
            log.debug(s"ignoring channel state update #$sequence for channelId=$channelId node=$node (we are at sequence #${s.sequence}")
            channelStates
          case None =>
            log.debug(s"received first channel state update #$sequence for channelId=$channelId node=$node amountMsat=$amountMsat relayFee=$relayFee")
            channelStates + (ChannelOneEnd(channelId, node) -> msg)
        }
      } else channelStates
      log.debug(s"channel states are now ${states2string(channelStates1)}")
      context become main(graph, dijkstra, neighbors, routingUpdatesBatch, channelUpdatesBatch, beacons, channelStates1, subscribed, subscribers, mysequence, promises)
    case RouteRequest(req, maxTries) =>
      log.info(s"received request ${req.rHash} to ${req.nodeId} with maxTries=$maxTries")
      val s = sender
      val mergedStates = merge(channelStates, req.states)
      val firstTry = Future(Option(findPaymentRoute(myself, req.nodeId, req.amountMsat, mergedStates.values)))
      val alternate = graph.getNodeIterator[MultiNode]
        .toList
        .map(n => node2pubkey(n))
        .map(node => (node, distance(req.nodeId, node)))
        .sortBy(_._2)
        .map(_._1)
        .take(maxTries)
      firstTry.map(r => self ! RouteRequestWip(r, s, req, alternate, mergedStates)).onFailure {
        case t: Throwable => t.printStackTrace()
      }
    case RouteRequestWip(result, s, req, alternate, mergedStates) =>
      result match {
        case Some(route) =>
          log.info(s"found route of size ${route.size} for payment request ${req.rHash} to ${req.nodeId}")
          s ! RouteResponse(route)
          context become main(graph, dijkstra, neighbors, routingUpdatesBatch, channelUpdatesBatch, beacons, channelStates, subscribed, subscribers, mysequence, promises - req.rHash)
        case None if alternate.headOption.isDefined =>
          val node = alternate.head
          log.info(s"sending route request to alternate ${pubkey2string(node)}")
          // this will hold alternate node's dynamic infos
          // TODO: add timeout
          val promise = Promise[Seq[channel_state_update]]()
          val (channels, route) = findRoute(dijkstra, getOrAdd(graph, myself), getOrAdd(graph, node))
          send(route, neighbors, neighbor_onion(ChannelStatesRequest(channel_states_request(myself, req.rHash, channels))))
          promise.future
            .map(states => {
              val mergedStates1 = merge(mergedStates, states)
              (mergedStates1, Option(findPaymentRoute(myself, req.nodeId, req.amountMsat, mergedStates1.values)))
            })
            .map(r => self ! RouteRequestWip(r._2, s, req, alternate.drop(1), r._1))
          context become main(graph, dijkstra, neighbors, routingUpdatesBatch, channelUpdatesBatch, beacons, channelStates, subscribed, subscribers, mysequence, promises + (req.rHash -> promise))
        case None =>
          log.info(s"cannot find route for payment request ${req.rHash} to ${req.nodeId}")
          s ! actor.Status.Failure(new RuntimeException("route not found"))
          context become main(graph, dijkstra, neighbors, routingUpdatesBatch, channelUpdatesBatch, beacons, channelStates, subscribed, subscribers, mysequence, promises - req.rHash)
      }
    case msg@channel_states_request(origin, requestId, channels) =>
      log.debug(s"received channel_states_request from $origin with requestId=$requestId")
      val updates = channels.map(channel => routing_table_update(ChannelOpen(channel))).toList
      val (graph1, _, dijkstra1) = include(myself, graph, updates, MAX_BEACON_DISTANCE, Set())
      val (_, route) = findRoute(dijkstra1, getOrAdd(graph1, myself), getOrAdd(graph1, origin))
      send(route, neighbors, neighbor_onion(ChannelStatesResponse(channel_states_response(myself, requestId, channelStates.values.toSeq))))
    case msg@channel_states_response(origin, requestId, states) =>
      log.debug(s"received channel_states_response from $origin with requestId=$requestId")
      promises.get(requestId).map(p => p.success(states))
    case 'network =>
      sender ! graph2table(graph).channels
    case 'states =>
      sender ! channelStates.values.toSeq
    case 'beacons =>
      sender ! beacons
    case 'info =>
      sender ! FlareInfo(neighbors.map(_.node_id).size, graph.getNodeCount, beacons)
    case 'dot =>
      sender ! graph2dot(myself, graph, beacons.map(_.id), channelStates)
  }

  def send(route: Seq[bitcoin_pubkey], neighbors: List[Neighbor], msg: neighbor_onion): Unit = {
    require(route.size >= 2, s"$route should be of size >=2 (neighbors=$neighbors msg=$msg)")
    val onion = buildOnion(route.drop(2), msg)
    if (route.size < 2) {
      log.warning(s"invalid route $route")
    }
    val neighbor = route(1)
    neighbor2channel(neighbor, neighbors) match {
      case Some(actorSelection) => actorSelection ! onion
      case None => log.error(s"could not find channel for neighbor $neighbor")
    }
  }

}


object FlareRouter {

  // @formatter:off
  case class Neighbor(node_id: bitcoin_pubkey, channel_id: sha256_hash, connection: ActorRef, sent: List[routing_table_update])
  case class Beacon(id: bitcoin_pubkey, distance: BigInt, hops: Int)
  case class ChannelOneEnd(channel_id: sha256_hash, node_id: bitcoin_pubkey)
  case class FlareInfo(neighbors: Int, known_nodes: Int, beacons: Set[Beacon])
  case class RouteRequest(req: payment_request, alternateTries: Int = 0)
  case class RouteRequestWip(result: Option[Seq[bitcoin_pubkey]], sender: ActorRef, req: payment_request, alternate: Seq[bitcoin_pubkey], wipStates: Map[ChannelOneEnd, channel_state_update])
  case class RouteResponse(route: Seq[bitcoin_pubkey])
  // @formatter:on

  def commitments2channelState(sequence: Int, myself: bitcoin_pubkey, commitments: Commitments): channel_state_update =
  // TODO : no fees
    channel_state_update(commitments.anchorId, sequence, myself, commitments.ourCommit.spec.amount_us_msat.toInt, 0)

  def graph2table(graph: MultiGraph): routing_table =
    routing_table(graph.getEdgeSet[Edge].map(edge2ChannelDesc(_)).toSeq)

  def node2pubkey(node: Node): bitcoin_pubkey =
    bin2pubkey(BinaryData(node.getId))

  def edge2ChannelDesc(edge: Edge): channel_open =
    channel_open(bin2sha256(BinaryData(edge.getId)), bin2pubkey(BinaryData(edge.getSourceNode[MultiNode].getId)), bin2pubkey(BinaryData(edge.getTargetNode[MultiNode].getId)))

  def channelId2sha256(edge: Edge): sha256_hash =
    bin2sha256(BinaryData(edge.getId))

  def graph2string(graph: MultiGraph): String =
    s"node_count=${graph.getNodeCount} edges: " + channels2string(graph2table(graph).channels)

  def states2string(states: Map[ChannelOneEnd, channel_state_update]): String =
    states.values.map(s => s"${pubkey2string(s.node)}@${s.channelId} -> avail: ${s.amountMsat} fee: ${s.relayFee}").mkString(",")

  def subscribers2string(suscribers: Map[bitcoin_pubkey, Seq[bitcoin_pubkey]]): String =
    s"suscribers=${suscribers.keys.map(n => pubkey2string(n).take(6)).mkString(",")}"

  def pubkey2string(pubkey: bitcoin_pubkey): String =
    pubkey2bin(pubkey).toString()

  def sha2562string(hash: sha256_hash): String =
    sha2562bin(hash).toString()

  def channels2string(channels: Seq[channel_open]): String =
    channels.map(c => s"${pubkey2string(c.nodeA).take(6)}->${pubkey2string(c.nodeB).take(6)}").mkString(" ")

  def graph2dot(myself: bitcoin_pubkey, g: MultiGraph, beacons: Set[bitcoin_pubkey], channelStates: Map[ChannelOneEnd, channel_state_update]): String = {
    val nodes = g.getNodeIterator[MultiNode].map(node => s""""${node.getId}" [label="${node.getId.take(6)}" tooltip="${node.getId}"];""").mkString("\n")
    val centerColor = s""""${pubkey2string(myself)}" [color=blue];"""
    val beaconsColor = beacons.map(beacon => s""""${pubkey2string(beacon)}" [color=red];""").mkString("\n")
    val edges = g.getEdgeSet[Edge]
      .flatMap(edge => {
        val src = ChannelOneEnd(BinaryData(edge.getId), BinaryData(edge.getSourceNode[MultiNode].getId))
        val srcAvail = channelStates.get(src).map(_.amountMsat)
        val tgt = ChannelOneEnd(BinaryData(edge.getId), BinaryData(edge.getTargetNode[MultiNode].getId))
        val tgtAvail = channelStates.get(tgt).map(_.amountMsat)
        s""" "${pubkey2string(src.node_id)}" -> "${pubkey2string(tgt.node_id)}" [fontsize=8 labeltooltip="${edge.getId}" label=${srcAvail.getOrElse("unknown")}];""" ::
          s""" "${pubkey2string(tgt.node_id)}" -> "${pubkey2string(src.node_id)}" [fontsize=8 labeltooltip="${edge.getId}" label=${tgtAvail.getOrElse("unknown")}];""" :: Nil
      }).mkString("\n")

    s"""digraph G {
        |rankdir=LR;
        |$nodes
        |$centerColor
        |$beaconsColor
        |$edges
        |}
    """.stripMargin
  }

  def copy(graph: MultiGraph): MultiGraph = {
    val g1 = new MultiGraph(UUID.randomUUID().toString)
    graph.getNodeIterator[MultiNode].foreach(node => g1.addNode[MultiNode](node.getId))
    graph.getEdgeIterator[Edge].foreach(edge => g1.addEdge[Edge](edge.getId, g1.getNode[MultiNode](edge.getSourceNode[MultiNode].getId), g1.getNode[MultiNode](edge.getTargetNode[MultiNode].getId), edge.isDirected))
    g1
  }

  def merge(myself: bitcoin_pubkey, graph: MultiGraph, table2: routing_table, radius: Int, beacons: Set[bitcoin_pubkey]): (MultiGraph, Dijkstra) = {
    val updates = table2.channels.map(c => routing_table_update(ChannelOpen(c)))
    val (graph1, _, dijkstra1) = include(myself, graph, updates, radius, beacons)
    (graph1, dijkstra1)
  }

  def getOrAdd(g: MultiGraph, node: bitcoin_pubkey): MultiNode = {
    val id = pubkey2string(node)
    Option(g.getNode[MultiNode](id)).getOrElse(g.addNode[MultiNode](id))
  }

  def include(myself: bitcoin_pubkey, graph: MultiGraph, updates: Seq[routing_table_update], radius: Int, beacons: Set[bitcoin_pubkey]): (MultiGraph, Seq[routing_table_update], Dijkstra) = {
    val graph1 = copy(graph)
    updates.collect {
      case routing_table_update(ChannelOpen(channel_open(channelId, nodeA, nodeB))) =>
        val a = getOrAdd(graph1, nodeA)
        val b = getOrAdd(graph1, nodeB)
        // optimistic, doesn't matter if it fails
        Try(graph1.addEdge[Edge](sha2562string(channelId), a, b))
      case routing_table_update(ChannelClose(channel_close(channelId))) =>
        // optimistic, doesn't matter if it fails
        Try(graph1.removeEdge[Edge](sha2562string(channelId)))
    }
    val myselfNode = graph1.getNode[MultiNode](pubkey2string(myself))
    val dijkstra = new Dijkstra()
    dijkstra.init(graph1)
    dijkstra.setSource(myselfNode)
    dijkstra.compute()

    // we whitelist all nodes on the path to beacons
    val whitelist = beacons.flatMap(beacon => dijkstra.getPathNodes[MultiNode](graph1.getNode[MultiNode](pubkey2string(beacon))))
    val neighbors = graph1.getNodeSet[MultiNode].toSet -- whitelist - myselfNode
    // we remove all nodes farther than radius and not on the path to beacons
    neighbors.collect {
      // this also eliminates unreachable nodes (infinite path length)
      case node if (dijkstra.getPathLength(node) > radius) => graph1.removeNode(node)
    }

    val origChannels = graph.getEdgeSet[Edge].map(e => BinaryData(e.getId)).toSet
    val newChannels = graph1.getEdgeSet[Edge].map(e => BinaryData(e.getId)).toSet
    val updates1 = updates.collect {
      case u@routing_table_update(ChannelOpen(channel_open(channelId, _, _))) if !origChannels.contains(channelId) && newChannels.contains(channelId) => u
      case u@routing_table_update(ChannelClose(channel_close(channelId))) if origChannels.contains(channelId) && !newChannels.contains(channelId) => u
    }
    (graph1, updates1, dijkstra)
  }

  def findRoute(dijkstra: Dijkstra, source: MultiNode, target: MultiNode): (Seq[channel_open], Seq[bitcoin_pubkey]) = {
    // make sure that the precomputed dijkstra was for the same source
    require(dijkstra.getSource[MultiNode] == source)
    // note: dijkstra path are given in reverse orders
    val channels = dijkstra.getPathEdges[Edge](target).map(edge => edge2ChannelDesc(edge)).toSeq.reverse
    val nodes = dijkstra.getPathNodes[MultiNode](target).map(node => node2pubkey(node)).toSeq.reverse
    (channels, nodes)
  }

  def buildOnion(nodes: Seq[bitcoin_pubkey], msg: neighbor_onion): neighbor_onion = {
    nodes.reverse.foldLeft(msg) {
      case (o, next) => neighbor_onion(Forward(forward(next, o)))
    }
  }

  def neighbor2channel(node_id: bitcoin_pubkey, neighbors: List[Neighbor]): Option[ActorRef] =
    neighbors.find(_.node_id == node_id).map(_.connection)

  def distance(a: BinaryData, b: BinaryData): BigInt = {
    if (a.length != b.length) {
      println("foo")
    }
    require(a.length == b.length)
    val c = new Array[Byte](a.length)
    for (i <- 0 until a.length) c(i) = ((a.data(i) ^ b.data(i)) & 0xff).toByte
    BigInt(1, c)
  }

  def merge(states1: Map[ChannelOneEnd, channel_state_update], states2: Seq[channel_state_update]): Map[ChannelOneEnd, channel_state_update] = {
    states2.foldLeft(states1) {
      case (m, u) if !m.containsKey(ChannelOneEnd(u.channelId, u.node)) => m + (ChannelOneEnd(u.channelId, u.node) -> u)
      case (m, u) if m(ChannelOneEnd(u.channelId, u.node)).sequence < u.sequence => m + (ChannelOneEnd(u.channelId, u.node) -> u)
      case (m, _) => m
    }
  }

  def findPaymentRoute(source: bitcoin_pubkey, target: bitcoin_pubkey, amountMsat: Int, states: Iterable[channel_state_update]): Seq[bitcoin_pubkey] = {
    val g = new MultiGraph(UUID.randomUUID().toString)
    // unique nodes
    val nodes = states.map(_.node).toSet
    nodes.foreach(node => g.addNode[MultiNode](pubkey2string(node)))
    // edges for which we have the two ends
    val edges = states
      .groupBy(_.channelId)
      .mapValues {
        case states if states.size == 1 => (states.head.node, null)
        case states if states.size == 2 => (states.head.node, states.drop(1).head.node)
        case states if states.size > 2 => throw new RuntimeException("more than 2 nodes attached to same channel, should never happen!")
      }
    // let's filter out channels that don't have the required amount available
    // note: +10 % because fees, which are cumulative so need to be conservative
    val states1 = states.filter(_.amountMsat > 1.1 * amountMsat)
    // adding *directed* edges
    states1.collect {
      case u if edges(u.channelId)._1 != u.node => g.addEdge[Edge](sha2562string(u.channelId) + pubkey2string(u.node), pubkey2string(u.node), pubkey2string(edges(u.channelId)._1), true)
      case u if edges(u.channelId)._2 != null && edges(u.channelId)._2 != u.node => g.addEdge[Edge](sha2562string(u.channelId) + pubkey2string(u.node), pubkey2string(u.node), pubkey2string(edges(u.channelId)._2), true)
    }
    val dijkstra = new Dijkstra()
    dijkstra.init(g)
    dijkstra.setSource(g.getNode[MultiNode](pubkey2string(source)))
    dijkstra.compute()
    dijkstra.getPath(g.getNode[MultiNode](pubkey2string(target))).getNodePath.map(node2pubkey(_))
  }

}