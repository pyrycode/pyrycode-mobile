package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.repository.BoundaryReason
import de.pyryco.mobile.data.repository.ResetStatus
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.components.SESSION_BOUNDARY_TEST_TAG
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** #1112: the thread's wrap-up line names the conversation's agent; #1578: the boundary names no agent. */
@RunWith(AndroidJUnit4::class)
class ThreadAgentNameTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val boundary =
        ThreadItem.SessionBoundary(
            previousSessionId = "s0",
            newSessionId = "s1",
            reason = BoundaryReason.Clear,
            occurredAt = Instant.parse("2026-09-25T10:00:00Z"),
            workspaceCwd = null,
        )

    private fun setScreen(agent: ConversationAgent) {
        composeRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state =
                        ThreadUiState(
                            conversationId = "c1",
                            displayName = "Agent",
                            isPromoted = true,
                            hasMessages = true, // the list, not the empty state, draws the rows
                            items = listOf(boundary),
                            agent = agent,
                        ),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    resetting = ResetStatus(ResetStatus.Phase.WrappingUp, ResetStatus.Handoff.Pending),
                )
            }
        }
    }

    @Test
    fun aCodexConversation_namesCodex_inTheWrapUp_andTheBoundaryNamesNoAgent() {
        setScreen(ConversationAgent.Codex)

        composeRule.onNodeWithContentDescription("Codex is writing a handoff note for the next session").assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.thread_resetting_wrapping_up))
            .assertDoesNotExist()
        composeRule.onNodeWithTag(SESSION_BOUNDARY_TEST_TAG).assertIsDisplayed()
        composeRule.assertNoSessionBoundaryExplanation()
    }

    @Test
    fun aClaudeConversation_readsAsBefore() {
        setScreen(ConversationAgent.Claude)

        composeRule.onNodeWithContentDescription("Claude is writing a handoff note for the next session").assertIsDisplayed()
        composeRule.onNodeWithTag(SESSION_BOUNDARY_TEST_TAG).assertIsDisplayed()
        composeRule.assertNoSessionBoundaryExplanation()
        composeRule.onNode(hasText("Codex", substring = true)).assertDoesNotExist()
    }
}
