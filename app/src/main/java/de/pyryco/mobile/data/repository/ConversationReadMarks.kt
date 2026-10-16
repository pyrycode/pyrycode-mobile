package de.pyryco.mobile.data.repository

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Daemon facts in the common history-entry space, scoped to a host and conversation.
 * Confirmed [readUpTo] and legacy [latestEntryId] are monotonic. Authoritative shown metadata
 * additionally carries current availability: null cannot certify sight, while zero is explicit.
 * Receipt and local read positions never confirm the shared read mark.
 */
data class ConversationReadMarks(
    val readUpTo: ULong?,
    val latestEntryId: ULong?,
    val lastShownVersion: ULong? = null,
    val threadSupported: Boolean = false,
) {
    /** Unavailable authoritative metadata never falls back to legacy receipt or numeric zero. */
    val unreadWatermark: ULong? get() = if (threadSupported) lastShownVersion else latestEntryId
}

/** Unread classification only; receipt IDs and presentation/checkpoint barriers remain unchanged. */
internal fun contributesToUnreadWatermark(
    type: String,
    payload: JsonElement = JsonNull,
): Boolean {
    if (type in
        setOf(
            "turn_state",
            "stall",
            "api_retry",
            "compacting",
            "session_transition",
            "tool_progress",
            "thinking_progress",
            "background_task_roster",
            "background_task_progress",
            "rate_limited",
            "context_usage",
            "model_announced",
            "session_facts",
            "mcp_status",
            "model_list",
            "slash_command_list",
        )
    ) {
        return false
    }
    if (type !in setOf("turn_end", "banner", "background_task_updated")) return true
    val fields = payload as? JsonObject ?: return true
    return try {
        fun text(key: String): String {
            val value = fields[key] ?: return ""
            if (value == JsonNull) return ""
            require(value is JsonPrimitive && value.isString)
            return value.content
        }

        fun flag(key: String): Boolean {
            val value = fields[key] ?: return false
            if (value == JsonNull) return false
            return requireNotNull((value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull)
        }
        when (type) {
            "turn_end" ->
                !(
                    text("stop_reason") == "end_turn" && !flag("is_error") &&
                        text("outcome") in setOf("", "success") && text("terminal_reason") in setOf("", "completed") &&
                        text("error_category").isEmpty()
                )
            "banner" -> text("level") != "info" || flag("stops_turn")
            else -> text("status").isNotEmpty() || text("summary").isNotEmpty()
        }
    } catch (_: IllegalArgumentException) {
        true // Malformed or unknown content remains a barrier and can never silently become read.
    }
}

internal fun ConversationReadMarks.merge(incoming: ConversationReadMarks): ConversationReadMarks =
    ConversationReadMarks(
        maxKnown(readUpTo, incoming.readUpTo),
        maxKnown(latestEntryId, incoming.latestEntryId),
        if (incoming.threadSupported) {
            incoming.lastShownVersion?.let { maxKnown(lastShownVersion, it) }
        } else {
            lastShownVersion
        },
        threadSupported || incoming.threadSupported,
    )

private fun maxKnown(
    held: ULong?,
    incoming: ULong?,
): ULong? =
    when {
        held == null -> incoming
        incoming == null -> held
        else -> maxOf(held, incoming)
    }
