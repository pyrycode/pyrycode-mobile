package de.pyryco.mobile.ui.conversations.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.repository.BannerLevel
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Deterministic Compose coverage for claude's banner row (#873). The attribution, the truncation mark and
 * the warning icon's description are client-owned strings, so matching on them also checks that claude's
 * text only ever fills the one slot between them.
 *
 * Fixtures are hand-written literals, never captured live payloads.
 */
@RunWith(AndroidJUnit4::class)
class BannerNoticeRowTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    // Matches the string resources added in #873.
    private val attribution = "Claude: "
    private val truncatedMark = " (truncated)"
    private val warningDescription = "Warning from Claude"

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

        composeTestRule.onNodeWithText("${attribution}Blocked by hook").assertIsDisplayed()
    }

    @Test
    fun a_truncated_banner_is_marked_as_cut() {
        setContent(banner(truncated = true))

        composeTestRule.onNodeWithText("${attribution}Blocked by hook$truncatedMark").assertIsDisplayed()
    }

    @Test
    fun an_untruncated_banner_carries_no_mark() {
        setContent(banner(truncated = false))

        composeTestRule.onNodeWithText("${attribution}Blocked by hook").assertIsDisplayed()
        composeTestRule.onNodeWithText(truncatedMark, substring = true).assertDoesNotExist()
    }

    @Test
    fun a_warning_shows_the_warning_icon() {
        setContent(banner(level = BannerLevel.Warning))

        composeTestRule.onNodeWithContentDescription(warningDescription).assertIsDisplayed()
    }

    @Test
    fun a_notice_shows_no_warning_icon() {
        setContent(banner(level = BannerLevel.Notice))

        composeTestRule.onNodeWithText("${attribution}Blocked by hook").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(warningDescription).assertDoesNotExist()
    }

    @Test
    fun the_row_has_no_click_action() {
        setContent(banner(text = "Visit https://evil.example now"))

        composeTestRule.onNode(hasClickAction()).assertDoesNotExist()
    }
}
