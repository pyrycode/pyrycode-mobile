package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem

/** Reply wording is unconstrained. Identity, complete delivery and settled state are not. */
internal fun holdsSettledAssistantReply(
    items: List<ThreadItem>,
    turnId: String,
    parentToolUseId: String = "",
): Boolean {
    val messages =
        items.filterIsInstance<ThreadItem.MessageItem>().map { it.message }.filter {
            it.role == Role.Assistant && it.segment?.turnId == turnId && it.parentToolUseId == parentToolUseId
        }
    if (messages.isEmpty() || messages.any { it.isStreaming } || messages.all { it.content.isBlank() }) return false
    val deltas = messages.flatMap { it.segment?.deltas.orEmpty() }
    return deltas.isNotEmpty() &&
        deltas.map { it.seq } == deltas.indices.toList() &&
        messages.all { row -> row.segment?.deltas?.sumOf { it.length } == row.content.length }
}
