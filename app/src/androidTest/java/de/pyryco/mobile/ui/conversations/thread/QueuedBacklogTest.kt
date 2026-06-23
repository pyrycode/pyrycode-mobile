package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.QueuedMessage
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Screen-level behaviour of the #461 queued-backlog render: while [ThreadScreen]'s
 * [ThreadUiState.queuedMessages] is non-empty, the ordered backlog is shown in wire order, visually
 * additive to the sent-message list; when empty, no backlog section renders. Mirrors
 * [ThinkingIndicatorTest] / [StallPromotionBannerTest] — the state is constructed directly (the composable
 * reads no repository), so this drives the render contract, not the ViewModel surfacing.
 */
@RunWith(AndroidJUnit4::class)
class QueuedBacklogTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun string(resId: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)

    private val backlogDescription: String = string(R.string.cd_thread_queued_backlog)

    private val dropDescription: String = string(R.string.cd_thread_queued_drop)

    private val ts: Instant = Instant.parse("2026-06-23T10:00:00Z")

    private fun queued(
        id: Long,
        text: String,
    ): QueuedMessage = QueuedMessage(id, text, ts)

    private fun sentUserMessage(
        id: String,
        text: String,
    ): ThreadItem.MessageItem =
        ThreadItem.MessageItem(
            Message(
                id = id,
                sessionId = "s1",
                role = Role.User,
                content = text,
                timestamp = ts,
                isStreaming = false,
            ),
        )

    private fun stateWith(
        queue: List<QueuedMessage>,
        items: List<ThreadItem> = emptyList(),
    ): ThreadUiState =
        ThreadUiState(
            conversationId = "c1",
            displayName = "Test channel",
            isPromoted = true,
            hasMessages = items.any { it is ThreadItem.MessageItem },
            items = items,
            queuedMessages = queue,
        )

    private fun setThreadScreen(state: ThreadUiState) {
        composeTestRule.setContent {
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
    }

    @Test
    fun renders_queued_messages_in_wire_order() {
        setThreadScreen(
            stateWith(
                queue =
                    listOf(
                        queued(1L, "first queued message"),
                        queued(2L, "second queued message"),
                    ),
            ),
        )

        // AC #1 / #5 — both render; the column merges descendants (a11y group) so read the per-row text
        // through the unmerged tree, then compare vertical position to prove FIFO == wire order.
        composeTestRule.onNodeWithText("first queued message", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("second queued message", useUnmergedTree = true).assertIsDisplayed()
        val firstTop =
            composeTestRule
                .onNodeWithText("first queued message", useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
                .top
        val secondTop =
            composeTestRule
                .onNodeWithText("second queued message", useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
                .top
        assertTrue("first queued message must render above the second", firstTop < secondTop)
    }

    @Test
    fun empty_queue_renders_no_backlog() {
        setThreadScreen(stateWith(queue = emptyList()))

        // AC #2 — no backlog section when the queue is empty.
        composeTestRule.onNodeWithContentDescription(backlogDescription).assertDoesNotExist()
    }

    @Test
    fun backlog_tracks_the_hoisted_state_with_no_local_state() {
        var queue by mutableStateOf(emptyList<QueuedMessage>())
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = stateWith(queue = queue),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                )
            }
        }

        // AC #3 — reactive: appears when a message joins the queue, clears when it empties.
        composeTestRule.onNodeWithContentDescription(backlogDescription).assertDoesNotExist()

        queue = listOf(queued(1L, "now waiting"))
        composeTestRule.onNodeWithContentDescription(backlogDescription).assertIsDisplayed()

        queue = emptyList()
        composeTestRule.onNodeWithContentDescription(backlogDescription).assertDoesNotExist()
    }

    @Test
    fun backlog_coexists_with_sent_messages() {
        setThreadScreen(
            stateWith(
                queue = listOf(queued(1L, "still waiting to send")),
                items = listOf(sentUserMessage("m0", "an already sent message")),
            ),
        )

        // The backlog is additive — the sent message still renders and the backlog renders alongside it.
        composeTestRule.onNodeWithText("an already sent message").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(backlogDescription).assertIsDisplayed()
    }

    @Test
    fun each_row_exposes_a_drop_affordance() {
        setThreadScreen(
            stateWith(
                queue =
                    listOf(
                        queued(1L, "first queued message"),
                        queued(2L, "second queued message"),
                    ),
            ),
        )

        // AC #1 — every rendered row carries an individually-addressable drop affordance. The backlog
        // Column merges descendants for its a11y group, but each clickable IconButton is its own
        // semantics node, so the drop nodes stay addressable in the unmerged tree (as the per-row text is).
        composeTestRule
            .onAllNodes(hasContentDescription(dropDescription), useUnmergedTree = true)
            .assertCountEquals(2)
    }

    @Test
    fun activating_a_rows_drop_affordance_routes_that_rows_id() {
        val dropped = mutableListOf<Long>()
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state =
                        stateWith(
                            queue =
                                listOf(
                                    queued(1L, "first queued message"),
                                    queued(2L, "second queued message"),
                                ),
                        ),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    onDropQueued = { dropped += it },
                )
            }
        }

        // AC #2 / AC #5 (activate half) — tapping the SECOND row's affordance routes the SECOND row's id
        // through onDropQueued. Asserting on the id (not just "a tap happened") proves per-row id wiring.
        composeTestRule
            .onAllNodes(hasContentDescription(dropDescription), useUnmergedTree = true)
            .onLast()
            .performClick()
        composeTestRule.runOnIdle {
            assertEquals(listOf(2L), dropped)
        }
    }
}
