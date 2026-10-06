package de.pyryco.mobile.ui.conversations.components

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.UnrecognizedSite
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Deterministic Compose coverage for the unrecognized-message row (#608).
 *
 * Every assertion matches on a **client-owned** string — the fixed label, a site label, the truncation
 * note — so the suite doubles as a check that the copy is resource-backed rather than inlined, and that
 * the daemon's `site` value only ever *selects* a string.
 *
 * Fixtures are hand-written literals, never captured live payloads (§ Security posture).
 *
 * Not covered here, deliberately: AC 1's interleaving / ordering / no-collapse clauses are properties of
 * the `LazyColumn` key and the `Box` placement in `ThreadScreen`, and nothing in this slice can *produce*
 * two rows to interleave — that assertion belongs to #609. Above-delimiter dimming is inherited
 * structurally from the wrapping `Box`, so it is not new behaviour to test.
 */
@RunWith(AndroidJUnit4::class)
class UnrecognizedMessageRowTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    // Matches the string resources added in #608.
    private val label = "Unrecognized message"
    private val truncationNote = "Payload truncated by the daemon."
    private val assistantBlockSite = "assistant block"
    private val undecodableSite = "could not be decoded"

    private val shortRaw = """  {"type":"thinking_delta","delta":"…"}  """

    private fun unrecognized(
        site: UnrecognizedSite = UnrecognizedSite.AssistantBlock,
        messageType: String = "thinking_delta",
        raw: String = shortRaw,
        truncated: Boolean = false,
    ) = ThreadItem.UnrecognizedMessage(
        id = "frame-1",
        site = site,
        messageType = messageType,
        raw = raw,
        truncated = truncated,
        occurredAt = Instant.parse("2026-07-30T12:00:00Z"),
    )

    private fun setContent(item: ThreadItem.UnrecognizedMessage) {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                UnrecognizedMessageRow(item = item)
            }
        }
    }

    @Test
    fun collapsed_shows_the_summary_and_hides_the_payload() {
        setContent(unrecognized())

        composeTestRule.onNodeWithText("$label · thinking_delta · $assistantBlockSite").assertIsDisplayed()
        composeTestRule.onNodeWithText(shortRaw).assertDoesNotExist()
    }

    @Test
    fun tapping_expands_the_payload_in_place() {
        setContent(unrecognized())

        composeTestRule.onNode(hasClickAction()).performClick()

        composeTestRule.onNodeWithText(shortRaw).assertIsDisplayed()
        // In place, not a navigation: the collapsed summary is still on screen.
        composeTestRule.onNodeWithText("$label · thinking_delta · $assistantBlockSite").assertIsDisplayed()
    }

    @Test
    fun tapping_again_collapses_the_payload() {
        setContent(unrecognized())

        composeTestRule.onNode(hasClickAction()).performClick()
        composeTestRule.onNode(hasClickAction()).performClick()

        composeTestRule.onNodeWithText(shortRaw).assertDoesNotExist()
    }

    @Test
    fun expanded_payload_renders_the_raw_string_verbatim() {
        setContent(unrecognized(raw = shortRaw))

        composeTestRule.onNode(hasClickAction()).performClick()

        // Byte-identical to the input, including the leading and trailing whitespace: never parsed,
        // trimmed, or reformatted.
        composeTestRule.onNodeWithText(shortRaw).assertIsDisplayed()
    }

    @Test
    fun truncation_note_is_shown_only_when_the_daemon_cut_the_payload() {
        setContent(unrecognized(truncated = true))

        composeTestRule.onNode(hasClickAction()).performClick()

        composeTestRule.onNodeWithText(truncationNote).assertIsDisplayed()
    }

    @Test
    fun truncation_note_is_absent_when_the_flag_is_clear() {
        setContent(unrecognized(truncated = false))

        composeTestRule.onNode(hasClickAction()).performClick()

        composeTestRule.onNodeWithText(shortRaw).assertIsDisplayed()
        composeTestRule.onNodeWithText(truncationNote).assertDoesNotExist()
    }

    @Test
    fun empty_message_type_omits_the_slot_rather_than_rendering_an_empty_literal() {
        setContent(
            unrecognized(
                site = UnrecognizedSite.Undecodable,
                messageType = "",
                raw = "not json at all",
            ),
        )

        // The whole collapsed line, matched exactly: asserting the label's presence alone would pass on a
        // dangling separator or an empty-quote artefact, so the shape is what is asserted.
        composeTestRule.onNodeWithText("$label · $undecodableSite").assertIsDisplayed()
    }

    // #1109: the two Codex sites carry their own client-owned labels.
    @Test
    fun codex_method_site_is_labelled_codex_notification() {
        setContent(unrecognized(site = UnrecognizedSite.CodexMethod, messageType = "error"))

        composeTestRule.onNodeWithText("$label · error · Codex notification").assertIsDisplayed()
    }

    @Test
    fun codex_item_site_is_labelled_codex_item() {
        setContent(unrecognized(site = UnrecognizedSite.CodexItem, messageType = "webSearch"))

        composeTestRule.onNodeWithText("$label · webSearch · Codex item").assertIsDisplayed()
    }

    @Test
    fun expanded_state_survives_configuration_change() {
        val restorationTester = StateRestorationTester(composeTestRule)
        restorationTester.setContent {
            PyrycodeMobileTheme {
                UnrecognizedMessageRow(item = unrecognized())
            }
        }

        composeTestRule.onNode(hasClickAction()).performClick()
        composeTestRule.onNodeWithText(shortRaw).assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        composeTestRule.onNodeWithText(shortRaw).assertIsDisplayed()
    }

    // #1608: Figma 685:4112 leaves the stream's standard 16dp `Message area` gap below this row, the same
    // rhythm every other row keeps, not the 12dp this row used on its own — so the collapsed row's own
    // reported height (its 34dp content plus its own trailing gutter) grows from 46dp to 50dp.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun collapsed_row_height_includes_the_standard_16dp_trailing_gutter() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                Column { UnrecognizedMessageRow(item = unrecognized(), modifier = Modifier.testTag("row-1")) }
            }
        }

        val row = composeTestRule.onNodeWithTag("row-1").getUnclippedBoundsInRoot()

        assertEquals(50f, row.height.value, 0.5f)
    }
}
