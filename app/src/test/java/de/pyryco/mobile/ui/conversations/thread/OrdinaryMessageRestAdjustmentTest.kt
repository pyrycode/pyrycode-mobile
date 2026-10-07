package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.unit.dp
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #1630: the composer's bottom content padding backs out exactly the extra trailing space a last row
 * carries of its own, so the gap from the newest row to the status band reads as the frames' 16dp
 * regardless of which row kind is last. A pure function, so this is a JVM unit test: no Compose rendering
 * needed to pin the cases Figma names (696:4913, 696:4677, and the plain-bubble control).
 *
 * A tool-call row needs no adjustment at all, nested in a background Agent block or not — its own trailing
 * space already equals the target gap. [BackgroundAgentRestGapTest] is the companion screen test proving
 * that in a real composition, after an over-eager 12dp branch here (release 4592) double-subtracted and
 * pulled a running Agent block's single un-folded row under the status band.
 */
class OrdinaryMessageRestAdjustmentTest {
    private val ts: Instant = Instant.parse("2026-10-02T10:00:00Z")

    private fun message(
        toolCall: ToolCall? = null,
        attachments: List<MessageAttachment> = emptyList(),
    ) = Message(
        id = "m-1",
        sessionId = "",
        role = Role.Assistant,
        content = "",
        timestamp = ts,
        isStreaming = false,
        toolCall = toolCall,
        attachments = attachments,
    )

    @Test
    fun `an ordinary plain bubble rests 4dp over the baseline`() {
        val row = ThreadRow.Delivered(ThreadItem.MessageItem(message()))

        assertEquals(4.dp, ordinaryMessageRestAdjustment(row, promptRows = 0))
    }

    @Test
    fun `a bubble with attachments rests 16dp over the baseline, Figma 696-4913`() {
        val row = ThreadRow.Delivered(ThreadItem.MessageItem(message(attachments = listOf(MessageAttachment("a1")))))

        assertEquals(16.dp, ordinaryMessageRestAdjustment(row, promptRows = 0))
    }

    @Test
    fun `a nested tool row inside a background Agent block needs no adjustment`() {
        val toolCall = ToolCall(toolName = "read_file", input = "a.kt", output = "ok")
        val row = ThreadRow.Delivered(ThreadItem.MessageItem(message(toolCall = toolCall)), agentBlockId = "agent-1")

        assertEquals(0.dp, ordinaryMessageRestAdjustment(row, promptRows = 0))
    }

    @Test
    fun `a top-level tool row with no agent block needs no adjustment either`() {
        val toolCall = ToolCall(toolName = "read_file", input = "a.kt", output = "ok")
        val row = ThreadRow.Delivered(ThreadItem.MessageItem(message(toolCall = toolCall)))

        assertEquals(0.dp, ordinaryMessageRestAdjustment(row, promptRows = 0))
    }

    @Test
    fun `a queued row rests 8dp over the baseline, Figma 696-4677`() {
        val row = ThreadRow.Queued(queuedMessageId = 1L, text = "hello", echoId = null)

        assertEquals(8.dp, ordinaryMessageRestAdjustment(row, promptRows = 0))
    }

    @Test
    fun `any open prompt rows mean the composer owns the gap, not the last row`() {
        val row = ThreadRow.Delivered(ThreadItem.MessageItem(message(attachments = listOf(MessageAttachment("a1")))))

        assertEquals(0.dp, ordinaryMessageRestAdjustment(row, promptRows = 1))
    }

    @Test
    fun `no row at all adjusts nothing`() {
        assertEquals(0.dp, ordinaryMessageRestAdjustment(row = null, promptRows = 0))
    }
}
