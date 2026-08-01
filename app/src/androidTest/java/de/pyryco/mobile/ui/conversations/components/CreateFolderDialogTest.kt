package de.pyryco.mobile.ui.conversations.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
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

    /**
     * Guards the auto-focus contract the LIVE `interactiveTurn_changeWorkspace_relabelsChipToNewWorkspace`
     * scenario waits on before typing (`InteractiveStreamE2ETest.kt:882`): the picker opens this dialog
     * **over the thread**, whose composer is also editable, and disambiguates the two
     * `hasSetTextAction()` nodes by focus. The predicate below is that live predicate verbatim — a
     * paraphrase would leave the property that scenario actually depends on unmeasured.
     *
     * This field is seeded **empty**, and that is the point: it proves focus does not depend on the field
     * carrying text, which is what makes the live selector usable here at all (there is no text to key on).
     *
     * The follow-up [androidx.compose.ui.test.junit4.ComposeTestRule.onNode] is not redundant: it throws
     * on multiple matches, so it asserts the **uniqueness** the live scenario relies on when it calls
     * `performTextInput` on this same selector.
     *
     * The 2 s bound is deliberate and is not [de.pyryco.mobile.e2e.InteractiveStreamE2ETest]'s 30 s. It
     * absorbs the frame between `LaunchedEffect` and focus dispatch — a bare `assertIsFocused()` could
     * report "contract broken" when the truth is "focus landed one frame later" — while staying short
     * enough that anything slower is a finding worth reporting rather than quietly waited out.
     */
    @Test
    fun field_reports_focus_once_dialog_composes() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                CreateFolderDialog(onCreate = {}, onDismiss = {})
            }
        }

        composeTestRule.waitUntil(2_000L) {
            composeTestRule
                .onAllNodes(hasSetTextAction() and isFocused())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNode(hasSetTextAction() and isFocused()).assertIsDisplayed()
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
