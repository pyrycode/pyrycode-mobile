package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.HistoryEntry
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.ThreadSnapshot
import de.pyryco.mobile.data.repository.contributesToUnreadWatermark
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull

/** Shared orchestration for the device scenario's receipt proof and classification query. */
internal suspend fun awaitScriptedReplyReadCheckpoint(
    snapshots: Flow<ThreadSnapshot>,
    expectedReply: String,
    requestHistory: suspend () -> List<HistoryEntry>,
): ULong {
    val (snapshot, reply) =
        snapshots
            .mapNotNull { snapshot ->
                val reply =
                    snapshot.rows
                        .filterIsInstance<ThreadItem.MessageItem>()
                        .lastOrNull { it.message.role == Role.Assistant && it.message.content == expectedReply }
                reply?.takeIf { hasCompleteScriptedReplyProof(snapshot, it) }?.let { snapshot to it }
            }.first()
    // requestHistory mutates the observed projection. It may only classify proof retained before I/O.
    return checkNotNull(scriptedReplyReadCheckpoint(snapshot, reply, requestHistory())) {
        "Qualified scripted reply has no displayable history receipt"
    }
}

/** A read-confirmation target for the exact completed scripted reply. */
internal fun scriptedReplyReadCheckpoint(
    snapshot: ThreadSnapshot,
    reply: ThreadItem.MessageItem,
    history: List<HistoryEntry>,
): ULong? {
    if (!hasCompleteScriptedReplyProof(snapshot, reply)) return null
    val claims = snapshot.readEvidence.versions[reply].orEmpty()
    // Completion can change the rendered row without adding visible content.
    // Keep the receipt proof, but use the same history classification as unread.
    return history
        .filter { it.unsignedId in claims && contributesToUnreadWatermark(it.type, it.payload) }
        .maxOfOrNull { it.unsignedId }
}

private fun hasCompleteScriptedReplyProof(
    snapshot: ThreadSnapshot,
    reply: ThreadItem.MessageItem,
): Boolean =
    reply.message.role == Role.Assistant &&
        !reply.message.isStreaming &&
        reply.message.content.isNotBlank() &&
        snapshot.readEvidence.checkpoint(reply, 0uL) != null
