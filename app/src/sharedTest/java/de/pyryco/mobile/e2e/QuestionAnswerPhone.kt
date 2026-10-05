package de.pyryco.mobile.e2e

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Assert.assertTrue

/** ScrollTo sees the drawing viewport, including controls behind chrome. Reveal the actual tap target. */
internal fun ComposeTestRule.questionAnswerTarget(matcher: SemanticsMatcher): SemanticsNodeInteraction {
    onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
    val target = onNode(matcher)
    val bounds = target.fetchSemanticsNode().boundsInRoot
    val headerBottom = onNodeWithTag("thread-top-bar").fetchSemanticsNode().boundsInRoot.bottom
    val composerTop = onNodeWithTag("thread-composer").fetchSemanticsNode().boundsInRoot.top
    val shift =
        when {
            bounds.top < headerBottom -> headerBottom - bounds.top + 4f
            bounds.bottom > composerTop -> composerTop - bounds.bottom - 4f
            else -> 0f
        }
    if (shift != 0f) {
        onNode(hasScrollToIndexAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, shift) }
    }
    val visible = target.fetchSemanticsNode().boundsInRoot
    assertTrue(
        "question-answer tap center must clear thread chrome: $visible in $headerBottom..$composerTop",
        visible.center.y > headerBottom && visible.center.y < composerTop,
    )
    return target
}
