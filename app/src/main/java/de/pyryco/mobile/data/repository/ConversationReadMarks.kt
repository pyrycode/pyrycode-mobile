package de.pyryco.mobile.data.repository

/**
 * Live daemon facts in the durable history id space, scoped to one host connection and conversation.
 * Null means the field has not been heard; zero is a valid checkpoint. A push can report [readUpTo]
 * before a list reports [latestEntryId], the unread watermark excluding the daemon's five status types.
 * These facts are never inferred from the local read position
 * or restored from disk. Only a present [readUpTo] establishes read-mark support on this connection.
 */
data class ConversationReadMarks(
    val readUpTo: ULong?,
    val latestEntryId: ULong?,
)

/** Unread classification only; receipt IDs and presentation/checkpoint barriers remain unchanged. */
internal fun contributesToUnreadWatermark(type: String): Boolean =
    when (type) {
        "turn_state", "stall", "api_retry", "compacting", "session_transition" -> false
        else -> true
    }

internal fun ConversationReadMarks.merge(incoming: ConversationReadMarks): ConversationReadMarks =
    ConversationReadMarks(maxKnown(readUpTo, incoming.readUpTo), maxKnown(latestEntryId, incoming.latestEntryId))

private fun maxKnown(
    held: ULong?,
    incoming: ULong?,
): ULong? =
    when {
        held == null -> incoming
        incoming == null -> held
        else -> maxOf(held, incoming)
    }
