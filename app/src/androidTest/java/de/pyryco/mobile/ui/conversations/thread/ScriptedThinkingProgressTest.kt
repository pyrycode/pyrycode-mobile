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
 * Rung 2 (#803): claude's mid-turn token reading driven through the real
 * [de.pyryco.mobile.data.repository.RemoteConversationRepository] `thinking_progress` projection (#801)
 * and rendered by `ThinkingIndicator` on the [ScriptedThreadHarness]. Live behaviour is #679's; rung 4
 * has no scripted emitter for this frame, so rung 2 is the integrated coverage this slice owns.
 *
 * Assertions are on the resolved `cd_*` strings via `onNodeWithContentDescription` (the
 * [ScriptedCompactingTest] idiom), with `assertDoesNotExist` on the *other* status for every
 * mutual-exclusion claim. Tolerant `waitUntil` timeouts, never counts or timing — the ladder-doc rule.
 * Runs under `./gradlew connectedAndroidTest` (device/emulator required).
 */
@RunWith(AndroidJUnit4::class)
class ScriptedThinkingProgressTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var harness: ScriptedThreadHarness

    private val thinkingDescription: String = string(R.string.cd_thread_thinking)

    private val compactingDescription: String = string(R.string.cd_thread_compacting)

    private val apiRetryDescription: String = string(R.string.cd_thread_api_retry, 3, 10)

    @Before
    fun setUp() {
        harness = ScriptedThreadHarness(composeRule)
        harness.start()
    }

    @After
    fun tearDown() {
        harness.close()
    }

    // AC #1: a live reading decorates the thinking arm, replacing the counter-less label in place. The
    // two presentations are never on screen together.
    @Test
    fun aLiveReading_decoratesTheThinkingArm() {
        harness.pushTurnState("thinking")
        awaitDisplayed(thinkingDescription)

        harness.pushThinkingProgress(estimatedTokens = 184)

        awaitDisplayed(progressDescription(184))
        composeRule.onNodeWithContentDescription(thinkingDescription).assertDoesNotExist()
    }

    // AC #2: `estimated_tokens` restarts near zero at every inference-request boundary, repeatedly inside
    // one turn, so a falling reading is a real reading and the label follows it down. The negative
    // control is the stale value — a running maximum would leave 184 standing.
    @Test
    fun aFallingReading_updatesTheLabel() {
        harness.pushTurnState("thinking")
        harness.pushThinkingProgress(estimatedTokens = 184)
        awaitDisplayed(progressDescription(184))

        harness.pushThinkingProgress(estimatedTokens = 4, estimatedTokensDelta = 4)

        awaitDisplayed(progressDescription(4))
        composeRule.onNodeWithContentDescription(progressDescription(184)).assertDoesNotExist()
    }

    // AC #2: an identical repeat is dropped by the projection's distinctUntilChanged, so the label holds
    // rather than being rewritten — which is what makes the arm flicker-free under a repeated reading.
    @Test
    fun aRepeatedReading_holds() {
        harness.pushTurnState("thinking")
        harness.pushThinkingProgress(estimatedTokens = 126)
        awaitDisplayed(progressDescription(126))

        harness.pushThinkingProgress(estimatedTokens = 126)

        awaitDisplayed(progressDescription(126))
        composeRule.onNodeWithContentDescription(thinkingDescription).assertDoesNotExist()
    }

    // AC #3: api-retry still wins the slot. The reading rides the thinking arm, which retry pre-empts, so
    // the alarming signal can never be masked by the benign one and the two never stack.
    @Test
    fun apiRetry_stillWinsOverALiveReading() {
        harness.pushTurnState("thinking")
        harness.pushThinkingProgress(estimatedTokens = 184)
        awaitDisplayed(progressDescription(184))

        harness.pushApiRetry(active = true, current = 3, total = 10)

        awaitDisplayed(apiRetryDescription)
        composeRule.onNodeWithContentDescription(progressDescription(184)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(thinkingDescription).assertDoesNotExist()
    }

    // AC #3: compaction still wins the slot over a live reading, for the same reason.
    @Test
    fun compaction_stillWinsOverALiveReading() {
        harness.pushTurnState("thinking")
        harness.pushThinkingProgress(estimatedTokens = 184)
        awaitDisplayed(progressDescription(184))

        harness.pushCompacting(active = true)

        awaitDisplayed(compactingDescription)
        composeRule.onNodeWithContentDescription(progressDescription(184)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(thinkingDescription).assertDoesNotExist()
    }

    // AC #1, second half: a conversation that never receives a `thinking_progress` frame renders exactly
    // as it does today. Absence is "no reading", never a stalled or failed presentation.
    @Test
    fun withoutAnyReading_theThinkingArmIsUnchanged() {
        harness.pushTurnState("thinking")

        awaitDisplayed(thinkingDescription)
        composeRule.onNodeWithContentDescription(progressDescription(184)).assertDoesNotExist()
    }

    private fun string(
        id: Int,
        vararg args: Any,
    ): String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getString(id, *args)

    /** The resolved `cd_thread_thinking_progress` for [tokens] — the arm's description while a reading is live. */
    private fun progressDescription(tokens: Long): String = string(R.string.cd_thread_thinking_progress, tokens)

    private fun awaitDisplayed(description: String) {
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            composeRule
                .onAllNodesWithContentDescription(description)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(description).assertIsDisplayed()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
