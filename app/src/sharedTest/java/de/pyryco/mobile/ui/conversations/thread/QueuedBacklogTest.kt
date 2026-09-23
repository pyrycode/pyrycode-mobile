package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
 * Screen-level behaviour of the queued backlog inside [ThreadScreen] — carried over from the #461/#467
 * foot-of-list section to #782's folded rows. The section is gone; its wire-order, drop-affordance and
 * id-routing contracts are not, and they are asserted here in the new shape, alongside the one the fold
 * exists for: a queued send draws **once**.
 *
 * The state is constructed directly (the composable reads no repository), so this drives the render
 * contract, not the ViewModel surfacing. The correlation rules themselves are proven off-device by
 * [de.pyryco.mobile.ui.conversations.thread.foldQueuedRows]'s unit test.
 */
@RunWith(AndroidJUnit4::class)
class QueuedBacklogTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun string(resId: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)

    private val dropDescription: String = string(R.string.cd_thread_queued_drop)

    private val ts: Instant = Instant.parse("2026-06-23T10:00:00Z")

    private fun queued(
        id: Long,
        text: String,
        messageId: String = "",
    ): QueuedMessage = QueuedMessage(id = id, text = text, timestamp = ts, messageId = messageId)

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

    private fun setThreadScreen(
        state: ThreadUiState,
        onDropQueued: (Long) -> Unit = {},
    ) {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = state,
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    onDropQueued = onDropQueued,
                )
            }
        }
    }

    private fun topOfText(text: String) =
        composeTestRule
            .onNodeWithText(text, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
            .top

    private fun dropAffordances() = composeTestRule.onAllNodes(hasContentDescription(dropDescription), useUnmergedTree = true)

    // AC #1 — the bug this slice fixes. The echo and the backlog item are the same message, so the
    // thread draws ONE row carrying the queue treatment, not the echo plus a second section row.
    @Test
    fun a_parked_send_draws_one_row_carrying_the_treatment() {
        setThreadScreen(
            stateWith(
                items = listOf(sentUserMessage("m-1", "wait for me")),
                queue = listOf(queued(7L, "wait for me", messageId = "m-1")),
            ),
        )

        composeTestRule.onAllNodesWithText("wait for me", useUnmergedTree = true).assertCountEquals(1)
        dropAffordances().assertCountEquals(1)
    }

    // AC #1 — correlation is by id, never by text: two echoes with equal text and different ids stay
    // two rows, and only the named one carries the treatment.
    @Test
    fun equal_text_under_different_ids_stays_two_rows() {
        setThreadScreen(
            stateWith(
                items = listOf(sentUserMessage("m-1", "same words"), sentUserMessage("m-2", "same words")),
                queue = listOf(queued(7L, "same words", messageId = "m-2")),
            ),
        )

        composeTestRule.onAllNodesWithText("same words", useUnmergedTree = true).assertCountEquals(2)
        dropAffordances().assertCountEquals(1)
    }

    // AC #2 — delivery keeps the row where it is and takes the treatment away; the message does not
    // vanish, move or double up. Driven through hoisted state, so it also re-proves the row holds none.
    @Test
    fun delivery_keeps_the_row_and_removes_its_treatment() {
        val items = listOf(sentUserMessage("m-0", "an already sent message"), sentUserMessage("m-1", "wait for me"))
        var queue by mutableStateOf(listOf(queued(7L, "wait for me", messageId = "m-1")))
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = stateWith(queue = queue, items = items),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                )
            }
        }

        dropAffordances().assertCountEquals(1)
        val queuedTop = topOfText("wait for me")
        val sentTop = topOfText("an already sent message")
        assertTrue("the queued row must render below the message sent before it", sentTop < queuedTop)

        queue = emptyList()

        dropAffordances().assertCountEquals(0)
        composeTestRule.onAllNodesWithText("wait for me", useUnmergedTree = true).assertCountEquals(1)
        composeTestRule.onNodeWithText("wait for me", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(
            "delivery must not reorder the row",
            topOfText("an already sent message") < topOfText("wait for me"),
        )
    }

    // AC #3 — a backlog item this device minted no echo for is its own row after the thread rows, and
    // is never hidden. Carried over from `backlog_coexists_with_sent_messages`.
    @Test
    fun an_unmatched_item_renders_after_the_thread_rows() {
        setThreadScreen(
            stateWith(
                items = listOf(sentUserMessage("m-0", "an already sent message")),
                queue = listOf(queued(1L, "queued from the desktop", messageId = "minted-elsewhere")),
            ),
        )

        composeTestRule.onNodeWithText("an already sent message", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("queued from the desktop", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(
            "an unmatched backlog row must render after the thread rows",
            topOfText("an already sent message") < topOfText("queued from the desktop"),
        )
    }

    // AC #3 — and it is not hidden behind the empty state when the thread has no rows of its own.
    @Test
    fun an_unmatched_item_is_visible_on_an_otherwise_empty_thread() {
        setThreadScreen(stateWith(queue = listOf(queued(1L, "queued from the desktop"))))

        composeTestRule.onNodeWithText("queued from the desktop", useUnmergedTree = true).assertIsDisplayed()
        dropAffordances().assertCountEquals(1)
    }

    // AC #5 — rows render in wire order, and an empty backlog leaves none behind. Carried over from
    // `renders_queued_messages_in_wire_order` + `backlog_tracks_the_hoisted_state_with_no_local_state`.
    @Test
    fun queued_rows_render_in_wire_order_and_clear_when_the_backlog_empties() {
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

        dropAffordances().assertCountEquals(0)

        queue = listOf(queued(1L, "first queued message"), queued(2L, "second queued message"))

        composeTestRule.onNodeWithText("first queued message", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("second queued message", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(
            "first queued message must render above the second",
            topOfText("first queued message") < topOfText("second queued message"),
        )

        queue = emptyList()

        dropAffordances().assertCountEquals(0)
        composeTestRule.onAllNodesWithText("first queued message", useUnmergedTree = true).assertCountEquals(0)
    }

    // AC #1 — every queued row carries an individually-addressable drop affordance. The row merges
    // descendants for its a11y group, but each clickable IconButton is its own semantics node.
    @Test
    fun each_queued_row_exposes_a_drop_affordance() {
        setThreadScreen(
            stateWith(queue = listOf(queued(1L, "first queued message"), queued(2L, "second queued message"))),
        )

        dropAffordances().assertCountEquals(2)
    }

    // AC #1 — tapping the LOWER row's affordance routes the LOWER row's id. Asserting on the id (not
    // just "a tap happened") is what proves per-row id wiring; the row is located by its position
    // rather than by tree order, which is what the wire-order assertion above pins.
    @Test
    fun activating_a_rows_drop_affordance_routes_that_rows_id() {
        val dropped = mutableListOf<Long>()
        setThreadScreen(
            state = stateWith(queue = listOf(queued(1L, "first queued message"), queued(2L, "second queued message"))),
            onDropQueued = { dropped += it },
        )

        val tops = dropAffordances().fetchSemanticsNodes().map { it.boundsInRoot.top }
        val lowerRow = tops.indices.maxByOrNull { tops[it] } ?: error("no drop affordance rendered")
        dropAffordances()[lowerRow].performClick()

        composeTestRule.runOnIdle {
            assertEquals(listOf(2L), dropped)
        }
    }
}
