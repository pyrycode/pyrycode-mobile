package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
 * Rung 2 (#594): claude's API-retry status driven through the real [RemoteConversationRepository]
 * `api_retry` projection (#593) and rendered by `ApiRetryIndicator` on the [ScriptedThreadHarness].
 * Rungs 3 and 4 cannot cover this — the daemon emits `api_retry` only from the PTY-runner detector
 * family, and production (rung 3) plus `fakeclaude` (rung 4) both run the stream-json interactive
 * runner, which has no emitter (see `docs/e2e-interactive-stream.md` § Follow-ups → Coverage).
 *
 * Assertions are on the resolved `cd_*` strings via `onNodeWithContentDescription` (the
 * [ThinkingIndicatorTest] idiom), with `assertDoesNotExist` on the *other* status for every
 * mutual-exclusion claim. Tolerant `waitUntil` timeouts, never counts or timing — the ladder-doc rule.
 * Runs under `./gradlew connectedAndroidTest` (device/emulator required).
 */
@RunWith(AndroidJUnit4::class)
class ScriptedApiRetryTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var harness: ScriptedThreadHarness

    private val thinkingDescription: String = string(R.string.cd_thread_thinking)

    private val counterlessDescription: String = string(R.string.cd_thread_api_retry_unknown)

    @Before
    fun setUp() {
        harness = ScriptedThreadHarness(composeRule)
        harness.start()
    }

    @After
    fun tearDown() {
        harness.close()
    }

    // AC #1: a parsed counter replaces the generic thinking label — the two never render stacked.
    @Test
    fun parsedCounter_replacesTheThinkingLabel() {
        harness.pushTurnState("thinking")
        awaitDisplayed(thinkingDescription)

        harness.pushApiRetry(active = true, current = 3, total = 10)

        awaitDisplayed(retryDescription(3, 10))
        composeRule.onNodeWithContentDescription(thinkingDescription).assertDoesNotExist()
    }

    // AC #1: the status is conversation-level, not turn-scoped — it shows even past the thinking phase.
    @Test
    fun retryShows_whileTheTurnStateIsIdle() {
        harness.pushTurnState("idle")
        harness.pushApiRetry(active = true, current = 3, total = 10)

        awaitDisplayed(retryDescription(3, 10))
        composeRule.onNodeWithContentDescription(thinkingDescription).assertDoesNotExist()
    }

    // AC #1: a climbed counter is a fresh emission all the way to the screen — proves no dedup,
    // memoisation, or `remember`-frozen label was introduced on this side (#593's `Attempt` equality
    // is what makes the upstream `distinctUntilChanged` pass the climb through).
    @Test
    fun climbedCounter_reRendersWithTheNewCount() {
        harness.pushApiRetry(active = true, current = 3, total = 10)
        awaitDisplayed(retryDescription(3, 10))

        harness.pushApiRetry(active = true, current = 4, total = 10)

        awaitDisplayed(retryDescription(4, 10))
        composeRule.onNodeWithContentDescription(retryDescription(3, 10)).assertDoesNotExist()
    }

    // AC #2: the documented unparsed counter. #593's mapper already folds `{0, 0}` to AttemptUnknown,
    // so this proves the whole path renders the counter-less status rather than "0/0".
    @Test
    fun unparsedCounter_rendersTheCounterlessStatus() {
        harness.pushApiRetry(active = true, current = 0, total = 0)

        awaitDisplayed(counterlessDescription)
        assertNoTextContaining("0/0")
    }

    // AC #2: an incoherent pair (current > total) is declined by the display gate, not clamped.
    @Test
    fun incoherentCounter_rendersTheCounterlessStatus() {
        harness.pushApiRetry(active = true, current = 9, total = 3)

        awaitDisplayed(counterlessDescription)
        assertNoTextContaining("9/3")
    }

    // AC #2: a counter beyond any plausible retry budget is declined — the layout-breaking case.
    @Test
    fun absurdCounter_rendersTheCounterlessStatus() {
        harness.pushApiRetry(active = true, current = 1, total = Int.MAX_VALUE)

        awaitDisplayed(counterlessDescription)
        assertNoTextContaining("2147483647")
    }

    // AC #3: the falling edge reverts to whatever the turn state says. Scripted with the stale counter
    // the wire actually carries on clear, proving #593's discard holds all the way to the screen.
    @Test
    fun clearedRetry_revertsToTheThinkingAffordance() {
        harness.pushTurnState("thinking")
        harness.pushApiRetry(active = true, current = 3, total = 10)
        awaitDisplayed(retryDescription(3, 10))

        harness.pushApiRetry(active = false, current = 3, total = 10)

        awaitDisplayed(thinkingDescription)
        composeRule.onNodeWithContentDescription(retryDescription(3, 10)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(counterlessDescription).assertDoesNotExist()
    }

    // AC #3: cleared while the turn is idle — neither status remains; nothing sticks on "Retrying".
    @Test
    fun clearedRetry_whileIdle_leavesNoStatus() {
        harness.pushApiRetry(active = true, current = 3, total = 10)
        awaitDisplayed(retryDescription(3, 10))
        harness.pushTurnState("idle")

        harness.pushApiRetry(active = false, current = 3, total = 10)

        awaitGone(retryDescription(3, 10))
        composeRule.onNodeWithContentDescription(counterlessDescription).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(thinkingDescription).assertDoesNotExist()
    }

    // AC #4: a conversation that never sees an `api_retry` frame renders exactly as it does today.
    @Test
    fun withoutAnyRetryFrame_theThinkingAffordanceIsUnchanged() {
        harness.pushTurnState("thinking")

        awaitDisplayed(thinkingDescription)
        composeRule.onNodeWithContentDescription(counterlessDescription).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(retryDescription(3, 10)).assertDoesNotExist()
    }

    private fun retryDescription(
        current: Int,
        total: Int,
    ): String = string(R.string.cd_thread_api_retry, current, total)

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

    /** Searched on the **unmerged** tree so the raw label `Text` cannot hide behind the merged node. */
    private fun assertNoTextContaining(fragment: String) {
        composeRule
            .onNodeWithText(fragment, substring = true, useUnmergedTree = true)
            .assertDoesNotExist()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
