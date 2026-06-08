package de.pyryco.mobile.ui.conversations.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.model.ToolCallStatus
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
}
