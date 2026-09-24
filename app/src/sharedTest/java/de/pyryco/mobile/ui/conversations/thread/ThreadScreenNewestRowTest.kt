package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A new newest row is shown to a reader at the newest end, streaming or not (#981), and a reader who has
 * scrolled away is left where they are. Under reverseLayout the list keeps its first visible row anchored
 * by key, so a row inserted at index 0 lands below the viewport unless something scrolls to it.
 *
 * Every test seeds thirty rows so the list overflows the viewport. With a short list the measure pulls
 * index 0 back in to fill the empty space, and the hidden-row failure cannot happen.
 */
@RunWith(AndroidJUnit4::class)
class ThreadScreenNewestRowTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun a_complete_reply_arriving_at_the_newest_end_is_composed_and_visible() {
        var state by mutableStateOf(threadState(rows(count = 30)))
        setScreen { state }
        composeRule.onNodeWithText("Row 30.").assertIsDisplayed()

        composeRule.runOnIdle { state = state.copy(items = state.items + message("reply", REPLY, isStreaming = false)) }

        composeRule.onNodeWithText(REPLY).assertIsDisplayed()
    }

    @Test
    fun a_reader_scrolled_away_is_not_pulled_back_by_a_complete_reply() {
        var state by mutableStateOf(threadState(rows(count = 30)))
        setScreen { state }
        scrollAwayFromTheNewestEnd()

        composeRule.runOnIdle { state = state.copy(items = state.items + message("reply", REPLY, isStreaming = false)) }

        composeRule.onNodeWithText("Row 30.").assertDoesNotExist()
        composeRule.onNodeWithText(REPLY).assertDoesNotExist()
    }

    @Test
    fun a_reader_scrolled_away_is_not_pulled_back_by_a_streaming_reply() {
        var state by mutableStateOf(threadState(rows(count = 30)))
        setScreen { state }
        scrollAwayFromTheNewestEnd()

        composeRule.runOnIdle { state = state.copy(items = state.items + message("reply", REPLY, isStreaming = true)) }

        composeRule.onNodeWithText("Row 30.").assertDoesNotExist()
        composeRule.onNodeWithText(REPLY, substring = true).assertDoesNotExist()
    }

    /**
     * A real drag, not `performScrollToIndex`: only input through the nested-scroll chain as
     * `NestedScrollSource.UserInput` sets the yield flag. Under reverseLayout older rows sit above, so the
     * finger moves down to reach them.
     */
    private fun scrollAwayFromTheNewestEnd() {
        composeRule.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Row 30.").assertDoesNotExist()
    }

    private fun setScreen(state: () -> ThreadUiState) {
        composeRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = state(),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                )
            }
        }
    }

    private fun threadState(items: List<ThreadItem>) =
        ThreadUiState(
            conversationId = "conversation",
            displayName = "Newest row",
            isPromoted = true,
            hasMessages = true,
            items = items,
        )

    private fun rows(count: Int): List<ThreadItem> = (1..count).map { message("Row-$it", "Row $it.", isStreaming = false) }

    private fun message(
        id: String,
        content: String,
        isStreaming: Boolean,
    ): ThreadItem =
        ThreadItem.MessageItem(
            Message(
                id = id,
                sessionId = "s1",
                role = Role.Assistant,
                content = content,
                timestamp = Instant.parse("2026-09-24T05:27:00Z"),
                isStreaming = isStreaming,
            ),
        )

    private companion object {
        const val REPLY = "The file's witness token."
    }
}
