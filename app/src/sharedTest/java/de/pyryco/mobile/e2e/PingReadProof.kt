package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import de.pyryco.mobile.data.repository.ConversationReadMarks
import de.pyryco.mobile.data.repository.RemoteConversationRepository

/** Shared store state is not a witness: only this repository's successful query can publish one. */
internal fun pingReplyCertifiedBy(
    current: ThreadEvidence,
    reply: ThreadItemDto,
    connection: RemoteConversationRepository,
): Boolean {
    val snapshot = current.sourceSnapshot ?: return false
    if (current.owner.connection !== connection || reply.session.isNullOrBlank() || reply.turn.isNullOrBlank()) return false
    return connection.authoritativeCompletions.value.any {
        it.owner === connection &&
            it.host == current.owner.host &&
            it.conversation == current.owner.conversation &&
            it.item.session == reply.session &&
            it.item.turn == reply.turn &&
            it.certifiedVersion >= reply.rev &&
            it.isCurrent(snapshot) &&
            connection.completionIsCurrent(it)
    }
}

/** Re-observe the exact certified target; a retired owner's sight or pending write proves nothing. */
internal fun pingReadMarkConfirmed(
    captured: ThreadEvidenceOwner,
    reply: ThreadItemDto,
    current: ThreadEvidence,
    marks: ConversationReadMarks?,
    connection: Any?,
    certified: () -> Boolean,
    revalidate: () -> AuthoritativeThreadSnapshot?,
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
        current.records.none { it.fact?.raw == reply.raw } ||
        !certified()
    ) {
        return false
    }
    val shown = marks?.lastShownVersion
    return marks?.threadSupported == true &&
        shown != null &&
        shown >= reply.id.toULong() &&
        (marks.readUpTo ?: 0uL) >= shown &&
        certified() &&
        // Reset/repair can withdraw certification without replacing the connection or its read marks.
        snapshot === revalidate()
}
