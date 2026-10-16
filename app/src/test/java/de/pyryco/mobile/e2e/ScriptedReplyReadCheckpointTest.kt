package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.ThreadReadEvidence
import de.pyryco.mobile.data.repository.ThreadSnapshot
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScriptedReplyReadCheckpointTest {
    private val reply =
        ThreadItem.MessageItem(
            Message("reply", "session", Role.Assistant, "ping", Instant.fromEpochSeconds(1), isStreaming = false),
        )
    private val evidence =
        ThreadReadEvidence(
            versions = mapOf(reply to setOf(9uL, 10uL)),
            facts = (1uL..12uL).associateWith { it != 9uL },
        )
    private val snapshot = ThreadSnapshot(listOf(reply), readEvidence = evidence)

    @Test fun completedReplyTargetsItsDisplayableReceiptInsteadOfHiddenCompletionAndMetadata() {
        assertEquals(12uL, evidence.checkpoint(reply, 0uL))
        assertEquals(9uL, scriptedReplyReadCheckpoint(snapshot, reply))
    }

    @Test fun hiddenReceiptCannotStandInForDisplayedReply() {
        val hiddenOnly = evidence.copy(versions = mapOf(reply to setOf(10uL, 11uL)))
        assertNull(scriptedReplyReadCheckpoint(snapshot.copy(readEvidence = hiddenOnly), reply))
    }

    @Test fun unknownAndMissingReceiptsBeforeReplyStillBlockConfirmationTarget() {
        for (facts in listOf(evidence.facts - 8uL, evidence.facts + (8uL to null))) {
            assertNull(scriptedReplyReadCheckpoint(snapshot.copy(readEvidence = evidence.copy(facts = facts)), reply))
        }
    }

    @Test fun wrongUnfinishedAndBlankReplyCannotReuseReceiptEvidence() {
        for (message in listOf(
            reply.message.copy(content = "unseen"),
            reply.message.copy(isStreaming = true),
            reply.message.copy(content = ""),
            reply.message.copy(role = Role.User),
        )) {
            assertNull(scriptedReplyReadCheckpoint(snapshot, reply.copy(message = message)))
        }
    }

    @Test fun newerUnseenContentCannotRaiseTheDisplayedReplyTarget() {
        val later = evidence.copy(facts = evidence.facts + (13uL to false))
        assertEquals(9uL, scriptedReplyReadCheckpoint(snapshot.copy(readEvidence = later), reply))
    }
}
