package de.pyryco.mobile.e2e

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.BoundaryReason
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.thread.ThreadScreen
import de.pyryco.mobile.ui.conversations.thread.ThreadUiState
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentSwitchHistoryTest {
    @get:Rule val rule = createComposeRule()

    @Test fun authoredHistoryRemainsOrderedAcrossTallHandoverAndSessionDelimiter() {
        val time = Instant.parse("2026-10-13T00:00:00Z")
        val expected =
            listOf(
                Role.User to "baseline prompt",
                Role.Assistant to "baseline reply",
                Role.User to "successor prompt",
                Role.Assistant to "successor reply",
            )
        val authored = expected.mapIndexed { i, (role, text) -> Message("authored$i", "session", role, text, time, isStreaming = false) }
        val handover =
            Message("handover", "session", Role.Assistant, List(80) { "Hand-over content" }.joinToString("\n\n"), time, isStreaming = false)
        val messages = authored.take(2) + handover + authored.drop(2)
        val items =
            messages.take(3).map { ThreadItem.MessageItem(it) } +
                ThreadItem.SessionBoundary("session", "successor", BoundaryReason.Clear, time, null) +
                messages.drop(3).map { ThreadItem.MessageItem(it) }
        val displayed = mutableStateOf(items)
        rule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    ThreadUiState("channel", "test", hasMessages = true, items = displayed.value),
                    {},
                    {},
                    ConnectionState.Connected,
                    {},
                )
            }
        }
        assertRenderedSwitchHistory(rule, messages, expected)
        rule.runOnIdle { displayed.value = items.reversed() }
        assertThrows(AssertionError::class.java) { assertRenderedSwitchHistory(rule, messages, expected) }
    }
}
