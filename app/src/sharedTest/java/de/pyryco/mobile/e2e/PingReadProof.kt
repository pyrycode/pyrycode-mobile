package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.repository.ConversationReadMarks

/** The final ping predicate, separate from frame driving so replacement can be controlled. */
internal fun pingReadMarkConfirmed(
    captured: ThreadEvidenceOwner,
    reply: ThreadItemDto,
    current: ThreadEvidence,
    marks: ConversationReadMarks?,
    connection: Any?,
): Boolean {
    val shown = marks?.lastShownVersion
    return connection === captured.connection && current.owner.matches(captured) &&
        marks?.threadSupported == true && shown != null && shown >= reply.id.toULong() &&
        (marks.readUpTo ?: 0uL) >= shown
}
