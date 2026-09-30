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
 * Rung 2 (#872): Reset session's phase driven through the real [RemoteConversationRepository]
 * `resetting` projection (#871) and rendered by `ResettingIndicator` in the thread status slot on the
 * [ScriptedThreadHarness]. Live behaviour on a real reset is #679's.
 *
 * Assertions are on the resolved label strings via `onNodeWithContentDescription` (the
 * [ScriptedCompactingTest] idiom), with `assertDoesNotExist` on the other labels for every one-slot claim.
 * Tolerant `waitUntil` timeouts, never counts or timing — the ladder-doc rule.
 */
@RunWith(AndroidJUnit4::class)
class ScriptedResettingTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var harness: ScriptedThreadHarness

    private val thinkingDescription: String = string(R.string.cd_thread_thinking)
    private val compactingDescription: String = string(R.string.cd_thread_compacting)
    private val wrappingUp: String = string(R.string.thread_resetting_wrapping_up)
    private val restartingWritten: String = string(R.string.thread_resetting_restarting_written)
    private val restartingSkipped: String = string(R.string.thread_resetting_restarting_skipped)

    @Before
    fun setUp() {
        harness = ScriptedThreadHarness(composeRule)
        harness.start()
    }

    @After
    fun tearDown() {
        harness.close()
    }

    // AC #1 + #3: the three-frame sequence — wrapping up, then restarting with the written outcome in the
    // same slot (the wrapping-up label is gone, not stacked), then the falling edge clears the arm.
    @Test
    fun threeFrameSequence_changesPhaseInOneSlot_thenClears() {
        harness.pushResetting(active = true, phase = "wrapping_up", handoff = "pending")
        awaitDisplayed(wrappingUp)

        harness.pushResetting(active = true, phase = "restarting", handoff = "written")
        awaitDisplayed(restartingWritten)
        composeRule.onNodeWithContentDescription(wrappingUp).assertDoesNotExist()

        harness.pushResetting(active = false)
        awaitGone(restartingWritten)
        composeRule.onNodeWithContentDescription(wrappingUp).assertDoesNotExist()
    }

    // AC #1: a restart without a handoff note says so.
    @Test
    fun restartingSkipped_showsTheSkippedOutcome() {
        harness.pushResetting(active = true, phase = "wrapping_up", handoff = "pending")
        awaitDisplayed(wrappingUp)

        harness.pushResetting(active = true, phase = "restarting", handoff = "skipped")

        awaitDisplayed(restartingSkipped)
        composeRule.onNodeWithContentDescription(wrappingUp).assertDoesNotExist()
    }

    // AC #2: the conversation's session transition clears the arm even without a falling edge.
    @Test
    fun sessionTransition_clearsTheArm() {
        harness.pushResetting(active = true, phase = "restarting", handoff = "written")
        awaitDisplayed(restartingWritten)

        harness.pushSessionTransition(previousSessionId = "s1", newSessionId = "s2", reason = "clear")

        awaitGone(restartingWritten)
    }

    // AC #2: a reset on another conversation never shows here.
    @Test
    fun resetElsewhere_neverShowsHere() {
        harness.pushResetting(active = true, phase = "wrapping_up", handoff = "pending", targetConversationId = "c-other")
        // An ordered barrier: the repository's single inbound collector handles frames in order, so once this
        // conversation's compaction renders, the other conversation's reset frame has been processed too.
        // Compaction ranks below resetting, so it could not render had that reset reached this screen.
        harness.pushCompacting(active = true)

        awaitDisplayed(compactingDescription)
        composeRule.onNodeWithContentDescription(wrappingUp).assertDoesNotExist()
    }

    // AC #1 ladder: resetting outranks thinking and compaction, since the wrap-up is itself a claude turn.
    @Test
    fun resetting_winsOverThinkingAndCompaction() {
        harness.pushTurnState("thinking")
        harness.pushCompacting(active = true)
        awaitDisplayed(compactingDescription)

        harness.pushResetting(active = true, phase = "wrapping_up", handoff = "pending")

        awaitDisplayed(wrappingUp)
        composeRule.onNodeWithContentDescription(compactingDescription).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(thinkingDescription).assertDoesNotExist()
    }

    // #1311 reordered the ladder to desktop's: the reset the user started outranks a retry inside it.
    @Test
    fun resetting_winsOverApiRetry() {
        harness.pushResetting(active = true, phase = "wrapping_up", handoff = "pending")
        awaitDisplayed(wrappingUp)

        harness.pushApiRetry(active = true, current = 3, total = 10)
        composeRule.waitForIdle()

        awaitDisplayed(wrappingUp)
        composeRule.onNodeWithContentDescription(string(R.string.cd_thread_api_retry, 3, 10)).assertDoesNotExist()

        harness.pushResetting(active = false)
        awaitDisplayed(string(R.string.cd_thread_api_retry, 3, 10))
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
        composeRule.onNodeWithContentDescription(description).assertDoesNotExist()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
