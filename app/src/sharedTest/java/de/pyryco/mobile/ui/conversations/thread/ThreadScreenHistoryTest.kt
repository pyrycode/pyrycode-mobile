package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The list side of the history walk (#777): the oldest-end affordance and the demand predicate. */
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

    @Test
    fun toggling_the_loading_flag_with_the_rows_unchanged_issues_no_further_demand() {
        // The regression this predicate exists for. Counting layoutInfo.totalItemsCount would count the
        // loading row itself, so a page answering atStart = false with zero entries would self-drive:
        // ask -> the indicator mounts -> the count rises -> the page settles -> the indicator unmounts ->
        // the count falls -> the predicate re-fires, with no further user input.
        //
        // The list MUST overflow the viewport and be scrolled to the oldest end for this to bite. When
        // every row fits, the mounted indicator is visible too, so the last visible index tracks the
        // total either way and a totalItemsCount predicate passes by accident. Overflowing, the indicator
        // mounts ABOVE the viewport, so a totalItemsCount predicate flips true -> false on mount and back
        // on unmount, and each unmount is an edge that issues another demand.
        var state by mutableStateOf(threadState(rows(count = 30), ThreadHistoryTail.None))
        var demands = 0
        setScreen({ state }, onDemand = { demands++ })
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(29)
        composeRule.waitForIdle()
        val afterReachingTheOldestRow = demands
        assertEquals(1, afterReachingTheOldestRow)
        repeat(3) {
            composeRule.runOnIdle { state = state.copy(historyTail = ThreadHistoryTail.Loading) }
            composeRule.waitForIdle()
            composeRule.runOnIdle { state = state.copy(historyTail = ThreadHistoryTail.None) }
            composeRule.waitForIdle()
        }
        assertEquals(afterReachingTheOldestRow, demands)
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

    @Test
    fun cycling_the_whole_tail_with_the_rows_unchanged_issues_no_further_demand() {
        // #777's regression, re-run across the widened slot: three mountable rows instead of one, and
        // still none of them may move a predicate that counts THREAD rows. The list must overflow the
        // viewport and be scrolled to the oldest end, or a totalItemsCount predicate passes by accident.
        var state by mutableStateOf(threadState(rows(count = 30), ThreadHistoryTail.None))
        var demands = 0
        setScreen({ state }, onDemand = { demands++ }, onRetryOlder = {})
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(29)
        composeRule.waitForIdle()
        val afterReachingTheOldestRow = demands
        assertEquals(1, afterReachingTheOldestRow)

        listOf(
            ThreadHistoryTail.Loading,
            ThreadHistoryTail.Retry,
            ThreadHistoryTail.DeadEnd,
            ThreadHistoryTail.None,
        ).forEach { tail ->
            composeRule.runOnIdle { state = state.copy(historyTail = tail) }
            composeRule.waitForIdle()
        }
        assertEquals(afterReachingTheOldestRow, demands)
    }

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
    }
}
