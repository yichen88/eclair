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

package fr.acinq.eclair.db.sqlite

import java.sql.Connection

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, Crypto, Satoshi}
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.db.NetworkDb
import fr.acinq.eclair.router.PublicChannel
import fr.acinq.eclair.wire.LightningMessageCodecs.nodeAnnouncementCodec
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate, NodeAnnouncement}
import grizzled.slf4j.Logging
import scodec.bits.ByteVector

import scala.collection.immutable.SortedMap

class SqliteNetworkDb(sqlite: Connection) extends NetworkDb with Logging {

  import SqliteUtils._
  import SqliteUtils.ExtendedResultSet._

  val DB_NAME = "network"
  val CURRENT_VERSION = 2

  using(sqlite.createStatement()) { statement =>
    getVersion(statement, DB_NAME, CURRENT_VERSION) match {
      case 1 =>
        // channel_update are cheap to retrieve, so let's just wipe them out and they'll get resynced
        statement.execute("PRAGMA foreign_keys = ON")
        logger.warn("migrating network db version 1->2")
        statement.executeUpdate("ALTER TABLE channels ADD COLUMN update_1_timestamp INTEGER NULL")
        statement.executeUpdate("ALTER TABLE channels ADD COLUMN update_1_message_flags BLOB NULL")
        statement.executeUpdate("ALTER TABLE channels ADD COLUMN update_1_channel_flags BLOB NULL")
        statement.executeUpdate("ALTER TABLE channels ADD COLUMN update_1_cltv_expiry_delta INTEGER NULL")
        statement.executeUpdate("ALTER TABLE channels ADD COLUMN update_1_htlc_minimum_msat INTEGER NULL")
        statement.executeUpdate("ALTER TABLE channels ADD COLUMN update_1_fee_base_msat INTEGER NULL")
        statement.executeUpdate("ALTER TABLE channels ADD COLUMN update_1_fee_proportional_millionths INTEGER NULL")
        statement.executeUpdate("ALTER TABLE channels ADD COLUMN update_1_htlc_maximum_msat INTEGER NULL")
        statement.executeUpdate("ALTER TABLE channels ADD COLUMN update_2_timestamp INTEGER NULL")
        statement.executeUpdate("ALTER TABLE channels ADD COLUMN update_2_message_flags BLOB NULL")
        statement.executeUpdate("ALTER TABLE channels ADD COLUMN update_2_channel_flags BLOB NULL")
        statement.executeUpdate("ALTER TABLE channels ADD COLUMN update_2_cltv_expiry_delta INTEGER NULL")
        statement.executeUpdate("ALTER TABLE channels ADD COLUMN update_2_htlc_minimum_msat INTEGER NULL")
        statement.executeUpdate("ALTER TABLE channels ADD COLUMN update_2_fee_base_msat INTEGER NULL")
        statement.executeUpdate("ALTER TABLE channels ADD COLUMN update_2_fee_proportional_millionths INTEGER NULL")
        statement.executeUpdate("ALTER TABLE channels ADD COLUMN update_2_htlc_maximum_msat INTEGER NULL")
        statement.executeUpdate("DROP TABLE channel_updates")
        statement.execute("PRAGMA foreign_keys = OFF")
        setVersion(statement, DB_NAME, CURRENT_VERSION)
        logger.warn("migration complete")
      case 2 => () // nothing to do
      case unknown => throw new IllegalArgumentException(s"unknown version $unknown for network db")
    }
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS nodes (node_id BLOB NOT NULL PRIMARY KEY, data BLOB NOT NULL)")
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS channels (short_channel_id INTEGER NOT NULL PRIMARY KEY, node_id_1 BLOB NOT NULL, node_id_2 BLOB NOT NULL, " +
      "update_1_timestamp INTEGER NULL, update_1_message_flags BLOB NULL, update_1_channel_flags BLOB NULL, update_1_cltv_expiry_delta INTEGER NULL, update_1_htlc_minimum_msat INTEGER NULL, update_1_fee_base_msat INTEGER NULL, update_1_fee_proportional_millionths INTEGER NULL, update_1_htlc_maximum_msat INTEGER NULL, " +
      "update_2_timestamp INTEGER NULL, update_2_message_flags BLOB NULL, update_2_channel_flags BLOB NULL, update_2_cltv_expiry_delta INTEGER NULL, update_2_htlc_minimum_msat INTEGER NULL, update_2_fee_base_msat INTEGER NULL, update_2_fee_proportional_millionths INTEGER NULL, update_2_htlc_maximum_msat INTEGER NULL)")
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS pruned (short_channel_id INTEGER NOT NULL PRIMARY KEY)")
  }

  override def addNode(n: NodeAnnouncement): Unit = {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO nodes VALUES (?, ?)")) { statement =>
      statement.setBytes(1, n.nodeId.toBin.toArray)
      statement.setBytes(2, nodeAnnouncementCodec.encode(n).require.toByteArray)
      statement.executeUpdate()
    }
  }

  override def updateNode(n: NodeAnnouncement): Unit = {
    using(sqlite.prepareStatement("UPDATE nodes SET data=? WHERE node_id=?")) { statement =>
      statement.setBytes(1, nodeAnnouncementCodec.encode(n).require.toByteArray)
      statement.setBytes(2, n.nodeId.toBin.toArray)
      statement.executeUpdate()
    }
  }

  override def removeNode(nodeId: Crypto.PublicKey): Unit = {
    using(sqlite.prepareStatement("DELETE FROM nodes WHERE node_id=?")) { statement =>
      statement.setBytes(1, nodeId.toBin.toArray)
      statement.executeUpdate()
    }
  }

  override def listNodes(): Seq[NodeAnnouncement] = {
    using(sqlite.createStatement()) { statement =>
      val rs = statement.executeQuery("SELECT data FROM nodes")
      codecSequence(rs, nodeAnnouncementCodec)
    }
  }

  override def addChannel(c: ChannelAnnouncement, txid: ByteVector32, capacity: Satoshi): Unit = {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO channels VALUES (?, ?, ?, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)")) { statement =>
      statement.setLong(1, c.shortChannelId.toLong)
      statement.setBytes(2, c.nodeId1.value.toBin(false).toArray) // we store uncompressed public keys
      statement.setBytes(3, c.nodeId2.value.toBin(false).toArray)
      statement.executeUpdate()
    }
  }

  override def updateChannel(u: ChannelUpdate): Unit = {
    val columnPrefix = if (u.isNode1) "update_1" else "update_2"
    using(sqlite.prepareStatement(s"UPDATE channels SET ${columnPrefix}_timestamp=?, ${columnPrefix}_message_flags=?, ${columnPrefix}_channel_flags=?, ${columnPrefix}_cltv_expiry_delta=?, ${columnPrefix}_htlc_minimum_msat=?, ${columnPrefix}_fee_base_msat=?, ${columnPrefix}_fee_proportional_millionths=?, ${columnPrefix}_htlc_maximum_msat=? WHERE short_channel_id=?")) { statement =>
      statement.setLong(1, u.timestamp)
      statement.setByte(2, u.messageFlags)
      statement.setByte(3, u.channelFlags)
      statement.setInt(4, u.cltvExpiryDelta)
      statement.setLong(5, u.htlcMinimumMsat)
      statement.setLong(6, u.feeBaseMsat)
      statement.setLong(7, u.feeProportionalMillionths)
      setNullableLong(statement, 8, u.htlcMaximumMsat)
      statement.setLong(9, u.shortChannelId.toLong)
      statement.executeUpdate()
    }
  }

  override def listChannels(): SortedMap[ShortChannelId, PublicChannel] = {
    using(sqlite.createStatement()) { statement =>
      val rs = statement.executeQuery("SELECT * FROM channels")
      var m = SortedMap.empty[ShortChannelId, PublicChannel]
      while (rs.next()) {
        val ann = ChannelAnnouncement(
          nodeSignature1 = null,
          nodeSignature2 = null,
          bitcoinSignature1 = null,
          bitcoinSignature2 = null,
          features = null,
          chainHash = null,
          shortChannelId = ShortChannelId(rs.getLong("short_channel_id")),
          nodeId1 = PublicKey(PublicKey(ByteVector.view(rs.getBytes("node_id_1")), checkValid = false).value, compressed = true), // we read as uncompressed, and convert to compressed, and we don't check the validity it was already checked before
          nodeId2 = PublicKey(PublicKey(ByteVector.view(rs.getBytes("node_id_2")), checkValid = false).value, compressed = true),
          bitcoinKey1 = null,
          bitcoinKey2 = null)
        val txId = ByteVector32.Zeroes
        val capacity = Satoshi(0)

        def getChannelUpdate(columnPrefix: String): Option[ChannelUpdate] = {
          if (rs.getNullableLong(s"${columnPrefix}_timestamp").isDefined) {
            Some(ChannelUpdate(
              signature = null,
              chainHash = null,
              shortChannelId = ShortChannelId(rs.getLong("short_channel_id")),
              timestamp = rs.getLong(s"${columnPrefix}_timestamp"),
              messageFlags = rs.getByte(s"${columnPrefix}_message_flags"),
              channelFlags = rs.getByte(s"${columnPrefix}_channel_flags"),
              cltvExpiryDelta = rs.getInt(s"${columnPrefix}_cltv_expiry_delta"),
              htlcMinimumMsat = rs.getLong(s"${columnPrefix}_htlc_minimum_msat"),
              feeBaseMsat = rs.getLong(s"${columnPrefix}_fee_base_msat"),
              feeProportionalMillionths = rs.getLong(s"${columnPrefix}_fee_proportional_millionths"),
              htlcMaximumMsat = rs.getNullableLong(s"${columnPrefix}_htlc_maximum_msat")))
          } else None
        }

        val channel_update_1_opt = getChannelUpdate("update_1")
        val channel_update_2_opt = getChannelUpdate("update_2")
        m = m + (ann.shortChannelId -> PublicChannel(ann, txId, capacity, channel_update_1_opt, channel_update_2_opt))
      }
      m
    }
  }

  override def removeChannels(shortChannelIds: Iterable[ShortChannelId]): Unit = {
    using(sqlite.createStatement) { statement =>
      shortChannelIds
        .grouped(1000) // remove channels by batch of 1000
        .foreach { group =>
        val ids = shortChannelIds.map(_.toLong).mkString(",")
        statement.executeUpdate(s"DELETE FROM channels WHERE short_channel_id IN ($ids)")
      }
    }
  }

  override def addToPruned(shortChannelIds: Iterable[ShortChannelId]): Unit = {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO pruned VALUES (?)"), disableAutoCommit = true) { statement =>
      shortChannelIds.foreach(shortChannelId => {
        statement.setLong(1, shortChannelId.toLong)
        statement.addBatch()
      })
      statement.executeBatch()
    }
  }

  override def removeFromPruned(shortChannelId: ShortChannelId): Unit = {
    using(sqlite.createStatement) { statement =>
      statement.executeUpdate(s"DELETE FROM pruned WHERE short_channel_id=${shortChannelId.toLong}")
    }
  }

  override def isPruned(shortChannelId: ShortChannelId): Boolean = {
    using(sqlite.prepareStatement("SELECT short_channel_id from pruned WHERE short_channel_id=?")) { statement =>
      statement.setLong(1, shortChannelId.toLong)
      val rs = statement.executeQuery()
      rs.next()
    }
  }

  override def close(): Unit = sqlite.close
}
