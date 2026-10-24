package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import de.pyryco.mobile.data.repository.AuthoritativeThreadStore
import de.pyryco.mobile.data.repository.ConversationReadMarks
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PingReadProofTest {
    private val first = Any()
    private val second = Any()
    private val reply =
        ThreadItemDto.decode(
            buildJsonObject {
                put("id", 9)
                put("rev", 12)
                put("order", 9)
                put("kind", "assistant_message")
                put("session", "session")
                put("turn", "turn")
                put("parent", 0)
                put("status", "done")
                put("active", false)
                put("shown", true)
                put("summary", "")
                put("content", buildJsonObject { put("text", "ping") })
            },
        )
    private val snapshot = AuthoritativeThreadSnapshot(epoch = "epoch", items = listOf(reply), checkpoint = 12, completedVersion = 12)
    private val confirmed = ConversationReadMarks(12u, null, 9u, true)

    private fun evidence(
        connection: Any = first,
        state: AuthoritativeThreadSnapshot = snapshot,
        host: String = "host",
        chat: String = "chat",
    ) = authoritativeThreadEvidence(host, chat, connection, state)

    private fun proof(
        captured: ThreadEvidenceOwner,
        reply: ThreadItemDto,
        current: ThreadEvidence,
        marks: ConversationReadMarks?,
        connection: Any?,
        certified: () -> Boolean = { true },
        revalidate: () -> AuthoritativeThreadSnapshot? = { current.sourceSnapshot },
    ) = pingReadMarkConfirmed(captured, reply, current, marks, connection, certified, revalidate)

    @Test fun certificationWithdrawalCannotBeHiddenByAnUnchangedSnapshot() {
        val captured = evidence().owner
        assertFalse(proof(captured, reply, evidence(), confirmed, first, certified = { false }))
        var samples = 0
        assertFalse(proof(captured, reply, evidence(), confirmed, first, certified = { ++samples == 1 }))
    }

    @Test fun replacementRequiresFreshCertificationAndAcknowledgementWithoutReusingOldOwner() {
        val captured = evidence().owner
        assertTrue(proof(captured, reply, evidence(), confirmed, first))
        val replacement = evidence(second, snapshot.copy(generation = 1))
        assertFalse("strict evidence ownership still rejects replacement", captured.matches(replacement.owner))
        assertFalse(proof(captured, reply, replacement, confirmed.copy(readUpTo = 0u), second))
        assertTrue(
            "fresh replacement facts must complete the final proof",
            proof(captured, reply, replacement, confirmed, second),
        )
    }

    @Test fun qualifiedSightAndPendingWritesCannotSubstituteForDaemonConfirmation() {
        for (marks in listOf(
            null,
            confirmed.copy(readUpTo = null),
            confirmed.copy(readUpTo = 8u),
            confirmed.copy(threadSupported = false),
            confirmed.copy(lastShownVersion = null),
            confirmed.copy(lastShownVersion = 8u),
            confirmed.copy(lastShownVersion = 13u),
        )) {
            assertFalse(proof(evidence().owner, reply, evidence(), marks, first))
        }
    }

    @Test fun certificationMustBeCurrentAndCoverTheExactReplyRevision() {
        for (state in listOf(
            snapshot.copy(checkpoint = 11),
            snapshot.copy(repairNeeded = true),
            snapshot.copy(resetNeeded = true),
            snapshot.copy(epoch = null),
        )) {
            assertFalse(proof(evidence().owner, reply, evidence(state = state), confirmed, first))
        }
    }

    @Test fun reusedReplyIdsCannotCrossHostConversationEpochOrChangedFacts() {
        val captured = evidence().owner
        for (foreign in listOf(
            evidence(host = "foreign"),
            evidence(chat = "foreign"),
            evidence(state = snapshot.copy(epoch = "foreign")),
            evidence(state = snapshot.copy(items = emptyList())),
        )) {
            assertFalse(proof(captured, reply, foreign, confirmed, first))
        }
        for ((key, value) in listOf(
            "rev" to JsonPrimitive(13),
            "session" to JsonPrimitive("other"),
            "turn" to JsonPrimitive("other"),
            "content" to buildJsonObject { put("text", "another reply") },
        )) {
            val changed = ThreadItemDto.decode(JsonObject(reply.raw + (key to value)))
            assertFalse(proof(captured, reply, evidence(state = snapshot.copy(items = listOf(changed))), confirmed, first))
        }
    }

    @Test fun replacementWhileSamplingCannotMixOwnersOrUseRetiredFacts() {
        val captured = evidence().owner
        assertFalse(proof(captured, reply, evidence(), confirmed, second))
        assertFalse(proof(captured, reply, evidence(second), confirmed, first))
        assertFalse(proof(captured, reply, evidence(), confirmed, null))
    }

    @Test fun reusedEpochResetDuringReadSamplingRequiresFreshExactReplyCertification() = resetDuringReadSampling("epoch")

    @Test fun changedEpochResetDuringReadSamplingCannotProveOriginalReply() = resetDuringReadSampling("changed")

    private fun resetDuringReadSampling(epoch: String) {
        val previousSink = RelayLog.sink
        RelayLog.sink = { _, _, _ -> }
        try {
            val store = AuthoritativeThreadStore()
            store.establishComplete("host", "chat", "epoch", 12, listOf(reply), 0)

            fun current() = evidence(state = store.current("host", "chat"))
            val sampled = current()
            val captured = sampled.owner
            val marks = confirmed.also { store.reset("host", "chat", epoch) }

            fun proofOf(value: ThreadEvidence) = proof(captured, reply, value, marks, first) { store.current("host", "chat") }
            assertFalse("unchanged connection and confirmed marks cannot retain withdrawn certification", proofOf(sampled))
            assertFalse(proofOf(current()))
            val generation = store.current("host", "chat").generation
            assertTrue(generation > requireNotNull(captured.generation))
            store.applyBatch("host", "chat", epoch, 12, listOf(reply), generation)
            assertFalse("exact reply receipt is not certification", proofOf(current()))
            store.establishComplete("host", "chat", epoch, 12, listOf(reply), generation)
            assertFalse("fresh certification cannot revive the earlier sample", proofOf(sampled))
            assertTrue("only the original epoch can freshly prove this reply", proofOf(current()) == (epoch == "epoch"))
            val certified = current()
            store.requestRepair("host", "chat")
            assertFalse("same-generation repair also withdraws certification", proofOf(certified))
        } finally {
            RelayLog.sink = previousSink
        }
    }
}
