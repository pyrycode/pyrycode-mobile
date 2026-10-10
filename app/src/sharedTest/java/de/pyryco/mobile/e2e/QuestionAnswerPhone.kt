package de.pyryco.mobile.e2e

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Assert.assertTrue

/** ScrollTo sees the drawing viewport, including controls behind chrome. Reveal the actual tap target.
 * [lazyKey] lets a caller distinguish a disposed row from one removed from the projected list.
 * Each correction happens before the caller's single physical tap.
 */
internal fun ComposeTestRule.questionAnswerTarget(
    matcher: SemanticsMatcher,
    lazyKey: String? = null,
    evidence: (String) -> Unit = {},
): SemanticsNodeInteraction {
    val list = onNode(hasScrollToNodeAction())

    fun boundsOrReveal(): Rect? {
        if (lazyKey == null) return onNode(matcher).fetchSemanticsNode().boundsInRoot
        val nodes = onAllNodes(matcher).fetchSemanticsNodes()
        // Count and bounds come from the same synchronized read, so disposal between two reads is safe.
        if (nodes.isNotEmpty()) {
            assertTrue("tap target must match exactly one node", nodes.size == 1)
            return nodes.single().boundsInRoot
        }
        val index = list.fetchSemanticsNode().config[SemanticsProperties.IndexForKey](lazyKey)
        evidence("event=question_answer_reveal stage=disposed key_index=$index composed=0")
        assertTrue("tap target left the projected list", index >= 0)
        list.performScrollToKey(lazyKey)
        return null
    }
    if (lazyKey != null) list.performScrollToKey(lazyKey)
    list.performScrollToNode(matcher)
    val target = onNode(matcher)
    var failure = "tap target did not remain composed after keyed reveal"
    // A live row can grow between these synchronized reads. Correct its new position, never tap again.
    repeat(3) { measurement ->
        val headerBottom = onNodeWithTag("thread-top-bar").fetchSemanticsNode().boundsInRoot.bottom
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
        val checkedHeader = onNodeWithTag("thread-top-bar").fetchSemanticsNode().boundsInRoot.bottom
        val checkedComposer = onNodeWithTag("thread-composer").fetchSemanticsNode().boundsInRoot.top
        val visible = boundsOrReveal() ?: return@repeat
        evidence(
            "event=question_answer_reveal stage=after measurement=$measurement bounds=$visible header=$checkedHeader composer=$checkedComposer shift=$shift",
        )
        if (visible.center.y > checkedHeader && visible.center.y < checkedComposer) return target
        failure = "question-answer tap center must clear thread chrome: $visible in $checkedHeader..$checkedComposer"
    }
    throw AssertionError(failure)
}
