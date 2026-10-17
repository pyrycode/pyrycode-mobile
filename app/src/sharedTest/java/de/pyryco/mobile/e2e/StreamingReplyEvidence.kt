package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.Role

/*
 * Scripted streaming, reopen and Direct Share assertions over source-bound evidence (#2171). Each reads
 * one main-thread reply; ownership is the first observation's, so a reused item ID from another host,
 * connection or document never satisfies them. Daemon facts prove growth by revision, never by deltas.
 */

private val COMPLETE = setOf("done", "finished")

/** Legacy rows report streaming; a daemon item is open until its own inactive completed facts. */
internal val ThreadEvidenceRecord.isOpen: Boolean
    get() = fact?.let { it.active || it.status !in COMPLETE } ?: message.isStreaming

internal fun ThreadEvidence.assistantRecords(): List<ThreadEvidenceRecord> =
    if (admitted) records.filter { it.message.role == Role.Assistant } else emptyList()

/** Unadmitted evidence is not an empty thread; a user record of any status counts as sent. */
internal fun ThreadEvidence.holdsNoUserMessage(): Boolean = admitted && records.none { it.message.role == Role.User }

/** The only user record, when it is [owner]'s single delivered exact echo of [text]. */
internal fun ThreadEvidence.ownedUserEcho(
    owner: ThreadEvidenceOwner,
    text: String,
): ThreadEvidenceRecord? =
    records.singleOrNull { it.message.role == Role.User }?.takeIf { this.owner.matches(owner) && userEchoCount(text) == 1 }

/** The single assistant record, still open with nonblank text, from [owner]. */
internal fun ThreadEvidence.openReply(owner: ThreadEvidenceOwner): ThreadEvidenceRecord? =
    assistantRecords().singleOrNull()?.takeIf { this.owner.matches(owner) && it.isOpen && it.fullText.isNotBlank() }

/** The same rendered row and daemon item; a host- or document-scoped identity never collides. */
internal fun ThreadEvidenceRecord.isSameItem(other: ThreadEvidenceRecord): Boolean =
    message.id == other.message.id && fact?.id == other.fact?.id

/** The same item with longer text that keeps [earlier]'s; a daemon item must also carry a later revision. */
internal fun ThreadEvidenceRecord.advancesFrom(earlier: ThreadEvidenceRecord): Boolean {
    val revision = fact?.rev
    val earlierRevision = earlier.fact?.rev
    return isSameItem(earlier) &&
        fullText.length > earlier.fullText.length &&
        fullText.startsWith(earlier.fullText) &&
        if (revision == null || earlierRevision == null) revision == earlierRevision else revision > earlierRevision
}

/**
 * [item]'s current record once its main-thread family settles for [owner]: legacy keeps the delta
 * sequence and length checks, daemon facts their own inactive completion.
 */
internal fun ThreadEvidence.settledFormOf(
    owner: ThreadEvidenceOwner,
    item: ThreadEvidenceRecord,
): ThreadEvidenceRecord? {
    val current = records.singleOrNull { it.isSameItem(item) } ?: return null
    val family = settledReply(owner, current.session ?: return null, current.turn ?: return null) ?: return null
    return current.takeIf { it in family }
}
