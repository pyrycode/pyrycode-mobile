package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.decodeReplySuggestion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Next-reply state for one host connection (#1865), never in HostReadings or persistent thread storage.
 * A clear remains a reading so its revision prevents revival by older sets. A fresh repository starts empty.
 * No thread row, turn event, outgoing request or coroutine is created here.
 */
internal class ReplySuggestionProjection {
    private val readings = MutableStateFlow<Map<Pair<String, String>, ReplySuggestion>>(emptyMap())

    fun apply(envelope: Envelope) {
        // The wire excludes suggestions from replay/history. Never accept a replay-tagged suggestion.
        val next = if (envelope.eventId == null) decodeReplySuggestion(envelope.payload) else null
        if (next == null) {
            RelayLog.d { "event=reply_suggestion outcome=malformed" }
            return
        }
        val key = next.conversationId to next.sessionId
        var accepted = false
        readings.update { held ->
            val previous = held[key]
            accepted = previous == null || next.revision > previous.revision
            if (accepted) held + (key to next) else held
        }
        RelayLog.d { if (accepted) "event=reply_suggestion outcome=accepted" else "event=reply_suggestion outcome=stale" }
    }

    fun observe(
        conversationId: String,
        sessionId: String,
    ): Flow<ReplySuggestion?> = readings.map { it[conversationId to sessionId] }.distinctUntilChanged()

    /** Releases all text and watermarks when the connection's inbound consumer terminates. */
    fun reset() {
        if (readings.getAndUpdate { emptyMap() }.isNotEmpty()) {
            RelayLog.d { "event=reply_suggestion outcome=reset" }
        }
    }
}
