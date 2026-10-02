package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1456: a timed-out ping reply wait names the layer that lost the reply. */
class PingReplyDiagnosisTest {
    private val held =
        PingReplyEvidence(peerTurnEnds = 2, peerSawReply = true, repositoryHoldsReply = true, replyNodes = 1, replyDisplayed = false)

    private fun message(evidence: PingReplyEvidence): String = evidence.failure(expectedTurnEnds = 2).message.orEmpty()

    @Test
    fun `a reply neither client received is the host's`() {
        val failure = message(held.copy(peerTurnEnds = 1, peerSawReply = false, repositoryHoldsReply = false, replyNodes = 0))

        assertTrue(failure, failure.contains("host:"))
    }

    @Test
    fun `either peer signal clears the host`() {
        assertFalse(message(held.copy(peerTurnEnds = 1)).contains("host:"))
        assertFalse(message(held.copy(peerSawReply = false)).contains("host:"))
    }

    @Test
    fun `a reply the peer got and the phone's repository lacks is the repository's`() {
        val failure = message(held.copy(repositoryHoldsReply = false, replyNodes = 0))

        assertTrue(failure, failure.contains("phone repository: the second client got the reply"))
    }

    @Test
    fun `no readable repository says so`() {
        val failure = message(held.copy(repositoryHoldsReply = null, replyNodes = 0))

        assertTrue(failure, failure.contains("phone repository: unreadable"))
    }

    @Test
    fun `a reply the repository holds and no bubble draws is the thread screen's`() {
        val failure = message(held.copy(replyNodes = 0))

        assertTrue(failure, failure.contains("thread screen: the repository holds the reply, no reply bubble"))
    }

    @Test
    fun `several matching bubbles are counted`() {
        val failure = message(held.copy(replyNodes = 2))

        assertTrue(failure, failure.contains("thread screen: 2 reply bubbles"))
    }

    @Test
    fun `a composed bubble that never displayed is the list's`() {
        val failure = message(held)

        assertTrue(failure, failure.contains("thread list: the reply bubble is composed but not displayed"))
        assertTrue(failure, failure.contains("peer turn_end 2/2"))
    }

    @Test
    fun `the failure keeps its cause`() {
        val cause = IllegalStateException("timeout")

        assertEquals(cause, held.failure(expectedTurnEnds = 2, cause = cause).cause)
    }

    @Test
    fun `a ping streamed after the cancelled turn's end is the follow-up reply`() {
        val frames =
            listOf(
                delta("t1", 0, "pyry"),
                turnEnd(),
                delta("t2", 1, "ng"),
                delta("t2", 0, "pi"),
                turnEnd(),
            )

        assertTrue(followUpPingReplyRecorded(frames, afterTurnEnds = 1))
    }

    @Test
    fun `an assistant message frame counts, a user one does not`() {
        assertTrue(followUpPingReplyRecorded(listOf(turnEnd(), messageFrame("assistant", " Ping\n")), afterTurnEnds = 1))
        assertFalse(followUpPingReplyRecorded(listOf(turnEnd(), messageFrame("user", "ping")), afterTurnEnds = 1))
    }

    @Test
    fun `a ping before the counted turn_end is not the follow-up reply`() {
        val frames = listOf(delta("t1", 0, "ping"), turnEnd())

        assertFalse(followUpPingReplyRecorded(frames, afterTurnEnds = 1))
    }

    @Test
    fun `frames short of the counted turn_end hold no follow-up reply`() {
        assertFalse(followUpPingReplyRecorded(listOf(delta("t1", 0, "ping")), afterTurnEnds = 1))
    }

    @Test
    fun `a reply with more than the word is not ping`() {
        assertFalse(followUpPingReplyRecorded(listOf(turnEnd(), delta("t2", 0, "ping pong")), afterTurnEnds = 1))
    }

    @Test
    fun `the repository holds the reply only as an assistant row reading ping`() {
        assertTrue(holdsPingReply(listOf(row(Role.User, "Reply with ping"), row(Role.Assistant, "ping"))))
        assertFalse(holdsPingReply(listOf(row(Role.User, "ping"))))
        assertFalse(holdsPingReply(listOf(row(Role.Assistant, "pyryheld"))))
    }

    private fun delta(
        turnId: String,
        seq: Int,
        text: String,
    ): Envelope =
        envelope(
            "assistant_delta",
            buildJsonObject {
                put("conversation_id", CONVERSATION)
                put("turn_id", turnId)
                put("seq", seq)
                put("text", text)
            },
        )

    private fun turnEnd(): Envelope =
        envelope(
            "turn_end",
            buildJsonObject {
                put("conversation_id", CONVERSATION)
                put("stop_reason", JsonPrimitive("end_turn"))
            },
        )

    private fun messageFrame(
        role: String,
        text: String,
    ): Envelope =
        envelope(
            "message",
            buildJsonObject {
                put("conversation_id", CONVERSATION)
                put("role", role)
                put("text", text)
            },
        )

    private fun envelope(
        type: String,
        payload: JsonObject,
    ): Envelope = Envelope(id = 1, type = type, ts = "2026-10-02T00:00:00Z", payload = payload)

    private fun row(
        role: Role,
        content: String,
    ): ThreadItem =
        ThreadItem.MessageItem(
            Message(
                id = content,
                sessionId = "s",
                role = role,
                content = content,
                timestamp = Instant.fromEpochMilliseconds(0),
                isStreaming = false,
            ),
        )

    private companion object {
        const val CONVERSATION = "c1"
    }
}
