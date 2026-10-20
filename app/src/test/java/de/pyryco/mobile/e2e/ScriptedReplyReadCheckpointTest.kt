package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.repository.HistoryEntry
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.ThreadReadEvidence
import de.pyryco.mobile.data.repository.ThreadSnapshot
import de.pyryco.mobile.data.repository.reduceOrderedHistoryPage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScriptedReplyReadCheckpointTest {
    private fun entry(
        id: ULong,
        type: String,
        payload: String,
    ) = HistoryEntry(type, MobileJson.parseToJsonElement(payload), Instant.fromEpochSeconds(1), id)

    private val entries =
        (
            (1uL..8uL).map { entry(it, "turn_state", """{"conversation_id":"c","state":"idle"}""") } +
                entry(9uL, "assistant_delta", """{"conversation_id":"c","turn_id":"t","seq":0,"text":"ping"}""") +
                entry(10uL, "turn_end", """{"conversation_id":"c","turn_id":"t","stop_reason":"end_turn"}""") +
                entry(11uL, "turn_state", """{"conversation_id":"c","state":"idle"}""") +
                entry(
                    12uL,
                    "context_usage",
                    """{"conversation_id":"c","model":"","total_tokens":1,"max_tokens":10,"percentage":10,"categories":[],"dropped_categories":0,"memory_files":[],"dropped_memory_files":0}""",
                )
        ).reversed()
    private val reduced = reduceOrderedHistoryPage(entries, true)
    private val reply = reduced.rows.filterIsInstance<ThreadItem.MessageItem>().last()
    private val evidence = ThreadReadEvidence().received(entries, reduced, reduced.rows)
    private val snapshot = ThreadSnapshot(reduced.rows, readEvidence = evidence)

    @Test fun classificationCannotRepairMissingLiveReceipts() =
        runTest {
            val observed = MutableStateFlow(snapshot.copy(readEvidence = evidence.copy(facts = evidence.facts - 8uL)))
            var queries = 0
            val target =
                async {
                    awaitScriptedReplyReadCheckpoint(observed, "ping") {
                        queries++
                        // requestHistory merges decoded history and can fill the missing live receipt.
                        val fetched = reduceOrderedHistoryPage(entries, true)
                        observed.value =
                            ThreadSnapshot(
                                fetched.rows,
                                readEvidence = ThreadReadEvidence().received(entries, fetched, fetched.rows),
                            )
                        entries
                    }
                }
            runCurrent()
            assertEquals("classification must wait for complete live receipts", 0, queries)
            assertFalse("history cannot manufacture live delivery proof", target.isCompleted)
            observed.value = snapshot
            assertEquals(9uL, target.await())
            assertEquals(1, queries)
        }

    @Test fun classificationCannotFinishTheLiveReply() =
        runTest {
            val streamingReply = reply.copy(message = reply.message.copy(isStreaming = true))
            val observed =
                MutableStateFlow(
                    snapshot.copy(
                        rows = listOf(streamingReply),
                        readEvidence = evidence.copy(versions = mapOf(streamingReply to setOf(9uL))),
                    ),
                )
            var queries = 0
            val target =
                async {
                    awaitScriptedReplyReadCheckpoint(observed, "ping") {
                        queries++
                        // The same mutating query can settle the row from a history turn_end.
                        val fetched = reduceOrderedHistoryPage(entries, true)
                        observed.value =
                            ThreadSnapshot(
                                fetched.rows,
                                readEvidence = ThreadReadEvidence().received(entries, fetched, fetched.rows),
                            )
                        entries
                    }
                }
            runCurrent()
            assertEquals("classification must wait for live completion", 0, queries)
            assertFalse("history cannot manufacture live completion proof", target.isCompleted)
            observed.value = snapshot
            assertEquals(9uL, target.await())
            assertEquals(1, queries)
        }

    @Test fun classificationUsesTheRetainedReplyProof() =
        runTest {
            val observed = MutableStateFlow(snapshot)
            var queries = 0
            val target =
                awaitScriptedReplyReadCheckpoint(observed, "ping") {
                    queries++
                    // A newer row version arriving during I/O must not replace the qualified snapshot.
                    observed.value = snapshot.copy(readEvidence = ThreadReadEvidence())
                    entries
                }
            assertEquals(9uL, target)
            assertEquals(1, queries)
        }

    @Test fun completedReplyTargetsItsDisplayableReceiptInsteadOfHiddenCompletionAndMetadata() {
        assertEquals(12uL, evidence.checkpoint(reply, 0uL))
        assertEquals(false, evidence.facts[10uL])
        assertEquals(9uL, scriptedReplyReadCheckpoint(snapshot, reply, entries))
    }

    @Test fun hiddenReceiptCannotStandInForDisplayedReply() {
        val hiddenOnly = evidence.copy(versions = mapOf(reply to setOf(10uL, 11uL)))
        assertNull(scriptedReplyReadCheckpoint(snapshot.copy(readEvidence = hiddenOnly), reply, entries))
    }

    @Test fun unknownAndMissingReceiptsBeforeReplyStillBlockConfirmationTarget() {
        for (facts in listOf(evidence.facts - 8uL, evidence.facts + (8uL to null))) {
            assertNull(scriptedReplyReadCheckpoint(snapshot.copy(readEvidence = evidence.copy(facts = facts)), reply, entries))
        }
    }

    @Test fun wrongUnfinishedAndBlankReplyCannotReuseReceiptEvidence() {
        for (message in listOf(
            reply.message.copy(content = "unseen"),
            reply.message.copy(isStreaming = true),
            reply.message.copy(content = ""),
            reply.message.copy(role = Role.User),
        )) {
            assertNull(scriptedReplyReadCheckpoint(snapshot, reply.copy(message = message), entries))
        }
    }

    @Test fun missingDisplayableHistoryCannotBorrowCompletionCoverage() {
        assertNull(scriptedReplyReadCheckpoint(snapshot, reply, entries.filter { it.unsignedId != 9uL }))
    }

    @Test fun newerUnseenContentCannotRaiseTheDisplayedReplyTarget() {
        val later = evidence.copy(facts = evidence.facts + (13uL to false))
        val unseen = entry(13uL, "assistant_delta", """{"conversation_id":"c","turn_id":"other","seq":0,"text":"unseen"}""")
        assertEquals(9uL, scriptedReplyReadCheckpoint(snapshot.copy(readEvidence = later), reply, listOf(unseen) + entries))
    }
}
