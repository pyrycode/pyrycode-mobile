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
 * Rung 2 (#805, reworked by #1357): a stopped turn's recovery advice, decoded from `turn_end` by the real
 * [de.pyryco.mobile.data.repository.RemoteConversationRepository] and rendered by `TurnOutcomeIndicator`
 * on the [ScriptedThreadHarness], in [ScriptedUsageLimitTest]'s shape. Live behaviour is #679's.
 *
 * Assertions are on the pill's content description, which is its visible label. Where a scenario proves an
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

    private val contextNotice: String = string(R.string.thread_recovery_context)

    @Before
    fun setUp() {
        harness = ScriptedThreadHarness(composeRule)
        harness.start()
    }

    @After
    fun tearDown() {
        harness.close()
    }

    // `success` with `is_error` and `prompt_too_long` is a context overflow: the advice and Compact replace
    // the spinner, and the Stop affordance goes with the turn.
    @Test
    fun contextOverflow_offersCompact_andClearsSpinnerAndStop() {
        harness.pushTurnState("thinking")
        awaitDisplayed(thinkingDescription)
        awaitDisplayed(interruptDescription)

        harness.pushTurnEnd("t1", outcome = "success", isError = true, terminalReason = "prompt_too_long")

        awaitDisplayed(contextNotice)
        composeRule.onNodeWithContentDescription(thinkingDescription).assertDoesNotExist()
        awaitGone(interruptDescription)
    }

    // A billing failure names the agent's billing, never the daemon's token.
    @Test
    fun billingFailure_namesTheAgentsBilling() {
        harness.pushTurnEnd("t1", isError = true, errorCategory = "billing_error")

        awaitDisplayed(string(R.string.thread_recovery_billing, string(R.string.agent_name_claude)))
    }

    // The advice clears when the next turn starts.
    @Test
    fun nextTurnStarting_clearsTheNotice() {
        harness.pushTurnEnd("t1", outcome = "success", isError = true, terminalReason = "prompt_too_long")
        awaitDisplayed(contextNotice)

        harness.pushTurnState("thinking")

        awaitDisplayed(thinkingDescription)
        composeRule.onNodeWithContentDescription(contextNotice).assertDoesNotExist()
    }

    // A cancelled turn, a clean one and any other stopped turn give no advice.
    @Test
    fun cancelledCleanAndOtherStoppedTurns_showNothing() {
        harness.pushTurnEnd("t1", stopReason = "cancelled", isError = true, terminalReason = "prompt_too_long")
        harness.pushTurnEnd("t2", outcome = "success", isError = false, terminalReason = "completed", errorCategory = "")
        harness.pushTurnEnd("t3", outcome = "error_max_turns", errorCategory = "\u001b[31mrate_limit")
        harness.pushTurnState("thinking")

        awaitDisplayed(thinkingDescription)
        composeRule.onNodeWithContentDescription(contextNotice).assertDoesNotExist()
    }

    // Compaction remains in the band while the advice stays in the independent overlay.
    @Test
    fun compactionAndNotice_remainVisibleInTheirOwnSurfaces() {
        harness.pushTurnEnd("t1", outcome = "success", isError = true, terminalReason = "prompt_too_long")
        awaitDisplayed(contextNotice)

        harness.pushCompacting(active = true)

        awaitDisplayed(compactingDescription)
        composeRule.onNodeWithContentDescription(contextNotice).assertIsDisplayed()
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
