package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.repository.BannerLevel
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.components.TurnRecoveryNotice
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #1113: the notice, refusal and turn-outcome rows name the conversation's agent, wired from
 * [ThreadUiState.agent] through [ThreadScreen]. The Claude cases assert today's literal copy, so a Claude
 * conversation provably reads as it did before. Since #1357 the turn-outcome row is recovery advice.
 *
 * Fixtures are hand-written literals, never captured live payloads.
 */
@RunWith(AndroidJUnit4::class)
class ThreadAgentAttributionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val occurredAt = Instant.parse("2026-09-25T12:00:00Z")

    private fun setScreen(
        agent: ConversationAgent,
        turnOutcome: TurnRecoveryNotice? = TurnRecoveryNotice.BillingError,
    ) {
        val state =
            ThreadUiState(
                conversationId = "conversation",
                displayName = "Agent attribution",
                isPromoted = true,
                // Without it the empty-thread state draws in place of the rows.
                hasMessages = true,
                agent = agent,
                items =
                    listOf(
                        ThreadItem.Banner(BannerLevel.Warning, "Blocked by hook", truncated = false, occurredAt = occurredAt),
                        ThreadItem.ModelRefusal("gpt-5", "gpt-5-mini", "Retried on another model.", false, occurredAt),
                    ),
            )
        composeRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = state,
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    turnOutcome = turnOutcome,
                )
            }
        }
    }

    private fun hasClickLabel(label: String) =
        SemanticsMatcher("click label is \"$label\"") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label }

    @Test
    fun a_codex_conversation_credits_codex_in_every_row() {
        setScreen(ConversationAgent.Codex)

        composeRule.onNodeWithText("Warning · Codex: Blocked by hook").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Warning from Codex").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Codex reported a billing error. Check Codex billing on this server.").assertIsDisplayed()
        composeRule.onNodeWithText("Show details").assertIsDisplayed()
        composeRule.onNode(hasClickLabel("Show Codex's explanation")).performClick()
        composeRule.onNodeWithText("Codex: Retried on another model.").assertIsDisplayed()
        composeRule.onNodeWithText("Hide details").assertIsDisplayed()
        composeRule.onNode(hasClickLabel("Hide Codex's explanation")).assertExists()
        composeRule.onNodeWithText("Claude", substring = true).assertDoesNotExist()
    }

    @Test
    fun a_codex_sign_in_failure_names_codex() {
        setScreen(ConversationAgent.Codex, TurnRecoveryNotice.AuthenticationFailed)

        composeRule
            .onNodeWithContentDescription("Codex reported an authentication failure. Check Codex sign-in on this server.")
            .assertIsDisplayed()
    }

    @Test
    fun a_claude_conversation_reads_exactly_as_today() {
        setScreen(ConversationAgent.Claude)

        composeRule.onNodeWithText("Warning · Claude: Blocked by hook").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Warning from Claude").assertDoesNotExist()
        composeRule
            .onNodeWithContentDescription(
                "Claude reported a billing error. Check Claude billing on this server.",
            ).assertIsDisplayed()
        composeRule.onNode(hasClickLabel("Show Claude's explanation")).performClick()
        composeRule.onNodeWithText("Claude: Retried on another model.").assertIsDisplayed()
        composeRule.onNode(hasClickLabel("Hide Claude's explanation")).assertExists()
    }

    @Test
    fun a_claude_sign_in_failure_names_claude() {
        setScreen(ConversationAgent.Claude, TurnRecoveryNotice.AuthenticationFailed)

        composeRule
            .onNodeWithContentDescription("Claude reported an authentication failure. Check Claude sign-in on this server.")
            .assertIsDisplayed()
    }
}
