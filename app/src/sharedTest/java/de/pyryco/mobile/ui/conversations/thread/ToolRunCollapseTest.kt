package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.components.TOOL_ROW_TAG
import de.pyryco.mobile.ui.conversations.components.TOOL_RUN_TAG
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #1635: with "Collapse assistant tool uses" on, each run of adjacent tool rows draws as one
 * "Using tools: N" header (Figma `726:5376`) that opens to the run's own rows; off, the thread draws as
 * before. Mounted on the real [ThreadScreen], because the fold, the expanded set and the row are one path.
 */
@RunWith(AndroidJUnit4::class)
class ToolRunCollapseTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val ts: Instant = Instant.parse("2026-10-03T10:00:00Z")

    private fun string(
        id: Int,
        vararg args: Any,
    ): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private fun tool(
        id: String,
        name: String,
        status: ToolCallStatus = ToolCallStatus.Done,
        parent: String = "",
    ): ThreadItem.MessageItem =
        ThreadItem.MessageItem(
            Message(
                id = id,
                sessionId = "s1",
                role = Role.Tool,
                content = "",
                timestamp = ts,
                isStreaming = false,
                toolCall = ToolCall(toolName = name, input = "", output = "", status = status, parentToolUseId = parent),
            ),
        )

    private fun assistant(
        id: String,
        text: String,
    ): ThreadItem.MessageItem =
        ThreadItem.MessageItem(
            Message(id = id, sessionId = "s1", role = Role.Assistant, content = text, timestamp = ts, isStreaming = false),
        )

    private var items by mutableStateOf<List<ThreadItem>>(emptyList())
    private var collapse by mutableStateOf(true)

    private fun setThread(initial: List<ThreadItem>) {
        items = initial
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state =
                        ThreadUiState(
                            conversationId = "c1",
                            displayName = "Test channel",
                            isPromoted = true,
                            hasMessages = true,
                            items = items,
                        ),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    collapseToolUses = collapse,
                )
            }
        }
    }

    private fun label(count: Int) = string(R.string.tool_run_label, count)

    private fun header(count: Int) = composeTestRule.onNode(hasTestTag(TOOL_RUN_TAG) and hasAnyDescendant(hasText(label(count))))

    private fun headerWithStatus(description: Int) =
        composeTestRule.onNode(
            hasTestTag(TOOL_RUN_TAG) and hasAnyDescendant(hasContentDescription(string(description))),
            useUnmergedTree = true,
        )

    private fun assertToolNamesShown(
        vararg names: String,
        shown: Boolean,
    ) {
        for (name in names) composeTestRule.onAllNodesWithText(name, useUnmergedTree = true).assertCountEquals(if (shown) 1 else 0)
    }

    @Test
    fun runsSplitByAssistantText_drawAsOneHeaderEach_andALoneToolDrawsAsToday() {
        setThread(
            listOf(
                tool("t1", "Grep"),
                tool("t2", "Read"),
                tool("t3", "Edit"),
                assistant("a1", "Halfway."),
                tool("t4", "Bash"),
                tool("t5", "Glob"),
                assistant("a2", "Done."),
                tool("t6", "Write"),
            ),
        )

        header(3).assertIsDisplayed()
        header(2).assertIsDisplayed()
        composeTestRule.onAllNodesWithTag(TOOL_RUN_TAG).assertCountEquals(2)
        assertToolNamesShown("Grep", "Read", "Edit", "Bash", "Glob", shown = false)
        assertToolNamesShown("Write", shown = true)
    }

    @Test
    fun tappingAHeader_expandsToItsFlushRows_keepingSubagentIndent_andTappingAgainCollapses() {
        setThread(
            listOf(
                tool("t1", "Agent"),
                tool("t2", "Task", parent = "t1"),
                tool("t3", "Grep", parent = "t2"),
                assistant("a1", "Found it."),
            ),
        )

        header(3).performClick()
        composeTestRule.waitForIdle()

        assertToolNamesShown("Agent", "Task", "Grep", shown = true)
        val left = { name: String -> composeTestRule.onNodeWithText(name, useUnmergedTree = true).getUnclippedBoundsInRoot().left }
        assertTrue("a subagent row stays indented under its parent", left("Task") > left("Agent"))
        assertTrue("a grandchild indents one more step", left("Grep") > left("Task"))
        val headerBounds = composeTestRule.onNodeWithTag(TOOL_RUN_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val firstRow =
            composeTestRule
                .onNode(hasTestTag(TOOL_ROW_TAG) and hasAnyDescendant(hasText("Agent")), useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
        assertEquals("the first row meets the header flush", (headerBounds.bottom - 1.dp).value, firstRow.top.value, 0.5f)

        header(3).performClick()
        composeTestRule.waitForIdle()

        assertToolNamesShown("Agent", "Task", "Grep", shown = false)
        header(3).assertIsDisplayed()
    }

    @Test
    fun anExpandedRun_staysExpandedWhileNewToolRowsJoinIt() {
        setThread(listOf(tool("t1", "Grep"), tool("t2", "Read")))
        header(2).performClick()
        composeTestRule.waitForIdle()

        items = items + tool("t3", "Edit")
        composeTestRule.waitForIdle()

        header(3).assertIsDisplayed()
        assertToolNamesShown("Grep", "Read", "Edit", shown = true)
    }

    @Test
    fun trailingStatus_showsCheck_spinner_andFailedCount_andFollowsLiveChanges() {
        setThread(listOf(tool("t1", "Grep"), tool("t2", "Read")))
        header(2).assertIsDisplayed()
        headerWithStatus(R.string.cd_tool_done).assertIsDisplayed()

        items = items + tool("t3", "Bash", status = ToolCallStatus.Running)
        composeTestRule.waitForIdle()
        header(3).assertIsDisplayed()
        headerWithStatus(R.string.cd_tool_running).assertIsDisplayed()

        items = items.dropLast(1) + tool("t3", "Bash", status = ToolCallStatus.Failed) + tool("t4", "Edit", status = ToolCallStatus.Denied)
        composeTestRule.waitForIdle()
        header(4).assertIsDisplayed()
        headerWithStatus(R.string.cd_tool_failed).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.tool_run_failed, 2), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun settingOff_drawsEveryToolRow_andTurningItOnFoldsTheOpenThread() {
        collapse = false
        setThread(listOf(tool("t1", "Grep"), tool("t2", "Read"), assistant("a1", "Found it.")))

        composeTestRule.onAllNodesWithTag(TOOL_RUN_TAG).assertCountEquals(0)
        composeTestRule.onAllNodesWithTag(TOOL_ROW_TAG, useUnmergedTree = true).assertCountEquals(2)

        collapse = true
        composeTestRule.waitForIdle()

        header(2).assertIsDisplayed()
        assertToolNamesShown("Grep", "Read", shown = false)

        collapse = false
        composeTestRule.waitForIdle()

        assertToolNamesShown("Grep", "Read", shown = true)
        composeTestRule.onAllNodesWithTag(TOOL_RUN_TAG).assertCountEquals(0)
    }
}
