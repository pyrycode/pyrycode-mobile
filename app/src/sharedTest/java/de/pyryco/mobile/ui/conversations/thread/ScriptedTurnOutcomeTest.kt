package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Rung 2 (#805): a failed or interrupted turn's outcome, decoded from `turn_end` by the real
 * [de.pyryco.mobile.data.repository.RemoteConversationRepository] and rendered by `TurnOutcomeIndicator`
 * on the [ScriptedThreadHarness], in [ScriptedUsageLimitTest]'s shape. Live behaviour is #679's.
 *
 * Assertions are on the row's content description, which is its visible label. Where a scenario proves an
 * **absence**, a later `turn_state thinking` frame is the sync point: frames fold in order on the one
 * inbound collector, and the thinking arm only renders when no higher arm holds the slot.
 */
@RunWith(AndroidJUnit4::class)
class ScriptedTurnOutcomeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var harness: ScriptedThreadHarness

    private val thinkingDescription: String = string(R.string.cd_thread_thinking)

    private val interruptDescription: String = string(R.string.cd_thread_interrupt)

    private val compactingDescription: String = string(R.string.cd_thread_compacting)

    private val failedLabel: String =
        string(R.string.thread_turn_outcome_failed) +
            string(R.string.thread_turn_outcome_agent_reports, string(R.string.agent_name_claude), "prompt_too_long")

    @Before
    fun setUp() {
        harness = ScriptedThreadHarness(composeRule)
        harness.start()
    }

    @After
    fun tearDown() {
        harness.close()
    }

    // AC #2 + #4: `success` with `is_error` is the failure it is, and the turn ending takes the spinner and
    // the Stop affordance with it.
    @Test
    fun successWithIsError_rendersAsAFailure_andClearsSpinnerAndStop() {
        harness.pushTurnState("thinking")
        awaitDisplayed(thinkingDescription)
        awaitDisplayed(interruptDescription)

        harness.pushTurnEnd("t1", outcome = "success", isError = true, terminalReason = "prompt_too_long")

        awaitDisplayed(failedLabel)
        composeRule.onNodeWithContentDescription(thinkingDescription).assertDoesNotExist()
        awaitGone(interruptDescription)
    }

    // AC #2: an interrupted turn says so.
    @Test
    fun cancelledTurn_rendersAsInterrupted() {
        harness.pushTurnState("thinking")
        harness.pushTurnEnd("t1", stopReason = "cancelled")

        awaitDisplayed(string(R.string.thread_turn_outcome_interrupted))
        composeRule.onNodeWithContentDescription(thinkingDescription).assertDoesNotExist()
    }

    // AC #4: the outcome clears when the next turn starts.
    @Test
    fun nextTurnStarting_clearsTheOutcome() {
        harness.pushTurnEnd("t1", outcome = "success", isError = true, terminalReason = "prompt_too_long")
        awaitDisplayed(failedLabel)

        harness.pushTurnState("thinking")

        awaitDisplayed(thinkingDescription)
        composeRule.onNodeWithContentDescription(failedLabel).assertDoesNotExist()
    }

    // AC #1: a clean turn, with or without the stop-shape fields, raises no outcome.
    @Test
    fun cleanTurnEnd_showsNoOutcome() {
        harness.pushTurnEnd("t1")
        harness.pushTurnEnd("t2", outcome = "success", isError = false, terminalReason = "completed", errorCategory = "")
        harness.pushTurnState("thinking")

        awaitDisplayed(thinkingDescription)
        composeRule.onNodeWithContentDescription(string(R.string.thread_turn_outcome_failed), substring = true).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(string(R.string.thread_turn_outcome_stopped), substring = true).assertDoesNotExist()
    }

    // AC #3: terminal escapes, line breaks and bidi overrides reach the composable only as spaces.
    @Test
    fun controlCharacters_renderAsInertText() {
        harness.pushTurnEnd("t1", outcome = "error_\u001b[31mx\n‮y", errorCategory = "billing_error")

        awaitDisplayed(
            string(R.string.thread_turn_outcome_stopped) +
                string(
                    R.string.thread_turn_outcome_agent_reports,
                    string(R.string.agent_name_claude),
                    "error_ [31mx  y, " + string(R.string.thread_turn_outcome_api_error, "billing_error"),
                ),
        )
    }

    // Ladder: compaction outranks the outcome; the two never stack.
    @Test
    fun compaction_winsTheSlotOverTheOutcome() {
        harness.pushTurnEnd("t1", outcome = "success", isError = true, terminalReason = "prompt_too_long")
        awaitDisplayed(failedLabel)

        harness.pushCompacting(active = true)

        awaitDisplayed(compactingDescription)
        composeRule.onNodeWithContentDescription(failedLabel).assertDoesNotExist()
    }

    private fun string(
        id: Int,
        vararg formatArgs: Any,
    ): String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getString(id, *formatArgs)

    private fun awaitDisplayed(description: String) {
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            composeRule
                .onAllNodesWithContentDescription(description)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(description).assertIsDisplayed()
    }

    private fun awaitGone(description: String) {
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            composeRule
                .onAllNodesWithContentDescription(description)
                .fetchSemanticsNodes()
                .isEmpty()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
