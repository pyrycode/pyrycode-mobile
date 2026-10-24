package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.thread.projectAuthoritativeThread

/** Passive readiness for reopening the destination after an operator session rotation. */
internal fun ThreadEvidence.certifiedNextSession(
    previousSession: String,
    current: ThreadEvidence,
): String? {
    if (!admitted || !current.admitted || owner.kind != ThreadEvidenceKind.Authoritative || !owner.matches(current.owner)) return null
    val snapshot = sourceSnapshot ?: return null
    if (snapshot !== current.sourceSnapshot ||
        snapshot.epoch == null ||
        snapshot.epoch != owner.epoch ||
        snapshot.generation != owner.generation ||
        snapshot.repairNeeded ||
        snapshot.resetNeeded ||
        snapshot.checkpoint <= 0 ||
        snapshot.checkpoint < snapshot.completedVersion
    ) {
        return null
    }
    val host = owner.host ?: return null
    return projectAuthoritativeThread(host, owner.conversation, snapshot)
        .items
        .filterIsInstance<ThreadItem.SessionBoundary>()
        .singleOrNull { it.previousSessionId == previousSession }
        ?.takeIf { it.newSessionId != previousSession }
        ?.newSessionId
}
