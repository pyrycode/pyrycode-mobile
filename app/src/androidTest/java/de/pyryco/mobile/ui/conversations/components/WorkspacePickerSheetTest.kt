package de.pyryco.mobile.ui.conversations.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspacePickerSheetTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val sampleRecents =
        listOf(
            "~/Workspace/Projects/KitchenClaw",
            "~/Workspace/Projects/pyrycode",
            "~/Workspace/Projects/pyrycode-mobile",
        )

    @Test
    fun recent_rows_render_full_paths() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                WorkspacePickerSheetContent(
                    recent = sampleRecents,
                    onPick = {},
                    onCreateNew = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNode(hasText("Recent")).assertIsDisplayed()
        sampleRecents.forEach { path ->
            composeTestRule.onNode(hasText(path)).assertIsDisplayed()
        }
    }

    @Test
    fun recent_row_tap_invokes_onPick_with_full_path() {
        val picked = mutableListOf<String>()
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                WorkspacePickerSheetContent(
                    recent = sampleRecents,
                    onPick = picked::add,
                    onCreateNew = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNode(hasText("~/Workspace/Projects/pyrycode")).performClick()

        assertEquals(listOf("~/Workspace/Projects/pyrycode"), picked)
    }

    @Test
    fun recent_header_is_omitted_when_recent_is_empty() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                WorkspacePickerSheetContent(
                    recent = emptyList(),
                    onPick = {},
                    onCreateNew = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNode(hasText("Recent")).assertDoesNotExist()

        composeTestRule.onNode(hasText("Other")).assertIsDisplayed()
        composeTestRule
            .onNode(hasText("Create new folder under pyry-workspace…"))
            .assertIsDisplayed()
    }

    @Test
    fun create_new_row_invokes_onCreateNew_on_tap() {
        var invoked = 0
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                WorkspacePickerSheetContent(
                    recent = sampleRecents,
                    onPick = {},
                    onCreateNew = { invoked++ },
                    onDismiss = {},
                )
            }
        }

        composeTestRule
            .onNode(hasText("Create new folder under pyry-workspace…"))
            .performClick()

        assertEquals(1, invoked)
    }

    @Test
    fun close_icon_invokes_onDismiss_on_tap() {
        var invoked = 0
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                WorkspacePickerSheetContent(
                    recent = sampleRecents,
                    onPick = {},
                    onCreateNew = {},
                    onDismiss = { invoked++ },
                )
            }
        }

        composeTestRule.onNode(hasContentDescription("Close")).performClick()

        assertEquals(1, invoked)
    }
}
