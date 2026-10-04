package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.SessionErrorPayloadDto
import de.pyryco.mobile.data.network.toSessionError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Latest session-error code per conversation on one connection. Inbound frames replace one entry;
 * caller sends and decoded non-idle turn states clear it. Pure atomic updates merge these writers.
 * No prose is retained, and no error causes a request, replay or resend.
 */
internal class SessionErrorProjection {
    private val errors = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Called only behind the repository's negotiated interactive gate. */
    fun apply(envelope: Envelope) {
        val decoded =
            try {
                MobileJson.decodeFromJsonElement<SessionErrorPayloadDto>(envelope.payload).toSessionError()
            } catch (_: IllegalArgumentException) {
                null
            }
        if (decoded == null) {
            RelayLog.d { "event=session_error outcome=malformed" }
            return
        }
        val (conversationId, code) = decoded
        errors.update { it + (conversationId to code) }
        // Codes are an open vocabulary: even they may contain sensitive or log-shaped text.
        RelayLog.d { "event=session_error outcome=applied" }
    }

    fun clear(conversationId: String) {
        val previous = errors.getAndUpdate { it - conversationId }
        if (conversationId in previous) RelayLog.d { "event=session_error outcome=cleared" }
    }

    /** Inbound completion or cancellation drops the connection's transient state. */
    fun reset() {
        val previous = errors.getAndUpdate { emptyMap() }
        if (previous.isNotEmpty()) RelayLog.d { "event=session_error outcome=disconnected" }
    }

    /** Current value immediately on subscription; unrelated conversation updates emit nothing. */
    fun observe(conversationId: String): Flow<String?> = errors.map { it[conversationId] }.distinctUntilChanged()
}
