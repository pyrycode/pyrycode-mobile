package de.pyryco.mobile.ui.conversations.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CreateFolderDialogTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun title_and_label_and_buttons_render() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                CreateFolderDialog(onCreate = {}, onDismiss = {})
            }
        }

        composeTestRule.onNode(hasText("Create workspace")).assertIsDisplayed()
        composeTestRule
            .onNode(hasText("What should this workspace be called?"))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
        composeTestRule.onNodeWithText("Create").assertIsDisplayed()
    }

    @Test
    fun create_button_disabled_when_input_blank() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                CreateFolderDialog(onCreate = {}, onDismiss = {})
            }
        }

        composeTestRule.onNodeWithText("Create").assertIsNotEnabled()
    }

    @Test
    fun create_button_enabled_after_non_blank_input() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                CreateFolderDialog(onCreate = {}, onDismiss = {})
            }
        }

        composeTestRule.onNode(hasSetTextAction()).performTextInput("my-workspace")

        composeTestRule.onNodeWithText("Create").assertIsEnabled()
    }

    @Test
    fun create_button_stays_disabled_when_input_is_whitespace_only() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                CreateFolderDialog(onCreate = {}, onDismiss = {})
            }
        }

        composeTestRule.onNode(hasSetTextAction()).performTextInput("    ")

        composeTestRule.onNodeWithText("Create").assertIsNotEnabled()
    }

    @Test
    fun tapping_create_invokes_onCreate_with_trimmed_text() {
        var created: String? = null
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                CreateFolderDialog(
                    onCreate = { created = it },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNode(hasSetTextAction()).performTextInput("  my-workspace  ")
        composeTestRule.onNodeWithText("Create").performClick()

        assertEquals("my-workspace", created)
    }

    @Test
    fun tapping_cancel_invokes_onDismiss_without_data() {
        var dismissed = 0
        var created: String? = null
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                CreateFolderDialog(
                    onCreate = { created = it },
                    onDismiss = { dismissed++ },
                )
            }
        }

        composeTestRule.onNode(hasSetTextAction()).performTextInput("my-workspace")
        composeTestRule.onNodeWithText("Cancel").performClick()

        assertEquals(1, dismissed)
        assertNull(created)
    }
}
