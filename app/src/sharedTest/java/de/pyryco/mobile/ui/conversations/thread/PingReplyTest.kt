package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.QueuedMessage
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.e2e.CompletedReplyObservation
import de.pyryco.mobile.e2e.ThreadEvidence
import de.pyryco.mobile.e2e.ThreadEvidenceKind
import de.pyryco.mobile.e2e.ThreadEvidenceOwner
import de.pyryco.mobile.e2e.ThreadEvidenceRecord
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PingReplyTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun ownedReplyAcceptsOrdinaryProse() {
        showBoundaryFixture(Role.Assistant, "Ping! Here is the reply.")
        assertTrue(composeTestRule.ownedReplyDisplayed(expected("Ping! Here is the reply.")))
    }

    @Test fun ownedReplyAcceptsFormattedOrdinaryProseWithoutWeakeningContentChecks() {
        showBoundaryFixture(Role.Assistant, "**Done.**\n\nHere is `ordinary prose`.")
        assertTrue(composeTestRule.ownedReplyDisplayed(expected("**Done.**\n\nHere is `ordinary prose`.")))
        assertFalse(composeTestRule.ownedReplyDisplayed(expected("**Done.**\n\nHere is `different prose`.")))
    }

    @Test fun ownedReplyAcceptsTableCellsRegardlessOfSemanticsTraversalOrder() {
        val source = "**Done.**\n\n| Key | Value |\n| --- | --- |\n| marker | present |"
        showBoundaryFixture(Role.Assistant, source)
        assertTrue(composeTestRule.ownedReplyDisplayed(expected(source)))
        assertFalse(composeTestRule.ownedReplyDisplayed(expected(source.replace("marker", "missing"))))
    }

    @Test
    fun ownedReplyRejectsUserEcho() {
        showBoundaryFixture(Role.User, "ping")
        assertFalse(composeTestRule.ownedReplyDisplayed(expected("ping")))
    }

    @Test fun ownedReplyRejectsAbsentRendering() {
        syntheticRendering(count = 0)
        assertFalse(composeTestRule.ownedReplyDisplayed(expected("actual content")))
    }

    @Test fun ownedReplyRejectsDuplicateIdentityEvenWithDifferentContent() {
        syntheticRendering(count = 2)
        assertEquals(listOf(2), composeTestRule.ownedReplyNodeCounts(expected("actual content")))
        assertFalse(composeTestRule.ownedReplyDisplayed(expected("actual content")))
    }

    @Test fun ownedReplyRejectsWrongIdentityWithIdenticalText() {
        syntheticRendering(id = "wrong")
        assertFalse(composeTestRule.ownedReplyDisplayed(expected("actual content")))
    }

    @Test fun ownedReplyRejectsWrongContentUnderTheRightIdentity() {
        syntheticRendering()
        assertFalse(composeTestRule.ownedReplyContentMatches(expected("other content")))
        assertFalse(composeTestRule.ownedReplyDisplayed(expected("other content")))
    }

    @Test fun ownedReplyRejectsComposedButOffscreenContent() {
        syntheticRendering(offscreen = true)
        val reply = expected("actual content")
        assertEquals(listOf(1), composeTestRule.ownedReplyNodeCounts(reply))
        assertTrue(composeTestRule.ownedReplyContentMatches(reply))
        assertFalse(composeTestRule.ownedReplyDisplayed(reply))
    }

    @Test fun ownedReplyRequiresEveryFragmentToBeDisplayed() {
        syntheticRendering()
        val first = expected("actual content")
        val missing = expected("second fragment", "missing").records.single()
        val reply =
            CompletedReplyObservation(
                first.owner,
                first.session,
                first.turn,
                "actual contentsecond fragment",
                first.records + missing,
                first.thread,
                first.host,
            )
        assertFalse(composeTestRule.ownedReplyDisplayed(reply))
    }

    @Test fun ownedReplyDisplaysMultipleAdmittedParagraphFragments() {
        val first = expected("PING!\n\nHere is ordinary prose.")
        val second = expected("Another fragment.", "second").records.single()
        val reply =
            CompletedReplyObservation(
                first.owner,
                first.session,
                first.turn,
                first.producerText + second.fullText,
                first.records + second,
                first.thread,
                first.host,
            )
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state =
                        (
                            ThreadUiState(
                                conversationId = "conversation",
                                displayName = "conversation",
                                hasMessages = true,
                                items = reply.records.map { ThreadItem.MessageItem(it.message) },
                            )
                        ).withLiteralRows(),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                )
            }
        }
        assertTrue(composeTestRule.ownedReplyDisplayed(reply))
    }

    private fun syntheticRendering(
        count: Int = 1,
        id: String = "owned",
        offscreen: Boolean = false,
    ) {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                Column {
                    repeat(count) { index ->
                        Column(Modifier.offset(y = if (offscreen) 2000.dp else 0.dp).testTag("assistant-reply:$id")) {
                            Text(if (index == 0) "actual content" else "different duplicate content")
                        }
                    }
                }
            }
        }
    }

    private fun expected(
        text: String,
        id: String = "owned",
    ): CompletedReplyObservation {
        val message = Message(id, "session", Role.Assistant, text, Instant.fromEpochMilliseconds(0), false)
        val owner = ThreadEvidenceOwner("host", "conversation", Any(), ThreadEvidenceKind.Legacy)
        val record = ThreadEvidenceRecord(message, text, "session", "turn", null, null)
        val evidence = ThreadEvidence(owner, listOf(record))
        return CompletedReplyObservation(owner, "session", "turn", text, listOf(record), evidence, "host")
    }

    private fun showBoundaryFixture(
        role: Role,
        text: String,
    ) {
        val message = Message("owned", "session", role, text, Instant.fromEpochMilliseconds(0), false)
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state =
                        (
                            ThreadUiState(
                                conversationId = "conversation",
                                displayName = "conversation",
                                hasMessages = true,
                                items = listOf(ThreadItem.MessageItem(message)),
                            )
                        ).withLiteralRows(),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                )
            }
        }
    }

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
            PyrycodeMobileTheme(darkTheme = true) {
                ThreadScreen(
                    state = (state).withLiteralRows(),
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
