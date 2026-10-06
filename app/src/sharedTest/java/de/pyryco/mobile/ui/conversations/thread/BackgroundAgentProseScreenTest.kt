package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.BackgroundTaskUpdate
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.historyKeys
import de.pyryco.mobile.e2e.questionAnswerTarget
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundAgentProseScreenTest {
    @get:Rule val compose = createComposeRule()
    private val ts = Instant.parse("2026-10-06T10:00:00Z")
    private var collapse by mutableStateOf(true)
    private var items by mutableStateOf<List<ThreadItem>>(emptyList())
    private var markers by mutableStateOf<List<ThreadHistoryMarker>>(emptyList())

    private fun prose(
        id: String,
        parent: String = "",
    ) = ThreadItem.MessageItem(Message(id, "s", Role.Assistant, id, ts, false, parentToolUseId = parent))

    private fun mount(
        childTool: Boolean,
        collapsed: Boolean,
    ) {
        collapse = collapsed
        val agent =
            ThreadItem.MessageItem(
                Message(
                    "a",
                    "s",
                    Role.Tool,
                    "",
                    ts,
                    false,
                    toolCall = ToolCall("Agent", "", "", inputFields = mapOf("run_in_background" to "true")),
                ),
            )
        val read =
            ThreadItem.MessageItem(
                Message(
                    "read",
                    "s",
                    Role.Tool,
                    "",
                    ts,
                    false,
                    toolCall = ToolCall("Read", "", "", parentToolUseId = "a"),
                ),
            )
        items = listOf(
            agent,
            ThreadItem.BackgroundTaskLifecycle("task", ts, "a", "Agent", "local_agent"),
            prose("Main"),
            prose("Child", "a"),
        ) + (if (childTool) listOf(read, prose("Child after tool", "a")) else emptyList())
        compose.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = ThreadUiState("c", "Channel", items = items, hasMessages = true, historyMarkers = markers),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    collapseToolUses = collapse,
                )
            }
        }
    }

    private fun run(count: Int) = compose.onNodeWithText("Using tools: $count", substring = true)

    private fun reveal(text: String) = compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))

    @Test fun lateParentReplayMovesVisibleSyntheticIntoClosedBlockAndOpeningRevealsItOnce() {
        mount(false, true)
        val receivedAt = Instant.parse("2020-01-01T10:00:00Z")
        val delta = LiveSessionEvent.AssistantDelta("c", "child", 0, "Reply", "")
        var fold =
            ThreadFold(items.filterNot { (it as? ThreadItem.MessageItem)?.message?.id == "Child" }, null)
                .reduce(ThreadInput.Live(delta, receivedAt), "c")
        compose.runOnIdle { items = fold.render() }
        compose.onNodeWithText("Reply", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("background-agent-child:a").assertDoesNotExist()
        compose.runOnIdle {
            fold = fold.reduce(ThreadInput.Live(delta.copy(parentToolUseId = "a"), receivedAt), "c")
            items = fold.render()
        }
        compose.onNodeWithText("Reply", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Main").assertIsDisplayed()
        run(1).performClick()
        compose.onNodeWithText("Reply", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("background-agent-child:a").assertIsDisplayed()
        compose.onAllNodesWithText("Reply", substring = true).assertCountEquals(1)
        val child = compose.onNodeWithText("Reply", substring = true).getUnclippedBoundsInRoot()
        val main = compose.onNodeWithText("Main").getUnclippedBoundsInRoot()
        assertEquals(main.left + 16.dp, child.left)
        run(1).performClick()
        compose.onNodeWithText("Reply", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("background-agent-child:a").assertDoesNotExist()
        compose.runOnIdle { collapse = false }
        compose.onNodeWithText("Reply", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("Reply", substring = true).assertCountEquals(1)
    }

    @Test fun proseWithoutChildToolUsesExistingOpenCloseControlAndSetting() {
        mount(false, true)
        compose.onNodeWithText("Child").assertDoesNotExist()
        compose.onNodeWithText("Main").assertIsDisplayed()
        run(1).performClick()
        compose.onNodeWithText("Child").assertIsDisplayed()
        compose.onAllNodesWithText("Child").assertCountEquals(1)
        run(1).performClick()
        compose.onNodeWithText("Child").assertDoesNotExist()
        compose.runOnIdle { collapse = false }
        compose.onNodeWithText("Child").assertIsDisplayed()
        compose.runOnIdle { collapse = true }
        compose.onNodeWithText("Child").assertDoesNotExist()
        run(1).performClick()
        compose.onNodeWithText("Child").assertIsDisplayed()
    }

    @Test fun agentRunControlHasStableOwnershipWhenAnOrdinaryRunHasTheSameLabel() {
        mount(true, true)
        compose.runOnIdle {
            val tool = items.filterIsInstance<ThreadItem.MessageItem>().first { it.message.id == "read" }.message
            items = listOf(
                ThreadItem.MessageItem(tool.copy(id = "outside", toolCall = tool.toolCall?.copy(parentToolUseId = ""))),
                ThreadItem.MessageItem(tool.copy(id = "outside2", toolCall = tool.toolCall?.copy(parentToolUseId = ""))),
                prose("Ordinary reply"),
            ) + items
        }
        val agentRun = hasText("Using tools: 2", substring = true) and hasClickAction() and hasAnyAncestor(hasTestTag("tool-run:a"))
        val ordinaryRun =
            hasText("Using tools: 2", substring = true) and hasClickAction() and hasAnyAncestor(hasTestTag("tool-run:outside"))
        compose.onNode(agentRun).performClick()
        reveal("Child")
        compose.onAllNodesWithText("Child").assertCountEquals(1)
        compose.onNodeWithTag("tool-run:outside").assertExists()
        compose.onNode(ordinaryRun).performClick()
        reveal("Using tools: 2")
        compose.onNode(agentRun).performClick()
        compose.onNodeWithText("Child").assertDoesNotExist()
        compose.onNodeWithText("Child after tool").assertDoesNotExist()
        compose.onNodeWithTag("tool-run:outside").assertExists()
    }

    @Test fun interleavedProseAndToolsHideTogetherAndRemainNestedWhenSettingIsOff() {
        mount(true, true)
        compose.onNodeWithText("Child").assertDoesNotExist()
        compose.onNodeWithText("Child after tool").assertDoesNotExist()
        run(2).performClick()
        reveal("Child after tool")
        compose.onNodeWithText("Child after tool").assertIsDisplayed()
        reveal("Child")
        compose.onNodeWithText("Child").assertIsDisplayed()
        reveal("Using tools: 2")
        run(2).performClick()
        compose.onNodeWithText("Child").assertDoesNotExist()
        compose.onNodeWithText("Child after tool").assertDoesNotExist()
        compose.runOnIdle { collapse = false }
        reveal("Child")
        val child = compose.onNodeWithText("Child").getUnclippedBoundsInRoot()
        reveal("Main")
        val main = compose.onNodeWithText("Main").getUnclippedBoundsInRoot()
        assertEquals(main.left + 16.dp, child.left)
        compose.onAllNodesWithText("Child").assertCountEquals(1)
    }

    @Test fun earlyProseInALongBlockCanBeScrolledIntoViewAndHiddenByItsOwnRun() {
        mount(true, true)
        compose.runOnIdle { items = items + (0 until 30).map { prose("Later child $it", "a") } }
        val ownedRun = hasText("Using tools: 2", substring = true) and hasClickAction() and hasAnyAncestor(hasTestTag("tool-run:a"))
        compose.questionAnswerTarget(ownedRun).performClick()
        reveal("Later child 29")
        // A loaded child can be outside composition; ownership is checked after scrolling it into view.
        compose.onNodeWithText("Child").assertDoesNotExist()
        reveal("Child")
        compose.onNode(hasText("Child") and hasAnyAncestor(hasTestTag("background-agent-child:a"))).assertIsDisplayed()
        compose.onAllNodesWithText("Child").assertCountEquals(1)
        compose.questionAnswerTarget(ownedRun).performClick()
        compose.onNodeWithText("Child").assertDoesNotExist()
        compose.onNodeWithText("Later child 29").assertDoesNotExist()
        // Collapse preserves every loaded paragraph; disabling it reveals the same child again.
        compose.runOnIdle { collapse = false }
        reveal("Child")
        compose.onAllNodesWithText("Child").assertCountEquals(1)
        reveal("Later child 29")
        compose.onNodeWithText("Later child 29").assertIsDisplayed()
    }

    @Test fun childGapStaysVisibleAtClosedHeaderAndReturnsToProseWhenOpened() {
        mount(false, true)
        compose.runOnIdle {
            val child = items.filterIsInstance<ThreadItem.MessageItem>().first { it.message.id == "Child" }
            markers = listOf(ThreadHistoryMarker(1, child.historyKeys().first()))
        }
        compose.onNodeWithText("Child").assertDoesNotExist()
        compose.onNodeWithTag("history-gap:1").assertIsDisplayed()
        run(1).performClick()
        compose.onNodeWithText("Child").assertIsDisplayed()
        compose.onNodeWithTag("history-gap:1").assertIsDisplayed()
        val gap = compose.onNodeWithTag("history-gap:1").getUnclippedBoundsInRoot()
        val prose = compose.onNodeWithText("Child").getUnclippedBoundsInRoot()
        org.junit.Assert.assertTrue(gap.bottom <= prose.top)
        run(1).performClick()
        compose.onNodeWithText("Child").assertDoesNotExist()
        compose.onNodeWithTag("history-gap:1").assertIsDisplayed()
        compose.runOnIdle { collapse = false }
        compose.onNodeWithText("Child").assertIsDisplayed()
        compose.onNodeWithTag("history-gap:1").assertIsDisplayed()
    }

    @Test fun finishAndLaterMainReplyKeepProseNestedAndCollapseStillControlsVisibility() {
        mount(false, false)
        compose.runOnIdle {
            items = items +
                ThreadItem.BackgroundTaskLifecycle(
                    "task",
                    ts,
                    terminal = BackgroundTaskUpdate("", "completed", "", null),
                ) + prose("Later")
        }
        compose.onNodeWithTag("background-agent-child:a").assertIsDisplayed()
        compose.onAllNodesWithText("Child").assertCountEquals(1)
        compose.runOnIdle { collapse = true }
        compose.onNodeWithText("Child").assertDoesNotExist()
        run(1).performClick()
        compose.onNodeWithText("Child").assertIsDisplayed()
        compose.onNodeWithText("Later").assertIsDisplayed()
    }
}
