package de.pyryco.mobile.ui.conversations.components

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
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

    // #1651: Figma 671:5664 leaves 23dp from the "Name" label's glyph top to the field's top, 2dp more
    // than this dialog drew before — the theme's default line box trims the label short of the frame's
    // full box. Pinned at the text-style level: the label keeps its full line box rather than the
    // trimmed default, so the field below it does not creep closer than the frame.
    @Test
    fun label_keeps_its_full_line_box_so_the_field_does_not_creep_closer() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                RenameDialog(initialName = "old name", onSubmit = {}, onDismiss = {})
            }
        }

        val results = mutableListOf<TextLayoutResult>()
        composeTestRule.onNode(hasText("Name")).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }

        assertEquals(
            LineHeightStyle.Trim.None,
            results
                .single()
                .layoutInput.style.lineHeightStyle
                ?.trim,
        )
    }

    @Test
    fun field_prefills_with_initial_name() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                RenameDialog(initialName = "old name", onSubmit = {}, onDismiss = {})
            }
        }

        val field = composeTestRule.onNode(hasSetTextAction() and hasText("old name")).assertIsDisplayed()
        assertEquals(TextRange(0, "old name".length), field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
    }

    /**
     * Guards the auto-focus contract three LIVE `InteractiveStreamE2ETest` scenarios wait on before
     * typing: `deleteConversation` (:544), `archiveRestore` (:689) and `renameConversation` (:981) each
     * open this dialog **over the thread**, whose composer is also editable, and disambiguate the two
     * `hasSetTextAction()` nodes by focus. The predicate below is that live predicate verbatim — a
     * paraphrase would leave the property those scenarios actually depend on unmeasured.
     *
     * The follow-up [androidx.compose.ui.test.junit4.ComposeTestRule.onNode] is not redundant: it throws
     * on multiple matches, so it asserts the **uniqueness** the live scenarios rely on when they call
     * `performTextReplacement` on this same selector.
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
                RenameDialog(initialName = "old name", onSubmit = {}, onDismiss = {})
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

    @Test
    fun ime_done_submits_only_a_changed_trimmed_name() {
        val submitted = mutableListOf<String>()
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                RenameDialog(initialName = "old", onSubmit = { submitted += it }, onDismiss = {})
            }
        }

        composeTestRule.onNode(hasSetTextAction()).performImeAction()
        composeTestRule.onNode(hasSetTextAction()).performTextReplacement("   ")
        composeTestRule.onNode(hasSetTextAction()).performImeAction()
        composeTestRule.onNode(hasSetTextAction()).performTextReplacement(" old ")
        composeTestRule.onNode(hasSetTextAction()).performImeAction()
        assertEquals(emptyList<String>(), submitted)

        composeTestRule.onNode(hasSetTextAction()).performTextReplacement("  new  ")
        composeTestRule.onNode(hasSetTextAction()).performImeAction()
        assertEquals(listOf("new"), submitted)
    }

    @Test
    fun close_and_back_dismiss_without_submitting() {
        var dismissed = 0
        var submitted = 0
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                RenameDialog(initialName = "old", onSubmit = { submitted++ }, onDismiss = { dismissed++ })
            }
        }

        composeTestRule.onNode(hasSetTextAction()).performTextReplacement("new")
        composeTestRule.onNodeWithContentDescription("Close").performClick()
        assertEquals(1, dismissed)
        assertEquals(0, submitted)

        Espresso.pressBack()
        assertEquals(2, dismissed)
        assertEquals(0, submitted)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun compact_width_and_large_text_keep_field_and_actions_reachable() {
        var submitted: String? = null
        composeTestRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 640.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.5f)) {
                    PyrycodeMobileTheme(darkTheme = true) {
                        RenameDialog(
                            initialName = "old",
                            onSubmit = { submitted = it },
                            onDismiss = {},
                            modifier = Modifier.size(320.dp, 640.dp),
                        )
                    }
                }
            }
        }

        val field = composeTestRule.onNode(hasSetTextAction()).assertIsDisplayed()
        field.performTouchInput { click() }
        composeTestRule.onNode(hasSetTextAction() and isFocused()).assertIsDisplayed()
        field.performTextReplacement("new")
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
        composeTestRule.onNodeWithText("Save").assertIsDisplayed().performTouchInput { click() }
        assertEquals("new", submitted)
    }
}
