package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule

internal const val PING_PROMPT = "Reply with exactly the word: ping (nothing else)."

// For fresh PING_PROMPT discussions only: the sent prompt is not exactly "ping", and
// the title/backlog sit outside ThreadScreen's scrollable message list.
internal fun pingReplyMatcher(): SemanticsMatcher = hasText("ping", ignoreCase = true) and hasAnyAncestor(hasScrollAction())

internal fun ComposeTestRule.awaitDisplayedPingReply(timeoutMillis: Long) {
    val reply = onNode(pingReplyMatcher(), useUnmergedTree = true)
    waitUntil(timeoutMillis) { reply.isDisplayed() }
    reply.assertIsDisplayed()
}
