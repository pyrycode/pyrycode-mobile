package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.SessionFactsPayloadDto
import de.pyryco.mobile.data.network.toFacts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * The session-facts reading for every conversation on one host (#890): its state, its decoder and its
 * read for the `session_facts` event, the [AnnouncedModelProjection] shape for the other half of claude's
 * `system/init` line. The repository keeps the routing: its `onInbound` arm calls [apply] only behind the
 * negotiated `interactive` gate, and its `session_transition` arm calls [clear].
 *
 * One instance per host pairing, held in [HostReadings] by the coordinator (#1317), so a reconnect keeps the
 * reading and a host switch or the end of the pairing does not carry it over.
 */
internal class SessionFactsProjection {
    /**
     * `conversationId -> the facts claude last reported` (#890). Each frame **replaces** the entry; the daemon
     * does not dedup and neither does this. Two writers, both on the repository's single inbound collector so
     * they never race: a frame stores its reading, and the conversation's `session_transition` removes it.
     */
    private val factsByConversation = MutableStateFlow<Map<String, SessionFacts>>(emptyMap())

    /**
     * Apply one `session_facts` envelope. Called only from the repository's `interactive`-gated arm. A
     * malformed payload decodes to null and is dropped so the single inbound consumer survives, leaving any
     * prior reading standing. The reported `permission_mode` is claude's claim and is held here only: it never
     * reaches the session settings or the confirmed permission reading. It folds no thread row, touches no
     * stall, and opens or closes no turn. Drop silently — nothing here logs the payload.
     */
    fun apply(envelope: Envelope) {
        decodeSessionFacts(envelope)?.let { (conversationId, facts) ->
            factsByConversation.update { it + (conversationId to facts) }
        }
    }

    /** Drop [conversationId]'s reading: its session was replaced, so the facts describe a gone run. */
    fun clear(conversationId: String) {
        factsByConversation.update { it - conversationId }
    }

    /**
     * The facts claude last reported for [conversationId] (#890), `null` until a report arrives, as a cold
     * projection of [factsByConversation]. [distinctUntilChanged] keeps another conversation's frame from
     * re-emitting this flow, and a `StateFlow` gives every new collector the current reading at once.
     */
    fun observe(conversationId: String): Flow<SessionFacts?> = factsByConversation.map { it[conversationId] }.distinctUntilChanged()

    /**
     * Decode one v2 `session_facts` envelope (#890) to its routing conversation id and reading, or **null**
     * when a missing or wrong-typed field fails the structural decode. There is no value to reject: both
     * strings may be empty and the posture is an open set. The caught throwable is discarded, since
     * kotlinx-serialization can quote the offending input in its message.
     */
    private fun decodeSessionFacts(envelope: Envelope): Pair<String, SessionFacts>? =
        try {
            val dto = MobileJson.decodeFromJsonElement<SessionFactsPayloadDto>(envelope.payload)
            dto.conversationId to dto.toFacts()
        } catch (e: IllegalArgumentException) {
            null
        }
}
