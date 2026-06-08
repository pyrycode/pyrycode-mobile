package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Screen-level behaviour of the #396 stall promotion: while [ThreadScreen]'s `isStalled` flag is set,
 * the prominent screen-snapshot affordance is shown, it triggers the already-shipped
 * `onShowLiteralScreen` navigation, and when not stalled the un-promoted manual action (the overflow
 * item) is unchanged.
 */
@RunWith(AndroidJUnit4::class)
class StallPromotionBannerTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun string(resId: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)

    private val stallDescription: String = string(R.string.cd_thread_stall_promotion)

    private fun baseState(): ThreadUiState =
        ThreadUiState(
            conversationId = "c1",
            displayName = "Test channel",
            isPromoted = true,
            hasMessages = false,
        )

    @Test
    fun banner_shown_when_stalled() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = baseState(),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    isStalled = true,
                )
            }
        }

        // AC #1 — the affordance is surfaced prominently while stalled.
        composeTestRule.onNodeWithContentDescription(stallDescription).assertIsDisplayed()
    }

    @Test
    fun banner_absent_when_not_stalled() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = baseState(),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    isStalled = false,
                )
            }
        }

        // AC #2 — no promotion when no stall is active.
        composeTestRule.onNodeWithContentDescription(stallDescription).assertDoesNotExist()
    }

    @Test
    fun overflow_snapshot_action_still_available_when_not_stalled() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = baseState(),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    isStalled = false,
                )
            }
        }

        // AC #2 — the un-promoted manual action (#372 lineage) is unchanged: reachable via overflow.
        composeTestRule.onNodeWithContentDescription(string(R.string.cd_more_actions)).performClick()
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_show_literal_screen)).assertIsDisplayed()
    }

    @Test
    fun banner_tracks_the_hoisted_flag_with_no_local_state() {
        var stalled by mutableStateOf(false)
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = baseState(),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    isStalled = stalled,
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(stallDescription).assertDoesNotExist()

        stalled = true
        composeTestRule.onNodeWithContentDescription(stallDescription).assertIsDisplayed()

        // AC #3 — the promotion clears when the stall resolves.
        stalled = false
        composeTestRule.onNodeWithContentDescription(stallDescription).assertDoesNotExist()
    }

    @Test
    fun tapping_banner_triggers_show_literal_screen() {
        var snapshotCount = 0
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = baseState(),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    isStalled = true,
                    onShowLiteralScreen = { snapshotCount++ },
                )
            }
        }

        // AC #4 — the prominent affordance triggers the same already-shipped snapshot action.
        composeTestRule.onNodeWithContentDescription(stallDescription).performClick()
        assertEquals(1, snapshotCount)
    }
}
