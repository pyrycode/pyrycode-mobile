package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.thread.projectAuthoritativeThread

/** Passive readiness for reopening the destination after an operator session rotation. */
internal fun ThreadEvidence.certifiedNextSession(previousSession: String): String? {
    if (!admitted) return null
    val snapshot = sourceSnapshot ?: return null
    val host = owner.host ?: return null
    return projectAuthoritativeThread(host, owner.conversation, snapshot)
        .items.filterIsInstance<ThreadItem.SessionBoundary>()
        .singleOrNull { it.previousSessionId == previousSession && it.newSessionId != previousSession }
        ?.newSessionId
}
