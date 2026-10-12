package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.AssistantSegment
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.SegmentDelta
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.datetime.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettledAssistantReplyTest {
    private fun row(
        text: String,
        turn: String = "turn",
        seq: Int = 0,
        parent: String = "",
        streaming: Boolean = false,
        role: Role = Role.Assistant,
    ) = ThreadItem.MessageItem(
        Message(
            id = "$turn#$seq",
            sessionId = "session",
            role = role,
            content = text,
            timestamp = Instant.fromEpochMilliseconds(0),
            isStreaming = streaming,
            parentToolUseId = parent,
            segment = if (role == Role.Assistant) AssistantSegment(turn, listOf(SegmentDelta(seq, text.length))) else null,
        ),
    )

    @Test
    fun acceptsVariedWordingSplitByAChildTool() {
        val rows = listOf(row("Yes, "), row("", turn = "launch", parent = "agent", role = Role.Tool), row("that is done!", seq = 1))
        assertTrue(holdsSettledAssistantReply(rows, "turn"))
    }

    @Test
    fun childReplyMustBelongToTheRequestedTurnAndAgent() {
        val rows = listOf(row("Completed the requested work.", parent = "agent"))
        assertTrue(holdsSettledAssistantReply(rows, "turn", "agent"))
        assertFalse(holdsSettledAssistantReply(rows, "turn"))
        assertFalse(holdsSettledAssistantReply(rows, "turn", "other"))
        assertFalse(holdsSettledAssistantReply(rows, "other", "agent"))
    }

    @Test
    fun unfinishedMissingOrNonAssistantTextCannotSupplyAReply() {
        assertFalse(holdsSettledAssistantReply(listOf(row("Done", turn = "old")), "new"))
        assertFalse(holdsSettledAssistantReply(listOf(row("Yes"), row("done", seq = 1, streaming = true)), "turn"))
        assertFalse(holdsSettledAssistantReply(listOf(row("Done", role = Role.User)), "turn"))
        assertFalse(holdsSettledAssistantReply(listOf(row("Done", role = Role.Tool)), "turn"))
        assertFalse(holdsSettledAssistantReply(listOf(row("  ")), "turn"))
        assertFalse(holdsSettledAssistantReply(emptyList(), "turn"))
        assertFalse(holdsSettledAssistantReply(listOf(row("First"), row("Last", seq = 2)), "turn"))
    }
}
