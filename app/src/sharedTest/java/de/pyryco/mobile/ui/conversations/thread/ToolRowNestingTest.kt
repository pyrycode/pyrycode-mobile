package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #896: a subagent's tool rows render indented under the `Agent`/`Task` call that spawned them, one
 * step per level, and say so in their content description; a row whose parent is not loaded stays at
 * top level. Mounted on the real [ThreadScreen] so the derivation, the list and the row are one path.
 */
@RunWith(AndroidJUnit4::class)
class ToolRowNestingTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val ts: Instant = Instant.parse("2026-09-24T10:00:00Z")

    private fun subagentStep(level: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.cd_tool_subagent_step, level)

    private fun tool(
        id: String,
        name: String,
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
                toolCall = ToolCall(toolName = name, input = "", output = "", parentToolUseId = parent),
            ),
        )

    private fun setThread() {
        val items =
            listOf(
                tool(id = "toolu_agent", name = "Agent"),
                tool(id = "toolu_task", name = "Task", parent = "toolu_agent"),
                tool(id = "toolu_grep", name = "Grep", parent = "toolu_task"),
                tool(id = "toolu_bash", name = "Bash", parent = "toolu_not_loaded"),
            )
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
                )
            }
        }
    }

    private fun leftOf(toolName: String): Dp =
        composeTestRule
            .onNodeWithText(toolName, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
            .left

    @Test
    fun matchedChildAndGrandchild_indentOneStepPerLevel_andUnmatchedStaysTopLevel() {
        setThread()

        val top = leftOf("Agent")
        val child = leftOf("Task")
        val grandchild = leftOf("Grep")
        assertTrue("child must indent past its parent: $top -> $child", child > top)
        assertEquals("each level indents by the same step", (child - top).value, (grandchild - child).value, 0.5f)
        assertEquals("an unmatched parent renders at top level", top.value, leftOf("Bash").value, 0.5f)
    }

    @Test
    fun nestedRows_sayTheyAreSubagentSteps_andTopLevelRowsDoNot() {
        setThread()

        composeTestRule.onNode(hasContentDescription(subagentStep(1)) and hasText("Task")).assertExists()
        composeTestRule.onNode(hasContentDescription(subagentStep(2)) and hasText("Grep")).assertExists()
        composeTestRule.onNode(hasContentDescription(subagentStep(1)) and hasText("Agent")).assertDoesNotExist()
        composeTestRule.onNode(hasContentDescription(subagentStep(1)) and hasText("Bash")).assertDoesNotExist()
        composeTestRule
            .onAllNodesWithContentDescription(subagentStep(1))
            .assertCountEquals(1)
        composeTestRule
            .onAllNodesWithContentDescription(subagentStep(2))
            .assertCountEquals(1)
    }
}
