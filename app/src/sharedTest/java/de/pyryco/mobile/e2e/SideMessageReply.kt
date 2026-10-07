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
    val reply =
        onNode(hasContentDescription(context.getString(R.string.cd_thread_reply_message)) and hasAnyAncestor(sourceRow))
            .assertIsDisplayed()
    val point = sideMessageActionTapPoint(sourceRow, "message-reply-glyph")
    onRoot().performTouchInput { click(point) }
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

/**
 * Semantic scrolling sees the full list; a pointer tap must also miss the chrome drawn over it: the header,
 * the composer and the Top overlay's pills. A short thread starts at the overlay's own inset (#1509), so a
 * pill that stays up can cover a first row's glyph. Returns the glyph's centre when it is clear, otherwise
 * the nearest clear point inside the same action's own target (#1818: copy owns the pair's upper part down
 * to the midpoint, reply the lower part). Fails, naming the cover, when the action has no clear point.
 */
internal fun ComposeTestRule.sideMessageActionTapPoint(
    sourceRow: androidx.compose.ui.test.SemanticsMatcher,
    glyphTag: String,
): Offset {
    val glyphNode = hasTestTag(glyphTag) and hasAnyAncestor(sourceRow)
    if (onAllNodes(hasTestTag("thread-top-bar")).fetchSemanticsNodes().isEmpty()) {
        return onNode(glyphNode, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
    }
    val unit = density.density
    val isCopy = glyphTag == "message-copy-glyph"

    // Header bottom, composer top, pill rectangles and the glyph centre, as currently laid out.
    fun clearPoint(): Pair<Offset?, String> {
        val top = onNodeWithTag("thread-top-bar").fetchSemanticsNode().boundsInRoot.bottom + 2 * unit
        val bottom = onNodeWithTag("thread-composer").fetchSemanticsNode().boundsInRoot.top - 2 * unit
        val pills =
            onAllNodes(hasAnyAncestor(hasTestTag("thread-top-overlay")), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .map { it.boundsInRoot.inflate(4 * unit) }
        val centre = onNode(glyphNode, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
        // The action's own target, kept 3dp off the shared midpoint and its outer edge.
        val range =
            if (isCopy) {
                (centre.y - 21 * unit)..(centre.y + 9.5f * unit)
            } else {
                (centre.y - 9.5f * unit)..(centre.y + 21 * unit)
            }

        fun clear(y: Float) = y in top..bottom && pills.none { it.contains(Offset(centre.x, y)) }
        val steps = ((range.endInclusive - range.start) / unit).toInt()
        val candidates = listOf(centre.y) + (0..steps).map { range.start + it * unit }.sortedBy { kotlin.math.abs(it - centre.y) }
        val y = candidates.firstOrNull(::clear)
        return y?.let { Offset(centre.x, it) } to "glyph centre $centre, clear band $top..$bottom px, pills $pills"
    }
    repeat(5) {
        val (point, _) = clearPoint()
        if (point != null) return point
        val top = onNodeWithTag("thread-top-bar").fetchSemanticsNode().boundsInRoot.bottom
        val bottom = onNodeWithTag("thread-composer").fetchSemanticsNode().boundsInRoot.top
        val y =
            onNode(glyphNode, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot.center.y
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
    val (point, layout) = clearPoint()
    return point ?: throw AssertionError("$glyphTag has no tap point clear of thread chrome; $layout")
}
