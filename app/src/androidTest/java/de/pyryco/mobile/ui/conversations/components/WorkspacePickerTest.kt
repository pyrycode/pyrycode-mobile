package de.pyryco.mobile.ui.conversations.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.repository.ConversationRepository
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

    // #564: a failing createWorkspaceFolder (here a not-connected session) surfaces a generic error
    // message instead of crashing, and does NOT invoke onPicked. The message dismisses on OK. Because
    // the surface lives in the shared picker, this holds identically from all three entry points.
    @Test
    fun create_failure_shows_generic_message_without_crashing_or_picking() {
        val picked = mutableListOf<String>()
        // Delegate every ConversationRepository member to a real fake, overriding only the create
        // call to throw (FakeConversationRepository is final, so interface delegation, not subclassing).
        val throwingRepo =
            object : ConversationRepository by FakeConversationRepository() {
                override suspend fun createWorkspaceFolder(name: String): String = throw IllegalStateException("not connected")
            }
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                WorkspacePickerInternal(
                    repository = throwingRepo,
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

        // No crash, generic message shown, onPicked never fired.
        composeTestRule.onNode(hasText("Couldn't create folder")).assertIsDisplayed()
        assertEquals(emptyList<String>(), picked)

        // OK dismisses the message.
        composeTestRule.onNodeWithText("OK").performClick()
        composeTestRule.onNode(hasText("Couldn't create folder")).assertDoesNotExist()
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
