package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
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
import de.pyryco.mobile.e2e.assertFinishedAgentMarker
import de.pyryco.mobile.e2e.questionAnswerTarget
import de.pyryco.mobile.e2e.verifyAgentRunNavigation
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

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
        compact: Boolean = false,
    ): StateRestorationTester {
        state = state.copy(items = items)
        collapse = collapsed
        val restoration = StateRestorationTester(compose)
        val content: @Composable () -> Unit = {
            PyrycodeMobileTheme(darkTheme = true) {
                ThreadScreen(
                    state = (state).withLiteralRows(),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    collapseToolUses = collapse,
                )
            }
        }
        restoration.setContent {
            if (compact) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 480.dp)), content)
            } else {
                content()
            }
        }
        return restoration
    }

    private fun list() = compose.onNode(hasScrollToIndexAction())

    private fun marker() = compose.onNodeWithText("Go to agent ↓")

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun lateNewerReplyDisposesMarkerButKeyedRevealStillNavigatesHeldAgent() {
        val reply = ThreadItem.MessageItem(Message("reply", "s", Role.Assistant, "Short reply", ts, true))
        mount(
            listOf(tool("a", "Agent"), launch(), user("Newer"), reply, tool("child", "Read", "a"), tool("child2", "Glob", "a")),
            true,
            compact = true,
        )
        val ownedMarker = hasText("Go to agent ↓") and hasClickAction() and hasText("Launch description")
        var grew = false
        val target =
            compose.questionAnswerTarget(ownedMarker, lazyKey = "agent-start:a") { stage ->
                println(stage)
                if ("stage=before" in stage && !grew) {
                    grew = true
                    compose.runOnIdle {
                        state =
                            state.copy(
                                items =
                                    state.items.map { item ->
                                        if (item ==
                                            reply
                                        ) {
                                            reply.copy(
                                                message =
                                                    reply.message.copy(
                                                        content = (0 until 30).joinToString("\n\n") { "Newer reply paragraph $it" },
                                                        isStreaming = false,
                                                    ),
                                            )
                                        } else {
                                            item
                                        }
                                    },
                            )
                    }
                    compose.waitForIdle()
                    val index = list().fetchSemanticsNode().config[SemanticsProperties.IndexForKey]("agent-start:a")
                    val composed = compose.onAllNodes(ownedMarker).fetchSemanticsNodes().size
                    val projected =
                        fixtureBackgroundRows(fixtureQueuedRows(state.items, state.queuedMessages), state.items, state.backgroundTasks)
                            .filterIsInstance<ThreadRow.AgentStartMarker>()
                            .single { it.agentId == "a" }
                    assertTrue("the held Agent must still be running", !projected.finished)
                    println(
                        "event=first_marker stage=late_reply task_running=${!projected.finished} newer_streaming=false marker_index=$index composed=$composed",
                    )
                    assertTrue("held marker must remain projected", index >= 0)
                    assertEquals("late reply must dispose the initially revealed marker", 0, composed)
                }
            }
        assertTrue("late reply must exercise the reveal/tap boundary", grew)
        val bounds = target.fetchSemanticsNode().boundsInRoot
        val top =
            compose
                .onNodeWithTag("thread-top-bar")
                .fetchSemanticsNode()
                .boundsInRoot.bottom
        val bottom =
            compose
                .onNodeWithTag("thread-composer")
                .fetchSemanticsNode()
                .boundsInRoot.top
        assertTrue("physical marker tap must clear chrome", bounds.center.y > top && bounds.center.y < bottom)
        target.performTouchInput { click(center) }
        compose.onNodeWithTag("background-agent:a").assertIsDisplayed()
        compose.onNodeWithText("Read", useUnmergedTree = true).assertDoesNotExist()
        assertTrue(
            "scroll-only navigation must keep the child run closed",
            list().fetchSemanticsNode().config[SemanticsProperties.IndexForKey]("msg:child2") < 0,
        )
    }

    @Test fun keyedMarkerWithHeldDescriptionNavigatesOnlyItsAgent() {
        val children =
            (0 until 24).map { n ->
                ThreadItem.MessageItem(Message("owned$n", "s", Role.Assistant, "Owned paragraph $n", ts, false, parentToolUseId = "a"))
            }
        mount(
            listOf(
                tool("a", "Agent"),
                launch(),
                tool("b", "Agent"),
                launch().copy(taskId = "tb", toolCallId = "b", description = "Other agent"),
            ) + (0 until 24).map { user("Newer $it") } + children,
        )
        val ownedMarker = hasText("Go to agent ↓") and hasClickAction() and hasText("Launch description")
        list().performScrollToNode(ownedMarker)
        compose.onAllNodes(hasText("Go to agent ↓") and hasClickAction()).assertCountEquals(2)
        compose.onNodeWithTag("background-agent:a").assertIsNotDisplayed()
        val target = compose.questionAnswerTarget(ownedMarker, lazyKey = "agent-start:a")
        target.performTouchInput { click(center) }
        compose.onNodeWithTag("background-agent:a").assertIsDisplayed()
        compose.onNodeWithTag("background-agent:b").assertIsNotDisplayed()
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun finishedMarkerIsRemeasuredAfterLaterReplyGrowth() {
        val reply = ThreadItem.MessageItem(Message("later-reply", "s", Role.Assistant, "Short later reply", ts, false))
        mount(
            listOf(tool("a", "Agent"), launch(), tool("child", "Read", "a"), tool("child2", "Glob", "a"), finish(), user("Later"), reply),
            collapsed = true,
            compact = true,
        )
        val ownedMarker = hasText("Go to agent ↓") and hasClickAction() and hasText("Launch description")
        var grew = false
        compose.assertFinishedAgentMarker("a", ownedMarker, "Agent finished") { sample ->
            println(sample)
            if ("stage=before" in sample && !grew) {
                grew = true
                compose.runOnIdle {
                    state =
                        state.copy(
                            items =
                                state.items.map { item ->
                                    if (item == reply) {
                                        val paragraphs = (0 until 30).joinToString("\n\n") { "Later reply paragraph $it" }
                                        reply.copy(message = reply.message.copy(content = paragraphs))
                                    } else {
                                        item
                                    }
                                },
                        )
                }
                compose.waitForIdle()
                assertTrue(
                    "the displaced finished marker must remain in the exact Agent's projection",
                    list().fetchSemanticsNode().config[SemanticsProperties.IndexForKey]("agent-start:a") >= 0,
                )
            }
        }
        assertTrue("exercise late screen growth after the initial finished-marker reveal", grew)
        val finished =
            compose
                .onNode(ownedMarker and hasText("Agent finished"))
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val top =
            compose
                .onNodeWithTag("thread-top-bar")
                .fetchSemanticsNode()
                .boundsInRoot.bottom
        val bottom =
            compose
                .onNodeWithTag("thread-composer")
                .fetchSemanticsNode()
                .boundsInRoot.top
        assertTrue("the finished marker must clear thread chrome after late growth", finished.center.y > top && finished.center.y < bottom)
        assertTrue(
            "revealing the marker must keep the owned run collapsed",
            list().fetchSemanticsNode().config[SemanticsProperties.IndexForKey]("msg:child2") < 0,
        )
    }

    @Test fun anotherFinishedAgentCannotSatisfyTheOwnedMarkerCheck() {
        mount(
            listOf(
                tool("b", "Agent"),
                launch().copy(taskId = "tb", toolCallId = "b"),
                finish().copy(taskId = "tb"),
            ),
            collapsed = true,
        )
        compose.onNodeWithText("Agent finished").assertIsDisplayed()
        assertThrows(ComposeTimeoutException::class.java) {
            compose.assertFinishedAgentMarker("a", hasText("Go to agent ↓") and hasText("Launch description"), "Agent finished")
        }
    }

    @Test fun resetBeforeFinishedMarkerRevealWaitsForTheOwnedFamily() {
        val settled =
            listOf(tool("a", "Agent"), launch(), tool("child", "Read", "a"), tool("child2", "Glob", "a"), finish(), user("Later"))
        mount(settled, collapsed = true)
        compose.runOnIdle { state = state.copy(items = emptyList(), hasMessages = false) }
        compose.onAllNodes(hasScrollToNodeAction()).assertCountEquals(0)
        var resetObserved = false
        val ownedMarker = hasText("Go to agent ↓") and hasClickAction() and hasText("Launch description")
        compose.assertFinishedAgentMarker("a", ownedMarker, "Agent finished") { sample ->
            println(sample)
            if ("event=finished_agent_readiness" in sample && "key_present=false" in sample && !resetObserved) {
                resetObserved = true
                compose.runOnIdle { state = state.copy(items = settled, hasMessages = true) }
            }
        }
        assertTrue("observe the reset before allowing catch-up", resetObserved)
        compose.onNode(ownedMarker and hasText("Agent finished")).assertIsDisplayed()
        assertTrue(
            "the child run remains collapsed",
            list().fetchSemanticsNode().config[SemanticsProperties.IndexForKey]("msg:child2") < 0,
        )
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun generationReplacementDuringFinishedRevealUsesTheCurrentOwnedKey() {
        var currentAgent = "a"
        val initial =
            listOf(tool("a", "Agent"), launch(), tool("child", "Read", "a"), tool("child2", "Glob", "a"), finish(), user("Later"))
        mount(initial, collapsed = true, compact = true)
        val ownedMarker = hasText("Go to agent ↓") and hasClickAction() and hasText("Launch description")
        var replaced = false
        var resolvedReplacement = false
        compose.assertFinishedAgentMarker(
            "a",
            ownedMarker,
            "Agent finished",
            resolveAgentId = {
                if (currentAgent == "a-next") resolvedReplacement = true
                currentAgent
            },
        ) { sample ->
            println(sample)
            if ("stage=before" in sample && !replaced) {
                replaced = true
                compose.runOnIdle { state = state.copy(items = emptyList(), hasMessages = false) }
                compose.waitForIdle()
                currentAgent = "a-next"
                val replacement =
                    listOf(
                        tool(currentAgent, "Agent"),
                        launch().copy(toolCallId = currentAgent),
                        tool("child-next", "Read", currentAgent),
                        tool("child2-next", "Glob", currentAgent),
                        finish(),
                    ) + (0 until 30).map { user("Later $it") }
                compose.runOnIdle { state = state.copy(items = replacement, hasMessages = true) }
                compose.waitForIdle()
                val index = list().fetchSemanticsNode().config[SemanticsProperties.IndexForKey]
                assertTrue("the previous generation's marker must be gone", index("agent-start:a") < 0)
                assertTrue("the same owned Agent must have its new rendered key", index("agent-start:$currentAgent") >= 0)
                compose.onNode(ownedMarker).assertIsNotDisplayed()
            }
        }
        assertTrue("exercise replacement during geometry measurement", replaced)
        assertTrue("resolve the Agent from its replacement generation", resolvedReplacement)
        compose.onNode(ownedMarker and hasText("Agent finished")).assertIsDisplayed()
    }

    @Test fun generationBetweenReadinessAndScrollDoesNotDispatchAStaleKey() {
        var currentAgent = "a"
        mount(listOf(tool("a", "Agent"), launch(), finish(), user("Later")), collapsed = true)
        val ownedMarker = hasText("Go to agent ↓") and hasClickAction() and hasText("Launch description")
        var replaced = false
        compose.assertFinishedAgentMarker(
            "a",
            ownedMarker,
            "Agent finished",
            resolveAgentId = { currentAgent },
        ) { sample ->
            println(sample)
            if ("event=finished_agent_readiness" in sample && "key_present=true" in sample && !replaced) {
                replaced = true
                currentAgent = "a-next"
                compose.runOnIdle {
                    state =
                        state.copy(
                            items = listOf(tool(currentAgent, "Agent"), launch().copy(toolCallId = currentAgent), finish(), user("Later")),
                        )
                }
                val index = list().fetchSemanticsNode().config[SemanticsProperties.IndexForKey]
                assertTrue("the sampled generation must be gone before scroll dispatch", index("agent-start:a") < 0)
                assertTrue("the same owned Agent has its replacement key", index("agent-start:$currentAgent") >= 0)
            }
        }
        assertTrue("replace the generation after readiness but before scroll dispatch", replaced)
        compose.onNode(ownedMarker and hasText("Agent finished")).assertIsDisplayed()
    }

    @Test fun anotherAgentsFinishedLabelCannotSatisfyTheOwnedMarkerCheck() {
        mount(
            listOf(
                tool("a", "Agent"),
                launch(),
                tool("b", "Agent"),
                launch().copy(taskId = "tb", toolCallId = "b", description = "Other Agent"),
                finish().copy(taskId = "tb"),
            ),
            collapsed = true,
        )
        compose.onNodeWithText("Agent finished").assertIsDisplayed()
        val failure =
            assertThrows(AssertionError::class.java) {
                compose.assertFinishedAgentMarker("a", hasText("Go to agent ↓") and hasText("Launch description"), "Agent finished")
            }
        assertTrue("the owned marker must itself report finished", failure.message.orEmpty().contains("Agent finished"))
    }

    @Test fun removedMarkerFailsInsteadOfBeingTreatedAsLazyDisposal() {
        mount(listOf(tool("a", "Agent"), launch(), user("Newer")), true)
        val ownedMarker = hasText("Go to agent ↓") and hasClickAction() and hasText("Launch description")
        val failure =
            assertThrows(AssertionError::class.java) {
                compose.questionAnswerTarget(ownedMarker, lazyKey = "agent-start:a") { stage ->
                    if ("stage=before" in stage) {
                        compose.runOnIdle { state = state.copy(items = listOf(user("Replacement"))) }
                    }
                }
            }
        assertEquals("tap target left the projected list", failure.message)
    }

    @Test fun lateJoinPreservesExpandedRunAndToolBodyAcrossSplitAndFinish() {
        // A second child ("child2") so that, once "a" joins the roster below, its own run of children can
        // still collapse -- the root "a" itself never folds into it (#1827 follow-up).
        mount(
            listOf(tool("outside", "Grep"), tool("a", "Agent"), tool("child", "Read", "a"), tool("child2", "Glob", "a"), user("Newer")),
            true,
        )
        compose.onNodeWithText("Using tools: 4", substring = true).performClick()
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
        val childRun = hasText("Using tools: 2", substring = true) and hasClickAction() and hasAnyAncestor(hasTestTag("tool-run:child"))
        // The list extends behind the composer; rest at the newest end so the header's tap is clear.
        list().performScrollToIndex(0)
        compose.onNode(childRun).performClick()
        // "a" always draws as itself (#1827 follow-up), so collapsing its children's run never hides it.
        compose.onNodeWithText("Agent", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Read", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { state = state.copy(items = state.items + user("Another")) }
        compose.onNodeWithText("Agent", useUnmergedTree = true).assertIsDisplayed()
        list().performScrollToNode(hasText("Go to agent ↓"))
        marker().performClick()
        compose.onNodeWithText("Agent", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("original input", substring = true, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test fun lateJoinKeepsAPreviouslyCollapsedRunCollapsed() {
        // A second child ("child2") so "a"'s own run of children can collapse -- the root "a" itself never
        // folds into it (#1827 follow-up), so it stays visible even while its children's run is collapsed.
        mount(
            listOf(tool("outside", "Grep"), tool("a", "Agent"), tool("child", "Read", "a"), tool("child2", "Glob", "a"), user("Newer")),
            true,
        )
        compose.runOnIdle { state = state.copy(items = state.items + launch()) }
        compose.onNodeWithText("Using tools: 2", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Agent", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Read", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun lateJoinOfALoneAgentLeavesItsPendingIntentParkedAndItsNewRunCollapsed() {
        mount(listOf(tool("outside", "Grep"), tool("a", "Agent"), user("Newer")), true)
        compose.onNodeWithText("Using tools: 2", substring = true).performClick()
        compose.runOnIdle { state = state.copy(items = state.items + launch()) }
        compose.onNodeWithText("Agent started, still working").assertIsDisplayed()
        // "a" always draws as itself (#1827 follow-up), so its pending open intent -- parked on its own id
        // while it had no children -- has nothing left to resolve into once children do arrive: the new
        // run starts collapsed, same as any fresh one would.
        compose.runOnIdle { state = state.copy(items = state.items + tool("child", "Read", "a") + tool("child2", "Glob", "a")) }
        compose.onNodeWithText("Agent", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Using tools: 2", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Read", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Using tools: 2", substring = true).performClick()
        compose.onNodeWithText("Read", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { state = state.copy(items = state.items + user("Later")) }
        compose.onNodeWithText("Agent", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun pendingLoneAgentExpansionSurvivesRestorationBeforeItsChildArrives() {
        val restoration = mount(listOf(tool("outside", "Grep"), tool("a", "Agent"), user("Newer")), true)
        compose.onNodeWithText("Using tools: 2", substring = true).performClick()
        compose.runOnIdle { state = state.copy(items = state.items + launch()) }
        compose.onNodeWithText("Agent started, still working").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()
        // "a" always draws as itself (#1827 follow-up); its restored pending intent has nothing left to
        // resolve into once children arrive, so their new run starts collapsed.
        compose.runOnIdle { state = state.copy(items = state.items + tool("child", "Read", "a") + tool("child2", "Glob", "a")) }
        compose.onNodeWithText("Agent", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Read", useUnmergedTree = true).assertDoesNotExist()

        compose.onNodeWithText("Using tools: 2", substring = true).performClick()
        compose.onNodeWithText("Read", useUnmergedTree = true).assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { state = state.copy(items = state.items + user("Later")) }
        compose.onNodeWithText("Agent", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun pendingLoneAgentExpansionDoesNotLeakToAnotherConversation() {
        mount(listOf(tool("outside", "Grep"), tool("a", "Agent"), user("Newer")), true)
        compose.onNodeWithText("Using tools: 2", substring = true).performClick()
        compose.runOnIdle { state = state.copy(items = state.items + launch()) }
        compose.onNodeWithText("Agent started, still working").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(conversationId = "another") }
        compose.runOnIdle { state = state.copy(items = state.items + tool("child", "Read", "a") + tool("child2", "Glob", "a")) }
        // "a" always draws as itself (#1827 follow-up), and a new child run never starts open regardless
        // (see the lone-agent tests above), so there is nothing left for a leaked intent to reveal.
        compose.onNodeWithText("Using tools: 2", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Read", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun agentBackfillCarriesAnOpenRunOfItsLoadedChildrenIntoTheBlock() {
        mount(listOf(tool("outside", "Grep"), tool("c1", "Read", "a"), tool("c2", "Glob", "a"), user("Newer")), true)
        compose.onNodeWithText("Using tools: 3", substring = true).performClick()
        compose.onNodeWithText("Read").performClick()
        val input = compose.onAllNodesWithText("original input", substring = true, useUnmergedTree = true)
        input.assertCountEquals(1)
        compose.runOnIdle { state = state.copy(items = listOf(tool("a", "Agent"), launch()) + state.items) }
        // "a" joins the backfilled block but never joins its run itself (#1827 follow-up): only its two
        // children, c1 and c2, are counted.
        compose.onNodeWithText("Using tools: 2", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Agent", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Glob", useUnmergedTree = true).assertIsDisplayed()
        // The open Read body, plus the Agent header's own input subject.
        input.assertCountEquals(2)
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

    @Test fun markerScrollsWithoutOpeningItsCollapsedRunAndDoesNotMergeWithOrdinaryTool() {
        mount(
            listOf(
                tool("a", "Agent"),
                launch(),
                user("Newer"),
                tool("outside", "Grep"),
                tool("child", "Read", "a"),
                tool("child2", "Glob", "a"),
            ),
            true,
        )
        // "a" always draws as itself (#1827 follow-up): it is visible before the reader even navigates to
        // it, while its own two-child run stays collapsed and never merges with the ordinary tool row.
        compose.onNodeWithText("Agent", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Read", useUnmergedTree = true).assertDoesNotExist()
        marker().performClick()
        // "Go to agent" scrolls only (this PR): the root stays visible as always and its children's run
        // stays exactly as collapsed as it was before the tap.
        compose.onNodeWithText("Agent", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Read", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { state = state.copy(items = state.items + finish() + user("Later")) }
        list().performScrollToNode(hasText("Go to agent ↓"))
        marker().performClick()
        compose.onNodeWithText("Agent", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Read", useUnmergedTree = true).assertDoesNotExist()
        compose.onAllNodesWithText("Using tools: 2", substring = true).assertCountEquals(1)
    }

    /**
     * "Go to agent" moves the viewport to the block's root row only (this PR). Before the fix it also
     * opened the root's own collapsed run; a reader who wanted the run closed had it reopened under them
     * on every jump. Two children so the run can collapse, mirroring the rest of this file's #1827
     * follow-up fixtures.
     */
    @Test fun markerOnlyScrollsAndLeavesACollapsedRunCollapsed() {
        mount(
            listOf(tool("a", "Agent"), launch(), tool("child", "Read", "a"), tool("child2", "Glob", "a"), user("Newer")),
            true,
        )
        compose.onNodeWithText("Using tools: 2", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Read", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { state = state.copy(items = state.items + finish() + user("Later")) }
        marker().performClick()
        // The root scrolled into view, between the two later messages -- navigation happened -- but its
        // run is still exactly as collapsed as it was before the tap.
        assertTrue(
            compose.onNodeWithText("Newer").getUnclippedBoundsInRoot().top <
                compose.onNodeWithText("Agent", useUnmergedTree = true).getUnclippedBoundsInRoot().top,
        )
        assertTrue(
            compose.onNodeWithText("Agent", useUnmergedTree = true).getUnclippedBoundsInRoot().top <
                compose.onNodeWithText("Later").getUnclippedBoundsInRoot().top,
        )
        compose.onNodeWithText("Read", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Using tools: 2", substring = true).assertIsDisplayed()
        // The run still opens on its own tap -- only the marker's auto-expand side effect was removed.
        compose.onNodeWithText("Using tools: 2", substring = true).performClick()
        compose.onNodeWithText("Read", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun settledNavigationThenOwnedPointerTapsOpenAndCloseALongChildRun() {
        val paragraphs =
            (0 until 24).map { n ->
                ThreadItem.MessageItem(Message("prose$n", "s", Role.Assistant, "Owned paragraph $n", ts, false, parentToolUseId = "a"))
            }
        mount(
            listOf(tool("a", "Agent"), launch(), tool("child", "Read", "a"), tool("child2", "Glob", "a")) +
                paragraphs + finish() + user("Later"),
            true,
        )
        compose.verifyAgentRunNavigation(
            agentId = "a",
            runId = "child",
            childIds = listOf("child", "child2") + paragraphs.map { it.message.id },
            ownedChild = hasText("Owned paragraph", substring = true) and hasAnyAncestor(hasTestTag("background-agent-child:a")),
            goLabel = "Go to agent ↓",
            expandLabel = "Show tool uses",
            collapseLabel = "Hide tool uses",
            evidence = { println(it) },
        )
    }

    @Test fun resetBeforeClosedRunCheckWaitsForTheSameOwnedFamily() {
        val paragraphs =
            (0 until 8).map { n ->
                ThreadItem.MessageItem(Message("prose$n", "s", Role.Assistant, "Owned paragraph $n", ts, false, parentToolUseId = "a"))
            }
        val settled =
            listOf(tool("a", "Agent"), launch(), tool("child", "Read", "a"), tool("child2", "Glob", "a")) +
                paragraphs + finish() + user("Later")
        mount(settled, true)
        // The repository can supply settled evidence immediately before a reset empties the screen.
        compose.runOnIdle { state = state.copy(items = emptyList(), hasMessages = false) }
        compose.onAllNodes(hasScrollToNodeAction()).assertCountEquals(0)
        println("reset unmerged_lists=${compose.onAllNodes(hasScrollToNodeAction(), useUnmergedTree = true).fetchSemanticsNodes().size}")
        var resetObserved = false
        compose.verifyAgentRunNavigation(
            agentId = "a",
            runId = "child",
            childIds = listOf("child", "child2") + paragraphs.map { it.message.id },
            ownedChild = hasText("Owned paragraph", substring = true) and hasAnyAncestor(hasTestTag("background-agent-child:a")),
            goLabel = "Go to agent ↓",
            expandLabel = "Show tool uses",
            collapseLabel = "Hide tool uses",
            evidence = { sample ->
                println(sample)
                if (sample.startsWith("event=agent_run_readiness") && "ready=false" in sample && !resetObserved) {
                    resetObserved = true
                    // Release catch-up only after the proof actually observes the absent list.
                    compose.runOnIdle { state = state.copy(items = settled, hasMessages = true) }
                }
            },
        )
        assertTrue("the proof must observe the reset before restoring the exact Agent", resetObserved)
    }

    @Test fun missingListCannotPassTheAgentNavigationProof() {
        mount(emptyList(), true)
        compose.runOnIdle { state = state.copy(hasMessages = false) }
        assertThrows(ComposeTimeoutException::class.java) { verifyMissingAgent() }
    }

    @Test fun anotherAgentsListCannotPassTheAgentNavigationProof() {
        mount(
            listOf(
                tool("b", "Agent"),
                launch().copy(taskId = "tb", toolCallId = "b"),
                tool("other-child", "Read", "b"),
                tool("other-child2", "Glob", "b"),
                user("Later"),
            ),
            true,
        )
        compose.onAllNodes(hasScrollToNodeAction()).assertCountEquals(1)
        assertThrows(ComposeTimeoutException::class.java) { verifyMissingAgent() }
    }

    private fun verifyMissingAgent() {
        compose.verifyAgentRunNavigation(
            agentId = "a",
            runId = "child",
            childIds = listOf("child", "child2"),
            ownedChild = hasText("Owned paragraph") and hasAnyAncestor(hasTestTag("background-agent-child:a")),
            goLabel = "Go to agent ↓",
            expandLabel = "Show tool uses",
            collapseLabel = "Hide tool uses",
        )
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
        // Each agent's own root never folds into its run (#1827 follow-up), so each needs two children for
        // its own run to form and collapse.
        mount(
            listOf(
                tool("a", "Agent"),
                launch(),
                tool("b", "Agent"),
                launch().copy(taskId = "tb", toolCallId = "b", description = "Second agent"),
                user("Newer"),
                tool("child", "Read", "a"),
                tool("child2", "Glob", "a"),
                tool("second-child", "Read", "b"),
                tool("second-child2", "Glob", "b"),
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
