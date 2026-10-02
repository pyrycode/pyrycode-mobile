package de.pyryco.mobile.ui.conversations.components

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.thread.ThreadScreen
import de.pyryco.mobile.ui.conversations.thread.ThreadUiState
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The switch-back button on a refusal row (#1360) in Figma's three states, 646-2833 (offered), 646-4694
 * (pending) and 646-4700 (failed), and, through [ThreadScreen], only on the row that armed the offer.
 *
 * Fixtures are hand-written literals, never captured live payloads.
 */
@RunWith(AndroidJUnit4::class)
class ModelRefusalSwitchBackTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val failedMessage = "Could not change the model — try again."

    private fun setRow(
        offer: SwitchBackOffer?,
        onSwitchBack: () -> Unit = {},
    ) {
        composeRule.setContent {
            PyrycodeMobileTheme {
                ModelRefusalRow(item = ROW, agent = ConversationAgent.Claude, switchBack = offer, onSwitchBack = onSwitchBack)
            }
        }
    }

    @Test
    fun offered_showsAnEnabledButtonNamingTheStrippedOriginalModel_andATapCallsBackOnce() {
        var taps = 0
        setRow(OFFER.copy(originalModel = "claude-\u001b[31mopus\u001b[0m-5-5\n"), onSwitchBack = { taps++ })

        composeRule
            .onNodeWithText("Switch back to claude-opus-5-5")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        assertEquals(1, taps)
        composeRule.onNodeWithText(failedMessage).assertDoesNotExist()
        // The toggle is still the title block's own action.
        composeRule.onNodeWithText("Show details").assertIsDisplayed()
    }

    @Test
    fun pending_disablesTheButton() {
        var taps = 0
        setRow(OFFER.copy(pending = true), onSwitchBack = { taps++ })

        composeRule
            .onNodeWithText("Switch back to claude-opus-5-5")
            .assertIsDisplayed()
            .assertIsNotEnabled()
            .performClick()

        assertEquals(0, taps)
        composeRule.onNodeWithText(failedMessage).assertDoesNotExist()
    }

    @Test
    fun failed_keepsTheButtonEnabled_withTheRetryMessageBelow() {
        setRow(OFFER.copy(failed = true))

        composeRule.onNodeWithText("Switch back to claude-opus-5-5").assertIsEnabled()
        composeRule.onNodeWithText(failedMessage).assertIsDisplayed()
    }

    @Test
    fun withoutAnOffer_thereIsNoButton() {
        setRow(offer = null)

        composeRule.onNodeWithText("Switch back", substring = true).assertDoesNotExist()
    }

    @Test
    fun inTheThread_onlyTheArmingRowShowsTheButton() {
        var taps = 0
        val later = ROW.copy(originalModel = "claude-haiku-4-5", occurredAt = Instant.parse("2026-09-23T12:00:05Z"))
        val noFallback = ROW.copy(fallbackModel = null)
        composeRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state =
                        ThreadUiState(
                            conversationId = "conversation",
                            displayName = "Refusals",
                            isPromoted = true,
                            hasMessages = true,
                            items = listOf(ROW, noFallback, later),
                        ),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    switchBackOffer = OFFER,
                    onSwitchBack = { taps++ },
                )
            }
        }

        composeRule.onAllNodesWithText("Switch back", substring = true).assertCountEquals(1)
        composeRule.onNode(hasText("Switch back to claude-opus-5-5") and hasClickAction()).performClick()
        assertEquals(1, taps)
        // The button names the arming row's model, not the later row's.
        composeRule.onNode(hasClickLabel("Show Claude's explanation") and hasText("claude-haiku-4-5", substring = true)).assertExists()
    }

    private fun hasClickLabel(label: String) =
        SemanticsMatcher("click label is \"$label\"") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label }

    private companion object {
        val AT: Instant = Instant.parse("2026-09-23T12:00:00Z")
        val ROW = ThreadItem.ModelRefusal("claude-opus-5-5", "claude-sonnet-5", "Retried on Sonnet.", false, AT)
        val OFFER = SwitchBackOffer(AT, "claude-opus-5-5", pending = false, failed = false)
    }
}
