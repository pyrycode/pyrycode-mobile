package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.repository.ConversationReadMarks

/** Re-observe the exact certified target; a retired owner's sight or pending write proves nothing. */
internal fun pingReadMarkConfirmed(
    captured: ThreadEvidenceOwner,
    reply: ThreadItemDto,
    current: ThreadEvidence,
    marks: ConversationReadMarks?,
    connection: Any?,
): Boolean {
    val snapshot = current.sourceSnapshot ?: return false
    val live = connection ?: return false
    // A new connection/generation needs its own complete evidence. Durable target scope stays fixed.
    val certifiedOwner = captured.copy(connection = live, generation = snapshot.generation)
    if (captured.kind != ThreadEvidenceKind.Authoritative ||
        captured.epoch == null ||
        !current.owner.matches(certifiedOwner) ||
        !current.admitted ||
        snapshot.epoch != captured.epoch ||
        snapshot.repairNeeded ||
        snapshot.resetNeeded ||
        snapshot.checkpoint < reply.rev ||
        current.records.none { it.fact?.raw == reply.raw }
    ) {
        return false
    }
    val shown = marks?.lastShownVersion
    return marks?.threadSupported == true &&
        shown != null &&
        shown >= reply.id.toULong() &&
        (marks.readUpTo ?: 0uL) >= shown
}
