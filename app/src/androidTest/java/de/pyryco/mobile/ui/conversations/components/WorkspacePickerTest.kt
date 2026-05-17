package de.pyryco.mobile.ui.conversations.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspacePickerTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun dialog_submit_calls_createWorkspaceFolder_exactly_once_and_forwards_returned_path_to_onPicked() {
        val repo = FakeConversationRepository()
        val picked = mutableListOf<String>()
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                WorkspacePickerInternal(
                    repository = repo,
                    onPicked = { picked += it },
                    onDismiss = {},
                )
            }
        }

        composeTestRule
            .onNode(hasText("Create new folder under pyry-workspace…"))
            .performClick()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("my-workspace")
        composeTestRule.onNodeWithText("Create").performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf("pyry-workspace/my-workspace"), picked)
        assertEquals(
            "pyry-workspace/my-workspace",
            runBlocking { repo.recentWorkspaces().first().first() },
        )
    }

    @Test
    fun cancelling_create_dialog_keeps_sheet_visible_and_does_not_invoke_host_onDismiss() {
        var dismissed = 0
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                WorkspacePickerInternal(
                    repository = FakeConversationRepository(),
                    onPicked = {},
                    onDismiss = { dismissed++ },
                )
            }
        }

        composeTestRule
            .onNode(hasText("Create new folder under pyry-workspace…"))
            .performClick()
        composeTestRule.onNodeWithText("Cancel").performClick()

        assertEquals(0, dismissed)
        composeTestRule.onNode(hasText("Choose workspace")).assertIsDisplayed()
        composeTestRule.onNode(hasText("Create workspace")).assertDoesNotExist()
    }

    @Test
    fun tapping_a_recent_row_invokes_onPicked_with_that_rows_path() {
        val picked = mutableListOf<String>()
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                WorkspacePickerInternal(
                    repository = FakeConversationRepository(),
                    onPicked = { picked += it },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNode(hasText("~/Workspace/pyrycode-mobile")).performClick()

        assertEquals(listOf("~/Workspace/pyrycode-mobile"), picked)
    }
}
