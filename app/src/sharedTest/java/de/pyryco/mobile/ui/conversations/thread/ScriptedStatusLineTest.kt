package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.theme.errorLight
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Rung 2 (#1311): the status band keeps a reading for the whole running turn, driven through the real
 * repository fold on the [ScriptedThreadHarness]. Every step asserts the one label it expects and the
 * absence of every other turn label, so a band that goes dark or stacks two readings fails.
 */
@RunWith(AndroidJUnit4::class)
class ScriptedStatusLineTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var harness: ScriptedThreadHarness

    private val thinking = string(R.string.cd_thread_thinking)
    private val working = string(R.string.cd_thread_working)
    private val stalled = string(R.string.cd_thread_stalled)
    private val runningBash = string(R.string.cd_thread_tool_running, "Bash")
    private val runningBashElapsed = string(R.string.cd_thread_tool_running_elapsed, "Bash", "1m 05s")
    private val runningWrite = string(R.string.cd_thread_tool_running, "Write")
    private val compacting = string(R.string.cd_thread_compacting)
    private val retrying = string(R.string.cd_thread_api_retry, 2, 10)
    private val wrappingUp = string(R.string.thread_resetting_wrapping_up)

    private val turnLabels = listOf(thinking, working, stalled, runningBash, runningBashElapsed, runningWrite)

    @Before
    fun setUp() {
        harness = ScriptedThreadHarness(composeRule)
        harness.start()
    }

    @After
    fun tearDown() {
        harness.close()
    }

    // AC1: thinking; responding with text; tool_use; tool_progress; tool_result; responding again; a denied
    // call; idle — the label at every step.
    @Test
    fun aRunningTurn_alwaysHasAReading_untilItGoesIdle() {
        harness.pushTurnState("thinking")
        awaitOnly(thinking)

        harness.pushTurnState("responding")
        harness.pushAssistantDelta(turnId = "t1", seq = 0, text = "Let me check")
        harness.pushAssistantDelta(turnId = "t1", seq = 1, text = " that.")
        awaitOnly(working)

        harness.pushToolUse(turnId = "t1", toolUseId = "tu1", name = "Bash", inputSummary = "ls")
        awaitOnly(runningBash)

        harness.pushToolProgress(turnId = "t1", toolUseId = "tu1", elapsedSeconds = 65)
        awaitOnly(runningBashElapsed)

        harness.pushToolResult(turnId = "t1", toolUseId = "tu1", isError = false, resultSummary = "ok")
        awaitOnly(working)

        harness.pushTurnState("responding")
        harness.pushAssistantDelta(turnId = "t1", seq = 2, text = " Now writing.")
        awaitOnly(working)

        harness.pushToolUse(turnId = "t1", toolUseId = "tu2", name = "Write", inputSummary = "notes.md")
        awaitOnly(runningWrite)
        harness.pushToolDenied(turnId = "t1", toolUseId = "tu2", toolName = "Write")
        awaitOnly(working)

        harness.pushTurnState("idle")
        awaitOnly(null)
    }

    // AC3 + AC4: a stall pre-empts the running tool in the error colour; Reset, api-retry and compaction
    // outrank it; the next live event clears it.
    @Test
    fun aStall_preemptsTheTurn_yieldsToResetRetryAndCompaction_andClearsOnActivity() {
        harness.pushTurnState("thinking")
        harness.pushToolUse(turnId = "t1", toolUseId = "tu1", name = "Bash", inputSummary = "sleep 600")
        awaitOnly(runningBash)

        harness.pushStall()
        awaitOnly(stalled)
        assertEquals(errorLight, labelColor(string(R.string.thread_stalled_label)))

        harness.pushApiRetry(active = true, current = 2, total = 10)
        awaitDisplayed(retrying)
        assertTurnLabelsAbsent()
        harness.pushApiRetry(active = false, current = 2, total = 10)
        awaitOnly(stalled)

        harness.pushCompacting(active = true)
        awaitDisplayed(compacting)
        assertTurnLabelsAbsent()
        harness.pushCompacting(active = false)
        awaitOnly(stalled)

        harness.pushResetting(active = true, phase = "wrapping_up", handoff = "pending")
        awaitDisplayed(wrappingUp)
        assertTurnLabelsAbsent()
        harness.pushResetting(active = false)
        awaitOnly(stalled)

        // Forward progress: any live-session event ends the stall, and the open call shows again.
        harness.pushAssistantDelta(turnId = "t1", seq = 0, text = "still here")
        awaitOnly(runningBash)
    }

    // AC4: Reset session and api-retry live together show the Reset reading.
    @Test
    fun resetAndApiRetryTogether_showTheReset() {
        harness.pushApiRetry(active = true, current = 2, total = 10)
        awaitDisplayed(retrying)

        harness.pushResetting(active = true, phase = "wrapping_up", handoff = "pending")

        awaitDisplayed(wrappingUp)
        composeRule.onNodeWithContentDescription(retrying).assertDoesNotExist()
    }

    /** Waits for [expected] (or, when null, for no turn label at all), then checks no other label shows. */
    private fun awaitOnly(expected: String?) {
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            turnLabels.all { label -> present(label) == (label == expected) }
        }
    }

    private fun assertTurnLabelsAbsent() {
        turnLabels.forEach { composeRule.onNodeWithContentDescription(it).assertDoesNotExist() }
    }

    private fun awaitDisplayed(description: String) {
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) { present(description) }
    }

    private fun present(description: String): Boolean =
        composeRule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()

    private fun labelColor(text: String) =
        mutableListOf<TextLayoutResult>()
            .also { layouts ->
                composeRule
                    .onNode(hasText(text), useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            }.single()
            .layoutInput.style.color

    private fun string(
        id: Int,
        vararg formatArgs: Any,
    ): String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getString(id, *formatArgs)

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
