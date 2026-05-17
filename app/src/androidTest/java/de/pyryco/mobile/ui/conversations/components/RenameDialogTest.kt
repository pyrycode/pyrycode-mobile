package de.pyryco.mobile.ui.conversations.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RenameDialogTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun title_and_field_label_and_buttons_render() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                RenameDialog(initialName = "old name", onSubmit = {}, onDismiss = {})
            }
        }

        composeTestRule.onNode(hasText("Rename")).assertIsDisplayed()
        composeTestRule.onNode(hasText("Name")).assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
        composeTestRule.onNodeWithText("Save").assertIsDisplayed()
    }

    @Test
    fun field_prefills_with_initial_name() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                RenameDialog(initialName = "old name", onSubmit = {}, onDismiss = {})
            }
        }

        composeTestRule.onNode(hasSetTextAction() and hasText("old name")).assertIsDisplayed()
    }

    @Test
    fun save_disabled_on_first_composition_when_matches_initial_name() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                RenameDialog(initialName = "old name", onSubmit = {}, onDismiss = {})
            }
        }

        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun save_disabled_when_input_cleared() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                RenameDialog(initialName = "old name", onSubmit = {}, onDismiss = {})
            }
        }

        composeTestRule.onNode(hasSetTextAction()).performTextReplacement("")

        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun save_disabled_on_whitespace_only_input() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                RenameDialog(initialName = "old name", onSubmit = {}, onDismiss = {})
            }
        }

        composeTestRule.onNode(hasSetTextAction()).performTextReplacement("   ")

        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun save_disabled_when_trimmed_input_equals_initial_name() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                RenameDialog(initialName = "kitchenclaw", onSubmit = {}, onDismiss = {})
            }
        }

        composeTestRule.onNode(hasSetTextAction()).performTextReplacement("  kitchenclaw  ")

        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun save_enabled_when_trimmed_input_differs_from_initial_name() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                RenameDialog(initialName = "old name", onSubmit = {}, onDismiss = {})
            }
        }

        composeTestRule.onNode(hasSetTextAction()).performTextReplacement("new name")

        composeTestRule.onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun tapping_save_invokes_onSubmit_with_trimmed_text() {
        var submitted: String? = null
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                RenameDialog(
                    initialName = "old",
                    onSubmit = { submitted = it },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNode(hasSetTextAction()).performTextReplacement("  refactored  ")
        composeTestRule.onNodeWithText("Save").performClick()

        assertEquals("refactored", submitted)
    }

    @Test
    fun tapping_cancel_invokes_onDismiss_without_invoking_onSubmit() {
        var dismissed = 0
        var submitted: String? = null
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                RenameDialog(
                    initialName = "old",
                    onSubmit = { submitted = it },
                    onDismiss = { dismissed++ },
                )
            }
        }

        composeTestRule.onNode(hasSetTextAction()).performTextReplacement("new")
        composeTestRule.onNodeWithText("Cancel").performClick()

        assertEquals(1, dismissed)
        assertNull(submitted)
    }
}
