package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import de.pyryco.mobile.data.repository.ConversationReadMarks
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
}
