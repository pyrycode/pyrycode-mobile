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
 * Layer 1b (#472): tool-step rows driven through the real [RemoteConversationRepository] correlation
 * fold (#387) and rendered by [ToolCallRow]'s status icon (#388) on the [ScriptedThreadHarness]. The
 * component-level twin of the emulator scenario #455. Assertions are **tolerant** (status
 * content-descriptions + the verbatim tool name, generous `waitUntil` timeouts), never on counts or
 * timing — the `docs/e2e-interactive-stream.md` ladder-doc rule. Runs under
 * `./gradlew connectedAndroidTest` (device/emulator required), alongside [ScriptedThreadRenderTest].
 */
@RunWith(AndroidJUnit4::class)
class ScriptedToolRowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var harness: ScriptedThreadHarness

    private val runningDescription: String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getString(R.string.cd_tool_running)

    private val failedDescription: String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getString(R.string.cd_tool_failed)

    @Before
    fun setUp() {
        harness = ScriptedThreadHarness(composeRule)
        harness.start()
    }

    @After
    fun tearDown() {
        harness.close()
    }

    // Running → done: push tool_use → assert the running spinner is shown WHILE the turn is in flight
    // (the result is withheld, so there is no race to done); only then push a non-error tool_result →
    // the same row flips to done. Done has no positive content-description (ToolCallRow.kt:148-154), so
    // it is asserted as the absence triad: running CD gone, failed CD absent, tool name still shown.
    // The tool_use's toolUseId MUST equal the tool_result's, or the fold drops the result and the row
    // never resolves. (AC #1, #2, #4)
    @Test
    fun toolStep_runningWhileInFlight_thenDoneOnResult() {
        harness.pushToolUse(turnId = "t1", toolUseId = "tu1", name = "Bash", inputSummary = "ls -la")

        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            composeRule
                .onAllNodesWithContentDescription(runningDescription)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        // In-flight running assertion (AC #1 + AC #4): the result has not been pushed yet.
        composeRule.onNodeWithContentDescription(runningDescription).assertIsDisplayed()
        composeRule.onNodeWithText("Bash", substring = true).assertIsDisplayed()

        harness.pushToolResult(turnId = "t1", toolUseId = "tu1", isError = false, resultSummary = "files")

        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            composeRule
                .onAllNodesWithContentDescription(runningDescription)
                .fetchSemanticsNodes()
                .isEmpty()
        }
        // Done triad: running gone (just waited on), failed absent, row resolved in place (name shown).
        composeRule.onNodeWithContentDescription(failedDescription).assertDoesNotExist()
        composeRule.onNodeWithText("Bash", substring = true).assertIsDisplayed()
    }

    // Failed: push tool_use → error tool_result back-to-back. Failed is a stable terminal state (it does
    // not auto-resolve), so no held-open fence is needed; assert only the terminal failed CD + the tool
    // name. Distinct toolUseId from the running→done test keeps the two scripts independently readable.
    // (AC #3)
    @Test
    fun toolStep_errorResult_rendersFailed() {
        harness.pushToolUse(turnId = "t1", toolUseId = "tuf", name = "Bash", inputSummary = "false")
        harness.pushToolResult(turnId = "t1", toolUseId = "tuf", isError = true, resultSummary = "exit 1")

        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            composeRule
                .onAllNodesWithContentDescription(failedDescription)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(failedDescription).assertIsDisplayed()
        composeRule.onNodeWithText("Bash", substring = true).assertIsDisplayed()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
