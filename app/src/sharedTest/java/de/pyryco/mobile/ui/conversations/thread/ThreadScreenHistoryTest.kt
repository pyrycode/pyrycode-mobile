package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The list side of the history walk (#777): the oldest-end affordance and the reader's pull (#1352). */
@RunWith(AndroidJUnit4::class)
class ThreadScreenHistoryTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loading_row_is_shown_at_the_oldest_end_only_while_a_page_is_in_flight() {
        var state by mutableStateOf(threadState(rows(count = 3), ThreadHistoryTail.None))
        setScreen({ state }, onDemand = {})
        composeRule.onNodeWithContentDescription(HISTORY_LOADING_DESCRIPTION).assertDoesNotExist()
        composeRule.runOnIdle { state = state.copy(historyTail = ThreadHistoryTail.Loading) }
        composeRule.onNodeWithContentDescription(HISTORY_LOADING_DESCRIPTION).assertIsDisplayed()
        composeRule.runOnIdle { state = state.copy(historyTail = ThreadHistoryTail.None) }
        composeRule.onNodeWithContentDescription(HISTORY_LOADING_DESCRIPTION).assertDoesNotExist()
    }

    // --- #1352: only the reader's pull asks ------------------------------------------------------

    @Test
    fun a_pull_on_an_empty_thread_asks_once() {
        var demands = 0
        setScreen({ threadState(emptyList(), ThreadHistoryTail.None).copy(hasMessages = false) }, onDemand = { demands++ })
        pullTowardOlder()
        composeRule.runOnIdle { assertEquals(1, demands) }
    }

    @Test
    fun a_pull_on_a_thread_too_short_to_scroll_asks_once() {
        var demands = 0
        setScreen({ threadState(rows(count = 3), ThreadHistoryTail.None) }, onDemand = { demands++ })
        pullTowardOlder()
        composeRule.runOnIdle { assertEquals(1, demands) }
    }

    @Test
    fun a_long_thread_asks_only_for_a_pull_that_starts_near_its_oldest_end() {
        var demands = 0
        setScreen({ threadState(rows(count = 30), ThreadHistoryTail.None) }, onDemand = { demands++ })

        // Opened at the newest end: the pull only scrolls.
        pullTowardOlder(fraction = 0.1f)
        composeRule.runOnIdle { assertEquals(0, demands) }

        // At the oldest end: one ask per pull.
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(29)
        composeRule.waitForIdle()
        pullTowardOlder()
        composeRule.runOnIdle { assertEquals(1, demands) }
        pullTowardOlder()
        composeRule.runOnIdle { assertEquals(2, demands) }
    }

    @Test
    fun the_ask_band_is_200dp_from_the_oldest_end() {
        var demands = 0
        setScreen({ threadState(rows(count = 30), ThreadHistoryTail.None) }, onDemand = { demands++ })

        // 100dp short of the oldest end is inside the band...
        scrollToOldestThenTowardNewer(100)
        composeRule.onNodeWithText("Row 1.").assertExists()
        assertTrue("Row 1 should be partly hidden above the list", oldestRowTop() < listTop())
        pullTowardOlder(fraction = 0.05f)
        composeRule.runOnIdle { assertEquals(1, demands) }

        // ...and 300dp short is outside it.
        scrollToOldestThenTowardNewer(300)
        pullTowardOlder(fraction = 0.05f)
        composeRule.runOnIdle { assertEquals(1, demands) }
    }

    @Test
    fun a_pull_while_a_page_is_loading_asks_nothing() {
        var demands = 0
        setScreen({ threadState(rows(count = 3), ThreadHistoryTail.Loading) }, onDemand = { demands++ })
        pullTowardOlder()
        composeRule.runOnIdle { assertEquals(0, demands) }
    }

    @Test
    fun reaching_the_oldest_row_or_cycling_the_tail_asks_nothing() {
        // Opening, a page arriving and the oldest row coming into view are not the reader's pull.
        var state by mutableStateOf(threadState(rows(count = 30), ThreadHistoryTail.None))
        var demands = 0
        setScreen({ state }, onDemand = { demands++ })
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(29)
        composeRule.waitForIdle()
        listOf(
            ThreadHistoryTail.Loading,
            ThreadHistoryTail.Retry,
            ThreadHistoryTail.DeadEnd,
            ThreadHistoryTail.Offline,
            ThreadHistoryTail.None,
        ).forEach { tail ->
            composeRule.runOnIdle { state = state.copy(historyTail = tail) }
            composeRule.waitForIdle()
        }
        composeRule.runOnIdle { state = state.copy(items = rows(count = 10, prefix = "Older") + state.items) }
        composeRule.waitForIdle()
        assertEquals(0, demands)
    }

    @Test
    fun offline_notice_is_shown_at_the_oldest_end() {
        setScreen({ threadState(rows(count = 3), ThreadHistoryTail.Offline) }, onDemand = {})
        composeRule.onNodeWithText(HISTORY_OFFLINE_TEXT).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(HISTORY_RETRY_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun a_prepended_page_leaves_the_row_the_reader_is_looking_at_where_it_was() {
        // reverseLayout puts older rows at HIGHER indices, so a prepend lands beyond the viewport rather
        // than shifting it, and the per-subtype keys are computed from item fields and never position.
        var state by mutableStateOf(threadState(rows(count = 30), ThreadHistoryTail.None))
        setScreen({ state }, onDemand = {})
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(10)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Row 19.").assertIsDisplayed()
        composeRule.runOnIdle { state = state.copy(items = rows(count = 20, prefix = "Older") + state.items) }
        composeRule.onNodeWithText("Row 19.").assertIsDisplayed()
    }

    // --- #778: the failure states of the same oldest-end slot --------------------------------------

    @Test
    fun retry_row_is_shown_for_a_retryable_failure_and_its_press_reaches_the_callback() {
        var state by mutableStateOf(threadState(rows(count = 3), ThreadHistoryTail.Retry))
        var retries = 0
        setScreen({ state }, onDemand = {}, onRetryOlder = { retries++ })

        composeRule.onNodeWithContentDescription(HISTORY_RETRY_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(HISTORY_RETRY_DESCRIPTION).performClick()
        assertEquals(1, retries)

        // The slot is one slot: settling back to loading replaces the row rather than stacking one.
        composeRule.runOnIdle { state = state.copy(historyTail = ThreadHistoryTail.Loading) }
        composeRule.onNodeWithContentDescription(HISTORY_RETRY_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(HISTORY_LOADING_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun dead_end_row_is_shown_for_a_permanent_failure_with_nothing_to_press() {
        val state = threadState(rows(count = 3), ThreadHistoryTail.DeadEnd)
        var retries = 0
        setScreen({ state }, onDemand = {}, onRetryOlder = { retries++ })

        composeRule.onNodeWithContentDescription(HISTORY_DEAD_END_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(HISTORY_RETRY_DESCRIPTION).assertDoesNotExist()
        // A dead end offers no affordance at all: pressing where the retry would be does nothing.
        composeRule.onNodeWithContentDescription(HISTORY_DEAD_END_DESCRIPTION).performClick()
        assertEquals(0, retries)
    }

    /** A user drag toward older messages: the finger moves down over [fraction] of the message region. */
    private fun pullTowardOlder(fraction: Float = 0.4f) {
        composeRule.onNodeWithTag(MESSAGE_REGION_TAG).performTouchInput {
            swipeDown(startY = height * 0.3f, endY = height * (0.3f + fraction), durationMillis = 400)
        }
        composeRule.waitForIdle()
    }

    /** Scroll to the oldest end, then [dp] back toward newer messages, without a user drag. */
    private fun scrollToOldestThenTowardNewer(dp: Int) {
        val list = composeRule.onNode(hasScrollToIndexAction())
        list.performScrollToIndex(29)
        composeRule.waitForIdle()
        val px = with(composeRule.density) { dp.dp.toPx() }
        // Semantics scroll follows the list's index order: under reverseLayout a negative y moves toward
        // index 0, the newest rows.
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -px) }
        composeRule.waitForIdle()
    }

    private fun oldestRowTop(): Float =
        composeRule
            .onNodeWithText("Row 1.")
            .fetchSemanticsNode()
            .boundsInRoot.top

    private fun listTop(): Float =
        composeRule
            .onNode(hasScrollToIndexAction())
            .fetchSemanticsNode()
            .boundsInRoot.top

    private fun setScreen(
        state: () -> ThreadUiState,
        onDemand: () -> Unit,
        onRetryOlder: () -> Unit = {},
    ) {
        composeRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = state(),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    onDemandOlderHistory = onDemand,
                    onRetryOlderHistory = onRetryOlder,
                )
            }
        }
    }

    private fun threadState(
        items: List<ThreadItem>,
        historyTail: ThreadHistoryTail,
    ) = ThreadUiState(
        conversationId = "conversation",
        displayName = "History walk",
        isPromoted = true,
        hasMessages = true,
        items = items,
        historyTail = historyTail,
    )

    private fun rows(
        count: Int,
        prefix: String = "Row",
    ): List<ThreadItem> =
        (1..count).map { index ->
            ThreadItem.MessageItem(
                Message(
                    id = "$prefix-$index",
                    sessionId = "s1",
                    role = Role.Assistant,
                    content = "$prefix $index.",
                    timestamp = Instant.parse("2026-09-22T10:00:00Z"),
                    isStreaming = false,
                ),
            )
        }

    private companion object {
        const val HISTORY_LOADING_DESCRIPTION = "Loading earlier messages in this conversation"
        const val HISTORY_RETRY_DESCRIPTION = "Couldn't load earlier messages. Try again."
        const val HISTORY_DEAD_END_DESCRIPTION = "Earlier messages in this conversation are unavailable"
        const val HISTORY_OFFLINE_TEXT = "Older messages require a connection."
        const val MESSAGE_REGION_TAG = "thread-message-region"
    }
}
