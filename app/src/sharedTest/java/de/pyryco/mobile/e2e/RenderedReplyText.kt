package de.pyryco.mobile.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule

/** Fixture reply text as rendered, shared by the device gate and its deterministic screen probes. */
internal fun ComposeContentTestRule.renderedReplyText(expected: String): String {
    val replyPart = expected.split(" ").map { hasText(it, substring = true) }.reduce { left, right -> left or right }
    return onAllNodes(replyPart, useUnmergedTree = true)
        .fetchSemanticsNodes()
        .sortedBy { it.boundsInRoot.top }
        .joinToString(" ") { node ->
            node.config
                .getOrElse(SemanticsProperties.Text) { emptyList() }
                .joinToString(" ")
                .trim()
        }
}
