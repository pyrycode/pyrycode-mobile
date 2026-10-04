package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.BoundaryReason
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.components.SESSION_BOUNDARY_TEST_TAG
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionBoundaryVisibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun appended_boundary_is_revealed_after_a_tall_wrap_up_reply() {
        val timestamp = Instant.parse("2026-09-20T10:00:00Z")
        val wrapUp = (1..80).joinToString("\n\n") { "Wrap-up detail $it." }
        var state by mutableStateOf(
            ThreadUiState(
                conversationId = "conversation",
                displayName = "Session boundary regression",
                isPromoted = true,
                hasMessages = true,
                items =
                    listOf(
                        ThreadItem.MessageItem(
                            Message(
                                id = "wrap-up",
                                sessionId = "old-session",
                                role = Role.Assistant,
                                content = wrapUp,
                                timestamp = timestamp,
                                isStreaming = false,
                            ),
                        ),
                    ),
            ),
        )
        composeRule.setContent {
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
        composeRule.onNodeWithText("Wrap-up detail 80.").assertIsDisplayed()
        composeRule.onNodeWithText("Wrap-up detail 1.").assertIsNotDisplayed()
        val boundary = composeRule.onNodeWithTag(SESSION_BOUNDARY_TEST_TAG)
        boundary.assertDoesNotExist()
        composeRule.runOnIdle {
            state =
                state.copy(
                    items =
                        state.items +
                            ThreadItem.SessionBoundary(
                                previousSessionId = "old-session",
                                newSessionId = "new-session",
                                reason = BoundaryReason.Clear,
                                occurredAt = timestamp,
                                workspaceCwd = null,
                            ),
                )
        }

        // Pin the viewport to the wrap-up row, now at index 1 in ThreadScreen's reversed list.
        // The expanded viewport draws under chrome. Semantic display can include an obscured boundary.
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
        composeRule.onNodeWithText("Wrap-up detail 80.").assertIsDisplayed()
        assertBoundaryInReadingArea(expected = false)
        composeRule.awaitDisplayedSessionBoundary(timeoutMillis = 5_000)
        boundary.assertIsDisplayed()
        assertBoundaryInReadingArea(expected = true)
    }

    private fun assertBoundaryInReadingArea(expected: Boolean) {
        // The tagged row includes its trailing space; the rule shares the label's centre, so the
        // label's full bounds establish that the rendered delimiter is readable, without counting padding.
        val boundary = composeRule.onNodeWithText("New session", substring = true, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val header = composeRule.onNodeWithTag("thread-top-bar").getUnclippedBoundsInRoot()
        val composer = composeRule.onNodeWithTag("thread-composer").getUnclippedBoundsInRoot()
        assertEquals(
            "boundary $boundary must be readable between ${header.bottom} and ${composer.top}",
            expected,
            boundary.top >= header.bottom && boundary.bottom <= composer.top,
        )
    }
}
