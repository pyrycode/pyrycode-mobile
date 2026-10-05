package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.BackgroundTask
import de.pyryco.mobile.data.model.BackgroundTaskRoster
import de.pyryco.mobile.data.model.BackgroundTaskUpdate
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

@RunWith(AndroidJUnit4::class)
class BackgroundAgentBlocksScreenTest {
    @get:Rule val compose = createComposeRule()
    private val ts = Instant.parse("2026-10-05T10:00:00Z")
    private var state by mutableStateOf(ThreadUiState("c", "Channel", hasMessages = true))
    private var collapse by mutableStateOf(false)

    private fun tool(
        id: String,
        name: String,
        parent: String = "",
    ) = ThreadItem.MessageItem(
        Message(
            id,
            "s",
            Role.Tool,
            "",
            ts,
            false,
            ToolCall(
                name,
                "original input",
                "original output",
                inputFields =
                    if (name ==
                        "Agent"
                    ) {
                        mapOf("run_in_background" to "true")
                    } else {
                        emptyMap()
                    },
                parentToolUseId = parent,
            ),
        ),
    )

    private fun user(id: String) = ThreadItem.MessageItem(Message(id, "s", Role.User, id, ts, false))

    private fun launch() = ThreadItem.BackgroundTaskLifecycle("t", ts, "a", "Launch description", "local_agent")

    private fun finish() = ThreadItem.BackgroundTaskLifecycle("t", ts, terminal = BackgroundTaskUpdate("", "completed", "", null))

    private fun mount(
        items: List<ThreadItem>,
        collapsed: Boolean = false,
    ) {
        state = state.copy(items = items)
        collapse = collapsed
        compose.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = state,
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    collapseToolUses = collapse,
                )
            }
        }
    }

    private fun list() = compose.onNode(hasScrollToIndexAction())

    private fun marker() = compose.onNodeWithText("Go to agent ↓")

    @Test fun lateJoinPreservesExpandedRunAndToolBodyAcrossSplitAndFinish() {
        mount(listOf(tool("outside", "Grep"), tool("a", "Agent"), tool("child", "Read", "a"), user("Newer")), true)
        compose.onNodeWithText("Using tools: 3", substring = true).performClick()
        compose.onNodeWithText("Agent").performClick()
        compose.runOnIdle {
            state =
                state.copy(
                    backgroundTasks =
                        BackgroundTaskRoster(
                            listOf(BackgroundTask("t", "a", "local_agent", "Roster description", null, null, null, false)),
                            0,
                        ),
                )
        }
        compose.onNodeWithText("Agent", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Read", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Grep", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("original input", substring = true, useUnmergedTree = true).assertCountEquals(1)
        compose.runOnIdle { state = state.copy(items = state.items + finish() + user("Later")) }
        compose.onNodeWithText("Agent", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("original input", substring = true, useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText("Using tools: 2", substring = true).performClick()
        compose.onAllNodesWithText("Agent").assertCountEquals(0)
        compose.runOnIdle { state = state.copy(items = state.items + user("Another")) }
        compose.onAllNodesWithText("Agent").assertCountEquals(0)
        marker().performClick()
        compose.onNodeWithText("Agent", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("original input", substring = true, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test fun lateJoinKeepsAPreviouslyCollapsedRunCollapsed() {
        mount(listOf(tool("outside", "Grep"), tool("a", "Agent"), tool("child", "Read", "a"), user("Newer")), true)
        compose.runOnIdle { state = state.copy(items = state.items + launch()) }
        compose.onNodeWithText("Using tools: 2", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("Agent").assertCountEquals(0)
    }

    @Test fun runningMarkerShowsTwoLinesAndToolExpansionSurvivesMovingAndFinishing() {
        mount(listOf(tool("a", "Agent"), tool("child", "Read", "a"), user("Newer")))
        compose.onNodeWithText("Agent").performClick()
        compose.onAllNodesWithText("original input", substring = true, useUnmergedTree = true).assertCountEquals(1)
        compose.runOnIdle { state = state.copy(items = state.items + launch()) }
        compose.onNodeWithText("Agent started, still working").assertIsDisplayed()
        compose.onNodeWithText("Launch description").assertIsDisplayed()
        compose.onAllNodesWithText("original input", substring = true, useUnmergedTree = true).assertCountEquals(1)
        val newer = compose.onNodeWithText("Newer").getUnclippedBoundsInRoot()
        val agent = compose.onNodeWithText("Agent", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(newer.bottom <= agent.top)
        compose.runOnIdle {
            state =
                state.copy(items = state.items + finish() + user("Later"), backgroundTasks = BackgroundTaskRoster(emptyList(), 0))
        }
        compose.onNodeWithText("Agent finished").assertIsDisplayed()
        marker().performClick()
        compose.onNodeWithText("Agent", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("original input", substring = true, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test fun markerOpensItsCollapsedRunBeforeAndAfterFinishAndDoesNotMergeWithOrdinaryTool() {
        mount(listOf(tool("a", "Agent"), launch(), user("Newer"), tool("outside", "Grep"), tool("child", "Read", "a")), true)
        compose.onAllNodesWithText("Agent").assertCountEquals(0)
        marker().performClick()
        compose.onNodeWithText("Agent", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Read", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { state = state.copy(items = state.items + finish() + user("Later")) }
        list().performScrollToNode(hasText("Go to agent ↓"))
        marker().performClick()
        compose.onNodeWithText("Agent", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("Using tools: 2", substring = true).assertCountEquals(1)
    }

    @Test fun rosterBeforeStartMovesBlockAndBackfillNeverDuplicatesIt() {
        mount(listOf(tool("a", "Agent"), user("Newer")))
        compose.runOnIdle {
            state =
                state.copy(
                    backgroundTasks =
                        BackgroundTaskRoster(
                            listOf(
                                BackgroundTask("t", "a", "local_agent", "Roster description", null, null, null, false),
                            ),
                            0,
                        ),
                )
        }
        compose.onNodeWithText("Agent started, still working").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(items = listOf(tool("a", "Agent"), launch(), user("Newer"))) }
        compose.onAllNodesWithText("Go to agent ↓").assertCountEquals(1)
        compose.onAllNodesWithText("Agent", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText("Launch description").assertIsDisplayed()
    }

    @Test fun twoRunningAgentsHaveSeparateRunsAndOneFinishLeavesTheOtherBelowLaterMessage() {
        mount(
            listOf(
                tool("a", "Agent"),
                launch(),
                tool("b", "Agent"),
                launch().copy(taskId = "tb", toolCallId = "b", description = "Second agent"),
                user("Newer"),
                tool("child", "Read", "a"),
                tool("second-child", "Read", "b"),
            ),
            true,
        )
        compose.onAllNodesWithText("Using tools: 2", substring = true).assertCountEquals(2)
        compose.runOnIdle { state = state.copy(items = state.items + finish() + user("Later")) }
        val runs =
            compose
                .onAllNodesWithText(
                    "Using tools: 2",
                    substring = true,
                ).fetchSemanticsNodes()
                .map { it.boundsInRoot }
                .sortedBy { it.top }
        val later = compose.onNodeWithText("Later").fetchSemanticsNode().boundsInRoot
        assertEquals(2, runs.size)
        assertTrue(runs.first().bottom <= later.top)
        assertTrue(later.bottom <= runs.last().top)
    }

    @Test fun markerDescriptionIsLengthBoundedInertSingleLineText() {
        val text = "https://example.invalid/<agent> ".repeat(200)
        mount(listOf(tool("a", "Agent"), launch().copy(description = text), user("Newer")))
        compose.onAllNodesWithText(text, useUnmergedTree = true).assertCountEquals(0)
        val description = compose.onNodeWithText(text.take(4096), useUnmergedTree = true).getUnclippedBoundsInRoot()
        val status = compose.onNodeWithText("Agent started, still working", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(status.height, description.height)
    }

    @Test fun inPlaceBlockGrowthPinsFollowerAndKeepsHistoryAnchor() {
        val older = (1..30).map { user("Older $it") }
        mount(older + tool("a", "Agent") + launch() + tool("child", "Read", "a"))
        compose.onNodeWithText("Read").performClick()

        fun grow(output: String) {
            compose.runOnIdle {
                state =
                    state.copy(
                        items =
                            state.items.map {
                                if (it is ThreadItem.MessageItem && it.message.id == "child") {
                                    it.copy(message = it.message.copy(toolCall = it.message.toolCall?.copy(output = output)))
                                } else {
                                    it
                                }
                            },
                    )
            }
            compose.waitForIdle()
        }
        grow((1..12).joinToString("\n") { "Growing result $it" })
        assertEquals(
            0f,
            list().fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value(),
            .001f,
        )
        list().performScrollToIndex(12)
        val anchor = compose.onAllNodesWithText("Older", substring = true).fetchSemanticsNodes().first()
        val text = anchor.config[androidx.compose.ui.semantics.SemanticsProperties.Text].first().text
        val top = anchor.boundsInRoot.top
        grow((1..20).joinToString("\n") { "More result $it" })
        assertEquals(
            top,
            compose
                .onNodeWithText(text)
                .fetchSemanticsNode()
                .boundsInRoot.top,
            1f,
        )
    }
}
