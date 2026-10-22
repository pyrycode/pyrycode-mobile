package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.HistoryEntryDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Expected text from the independent producer; never supplies phone rows or read proof. */
internal fun completedProducerReply(
    history: List<HistoryEntryDto>,
    turnId: String,
): String? {
    fun HistoryEntryDto.field(key: String) = (payload as? JsonObject)?.get(key)?.jsonPrimitive
    val turn =
        history
            .filter {
                it.field("turn_id")?.content == turnId &&
                    it
                        .field("parent_tool_use_id")
                        ?.content
                        .orEmpty()
                        .isEmpty()
            }.sortedBy { it.id }
    val deltas = turn.filter { it.type == "assistant_delta" }
    val terminal = turn.singleOrNull { it.type == "turn_end" } ?: return null
    if (deltas.any { it.id >= terminal.id }) return null
    if (deltas.isEmpty() || deltas.map { it.field("seq")?.intOrNull } != deltas.indices.toList()) return null
    val seam = turn.lastOrNull { it.type == "tool_use" }?.id ?: 0uL
    return deltas.filter { it.id > seam }.joinToString("") { it.field("text")?.content.orEmpty() }.takeIf { it.isNotBlank() }
}
