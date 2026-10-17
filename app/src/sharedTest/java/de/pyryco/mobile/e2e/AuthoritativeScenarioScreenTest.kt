package de.pyryco.mobile.e2e

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
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import de.pyryco.mobile.ui.conversations.thread.ThreadScreen
import de.pyryco.mobile.ui.conversations.thread.ThreadUiState
import de.pyryco.mobile.ui.conversations.thread.projectAuthoritativeThread
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** #2174: the migrated replay-order and background-agent device assertions over a rendered daemon thread. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuthoritativeScenarioScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun mount(items: List<ThreadItemDto>): ThreadEvidence {
        val snapshot: AuthoritativeThreadSnapshot = scenarioSnapshot(items)
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
                )
            }
        }
        return authoritativeThreadEvidence("host", "conversation", Any(), snapshot)
    }

    @Test fun recoveredReplayRendersAndSettlesInDaemonOrder() {
        val evidence = mount(replayScenarioItems())
        compose.assertOrderedReplay(evidence, "alpha bravo charlie", "hello")
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
}
