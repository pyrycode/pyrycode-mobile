package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.repository.ModelMenu
import de.pyryco.mobile.data.repository.ModelMenuRow
import de.pyryco.mobile.ui.conversations.thread.UNAVAILABLE_MODEL_LABEL
import de.pyryco.mobile.ui.conversations.thread.dropdownLabel
import de.pyryco.mobile.ui.conversations.thread.INHERITED_DEFAULT_MODEL_VALUE as INHERITED_MODEL_VALUE

/** The menu's rendering cap applies after removing unknown agents and hidden default metadata. */
internal fun renderedModelRows(menu: ModelMenu): List<ModelMenuRow> =
    menu.rows.filter { it.agent != null && it.value != INHERITED_MODEL_VALUE }.take(32)

/**
 * The Claude row an inherited conversation marks for [announced] (#1308): exact value, else
 * `resolved_model`, else family. The first tier with any candidate decides; more than one marks nothing.
 */
internal fun announcedRow(
    menu: ModelMenu,
    announced: String,
): ModelMenuRow? {
    if (announced.isEmpty()) return null
    val rows = menu.rows.filter { it.agent == ConversationAgent.Claude && it.value != INHERITED_MODEL_VALUE }
    val family = claudeFamily(announced)
    val tiers =
        listOf<(ModelMenuRow) -> Boolean>(
            { it.value == announced },
            { it.resolvedModel == announced && "resolved_model" !in it.truncatedFields.orEmpty() },
            { family.isNotEmpty() && claudeFamily(it.value) == family },
        )
    for (matches in tiers) {
        val candidates = rows.filter(matches)
        if (candidates.isNotEmpty()) return candidates.singleOrNull()?.takeIf { it in renderedModelRows(menu) }
    }
    return null
}

/** Expected inherited label from the host's fresh published list, independent of a running turn. */
internal fun inheritedModelLabel(menu: ModelMenu): String {
    val claudeRows = menu.rows.filter { it.agent == ConversationAgent.Claude }
    val default = claudeRows.filter { it.value == INHERITED_MODEL_VALUE }.singleOrNull()
    val resolved = default?.resolvedModel.orEmpty()
    if (resolved.isBlank() || resolved.startsWith("<") || "resolved_model" in default?.truncatedFields.orEmpty()) {
        return UNAVAILABLE_MODEL_LABEL
    }
    // #1308: with no unique row, the label names the default resolution's family, never "Default".
    return inheritedModelRow(menu)
        ?.dropdownLabel(ConversationAgent.Claude)
        ?: claudeFamily(resolved).ifEmpty { UNAVAILABLE_MODEL_LABEL }
}

/** Ambiguity counts the full Claude list, but a displayed default selection must be rendered. */
internal fun inheritedModelRow(menu: ModelMenu): ModelMenuRow? {
    val claudeRows = menu.rows.filter { it.agent == ConversationAgent.Claude }
    val default = claudeRows.singleOrNull { it.value == INHERITED_MODEL_VALUE } ?: return null
    val resolved = default.resolvedModel
    if (resolved.isBlank() || resolved.startsWith("<") || "resolved_model" in default.truncatedFields.orEmpty()) return null
    return claudeRows
        .filter { it.value != INHERITED_MODEL_VALUE && it.resolvedModel == resolved }
        .singleOrNull()
        ?.takeIf { it in renderedModelRows(menu) }
}

private fun claudeFamily(identifier: String): String =
    identifier.removePrefix("claude-").takeWhile { it in 'A'..'Z' || it in 'a'..'z' }.replaceFirstChar { it.uppercaseChar() }
