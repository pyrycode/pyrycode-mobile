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
 * Rung 2 (#597): claude's auto-compaction status driven through the real [RemoteConversationRepository]
 * `compacting` projection (#596) and rendered by `CompactingIndicator` on the [ScriptedThreadHarness].
 * Rungs 3 and 4 cannot cover this — the daemon emits `compacting` only from the PTY-runner detector
 * family, and production (rung 3) plus `fakeclaude` (rung 4) both run the stream-json interactive
 * runner, which has no emitter (see `docs/e2e-interactive-stream.md` § Follow-ups → Coverage).
 *
 * Assertions are on the resolved `cd_*` strings via `onNodeWithContentDescription` (the
 * [ScriptedApiRetryTest] idiom), with `assertDoesNotExist` on the *other* status for every
 * mutual-exclusion claim. Tolerant `waitUntil` timeouts, never counts or timing — the ladder-doc rule.
 * Runs under `./gradlew connectedAndroidTest` (device/emulator required).
 */
@RunWith(AndroidJUnit4::class)
class ScriptedCompactingTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var harness: ScriptedThreadHarness

    private val thinkingDescription: String = string(R.string.cd_thread_thinking)

    private val compactingDescription: String = string(R.string.cd_thread_compacting)

    @Before
    fun setUp() {
        harness = ScriptedThreadHarness(composeRule)
        harness.start()
    }

    @After
    fun tearDown() {
        harness.close()
    }

    // AC #1: the rising edge replaces the generic thinking label — the two never render stacked.
    @Test
    fun compacting_replacesTheThinkingLabel() {
        harness.pushTurnState("thinking")
        awaitDisplayed(thinkingDescription)

        harness.pushCompacting(active = true)

        awaitDisplayed(compactingDescription)
        composeRule.onNodeWithContentDescription(thinkingDescription).assertDoesNotExist()
    }

    // AC #1: the status is conversation-level, not turn-scoped — it shows even past the thinking phase.
    @Test
    fun compactingShows_whileTheTurnStateIsIdle() {
        harness.pushTurnState("idle")
        harness.pushCompacting(active = true)

        awaitDisplayed(compactingDescription)
        composeRule.onNodeWithContentDescription(thinkingDescription).assertDoesNotExist()
    }

    // AC #2: the falling edge reverts to whatever the turn state says — the generic thinking affordance
    // while claude is still thinking. Nothing sticks on "Compacting conversation".
    @Test
    fun clearedCompacting_revertsToTheThinkingAffordance() {
        harness.pushTurnState("thinking")
        harness.pushCompacting(active = true)
        awaitDisplayed(compactingDescription)

        harness.pushCompacting(active = false)

        awaitDisplayed(thinkingDescription)
        composeRule.onNodeWithContentDescription(compactingDescription).assertDoesNotExist()
    }

    // AC #2: cleared while the turn is idle — neither status remains; the slot goes empty.
    @Test
    fun clearedCompacting_whileIdle_leavesNoStatus() {
        harness.pushCompacting(active = true)
        awaitDisplayed(compactingDescription)
        harness.pushTurnState("idle")

        harness.pushCompacting(active = false)

        awaitGone(compactingDescription)
        composeRule.onNodeWithContentDescription(thinkingDescription).assertDoesNotExist()
    }

    // AC #3: a conversation that never sees a `compacting` frame renders exactly as it does today.
    @Test
    fun withoutAnyCompactingFrame_theThinkingAffordanceIsUnchanged() {
        harness.pushTurnState("thinking")

        awaitDisplayed(thinkingDescription)
        composeRule.onNodeWithContentDescription(compactingDescription).assertDoesNotExist()
    }

    private fun string(id: Int): String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getString(id)

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
