package de.pyryco.mobile.e2e

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.repository.AuthoritativeThreadStore
import de.pyryco.mobile.ui.conversations.thread.ThreadScreen
import de.pyryco.mobile.ui.conversations.thread.ThreadUiState
import de.pyryco.mobile.ui.conversations.thread.authoritativeAgent
import de.pyryco.mobile.ui.conversations.thread.authoritativeItem
import de.pyryco.mobile.ui.conversations.thread.projectAuthoritativeThread
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** The live agent-reply scenario's rendered steps, keyed only by identities taken from authoritative evidence. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentReplyEvidenceScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun heldRunRebindsAfterResetBetweenReadinessAndReveal() = resetDuringReveal(1)

    @Test fun heldRunRebindsAfterTwoConsecutiveResets() = resetDuringReveal(2)

    @Test fun heldRunRejectsMissingProjectedKey() = rejectUnavailableOwnedRun(keepOwnedTool = false)

    @Test fun heldRunRejectsUnrelatedClickableRunWithOwnedKeyPresent() = rejectUnavailableOwnedRun(keepOwnedTool = true)

    private fun rejectUnavailableOwnedRun(keepOwnedTool: Boolean) {
        val original = agentReplySnapshot()
        val admitted =
            original.copy(
                items =
                    original.items +
                        listOf(
                            authoritativeAgent(6, task = "other-task", toolUse = "other-agent"),
                            authoritativeItem(7, parent = 6, turn = "other-child"),
                            authoritativeItem(8, "tool_call", parent = 6, content = """{"name":"Bash"}""", turn = "other-child"),
                            authoritativeItem(9, "tool_call", parent = 6, content = """{"name":"Bash"}""", turn = "other-child"),
                        ),
            )
        val evidence = authoritativeThreadEvidence("host", "conversation", Any(), admitted)
        val owned = requireNotNull(evidence.heldAgentReplyEvidence(evidence.owner, "raw-task", "raw-agent", "child", "main"))
        val unrelated = requireNotNull(evidence.heldAgentReplyEvidence(evidence.owner, "other-task", "other-agent", "other-child", "main"))
        assertNotEquals(owned.runId, unrelated.runId)
        // Keep the actual first tool's key, but remove the rest of its run from the screen.
        // Evidence readiness must never substitute for the real screen's key and exact header.
        val rendered = admitted.copy(items = admitted.items.filterNot { it.id == 2L || it.id == 4L || (!keepOwnedTool && it.id == 3L) })
        compose.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                ThreadScreen(
                    ThreadUiState(
                        "conversation",
                        "Channel",
                        authoritativeThread = projectAuthoritativeThread("host", "conversation", rendered),
                    ),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    collapseToolUses = true,
                )
            }
        }
        val list = compose.onNode(hasScrollToIndexAction())
        val index = list.fetchSemanticsNode().config[SemanticsProperties.IndexForKey]
        assertEquals("the negative must exercise its intended key boundary", keepOwnedTool, index("msg:${owned.runId}") >= 0)
        assertTrue("the unrelated run must be projected", index("msg:${unrelated.runId}") >= 0)
        val header = hasText("Using tools:", substring = true) and hasClickAction()
        val ownedHeader = header and hasAnyAncestor(hasTestTag("tool-run:${owned.runId}"))
        val unrelatedHeader = header and hasAnyAncestor(hasTestTag("tool-run:${unrelated.runId}"))
        val closedUnrelated =
            unrelatedHeader and
                SemanticsMatcher("Show tool uses") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Show tool uses" }
        compose.onAllNodes(ownedHeader).assertCountEquals(0)
        list.performScrollToNode(unrelatedHeader)
        compose.onNode(closedUnrelated).assertIsDisplayed()
        val stages = mutableListOf<String>()
        var resolutions = 0
        val started = System.nanoTime()
        assertThrows(ComposeTimeoutException::class.java) {
            compose.revealHeldAgentRun(
                timeoutMillis = 1_000,
                resolve = { remaining ->
                    assertTrue("resolution must share the bounded original deadline", remaining in 1..1_000)
                    resolutions++
                    owned
                },
                evidence = stages::add,
            )
        }
        assertTrue("the helper must actually resolve admitted owned evidence", resolutions > 0)
        assertTrue("rejection must stay bounded", (System.nanoTime() - started) / 1_000_000 < 5_000)
        assertEquals(
            "only a projected owned key may reach scroll readiness",
            keepOwnedTool,
            stages.any {
                it ==
                    "event=held_agent_run stage=ready"
            },
        )
        assertTrue(
            "an unrelated header must never complete the owned reveal",
            stages.none {
                it ==
                    "event=held_agent_run stage=revealed owned=1"
            },
        )
        compose.onAllNodes(ownedHeader).assertCountEquals(0)
        compose.onAllNodes(hasTestTag("assistant-reply:${unrelated.heldReply.message.id}"), useUnmergedTree = true).assertCountEquals(0)
        list.performScrollToNode(unrelatedHeader)
        compose.onNode(closedUnrelated).assertIsDisplayed().performClick()
        val unrelatedReply = hasTestTag("assistant-reply:${unrelated.heldReply.message.id}")
        list.performScrollToNode(unrelatedReply)
        compose
            .onNode(
                unrelatedReply and hasAnyAncestor(hasTestTag("background-agent-child:${unrelated.agent.block}")),
                useUnmergedTree = true,
            ).assertIsDisplayed()
        compose.onAllNodes(unrelatedReply, useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodes(hasTestTag("assistant-reply:${owned.heldReply.message.id}"), useUnmergedTree = true).assertCountEquals(0)
    }

    private fun resetDuringReveal(resets: Int) {
        var snapshot by mutableStateOf(agentReplySnapshot())
        val store = AuthoritativeThreadStore()
        store.establishComplete(
            "host",
            "conversation",
            requireNotNull(snapshot.epoch),
            snapshot.completedVersion,
            snapshot.items,
            snapshot.generation,
        )
        val connection = Any()

        fun reading() = authoritativeThreadEvidence("host", "conversation", connection, snapshot)

        fun resolve() = reading().let { it.heldAgentReplyEvidence(it.owner, "raw-task", "raw-agent", "child", "main") }
        val original = requireNotNull(resolve())
        compose.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                ThreadScreen(
                    ThreadUiState(
                        "conversation",
                        "Channel",
                        authoritativeThread = projectAuthoritativeThread("host", "conversation", snapshot),
                    ),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    collapseToolUses = true,
                )
            }
        }
        var resetCount = 0
        val revealed =
            compose.revealHeldAgentRun(5_000, { resolve() }) { stage ->
                if (resetCount < resets && stage == "event=held_agent_run stage=ready") {
                    val beforeReset = requireNotNull(resolve())
                    val index = compose.onNode(hasScrollToIndexAction()).fetchSemanticsNode().config[SemanticsProperties.IndexForKey]
                    assertTrue("the original owned key was ready before reset", index("msg:${beforeReset.runId}") >= 0)
                    compose.runOnIdle {
                        val epoch = requireNotNull(snapshot.epoch)
                        store.reset("host", "conversation", epoch)
                        store.establishComplete(
                            "host",
                            "conversation",
                            epoch,
                            snapshot.completedVersion,
                            snapshot.items,
                            store.current("host", "conversation").generation,
                        )
                        snapshot = store.current("host", "conversation")
                    }
                    resetCount++
                    val replacement = requireNotNull(resolve())
                    assertNotEquals(beforeReset.runId, replacement.runId)
                    val currentIndex = compose.onNode(hasScrollToIndexAction()).fetchSemanticsNode().config[SemanticsProperties.IndexForKey]
                    assertTrue("the replaced key must leave the list", currentIndex("msg:${beforeReset.runId}") < 0)
                    assertTrue(
                        "the same owned run remains projected under its current identity",
                        currentIndex("msg:${replacement.runId}") >= 0,
                    )
                }
            }
        assertEquals(resets, resetCount)
        assertNotEquals(original.runId, revealed.runId)
        val current = requireNotNull(resolve())
        val run =
            hasText("Using tools:", substring = true) and hasClickAction() and hasAnyAncestor(hasTestTag("tool-run:${current.runId}"))
        compose.onNode(run).assertIsDisplayed().performClick()
        val reply = hasTestTag("assistant-reply:${current.heldReply.message.id}")
        val child = hasTestTag("background-agent-child:${current.agent.block}")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(reply)
        compose.onAllNodes(reply and hasAnyAncestor(child), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test fun authoritativeEvidenceIdentitiesKeyTheCollapseAndReopenChecks() {
        val snapshot = agentReplySnapshot()
        val held = authoritativeThreadEvidence("host", "conversation", Any(), snapshot)
        val evidence = requireNotNull(held.agentReplyEvidence(held.owner, "raw-task", "raw-agent", "child", "main"))
        compose.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                ThreadScreen(
                    state =
                        ThreadUiState(
                            "conversation",
                            "Channel",
                            authoritativeThread = projectAuthoritativeThread("host", "conversation", snapshot),
                        ),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    collapseToolUses = true,
                )
            }
        }
        val reply = hasTestTag("assistant-reply:${evidence.heldReply.message.id}")
        val child = hasTestTag("background-agent-child:${evidence.agent.block}")
        val run =
            hasText("Using tools:", substring = true) and hasClickAction() and hasAnyAncestor(hasTestTag("tool-run:${evidence.runId}"))

        fun labelled(label: String) = run and SemanticsMatcher(label) { it.config.getOrNull(SemanticsActions.OnClick)?.label == label }
        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToNode(run)
        compose.onNode(labelled("Show tool uses")).assertExists()
        compose.onAllNodes(reply, useUnmergedTree = true).assertCountEquals(0)
        compose.onNode(labelled("Show tool uses")).performClick()
        list.performScrollToNode(reply)
        compose.onNode(reply and hasAnyAncestor(child), useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodes(reply, useUnmergedTree = true).assertCountEquals(1)
        list.performScrollToNode(run)
        compose.onNode(labelled("Hide tool uses")).performClick()
        compose.onAllNodes(reply, useUnmergedTree = true).assertCountEquals(0)
        val mainBody = hasTestTag("assistant-reply:${evidence.mainReply.message.id}")
        list.performScrollToNode(mainBody)
        compose.onNode(mainBody, useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodes(mainBody and hasAnyAncestor(child), useUnmergedTree = true).assertCountEquals(0)
    }
}
