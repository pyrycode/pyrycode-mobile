package de.pyryco.mobile.ui.conversations.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
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
                    onDismiss = {},
                )
            }
        }

        composeTestRule
            .onNode(isSelectable() and hasAnyDescendant(hasText("Sonnet 4.6")))
            .assertIsSelected()
    }
}
