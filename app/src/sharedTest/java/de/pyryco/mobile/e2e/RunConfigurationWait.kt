package de.pyryco.mobile.e2e

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag

/**
 * Selection, pending and note are checked together in one requested section's semantics snapshot.
 * [confirmed] also gates completion when an independent reading can settle the UI before persistence.
 */
internal fun ComposeTestRule.awaitRunConfiguration(
    section: String,
    label: String,
    timeoutMillis: Long,
    confirmed: () -> Boolean = { true },
    observe: (String) -> Unit = {},
    state: (String?) -> Boolean = { true },
) {
    var present = false
    var selected = false
    var pending = false
    var noteMatches = false
    var confirmationMatches = false
    var confirmationEvaluated = false
    var observedRows = emptyList<String>()
    var observedTexts = emptyList<String>()
    var observedNote: String? = null

    fun evidence() =
        "run configuration section=$section present=$present selected=$selected pending=$pending " +
            "note_matches=$noteMatches confirmation_matches=$confirmationMatches " +
            "confirmation_evaluated=$confirmationEvaluated expected_label=$label rows=$observedRows " +
            "note=$observedNote section_texts=$observedTexts"
    try {
        waitUntil(timeoutMillis) {
            val root = onAllNodesWithTag("run-config-$section", useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()
            present = root != null
            val nodes = root?.descendants().orEmpty()
            observedTexts = nodes.flatMap { it.texts() }
            // Copy only this section's controlled choices from the traversal already used below.
            observedRows =
                nodes.mapNotNull { node ->
                    node.config.getOrNull(SemanticsProperties.Selected)?.let {
                        "selected=$it texts=${node.descendants().flatMap { child -> child.texts() }}"
                    }
                }
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
            observedNote = note
            confirmationEvaluated = false
            confirmationMatches = present &&
                selected &&
                !pending &&
                noteMatches &&
                run {
                    confirmationEvaluated = true
                    confirmed()
                }
            confirmationMatches
        }
    } catch (e: ComposeTimeoutException) {
        val detail = evidence()
        throw ComposeTimeoutException(detail).apply {
            initCause(e)
            runCatching { observe(detail) }.exceptionOrNull()?.let(::addSuppressed)
        }
    }
    observe(evidence())
}

private fun SemanticsNode.descendants(): List<SemanticsNode> = listOf(this) + children.flatMap { it.descendants() }

private fun SemanticsNode.texts(): List<String> = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
