package de.pyryco.mobile.e2e

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertTrue

/** ScrollTo sees the drawing viewport, including controls behind chrome. Reveal the actual tap target.
 * [lazyKey] lets a caller distinguish a disposed row from one removed from the projected list.
 * Each correction happens before the caller's single physical tap.
 */
internal fun ComposeTestRule.questionAnswerTarget(
    matcher: SemanticsMatcher,
    lazyKey: String? = null,
    evidence: (String) -> Unit = {},
): SemanticsNodeInteraction = questionAnswerLocation(matcher, lazyKey, allowPartial = false, evidence).first

/** A generation-scoped row must resolve its current owned key at each geometry read. */
internal fun ComposeTestRule.questionAnswerTargetWithCurrentKey(
    matcher: SemanticsMatcher,
    currentKey: () -> String,
    revealCurrentKey: () -> Int,
    evidence: (String) -> Unit = {},
): SemanticsNodeInteraction = questionAnswerLocation(matcher, null, allowPartial = false, evidence, currentKey, revealCurrentKey).first

/** Tap once inside the actual exposed control when a short thread cannot scroll past a notice. */
internal fun ComposeTestRule.questionAnswerTap(
    matcher: SemanticsMatcher,
    lazyKey: String? = null,
    evidence: (String) -> Unit = {},
) {
    val (target, localPoint) = questionAnswerLocation(matcher, lazyKey, allowPartial = true, evidence)
    target.performTouchInput { click(localPoint) }
}

private fun ComposeTestRule.questionAnswerLocation(
    matcher: SemanticsMatcher,
    lazyKey: String?,
    allowPartial: Boolean,
    evidence: (String) -> Unit,
    currentKey: (() -> String)? = null,
    revealCurrentKey: (() -> Int)? = null,
): Pair<SemanticsNodeInteraction, Offset> {
    val list = onNode(hasScrollToNodeAction())

    fun keyForRead(): String? = currentKey?.invoke() ?: lazyKey

    fun clearTop(): Float {
        val header = onNodeWithTag("thread-top-bar").fetchSemanticsNode().boundsInRoot.bottom
        // Notices draw over the messages below the header and can intercept a visible row's tap.
        val overlay =
            onAllNodes(hasTestTag("thread-top-overlay"), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .maxOfOrNull { it.boundsInRoot.bottom } ?: header
        return maxOf(header, overlay)
    }

    fun boundsOrReveal(): Rect? {
        val key = keyForRead() ?: return onNode(matcher).fetchSemanticsNode().boundsInRoot
        val nodes = onAllNodes(matcher).fetchSemanticsNodes()
        // Count and bounds come from the same synchronized read, so disposal between two reads is safe.
        if (nodes.isNotEmpty()) {
            assertTrue("tap target must match exactly one node", nodes.size == 1)
            return nodes.single().boundsInRoot
        }
        val revealKey = keyForRead() ?: key
        val index = revealCurrentKey?.invoke() ?: list.fetchSemanticsNode().config[SemanticsProperties.IndexForKey](revealKey)
        evidence("event=question_answer_reveal stage=disposed key_index=$index composed=0")
        if (revealCurrentKey == null) {
            assertTrue("tap target left the projected list", index >= 0)
            list.performScrollToKey(revealKey)
        }
        return null
    }
    if (revealCurrentKey != null) revealCurrentKey() else keyForRead()?.let { list.performScrollToKey(it) }
    currentKey?.invoke()
    if (revealCurrentKey != null) {
        evidence("event=question_answer_reveal stage=keyed-ready")
        // The atomic keyed reveal already placed this row. A second matcher scroll can
        // race reset/catch-up; the geometry reads below resample current-key readiness.
    } else {
        list.performScrollToNode(matcher)
    }
    val target = onNode(matcher)
    var failure = "tap target did not remain composed after keyed reveal"
    // A live row can grow between these synchronized reads. Correct its new position, never tap again.
    repeat(3) { measurement ->
        val headerBottom = clearTop()
        val composerTop = onNodeWithTag("thread-composer").fetchSemanticsNode().boundsInRoot.top
        val bounds = boundsOrReveal() ?: return@repeat
        val shift =
            when {
                bounds.top < headerBottom -> headerBottom - bounds.top + 4f
                bounds.bottom > composerTop -> composerTop - bounds.bottom - 4f
                else -> 0f
            }
        evidence(
            "event=question_answer_reveal stage=before measurement=$measurement bounds=$bounds header=$headerBottom composer=$composerTop shift=$shift",
        )
        if (shift != 0f) {
            onNode(hasScrollToIndexAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, shift) }
        }
        val checkedHeader = clearTop()
        val checkedComposer = onNodeWithTag("thread-composer").fetchSemanticsNode().boundsInRoot.top
        val visible = boundsOrReveal() ?: return@repeat
        evidence(
            "event=question_answer_reveal stage=after measurement=$measurement bounds=$visible header=$checkedHeader composer=$checkedComposer shift=$shift",
        )
        val exposed =
            Rect(visible.left, maxOf(visible.top, checkedHeader + 4f), visible.right, minOf(visible.bottom, checkedComposer - 4f))
        val point = if (allowPartial && exposed.height > 0f) exposed.center else visible.center
        if (visible.width > 0f &&
            visible.height > 0f &&
            visible.contains(point) &&
            point.y > checkedHeader &&
            point.y < checkedComposer
        ) {
            evidence("event=question_answer_tap_target point=$point target=$visible clear=$checkedHeader..$checkedComposer")
            return target to (point - visible.topLeft)
        }
        failure = "question-answer tap must clear thread chrome: $visible in $checkedHeader..$checkedComposer"
    }
    throw AssertionError(failure)
}
