package de.pyryco.mobile.e2e

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag

/** Selection, pending and note are checked together in one requested section's semantics snapshot. */
internal fun ComposeTestRule.awaitRunConfiguration(
    section: String,
    label: String,
    timeoutMillis: Long,
    state: (String?) -> Boolean = { true },
) {
    var present = false
    var selected = false
    var pending = false
    var noteMatches = false
    try {
        waitUntil(timeoutMillis) {
            val root = onAllNodesWithTag("run-config-$section", useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()
            present = root != null
            val nodes = root?.descendants().orEmpty()
            selected =
                section == "Effort" &&
                label == "Effort" ||
                nodes.any { node ->
                    node.config.getOrNull(SemanticsProperties.Selected) == true &&
                        node.descendants().any { label in it.texts() }
                }
            pending = nodes.any { "$section · applying…" in it.texts() }
            val note =
                nodes
                    .firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == "run-config-$section-note" }
                    ?.descendants()
                    ?.flatMap { it.texts() }
                    ?.singleOrNull()
            noteMatches = state(note)
            present && selected && !pending && noteMatches
        }
    } catch (e: ComposeTimeoutException) {
        throw ComposeTimeoutException(
            "run configuration section=$section present=$present selected=$selected pending=$pending note_matches=$noteMatches",
        ).apply { initCause(e) }
    }
}

private fun SemanticsNode.descendants(): List<SemanticsNode> = listOf(this) + children.flatMap { it.descendants() }

private fun SemanticsNode.texts(): List<String> = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
