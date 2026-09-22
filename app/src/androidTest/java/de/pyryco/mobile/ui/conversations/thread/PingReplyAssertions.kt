package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import de.pyryco.mobile.ui.conversations.components.MESSAGE_BUBBLE_TEST_TAG

internal const val PING_PROMPT = "Reply with exactly the word: ping (nothing else)."

// For fresh PING_PROMPT discussions only: the sent prompt is not exactly "ping", and a reply is
// content the daemon delivered, which on this screen means a message bubble.
//
// The anchor is `MessageContainer`'s bubble tag rather than "has a scrollable ancestor" (#694's
// original second clause). That clause encoded a placement — the title and the foot-of-list queued
// backlog sat outside ThreadScreen's scrollable message list — and #782 retired it: `foldQueuedRows`
// draws a queued row inline among the delivered rows, so a queued entry whose text is exactly "ping"
// now does have a scroll ancestor. Anchoring on the bubble keeps #694's actual intent — reply
// detection is independent of queued text — and is stronger than the placement it replaces, because a
// queued row renders its own Surface and never a message bubble, as does the app bar title. Both
// roles reach the tag through `MessageContainer`, so an assistant reply still matches.
internal fun pingReplyMatcher(): SemanticsMatcher =
    hasText("ping", ignoreCase = true) and hasAnyAncestor(hasTestTag(MESSAGE_BUBBLE_TEST_TAG))

internal fun ComposeTestRule.awaitDisplayedPingReply(timeoutMillis: Long) {
    val reply = onNode(pingReplyMatcher(), useUnmergedTree = true)
    waitUntil(timeoutMillis) { reply.isDisplayed() }
    reply.assertIsDisplayed()
}
