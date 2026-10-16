package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.HistoryEntry
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.ThreadSnapshot
import de.pyryco.mobile.data.repository.contributesToUnreadWatermark

/** A read-confirmation target for the exact completed scripted reply. */
internal fun scriptedReplyReadCheckpoint(
    snapshot: ThreadSnapshot,
    reply: ThreadItem.MessageItem,
    history: List<HistoryEntry>,
): ULong? {
    if (reply.message.role != Role.Assistant || reply.message.isStreaming || reply.message.content.isBlank()) return null
    val evidence = snapshot.readEvidence
    evidence.checkpoint(reply, 0uL) ?: return null
    val claims = evidence.versions[reply].orEmpty()
    // Completion can change the rendered row without adding visible content.
    // Keep the receipt proof, but use the same history classification as unread.
    return history
        .filter { it.unsignedId in claims && contributesToUnreadWatermark(it.type, it.payload) }
        .maxOfOrNull { it.unsignedId }
}
