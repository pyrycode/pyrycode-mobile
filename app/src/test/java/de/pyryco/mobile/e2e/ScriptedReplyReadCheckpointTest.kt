package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.repository.HistoryEntry
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.ThreadReadEvidence
import de.pyryco.mobile.data.repository.ThreadSnapshot
import de.pyryco.mobile.data.repository.reduceOrderedHistoryPage
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
                entry(12uL, "context_usage", """{"conversation_id":"c","total_tokens":1,"max_tokens":10,"percentage":10}""")
        ).reversed()
    private val reduced = reduceOrderedHistoryPage(entries, true)
    private val reply = reduced.rows.filterIsInstance<ThreadItem.MessageItem>().last()
    private val evidence = ThreadReadEvidence().received(entries, reduced, reduced.rows)
    private val snapshot = ThreadSnapshot(reduced.rows, readEvidence = evidence)

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
