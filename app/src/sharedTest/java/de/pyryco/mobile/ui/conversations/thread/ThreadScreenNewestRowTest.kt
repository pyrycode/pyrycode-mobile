package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
     * A finger resting at the newest end holds the list's scroll at `UserInput` priority while the list
     * still follows (#1314), so a row arriving then has its pin scroll refused. The refusal must cost that one
     * scroll, not the pin: the next arrival after the finger lifts is followed again.
     */
    @Test
    fun a_scroll_refused_under_a_resting_finger_does_not_stop_later_rows_being_followed() {
        var state by mutableStateOf(threadState(rows(count = 30)))
        setScreen { state }
        val list = composeRule.onNode(hasScrollToIndexAction())
        list.performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(0f, 150f)) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Row 30.").assertDoesNotExist()
        // The last move crosses the newest edge in one event, so the drag ends at the newest end and
        // following is recomputed as on.
        list.performTouchInput {
            repeat(9) { moveBy(Offset(0f, -150f)) }
            moveBy(Offset(0f, -600f))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Row 30.").assertIsDisplayed()

        composeRule.runOnIdle { state = state.copy(items = state.items + message("refused", "Refused row.", isStreaming = false)) }
        composeRule.waitForIdle()
        // The finger rests before lifting, so the lift carries no fling that would reveal the rows itself.
        list.performTouchInput {
            advanceEventTime(1_000)
            up()
        }
        composeRule.runOnIdle { state = state.copy(items = state.items + message("reply", REPLY, isStreaming = false)) }

        composeRule.onNodeWithText(REPLY).assertIsDisplayed()
    }

    /**
     * A real drag that ends away from the newest end, so following is off by position (#1314). Under
     * reverseLayout older rows sit above, so the finger moves down to reach them. The list is selected by `ScrollToIndex` because on a device the
     * composer's text field also exposes `ScrollBy`.
     */
    private fun scrollAwayFromTheNewestEnd() {
        val top =
            composeRule
                .onNodeWithTag("thread-top-bar")
                .fetchSemanticsNode()
                .boundsInRoot.bottom + 24f
        val bottom =
            composeRule
                .onNodeWithTag("thread-composer")
                .fetchSemanticsNode()
                .boundsInRoot.top - 24f
        composeRule.onNode(hasScrollToIndexAction()).performTouchInput { swipeDown(startY = top, endY = bottom) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Row 30.").assertDoesNotExist()
    }

    /**
     * Overscroll is off: on a device a finger resting past the newest edge holds the stretch effect, which
     * keeps drawing frames, so `waitForIdle` never returns. Robolectric draws no such frames.
     */
    private fun setScreen(state: () -> ThreadUiState) {
        composeRule.setContent {
            CompositionLocalProvider(LocalOverscrollFactory provides null) {
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
