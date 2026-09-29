package de.pyryco.mobile.ui.conversations.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.repository.BannerLevel
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Deterministic Compose coverage for session notices. The warning prefix, attribution and truncation
 * mark are client-owned strings, so matching on them checks that agent text fills only its own slot.
 *
 * Fixtures are hand-written literals, never captured live payloads.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BannerNoticeRowTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    // Matches the string resources added in #873.
    private val attribution = "Claude: "
    private val truncatedMark = " (truncated)"

    private fun banner(
        level: BannerLevel = BannerLevel.Warning,
        text: String = "Blocked by hook",
        truncated: Boolean = false,
    ) = ThreadItem.Banner(level = level, text = text, truncated = truncated, occurredAt = Instant.parse("2026-09-23T12:00:00Z"))

    private fun setContent(item: ThreadItem.Banner) {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                BannerNoticeRow(item = item, agent = ConversationAgent.Claude)
            }
        }
    }

    @Test
    fun shows_the_text_attributed_to_claude_with_escapes_stripped() {
        setContent(banner(text = "Blocked \u001b[31mby\u001b[0m hook"))

        composeTestRule.onNodeWithText("Warning · ${attribution}Blocked by hook").assertIsDisplayed()
    }

    @Test
    fun a_truncated_banner_is_marked_as_cut() {
        setContent(banner(truncated = true))

        composeTestRule.onNodeWithText("Warning · ${attribution}Blocked by hook$truncatedMark").assertIsDisplayed()
    }

    @Test
    fun an_untruncated_banner_carries_no_mark() {
        setContent(banner(truncated = false))

        composeTestRule.onNodeWithText("Warning · ${attribution}Blocked by hook").assertIsDisplayed()
        composeTestRule.onNodeWithText(truncatedMark, substring = true).assertDoesNotExist()
    }

    @Test
    fun a_warning_names_itself_in_text_without_an_icon() {
        setContent(banner(level = BannerLevel.Warning))

        composeTestRule.onNodeWithText("Warning · ${attribution}Blocked by hook").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Warning from Claude").assertDoesNotExist()
    }

    @Test
    fun a_notice_has_no_warning_prefix() {
        setContent(banner(level = BannerLevel.Notice))

        composeTestRule.onNodeWithText("${attribution}Blocked by hook").assertIsDisplayed()
        composeTestRule.onNodeWithText("Warning", substring = true).assertDoesNotExist()
    }

    @Test
    fun the_row_has_no_click_action() {
        setContent(banner(text = "Visit https://evil.example now"))

        composeTestRule.onNode(hasClickAction()).assertDoesNotExist()
    }

    @Test
    fun long_warning_wraps_inside_the_message_gutter() {
        val text = "A hook blocked this request because the requested workspace is unavailable. ".repeat(3)
        setContent(banner(text = text))

        val row = composeTestRule.onNodeWithText("Warning · ${attribution}$text").getUnclippedBoundsInRoot()
        val root = composeTestRule.onRoot().getUnclippedBoundsInRoot()
        assertEquals(20f, row.left.value - root.left.value, 0.5f)
        assertEquals(20f, root.right.value - row.right.value, 0.5f)
        assertTrue(row.bottom.value - row.top.value > 40f)
    }
}
