package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import de.pyryco.mobile.data.repository.ConversationReadMarks
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    private fun evidence(connection: Any = first, state: AuthoritativeThreadSnapshot = snapshot, host: String = "host", chat: String = "chat") =
        authoritativeThreadEvidence(host, chat, connection, state)

    @Test fun replacementRequiresFreshCertificationAndAcknowledgementWithoutReusingOldOwner() {
        val captured = evidence().owner
        assertTrue(pingReadMarkConfirmed(captured, reply, evidence(), confirmed, first))
        val replacement = evidence(second, snapshot.copy(generation = 1))
        assertFalse("strict evidence ownership still rejects replacement", captured.matches(replacement.owner))
        assertFalse(pingReadMarkConfirmed(captured, reply, replacement, confirmed.copy(readUpTo = 0u), second))
        assertTrue("fresh replacement facts must complete the final proof", pingReadMarkConfirmed(captured, reply, replacement, confirmed, second))
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
            assertFalse(pingReadMarkConfirmed(evidence().owner, reply, evidence(), marks, first))
        }
    }

    @Test fun certificationMustBeCurrentAndCoverTheExactReplyRevision() {
        for (state in listOf(
            snapshot.copy(checkpoint = 11),
            snapshot.copy(repairNeeded = true),
            snapshot.copy(resetNeeded = true),
            snapshot.copy(epoch = null),
        )) {
            assertFalse(pingReadMarkConfirmed(evidence().owner, reply, evidence(state = state), confirmed, first))
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
            assertFalse(pingReadMarkConfirmed(captured, reply, foreign, confirmed, first))
        }
        for ((key, value) in listOf("rev" to JsonPrimitive(13), "session" to JsonPrimitive("other"), "turn" to JsonPrimitive("other"),
            "content" to buildJsonObject { put("text", "another reply") })) {
            val changed = ThreadItemDto.decode(JsonObject(reply.raw + (key to value)))
            assertFalse(pingReadMarkConfirmed(captured, reply, evidence(state = snapshot.copy(items = listOf(changed))), confirmed, first))
        }
    }

    @Test fun replacementWhileSamplingCannotMixOwnersOrUseRetiredFacts() {
        val captured = evidence().owner
        assertFalse(pingReadMarkConfirmed(captured, reply, evidence(), confirmed, second))
        assertFalse(pingReadMarkConfirmed(captured, reply, evidence(second), confirmed, first))
        assertFalse(pingReadMarkConfirmed(captured, reply, evidence(), confirmed, null))
    }
}
