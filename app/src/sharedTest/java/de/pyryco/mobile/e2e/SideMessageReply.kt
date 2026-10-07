package de.pyryco.mobile.e2e

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextRange
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.ui.conversations.components.formatShortDateTime
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import java.util.Locale

/** Pointer reply on one source row; assert exact editable text, end cursor and independent focus. */
internal fun ComposeTestRule.assertSideMessageReply(
    message: Message,
    expectedDraft: String,
) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val sourceRow = hasTestTag("message-row") and hasAnyDescendant(hasText(message.content.trimEnd(), substring = message.isStreaming))
    val notices = onAllNodes(hasContentDescription(context.getString(R.string.thread_notice_dismiss)))
    repeat(notices.fetchSemanticsNodes().size) { notices[0].performClick() }
    // The divided targets may overflow a short first row; scroll its visual row, not the overflow.
    onNode(sourceRow).performScrollTo()
    scrollSideMessageGlyphIntoView(sourceRow, "message-reply-glyph")
    val reply =
        onNode(hasContentDescription(context.getString(R.string.cd_thread_reply_message)) and hasAnyAncestor(sourceRow))
            .assertIsDisplayed()
    val glyph = onNode(hasTestTag("message-reply-glyph") and hasAnyAncestor(sourceRow), useUnmergedTree = true).fetchSemanticsNode()
    onRoot().performTouchInput { click(glyph.boundsInRoot.center) }
    waitUntil(5_000) {
        onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text == expectedDraft
    }
    val field = onNode(hasSetTextAction()).assertIsFocused().fetchSemanticsNode()
    assertEquals(expectedDraft, field.config[SemanticsProperties.EditableText].text)
    assertEquals(TextRange(expectedDraft.length), field.config[SemanticsProperties.TextSelectionRange])
    onAllNodes(
        hasText(formatShortDateTime(message.timestamp, TimeZone.currentSystemDefault(), Locale.getDefault())) and hasAnyAncestor(sourceRow),
        useUnmergedTree = true,
    ).assertCountEquals(0)
}

/** Semantic scrolling sees the full list; pointer actions must clear its overlaid chrome too. */
internal fun ComposeTestRule.scrollSideMessageGlyphIntoView(
    sourceRow: androidx.compose.ui.test.SemanticsMatcher,
    glyphTag: String,
) {
    if (onAllNodes(hasTestTag("thread-top-bar")).fetchSemanticsNodes().isEmpty()) return
    repeat(5) {
        val top = onNodeWithTag("thread-top-bar").fetchSemanticsNode().boundsInRoot.bottom
        val bottom = onNodeWithTag("thread-composer").fetchSemanticsNode().boundsInRoot.top
        val glyph = onNode(hasTestTag(glyphTag) and hasAnyAncestor(sourceRow), useUnmergedTree = true).fetchSemanticsNode()
        val y = glyph.boundsInRoot.center.y
        val margin = 24f * density.density
        if (y in (top + margin)..(bottom - margin)) return
        val region = onNodeWithTag("thread-message-region")
        val origin = region.fetchSemanticsNode().boundsInRoot.topLeft
        val middle = (top + bottom) / 2f
        val distance = (middle - y).coerceIn(-(bottom - top) / 3f, (bottom - top) / 3f)
        val start =
            Offset(
                region
                    .fetchSemanticsNode()
                    .boundsInRoot.center.x,
                middle,
            ) - origin
        region.performTouchInput { swipe(start, start + Offset(0f, distance), 500) }
        waitForIdle()
    }
}
