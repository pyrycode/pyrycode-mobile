package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.components.TOOL_ROW_TAG
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #1577: adjacent tool rows sit flush, their 1 dp outlines overlapping into one line as in Figma
 * `620:1792`; a tool row followed by anything else keeps its spacing below. Mounted on the real
 * [ThreadScreen], because the neighbour decision is the list's.
 */
@RunWith(AndroidJUnit4::class)
class ConsecutiveToolRowSpacingTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val ts: Instant = Instant.parse("2026-10-03T10:00:00Z")

    private fun message(
        id: String,
        role: Role,
        content: String = "",
        toolCall: ToolCall? = null,
    ): ThreadItem.MessageItem =
        ThreadItem.MessageItem(
            Message(
                id = id,
                sessionId = "s1",
                role = role,
                content = content,
                timestamp = ts,
                isStreaming = false,
                toolCall = toolCall,
            ),
        )

    private fun tool(
        id: String,
        name: String,
    ) = message(id, Role.Tool, toolCall = ToolCall(toolName = name, input = "", output = ""))

    private fun setThread() {
        val items =
            listOf(
                tool(id = "toolu_grep", name = "Grep"),
                tool(id = "toolu_read", name = "Read"),
                message(id = "m_reply", role = Role.Assistant, content = "Found it."),
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

    private fun toolRow(toolName: String): DpRect =
        composeTestRule
            .onNode(hasTestTag(TOOL_ROW_TAG) and hasAnyDescendant(hasText(toolName)), useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

    private fun assertOutlinesOverlapByOneDp() {
        val upper = toolRow("Grep")
        val lower = toolRow("Read")
        assertEquals(
            "the next tool row starts one outline width above: $upper / $lower",
            (upper.bottom - 1.dp).value,
            lower.top.value,
            0.5f,
        )
    }

    @Test
    fun adjacentCollapsedToolRows_shareOneOutline() {
        setThread()

        assertOutlinesOverlapByOneDp()
    }

    @Test
    fun adjacentToolRows_shareOneOutline_whenTheUpperIsExpanded() {
        setThread()
        val collapsedHeight = toolRow("Grep").height

        composeTestRule.onNodeWithText("Grep", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()

        assertTrue("the row must have expanded", toolRow("Grep").height > collapsedHeight)
        assertOutlinesOverlapByOneDp()
    }

    @Test
    fun toolRowFollowedByAssistantReply_keepsItsSpacingBelow() {
        setThread()

        val lastTool = toolRow("Read")
        val replyTop = composeTestRule.onNodeWithText("Found it.", useUnmergedTree = true).getUnclippedBoundsInRoot().top
        assertTrue("a tool row keeps 12 dp below it before a reply: ${lastTool.bottom} -> $replyTop", replyTop - lastTool.bottom >= 12.dp)
    }
}
