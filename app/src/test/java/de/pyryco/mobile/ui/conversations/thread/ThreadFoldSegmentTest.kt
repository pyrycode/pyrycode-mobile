package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.model.AssistantSegment
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.SegmentDelta
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The synthetic streaming row beside the projection's assistant segments (#1350): [render] never draws a
 * synthetic copy of a turn the finished projection already holds any segment of.
 */
class ThreadFoldSegmentTest {
    @Test
    fun render_projectionHoldsOnlyALaterSegmentOfTheTurn_drawsNoSynthetic() {
        val finished = listOf(tool("u1"), segment("$TURN#2", seq = 2, text = "Found it."))
        val fold =
            ThreadFold(emptyList(), null)
                .reduce(ThreadInput.Live(delta(2, "Found it.")), CONVERSATION)
                .reduce(ThreadInput.Finished(finished), CONVERSATION)
                .reduce(ThreadInput.Live(delta(3, " More.")), CONVERSATION)

        assertEquals(finished, fold.render())
    }

    @Test
    fun render_repeatedFirstDeltaAfterAToolRow_drawsNoSynthetic() {
        val first = listOf(segment(TURN, seq = 0, text = "Let me look."))
        val finished = first + tool("u1")
        val fold =
            ThreadFold(emptyList(), null)
                .reduce(ThreadInput.Live(delta(0, "Let me look.")), CONVERSATION)
                .reduce(ThreadInput.Finished(first), CONVERSATION)
                .reduce(ThreadInput.Finished(finished), CONVERSATION)
                .reduce(ThreadInput.Live(delta(0, "Let me look.")), CONVERSATION)

        val rows = fold.render()
        assertEquals(finished, rows)
        val ids = rows.filterIsInstance<ThreadItem.MessageItem>().map { it.message.id }
        assertEquals(ids.distinct(), ids)
    }

    @Test
    fun render_projectionHoldsNothingOfTheTurnYet_stillDrawsTheSynthetic() {
        val fold = ThreadFold(listOf(tool("u0")), null).reduce(ThreadInput.Live(delta(0, "Hi")), CONVERSATION)

        val synthetic = (fold.render().last() as ThreadItem.MessageItem).message
        assertEquals(TURN to "Hi", synthetic.id to synthetic.content)
    }

    private fun delta(
        seq: Int,
        text: String,
    ) = LiveSessionEvent.AssistantDelta(CONVERSATION, TURN, seq, text)

    private fun segment(
        id: String,
        seq: Int,
        text: String,
    ) = ThreadItem.MessageItem(
        Message(
            id = id,
            sessionId = "",
            role = Role.Assistant,
            content = text,
            timestamp = TS,
            isStreaming = true,
            segment = AssistantSegment(TURN, listOf(SegmentDelta(seq, text.length))),
        ),
    )

    private fun tool(id: String) =
        ThreadItem.MessageItem(
            Message(id, "", Role.Tool, "Bash", TS, isStreaming = false, toolCall = ToolCall("Bash", "ls", "ok")),
        )

    private companion object {
        const val CONVERSATION = "c1"
        const val TURN = "t1"
        val TS: Instant = Instant.parse("2026-10-01T10:00:00Z")
    }
}
