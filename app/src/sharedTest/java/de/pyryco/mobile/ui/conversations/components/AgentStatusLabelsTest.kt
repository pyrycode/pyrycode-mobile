package de.pyryco.mobile.ui.conversations.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.repository.ApiRetryStatus
import de.pyryco.mobile.data.repository.ThinkingProgress
import de.pyryco.mobile.ui.conversations.thread.ThreadScreen
import de.pyryco.mobile.ui.conversations.thread.ThreadUiState
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #1114: the live status band's screen-reader labels name the conversation's agent. The expected texts are
 * literals, so a Claude label that drifts from what shipped fails here rather than following the resource.
 */
@RunWith(AndroidJUnit4::class)
class AgentStatusLabelsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun tool(elapsedSeconds: Int? = null): ToolCall =
        ToolCall(
            toolName = "Bash",
            input = "",
            output = "",
            status = ToolCallStatus.Running,
            elapsedSeconds = elapsedSeconds,
        )

    private fun show(content: @Composable () -> Unit) {
        composeTestRule.setContent { PyrycodeMobileTheme { content() } }
    }

    private fun assertDescribed(description: String) {
        composeTestRule.onNodeWithContentDescription(description).assertIsDisplayed()
    }

    @Test
    fun codex_thinkingReading_namesCodex() {
        show {
            ThinkingIndicator(
                isThinking = true,
                progress = ThinkingProgress(estimatedTokens = 184, estimatedTokensDelta = 64),
                agent = ConversationAgent.Codex,
            )
        }
        assertDescribed("Codex is thinking, about 184 tokens into its current reasoning step")
    }

    @Test
    fun codex_runningTool_namesCodex() {
        show { ThinkingIndicator(isThinking = true, runningTool = tool(), agent = ConversationAgent.Codex) }
        assertDescribed("Codex is running Bash")
    }

    @Test
    fun codex_runningToolWithElapsed_namesCodex() {
        show {
            ThinkingIndicator(isThinking = true, runningTool = tool(elapsedSeconds = 65), agent = ConversationAgent.Codex)
        }
        assertDescribed("Codex is running Bash, 1m 05s elapsed")
    }

    @Test
    fun codex_working_namesCodex() {
        show { ThinkingIndicator(isThinking = false, isWorking = true, agent = ConversationAgent.Codex) }
        assertDescribed("Codex is working")
    }

    @Test
    fun codex_apiRetryCounter_namesCodex() {
        show { ApiRetryIndicator(status = ApiRetryStatus.Attempt(current = 3, total = 10), agent = ConversationAgent.Codex) }
        assertDescribed("Codex is retrying, attempt 3 of 10")
    }

    @Test
    fun codex_apiRetryUnknown_namesCodex() {
        show { ApiRetryIndicator(status = ApiRetryStatus.AttemptUnknown, agent = ConversationAgent.Codex) }
        assertDescribed("Codex is retrying, attempt count unknown")
    }

    @Test
    fun codex_compacting_namesCodex() {
        show { CompactingIndicator(isCompacting = true, agent = ConversationAgent.Codex) }
        assertDescribed("Codex is compacting the conversation")
    }

    @Test
    fun claude_labels_readAsShipped() {
        show {
            Column {
                ThinkingIndicator(
                    isThinking = true,
                    progress = ThinkingProgress(estimatedTokens = 184, estimatedTokensDelta = 64),
                )
                ThinkingIndicator(isThinking = true, runningTool = tool())
                ThinkingIndicator(isThinking = true, runningTool = tool(elapsedSeconds = 65))
                ApiRetryIndicator(status = ApiRetryStatus.Attempt(current = 3, total = 10))
                ApiRetryIndicator(status = ApiRetryStatus.AttemptUnknown)
                CompactingIndicator(isCompacting = true)
            }
        }
        assertDescribed("Claude is thinking, about 184 tokens into its current reasoning step")
        assertDescribed("Claude is running Bash")
        assertDescribed("Claude is running Bash, 1m 05s elapsed")
        assertDescribed("Claude is retrying, attempt 3 of 10")
        assertDescribed("Claude is retrying, attempt count unknown")
        assertDescribed("Claude is compacting the conversation")
    }

    @Test
    fun threadScreen_passesTheConversationsAgent() {
        show {
            ThreadScreen(
                state =
                    ThreadUiState(
                        conversationId = "ch_codex",
                        displayName = "Codex channel",
                        isPromoted = true,
                        agent = ConversationAgent.Codex,
                    ),
                onBack = {},
                onSendMessage = {},
                connectionState = ConnectionState.Connected,
                onRetry = {},
                isCompacting = true,
            )
        }
        assertDescribed("Codex is compacting the conversation")
    }
}
