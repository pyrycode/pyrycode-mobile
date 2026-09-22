package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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
        var state by mutableStateOf(threadState(rows(count = 3), historyLoading = false))
        setScreen({ state }, onDemand = {})
        composeRule.onNodeWithContentDescription(HISTORY_LOADING_DESCRIPTION).assertDoesNotExist()
        composeRule.runOnIdle { state = state.copy(historyLoading = true) }
        composeRule.onNodeWithContentDescription(HISTORY_LOADING_DESCRIPTION).assertIsDisplayed()
        composeRule.runOnIdle { state = state.copy(historyLoading = false) }
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
        var state by mutableStateOf(threadState(rows(count = 30), historyLoading = false))
        var demands = 0
        setScreen({ state }, onDemand = { demands++ })
        composeRule.onNode(hasScrollAction()).performScrollToIndex(29)
        composeRule.waitForIdle()
        val afterReachingTheOldestRow = demands
        assertEquals(1, afterReachingTheOldestRow)
        repeat(3) {
            composeRule.runOnIdle { state = state.copy(historyLoading = true) }
            composeRule.waitForIdle()
            composeRule.runOnIdle { state = state.copy(historyLoading = false) }
            composeRule.waitForIdle()
        }
        assertEquals(afterReachingTheOldestRow, demands)
    }

    @Test
    fun a_prepended_page_leaves_the_row_the_reader_is_looking_at_where_it_was() {
        // reverseLayout puts older rows at HIGHER indices, so a prepend lands beyond the viewport rather
        // than shifting it, and the per-subtype keys are computed from item fields and never position.
        var state by mutableStateOf(threadState(rows(count = 30), historyLoading = false))
        setScreen({ state }, onDemand = {})
        composeRule.onNode(hasScrollAction()).performScrollToIndex(10)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Row 19.").assertIsDisplayed()
        composeRule.runOnIdle { state = state.copy(items = rows(count = 20, prefix = "Older") + state.items) }
        composeRule.onNodeWithText("Row 19.").assertIsDisplayed()
    }

    private fun setScreen(
        state: () -> ThreadUiState,
        onDemand: () -> Unit,
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
                )
            }
        }
    }

    private fun threadState(
        items: List<ThreadItem>,
        historyLoading: Boolean,
    ) = ThreadUiState(
        conversationId = "conversation",
        displayName = "History walk",
        isPromoted = true,
        hasMessages = true,
        items = items,
        historyLoading = historyLoading,
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
    }
}
