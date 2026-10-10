package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.BannerLevel
import de.pyryco.mobile.data.repository.BoundaryReason
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.UnrecognizedSite
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ThreadRowContentTypeMappingTest {
    private val ts = Instant.parse("2026-10-08T10:00:00Z")
    private val message = Message("first", "s1", Role.User, "Hello", ts, isStreaming = false)

    private fun Message.type() = ThreadRow.Delivered(ThreadItem.MessageItem(this)).contentType()

    @Test
    fun `bubble and tool types depend on rendering kind rather than message identity or text`() {
        val assistant = message.copy(id = "second", role = Role.Assistant, content = "Different", isStreaming = true)
        assertEquals(message.type(), assistant.type())
        val tool = message.copy(role = Role.Tool)
        assertNotEquals(message.type(), tool.type())
        assertEquals(tool.type(), tool.copy(id = "another-tool", content = "Changed").type())
    }

    @Test
    fun `all folded and delivered kinds have distinct nonempty types`() {
        val delivered =
            listOf(
                ThreadItem.MessageItem(message),
                ThreadItem.MessageItem(message.copy(role = Role.Tool)),
                ThreadItem.SessionBoundary("s0", "s1", BoundaryReason.Clear, ts),
                ThreadItem.UnrecognizedMessage("unknown", UnrecognizedSite.LineType, "new", "{}", false, ts),
                ThreadItem.Banner(BannerLevel.Notice, "Notice", false, ts),
                ThreadItem.CompactionBoundary(null, null, false, ts),
                ThreadItem.ModelRefusal("original", null, "Refusal", false, ts),
                ThreadItem.BackgroundTaskLifecycle("task", ts),
                ThreadItem.StoppedTurn("turn", "Stopped", "error", ts),
            )
        val rows =
            delivered.map { ThreadRow.Delivered(it) } +
                listOf(
                    ThreadRow.Queued(7L, "Waiting", null),
                    ThreadRow.ToolRun("run", emptyList(), false),
                    ThreadRow.AgentStartMarker("agent", "Work", false),
                )
        val types = rows.map { it.contentType() }
        assertEquals(rows.size, types.toSet().size)
        assertEquals(0, types.count { it.isEmpty() })
    }

    @Test
    fun `row types stay stable across queue correlation run expansion and agent completion`() {
        val queued = ThreadRow.Queued(7L, "Waiting", null)
        assertEquals(queued.contentType(), queued.copy(queuedMessageId = 8L, text = "Other", echoId = "echo").contentType())
        val run = ThreadRow.ToolRun("run", emptyList(), false)
        assertEquals(run.contentType(), run.copy(runId = "other", tools = listOf(message), expanded = true).contentType())
        val agent = ThreadRow.AgentStartMarker("agent", "Work", false)
        assertEquals(agent.contentType(), agent.copy(agentId = "other", description = "Done", finished = true).contentType())
        val banner = ThreadItem.Banner(BannerLevel.Notice, "Notice", false, ts)
        assertEquals(
            ThreadRow.Delivered(banner).contentType(),
            ThreadRow.Delivered(banner.copy(level = BannerLevel.Info, text = "Other")).contentType(),
        )
    }
}
