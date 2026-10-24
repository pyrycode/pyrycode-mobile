package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot

/** A Direct Share assertion reads only the destination's current admitted source publication. */
internal fun ThreadEvidence.isCurrentDirectShareEvidence(
    host: String,
    conversation: String,
    connection: Any?,
    supportedConnection: Any?,
    snapshot: AuthoritativeThreadSnapshot,
): Boolean =
    admitted &&
        owner.kind == ThreadEvidenceKind.Authoritative &&
        owner.host == host &&
        owner.conversation == conversation &&
        connection != null &&
        owner.connection === connection &&
        supportedConnection === connection &&
        sourceSnapshot === snapshot &&
        owner.epoch == snapshot.epoch &&
        owner.generation == snapshot.generation
