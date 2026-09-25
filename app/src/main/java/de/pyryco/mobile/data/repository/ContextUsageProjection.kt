package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.ContextUsagePayloadDto
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.toReading
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * The context-usage reading for every conversation on one connection (#945): its state, its decoder and its read.
 * The repository keeps the routing: its `onInbound` arm calls [apply] only behind the negotiated `interactive` gate,
 * and its `session_transition` arm calls [onSessionTransition].
 *
 * **The phone does not send `request_context_usage` (#946).** The daemon answers a mid-turn ask only after the turn
 * ends, and until pyrycode#2563 it holds every later frame on the connection behind that wait, `send_message` and
 * `interrupt` included. The phone cannot tell whether a turn is open when it subscribes, a reconnect least of all, so
 * any ask could stall the thread. The reading comes from the daemon's post-turn `context_usage` push alone, and stays
 * absent until the conversation's next turn ends on this connection.
 *
 * One instance per repository, and a fresh repository per connection (#351), so the state is connection-scoped:
 * a reconnect starts from nothing. Nothing here logs.
 */
internal class ContextUsageProjection {
    /**
     * `conversationId -> the reading Claude last reported` (#945). Each frame **replaces** the entry. Two writers,
     * both on the repository's single inbound collector so they never race: a frame stores its reading, and the
     * conversation's `session_transition` removes it.
     */
    private val readingByConversation = MutableStateFlow<Map<String, ContextUsage>>(emptyMap())

    /**
     * Apply one `context_usage` envelope. Called only from the repository's `interactive`-gated arm. Routing is the
     * payload's own daemon-authored `conversation_id`, never `in_reply_to`, so a frame naming another conversation
     * lands under that one. A malformed payload, a negative `percentage` or an unreadable `as_of` decodes to null
     * and is dropped so the single inbound consumer survives, leaving any prior reading standing and never writing a
     * zero one. It folds no thread row, touches no stall and never reaches the session settings' transcript-derived
     * token counts. Drop silently — nothing here logs the payload.
     */
    fun apply(envelope: Envelope) {
        decodeContextUsage(envelope)?.let { (conversationId, reading) ->
            readingByConversation.update { it + (conversationId to reading) }
        }
    }

    /** The conversation's session was replaced, so its reading describes a gone context: drop it. */
    fun onSessionTransition(conversationId: String) {
        readingByConversation.update { it - conversationId }
    }

    /**
     * The reading Claude last reported for [conversationId] (#945), `null` while there is none, as a cold
     * projection of [readingByConversation]. [distinctUntilChanged] keeps another conversation's frame from
     * re-emitting this flow, and a `StateFlow` gives every new collector the current reading at once.
     */
    fun observe(conversationId: String): Flow<ContextUsage?> =
        readingByConversation
            .map { it[conversationId] }
            .distinctUntilChanged()

    /**
     * Decode one v2 `context_usage` envelope to its routing conversation id and reading, or **null** when it must
     * be dropped: a missing, `null` or wrong-typed scalar fails the structural decode, and a negative `percentage`
     * or a malformed `as_of` fails [toReading]. The caught throwable is discarded, since kotlinx-serialization can
     * quote the offending input in its message.
     */
    private fun decodeContextUsage(envelope: Envelope): Pair<String, ContextUsage>? =
        try {
            val dto = MobileJson.decodeFromJsonElement<ContextUsagePayloadDto>(envelope.payload)
            dto.toReading()?.let { dto.conversationId to it }
        } catch (e: IllegalArgumentException) {
            null
        }
}
