package de.pyryco.mobile.ui.conversations.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.conversations.thread.WorkspaceChoice
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SaveAsChannelDialogTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun title_field_label_radios_and_buttons_render() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                SaveAsChannelDialog(
                    initialName = "New channel",
                    onSubmit = { _, _ -> },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNode(hasText("Save as channel")).assertIsDisplayed()
        composeTestRule.onNode(hasText("Name")).assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Move to dedicated channel folder")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Keep in scratch").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
        composeTestRule.onNodeWithText("Save").assertIsDisplayed()
    }

    @Test
    fun field_prefills_with_initial_name() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                SaveAsChannelDialog(
                    initialName = "New channel",
                    onSubmit = { _, _ -> },
                    onDismiss = {},
                )
            }
        }

        composeTestRule
            .onNode(hasSetTextAction() and hasText("New channel"))
            .assertIsDisplayed()
    }

    /**
     * Guards the auto-focus contract the LIVE `interactiveTurn_saveAsChannel_promotesToChannelTier`
     * scenario waits on before typing (`InteractiveStreamE2ETest.kt:1145`): the dialog opens **over the
     * thread**, whose composer is also editable, and disambiguates the two `hasSetTextAction()` nodes by
     * focus. The predicate below is that live predicate verbatim — a paraphrase would leave the property
     * that scenario actually depends on unmeasured.
     *
     * The follow-up [androidx.compose.ui.test.junit4.ComposeTestRule.onNode] is not redundant: it throws
     * on multiple matches, so it asserts the **uniqueness** the live scenario relies on when it calls
     * `performTextReplacement` on this same selector. Uniqueness carries extra weight in this dialog —
     * its `text` slot also holds [WorkspaceChoice] radios, so it is the one of the three where a sibling
     * could plausibly match.
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
                SaveAsChannelDialog(
                    initialName = "New channel",
                    onSubmit = { _, _ -> },
                    onDismiss = {},
                )
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
    fun dedicated_radio_selected_by_default() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                SaveAsChannelDialog(
                    initialName = "New channel",
                    onSubmit = { _, _ -> },
                    onDismiss = {},
                )
            }
        }

        composeTestRule
            .onNodeWithText("Move to dedicated channel folder")
            .assertIsSelected()
        composeTestRule.onNodeWithText("Keep in scratch").assertIsNotSelected()
    }

    @Test
    fun save_enabled_with_seeded_name() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                SaveAsChannelDialog(
                    initialName = "New channel",
                    onSubmit = { _, _ -> },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun save_disabled_when_input_cleared() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                SaveAsChannelDialog(
                    initialName = "New channel",
                    onSubmit = { _, _ -> },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNode(hasSetTextAction()).performTextReplacement("")

        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun save_disabled_on_whitespace_only_input() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                SaveAsChannelDialog(
                    initialName = "New channel",
                    onSubmit = { _, _ -> },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNode(hasSetTextAction()).performTextReplacement("   ")

        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun save_enabled_with_non_blank_trimmed_input() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                SaveAsChannelDialog(
                    initialName = "New channel",
                    onSubmit = { _, _ -> },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNode(hasSetTextAction()).performTextReplacement("  alpha  ")

        composeTestRule.onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun selecting_scratch_radio_updates_selection() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                SaveAsChannelDialog(
                    initialName = "New channel",
                    onSubmit = { _, _ -> },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Keep in scratch").performClick()

        composeTestRule.onNodeWithText("Keep in scratch").assertIsSelected()
        composeTestRule
            .onNodeWithText("Move to dedicated channel folder")
            .assertIsNotSelected()
    }

    @Test
    fun tapping_save_with_dedicated_invokes_onSubmit_with_trimmed_name_and_dedicated_choice() {
        var submitted: Pair<String, WorkspaceChoice>? = null
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                SaveAsChannelDialog(
                    initialName = "New channel",
                    onSubmit = { name, workspace -> submitted = name to workspace },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNode(hasSetTextAction()).performTextReplacement("  alpha  ")
        composeTestRule.onNodeWithText("Save").performClick()

        assertEquals("alpha" to WorkspaceChoice.DEDICATED, submitted)
    }

    @Test
    fun tapping_save_with_scratch_invokes_onSubmit_with_scratch_choice() {
        var submitted: Pair<String, WorkspaceChoice>? = null
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                SaveAsChannelDialog(
                    initialName = "New channel",
                    onSubmit = { name, workspace -> submitted = name to workspace },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNode(hasSetTextAction()).performTextReplacement("alpha")
        composeTestRule.onNodeWithText("Keep in scratch").performClick()
        composeTestRule.onNodeWithText("Save").performClick()

        assertEquals("alpha" to WorkspaceChoice.SCRATCH, submitted)
    }

    @Test
    fun tapping_cancel_invokes_onDismiss_without_invoking_onSubmit() {
        var dismissed = 0
        var submitted: Pair<String, WorkspaceChoice>? = null
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                SaveAsChannelDialog(
                    initialName = "New channel",
                    onSubmit = { name, workspace -> submitted = name to workspace },
                    onDismiss = { dismissed++ },
                )
            }
        }

        composeTestRule.onNodeWithText("Cancel").performClick()

        assertEquals(1, dismissed)
        assertNull(submitted)
    }
}
