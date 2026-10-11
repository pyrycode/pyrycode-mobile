package de.pyryco.mobile.data.repository

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
internal fun contributesToUnreadWatermark(type: String): Boolean =
    when (type) {
        "turn_state", "stall", "api_retry", "compacting", "session_transition" -> false
        else -> true
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
