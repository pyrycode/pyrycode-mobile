package de.pyryco.mobile.ui.conversations.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.conversations.thread.ThreadEffortChoice
import de.pyryco.mobile.ui.conversations.thread.ThreadModelChoice
import de.pyryco.mobile.ui.conversations.thread.ThreadReportedText
import de.pyryco.mobile.ui.conversations.thread.ThreadRunningModel
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class StatusSheetTest {
    @Rule @JvmField
    val composeTestRule = createComposeRule()

    // ---- #807 fixtures ---------------------------------------------------------------------------
    //
    // The sheet no longer iterates the three-entry Model and five-entry Effort device enums; it renders
    // whatever the daemon published for this conversation. These rows stand in for one such menu, and the
    // helper below carries every unvaried parameter so each case names only what it is about.

    private val opus =
        ThreadModelChoice(
            value = "opus",
            label = "Opus 4.7",
            detail = "claude-opus-4-7",
            effortChoices = listOf("low", "high", "max").map { ThreadEffortChoice(it, it) },
        )

    private val sonnet =
        ThreadModelChoice(
            value = "sonnet",
            label = "Sonnet 4.6",
            detail = "claude-sonnet-4-6",
            effortChoices = listOf("low", "high").map { ThreadEffortChoice(it, it) },
        )

    /** A row publishing no effort levels — a positive statement, not a cue to substitute anything. */
    private val haiku =
        ThreadModelChoice(value = "haiku", label = "Haiku 4.5", detail = "", effortChoices = emptyList())

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun sharedModalDoneDismissesWithoutWritingSettings() {
        var dismissals = 0
        val writes = mutableListOf<String>()
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                StatusSheet(
                    choices = listOf(opus, sonnet),
                    menuAvailable = true,
                    notListedModels = 0,
                    selectedModel = "opus",
                    onModelSelected = writes::add,
                    effortChoices = opus.effortChoices,
                    selectedEffort = "high",
                    onEffortSelected = writes::add,
                    pending = false,
                    enabled = true,
                    onDismiss = { dismissals++ },
                )
            }
        }

        composeTestRule.onNodeWithText("Done").performClick()
        assertEquals(1, dismissals)
        assertEquals(emptyList<String>(), writes)
    }

    @Config(qualifiers = "w320dp-h640dp")
    @Test
    fun compactModalWithEnlargedTextScrollsThroughPublishedChoicesWhileDoneStaysReachable() {
        val models =
            (0..11).map { index ->
                ThreadModelChoice("model-$index", "Model $index", "", emptyList())
            }
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                PyrycodeMobileTheme {
                    StatusSheet(
                        choices = models,
                        menuAvailable = true,
                        notListedModels = 0,
                        selectedModel = models.first().value,
                        onModelSelected = {},
                        effortChoices = (0..7).map { ThreadEffortChoice("level-$it", "Level $it") },
                        selectedEffort = "level-7",
                        onEffortSelected = {},
                        pending = false,
                        enabled = true,
                        onDismiss = {},
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Model 11").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Level 7").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Done").assertIsDisplayed()
    }

    private fun ComposeContentTestRule.setSheet(
        choices: List<ThreadModelChoice> = listOf(opus, sonnet, haiku),
        menuAvailable: Boolean = true,
        notListedModels: Int = 0,
        selectedModel: String? = "opus",
        modelSelectionNote: String? = null,
        onModelSelected: (String) -> Unit = {},
        effortChoices: List<ThreadEffortChoice>? = null,
        selectedEffort: String = "high",
        onEffortSelected: (String) -> Unit = {},
        pending: Boolean = false,
        enabled: Boolean = true,
        effortNote: String? = null,
        running: ThreadRunningModel = ThreadRunningModel(),
        contextPercent: Int? = null,
        permissionMode: String = "plan",
        permissionPending: Boolean = false,
        onPermissionSelected: (String) -> Unit = {},
    ) = setContent {
        PyrycodeMobileTheme {
            StatusSheetContent(
                choices = choices,
                menuAvailable = menuAvailable,
                notListedModels = notListedModels,
                selectedModel = selectedModel,
                modelSelectionNote = modelSelectionNote,
                onModelSelected = onModelSelected,
                // Defaulted to the selected row's own levels, which is what the ViewModel derives.
                effortChoices =
                    effortChoices ?: choices.firstOrNull { it.value == selectedModel }?.effortChoices.orEmpty(),
                selectedEffort = selectedEffort,
                onEffortSelected = onEffortSelected,
                pending = pending,
                enabled = enabled,
                effortNote = effortNote,
                running = running,
                contextPercent = contextPercent,
                permissionMode = permissionMode,
                permissionChoices = listOf("plan" to "Plan", "default" to "Manual approval"),
                permissionPending = permissionPending,
                onPermissionSelected = onPermissionSelected,
            )
        }
    }

    // ---- Model section ---------------------------------------------------------------------------

    @Test
    fun permissionChoiceIsAvailableInRunConfiguration() {
        val selected = mutableListOf<String>()
        composeTestRule.setSheet(choices = listOf(opus), effortChoices = emptyList(), onPermissionSelected = selected::add)

        composeTestRule.onNode(hasText("Plan") and isSelectable()).assertIsSelected()
        composeTestRule.onNode(hasText("Manual approval") and isSelectable()).performClick()

        assertEquals(listOf("default"), selected)
    }

    @Test
    fun permissionChoiceIsDisabledWhileAWriteIsPending() {
        composeTestRule.setSheet(permissionPending = true)

        composeTestRule.onNodeWithText("Permission · applying…").assertIsDisplayed()
        composeTestRule.onNode(hasText("Manual approval") and isSelectable()).assertIsNotEnabled()
    }

    @Test
    fun renders_the_published_rows_with_their_labels_and_details() {
        composeTestRule.setSheet()

        composeTestRule.onNode(hasText("Model")).assertIsDisplayed()
        composeTestRule.onNode(hasText("Opus 4.7")).assertIsDisplayed()
        composeTestRule.onNode(hasText("claude-opus-4-7")).assertIsDisplayed()
        composeTestRule.onNode(hasText("Sonnet 4.6")).assertIsDisplayed()
        composeTestRule.onNode(hasText("claude-sonnet-4-6")).assertIsDisplayed()
        composeTestRule.onNode(hasText("Haiku 4.5")).assertIsDisplayed()
    }

    @Test
    fun tapping_a_row_reports_the_published_value_not_the_label() {
        val picks = mutableListOf<String>()
        composeTestRule.setSheet(onModelSelected = picks::add)

        composeTestRule.onNode(hasText("Sonnet 4.6")).performClick()

        // The wire argument, forwarded verbatim — never parsed, never the label.
        assertEquals(listOf("sonnet"), picks)
    }

    @Test
    fun selected_row_reports_selected_semantics() {
        composeTestRule.setSheet(selectedModel = "sonnet")

        composeTestRule.onNode(isSelectable() and hasText("Sonnet 4.6")).assertIsSelected()
        composeTestRule.onNode(isSelectable() and hasText("Opus 4.7")).assertIsNotSelected()
    }

    @Test
    fun radioRowsKeepExpandedTouchTargetsAroundCompactVisibleCircles() {
        composeTestRule.setSheet()

        val model = composeTestRule.onNode(isSelectable() and hasText("Opus 4.7")).fetchSemanticsNode()
        val effort = composeTestRule.onNode(isSelectable() and hasText("high")).fetchSemanticsNode()
        assertTrue(model.touchBoundsInRoot.height >= 48f)
        assertTrue(effort.touchBoundsInRoot.height >= 48f)
    }

    @Test
    fun a_saved_value_no_row_publishes_selects_nothing() {
        composeTestRule.setSheet(selectedModel = "opus[1m]")

        composeTestRule.onNode(isSelectable() and hasText("Opus 4.7")).assertIsNotSelected()
        composeTestRule.onNode(isSelectable() and hasText("Sonnet 4.6")).assertIsNotSelected()
    }

    @Test
    fun inheritedSelectionMarksOneOrdinaryRowWithoutShowingDefault() {
        composeTestRule.setSheet(selectedModel = "sonnet")

        composeTestRule.onNode(isSelectable() and hasText("Sonnet 4.6")).assertIsSelected()
        composeTestRule.onAllNodes(isSelectable() and hasText("Default")).assertCountEquals(0)
    }

    @Test
    fun delayedSettingsHaveNoSelectedRadio() {
        composeTestRule.setSheet(selectedModel = null)
        composeTestRule.onNode(isSelectable() and hasText("Opus 4.7")).assertIsNotSelected()
        composeTestRule.onNode(isSelectable() and hasText("Sonnet 4.6")).assertIsNotSelected()
    }

    @Test
    fun unmatchedSettingsShowInertNoteWithNoSelectedRadio() {
        composeTestRule.setSheet(selectedModel = null, modelSelectionNote = "opus[1m]")
        composeTestRule.onNodeWithText("opus[1m]").assertIsDisplayed()
        composeTestRule.onNode(isSelectable() and hasText("Opus 4.7")).assertIsNotSelected()
    }

    @Test
    fun an_unavailable_menu_says_so_and_offers_no_rows() {
        composeTestRule.setSheet(choices = emptyList(), menuAvailable = false)

        composeTestRule.onNode(hasText("Model list unavailable")).assertIsDisplayed()
        // No device-enum substitution: the three retired entries must not appear.
        composeTestRule.onAllNodes(hasText("Opus 4.7")).assertCountEquals(0)
    }

    @Test
    fun a_menu_that_published_nothing_is_a_different_statement_from_an_absent_one() {
        composeTestRule.setSheet(choices = emptyList(), menuAvailable = true)

        composeTestRule.onNode(hasText("This server published no selectable models.")).assertIsDisplayed()
    }

    @Test
    fun a_truncated_menu_reports_the_count_it_was_given() {
        composeTestRule.setSheet(notListedModels = 44)

        // Reported, never recomputed from the rendered row count.
        composeTestRule.onNode(hasText("3 shown · 44 not listed")).assertIsDisplayed()
    }

    // ---- Effort section --------------------------------------------------------------------------

    @Test
    fun renders_the_selected_rows_effort_levels_in_wire_order() {
        composeTestRule.setSheet(selectedModel = "opus")

        composeTestRule.onNode(hasText("Effort")).assertIsDisplayed()
        composeTestRule.onNode(hasText("low")).assertIsDisplayed()
        composeTestRule.onNode(hasText("high")).assertIsDisplayed()
        composeTestRule.onNode(hasText("max")).assertIsDisplayed()
        // "medium" and "xhigh" are Effort entries this row never published.
        composeTestRule.onAllNodes(hasText("medium")).assertCountEquals(0)
        composeTestRule.onAllNodes(hasText("xhigh")).assertCountEquals(0)
    }

    @Test
    fun tapping_a_chip_reports_its_level() {
        val picks = mutableListOf<String>()
        composeTestRule.setSheet(onEffortSelected = picks::add)

        composeTestRule.onNode(hasText("low")).performClick()

        assertEquals(listOf("low"), picks)
    }

    @Test
    fun selected_effort_chip_reports_selected_semantics() {
        composeTestRule.setSheet(selectedEffort = "max")

        composeTestRule.onNode(isSelectable() and hasText("max")).assertIsSelected()
        composeTestRule.onNode(isSelectable() and hasText("low")).assertIsNotSelected()
    }

    @Test
    fun an_unset_saved_effort_selects_no_chip_but_still_offers_them() {
        val picks = mutableListOf<String>()
        composeTestRule.setSheet(selectedEffort = "", onEffortSelected = picks::add)

        composeTestRule.onNode(isSelectable() and hasText("low")).assertIsNotSelected()
        composeTestRule.onNode(isSelectable() and hasText("high")).assertIsNotSelected()

        // Still selectable: an unset saved effort is not a reason to withhold the row's own levels.
        composeTestRule.onNode(hasText("low")).performClick()
        assertEquals(listOf("low"), picks)
    }

    // #889: the sheet says why the selection is not Claude's applied effort.
    @Test
    fun an_effort_note_renders_below_the_effort_chips() {
        composeTestRule.setSheet(selectedEffort = "", effortNote = "Claude reports no effort parameter.")

        composeTestRule.onNode(hasText("Claude reports no effort parameter.")).assertIsDisplayed()
        composeTestRule.onNode(isSelectable() and hasText("high")).assertIsNotSelected()
    }

    @Test
    fun a_row_publishing_no_levels_offers_no_effort_choice() {
        composeTestRule.setSheet(selectedModel = "haiku")

        composeTestRule.onNode(hasText("No effort levels published for this model.")).assertIsDisplayed()
        composeTestRule.onAllNodes(hasText("low")).assertCountEquals(0)
        composeTestRule.onAllNodes(hasText("high")).assertCountEquals(0)
    }

    // ---- Running model (#891) --------------------------------------------------------------------

    @Test
    fun the_announced_model_and_build_render_apart_from_the_selected_model() {
        composeTestRule.setSheet(
            selectedModel = "sonnet",
            running =
                ThreadRunningModel(
                    model = ThreadReportedText("claude-opus-4-7", truncated = false),
                    build = ThreadReportedText("2.1.3", truncated = false),
                ),
        )

        composeTestRule.onNode(hasText("Running model")).assertIsDisplayed()
        composeTestRule.onNode(hasTestTag(RUNNING_MODEL_TEST_TAG) and hasText("claude-opus-4-7")).assertIsDisplayed()
        composeTestRule.onNode(hasText("Claude Code 2.1.3")).assertIsDisplayed()
        composeTestRule.onNode(isSelectable() and hasText("Sonnet 4.6")).assertIsSelected()
    }

    @Test
    fun no_announcement_renders_the_unavailable_state_and_no_build_line() {
        composeTestRule.setSheet(selectedModel = "opus")

        composeTestRule.onNode(hasText("Running model")).assertIsDisplayed()
        composeTestRule.onAllNodes(hasText("Not reported yet")).assertCountEquals(2)
        composeTestRule.onNode(hasTestTag(RUNNING_MODEL_TEST_TAG)).assertDoesNotExist()
        composeTestRule.onAllNodes(hasText("Claude Code", substring = true)).assertCountEquals(0)
    }

    @Test
    fun a_truncated_value_carries_a_visible_and_announced_mark() {
        composeTestRule.setSheet(
            running =
                ThreadRunningModel(
                    model = ThreadReportedText("claude-opus", truncated = true),
                    build = ThreadReportedText("2.1", truncated = true),
                ),
        )

        // The mark is part of the node's text, which is what TalkBack reads.
        composeTestRule.onNode(hasTestTag(RUNNING_MODEL_TEST_TAG) and hasText("claude-opus (truncated)")).assertIsDisplayed()
        composeTestRule.onNode(hasText("Claude Code 2.1 (truncated)")).assertIsDisplayed()
    }

    // ---- Pending and read-only -------------------------------------------------------------------

    @Test
    fun a_pending_write_is_announced_and_disables_both_controls() {
        composeTestRule.setSheet(pending = true)

        composeTestRule.onNode(hasText("Model · applying…")).assertIsDisplayed()
        composeTestRule.onNode(hasText("Effort · applying…")).assertIsDisplayed()
        composeTestRule.onNode(isSelectable() and hasText("Sonnet 4.6")).assertIsNotEnabled()
        composeTestRule.onNode(isSelectable() and hasText("low")).assertIsNotEnabled()
    }

    @Test
    fun a_read_only_session_disables_the_controls_without_claiming_a_write_is_in_flight() {
        composeTestRule.setSheet(enabled = false)

        composeTestRule.onNode(hasText("Model")).assertIsDisplayed()
        composeTestRule.onAllNodes(hasText("Model · applying…")).assertCountEquals(0)
        composeTestRule.onNode(isSelectable() and hasText("Sonnet 4.6")).assertIsNotEnabled()
        composeTestRule.onNode(isSelectable() and hasText("low")).assertIsNotEnabled()
    }

    // ---- Unchanged sections ----------------------------------------------------------------------

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun tapping_close_icon_invokes_onDismiss() {
        var invoked = 0
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                StatusSheet(
                    choices = listOf(opus),
                    menuAvailable = true,
                    notListedModels = 0,
                    selectedModel = "opus",
                    onModelSelected = {},
                    effortChoices = opus.effortChoices,
                    selectedEffort = "high",
                    onEffortSelected = {},
                    pending = false,
                    enabled = true,
                    onDismiss = { invoked++ },
                )
            }
        }

        composeTestRule.onNode(hasContentDescription("Close")).performClick()

        assertEquals(1, invoked)
    }

    // #650: the permission control moved to the composer footer, where it reads the confirmed mode.
    @Test
    fun has_no_yolo_switch() {
        composeTestRule.setSheet()

        composeTestRule.onNode(hasText("YOLO mode")).assertDoesNotExist()
        composeTestRule.onNode(hasText("Auto-accept tool calls")).assertDoesNotExist()
    }

    @Test
    fun renders_context_window_section_as_unavailable_without_old_helper() {
        composeTestRule.setSheet()

        composeTestRule.onNode(hasText("Context window")).assertIsDisplayed()
        composeTestRule.onAllNodes(hasText("Not reported yet")).assertCountEquals(2)
        composeTestRule
            .onAllNodes(hasText("When full, oldest messages get dropped", substring = true))
            .assertCountEquals(0)
        // No progress bar anywhere in the sheet: the figure is unknown, so no severity is shown.
        composeTestRule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assertCountEquals(0)
    }

    // #946: Claude's reported percentage replaces the unavailable line.
    @Test
    fun renders_the_reported_context_percentage_in_place_of_the_unavailable_line() {
        composeTestRule.setSheet(contextPercent = 84)

        composeTestRule.onNode(hasText("Context window")).assertIsDisplayed()
        composeTestRule.onNode(hasText("84% used")).assertIsDisplayed()
        composeTestRule.onAllNodes(hasText("Not reported yet")).assertCountEquals(1)
    }
}
