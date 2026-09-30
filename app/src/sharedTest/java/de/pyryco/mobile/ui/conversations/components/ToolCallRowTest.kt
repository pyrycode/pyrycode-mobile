package de.pyryco.mobile.ui.conversations.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.model.ToolDenial
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToolCallRowTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    // Matches the string resources added in #388.
    private val runningDescription = "Tool call running"
    private val failedDescription = "Tool call failed"

    private fun string(id: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private val doneDescription = string(R.string.cd_tool_done)
    private val deniedDescription = string(R.string.cd_tool_denied)

    private fun runningToolCall() =
        ToolCall(
            toolName = "Bash",
            input = "./gradlew assembleDebug",
            output = "",
            status = ToolCallStatus.Running,
        )

    private fun doneToolCall() =
        ToolCall(
            toolName = "Bash",
            input = "git status",
            output = "working tree clean",
            status = ToolCallStatus.Done,
        )

    private fun failedToolCall() =
        ToolCall(
            toolName = "Bash",
            input = "./gradlew assembleDebug",
            output = "FAILURE: Build failed with an exception.",
            status = ToolCallStatus.Failed,
        )

    private fun setContent(toolCall: ToolCall) {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ToolCallRow(toolCall = toolCall)
            }
        }
    }

    @Test
    fun running_shows_the_progress_affordance() {
        setContent(runningToolCall())

        composeTestRule.onNodeWithContentDescription(runningDescription).assertIsDisplayed()
    }

    @Test
    fun failed_shows_the_error_affordance() {
        setContent(failedToolCall())

        composeTestRule.onNodeWithContentDescription(failedDescription).assertIsDisplayed()
    }

    @Test
    fun done_shows_neither_status_affordance() {
        setContent(doneToolCall())

        composeTestRule.onNode(hasText("Bash", substring = true)).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(runningDescription).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(failedDescription).assertDoesNotExist()
    }

    @Test
    fun output_is_hidden_while_running_and_revealed_on_resolution() {
        setContent(runningToolCall())
        composeTestRule.onNode(hasClickAction()).performClick()
        composeTestRule.onNodeWithText("Output").assertDoesNotExist()
    }

    @Test
    fun output_is_shown_once_the_call_resolves() {
        setContent(failedToolCall())
        composeTestRule.onNode(hasClickAction()).performClick()

        composeTestRule.onNodeWithText("Output").assertIsDisplayed()
        composeTestRule.onNode(hasText("FAILURE", substring = true)).assertIsDisplayed()
    }

    /**
     * Code-ish tool output takes the markdown code block's chrome but not its copy control: whether
     * tool output is copyable is #658's decision, and `CodeBlock`'s control is opt-in for that reason.
     */
    @Test
    fun code_ish_output_renders_the_code_block_without_a_copy_control() {
        setContent(
            ToolCall(
                toolName = "Bash",
                input = "ls",
                output = "build.gradle.kts\nsettings.gradle.kts",
                status = ToolCallStatus.Done,
            ),
        )
        composeTestRule.onNode(hasClickAction()).performClick()

        composeTestRule.onNodeWithText("build.gradle.kts\nsettings.gradle.kts").assertIsDisplayed()
        val copyCode =
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.cd_thread_copy_code)
        composeTestRule.onNodeWithContentDescription(copyCode).assertDoesNotExist()
        composeTestRule.onNodeWithTag(CODE_BLOCK_HEADER_TAG).assertDoesNotExist()
    }

    @Test
    fun done_shows_its_own_affordance() {
        setContent(doneToolCall())

        composeTestRule.onNodeWithContentDescription(doneDescription).assertIsDisplayed()
    }

    @Test
    fun denied_shows_its_own_affordance_not_the_failed_one() {
        setContent(deniedToolCall())

        composeTestRule.onNodeWithContentDescription(deniedDescription).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(failedDescription).assertDoesNotExist()
    }

    @Test
    fun running_with_a_reading_shows_the_elapsed_time() {
        setContent(runningToolCall().copy(elapsedSeconds = 65))

        composeTestRule.onNodeWithTag(TOOL_ELAPSED_TAG, useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("1m 05s").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(runningDescription).assertIsDisplayed()
    }

    @Test
    fun running_without_a_reading_shows_no_time() {
        setContent(runningToolCall())

        composeTestRule.onNodeWithTag(TOOL_ELAPSED_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun a_resolved_row_shows_no_time_even_with_a_stale_reading() {
        setContent(doneToolCall().copy(elapsedSeconds = 12))

        composeTestRule.onNodeWithTag(TOOL_ELAPSED_TAG, useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("12s").assertDoesNotExist()
    }

    @Test
    fun a_supplied_description_uses_the_described_header() {
        setContent(
            doneToolCall().copy(
                inputFields = mapOf("command" to "git status", "description" to "Show working tree status"),
            ),
        )

        composeTestRule.onNodeWithText("Show working tree status").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bash").assertDoesNotExist()
        composeTestRule.onNodeWithTag("tool-description-chevron", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun a_bash_command_without_a_description_leads_with_the_command_alone() {
        setContent(doneToolCall().copy(inputFields = mapOf("command" to "git status")))

        composeTestRule.onNodeWithText("git status").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bash").assertDoesNotExist()
        composeTestRule.onNodeWithTag("tool-description-chevron", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun a_non_bash_call_with_a_description_keeps_its_name_and_subject() {
        setContent(
            doneToolCall().copy(
                toolName = "Agent",
                inputFields = mapOf("description" to "Review the diff", "subagent_type" to "general-purpose"),
            ),
        )

        composeTestRule.onNodeWithText("Agent").assertIsDisplayed()
        composeTestRule.onNodeWithText("Review the diff").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tool-description-chevron", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun bash_output_with_a_description_is_not_treated_as_bash() {
        setContent(
            doneToolCall().copy(
                toolName = "BashOutput",
                inputFields = mapOf("command" to "tail -f build.log", "description" to "Follow the build log"),
            ),
        )

        composeTestRule.onNodeWithText("BashOutput").assertIsDisplayed()
        composeTestRule.onNodeWithText("tail -f build.log").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tool-description-chevron", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun a_call_with_neither_field_uses_the_simple_name_and_precis() {
        setContent(doneToolCall())

        composeTestRule.onNodeWithText("Bash").assertIsDisplayed()
        composeTestRule.onNodeWithText("git status").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tool-description-chevron", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun the_described_row_toggles_supplied_code_and_result_without_copy() {
        setContent(
            doneToolCall().copy(
                inputFields = mapOf("command" to "git status", "description" to "Inspect repository"),
                output = "working tree clean",
            ),
        )
        composeTestRule.onNodeWithText("Input").assertDoesNotExist()
        composeTestRule.onNode(hasClickAction()).performClick()

        composeTestRule.onNodeWithText("git status").assertIsDisplayed()
        composeTestRule.onNodeWithText("working tree clean").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(string(R.string.cd_thread_copy_code)).assertDoesNotExist()

        composeTestRule.onNode(hasClickAction()).performClick()
        composeTestRule.onNodeWithText("Input").assertDoesNotExist()
    }

    @Test
    fun a_resolved_call_without_a_result_does_not_invent_output() {
        setContent(doneToolCall().copy(output = ""))
        composeTestRule.onNode(hasClickAction()).performClick()

        composeTestRule.onNodeWithText("Output").assertDoesNotExist()
    }

    @Test
    fun an_expanded_denied_row_shows_the_denial_message() {
        setContent(deniedToolCall())
        composeTestRule.onNode(hasClickAction()).performClick()

        composeTestRule.onNodeWithText("The user declined this command.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Output").assertDoesNotExist()
    }

    @Test
    fun a_row_stays_expanded_while_it_is_updated_in_place() {
        var toolCall by
            mutableStateOf(
                runningToolCall().copy(
                    inputFields = mapOf("description" to "Build the app", "command" to "./gradlew assembleDebug"),
                    elapsedSeconds = 3,
                ),
            )
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ToolCallRow(toolCall = toolCall)
            }
        }
        composeTestRule.onNode(hasClickAction()).performClick()
        composeTestRule.onNodeWithText("Input").assertIsDisplayed()

        toolCall = toolCall.copy(elapsedSeconds = 4)
        composeTestRule.onNodeWithText("4s", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Input").assertIsDisplayed()

        toolCall = toolCall.copy(status = ToolCallStatus.Done, output = "built", elapsedSeconds = null)
        composeTestRule.onNodeWithText("Input").assertIsDisplayed()
        composeTestRule.onNodeWithText("Output").assertIsDisplayed()
        composeTestRule.onNodeWithText("built").assertIsDisplayed()
    }

    private fun deniedToolCall() =
        ToolCall(
            toolName = "Bash",
            input = "rm -rf build",
            output = "",
            status = ToolCallStatus.Denied,
            denial =
                ToolDenial(
                    toolName = "Bash",
                    decisionReasonType = "user",
                    decisionReason = "",
                    message = "The user declined this command.",
                    truncatedFields = null,
                    droppedFields = null,
                ),
        )
}
