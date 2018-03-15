package fr.acinq.eclair.crypto

import java.net.InetSocketAddress
import java.sql.DriverManager

import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.Crypto.{PrivateKey, ripemd160}
import fr.acinq.bitcoin.SigVersion.SIGVERSION_WITNESS_V0
import fr.acinq.bitcoin.{BinaryData, Block, Crypto, SIGHASH_ALL, Satoshi, Script, Transaction}
import fr.acinq.eclair.NodeParams.BITCOIND
import fr.acinq.eclair._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.db.sqlite._
import fr.acinq.eclair.io.Peer
import fr.acinq.eclair.payment.{ForwardAdd, PaymentLifecycle}
import fr.acinq.eclair.router.Hop
import fr.acinq.eclair.transactions.Scripts.{htlcReceived, toLocalDelayed}
import fr.acinq.eclair.wire.{ChannelUpdate, Color, LightningMessageCodecs, UpdateAddHtlc}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import scodec.bits.BitVector
import scodec.{Attempt, DecodeResult}

import scala.concurrent.duration._
import scala.util.{Failure, Random, Success}

@RunWith(classOf[JUnitRunner])
class SignHtlcSpec extends TestkitBaseClass with StateTestsHelperMethods {

  import TestConstants._

  object Charlie {
    val seed = BinaryData("03" * 32)
    val keyManager = new LocalKeyManager(seed)

    def sqlite = DriverManager.getConnection("jdbc:sqlite::memory:")

    def nodeParams = NodeParams(
      keyManager = keyManager,
      alias = "charlie",
      color = Color(4, 5, 6),
      publicAddresses = new InetSocketAddress("localhost", 9732) :: Nil,
      globalFeatures = "",
      localFeatures = "00", // no announcement
      dustLimitSatoshis = 1000,
      maxHtlcValueInFlightMsat = UInt64.MaxValue, // Bob has no limit on the combined max value of in-flight htlcs
      maxAcceptedHtlcs = 30,
      expiryDeltaBlocks = 144,
      htlcMinimumMsat = 1000,
      minDepthBlocks = 3,
      toRemoteDelayBlocks = 144,
      maxToLocalDelayBlocks = 1000,
      smartfeeNBlocks = 3,
      feeBaseMsat = 546000,
      feeProportionalMillionth = 10,
      reserveToFundingRatio = 0.01, // note: not used (overridden below)
      maxReserveToFundingRatio = 0.05,
      channelsDb = new SqliteChannelsDb(sqlite),
      peersDb = new SqlitePeersDb(sqlite),
      networkDb = new SqliteNetworkDb(sqlite),
      pendingRelayDb = new SqlitePendingRelayDb(sqlite),
      paymentsDb = new SqlitePaymentsDb(sqlite),
      routerBroadcastInterval = 60 seconds,
      routerValidateInterval = 2 seconds,
      pingInterval = 30 seconds,
      maxFeerateMismatch = 1.0,
      updateFeeMinDiffRatio = 0.1,
      autoReconnect = false,
      chainHash = Block.RegtestGenesisBlock.hash,
      channelFlags = 1,
      channelExcludeDuration = 5 seconds,
      watcherType = BITCOIND,
      paymentRequestExpiry = 1 hour,
      maxPendingPaymentRequests = 10000000)

    def id = nodeParams.privateKey.publicKey

    def channelParams = Peer.makeChannelParams(
      nodeParams = nodeParams,
      defaultFinalScriptPubKey = Script.write(Script.pay2wpkh(PrivateKey(Array.fill[Byte](32)(6), compressed = true).publicKey)),
      isFunder = false,
      fundingSatoshis).copy(
      channelReserveSatoshis = 20000 // Alice will need to keep that much satoshis as direct payment
    )
  }

  type FixtureParam = Tuple2[Setup, Setup]

  override def withFixture(test: OneArgTest) = {

    val setupAB = init(TestConstants.Alice.nodeParams, TestConstants.Bob.nodeParams)
    within(30 seconds) {
      import setupAB._
      reachNormal(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, relayer)
      awaitCond(alice.stateName == NORMAL)
      awaitCond(bob.stateName == NORMAL)
    }
    val setupBC = init(TestConstants.Bob.nodeParams, Charlie.nodeParams)
    within(30 seconds) {
      import setupBC._
      reachNormal(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, relayer)
      awaitCond(alice.stateName == NORMAL)
      awaitCond(bob.stateName == NORMAL)
    }
    test((setupAB, setupBC))
  }

  def addHtlc(amountMsat: Int, s: TestFSMRef[State, Data, Channel], r: TestFSMRef[State, Data, Channel], s2r: TestProbe, r2s: TestProbe, hops: Seq[Hop]): (BinaryData, UpdateAddHtlc) = {
    val R: BinaryData = Array.fill[Byte](32)(0)
    Random.nextBytes(R)
    val H: BinaryData = Crypto.sha256(R)
    val sender = TestProbe()
    val expiry = 400144
    val cmd = PaymentLifecycle.buildCommand(amountMsat, expiry, H, hops)._1.copy(commit = false)
    sender.send(s, cmd)
    sender.expectMsg("ok")
    val htlc = s2r.expectMsgType[UpdateAddHtlc]
    s2r.forward(r)
    awaitCond(r.stateData.asInstanceOf[HasCommitments].commitments.remoteChanges.proposed.contains(htlc))
    (R, htlc)
  }

  test("sign outgoing htlc") {
    case (setupAB, setupBC) => {
      val keyManager = TestConstants.Bob.keyManager
      val upd1 = setupAB.relayer.expectMsgType[LocalChannelUpdate]
      val upd2 = setupAB.relayer.expectMsgType[LocalChannelUpdate]

      val channelUpdate = ChannelUpdate(null, null, ShortChannelId(0), 0, null, 144, 0, 0, 0)
      val (r, htlc) = addHtlc(50000000, setupAB.alice, setupAB.bob, setupAB.alice2bob, setupAB.bob2alice, Hop(null, Bob.nodeParams.nodeId, null) :: Hop(Bob.nodeParams.nodeId, Charlie.nodeParams.nodeId, channelUpdate) :: Nil)
      crossSign(setupAB.alice, setupAB.bob, setupAB.alice2bob, setupAB.bob2alice)
      val commitmentsUpstream = setupAB.bob.stateData.asInstanceOf[DATA_NORMAL].commitments

      val forward = setupAB.relayer.expectMsgType[ForwardAdd]
      val proof = commitmentsUpstream.htlcProof(htlc).get

      val localPerCommitmentPoint = keyManager.commitmentPoint(proof.channelKeyPath, proof.commitIndex)

      // this is the redeem script for the HTLC we received upstream
      val redeemScript = htlcReceived(
            Generators.derivePubKey(keyManager.htlcPoint(proof.channelKeyPath).publicKey, localPerCommitmentPoint),
            Generators.derivePubKey(commitmentsUpstream.remoteParams.htlcBasepoint, localPerCommitmentPoint),
            Generators.revocationPubKey(commitmentsUpstream.remoteParams.revocationBasepoint, localPerCommitmentPoint),
            ripemd160(htlc.paymentHash),
            htlc.expiry)

      // check that their sig is valid
      Crypto.verifySignature(
        Transaction.hashForSigning(proof.htlcSuccessTx.tx, inputIndex = 0, Script.write(redeemScript), SIGHASH_ALL, Satoshi(htlc.amountMsat / 1000), SIGVERSION_WITNESS_V0),
        proof.remoteSig,
        Generators.derivePubKey(commitmentsUpstream.remoteParams.htlcBasepoint, localPerCommitmentPoint))

      // check that our HTLC success tx spends an output in our commit tx that matches our HTLC
      val txOut = proof.commitTx.tx.txOut(proof.htlcSuccessTx.tx.txIn(0).outPoint.index.toInt)
      assert(txOut.publicKeyScript == Script.write(Script.pay2wsh(redeemScript)))

      // and check that the output of our HTLC success tx does send money to us
      val outputScript = Script.write(
        Script.pay2wsh(
          toLocalDelayed(
            Generators.revocationPubKey(commitmentsUpstream.remoteParams.revocationBasepoint, localPerCommitmentPoint),
            commitmentsUpstream.localParams.toSelfDelay,
            Generators.derivePubKey(keyManager.delayedPaymentPoint(proof.channelKeyPath).publicKey, localPerCommitmentPoint)
          )
        )
      )
      assert(proof.htlcSuccessTx.tx.txOut(0).publicKeyScript == outputScript)

      val Success((perHopPayload, nextPacket, _)) = Sphinx.parsePacket(TestConstants.Bob.nodeParams.privateKey, forward.add.paymentHash, forward.add.onionRoutingPacket)
        .flatMap {
          case Sphinx.ParsedPacket(payload, nextPacket, sharedSecret) =>
            LightningMessageCodecs.perHopPayloadCodec.decode(BitVector(payload.data)) match {
              case Attempt.Successful(DecodeResult(perHopPayload, _)) => Success((perHopPayload, nextPacket, sharedSecret))
              case Attempt.Failure(cause) => Failure(new RuntimeException(cause.messageWithContext))
            }
        }
      val cmd = CMD_ADD_HTLC(perHopPayload.amtToForward, forward.add.paymentHash, perHopPayload.outgoingCltvValue, nextPacket.serialize, upstream_opt = Some(forward.add), commit = false)
      val sender = TestProbe()
      sender.send(setupBC.alice, cmd)
      sender.expectMsg("ok")
      setupBC.alice2bob.expectMsgType[UpdateAddHtlc]
      setupBC.alice2bob.forward(setupBC.bob)
      crossSign(setupBC.alice, setupBC.bob, setupBC.alice2bob, setupBC.bob2alice)

      val commitmentsDowntstream = setupBC.alice.stateData.asInstanceOf[DATA_NORMAL]
      println(commitmentsDowntstream)
    }
  }
}
