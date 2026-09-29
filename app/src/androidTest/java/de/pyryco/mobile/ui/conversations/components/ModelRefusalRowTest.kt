package de.pyryco.mobile.ui.conversations.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Deterministic Compose coverage for the model refusal row (#875). The title words, "unknown model", the
 * attribution and the truncation mark are client-owned strings, so matching on them also checks that
 * claude's values only ever fill their own slots.
 *
 * Fixtures are hand-written literals, never captured live payloads.
 */
@RunWith(AndroidJUnit4::class)
class ModelRefusalRowTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    // Matches the string resources added in #873 and #875.
    private val attribution = "Claude: "
    private val truncatedMark = " (truncated)"

    private fun refusal(
        originalModel: String = "claude-opus-5-5",
        fallbackModel: String? = "claude-sonnet-5",
        banner: String = "Retried on another model.",
        bannerTruncated: Boolean = false,
    ) = ThreadItem.ModelRefusal(originalModel, fallbackModel, banner, bannerTruncated, Instant.parse("2026-09-23T12:00:00Z"))

    private fun setContent(item: ThreadItem.ModelRefusal) {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ModelRefusalRow(item = item, agent = ConversationAgent.Claude)
            }
        }
    }

    @Test
    fun a_fallback_reads_refused_on_and_continued_on() {
        setContent(refusal())

        composeTestRule.onNodeWithText("Refused on claude-opus-5-5, continued on claude-sonnet-5").assertIsDisplayed()
    }

    @Test
    fun a_no_fallback_reads_refused_by() {
        setContent(refusal(fallbackModel = null))

        composeTestRule.onNodeWithText("Refused by claude-opus-5-5").assertIsDisplayed()
    }

    @Test
    fun an_empty_model_reads_unknown_model() {
        setContent(refusal(originalModel = "", fallbackModel = "\u001b[0m"))

        composeTestRule.onNodeWithText("Refused on unknown model, continued on unknown model").assertIsDisplayed()
    }

    @Test
    fun the_banner_opens_in_place_attributed_to_claude_with_escapes_stripped() {
        setContent(refusal(banner = "Declined \u001b[31mhere\u001b[0m."))

        composeTestRule.onNodeWithText("${attribution}Declined here.").assertDoesNotExist()
        composeTestRule.onNode(hasClickAction()).performClick()
        composeTestRule.onNodeWithText("${attribution}Declined here.").assertIsDisplayed()
    }

    @Test
    fun a_cut_banner_is_marked_and_an_uncut_one_is_not() {
        setContent(refusal(bannerTruncated = true))

        composeTestRule.onNode(hasClickAction()).performClick()
        composeTestRule.onNodeWithText("${attribution}Retried on another model.$truncatedMark").assertIsDisplayed()
    }

    @Test
    fun an_uncut_banner_carries_no_mark() {
        setContent(refusal(bannerTruncated = false))

        composeTestRule.onNode(hasClickAction()).performClick()
        composeTestRule.onNodeWithText("${attribution}Retried on another model.").assertIsDisplayed()
        composeTestRule.onNodeWithText(truncatedMark, substring = true).assertDoesNotExist()
    }

    @Test
    fun a_row_without_a_banner_has_no_click_action() {
        setContent(refusal(banner = ""))

        composeTestRule.onNodeWithText("Refused on claude-opus-5-5, continued on claude-sonnet-5").assertIsDisplayed()
        composeTestRule.onNode(hasClickAction()).assertDoesNotExist()
    }

    @Test
    fun tapping_the_far_edge_of_the_row_opens_and_closes_details() {
        setContent(refusal())

        composeTestRule.onNodeWithText("Show details").assertIsDisplayed()
        composeTestRule.onNode(hasClickAction()).performTouchInput {
            click(Offset(center.x * 2f - 2f, center.y))
        }
        composeTestRule.onNodeWithText("${attribution}Retried on another model.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hide details").assertIsDisplayed()
        composeTestRule.onNode(hasClickAction()).performTouchInput {
            click(Offset(center.x * 2f - 2f, center.y))
        }
        composeTestRule.onNodeWithText("${attribution}Retried on another model.").assertDoesNotExist()
    }

    @Test
    fun long_explanation_wraps_inside_the_message_gutter() {
        val explanation = "The request could not run on the selected model and continued on another model. ".repeat(3)
        setContent(refusal(banner = explanation))

        composeTestRule.onNode(hasClickAction()).performClick()
        val text = composeTestRule.onNodeWithText("$attribution$explanation").getUnclippedBoundsInRoot()
        val root = composeTestRule.onRoot().getUnclippedBoundsInRoot()
        assertEquals(20f, text.left.value - root.left.value, 0.5f)
        assertEquals(20f, root.right.value - text.right.value, 0.5f)
    }
}
