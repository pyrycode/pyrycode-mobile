package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * First coverage for [ThreadStatusRow] (#602). The row renders model and effort only — the
 * context-usage segment it used to quote was derived from a hardcoded stub nobody measured, so it
 * was deleted rather than reworded; the Status sheet (#601) carries the honest explanation and #591
 * restores a measured figure.
 *
 * The composable is exercised directly rather than through [ThreadScreen] so the assertions stay
 * unambiguous and the known-red sibling tests in this package cannot mask a failure here.
 */
@RunWith(AndroidJUnit4::class)
class ThreadStatusRowTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun string(resId: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)

    @Test
    fun renders_model_and_effort_only() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadStatusRow(model = "Opus 4.7", effort = "high", onExpandClick = {})
            }
        }

        // AC #2 — exactly two segments. The match is exact (onNodeWithText defaults to
        // substring = false), which is what catches a dangling trailing separator: the old
        // " · $effort · " span would render "Opus 4.7 · high · " and fail here.
        composeTestRule.onNodeWithText("Opus 4.7 · high").assertIsDisplayed()
    }

    @Test
    fun no_usage_segment_is_rendered() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadStatusRow(model = "Opus 4.7", effort = "high", onExpandClick = {})
            }
        }

        // AC #2 — the fabricated percentage is gone; the bare "%" guards a partial reintroduction.
        composeTestRule.onNodeWithText("% used", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("%", substring = true).assertDoesNotExist()
    }

    @Test
    fun separator_is_built_from_the_parameters() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadStatusRow(model = "Sonnet 4.6", effort = "medium", onExpandClick = {})
            }
        }

        // AC #2 — the surviving string is composed from the parameters, not hardcoded around the
        // deleted span.
        composeTestRule.onNodeWithText("Sonnet 4.6 · medium").assertIsDisplayed()
    }

    @Test
    fun expand_affordance_is_unchanged() {
        var expandCount = 0
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadStatusRow(model = "Opus 4.7", effort = "high", onExpandClick = { expandCount++ })
            }
        }

        // Regression guard — the trailing expand icon, its content description and the row's
        // clickable behaviour are untouched by the removal.
        composeTestRule.onNodeWithContentDescription(string(R.string.cd_thread_status_expand)).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(string(R.string.cd_thread_status_expand)).performClick()
        assertEquals(1, expandCount)
    }
}
