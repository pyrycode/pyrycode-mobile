package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.ThreadSnapshot

/** A read-confirmation target for the exact completed scripted reply. */
internal fun scriptedReplyReadCheckpoint(
    snapshot: ThreadSnapshot,
    reply: ThreadItem.MessageItem,
): ULong? {
    if (reply.message.role != Role.Assistant || reply.message.isStreaming || reply.message.content.isBlank()) return null
    val evidence = snapshot.readEvidence
    evidence.checkpoint(reply, 0uL) ?: return null
    // Receipt coverage includes hidden completion/metadata. The daemon's read
    // watermark advances only through displayable history, never that tail.
    return evidence.versions[reply]?.filter { evidence.facts[it] == false }?.maxOrNull()
}
