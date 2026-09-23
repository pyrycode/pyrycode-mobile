package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** #843: a rejected pairing offers Re-pair beside the composer instead of a retry that cannot succeed. */
@RunWith(AndroidJUnit4::class)
class ThreadScreenRePairTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun a_rejected_pairing_shows_re_pair_and_no_retry_banner() {
        setScreen(showRePair = true)
        composeRule.onNodeWithText(RE_PAIR_LABEL).assertIsDisplayed()
        composeRule.onNodeWithText(OFFLINE_BANNER).assertDoesNotExist()
    }

    @Test
    fun tapping_re_pair_emits_the_tap() {
        var taps = 0
        setScreen(showRePair = true, onRePair = { taps++ })
        composeRule.onNodeWithText(RE_PAIR_LABEL).performClick()
        composeRule.runOnIdle { assertEquals(1, taps) }
    }

    @Test
    fun network_loss_keeps_the_retry_banner_and_offers_no_re_pair() {
        setScreen(showRePair = false)
        composeRule.onNodeWithText(OFFLINE_BANNER).assertIsDisplayed()
        composeRule.onNodeWithText(RE_PAIR_LABEL).assertDoesNotExist()
    }

    private fun setScreen(
        showRePair: Boolean,
        onRePair: () -> Unit = {},
    ) {
        composeRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = ThreadUiState(conversationId = "conversation", displayName = "Re-pair"),
                    onBack = {},
                    onSendMessage = {},
                    // The legacy state a rejected pairing folds into, so the banner would otherwise offer retry.
                    connectionState = ConnectionState.Offline,
                    onRetry = {},
                    showRePair = showRePair,
                    onRePair = onRePair,
                )
            }
        }
    }

    private companion object {
        const val RE_PAIR_LABEL = "Pairing error - Re-pair"
        const val OFFLINE_BANNER = "Offline — tap to retry"
    }
}
