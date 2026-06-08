package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThinkingIndicatorTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val thinkingDescription: String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getString(R.string.cd_thread_thinking)

    private fun message(id: String): ThreadItem.MessageItem =
        ThreadItem.MessageItem(
            Message(
                id = id,
                sessionId = "s1",
                role = Role.Assistant,
                content = "message $id",
                timestamp = Instant.parse("2026-05-20T10:00:00Z"),
                isStreaming = false,
            ),
        )

    private fun populatedState(): ThreadUiState =
        ThreadUiState(
            conversationId = "ch_abc123",
            displayName = "Test channel",
            isPromoted = true,
            hasMessages = true,
            items = listOf(message("m0"), message("m1")),
        )

    private fun emptyState(): ThreadUiState =
        ThreadUiState(
            conversationId = "ch_abc123",
            displayName = "Test channel",
            isPromoted = true,
            hasMessages = false,
            items = emptyList(),
        )

    @Test
    fun indicator_shown_when_thinking_with_messages() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = populatedState(),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    isThinking = true,
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(thinkingDescription).assertIsDisplayed()
    }

    @Test
    fun indicator_shown_when_thinking_empty_thread() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = emptyState(),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    isThinking = true,
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(thinkingDescription).assertIsDisplayed()
    }

    @Test
    fun indicator_hidden_when_not_thinking() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = populatedState(),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    isThinking = false,
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(thinkingDescription).assertDoesNotExist()
    }

    @Test
    fun indicator_tracks_the_hoisted_flag_with_no_local_state() {
        var thinking by mutableStateOf(false)
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = populatedState(),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    isThinking = thinking,
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(thinkingDescription).assertDoesNotExist()

        thinking = true
        composeTestRule.onNodeWithContentDescription(thinkingDescription).assertIsDisplayed()

        thinking = false
        composeTestRule.onNodeWithContentDescription(thinkingDescription).assertDoesNotExist()
    }
}
