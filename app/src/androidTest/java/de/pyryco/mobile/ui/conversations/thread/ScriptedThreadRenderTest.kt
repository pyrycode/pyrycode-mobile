package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import org.junit.After
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

    private companion object {
        const val TIMEOUT_MS = 5_000L

        // MessageBubble.STREAMING_CARET_GLYPH — the caret present only on a streaming (non-finalized) row.
        const val STREAMING_CARET = "▎"
    }
}
