package de.pyryco.mobile.e2e

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
import de.pyryco.mobile.ui.conversations.thread.projectAuthoritativeThread
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
