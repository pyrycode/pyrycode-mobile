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

/** ScrollTo sees the drawing viewport, including controls behind chrome. Reveal the actual tap target. */
internal fun ComposeTestRule.questionAnswerTarget(
    matcher: SemanticsMatcher,
    evidence: (String) -> Unit = {},
): SemanticsNodeInteraction {
    onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
    val target = onNode(matcher)
    var failure = ""
    // A live row can grow between these synchronized reads. Correct its new position, never tap again.
    repeat(3) { measurement ->
        val headerBottom = onNodeWithTag("thread-top-bar").fetchSemanticsNode().boundsInRoot.bottom
        val composerTop = onNodeWithTag("thread-composer").fetchSemanticsNode().boundsInRoot.top
        val bounds = target.fetchSemanticsNode().boundsInRoot
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
        val visible = target.fetchSemanticsNode().boundsInRoot
        evidence(
            "event=question_answer_reveal stage=after measurement=$measurement bounds=$visible header=$checkedHeader composer=$checkedComposer shift=$shift",
        )
        if (visible.center.y > checkedHeader && visible.center.y < checkedComposer) return target
        failure = "question-answer tap center must clear thread chrome: $visible in $checkedHeader..$checkedComposer"
    }
    throw AssertionError(failure)
}
