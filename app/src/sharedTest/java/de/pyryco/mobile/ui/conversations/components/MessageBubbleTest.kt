package de.pyryco.mobile.ui.conversations.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
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
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.semantics.Role as SemanticsRole

/**
 * `MessageBubble`'s supplied treatment (#644) — the rung-2 component-render layer of
 * `docs/e2e-interactive-stream.md`, alongside `SessionBoundaryDelimiterTest` and `ToolCallRowTest`.
 *
 * Nothing here asserts a formatted timestamp: the meta row renders through the device's locale and
 * zone, so a literal would redden off a de-DE host. `MessageMetaRowFormatTest` owns the format, these
 * tests own the arrangement and the copy behaviour.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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

    @Test
    fun reply_isAlwaysVisibleForBothRoles() {
        setBothRoles()
        composeTestRule.onAllNodesWithContentDescription("Reply to this message").assertCountEquals(2)
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

    @Test
    fun sideCopy_hasA48dpSquareTarget_outsideTheTimestampRow() {
        setBothRoles()

        val controls = composeTestRule.onAllNodesWithContentDescription(copyDescription)
        assertEquals(2, controls.fetchSemanticsNodes().size)
        repeat(2) { index ->
            val bounds = controls[index].getUnclippedBoundsInRoot()
            assertEquals(48f, bounds.width.value, 1f)
            assertEquals(48f, bounds.height.value, 1f)
        }
    }

    @Test
    fun user_blank_line_separates_plain_text_paragraphs_by_the_design_gap_and_copy_keeps_source() {
        val source = "First paragraph.\n\nSecond paragraph."
        val clipboard = RecordingClipboard()
        composeTestRule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                    Surface { MessageBubble(message(Role.User, source)) }
                }
            }
        }

        val first = composeTestRule.onNodeWithText("First paragraph.").getUnclippedBoundsInRoot()
        val second = composeTestRule.onNodeWithText("Second paragraph.").getUnclippedBoundsInRoot()
        assertEquals(BubbleContentSpacing.value, (second.top - first.bottom).value, 1.5f)
        composeTestRule.onNodeWithContentDescription(copyDescription).performClick()
        assertEquals(listOf(source), clipboard.writes)
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
        val lane = root.width - MessageContentGutter * 2 - 40.dp - 25.dp
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
                        MessageBubble(message(Role.Assistant, STREAMING_BODY, isStreaming = true), metaRowVisible = false)
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

    @Test
    fun streamingReveal_frequentAppends_keepProgressAndCatchUpWithoutLosingThePrefix() {
        composeTestRule.mainClock.autoAdvance = false
        val arrived = mutableStateOf("word ".repeat(400)) // A 2000-character initial backlog.
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                MessageBubble(
                    message(Role.Assistant, arrived.value, isStreaming = true),
                    metaRowVisible = false,
                )
            }
        }

        fun revealedText(): String =
            composeTestRule
                .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .single()
                .config[SemanticsProperties.Text]
                .single()
                .text
                .removeSuffix(STREAMING_CARET)
                .trimEnd()

        var previous = revealedText()
        val snapshots = mutableListOf<Pair<Long, String>>()
        repeat(128) { tick ->
            composeTestRule.runOnIdle { arrived.value += "word " }
            snapshots += composeTestRule.mainClock.currentTime to arrived.value.trimEnd()
            // One 16 ms frame per arrival is faster than the 33 ms reveal interval.
            composeTestRule.mainClock.advanceTimeByFrame()
            val revealed = revealedText()
            assertTrue("the revealed prefix must not shrink at tick $tick", revealed.startsWith(previous))
            assertTrue(arrived.value.startsWith(revealed))
            assertTrue("steps must end on whole words", revealed.isEmpty() || revealed.endsWith("word"))
            if (tick >= 4) assertTrue("reveal must advance during arrivals", revealed.isNotEmpty())
            snapshots.filter { (time, _) -> composeTestRule.mainClock.currentTime - time >= 512L }.forEach { (_, text) ->
                // 495 ms reveal budget plus one presentation frame: about half a second.
                assertTrue("each arrived snapshot must catch up during arrivals", revealed.startsWith(text))
            }
            previous = revealed
        }

        composeTestRule.mainClock.advanceTimeBy(512L)
        assertEquals(arrived.value.trimEnd(), revealedText())
        // After catching up, the same producer must still reveal a later arrival.
        composeTestRule.runOnIdle { arrived.value += "word " }
        composeTestRule.mainClock.advanceTimeBy(512L)
        assertEquals(arrived.value.trimEnd(), revealedText())
    }

    @Test
    fun sideCopy_at412dp_keepsThe307dpBubble_andDarkGeometry() = assertSideGeometry(412, true)

    @Test
    fun sideCopy_at320dp_wrapsWithoutOverlap_andLightGeometry() = assertSideGeometry(320, false)

    @OptIn(ExperimentalTestApi::class)
    private fun assertSideGeometry(
        width: Int,
        dark: Boolean,
    ) {
        val clipboard = RecordingClipboard()
        var toggles = 0
        composeTestRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, 892.dp))) {
                PyrycodeMobileTheme(darkTheme = dark) {
                    CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                        Surface(Modifier.fillMaxWidth().testTag("message-fixture")) {
                            Column(Modifier.fillMaxWidth()) {
                                listOf(Role.Assistant, Role.User).forEach { role ->
                                    MessageBubble(message(role, LONG_BODY), metaRowVisible = false, onToggleMetaRow = { toggles++ })
                                }
                            }
                        }
                    }
                }
            }
        }
        val root = composeTestRule.onNodeWithTag("message-fixture").getUnclippedBoundsInRoot()
        val bubbles = composeTestRule.onAllNodesWithTag(MESSAGE_BUBBLE_TEST_TAG)
        val columns = composeTestRule.onAllNodesWithTag("message-actions", useUnmergedTree = true)
        val glyphs = composeTestRule.onAllNodesWithTag("message-copy-glyph", useUnmergedTree = true)
        val controls = composeTestRule.onAllNodesWithContentDescription(copyDescription)
        repeat(2) { index ->
            val bubble = bubbles[index].getUnclippedBoundsInRoot()
            val column = columns[index].getUnclippedBoundsInRoot()
            val glyph = glyphs[index].getUnclippedBoundsInRoot()
            val target = controls[index].getUnclippedBoundsInRoot()
            assertEquals(root.width.value - 105f, bubble.width.value, 1f)
            assertEquals(13f, column.width.value, 1f)
            assertEquals(bubble.height.value, column.height.value, 1f)
            assertEquals(11f, glyph.width.value, 1f)
            assertEquals(12f, glyph.height.value, 1f)
            assertEquals(((bubble.top + bubble.bottom) / 2).value - 12.5f, ((glyph.top + glyph.bottom) / 2).value, 1f)
            assertEquals(48f, target.width.value, 1f)
            assertEquals(48f, target.height.value, 1f)
            if (index == 0) {
                assertEquals(20f, (bubble.left - root.left).value, 1f)
                assertEquals(12f, (column.left - bubble.right).value, 1f)
            } else {
                assertEquals(20f, (root.right - bubble.right).value, 1f)
                assertEquals(12f, (bubble.left - column.right).value, 1f)
            }
            assertTrue(target.left >= root.left && target.right <= root.right)
            // Real pointer taps cover every side, including the strip overlapping the bubble.
            controls[index].performTouchInput {
                click(Offset(1f, center.y))
                click(Offset(right - 1f, center.y))
                click(Offset(center.x, 1f))
                click(Offset(center.x, bottom - 1f))
            }
        }
        assertEquals(List(8) { LONG_BODY }, clipboard.writes)
        assertEquals("copy target must win over the bubble timestamp detector", 0, toggles)
    }

    @Test
    fun streamingSideCopy_readsLatestMarkdownSource_andRetainsTheClipboardBound() {
        composeTestRule.mainClock.autoAdvance = false
        val content = mutableStateOf("**arrived** source")
        val clipboard = RecordingClipboard()
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                    MessageBubble(message(Role.Assistant, content.value, isStreaming = true), metaRowVisible = false)
                }
            }
        }
        composeTestRule.onNodeWithContentDescription(copyDescription).performClick()
        composeTestRule.runOnIdle { content.value = "# later source " + "x".repeat(MAX_CLIPBOARD_CHARS) }
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.onNodeWithContentDescription(copyDescription).performClick()
        assertEquals(listOf("**arrived** source", content.value.take(MAX_CLIPBOARD_CHARS)), clipboard.writes)
    }

    @Test
    fun finishedAssistantSideCopy_keepsMarkdownSource() {
        val source = "**bold** and [link](https://example.com)"
        val clipboard = RecordingClipboard()
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                    MessageBubble(message(Role.Assistant, source), metaRowVisible = false)
                }
            }
        }
        composeTestRule.onNodeWithContentDescription(copyDescription).performClick()
        assertEquals(listOf(source), clipboard.writes)
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
