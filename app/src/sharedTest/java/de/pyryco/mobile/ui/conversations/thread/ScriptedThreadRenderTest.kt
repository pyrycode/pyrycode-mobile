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
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.e2e.renderedReplyText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        harness.pushAssistantDelta("t1", 0, "alpha ")
        harness.pushAssistantDelta("t1", 1, "bravo ")
        harness.pushEnvelope(
            Envelope(
                id = 3L,
                type = "message",
                ts = "2026-10-06T10:00:00Z",
                payload =
                    MobileJson.parseToJsonElement(
                        """{"conversation_id":"c1","message_id":"echo","role":"user","text":"hello"}""",
                    ),
            ),
        )
        harness.pushAssistantDelta("t1", 2, "charlie")
        harness.pushTurnEnd("t1")
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            composeRule.renderedReplyText(ORDERED_REPLY) == ORDERED_REPLY
        }
        composeRule.onNodeWithText("alpha bravo", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("charlie", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("alpha bravo charlie", substring = true).assertDoesNotExist()
        assertEquals(ORDERED_REPLY, composeRule.renderedReplyText(ORDERED_REPLY))
    }

    @Test
    fun replayReply_duplicateTextInOneBubble_isRejected() = assertIncorrectReplay("alpha alpha bravo charlie")

    @Test
    fun replayReply_reorderedText_isRejected() = assertIncorrectReplay("bravo alpha charlie")

    @Test
    fun replayReply_missingText_isRejected() = assertIncorrectReplay("alpha charlie")

    private fun assertIncorrectReplay(text: String) {
        harness.pushAssistantDelta("t1", 0, text)
        harness.pushTurnEnd("t1")
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            composeRule.renderedReplyText(ORDERED_REPLY) == text
        }
        assertNotEquals(ORDERED_REPLY, composeRule.renderedReplyText(ORDERED_REPLY))
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

        // MessageBubble.STREAMING_CARET_GLYPH — the caret present only on a streaming (non-finalized) row.
        const val STREAMING_CARET = "▎"
    }
}
