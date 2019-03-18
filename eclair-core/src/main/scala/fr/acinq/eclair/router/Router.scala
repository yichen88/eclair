/*
 * Copyright 2018 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.router

import akka.Done
import akka.actor.{ActorRef, Props, Status}
import akka.event.Logging.MDC
import fr.acinq.bitcoin.{ByteVector32, Satoshi}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, Satoshi}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.io.Peer.{InvalidSignature, PeerRoutingMessage}
import fr.acinq.eclair.io.Peer.{ChannelClosed, InvalidAnnouncement, InvalidSignature, PeerRoutingMessage}
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.router.Graph.GraphStructure.DirectedGraph.graphEdgeToHop
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.router.Graph.{RichWeight, WeightRatios}
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire._
import scodec.bits.ByteVector

import scala.collection.immutable.SortedMap
import scala.collection.{SortedSet, mutable}
import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Promise}
import scala.util.{Random, Try}

// @formatter:off

case class RouterConf(randomizeRouteSelection: Boolean,
                      channelExcludeDuration: FiniteDuration,
                      routerBroadcastInterval: FiniteDuration,
                      searchMaxFeeBaseSat: Long,
                      searchMaxFeePct: Double,
                      searchMaxRouteLength: Int,
                      searchMaxCltv: Int,
                      searchHeuristicsEnabled: Boolean,
                      searchRatioCltv: Double,
                      searchRatioChannelAge: Double,
                      searchRatioChannelCapacity: Double)

case class ChannelDesc(shortChannelId: ShortChannelId, a: PublicKey, b: PublicKey)

case class PublicChannel(ann: ChannelAnnouncement, fundingTxid: ByteVector32, capacity: Satoshi, update_1_opt: Option[ChannelUpdate], update_2_opt: Option[ChannelUpdate]) {
  def getNodeIdSameSideAs(u: ChannelUpdate): PublicKey = if (Announcements.isNode1(u.channelFlags)) ann.nodeId1 else ann.nodeId2
  def getSameSideAs(u: ChannelUpdate): Option[ChannelUpdate] = if (Announcements.isNode1(u.channelFlags)) update_1_opt else update_2_opt
  def updateSameSideAs(u: ChannelUpdate): PublicChannel = if (Announcements.isNode1(u.channelFlags)) copy(update_1_opt = Some(u)) else copy(update_2_opt = Some(u))
  def updateFor(n: PublicKey): Option[ChannelUpdate] = if (n == ann.nodeId1) update_1_opt else if (n == ann.nodeId2) update_2_opt else throw new IllegalArgumentException("this node is unrelated to this channel")
}
case class PrivateChannel(nodeId: PublicKey, update_1_opt: Option[ChannelUpdate], update_2_opt: Option[ChannelUpdate])(implicit nodeParams: NodeParams) {
  val (nodeId1, nodeId2) = if (Announcements.isNode1(nodeParams.nodeId, nodeId)) (nodeParams.nodeId, nodeId) else (nodeId, nodeParams.nodeId)
  def getNodeIdSameSideAs(u: ChannelUpdate): PublicKey = if (Announcements.isNode1(u.channelFlags)) nodeId1 else nodeId2
  def getSameSideAs(u: ChannelUpdate): Option[ChannelUpdate] = if (Announcements.isNode1(u.channelFlags)) update_1_opt else update_2_opt
  def updateSameSideAs(u: ChannelUpdate): PrivateChannel = if (Announcements.isNode1(u.channelFlags)) copy(update_1_opt = Some(u)) else copy(update_2_opt = Some(u))
}

case class AssistedChannel(extraHop: ExtraHop, nextNodeId: PublicKey)

case class Hop(nodeId: PublicKey, nextNodeId: PublicKey, lastUpdate: ChannelUpdate)
case class RouteParams(randomize: Boolean, maxFeeBaseMsat: Long, maxFeePct: Double, routeMaxLength: Int, routeMaxCltv: Int, ratios: Option[WeightRatios])
case class RouteRequest(source: PublicKey,
                        target: PublicKey,
                        amountMsat: Long,
                        assistedRoutes: Seq[Seq[ExtraHop]] = Nil,
                        ignoreNodes: Set[PublicKey] = Set.empty,
                        ignoreChannels: Set[ChannelDesc] = Set.empty,
                        routeParams: Option[RouteParams] = None)

case class RouteResponse(hops: Seq[Hop], ignoreNodes: Set[PublicKey], ignoreChannels: Set[ChannelDesc]) {
  require(hops.size > 0, "route cannot be empty")
}
case class ExcludeChannel(desc: ChannelDesc) // this is used when we get a TemporaryChannelFailure, to give time for the channel to recover (note that exclusions are directed)
case class LiftChannelExclusion(desc: ChannelDesc)
case class SendChannelQuery(remoteNodeId: PublicKey, to: ActorRef)
case class SendChannelQueryEx(remoteNodeId: PublicKey, to: ActorRef)
case object GetRoutingState
case class RoutingState(channels: Iterable[PublicChannel], nodes: Iterable[NodeAnnouncement])
case class Stash(updates: Map[ChannelUpdate, Set[ActorRef]], nodes: Map[NodeAnnouncement, Set[ActorRef]])
case class Rebroadcast(channels: Map[ChannelAnnouncement, Set[ActorRef]], updates: Map[ChannelUpdate, Set[ActorRef]], nodes: Map[NodeAnnouncement, Set[ActorRef]])

case class Sync(missing: SortedSet[ShortChannelId], totalMissingCount: Int, outdated: SortedSet[ShortChannelId] = SortedSet.empty[ShortChannelId], totalOutdatedCount: Int = 0)

case class Data(nodes: Map[PublicKey, NodeAnnouncement],
                channels: SortedMap[ShortChannelId, PublicChannel],
                stash: Stash,
                awaiting: Map[ChannelAnnouncement, Seq[ActorRef]], // note: this is a seq because we want to preserve order: first actor is the one who we need to send a tcp-ack when validation is done
                privateChannels: Map[ShortChannelId, PrivateChannel], // short_channel_id -> node_id
                excludedChannels: Set[ChannelDesc], // those channels are temporarily excluded from route calculation, because their node returned a TemporaryChannelFailure
                graph: DirectedGraph,
                sync: Map[PublicKey, Sync] // keep tracks of channel range queries sent to each peer. If there is an entry in the map, it means that there is an ongoing query
                                           // for which we have not yet received an 'end' message
               )

sealed trait State
case object NORMAL extends State

case object TickBroadcast
case object TickPruneStaleChannels

// @formatter:on

/**
  * Created by PM on 24/05/2016.
  */

class Router(nodeParams: NodeParams, watcher: ActorRef, initialized: Option[Promise[Done]] = None) extends FSMDiagnosticActorLogging[State, Data] {

  import Router._

  implicit val np = nodeParams

  import ExecutionContext.Implicits.global

  context.system.eventStream.subscribe(self, classOf[LocalChannelUpdate])
  context.system.eventStream.subscribe(self, classOf[LocalChannelDown])

  setTimer(TickBroadcast.toString, TickBroadcast, nodeParams.routerConf.routerBroadcastInterval, repeat = true)
  setTimer(TickPruneStaleChannels.toString, TickPruneStaleChannels, 1 hour, repeat = true)

  val SHORTID_WINDOW = 100

  val defaultRouteParams = RouteParams(
    randomize = nodeParams.routerConf.randomizeRouteSelection,
    maxFeeBaseMsat = nodeParams.routerConf.searchMaxFeeBaseSat * 1000, // converting sat -> msat
    maxFeePct = nodeParams.routerConf.searchMaxFeePct,
    routeMaxLength = nodeParams.routerConf.searchMaxRouteLength,
    routeMaxCltv = nodeParams.routerConf.searchMaxCltv,
    ratios = nodeParams.routerConf.searchHeuristicsEnabled match {
      case false => None
      case true => Some(WeightRatios(
        cltvDeltaFactor = nodeParams.routerConf.searchRatioCltv,
        ageFactor = nodeParams.routerConf.searchRatioChannelAge,
        capacityFactor = nodeParams.routerConf.searchRatioChannelCapacity
      ))
    }
  )

  val db = nodeParams.networkDb

  {
    log.info("loading network announcements from db...")
    // On Android, we discard the node announcements
    val channels = db.listChannels()
    log.info("loaded from db: channels={} nodes={}", channels.size)
    val initChannels = channels
    // this will be used to calculate routes
    val graph = DirectedGraph.makeGraph(initChannels)

    log.info(s"initialization completed, ready to process messages")
    Try(initialized.map(_.success(Done)))
    startWith(NORMAL, Data(Map.empty, initChannels, Stash(Map.empty, Map.empty), awaiting = Map.empty, privateChannels = Map.empty, excludedChannels = Set.empty, graph, sync = Map.empty))
  }

  when(NORMAL) {
    case Event(LocalChannelUpdate(_, _, shortChannelId, remoteNodeId, channelAnnouncement_opt, u, _), d: Data) =>
      d.channels.get(shortChannelId) match {
        case Some(_) =>
          // channel has already been announced and router knows about it, we can process the channel_update
          stay using handle(u, self, d)
        case None =>
          channelAnnouncement_opt match {
            case Some(c) if d.awaiting.contains(c) =>
              // channel is currently being verified, we can process the channel_update right away (it will be stashed)
              stay using handle(u, self, d)
            case Some(c) =>
              // channel wasn't announced but here is the announcement, we will process it *before* the channel_update
              watcher ! ValidateRequest(c)
              val d1 = d.copy(awaiting = d.awaiting + (c -> Nil)) // no origin
              // On android we don't track pruned channels in our db
              stay using handle(u, self, d1)
            case None if d.privateChannels.contains(shortChannelId) =>
              // channel isn't announced but we already know about it, we can process the channel_update
              stay using handle(u, self, d)
            case None =>
              // channel isn't announced and we never heard of it (maybe it is a private channel or maybe it is a public channel that doesn't yet have 6 confirmations)
              // let's create a corresponding private channel and process the channel_update
              log.info("adding unannounced local channel to remote={} shortChannelId={}", remoteNodeId, shortChannelId)
              stay using handle(u, self, d.copy(privateChannels = d.privateChannels + (shortChannelId -> PrivateChannel(remoteNodeId, None, None))))
          }
      }

    case Event(LocalChannelDown(_, channelId, shortChannelId, remoteNodeId), d: Data) =>
      // a local channel has permanently gone down
      if (d.channels.contains(shortChannelId)) {
        // the channel was public, we will receive (or have already received) a WatchEventSpentBasic event, that will trigger a clean up of the channel
        // so let's not do anything here
        stay
      } else if (d.privateChannels.contains(shortChannelId)) {
        // the channel was private or public-but-not-yet-announced, let's do the clean up
        log.debug("removing private local channel and channel_update for channelId={} shortChannelId={}", channelId, shortChannelId)
        val desc1 = ChannelDesc(shortChannelId, nodeParams.nodeId, remoteNodeId)
        val desc2 = ChannelDesc(shortChannelId, remoteNodeId, nodeParams.nodeId)
        // we remove the corresponding updates from the graph
        val graph1 = d.graph
          .removeEdge(desc1)
          .removeEdge(desc2)
        // and we remove the channel and channel_update from our state
        stay using d.copy(privateChannels = d.privateChannels - shortChannelId, graph = graph1)
      } else {
        stay
      }

    case Event(GetRoutingState, d: Data) =>
      stay // ignored on Android

    case Event(WatchEventSpentBasic(BITCOIN_FUNDING_EXTERNAL_CHANNEL_SPENT(shortChannelId)), d) if d.channels.contains(shortChannelId) =>
      val lostChannel = d.channels(shortChannelId).ann
      log.info("funding tx of channelId={} has been spent", shortChannelId)
      // we need to remove nodes that aren't tied to any channels anymore
      val channels1 = d.channels - lostChannel.shortChannelId
      val lostNodes = Seq(lostChannel.nodeId1, lostChannel.nodeId2).filterNot(nodeId => hasChannels(nodeId, channels1.values))
      // let's clean the db and send the events
      log.info("pruning shortChannelId={} (spent)", shortChannelId)
      db.removeChannel(shortChannelId) // NB: this also removes channel updates
    // we also need to remove updates from the graph
    val graph1 = d.graph
      .removeEdge(ChannelDesc(lostChannel.shortChannelId, lostChannel.nodeId1, lostChannel.nodeId2))
      .removeEdge(ChannelDesc(lostChannel.shortChannelId, lostChannel.nodeId2, lostChannel.nodeId1))

      context.system.eventStream.publish(ChannelLost(shortChannelId))
      lostNodes.foreach {
        case nodeId =>
          log.info("pruning nodeId={} (spent)", nodeId)
          db.removeNode(nodeId)
          context.system.eventStream.publish(NodeLost(nodeId))
      }
      stay using d.copy(nodes = d.nodes -- lostNodes, channels = d.channels - shortChannelId, graph = graph1)

    case Event(TickBroadcast, d) =>
      // On Android we don't rebroadcast announcements
      stay

    case Event(TickPruneStaleChannels, d) =>
      // first we select channels that we will prune
      val staleChannels = getStaleChannels(d.channels.values)
      val staleChannelIds = staleChannels.map(_.ann.shortChannelId)
      // then we remove nodes that aren't tied to any channels anymore (and deduplicate them)
      val channels1 = d.channels -- staleChannelIds

      // let's clean the db and send the events
      db.removeChannels(staleChannelIds) // NB: this also removes channel updates
      // On Android we don't track pruned channels in our db
      staleChannelIds.foreach { shortChannelId =>
        log.info("pruning shortChannelId={} (stale)", shortChannelId)
        context.system.eventStream.publish(ChannelLost(shortChannelId))
      }
      // we also need to remove updates from the graph
      val staleChannelsToRemove = new mutable.MutableList[ChannelDesc]
      staleChannels.foreach(ca => {
        staleChannelsToRemove += ChannelDesc(ca.ann.shortChannelId, ca.ann.nodeId1, ca.ann.nodeId2)
        staleChannelsToRemove += ChannelDesc(ca.ann.shortChannelId, ca.ann.nodeId2, ca.ann.nodeId1)
      })

      val graph1 = d.graph.removeEdges(staleChannelsToRemove)
      stay using d.copy(channels = channels1, graph = graph1)

    case Event(ExcludeChannel(desc@ChannelDesc(shortChannelId, nodeId, _)), d) =>
      val banDuration = nodeParams.routerConf.channelExcludeDuration
      log.info("excluding shortChannelId={} from nodeId={} for duration={}", shortChannelId, nodeId, banDuration)
      context.system.scheduler.scheduleOnce(banDuration, self, LiftChannelExclusion(desc))
      stay using d.copy(excludedChannels = d.excludedChannels + desc)

    case Event(LiftChannelExclusion(desc@ChannelDesc(shortChannelId, nodeId, _)), d) =>
      log.info("reinstating shortChannelId={} from nodeId={}", shortChannelId, nodeId)
      stay using d.copy(excludedChannels = d.excludedChannels - desc)

    case Event('nodes, d) =>
      sender ! d.nodes.values
      stay

    case Event('channels, d) =>
      sender ! d.channels.values.map(_.ann)
      stay

    case Event('channelsMap, d) =>
      sender ! d.channels
      stay

    case Event('updates, d) =>
      val updates: Iterable[ChannelUpdate] = d.channels.values.flatMap(d => d.update_1_opt ++ d.update_2_opt) ++ d.privateChannels.values.flatMap(d => d.update_1_opt ++ d.update_2_opt)
      sender ! updates
      stay

    case Event('data, d) =>
      sender ! d
      stay

    case Event(RouteRequest(start, end, amount, assistedRoutes, ignoreNodes, ignoreChannels, params_opt), d) =>
      // we convert extra routing info provided in the payment request to fake channel_update
      // it takes precedence over all other channel_updates we know
      val assistedChannels: Map[ShortChannelId, AssistedChannel] = assistedRoutes.flatMap(toAssistedChannels(_, end)).toMap
      val extraEdges = assistedChannels.values.map(ac => GraphEdge(ChannelDesc(ac.extraHop.shortChannelId, ac.extraHop.nodeId, ac.nextNodeId), toFakeUpdate(ac.extraHop))).toSet
      val ignoredEdges = ignoreChannels ++ d.excludedChannels
      val params = params_opt.getOrElse(defaultRouteParams)
      val routesToFind = if (params.randomize) DEFAULT_ROUTES_COUNT else 1

      log.info(s"finding a route $start->$end with assistedChannels={} ignoreNodes={} ignoreChannels={} excludedChannels={}", assistedChannels.keys.mkString(","), ignoreNodes.map(_.toBin).mkString(","), ignoreChannels.mkString(","), d.excludedChannels.mkString(","))
      log.info(s"finding a route with randomize={} params={}", routesToFind > 1, params)
      findRoute(d.graph, start, end, amount, numRoutes = routesToFind, extraEdges = extraEdges, ignoredEdges = ignoredEdges, ignoredVertices = ignoreNodes, routeParams = params)
        .map(r => sender ! RouteResponse(r, ignoreNodes, ignoreChannels))
        .recover { case t => sender ! Status.Failure(t) }
      stay

    case Event(SendChannelQuery(remoteNodeId, remote), d) =>
      // ask for everything
      // we currently send only one query_channel_range message per peer, when we just (re)connected to it, so we don't
      // have to worry about sending a new query_channel_range when another query is still in progress
      val query = QueryChannelRange(nodeParams.chainHash, firstBlockNum = 0, numberOfBlocks = Int.MaxValue)
      log.info("sending query_channel_range={}", query)
      remote ! query

      // we also set a pass-all filter for now (we can update it later) for the future gossip messages, by setting
      // the first_timestamp field to the current date/time and timestamp_range to the maximum value
      // NB: we can't just set firstTimestamp to 0, because in that case peer would send us all past messages matching
      // that (i.e. the whole routing table)
      val filter = GossipTimestampFilter(nodeParams.chainHash, firstTimestamp = Platform.currentTime / 1000, timestampRange = Int.MaxValue)
      remote ! filter

      // clean our sync state for this peer: we receive a SendChannelQuery just when we connect/reconnect to a peer and
      // will start a new complete sync process
      stay using d.copy(sync = d.sync - remoteNodeId)

    case Event(SendChannelQueryEx(remoteNodeId, remote), d) =>
      // ask for everything
      val query = QueryChannelRangeEx(nodeParams.chainHash, firstBlockNum = 0, numberOfBlocks = Int.MaxValue)
      log.info("sending query_channel_range_ex={}", query)
      remote ! query
      // we also set a pass-all filter for now (we can update it later)
      val filter = GossipTimestampFilter(nodeParams.chainHash, firstTimestamp = 0, timestampRange = Int.MaxValue)
      remote ! filter
      // clean our sync state for this peer: we receive a SendChannelQuery just when we connect/reconnect to a peer and
      // will start a new complete sync process
      stay using d.copy(sync = d.sync - remoteNodeId)

    // Warning: order matters here, this must be the first match for HasChainHash messages !
    case Event(PeerRoutingMessage(_, _, routingMessage: HasChainHash), d) if routingMessage.chainHash != nodeParams.chainHash =>
      sender ! TransportHandler.ReadAck(routingMessage)
      log.warning("message {} for wrong chain {}, we're on {}", routingMessage, routingMessage.chainHash, nodeParams.chainHash)
      stay

    case Event(u: ChannelUpdate, d: Data) =>
      // it was sent by us, routing messages that are sent by  our peers are now wrapped in a PeerRoutingMessage
      log.debug("received channel update from {}", sender)
      stay using handle(u, sender, d)

    case Event(PeerRoutingMessage(transport, remoteNodeId, u: ChannelUpdate), d) =>
      sender ! TransportHandler.ReadAck(u)
      log.debug("received channel update for shortChannelId={}", u.shortChannelId)
      stay using handle(u, sender, d, remoteNodeId_opt = Some(remoteNodeId), transport_opt = Some(transport))

    case Event(PeerRoutingMessage(_, _, c: ChannelAnnouncement), d) =>
      log.debug("received channel announcement for shortChannelId={} nodeId1={} nodeId2={}", c.shortChannelId, c.nodeId1, c.nodeId2)
      if (d.channels.contains(c.shortChannelId)) {
        sender ! TransportHandler.ReadAck(c)
        log.debug("ignoring {} (duplicate)", c)
        stay
      } else if (d.awaiting.contains(c)) {
        sender ! TransportHandler.ReadAck(c)
        log.debug("ignoring {} (being verified)", c)
        // adding the sender to the list of origins so that we don't send back the same announcement to this peer later
        val origins = d.awaiting(c) :+ sender
        stay using d.copy(awaiting = d.awaiting + (c -> origins))
      } else if (!Announcements.checkSigs(c)) {
        // On Android we don't track pruned channels in our db
        sender ! TransportHandler.ReadAck(c)
        log.warning("bad signature for announcement {}", c)
        sender ! InvalidSignature(c)
        stay
      } else {
        // On Android, after checking the sig we remove as much data as possible to reduce RAM consumption
        val c1 = c.copy(
          nodeSignature1 = null,
          nodeSignature2 = null,
          bitcoinSignature1 = null,
          bitcoinSignature2 = null,
          features = null,
          chainHash = null,
          bitcoinKey1 = null,
          bitcoinKey2 = null)
        sender ! TransportHandler.ReadAck(c)
        // On Android, we don't validate announcements for now, it means that neither awaiting nor stashed announcements are used
        val pc = PublicChannel(c1, ByteVector32.Zeroes, Satoshi(0), None, None)
        db.addChannel(pc.ann, pc.fundingTxid, pc.capacity)
        stay using d.copy(
          channels = d.channels + (c1.shortChannelId -> pc),
          privateChannels = d.privateChannels - c1.shortChannelId // we remove fake announcements that we may have made before)
        )
      }

    case Event(n: NodeAnnouncement, d: Data) =>
      // it was sent by us, routing messages that are sent by  our peers are now wrapped in a PeerRoutingMessage
      stay // we just ignore node_announcements on Android

    case Event(PeerRoutingMessage(_, _, n: NodeAnnouncement), d: Data) =>
      sender ! TransportHandler.ReadAck(n)
      stay // we just ignore node_announcements on Android

    case Event(PeerRoutingMessage(transport, _, routingMessage@QueryChannelRange(chainHash, firstBlockNum, numberOfBlocks)), d) =>
      sender ! TransportHandler.ReadAck(routingMessage)
      // On Android we ignore queries
      stay

    case Event(PeerRoutingMessage(transport, remoteNodeId, routingMessage@ReplyChannelRange(chainHash, firstBlockNum, numberOfBlocks, _, data)), d) =>
      sender ! TransportHandler.ReadAck(routingMessage)
      val (format, theirShortChannelIds, useGzip) = ChannelRangeQueries.decodeShortChannelIds(data)
      val ourShortChannelIds: SortedSet[ShortChannelId] = d.channels.keySet.filter(keep(firstBlockNum, numberOfBlocks, _, d.channels))
      val missing: SortedSet[ShortChannelId] = theirShortChannelIds -- ourShortChannelIds
      log.info("received reply_channel_range, we're missing {} channel announcements/updates, format={} useGzip={}", missing.size, format, useGzip)

      val d1 = if (missing.nonEmpty) {
        // they may send back several reply_channel_range messages for a single query_channel_range query, and we must not
        // send another query_short_channel_ids query if they're still processing one
        d.sync.get(remoteNodeId) match {
          case None =>
            // we don't have a pending query with this peer
            val (slice, rest) = missing.splitAt(SHORTID_WINDOW)
            transport ! QueryShortChannelIds(chainHash, ChannelRangeQueries.encodeShortChannelIdsSingle(slice, format, useGzip))
            d.copy(sync = d.sync + (remoteNodeId -> Sync(rest, missing.size)))
          case Some(sync) =>
            // we already have a pending query with this peer, add missing ids to our "sync" state
            d.copy(sync = d.sync + (remoteNodeId -> Sync(sync.missing ++ missing, sync.totalMissingCount + missing.size)))
        }
      } else d
      context.system.eventStream.publish(syncProgress(d1))

      // we have channel announcement that they don't have: check if we can prune them
      val pruningCandidates = {
        val first = ShortChannelId(firstBlockNum.toInt, 0, 0)
        val last = ShortChannelId((firstBlockNum + numberOfBlocks).toInt, 0xFFFFFFFF, 0xFFFF)
        // channel ids are sorted so we can simplify our range check
        val shortChannelIds = d.channels.keySet.dropWhile(_ < first).takeWhile(_ <= last) -- theirShortChannelIds
        log.info("we have {} channel that they do not have", shortChannelIds.size)
        d.channels.filterKeys(id => shortChannelIds.contains(id))
      }

      // first we select channels that we will prune
      val staleChannels = getStaleChannels(pruningCandidates.values)
      val staleChannelIds = staleChannels.map(_.ann.shortChannelId)
      // then we remove nodes that aren't tied to any channels anymore (and deduplicate them)
      val channels1 = d.channels -- staleChannelIds

      // let's clean the db and send the events
      db.removeChannels(staleChannelIds) // NB: this also removes channel updates
      // On Android we don't track pruned channels in our db
      staleChannelIds.foreach { shortChannelId =>
        log.info("pruning shortChannelId={} (stale)", shortChannelId)
        context.system.eventStream.publish(ChannelLost(shortChannelId))
      }
      // we also need to remove updates from the graph
      val staleChannelsToRemove = new mutable.MutableList[ChannelDesc]
      staleChannels.foreach(ca => {
        staleChannelsToRemove += ChannelDesc(ca.ann.shortChannelId, ca.ann.nodeId1, ca.ann.nodeId2)
        staleChannelsToRemove += ChannelDesc(ca.ann.shortChannelId, ca.ann.nodeId2, ca.ann.nodeId1)
      })

      val graph1 = d.graph.removeEdges(staleChannelsToRemove)
      stay using d.copy(channels = channels1, graph = graph1)

    case Event(PeerRoutingMessage(transport, _, routingMessage@QueryShortChannelIds(chainHash, data)), d) =>
      sender ! TransportHandler.ReadAck(routingMessage)
      // On Android we ignore queries
      stay

    case Event(PeerRoutingMessage(transport, remoteNodeId, routingMessage@ReplyShortChannelIdsEnd(chainHash, complete)), d) =>
      sender ! TransportHandler.ReadAck(routingMessage)
      log.info("received reply_short_channel_ids_end={}", routingMessage)
      // have we more channels to ask this peer?
      val d1 = d.sync.get(remoteNodeId) match {
        case Some(sync) if sync.missing.nonEmpty =>
          log.info(s"asking {} for the next slice of short_channel_ids", remoteNodeId)
          val (slice, rest) = sync.missing.splitAt(SHORTID_WINDOW)
          transport ! QueryShortChannelIds(chainHash, ChannelRangeQueries.encodeShortChannelIdsSingle(slice, ChannelRangeQueries.UNCOMPRESSED_FORMAT, useGzip = false))
          d.copy(sync = d.sync + (remoteNodeId -> sync.copy(missing = rest)))
        case Some(sync) if sync.missing.isEmpty =>
          // we received reply_short_channel_ids_end for our last query and have not sent another one, we can now remove
          // the remote peer from our map
          d.copy(sync = d.sync - remoteNodeId)
        case _ =>
          d
      }
      context.system.eventStream.publish(syncProgress(d1))
      stay using d1
  }

  initialize()

  def handle(n: NodeAnnouncement, origin: ActorRef, d: Data): Data =
    if (d.stash.nodes.contains(n)) {
      log.debug("ignoring {} (already stashed)", n)
      val origins = d.stash.nodes(n) + origin
      d.copy(stash = d.stash.copy(nodes = d.stash.nodes + (n -> origins)))
    } else if (d.nodes.contains(n.nodeId) && d.nodes(n.nodeId).timestamp >= n.timestamp) {
      log.debug("ignoring {} (duplicate)", n)
      d
    } else if (!Announcements.checkSig(n)) {
      log.warning("bad signature for {}", n)
      origin ! InvalidSignature(n)
      d
    } else if (d.nodes.contains(n.nodeId)) {
      log.debug("updated node nodeId={}", n.nodeId)
      context.system.eventStream.publish(NodeUpdated(n))
      db.updateNode(n)
      d.copy(nodes = d.nodes + (n.nodeId -> n))
    } else if (d.channels.values.exists(c => isRelatedTo(c.ann, n.nodeId))) {
      log.debug("added node nodeId={}", n.nodeId)
      context.system.eventStream.publish(NodesDiscovered(n :: Nil))
      db.addNode(n)
      d.copy(nodes = d.nodes + (n.nodeId -> n))
    } else if (d.awaiting.keys.exists(c => isRelatedTo(c, n.nodeId))) {
      log.debug("stashing {}", n)
      d.copy(stash = d.stash.copy(nodes = d.stash.nodes + (n -> Set(origin))))
    } else {
      log.debug("ignoring {} (no related channel found)", n)
      // there may be a record if we have just restarted
      db.removeNode(n.nodeId)
      d
    }

  def handle(u: ChannelUpdate, origin: ActorRef, d: Data, remoteNodeId_opt: Option[PublicKey] = None, transport_opt: Option[ActorRef] = None): Data = {
    // On Android, after checking the sig we remove as much data as possible to reduce RAM consumption
    val u1 = u.copy(
      signature = null,
      chainHash = null
    )
    if (d.channels.contains(u.shortChannelId)) {
      // related channel is already known (note: this means no related channel_update is in the stash)
      val publicChannel = true
      val pc = d.channels(u.shortChannelId)
      val desc = getDesc(u, pc.ann)
      if (isStale(u)) {
        log.debug("ignoring {} (stale)", u)
        d
      } else if (pc.getSameSideAs(u).exists(_.timestamp >= u.timestamp)) {
        log.debug("ignoring {} (duplicate)", u)
        d
      } else if (!Announcements.checkSig(u, pc.getNodeIdSameSideAs(u))) {
        log.warning("bad signature for announcement shortChannelId={} {}", u.shortChannelId, u)
        origin ! InvalidSignature(u)
        d
      } else if (pc.getSameSideAs(u).isDefined) {
        log.debug("updated channel_update for shortChannelId={} public={} flags={} {}", u.shortChannelId, publicChannel, u.channelFlags, u)
        db.updateChannel(u)
        // update the graph
        val graph1 = Announcements.isEnabled(u1.channelFlags) match {
          case true => d.graph.removeEdge(desc).addEdge(desc, u1)
          case false => d.graph.removeEdge(desc) // if the channel is now disabled, we remove it from the graph
        }
        d.copy(channels = d.channels + (u.shortChannelId -> pc.updateSameSideAs(u)), graph = graph1)
      } else {
        log.debug("added channel_update for shortChannelId={} public={} flags={} {}", u.shortChannelId, publicChannel, u.channelFlags, u)
        db.updateChannel(u)
        // we also need to update the graph
        val graph1 = d.graph.addEdge(desc, u)
        d.copy(channels = d.channels + (u.shortChannelId -> pc.updateSameSideAs(u)), privateChannels = d.privateChannels - u.shortChannelId, graph = graph1)
      }
    } else if (d.awaiting.keys.exists(c => c.shortChannelId == u.shortChannelId)) {
      // channel is currently being validated
      if (d.stash.updates.contains(u)) {
        log.debug("ignoring {} (already stashed)", u)
        val origins = d.stash.updates(u) + origin
        d.copy(stash = d.stash.copy(updates = d.stash.updates + (u -> origins)))
      } else {
        log.debug("stashing {}", u)
        d.copy(stash = d.stash.copy(updates = d.stash.updates + (u -> Set(origin))))
      }
    } else if (d.privateChannels.contains(u.shortChannelId)) {
      val publicChannel = false
      val pc = d.privateChannels(u.shortChannelId)
      val desc = if (Announcements.isNode1(u.channelFlags)) ChannelDesc(u.shortChannelId, pc.nodeId1, pc.nodeId2) else ChannelDesc(u.shortChannelId, pc.nodeId2, pc.nodeId1)
      if (isStale(u)) {
        log.debug("ignoring {} (stale)", u)
        d
      } else if (pc.getSameSideAs(u).exists(_.timestamp >= u.timestamp)) {
        log.debug("ignoring {} (already know same or newer)", u)
        d
      } else if (!Announcements.checkSig(u, desc.a)) {
        log.warning("bad signature for announcement shortChannelId={} {}", u.shortChannelId, u)
        origin ! InvalidSignature(u)
        d
      } else if (pc.getSameSideAs(u).isDefined) {
        log.debug("updated channel_update for shortChannelId={} public={} flags={} {}", u.shortChannelId, publicChannel, u.channelFlags, u)
        // we also need to update the graph
        val graph1 = d.graph.removeEdge(desc).addEdge(desc, u)
        d.copy(privateChannels = d.privateChannels + (u.shortChannelId -> pc.updateSameSideAs(u)), graph = graph1)
      } else {
        log.debug("added channel_update for shortChannelId={} public={} flags={} {}", u.shortChannelId, publicChannel, u.channelFlags, u)
        // we also need to update the graph
        val graph1 = d.graph.addEdge(desc, u)
        d.copy(privateChannels = d.privateChannels + (u.shortChannelId -> pc.updateSameSideAs(u)), graph = graph1)
      }
    } else {
      // On android we don't track pruned channels in our db
      log.debug("ignoring announcement {} (unknown channel)", u)
      d
    }
  }

  override def mdc(currentMessage: Any): MDC = currentMessage match {
    case SendChannelQuery(remoteNodeId, _) => Logs.mdc(remoteNodeId_opt = Some(remoteNodeId))
    case PeerRoutingMessage(_, remoteNodeId, _) => Logs.mdc(remoteNodeId_opt = Some(remoteNodeId))
    case _ => akka.event.Logging.emptyMDC
  }
}

object Router {

  def props(nodeParams: NodeParams, watcher: ActorRef, initialized: Option[Promise[Done]] = None) = Props(new Router(nodeParams, watcher, initialized))

  def toFakeUpdate(extraHop: ExtraHop): ChannelUpdate =
  // the `direction` bit in flags will not be accurate but it doesn't matter because it is not used
  // what matters is that the `disable` bit is 0 so that this update doesn't get filtered out
    ChannelUpdate(signature = ByteVector.empty, chainHash = ByteVector32.Zeroes, extraHop.shortChannelId, Platform.currentTime / 1000, messageFlags = 0, channelFlags = 0, extraHop.cltvExpiryDelta, htlcMinimumMsat = 0L, extraHop.feeBaseMsat, extraHop.feeProportionalMillionths, None)


  def toAssistedChannels(extraRoute: Seq[ExtraHop], targetNodeId: PublicKey): Map[ShortChannelId, AssistedChannel] = {
    // BOLT 11: "For each entry, the pubkey is the node ID of the start of the channel", and the last node is the destination
    val nextNodeIds = extraRoute.map(_.nodeId).drop(1) :+ targetNodeId
    extraRoute.zip(nextNodeIds).map {
      case (extraHop: ExtraHop, nextNodeId) => extraHop.shortChannelId -> AssistedChannel(extraHop, nextNodeId)
    }.toMap
  }

  def getDesc(u: ChannelUpdate, channel: ChannelAnnouncement): ChannelDesc = {
    // the least significant bit tells us if it is node1 or node2
    if (Announcements.isNode1(u.channelFlags)) ChannelDesc(u.shortChannelId, channel.nodeId1, channel.nodeId2) else ChannelDesc(u.shortChannelId, channel.nodeId2, channel.nodeId1)
  }

  def isRelatedTo(c: ChannelAnnouncement, nodeId: PublicKey) = nodeId == c.nodeId1 || nodeId == c.nodeId2

  def hasChannels(nodeId: PublicKey, channels: Iterable[PublicChannel]): Boolean = channels.exists(c => isRelatedTo(c.ann, nodeId))

  def isStale(u: ChannelUpdate): Boolean = {
    // BOLT 7: "nodes MAY prune channels should the timestamp of the latest channel_update be older than 2 weeks (1209600 seconds)"
    // but we don't want to prune brand new channels for which we didn't yet receive a channel update
    val staleThresholdSeconds = Platform.currentTime / 1000 - 1209600
    u.timestamp < staleThresholdSeconds
  }

  /**
    * Is stale a channel that:
    * (1) is older than 2 weeks (2*7*144 = 2016 blocks)
    * AND
    * (2) has no channel_update younger than 2 weeks
    *
    * @param channel
    * @param update1_opt update corresponding to one side of the channel, if we have it
    * @param update2_opt update corresponding to the other side of the channel, if we have it
    * @return
    */
  def isStale(channel: ChannelAnnouncement, update1_opt: Option[ChannelUpdate], update2_opt: Option[ChannelUpdate]): Boolean = {
    // BOLT 7: "nodes MAY prune channels should the timestamp of the latest channel_update be older than 2 weeks (1209600 seconds)"
    // but we don't want to prune brand new channels for which we didn't yet receive a channel update, so we keep them as long as they are less than 2 weeks (2016 blocks) old
    val staleThresholdBlocks = Globals.blockCount.get() - 2016
    val TxCoordinates(blockHeight, _, _) = ShortChannelId.coordinates(channel.shortChannelId)
    blockHeight < staleThresholdBlocks && update1_opt.map(isStale).getOrElse(true) && update2_opt.map(isStale).getOrElse(true)
  }

  def getStaleChannels(channels: Iterable[PublicChannel]): Iterable[PublicChannel] = channels.filter(data => isStale(data.ann, data.update_1_opt, data.update_2_opt))

  /**
    * Filters channels that we want to send to nodes asking for a channel range
    */
  def keep(firstBlockNum: Long, numberOfBlocks: Long, id: ShortChannelId, channels: Map[ShortChannelId, PublicChannel]): Boolean = {
    val TxCoordinates(height, _, _) = ShortChannelId.coordinates(id)
    height >= firstBlockNum && height <= (firstBlockNum + numberOfBlocks)
  }

  def syncProgress(d: Data): SyncProgress =
    if (d.sync.isEmpty) {
      SyncProgress(1)
    } else {
      SyncProgress(1 - d.sync.values.map(v => v.missing.size + v.outdated.size).sum * 1.0 / d.sync.values.map(v => v.totalMissingCount + v.totalOutdatedCount).sum)
    }

  /**
    * This method is used after a payment failed, and we want to exclude some nodes that we know are failing
    */
  def getIgnoredChannelDesc(channels: Map[ShortChannelId, PublicChannel], ignoreNodes: Set[PublicKey]): Iterable[ChannelDesc] = {
    val desc = if (ignoreNodes.isEmpty) {
      Iterable.empty[ChannelDesc]
    } else {
      // expensive, but node blacklisting shouldn't happen often
      channels.values
        .filter(channelData => ignoreNodes.contains(channelData.ann.nodeId1) || ignoreNodes.contains(channelData.ann.nodeId2))
        .flatMap(channelData => Vector(ChannelDesc(channelData.ann.shortChannelId, channelData.ann.nodeId1, channelData.ann.nodeId2), ChannelDesc(channelData.ann.shortChannelId, channelData.ann.nodeId2, channelData.ann.nodeId1)))
    }
    desc
  }

  /**
    * https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md#clarifications
    */
  val ROUTE_MAX_LENGTH = 20

  // Max allowed CLTV for a route
  val DEFAULT_ROUTE_MAX_CLTV = 1008

  // The default amount of routes we'll search for when findRoute is called
  val DEFAULT_ROUTES_COUNT = 3

  /**
    * Find a route in the graph between localNodeId and targetNodeId, returns the route.
    * Will perform a k-shortest path selection given the @param numRoutes and randomly select one of the result.
    *
    * @param g
    * @param localNodeId
    * @param targetNodeId
    * @param amountMsat   the amount that will be sent along this route
    * @param numRoutes    the number of shortest-paths to find
    * @param extraEdges   a set of extra edges we want to CONSIDER during the search
    * @param ignoredEdges a set of extra edges we want to IGNORE during the search
    * @param routeParams  a set of parameters that can restrict the route search
    * @param wr           an object containing the ratios used to 'weight' edges when searching for the shortest path
    * @return the computed route to the destination @targetNodeId
    */
  def findRoute(g: DirectedGraph,
                localNodeId: PublicKey,
                targetNodeId: PublicKey,
                amountMsat: Long,
                numRoutes: Int,
                extraEdges: Set[GraphEdge] = Set.empty,
                ignoredEdges: Set[ChannelDesc] = Set.empty,
                ignoredVertices: Set[PublicKey] = Set.empty,
                routeParams: RouteParams): Try[Seq[Hop]] = Try {

    if (localNodeId == targetNodeId) throw CannotRouteToSelf

    val currentBlockHeight = Globals.blockCount.get()

    val boundaries: RichWeight => Boolean = { weight =>
      ((weight.cost - amountMsat) < routeParams.maxFeeBaseMsat || (weight.cost - amountMsat) < (routeParams.maxFeePct * amountMsat)) &&
        weight.length <= routeParams.routeMaxLength && weight.length <= ROUTE_MAX_LENGTH &&
        weight.cltv <= routeParams.routeMaxCltv
    }

    val foundRoutes = Graph.yenKshortestPaths(g, localNodeId, targetNodeId, amountMsat, ignoredEdges, ignoredVertices, extraEdges, numRoutes, routeParams.ratios, currentBlockHeight, boundaries).toList match {
      case Nil if routeParams.routeMaxLength < ROUTE_MAX_LENGTH => // if not found within the constraints we relax and repeat the search
        return findRoute(g, localNodeId, targetNodeId, amountMsat, numRoutes, extraEdges, ignoredEdges, ignoredVertices, routeParams.copy(routeMaxLength = ROUTE_MAX_LENGTH, routeMaxCltv = DEFAULT_ROUTE_MAX_CLTV))
      case Nil => throw RouteNotFound
      case routes => routes.find(_.path.size == 1) match {
        case Some(directRoute) => directRoute :: Nil
        case _ => routes
      }
    }

    // At this point 'foundRoutes' cannot be empty
    Random.shuffle(foundRoutes).head.path.map(graphEdgeToHop)
  }
}
