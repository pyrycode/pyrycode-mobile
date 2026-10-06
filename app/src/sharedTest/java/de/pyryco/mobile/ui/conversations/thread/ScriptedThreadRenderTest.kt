package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.SegmentDelta
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.e2e.assertOrderedReplay
import de.pyryco.mobile.e2e.hasCompletedReplay
import de.pyryco.mobile.e2e.renderedReplyText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Layer 1a (#432): the two render cases that ride [ScriptedThreadHarness] — a scripted structured-event
 * stream driven through the real repository fold and rendered by [ThreadScreen]. Assertions are
 * **tolerant** (substring / presence, generous `waitUntil` timeouts), never on delta counts or timing —
 * the `docs/e2e-interactive-stream.md` ladder-doc rule. Runs under `./gradlew connectedAndroidTest`
 * (device/emulator required), alongside `ThinkingIndicatorTest`.
 */
@RunWith(AndroidJUnit4::class)
class ScriptedThreadRenderTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var harness: ScriptedThreadHarness

    private val thinkingDescription: String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getString(R.string.cd_thread_thinking)

    private val interruptDescription: String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getString(R.string.cd_thread_interrupt)

    @Before
    fun setUp() {
        harness = ScriptedThreadHarness(composeRule)
        harness.start()
    }

    @After
    fun tearDown() {
        harness.close()
    }

    // Text case: several incremental deltas for one turn, then turn_end → one assistant message whose
    // content is the deltas concatenated in arrival order, shown finalized (no streaming caret). Plain
    // alphanumeric text so MarkdownText's rendered output matches the raw substring 1:1. Assert only
    // after turn_end: the streaming body reveals progressively and carries the caret, so the full string
    // is reliably present (and caret-free) only once the turn finalizes.
    @Test
    fun text_deltasConcatenateAndFinalizeOnTurnEnd() {
        harness.pushAssistantDelta("t1", 0, "hel")
        harness.pushAssistantDelta("t1", 1, "lo ")
        harness.pushAssistantDelta("t1", 2, "world")
        harness.pushTurnEnd("t1")

        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            composeRule
                .onAllNodesWithText("hello world", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("hello world", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(STREAMING_CARET, substring = true).assertDoesNotExist()
    }

    // Reproduce the failed replay-order history shape without a host daemon or emulator.
    @Test
    fun replayReply_interleavedUserEcho_retainsVisibleOrderAcrossSegments() {
        pushOrderedReplay(split = true)
        val rows = replayRows()
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            composeRule.renderedReplyText(ORDERED_REPLY) == ORDERED_REPLY
        }
        composeRule.onNodeWithText("alpha bravo", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("charlie", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(ORDERED_REPLY, substring = true).assertDoesNotExist()
        composeRule.assertOrderedReplay(rows, ORDERED_REPLY, REPLAY_PROMPT)
    }

    @Test
    fun replayReply_interleavedUserEcho_repositoryWaitAcceptsSegments() {
        pushOrderedReplay(split = true)
        assertTrue(replayRows().hasCompletedReplay(ORDERED_REPLY))
    }

    @Test
    fun replayReply_unsplit_acceptsCompleteDeviceAssertions() {
        pushOrderedReplay(split = false)
        val rows = replayRows()
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            composeRule.renderedReplyText(ORDERED_REPLY) == ORDERED_REPLY
        }
        assertTrue(rows.hasCompletedReplay(ORDERED_REPLY))
        composeRule.assertOrderedReplay(rows, ORDERED_REPLY, REPLAY_PROMPT)
    }

    private fun pushOrderedReplay(split: Boolean) {
        if (!split) pushReplayUser()
        harness.pushAssistantDelta("t1", 0, "alpha ")
        harness.pushAssistantDelta("t1", 1, "bravo ")
        if (split) pushReplayUser()
        harness.pushAssistantDelta("t1", 2, "charlie")
        harness.pushTurnEnd("t1")
    }

    private fun pushReplayUser() {
        harness.pushEnvelope(
            Envelope(
                id = 3L,
                type = "message",
                ts = "2026-10-06T10:00:00Z",
                payload =
                    MobileJson.parseToJsonElement(
                        """{"conversation_id":"c1","message_id":"echo","role":"user","text":"$REPLAY_PROMPT"}""",
                    ),
            ),
        )
    }

    @Test
    fun replayReply_duplicateTextInOneBubble_isRejected() = assertIncorrectReplay("alpha alpha bravo charlie")

    @Test
    fun replayReply_reorderedText_isRejected() = assertIncorrectReplay("bravo alpha charlie")

    @Test
    fun replayReply_missingText_isRejected() = assertIncorrectReplay("alpha charlie")

    private fun assertIncorrectReplay(text: String) {
        pushReplayUser()
        harness.pushAssistantDelta("t1", 0, text)
        harness.pushTurnEnd("t1")
        val rows = replayRows(text)
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            composeRule.renderedReplyText(ORDERED_REPLY) == text
        }
        assertFalse(rows.hasCompletedReplay(ORDERED_REPLY))
        assertThrows(AssertionError::class.java) {
            composeRule.assertOrderedReplay(rows, ORDERED_REPLY, REPLAY_PROMPT)
        }
    }

    @Test
    fun replayReply_correctTextWithMissingSequence_isRejected() = assertIncorrectSequences(listOf(0, 2))

    @Test
    fun replayReply_correctTextWithReorderedSequences_isRejected() = assertIncorrectSequences(listOf(1, 0, 2))

    @Test
    fun replayReply_correctTextWithDuplicateSequence_isRejected() = assertIncorrectSequences(listOf(0, 1, 1, 2))

    private fun assertIncorrectSequences(sequences: List<Int>) {
        assertIncorrectRows { rows ->
            rows.map { row ->
                if (row is ThreadItem.MessageItem && row.message.role == Role.Assistant) {
                    val segment = requireNotNull(row.message.segment)
                    row.copy(message = row.message.copy(segment = segment.copy(deltas = sequences.map { SegmentDelta(it, 0) })))
                } else {
                    row
                }
            }
        }
    }

    @Test
    fun replayReply_duplicateRetainedSegment_isRejected() =
        assertIncorrectRows { rows -> rows + rows.filterIsInstance<ThreadItem.MessageItem>().last { it.message.role == Role.Assistant } }

    @Test
    fun replayReply_userAfterCompleteReply_isRejected() =
        assertIncorrectRows { rows ->
            val user = rows.filterIsInstance<ThreadItem.MessageItem>().single { it.message.role == Role.User }
            rows.filterNot { it == user } + user
        }

    private fun assertIncorrectRows(change: (List<ThreadItem>) -> List<ThreadItem>) {
        pushOrderedReplay(split = false)
        val rows = replayRows()
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            composeRule.renderedReplyText(ORDERED_REPLY) == ORDERED_REPLY
        }
        assertThrows(AssertionError::class.java) {
            composeRule.assertOrderedReplay(change(rows), ORDERED_REPLY, REPLAY_PROMPT)
        }
    }

    private fun replayRows(text: String = ORDERED_REPLY): List<ThreadItem> =
        runBlocking {
            withTimeout(TIMEOUT_MS) {
                harness.observeMessages().first { items ->
                    val replies = items.filterIsInstance<ThreadItem.MessageItem>().filter { it.message.role == Role.Assistant }
                    replies.isNotEmpty() &&
                        replies.all { !it.message.isStreaming } &&
                        replies.joinToString("") { it.message.content } == text
                }
            }
        }

    // Spinner case: open a turn (thinking) → the thinking indicator shows; end it → the indicator is
    // gone. isThinking tracks the `thinking` phase only (thinkingTransition: responding/idle/turn_end →
    // false), so the turn is opened with `thinking` specifically.
    @Test
    fun spinner_shownWhileThinking_goneAfterTurnEnd() {
        harness.pushTurnState("thinking")
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            composeRule
                .onAllNodesWithContentDescription(thinkingDescription)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(thinkingDescription).assertIsDisplayed()

        harness.pushTurnEnd("t1")
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            composeRule
                .onAllNodesWithContentDescription(thinkingDescription)
                .fetchSemanticsNodes()
                .isEmpty()
        }
        composeRule.onNodeWithContentDescription(thinkingDescription).assertDoesNotExist()
    }

    // Stop stays visible across thinking/responding and after the tap until the daemon ends the turn.
    @Test
    fun interrupt_shownWhileBusy_invokesOnTap_goneAfterTurnEnd() {
        composeRule.onNodeWithContentDescription(interruptDescription).assertDoesNotExist()
        harness.pushTurnState("thinking")
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            composeRule.onAllNodesWithContentDescription(interruptDescription).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(interruptDescription).assertIsDisplayed()
        harness.pushTurnState("responding")
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            composeRule
                .onAllNodesWithContentDescription(thinkingDescription)
                .fetchSemanticsNodes()
                .isEmpty()
        }
        composeRule.onNodeWithContentDescription(interruptDescription).assertIsDisplayed()

        composeRule.onNodeWithContentDescription(interruptDescription).performClick()
        composeRule.waitForIdle()
        assertEquals(1, harness.interruptInvocations())
        assertEquals(listOf("c1"), harness.interruptedConversations())
        composeRule.onNodeWithContentDescription(interruptDescription).assertIsDisplayed()

        harness.pushTurnEnd("t1")
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            composeRule
                .onAllNodesWithContentDescription(interruptDescription)
                .fetchSemanticsNodes()
                .isEmpty()
        }
        composeRule.onNodeWithContentDescription(interruptDescription).assertDoesNotExist()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val ORDERED_REPLY = "alpha bravo charlie"
        const val REPLAY_PROMPT = "hello"

        // MessageBubble.STREAMING_CARET_GLYPH — the caret present only on a streaming (non-finalized) row.
        const val STREAMING_CARET = "▎"
    }
}
