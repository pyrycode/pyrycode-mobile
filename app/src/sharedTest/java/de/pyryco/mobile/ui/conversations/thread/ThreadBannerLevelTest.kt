package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.repository.BannerLevel
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #1359: an info-level banner stays in the thread data but draws nothing, as desktop's `TimelineRow` does;
 * notice and warning banners draw as before.
 *
 * Fixtures are hand-written literals, never captured live payloads.
 */
@RunWith(AndroidJUnit4::class)
class ThreadBannerLevelTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun an_info_banner_draws_nothing_while_notice_and_warning_draw() {
        val state =
            ThreadUiState(
                conversationId = "conversation",
                displayName = "Banner levels",
                isPromoted = true,
                hasMessages = true,
                agent = ConversationAgent.Claude,
                items =
                    listOf(
                        ThreadItem.Banner(BannerLevel.Info, "Info text", false, Instant.parse("2026-10-01T10:00:00Z")),
                        ThreadItem.Banner(BannerLevel.Notice, "Notice text", false, Instant.parse("2026-10-01T10:01:00Z")),
                        ThreadItem.Banner(BannerLevel.Warning, "Warning text", false, Instant.parse("2026-10-01T10:02:00Z")),
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
                )
            }
        }

        composeRule.onNodeWithText("Info text", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Notice text", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Warning text", substring = true).assertIsDisplayed()
    }
}
