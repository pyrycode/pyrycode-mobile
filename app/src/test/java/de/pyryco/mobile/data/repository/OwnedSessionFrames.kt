package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.SessionProvenance
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Authored session ownership for tests of the retained live-reading protocol. */
internal fun Envelope.ownedBy(
    conversation: String,
    session: String,
): Envelope =
    copy(
        conversationId = JsonPrimitive(conversation),
        sessionProvenance = SessionProvenance(JsonPrimitive(session)),
        sessionStateCleared = JsonPrimitive(false),
    )

internal fun sessionSnapshotClear(
    conversation: String,
    session: String,
): Envelope =
    Envelope(1L, "context_usage", "2026-10-01T00:00:00Z", JsonObject(emptyMap()))
        .ownedBy(conversation, session)
        .copy(sessionStateCleared = JsonPrimitive(true))
