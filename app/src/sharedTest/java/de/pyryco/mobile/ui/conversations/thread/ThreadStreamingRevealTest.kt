package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThreadStreamingRevealTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var row by mutableStateOf<Message?>(null)
    private var open by mutableStateOf(true)

    private fun showThread() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            PyrycodeMobileTheme {
                if (open) {
                    ThreadScreen(
                        state =
                            ThreadUiState(
                                conversationId = "c1",
                                displayName = "Thread",
                                hasMessages = row != null,
                                items = row?.let { listOf(ThreadItem.MessageItem(it)) }.orEmpty(),
                            ),
                        onBack = {},
                        onSendMessage = {},
                        connectionState = ConnectionState.Connected,
                        onRetry = {},
                    )
                }
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
    }

    private fun streaming(
        content: String,
        timestamp: Instant,
    ) = Message(
        id = "reply",
        sessionId = "s1",
        role = Role.Assistant,
        content = content,
        timestamp = timestamp,
        isStreaming = true,
    )

    @Test
    fun existingText_isImmediate_appendReveals_andReopeningReplaysNothing() {
        val arrived = "Already arrived"
        val appended = " and more text arrives while the reply continues streaming in the open thread"
        row = streaming(arrived, Instant.parse("2026-01-01T00:00:00Z"))
        showThread()
        composeRule.onNodeWithText(arrived, substring = true).assertIsDisplayed()

        composeRule.runOnIdle { row = row?.copy(content = arrived + appended) }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText(arrived, substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(arrived + appended, substring = true).assertDoesNotExist()
        composeRule.mainClock.advanceTimeBy(160)
        composeRule.onNodeWithText(arrived + " and", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(arrived + appended, substring = true).assertDoesNotExist()

        // Reopen before the appended text has finished revealing.
        composeRule.runOnIdle { open = false }
        composeRule.mainClock.advanceTimeBy(64)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(arrived, substring = true).assertDoesNotExist()
        composeRule.runOnIdle { open = true }
        // Synchronize each layout frame; the budget cannot reveal even the remaining appended text.
        composeRule.mainClock.advanceTimeUntil(timeoutMillis = 128) {
            composeRule.onAllNodesWithText(arrived + appended, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(arrived + appended, substring = true).assertExists().assertIsDisplayed()
    }

    @Test
    fun firstDeltaAfterOpening_revealsFromZero() {
        var fold = ThreadFold(emptyList(), null)
        showThread()
        val content = "New reply while open continues with enough words to observe gradual reveal before catching up"
        composeRule.runOnIdle {
            fold = fold.reduce(ThreadInput.Live(LiveSessionEvent.AssistantDelta("c1", "reply", 1, content)), "c1")
            row = (fold.render().single() as ThreadItem.MessageItem).message
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText(content, substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("New", substring = true).assertDoesNotExist()
        composeRule.mainClock.advanceTimeBy(160)
        composeRule.onNodeWithText("New", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(content, substring = true).assertDoesNotExist()

        // The repository projection wins next, with the same lazy key and additional text.
        val full = content + " and an appended delta"
        composeRule.runOnIdle {
            fold = fold.reduce(ThreadInput.Finished(listOf(ThreadItem.MessageItem(streaming(full, Clock.System.now())))), "c1")
            row = (fold.render().single() as ThreadItem.MessageItem).message
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText("New", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(content, substring = true).assertDoesNotExist()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.onNodeWithText(full, substring = true).assertIsDisplayed()
    }

    @Test
    fun syntheticFirstDeltaWithHistory_revealsFromZero() {
        val history =
            streaming(
                "Earlier question",
                Instant.parse("2026-01-01T00:00:00Z"),
            ).copy(id = "question", role = Role.User, isStreaming = false)
        row = history
        showThread()
        val content = "New reply after history continues with enough words to observe gradual reveal before catching up"
        composeRule.runOnIdle {
            val fold =
                ThreadFold(listOf(ThreadItem.MessageItem(history)), null)
                    .reduce(ThreadInput.Live(LiveSessionEvent.AssistantDelta("c1", "reply", 1, content)), "c1")
            row = (fold.render().last() as ThreadItem.MessageItem).message
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText("New", substring = true).assertDoesNotExist()
        composeRule.mainClock.advanceTimeBy(160)
        composeRule.onNodeWithText("New", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(content, substring = true).assertDoesNotExist()
    }

    @Test
    fun syntheticBeforeOpening_isImmediate_andReopeningReplaysNothing() {
        var fold =
            ThreadFold(emptyList(), null)
                .reduce(ThreadInput.Live(LiveSessionEvent.AssistantDelta("c1", "reply", 1, "Arrived before opening")), "c1")
        row = (fold.render().single() as ThreadItem.MessageItem).message
        showThread()
        composeRule.onNodeWithText("Arrived before opening", substring = true).assertIsDisplayed()

        val appended = " and appended afterward while the reply continues streaming in the open thread"
        val full = "Arrived before opening" + appended
        composeRule.runOnIdle {
            fold = fold.reduce(ThreadInput.Live(LiveSessionEvent.AssistantDelta("c1", "reply", 2, appended)), "c1")
            row = (fold.render().single() as ThreadItem.MessageItem).message
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithText("Arrived before opening", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(full, substring = true).assertDoesNotExist()
        composeRule.mainClock.advanceTimeBy(160)
        composeRule.onNodeWithText("Arrived before opening and", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(full, substring = true).assertDoesNotExist()

        composeRule.runOnIdle { open = false }
        composeRule.mainClock.advanceTimeBy(64)
        composeRule.waitForIdle()
        composeRule.runOnIdle { open = true }
        composeRule.mainClock.advanceTimeUntil(timeoutMillis = 128) {
            composeRule.onAllNodesWithText(full, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(full, substring = true).assertIsDisplayed()
    }
}
