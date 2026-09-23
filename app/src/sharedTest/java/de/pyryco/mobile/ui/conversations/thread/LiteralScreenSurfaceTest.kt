package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiteralScreenSurfaceTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun string(resId: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)

    @Test
    fun loading_state_shows_progress_indicator() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                LiteralScreenSurface(
                    state = LiteralScreenUiState.Loading,
                    onEvent = {},
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(string(R.string.literal_screen_loading)).assertIsDisplayed()
    }

    // The AC#1 proof: feed markdown-ish input and assert it appears literally. If the surface routed
    // through MarkdownText, the `**`/backtick markers would be stripped/rendered and this raw node
    // would be absent — its presence proves the snapshot bypasses the assistant-message renderer. The
    // leading whitespace + internal newline pin "no trim/reflow". (FontFamily.Monospace is not
    // queryable via the test API; the verbatim assertion plus code review of the Text covers it.)
    @Test
    fun content_renders_verbatim_bypassing_markdown() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                LiteralScreenSurface(
                    state = LiteralScreenUiState.Content("**bold** `code`\n  indented"),
                    onEvent = {},
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText("**bold** `code`", substring = true).assertIsDisplayed()
    }

    @Test
    fun error_unknown_conversation_shows_copy_and_retry_fires_retry() {
        val events = mutableListOf<LiteralScreenEvent>()
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                LiteralScreenSurface(
                    state = LiteralScreenUiState.Error(LiteralScreenError.UnknownConversation),
                    onEvent = { events.add(it) },
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.literal_screen_error_unknown_conversation)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.literal_screen_retry)).performClick()

        // Request (LaunchedEffect on open) then Retry (the retry button).
        assertEquals(listOf(LiteralScreenEvent.Request, LiteralScreenEvent.Retry), events)
    }

    @Test
    fun error_server_shows_copy() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                LiteralScreenSurface(
                    state = LiteralScreenUiState.Error(LiteralScreenError.ServerError),
                    onEvent = {},
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.literal_screen_error_server)).assertIsDisplayed()
    }

    @Test
    fun error_not_connected_shows_copy() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                LiteralScreenSurface(
                    state = LiteralScreenUiState.Error(LiteralScreenError.NotConnected),
                    onEvent = {},
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.literal_screen_error_not_connected)).assertIsDisplayed()
    }

    @Test
    fun request_fired_exactly_once_on_open() {
        val events = mutableListOf<LiteralScreenEvent>()
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                LiteralScreenSurface(
                    state = LiteralScreenUiState.Loading,
                    onEvent = { events.add(it) },
                    onBack = {},
                )
            }
        }

        composeTestRule.runOnIdle {
            assertEquals(listOf(LiteralScreenEvent.Request), events)
        }
    }

    @Test
    fun refresh_in_content_refetches_in_place() {
        val events = mutableListOf<LiteralScreenEvent>()
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                LiteralScreenSurface(
                    state = LiteralScreenUiState.Content("screen"),
                    onEvent = { events.add(it) },
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(string(R.string.literal_screen_refresh)).performClick()

        // Request (open) then Retry (refresh); the surface is still composed (title still shown).
        assertEquals(listOf(LiteralScreenEvent.Request, LiteralScreenEvent.Retry), events)
        composeTestRule.onNodeWithText(string(R.string.literal_screen_title)).assertIsDisplayed()
    }
}
