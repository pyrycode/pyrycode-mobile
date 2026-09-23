package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The #896 derivation: how deep each tool row nests under the `Agent`/`Task` calls that spawned it,
 * read from `parentToolUseId` against the tool rows loaded in the thread.
 */
class ToolNestingDepthsTest {
    private val ts: Instant = Instant.parse("2026-09-24T10:00:00Z")

    private fun tool(
        id: String,
        parent: String = "",
    ): ThreadItem.MessageItem =
        ThreadItem.MessageItem(
            Message(
                id = id,
                sessionId = "",
                role = Role.Tool,
                content = "",
                timestamp = ts,
                isStreaming = false,
                toolCall = ToolCall(toolName = "Agent", input = "", output = "", parentToolUseId = parent),
            ),
        )

    private fun user(id: String): ThreadItem.MessageItem =
        ThreadItem.MessageItem(
            Message(id = id, sessionId = "", role = Role.User, content = "hi", timestamp = ts, isStreaming = false),
        )

    @Test
    fun mainThreadRows_areNotNested() {
        assertEquals(emptyMap<String, Int>(), toolNestingDepths(listOf(tool("a"), tool("b"))))
    }

    @Test
    fun matchedChild_andGrandchild_nestByLevel() {
        val items = listOf(tool("agent"), tool("inner", parent = "agent"), tool("grep", parent = "inner"))

        assertEquals(mapOf("inner" to 1, "grep" to 2), toolNestingDepths(items))
    }

    @Test
    fun unmatchedParent_staysTopLevel() {
        val items = listOf(tool("agent"), tool("orphan", parent = "not-loaded"))

        assertEquals(emptyMap<String, Int>(), toolNestingDepths(items))
    }

    @Test
    fun childUnderAnUnmatchedParent_nestsOnlyUnderWhatIsLoaded() {
        val items = listOf(tool("orphan", parent = "not-loaded"), tool("child", parent = "orphan"))

        assertEquals(mapOf("child" to 1), toolNestingDepths(items))
    }

    @Test
    fun parentThatIsNotAToolRow_doesNotNest() {
        val items = listOf(user("u1"), tool("t1", parent = "u1"))

        assertEquals(emptyMap<String, Int>(), toolNestingDepths(items))
    }

    @Test
    fun emptyId_neitherNestsNorParents() {
        val items = listOf(tool(""), tool("t1", parent = ""), tool("", parent = "t1"))

        assertEquals(emptyMap<String, Int>(), toolNestingDepths(items))
    }

    @Test
    fun parentListedAfterItsChild_stillNests() {
        val items = listOf(tool("child", parent = "agent"), tool("agent"))

        assertEquals(mapOf("child" to 1), toolNestingDepths(items))
    }

    @Test
    fun twoRowCycle_terminates_breakingAtTheFirstRowReachedTwice() {
        val items = listOf(tool("a", parent = "b"), tool("b", parent = "a"))

        assertEquals(mapOf("a" to 1), toolNestingDepths(items))
    }

    @Test
    fun selfParent_staysTopLevel() {
        val items = listOf(tool("a", parent = "a"), tool("b", parent = "a"))

        assertEquals(mapOf("b" to 1), toolNestingDepths(items))
    }
}
