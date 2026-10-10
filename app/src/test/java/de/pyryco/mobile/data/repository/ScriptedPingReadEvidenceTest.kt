package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.MobileJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Content-free shape of the original paired failure; daemon #3167 owns the missing receipt. */
class ScriptedPingReadEvidenceTest {
    @Test
    fun deliveredReceiptHoleBlocksTheBoundSettledReplyAcrossRepeatedObservation() =
        runTest {
            val projection = ThreadProjection()
            projection.mergeHistoryPage("c", HistoryPage(originalReceipts(), "", true), true)
            repeat(3) {
                val snapshot = projection.observeSnapshot("c").first()
                val reply = snapshot.rows.filterIsInstance<ThreadItem.MessageItem>().last()
                assertTrue(!reply.message.isStreaming)
                assertEquals(setOf(9uL, 10uL), snapshot.readEvidence.versions[reply])
                assertEquals(
                    listOf(2uL, 3uL, 4uL, 5uL, 6uL, 7uL, 9uL, 10uL, 11uL, 12uL),
                    snapshot.readEvidence.facts.keys
                        .sorted(),
                )
                assertTrue(
                    snapshot.readEvidence.facts.values
                        .none { fact -> fact == null },
                )
                assertTrue(snapshot.readEvidence.unidentified.isEmpty())
                assertTrue(snapshot.readEvidence.gaps.isEmpty())
                assertNull(snapshot.readEvidence.checkpoint(reply, 0u))
            }
        }

    @Test
    fun onlyUnderstoodReceiptCanCloseTheHoleWithoutGrantingAnUnseenVersion() =
        runTest {
            for (receipt in listOf(
                entry(8u, "future_type", "{}"),
                entry(8u, "banner", "{}"),
                entry(8u, "banner", INFO_RECEIPT),
            )) {
                val projection = ThreadProjection()
                projection.mergeHistoryPage("c", HistoryPage(originalReceipts(), "", true), true)
                val before = projection.observeSnapshot("c").first()
                val reply = before.rows.filterIsInstance<ThreadItem.MessageItem>().last()
                projection.mergeHistoryPage("c", HistoryPage(listOf(receipt), "", true), true)
                val after = projection.observeSnapshot("c").first()
                assertEquals(before.rows.filterIsInstance<ThreadItem.MessageItem>(), after.rows.filterIsInstance<ThreadItem.MessageItem>())
                assertEquals(
                    if (receipt.payload == MobileJson.parseToJsonElement(INFO_RECEIPT)) 12uL else null,
                    after.readEvidence.checkpoint(reply, 0u),
                )
                val unseen = reply.copy(message = reply.message.copy(content = "different content"))
                assertNull(after.readEvidence.checkpoint(unseen, 0u))
                // Duplicate receipt and overlapping content cannot create a claim for a different version.
                projection.mergeHistoryPage("c", HistoryPage(listOf(receipt) + originalReceipts().take(4), "", true), true)
                assertNull(
                    projection
                        .observeSnapshot("c")
                        .first()
                        .readEvidence
                        .checkpoint(unseen, 0u),
                )
                assertTrue(
                    ThreadProjection()
                        .observeSnapshot("c")
                        .first()
                        .readEvidence.versions
                        .isEmpty(),
                )
            }
        }

    private fun originalReceipts(): List<HistoryEntry> =
        listOf(
            entry(12u, "context_usage", """{"conversation_id":"c","total_tokens":1,"max_tokens":10,"percentage":10}"""),
            entry(11u, "turn_state", """{"conversation_id":"c","state":"idle"}"""),
            entry(10u, "turn_end", """{"conversation_id":"c","turn_id":"t","stop_reason":"end_turn"}"""),
            entry(9u, "assistant_delta", """{"conversation_id":"c","turn_id":"t","seq":0,"text":"seen"}"""),
            entry(7u, "message", """{"conversation_id":"c","message_id":"m","role":"user","text":"asked"}"""),
            entry(6u, "turn_state", """{"conversation_id":"c","state":"responding"}"""),
            entry(5u, "banner", INFO_RECEIPT),
            entry(4u, "mcp_status", """{"conversation_id":"c","servers":[],"dropped_servers":0}"""),
            entry(3u, "slash_command_list", """{"conversation_id":"c","commands":[],"dropped_commands":0}"""),
            entry(2u, "model_list", """{"conversation_id":"c","models":[],"dropped_models":0}"""),
        )

    private fun entry(
        id: ULong,
        type: String,
        payload: String,
    ) = HistoryEntry(
        unsignedId = id,
        type = type,
        payload = MobileJson.parseToJsonElement(payload),
        timestamp = Instant.parse("2026-10-11T00:00:00Z"),
    )

    private companion object {
        const val INFO_RECEIPT = """{"conversation_id":"c","level":"info","text":"","truncated":false,"stops_turn":false}"""
    }
}
