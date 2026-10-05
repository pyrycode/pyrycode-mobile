package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.repository.ThinkingProgress
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.components.TurnRecoveryNotice
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #897: the status area names the open tool call, and claude's latest elapsed reading for it, in the
 * thinking arm's slot while a turn runs.
 */
@RunWith(AndroidJUnit4::class)
class RunningToolIndicatorTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val thinkingDescription: String = context.getString(R.string.cd_thread_thinking)

    private fun runningDescription(name: String): String = context.getString(R.string.cd_thread_tool_running, name)

    private fun elapsedDescription(
        name: String,
        elapsed: String,
    ): String = context.getString(R.string.cd_thread_tool_running_elapsed, name, elapsed)

    private fun toolRow(
        id: String,
        name: String,
        status: ToolCallStatus,
        elapsedSeconds: Int? = null,
        parentToolUseId: String = "",
    ): ThreadItem.MessageItem =
        ThreadItem.MessageItem(
            Message(
                id = id,
                sessionId = "s1",
                role = Role.Tool,
                content = "",
                timestamp = Instant.parse("2026-09-24T10:00:00Z"),
                isStreaming = false,
                toolCall =
                    ToolCall(
                        toolName = name,
                        input = "",
                        output = "",
                        status = status,
                        elapsedSeconds = elapsedSeconds,
                        parentToolUseId = parentToolUseId,
                    ),
            ),
        )

    private fun stateOf(vararg items: ThreadItem): ThreadUiState =
        ThreadUiState(
            conversationId = "ch_abc123",
            displayName = "Test channel",
            isPromoted = true,
            hasMessages = true,
            items = items.toList(),
        )

    private var state by mutableStateOf(stateOf())
    private var isThinking by mutableStateOf(false)
    private var isBusy by mutableStateOf(true)
    private var progress by mutableStateOf<ThinkingProgress?>(null)
    private var isCompacting by mutableStateOf(false)
    private var turnOutcome by mutableStateOf<TurnRecoveryNotice?>(null)

    private fun setThreadScreen() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = state,
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    isThinking = isThinking,
                    isBusy = isBusy,
                    thinkingProgress = progress,
                    isCompacting = isCompacting,
                    turnOutcome = turnOutcome,
                )
            }
        }
    }

    @Test
    fun openProgressResult_eachStateShows_thenTheBandReturnsToThinking() {
        isThinking = true
        state = stateOf(toolRow("t1", "Bash", ToolCallStatus.Running))
        setThreadScreen()

        // Open, no reading yet: the name alone, and no time.
        composeTestRule.onNodeWithText("Running Bash…").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(runningDescription("Bash")).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(thinkingDescription).assertDoesNotExist()

        // Progress: claude's reading appended in the shared elapsed format.
        state = stateOf(toolRow("t1", "Bash", ToolCallStatus.Running, elapsedSeconds = 65))
        composeTestRule.onNodeWithText("Running Bash… 1m 05s").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(elapsedDescription("Bash", "1m 05s")).assertIsDisplayed()

        // A later reading replaces the earlier one.
        state = stateOf(toolRow("t1", "Bash", ToolCallStatus.Running, elapsedSeconds = 95))
        composeTestRule.onNodeWithText("Running Bash… 1m 35s").assertIsDisplayed()
        composeTestRule.onNodeWithText("Running Bash… 1m 05s").assertDoesNotExist()

        // Result: the call closes and the band returns to what it would otherwise show.
        state = stateOf(toolRow("t1", "Bash", ToolCallStatus.Done))
        composeTestRule.onNodeWithText("Running Bash… 1m 35s").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(thinkingDescription).assertIsDisplayed()
    }

    // #1311: a busy turn in the responding phase keeps "Working…" up after a denial; it used to go empty.
    @Test
    fun denial_removesTheLabel_andARespondingBandReadsWorking() {
        state = stateOf(toolRow("t1", "Write", ToolCallStatus.Running, elapsedSeconds = 40))
        setThreadScreen()
        composeTestRule.onNodeWithText("Running Write… 40s").assertIsDisplayed()

        state = stateOf(toolRow("t1", "Write", ToolCallStatus.Denied))

        composeTestRule.onNodeWithText("Running Write… 40s").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(runningDescription("Write")).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(thinkingDescription).assertDoesNotExist()
        composeTestRule.onNodeWithText(context.getString(R.string.thread_working_label)).assertIsDisplayed()
    }

    @Test
    fun aNewerOpenCall_replacesTheOlderOnesNameAndSeconds() {
        state = stateOf(toolRow("t1", "Bash", ToolCallStatus.Running, elapsedSeconds = 65))
        setThreadScreen()
        composeTestRule.onNodeWithText("Running Bash… 1m 05s").assertIsDisplayed()

        state =
            stateOf(
                toolRow("t1", "Bash", ToolCallStatus.Running, elapsedSeconds = 65),
                toolRow("t2", "Grep", ToolCallStatus.Running),
            )

        composeTestRule.onNodeWithText("Running Grep…").assertIsDisplayed()
        composeTestRule.onNodeWithText("Running Bash… 1m 05s").assertDoesNotExist()
        composeTestRule.onNodeWithText("Running Grep… 1m 05s").assertDoesNotExist()
    }

    @Test
    fun noTurnRunning_noLabel_evenWithARunningRow() {
        isBusy = false
        state = stateOf(toolRow("t1", "Bash", ToolCallStatus.Running, elapsedSeconds = 65))
        setThreadScreen()

        composeTestRule.onNodeWithContentDescription(runningDescription("Bash")).assertDoesNotExist()
        composeTestRule.onNodeWithText("Running Bash… 1m 05s").assertDoesNotExist()
    }

    @Test
    fun backgroundTool_doesNotReplaceTheMainTool_orItsWorkingAndIdleFallbacks() {
        val backgroundCall = toolRow("t2", "Grep", ToolCallStatus.Running, elapsedSeconds = 90, parentToolUseId = "agent")
        state = stateOf(toolRow("t1", "Bash", ToolCallStatus.Running, elapsedSeconds = 65), backgroundCall)
        setThreadScreen()

        composeTestRule.onNodeWithText("Running Bash… 1m 05s").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(elapsedDescription("Bash", "1m 05s")).assertIsDisplayed()
        composeTestRule.onNodeWithText("Running Grep…", substring = true).assertDoesNotExist()

        state = stateOf(toolRow("t1", "Bash", ToolCallStatus.Done), backgroundCall)

        composeTestRule.onNodeWithText("Running Bash…", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Running Grep…", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText(context.getString(R.string.thread_working_label)).assertIsDisplayed()

        isBusy = false

        composeTestRule.onNodeWithText(context.getString(R.string.thread_working_label)).assertDoesNotExist()
        composeTestRule.onNodeWithText("Running Grep…", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(thinkingDescription).assertDoesNotExist()
    }

    @Test
    fun theToolLabel_replacesTheTokenReadingLabel() {
        isThinking = true
        progress = ThinkingProgress(estimatedTokens = 184, estimatedTokensDelta = 64)
        state = stateOf(toolRow("t1", "Bash", ToolCallStatus.Running))
        setThreadScreen()

        composeTestRule.onNodeWithText("Running Bash…").assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.cd_thread_thinking_progress, 184L))
            .assertDoesNotExist()
    }

    @Test
    fun compactionTakesPrecedence_outcomeLeavesRunningToolVisible() {
        state = stateOf(toolRow("t1", "Bash", ToolCallStatus.Running))
        isCompacting = true
        setThreadScreen()

        composeTestRule.onNodeWithContentDescription(context.getString(R.string.cd_thread_compacting)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Running Bash…").assertDoesNotExist()

        isCompacting = false
        turnOutcome = TurnRecoveryNotice.ContextTooLong

        composeTestRule.onNodeWithText("Running Bash…").assertIsDisplayed()
        composeTestRule.onNodeWithText("Context too long - Compact").assertIsDisplayed()
    }
}
