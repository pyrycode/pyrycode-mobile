package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.ModelAnnouncedPayloadDto
import de.pyryco.mobile.data.network.toReading
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * The announced-model reading for every conversation on one host (#890): its state, its decoder and
 * its read for the `model_announced` event, in its own file like every other status event. The repository
 * keeps the routing: its `onInbound` arm calls [apply] only behind the negotiated `interactive` gate, and its
 * `session_transition` arm calls [clear].
 *
 * One instance per host pairing, held in [HostReadings] by the coordinator (#1317), so a reconnect keeps the
 * reading and a host switch or the end of the pairing does not carry it over.
 */
internal class AnnouncedModelProjection {
    /**
     * `conversationId -> the model claude last announced` (#890). Each frame **replaces** the entry: claude
     * announces on every turn and a `/model` turn still names the old model, so latching the first would show
     * a stale value. Two writers, both on the repository's single inbound collector so they never race: a
     * frame stores its reading, and the conversation's `session_transition` removes it. [observe] fans out.
     */
    private val modelByConversation = MutableStateFlow<Map<String, AnnouncedModel>>(emptyMap())

    /**
     * Apply one `model_announced` envelope. Called only from the repository's `interactive`-gated arm. A
     * malformed payload, or an empty `model`, decodes to null and is dropped so the single inbound consumer
     * survives, leaving any prior reading standing. It folds no thread row, touches no stall, opens or closes
     * no turn, and never reaches the saved session settings. Drop silently — nothing here logs the payload.
     */
    fun apply(envelope: Envelope) {
        decodeModelAnnounced(envelope)?.let { (conversationId, reading) ->
            modelByConversation.update { it + (conversationId to reading) }
        }
    }

    /** Drop [conversationId]'s reading: its session was replaced, so the announcement describes a gone run. */
    fun clear(conversationId: String) {
        modelByConversation.update { it - conversationId }
    }

    /**
     * The model claude last announced for [conversationId] (#890), `null` until one arrives, as a cold
     * projection of [modelByConversation]. [distinctUntilChanged] keeps another conversation's frame from
     * re-emitting this flow, and a `StateFlow` gives every new collector the current reading at once.
     */
    fun observe(conversationId: String): Flow<AnnouncedModel?> = modelByConversation.map { it[conversationId] }.distinctUntilChanged()

    /**
     * Decode one v2 `model_announced` envelope (#890) to its routing conversation id and reading, or **null**
     * when it must be dropped: a missing or wrong-typed field fails the structural decode, and an empty
     * `model` fails [toReading]. The caught throwable is discarded, since kotlinx-serialization can quote the
     * offending input in its message.
     */
    private fun decodeModelAnnounced(envelope: Envelope): Pair<String, AnnouncedModel>? =
        try {
            val dto = MobileJson.decodeFromJsonElement<ModelAnnouncedPayloadDto>(envelope.payload)
            dto.toReading()?.let { dto.conversationId to it }
        } catch (e: IllegalArgumentException) {
            null
        }
}
