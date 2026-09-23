package de.pyryco.mobile.ui.conversations.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.ui.semantics.Role as SemanticsRole

/**
 * `MessageBubble`'s supplied treatment (#644) — the rung-2 component-render layer of
 * `docs/e2e-interactive-stream.md`, alongside `SessionBoundaryDelimiterTest` and `ToolCallRowTest`.
 *
 * Nothing here asserts a formatted timestamp: the meta row renders through the device's locale and
 * zone, so a literal would redden off a de-DE host. `MessageMetaRowFormatTest` owns the format, these
 * tests own the arrangement and the copy behaviour.
 */
@RunWith(AndroidJUnit4::class)
class MessageBubbleTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    /**
     * Captures every clipboard write in order, so a test can assert both *what* landed and that
     * nothing else did.
     */
    private class RecordingClipboard : ClipboardManager {
        val writes = mutableListOf<String>()

        override fun setText(annotatedString: AnnotatedString) {
            writes += annotatedString.text
        }

        override fun getText(): AnnotatedString? = writes.lastOrNull()?.let { AnnotatedString(it) }

        override fun hasText(): Boolean = writes.isNotEmpty()
    }

    private val copyDescription: String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getString(R.string.cd_thread_copy_message)

    private fun message(
        role: Role,
        content: String,
        isStreaming: Boolean = false,
    ) = Message(
        id = "m-${role.name}",
        sessionId = "s1",
        role = role,
        content = content,
        timestamp = Instant.parse("2026-01-13T12:55:00Z"),
        isStreaming = isStreaming,
    )

    private fun setBothRoles(clipboard: ClipboardManager = RecordingClipboard()) {
        composeTestRule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                    Surface {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            MessageBubble(message(Role.Assistant, ASSISTANT_BODY))
                            MessageBubble(message(Role.User, USER_BODY))
                        }
                    }
                }
            }
        }
    }

    // AC #1 (both roles render as bubbles) + AC #2 (each bubble ends with a meta row).
    @Test
    fun bothRoles_renderBodyAndOwnMetaRow() {
        setBothRoles()

        composeTestRule.onNodeWithText(ASSISTANT_BODY, substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText(USER_BODY, substring = true).assertIsDisplayed()
        // One meta row per bubble, not one shared row and not a row on only the boxed role.
        assertEquals(
            2,
            composeTestRule
                .onAllNodesWithContentDescription(copyDescription)
                .fetchSemanticsNodes()
                .size,
        )
    }

    // AC #1 (each side's alignment, and the 272dp-in-372dp geometry the 100dp role inset produces).
    // Read the body text rects rather than tagging the Surface: the inset is observable as "this role's
    // content never reaches within MessageRoleInset of the opposite edge", which is the property that
    // actually matters and which a hardcoded max-width assertion would not catch.
    @Test
    fun roleAlignment_userSitsRightOfAssistant_andEachClearsTheOppositeInset() {
        setBothRoles()

        val root = composeTestRule.onRoot().getUnclippedBoundsInRoot()
        val assistant =
            composeTestRule
                .onNodeWithText(ASSISTANT_BODY, substring = true, useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
        val user =
            composeTestRule
                .onNodeWithText(USER_BODY, substring = true, useUnmergedTree = true)
                .getUnclippedBoundsInRoot()

        assertTrue(
            "user bubble must sit right of the assistant bubble " +
                "(assistantLeft=${assistant.left}, userLeft=${user.left})",
            user.left > assistant.left,
        )
        assertTrue(
            "assistant content must clear the trailing inset " +
                "(assistantRight=${assistant.right}, rootRight=${root.right})",
            assistant.right <= root.right - MessageRoleInset,
        )
        assertTrue(
            "user content must clear the leading inset (userLeft=${user.left}, rootLeft=${root.left})",
            user.left >= root.left + MessageRoleInset,
        )
        // Both bubbles sit inside the design's content gutter rather than bleeding to the screen edge.
        assertTrue(
            "assistant content must sit inside the gutter (assistantLeft=${assistant.left})",
            assistant.left >= root.left + MessageContentGutter,
        )
        assertTrue(
            "user content must sit inside the gutter (userRight=${user.right})",
            user.right <= root.right - MessageContentGutter,
        )
    }

    // AC #1 — 272dp is a *maximum*, not a fixed width: a short assistant body hugs its content, which is
    // what the Figma's short assistant instance (`I533:1956;132:4539`, 205dp) shows. Regression guard for
    // `fillMaxWidth()` on the finalized body: that sets minWidth = maxWidth, so every assistant bubble
    // measured at the full lane and the hug was invisible. Read off the bubble Surface, because a body
    // `Text` hugs its own content whether or not the container does — the container is the only node the
    // regression moves.
    @Test
    fun shortAssistantBody_hugsItsContent_whileALongOneStillGrowsToTheLane() {
        composeTestRule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                Surface {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        MessageBubble(message(Role.Assistant, SHORT_BODY))
                        MessageBubble(message(Role.Assistant, LONG_BODY))
                    }
                }
            }
        }

        val root = composeTestRule.onRoot().getUnclippedBoundsInRoot()
        // What an assistant bubble may occupy: the full width less both gutters and the trailing inset.
        val lane = root.width - MessageContentGutter * 2 - MessageRoleInset
        val bubbles = composeTestRule.onAllNodesWithTag(MESSAGE_BUBBLE_TEST_TAG, useUnmergedTree = true)
        val short = bubbles[0].getUnclippedBoundsInRoot().width
        val long = bubbles[1].getUnclippedBoundsInRoot().width

        // The margin matters: a lane-pinned bubble measures a fraction *under* the computed lane
        // (271.24 against 271.43 on this device) because the enclosing padding rounds through px, so a
        // bare `short < lane` passes even when the hug is broken. A real hug here is ~150dp — the meta
        // row plus the bubble's own padding — so 40dp is far outside the rounding and far inside the hug.
        assertTrue(
            "a short assistant bubble must hug its content, not fill the lane (width=$short, lane=$lane)",
            short < lane - HUG_MARGIN,
        )
        // Paired so the first assertion cannot be satisfied by a blanket shrink: width tracks content.
        assertTrue(
            "a long assistant bubble must still grow toward the lane (long=$long, short=$short)",
            long > short,
        )
    }

    // AC #3 — the control copies exactly its own message's text, and copying one message leaves every
    // other message's text off the clipboard.
    @Test
    fun copy_putsOnlyThatMessagesTextOnTheClipboard() {
        val clipboard = RecordingClipboard()
        setBothRoles(clipboard)

        // Index 0 is the assistant bubble: it is emitted first in the column above.
        composeTestRule.onAllNodesWithContentDescription(copyDescription)[0].performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(ASSISTANT_BODY), clipboard.writes)
        assertFalse(
            "the user message's text must not reach the clipboard",
            clipboard.writes.single().contains(USER_BODY),
        )
    }

    @Test
    fun copy_fromTheUserBubble_putsOnlyTheUserText_onTheClipboard() {
        val clipboard = RecordingClipboard()
        setBothRoles(clipboard)

        composeTestRule.onAllNodesWithContentDescription(copyDescription)[1].performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(USER_BODY), clipboard.writes)
    }

    // AC #3 — the accessible name, in the cd_thread_* family, on a control that reports as a button.
    @Test
    fun copyControl_carriesItsAccessibleNameAndButtonRole() {
        setBothRoles()

        composeTestRule
            .onAllNodesWithContentDescription(copyDescription)[0]
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.Role, SemanticsRole.Button),
            )
    }

    // AC #4 — the progressive reveal and its caret keep working inside the new container, and the copy
    // control yields what has arrived rather than the revealed prefix.
    //
    // Left on the default auto-advancing clock deliberately. The caret's producer is a `while (true)`
    // blink, but it is idle between its 500ms delays, so the harness reaches idle the same way
    // `ScriptedThreadRenderTest` already relies on while driving streaming rows. `waitUntil` polls for a
    // blink-on frame instead of sampling one, which is what makes the caret assertion stable.
    @Test
    fun copy_onStreamingMessage_yieldsWhatHasArrived_andTheCaretStillRenders() {
        val clipboard = RecordingClipboard()
        composeTestRule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                    Surface {
                        MessageBubble(message(Role.Assistant, STREAMING_BODY, isStreaming = true))
                    }
                }
            }
        }

        composeTestRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            composeTestRule
                .onAllNodesWithText(STREAMING_CARET, substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            composeTestRule
                .onAllNodesWithText(STREAMING_BODY, substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeTestRule.onNodeWithContentDescription(copyDescription).performClick()
        composeTestRule.waitForIdle()

        // The full arrived content, never the revealed prefix and never with the caret glyph attached —
        // the control is handed `Message.content`, not anything read back out of the render.
        assertEquals(listOf(STREAMING_BODY), clipboard.writes)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L

        // See shortAssistantBody_hugsItsContent_whileALongOneStillGrowsToTheLane.
        val HUG_MARGIN = 40.dp

        // Plain alphanumeric bodies: MarkdownText renders them 1:1, they do not collide with each
        // other, and neither is a substring of the other.
        const val ASSISTANT_BODY = "alpha assistant line"
        const val USER_BODY = "omega user line"

        // Short enough that the meta row, not the body, sets the bubble's width — the clearest hug case.
        const val SHORT_BODY = "ok"

        // Long enough to wrap at any phone width, so it grows to the lane the short one must not fill.
        const val LONG_BODY =
            "a considerably longer assistant reply that wraps across several lines at any " +
                "phone width and therefore grows out to the maximum the role inset allows"

        // Short so the 50-chars-per-second reveal finishes well inside the timeout.
        const val STREAMING_BODY = "kappa stream"

        // MessageBubble.STREAMING_CARET_GLYPH.
        const val STREAMING_CARET = "▎"
    }
}
