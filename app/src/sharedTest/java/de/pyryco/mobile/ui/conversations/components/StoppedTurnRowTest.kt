package de.pyryco.mobile.ui.conversations.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The stopped-turn row's copy (#1356), case for case against desktop's `stoppedTurnText`. The lead and every
 * fixed phrase are client-owned strings, so matching on whole labels checks that a report fills only its slot.
 *
 * Fixtures are hand-written literals, never captured live payloads.
 */
@RunWith(AndroidJUnit4::class)
class StoppedTurnRowTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun stopped(
        reason: String,
        category: String = "",
    ) = ThreadItem.StoppedTurn("turn-1", reason, category, Instant.parse("2026-10-02T10:00:00Z"))

    /** Each case's label, resolved in one composition (a rule takes one `setContent`). */
    private fun labels(cases: List<Pair<ThreadItem.StoppedTurn, ConversationAgent>>): List<String> {
        val labels = mutableListOf<String>()
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                cases.forEach { (item, agent) -> labels += stoppedTurnLabel(item, agent) }
            }
        }
        composeTestRule.waitForIdle()
        return labels
    }

    @Test
    fun each_reason_reads_as_desktop_copy() {
        val table =
            listOf(
                "max_turns" to "Stopped: turn limit reached",
                "budget_exhausted" to "Stopped: budget exhausted",
                "prompt_too_long" to "Stopped: context too long, compact or reset",
                "api_error" to "Stopped: API error",
                "hook_stopped" to "Stopped by a hook",
                "stop_hook_prevented" to "Stopped by a hook",
                "model_error" to "Stopped: model error",
                "future" to "Stopped: future",
                "" to "Stopped: error",
            )

        assertEquals(table.map { it.second }, labels(table.map { stopped(it.first) to ConversationAgent.Claude }))
    }

    @Test
    fun a_category_is_credited_to_the_conversations_agent() {
        val item = stopped("api_error", category = "overloaded")

        assertEquals(
            listOf("Stopped: API error (Claude reported: overloaded)", "Stopped: API error (Codex reported: overloaded)"),
            labels(listOf(item to ConversationAgent.Claude, item to ConversationAgent.Codex)),
        )
    }

    @Test
    fun hostile_report_text_cannot_alter_the_fixed_text() {
        // A row restored from disk is held to the same rule as a live one: the label re-applies it.
        val cases =
            listOf(
                stopped("<img>\nfuture\u0000") to ConversationAgent.Claude,
                stopped("\u202Eerror_x", category = "bad\u2028\u2066line") to ConversationAgent.Claude,
                stopped("x".repeat(257), category = "y".repeat(257)) to ConversationAgent.Codex,
            )

        assertEquals(
            listOf(
                "Stopped: <img>future",
                "Stopped: error_x (Claude reported: badline)",
                "Stopped: error",
            ),
            labels(cases),
        )
    }

    @Test
    fun the_row_draws_its_label_as_inert_text() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                StoppedTurnRow(item = stopped("prompt_too_long", category = "invalid_request"), agent = ConversationAgent.Codex)
            }
        }

        composeTestRule
            .onNodeWithText("Stopped: context too long, compact or reset (Codex reported: invalid_request)")
            .assertIsDisplayed()
        composeTestRule.onNode(hasClickAction()).assertDoesNotExist()
    }

    // #1608: Figma 685:3992 leaves 30dp from the bubble above to this row's text: the stream's standard
    // 16dp row gap (the previous row's own trailing gutter, outside this component) plus this row's own
    // 8dp top pad. This slice owns only the 8dp; a sibling with no trailing gutter of its own, as here,
    // leaves exactly that much.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun leaves_its_own_8dp_top_pad_above_the_text() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                Column {
                    androidx.compose.foundation.layout
                        .Box(Modifier.testTag("bubble-above").height(10.dp))
                    StoppedTurnRow(item = stopped("max_turns"), agent = ConversationAgent.Claude)
                }
            }
        }

        val above = composeTestRule.onNodeWithTag("bubble-above").getUnclippedBoundsInRoot()
        val text = composeTestRule.onNodeWithText("Stopped: turn limit reached").getUnclippedBoundsInRoot()

        assertEquals(8f, (text.top - above.bottom).value, 0.5f)
    }
}
