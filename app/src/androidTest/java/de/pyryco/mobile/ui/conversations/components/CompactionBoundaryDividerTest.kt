package de.pyryco.mobile.ui.conversations.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Deterministic Compose coverage for the compaction divider (#874). The label rules themselves are covered
 * on the JVM by `CompactionBoundaryLabelTest`; this proves the row draws that label and stays inert.
 */
@RunWith(AndroidJUnit4::class)
class CompactionBoundaryDividerTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setContent(item: ThreadItem.CompactionBoundary) {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                CompactionBoundaryDivider(item = item)
            }
        }
    }

    @Test
    fun shows_the_sized_manual_label() {
        setContent(ThreadItem.CompactionBoundary(24000, 3000, manual = true, occurredAt = Instant.parse("2026-09-23T12:00:00Z")))

        composeTestRule.onNodeWithText("Conversation compacted, 24k → 3k tokens by you").assertIsDisplayed()
    }

    @Test
    fun a_missing_count_claims_no_size_and_the_row_is_inert() {
        setContent(ThreadItem.CompactionBoundary(24000, null, manual = false, occurredAt = Instant.parse("2026-09-23T12:00:00Z")))

        composeTestRule.onNodeWithText("Conversation compacted").assertIsDisplayed()
        composeTestRule.onNode(hasClickAction()).assertDoesNotExist()
    }
}
