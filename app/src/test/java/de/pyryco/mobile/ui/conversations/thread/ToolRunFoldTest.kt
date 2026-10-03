package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.repository.BannerLevel
import de.pyryco.mobile.data.repository.BoundaryReason
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #1635: the render-time fold that draws each run of two or more adjacent tool rows as one
 * [ThreadRow.ToolRun]. A pure function over the queued fold's output, so a JVM unit test.
 */
class ToolRunFoldTest {
    private val ts: Instant = Instant.parse("2026-10-03T10:00:00Z")

    private fun message(
        id: String,
        role: Role,
        toolCall: ToolCall? = null,
    ): ThreadRow.Delivered =
        ThreadRow.Delivered(
            ThreadItem.MessageItem(
                Message(id = id, sessionId = "s1", role = role, content = "", timestamp = ts, isStreaming = false, toolCall = toolCall),
            ),
        )

    private fun tool(
        id: String,
        parent: String = "",
    ) = message(id, Role.Tool, ToolCall(toolName = "Read", input = "", output = "", parentToolUseId = parent))

    private fun ThreadRow.Delivered.msg(): Message = (item as ThreadItem.MessageItem).message

    private fun run(
        vararg tools: ThreadRow.Delivered,
        expanded: Boolean = false,
    ) = ThreadRow.ToolRun(runId = tools.first().msg().id, tools = tools.map { it.msg() }, expanded = expanded)

    @Test
    fun `a run of adjacent tool rows folds into one row counting its tools`() {
        val rows = listOf(message("u", Role.User), tool("t1"), tool("t2"), tool("t3"), message("a", Role.Assistant))

        val folded = foldToolRuns(rows, expandedRuns = emptySet())

        assertEquals(
            listOf(rows[0], run(rows[1] as ThreadRow.Delivered, rows[2] as ThreadRow.Delivered, rows[3] as ThreadRow.Delivered), rows[4]),
            folded,
        )
    }

    @Test
    fun `any non-tool row between tool rows starts a new run`() {
        val t1 = tool("t1")
        val t2 = tool("t2")
        val t3 = tool("t3")
        val t4 = tool("t4")
        val t5 = tool("t5")
        val t6 = tool("t6")
        val t7 = tool("t7")
        val t8 = tool("t8")
        val assistant = message("a", Role.Assistant)
        val boundary =
            ThreadRow.Delivered(ThreadItem.SessionBoundary("s0", "s1", BoundaryReason.Clear, ts))
        val banner = ThreadRow.Delivered(ThreadItem.Banner(BannerLevel.Info, "note", false, ts))
        val rows = listOf(t1, t2, assistant, t3, t4, boundary, t5, t6, banner, t7, t8)

        val folded = foldToolRuns(rows, expandedRuns = emptySet())

        assertEquals(listOf(run(t1, t2), assistant, run(t3, t4), boundary, run(t5, t6), banner, run(t7, t8)), folded)
    }

    @Test
    fun `a lone tool row draws as itself`() {
        val rows = listOf(message("u", Role.User), tool("t1"), message("a", Role.Assistant), tool("t2"))

        assertEquals(rows, foldToolRuns(rows, expandedRuns = emptySet()))
    }

    @Test
    fun `a tool message with no tool call is not a tool row and ends a run`() {
        val bare = message("bare", Role.Tool)
        val rows = listOf(tool("t1"), bare, tool("t2"))

        assertEquals(rows, foldToolRuns(rows, expandedRuns = emptySet()))
    }

    @Test
    fun `nested subagent rows join the run they sit in`() {
        val agent = tool("agent")
        val child = tool("child", parent = "agent")
        val grandchild = tool("grandchild", parent = "child")
        val rows = listOf(agent, child, grandchild)

        assertEquals(listOf(run(agent, child, grandchild)), foldToolRuns(rows, expandedRuns = emptySet()))
    }

    @Test
    fun `an expanded run draws its header then its own rows in order`() {
        val t1 = tool("t1")
        val t2 = tool("t2")
        val t3 = tool("t3")
        val t4 = tool("t4")
        val assistant = message("a", Role.Assistant)
        val rows = listOf(t1, t2, assistant, t3, t4)

        val folded = foldToolRuns(rows, expandedRuns = setOf("t1"))

        assertEquals(listOf(run(t1, t2, expanded = true), t1, t2, assistant, run(t3, t4)), folded)
    }

    @Test
    fun `a run keeps its identity while new tool rows join it`() {
        val before = foldToolRuns(listOf(tool("t1"), tool("t2")), expandedRuns = setOf("t1"))
        val after = foldToolRuns(listOf(tool("t1"), tool("t2"), tool("t3")), expandedRuns = setOf("t1"))

        assertEquals("t1", (before.first() as ThreadRow.ToolRun).runId)
        assertEquals("t1", (after.first() as ThreadRow.ToolRun).runId)
        assertEquals(listOf(true, true), listOf(before, after).map { (it.first() as ThreadRow.ToolRun).expanded })
        assertEquals(4, after.size)
    }

    @Test
    fun `folded rows carry unique list keys collapsed and expanded`() {
        val rows =
            listOf(
                message("u", Role.User),
                tool("t1"),
                tool("t2", parent = "t1"),
                message("a", Role.Assistant),
                tool("t3"),
                tool("t4"),
                ThreadRow.Queued(queuedMessageId = 1L, text = "later", echoId = null),
            )

        for (expanded in listOf(emptySet(), setOf("t1"), setOf("t1", "t3"))) {
            val folded = foldToolRuns(rows, expanded)
            val keys = folded.mapIndexed { index, row -> row.listKey(index) }
            assertEquals(keys.toSet().size, keys.size)
        }
        assertEquals("tool-run:t1", run(tool("t1"), tool("t2")).listKey(0))
    }
}
