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
import de.pyryco.mobile.data.repository.ThinkingProgress
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

    // ---- #803: the thinking arm carries claude's live token reading ------------------------------

    /**
     * AC #1: a reading replaces the plain label in place — the arm stays one merged node and the
     * counter-less description is gone, so the two presentations can never be on screen together.
     */
    @Test
    fun readingIsShown_replacingThePlainLabel() {
        setThreadScreen(ThinkingProgress(estimatedTokens = 184, estimatedTokensDelta = 64))

        composeTestRule.onNodeWithContentDescription(progressDescription(184)).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(thinkingDescription).assertDoesNotExist()
    }

    /**
     * AC #2: a *falling* reading is a real reading — `estimated_tokens` restarts near zero at every
     * inference-request boundary, repeatedly inside one turn — so the label follows it down rather than
     * holding a running maximum. The negative control is the stale value: 184 must be gone.
     */
    @Test
    fun aFallingReadingUpdatesTheLabel() {
        var progress by mutableStateOf<ThinkingProgress?>(ThinkingProgress(184, 64))
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = populatedState(),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    isThinking = true,
                    thinkingProgress = progress,
                )
            }
        }
        composeTestRule.onNodeWithContentDescription(progressDescription(184)).assertIsDisplayed()

        progress = ThinkingProgress(estimatedTokens = 4, estimatedTokensDelta = 4)

        composeTestRule.onNodeWithContentDescription(progressDescription(4)).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(progressDescription(184)).assertDoesNotExist()
    }

    /**
     * AC #1, second half: with no reading the arm renders exactly as it does today. `null` is "no
     * reading", never "claude is not thinking" — it must degrade to the plain label and never to a
     * stalled or failed presentation.
     */
    @Test
    fun withoutAReading_theArmIsUnchanged() {
        setThreadScreen(progress = null)

        composeTestRule.onNodeWithContentDescription(thinkingDescription).assertIsDisplayed()
    }

    /**
     * The display sanity gate declines a reading it cannot honestly render and falls back to the plain
     * label — never a clamp, and never a rewritten server value. #801 carries a negative reading verbatim
     * through the decode boundary by design, so this is the layer that refuses to put one on screen.
     */
    @Test
    fun aNegativeReadingDeclinesToThePlainLabel() {
        setThreadScreen(ThinkingProgress(estimatedTokens = -5, estimatedTokensDelta = -5))

        composeTestRule.onNodeWithContentDescription(thinkingDescription).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(progressDescription(-5)).assertDoesNotExist()
    }

    /** The gate's upper bound: an absurd magnitude from a buggy or hostile daemon cannot stretch the band. */
    @Test
    fun anImplausiblyLargeReadingDeclinesToThePlainLabel() {
        setThreadScreen(ThinkingProgress(estimatedTokens = Long.MAX_VALUE, estimatedTokensDelta = 64))

        composeTestRule.onNodeWithContentDescription(thinkingDescription).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(progressDescription(Long.MAX_VALUE)).assertDoesNotExist()
    }

    private fun setThreadScreen(progress: ThinkingProgress?) {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = populatedState(),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    isThinking = true,
                    thinkingProgress = progress,
                )
            }
        }
    }

    /** The resolved `cd_thread_thinking_progress` for [tokens] — the arm's description while a reading is live. */
    private fun progressDescription(tokens: Long): String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getString(R.string.cd_thread_thinking_progress, tokens)
}
