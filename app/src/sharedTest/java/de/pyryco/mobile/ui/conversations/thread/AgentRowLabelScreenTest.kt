package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.BackgroundTaskUpdate
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins the "Agent" word itself on a background agent's own row (release-4592 regression, #1827
 * follow-up): [foldToolRuns] must never sweep the block's root call into the generic "Using tools: N"
 * header, in every state a reader can land the block in. Each test here is a single, deliberately minimal
 * state; the broader fold mechanics (carrying, backfill, history gaps) are covered in
 * [BackgroundAgentBlocksScreenTest] and [BackgroundAgentProseScreenTest].
 */
@RunWith(AndroidJUnit4::class)
class AgentRowLabelScreenTest {
    @get:Rule val compose = createComposeRule()
    private val ts = Instant.parse("2026-10-07T10:00:00Z")
    private var collapse by mutableStateOf(true)

    private fun agentCall() =
        ThreadItem.MessageItem(
            Message(
                "a",
                "s",
                Role.Tool,
                "",
                ts,
                false,
                toolCall =
                    ToolCall(
                        "Agent",
                        "",
                        "",
                        inputFields =
                            mapOf(
                                "run_in_background" to "true",
                            ),
                    ),
            ),
        )

    private fun launch() = ThreadItem.BackgroundTaskLifecycle("task", ts, "a", "Launch description", "local_agent")

    private fun finish() = ThreadItem.BackgroundTaskLifecycle("task", ts, terminal = BackgroundTaskUpdate("", "completed", "", null))

    private fun tool(
        id: String,
        name: String,
        parent: String = "a",
    ) = ThreadItem.MessageItem(Message(id, "s", Role.Tool, "", ts, false, toolCall = ToolCall(name, "", "", parentToolUseId = parent)))

    private fun prose(
        id: String,
        parent: String = "a",
    ) = ThreadItem.MessageItem(Message(id, "s", Role.Assistant, id, ts, false, parentToolUseId = parent))

    private fun mount(
        items: List<ThreadItem>,
        collapsed: Boolean = true,
    ) {
        collapse = collapsed
        compose.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = ThreadUiState("c", "Channel", items = items, hasMessages = true),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    collapseToolUses = collapse,
                )
            }
        }
    }

    @Test fun runningLoneAgentShowsTheLabel() {
        mount(listOf(agentCall(), launch()))
        compose.onNodeWithText("Agent", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun runningAgentWithOneProseChildShowsTheLabel() {
        // A lone child, prose or tool, never meets the two-row fold threshold (#1827 follow-up): it always
        // shows beside "Agent", with nothing to collapse it behind.
        mount(listOf(agentCall(), launch(), prose("child")))
        compose.onNodeWithText("Agent", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("child", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun runningAgentWithOneToolChildShowsTheLabel() {
        mount(listOf(agentCall(), launch(), tool("child", "Read")))
        compose.onNodeWithText("Agent", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Read", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun runningAgentWithTwoChildrenShowsTheLabelWhileItsOwnRunStaysCollapsed() {
        // Two children form a real, collapsible run -- but the root itself is never part of it.
        mount(listOf(agentCall(), launch(), tool("child", "Read"), tool("child2", "Glob")))
        compose.onNodeWithText("Agent", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Using tools: 2", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Read", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Using tools: 2", substring = true).performClick()
        compose.onNodeWithText("Agent", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Read", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun finishedLoneAgentShowsTheLabel() {
        mount(listOf(agentCall(), launch(), finish()))
        compose.onNodeWithText("Agent", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun finishedAgentWithTwoChildrenShowsTheLabelWhileItsOwnRunStaysCollapsed() {
        mount(listOf(agentCall(), launch(), tool("child", "Read"), tool("child2", "Glob"), finish()))
        compose.onNodeWithText("Agent", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Using tools: 2", substring = true).assertIsDisplayed()
    }

    @Test fun runningAgentShowsTheLabelWithCollapseSettingOff() {
        mount(listOf(agentCall(), launch(), tool("child", "Read"), tool("child2", "Glob")), collapsed = false)
        compose.onNodeWithText("Agent", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Read", useUnmergedTree = true).assertIsDisplayed()
    }
}
