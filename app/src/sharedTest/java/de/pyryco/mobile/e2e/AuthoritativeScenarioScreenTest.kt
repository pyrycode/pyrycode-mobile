package de.pyryco.mobile.e2e

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.repository.AuthoritativeThreadOutcome
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import de.pyryco.mobile.data.repository.AuthoritativeThreadStore
import de.pyryco.mobile.ui.conversations.thread.ThreadScreen
import de.pyryco.mobile.ui.conversations.thread.ThreadUiState
import de.pyryco.mobile.ui.conversations.thread.projectAuthoritativeThread
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** #2174: the migrated replay-order and background-agent device assertions over a rendered daemon thread. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuthoritativeScenarioScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun mount(
        items: List<ThreadItemDto>,
        collapseToolUses: Boolean = false,
        snapshot: AuthoritativeThreadSnapshot = scenarioSnapshot(items),
        onRendered: () -> Unit = {},
    ): ThreadEvidence {
        val evidence = authoritativeThreadEvidence("host", "conversation", Any(), snapshot)
        compose.setContent {
            SideEffect { onRendered() }
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
                    collapseToolUses = collapseToolUses,
                )
            }
        }
        return evidence
    }

    @Test fun recoveredReplayRendersAndSettlesInDaemonOrder() {
        val evidence = mount(replayScenarioItems())
        compose.assertOrderedReplay(evidence, "alpha bravo charlie", "hello")
    }

    @Test fun replayRefreshAcceptsSameDocumentAdvanceDuringRender() {
        val store = AuthoritativeThreadStore()
        val items = replayScenarioItems()
        assertEquals(AuthoritativeThreadOutcome.Applied, store.establishComplete("host", "conversation", "epoch", 4, items, 0))
        val beforeRender = store.current("host", "conversation")
        val held =
            mount(items, snapshot = beforeRender, onRendered = {
                // Model a later producer/catch-up publication while the reply is being composed.
                store.applyBatch("host", "conversation", "epoch", 5, emptyList(), 0)
            })
        var refreshed = false
        compose.assertOrderedReplay(held, "alpha bravo charlie", "hello") { observed ->
            val current = store.current("host", "conversation")
            assertNotSame(beforeRender, current)
            assertEquals(5L, current.completedVersion)
            assertTrue(observed.isCurrentReplay("host", "conversation", held.owner.connection, current))
            refreshed = true
            authoritativeThreadEvidence("host", "conversation", held.owner.connection, current)
        }
        assertTrue("final proof must read current facts after synchronization", refreshed)
    }

    @Test fun replayRefreshRejectsNewDuplicateEchoDespiteCorrectOldFactsAndRender() {
        val store = AuthoritativeThreadStore()
        val items = replayScenarioItems()
        store.establishComplete("host", "conversation", "epoch", 4, items, 0)
        val beforeRender = store.current("host", "conversation")
        val held =
            mount(items, snapshot = beforeRender, onRendered = {
                store.applyBatch("host", "conversation", "epoch", 5, listOf(scenarioUser(5, "hello")), 0)
            })
        val failure =
            assertThrows(AssertionError::class.java) {
                compose.assertOrderedReplay(held, "alpha bravo charlie", "hello") {
                    authoritativeThreadEvidence("host", "conversation", held.owner.connection, store.current("host", "conversation"))
                }
            }
        assertTrue("the fresh duplicate echo must cause the rejection", failure.message.orEmpty().contains("users=2"))
    }

    @Test fun reorderedRecoveredReplayFailsTheMigratedAssertion() {
        val (hello, _, _, charlie) = replayScenarioItems()
        val evidence = mount(listOf(hello, scenarioItem(2, text = "bravo "), scenarioItem(3, text = "alpha "), charlie))
        assertThrows(AssertionError::class.java) { compose.assertOrderedReplay(evidence, "alpha bravo charlie", "hello") }
    }

    @Test fun settledAgentHeaderAndUnmatchedProseFollowRenderedFamilyIdentities() {
        val evidence = mount(finishedAgentScenarioItems())
        val family = requireNotNull(evidence.agentFamily(evidence.owner))
        val child = hasTestTag("background-agent-child:${family.agentId}")
        val list = compose.onNode(hasScrollToNodeAction())
        list.performScrollToNode(hasText("unmatched1827"))
        compose.onNodeWithText("unmatched1827").assertIsDisplayed()
        compose.onAllNodes(hasText("unmatched1827") and hasAnyAncestor(child), useUnmergedTree = true).assertCountEquals(0)
        list.performScrollToNode(hasTestTag("background-agent:${family.agentId}"))
        compose.onNode(hasTestTag("background-agent:${family.agentId}")).assertIsDisplayed()
    }

    @Test fun settledAgentRunNavigatesOpensAndClosesOverDaemonBuiltThread() {
        val evidence = mount(finishedAgentScenarioItems(), collapseToolUses = true)
        val family = requireNotNull(evidence.agentFamily(evidence.owner))
        compose.verifyAgentRunNavigation(
            agentId = family.agentId,
            runId = requireNotNull(family.runId),
            childIds = family.childIds,
            ownedChild = hasText("child1827-after") and hasAnyAncestor(hasTestTag("background-agent-child:${family.agentId}")),
            goLabel = "Go to agent ↓",
            expandLabel = "Show tool uses",
            collapseLabel = "Hide tool uses",
            evidence = { println(it) },
        )
    }
}
