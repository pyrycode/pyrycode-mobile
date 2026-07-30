package de.pyryco.mobile.ui.conversations.components

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.preferences.Effort
import de.pyryco.mobile.data.preferences.Model
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StatusSheetTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun renders_model_section_with_all_three_rows_and_descriptions() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                StatusSheetContent(
                    selectedModel = Model.OPUS_4_7,
                    onModelSelected = {},
                    selectedEffort = Effort.HIGH,
                    onEffortSelected = {},
                    yoloEnabled = false,
                    onYoloToggled = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNode(hasText("Model")).assertIsDisplayed()
        composeTestRule.onNode(hasText("Opus 4.7")).assertIsDisplayed()
        composeTestRule.onNode(hasText("best for complex work")).assertIsDisplayed()
        composeTestRule.onNode(hasText("Sonnet 4.6")).assertIsDisplayed()
        composeTestRule.onNode(hasText("faster, cheaper")).assertIsDisplayed()
        composeTestRule.onNode(hasText("Haiku 4.5")).assertIsDisplayed()
        composeTestRule.onNode(hasText("fastest")).assertIsDisplayed()
    }

    @Test
    fun tapping_sonnet_row_invokes_onModelSelected_with_sonnet() {
        val picks = mutableListOf<Model>()
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                StatusSheetContent(
                    selectedModel = Model.OPUS_4_7,
                    onModelSelected = picks::add,
                    selectedEffort = Effort.HIGH,
                    onEffortSelected = {},
                    yoloEnabled = false,
                    onYoloToggled = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNode(hasText("Sonnet 4.6")).performClick()

        assertEquals(listOf(Model.SONNET_4_6), picks)
    }

    @Test
    fun tapping_haiku_row_invokes_onModelSelected_with_haiku() {
        val picks = mutableListOf<Model>()
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                StatusSheetContent(
                    selectedModel = Model.OPUS_4_7,
                    onModelSelected = picks::add,
                    selectedEffort = Effort.HIGH,
                    onEffortSelected = {},
                    yoloEnabled = false,
                    onYoloToggled = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNode(hasText("Haiku 4.5")).performClick()

        assertEquals(listOf(Model.HAIKU_4_5), picks)
    }

    @Test
    fun tapping_close_icon_invokes_onDismiss() {
        var invoked = 0
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                StatusSheetContent(
                    selectedModel = Model.OPUS_4_7,
                    onModelSelected = {},
                    selectedEffort = Effort.HIGH,
                    onEffortSelected = {},
                    yoloEnabled = false,
                    onYoloToggled = {},
                    onDismiss = { invoked++ },
                )
            }
        }

        composeTestRule.onNode(hasContentDescription("Close")).performClick()

        assertEquals(1, invoked)
    }

    @Test
    fun selected_row_reports_selected_semantics() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                StatusSheetContent(
                    selectedModel = Model.SONNET_4_6,
                    onModelSelected = {},
                    selectedEffort = Effort.HIGH,
                    onEffortSelected = {},
                    yoloEnabled = false,
                    onYoloToggled = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule
            .onNode(isSelectable() and hasAnyDescendant(hasText("Sonnet 4.6")))
            .assertIsSelected()
    }

    @Test
    fun renders_effort_section_with_all_five_chips() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                StatusSheetContent(
                    selectedModel = Model.OPUS_4_7,
                    onModelSelected = {},
                    selectedEffort = Effort.HIGH,
                    onEffortSelected = {},
                    yoloEnabled = false,
                    onYoloToggled = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNode(hasText("Effort")).assertIsDisplayed()
        composeTestRule.onNode(hasText("low")).assertIsDisplayed()
        composeTestRule.onNode(hasText("medium")).assertIsDisplayed()
        composeTestRule.onNode(hasText("high")).assertIsDisplayed()
        composeTestRule.onNode(hasText("xhigh")).assertIsDisplayed()
        composeTestRule.onNode(hasText("max")).assertIsDisplayed()
    }

    @Test
    fun tapping_low_chip_invokes_onEffortSelected_with_low() {
        val picks = mutableListOf<Effort>()
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                StatusSheetContent(
                    selectedModel = Model.OPUS_4_7,
                    onModelSelected = {},
                    selectedEffort = Effort.HIGH,
                    onEffortSelected = picks::add,
                    yoloEnabled = false,
                    onYoloToggled = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNode(hasText("low")).performClick()

        assertEquals(listOf(Effort.LOW), picks)
    }

    @Test
    fun selected_effort_chip_reports_selected_semantics() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                StatusSheetContent(
                    selectedModel = Model.OPUS_4_7,
                    onModelSelected = {},
                    selectedEffort = Effort.MAX,
                    onEffortSelected = {},
                    yoloEnabled = false,
                    onYoloToggled = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule
            .onNode(isSelectable() and hasAnyDescendant(hasText("max")))
            .assertIsSelected()
        composeTestRule
            .onNode(isSelectable() and hasAnyDescendant(hasText("low")))
            .assertIsNotSelected()
    }

    @Test
    fun renders_yolo_section_with_title_and_supporting_text() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                StatusSheetContent(
                    selectedModel = Model.OPUS_4_7,
                    onModelSelected = {},
                    selectedEffort = Effort.HIGH,
                    onEffortSelected = {},
                    yoloEnabled = false,
                    onYoloToggled = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNode(hasText("YOLO mode")).assertIsDisplayed()
        composeTestRule.onNode(hasText("Auto-accept tool calls")).assertIsDisplayed()
        composeTestRule
            .onNode(hasText("Claude runs commands without asking for confirmation. Use carefully."))
            .assertIsDisplayed()
    }

    @Test
    fun tapping_yolo_row_when_off_invokes_onYoloToggled_with_true() {
        val toggles = mutableListOf<Boolean>()
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                StatusSheetContent(
                    selectedModel = Model.OPUS_4_7,
                    onModelSelected = {},
                    selectedEffort = Effort.HIGH,
                    onEffortSelected = {},
                    yoloEnabled = false,
                    onYoloToggled = toggles::add,
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNode(hasText("Auto-accept tool calls")).performClick()

        assertEquals(listOf(true), toggles)
    }

    @Test
    fun tapping_yolo_row_when_on_invokes_onYoloToggled_with_false() {
        val toggles = mutableListOf<Boolean>()
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                StatusSheetContent(
                    selectedModel = Model.OPUS_4_7,
                    onModelSelected = {},
                    selectedEffort = Effort.HIGH,
                    onEffortSelected = {},
                    yoloEnabled = true,
                    onYoloToggled = toggles::add,
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNode(hasText("Auto-accept tool calls")).performClick()

        assertEquals(listOf(false), toggles)
    }

    @Test
    fun renders_context_window_section_as_unavailable_with_header_and_caption() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                StatusSheetContent(
                    selectedModel = Model.OPUS_4_7,
                    onModelSelected = {},
                    selectedEffort = Effort.HIGH,
                    onEffortSelected = {},
                    yoloEnabled = false,
                    onYoloToggled = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNode(hasText("Context window")).assertIsDisplayed()
        composeTestRule.onNode(hasText("Context usage unavailable")).assertIsDisplayed()
        composeTestRule
            .onNode(
                hasText(
                    "When full, oldest messages get dropped from claude's view " +
                        "(delimiter still shows; old messages stay in your scroll).",
                ),
            ).assertIsDisplayed()
        // No progress bar anywhere in the sheet: the figure is unknown, so no severity is shown.
        composeTestRule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assertCountEquals(0)
    }
}
