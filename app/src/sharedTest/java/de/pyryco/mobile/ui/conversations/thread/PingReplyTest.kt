package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.QueuedMessage
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PingReplyTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun reply_matches_when_queue_disappears_without_substring_count_growth() {
        val timestamp = Instant.parse("2026-09-20T10:00:00Z")
        val prompt =
            ThreadItem.MessageItem(
                Message(
                    id = "prompt",
                    sessionId = "session",
                    role = Role.User,
                    content = PING_PROMPT,
                    timestamp = timestamp,
                    isStreaming = false,
                ),
            )
        val initialState =
            ThreadUiState(
                conversationId = "conversation",
                displayName = "ping",
                isPromoted = true,
                hasMessages = true,
                items = listOf(prompt),
                queuedMessages = listOf(QueuedMessage(1L, "ping", timestamp)),
            )
        var state by mutableStateOf(initialState)
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

        // Even exact "ping" in the title and in a queued row is not a reply. Since #782 the queued row
        // is drawn inline among the delivered rows rather than in a foot-of-list section, so this is
        // also what pins that an inline queued entry is still not a reply.
        composeTestRule.onAllNodes(pingReplyMatcher(), useUnmergedTree = true).assertCountEquals(0)
        val baseline = substringCount()
        assertEquals(3, baseline)

        composeTestRule.runOnIdle { state = state.copy(queuedMessages = emptyList()) }
        composeTestRule.onAllNodes(pingReplyMatcher(), useUnmergedTree = true).assertCountEquals(0)
        assertEquals(baseline - 1, substringCount())

        composeTestRule.runOnIdle { state = initialState }
        composeTestRule.onAllNodes(pingReplyMatcher(), useUnmergedTree = true).assertCountEquals(0)
        assertEquals(baseline, substringCount())

        composeTestRule.runOnIdle {
            state =
                state.copy(
                    queuedMessages = emptyList(),
                    items =
                        listOf(
                            prompt,
                            ThreadItem.MessageItem(
                                prompt.message.copy(id = "reply", role = Role.Assistant, content = "ping"),
                            ),
                        ),
                )
        }
        composeTestRule.awaitDisplayedPingReply(timeoutMillis = 5_000)
        assertEquals(baseline, substringCount())
    }

    private fun substringCount(): Int =
        composeTestRule
            .onAllNodesWithText("ping", substring = true, ignoreCase = true)
            .fetchSemanticsNodes()
            .size
}
