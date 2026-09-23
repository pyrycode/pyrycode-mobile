package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.AttachmentOfferedPayloadDto
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.attachmentDisplayName
import de.pyryco.mobile.data.network.isAttachmentIdShape
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * The files the daemon has offered in each conversation on one connection (#898): the state, decoder and
 * read for the `attachment_offered` event. The repository keeps the routing and calls [apply] for every
 * such frame. Wire SSOT: pyrycode `docs/protocol-mobile.md` § Attachments → `attachment_offered`.
 *
 * One instance per repository, and a fresh repository per connection (#351), so offers last as long as the
 * connection and no longer. That matches the wire: the offer is live-only, with no replay and no list verb,
 * so the offers held here are the ones this connection happened to receive, not the conversation's files.
 */
internal class AttachmentOfferProjection {
    /**
     * `conversationId -> its offers in arrival order`, one entry per attachment id. Written only from the
     * repository's single inbound collector, with [MutableStateFlow.update] because each write reads the
     * list it extends. [observe] fans out.
     */
    private val offersByConversation = MutableStateFlow<Map<String, List<AttachmentOffer>>>(emptyMap())

    /**
     * Apply one `attachment_offered` envelope. The daemon routes nothing, so the payload's conversation id
     * is the only filter. A payload that does not decode, or whose conversation or attachment id is not the
     * published shape, is dropped so the single inbound consumer survives. The first offer of an attachment
     * id wins: a repeat neither moves nor renames it. Only the validated attachment id is logged; the
     * conversation id and the name never are.
     */
    fun apply(envelope: Envelope) {
        val offer = decodeOffer(envelope)
        if (offer == null) {
            RelayLog.d { "event=attachment_offered outcome=dropped" }
            return
        }
        val (conversationId, attachmentOffer) = offer
        RelayLog.d { "event=attachment_offered id=${attachmentOffer.attachmentId}" }
        offersByConversation.update { offers ->
            val held = offers[conversationId].orEmpty()
            if (held.any { it.attachmentId == attachmentOffer.attachmentId }) {
                offers
            } else {
                offers + (conversationId to held + attachmentOffer)
            }
        }
    }

    /**
     * The offers held for [conversationId], empty until one arrives, as a cold projection of
     * [offersByConversation]. [distinctUntilChanged] keeps another conversation's offer, or a repeated one,
     * from re-emitting this flow.
     */
    fun observe(conversationId: String): Flow<List<AttachmentOffer>> =
        offersByConversation.map { it[conversationId].orEmpty() }.distinctUntilChanged()

    /**
     * Decode one `attachment_offered` envelope to its conversation id and offer, or **null** when it must be
     * dropped. The caught throwable is discarded, since kotlinx-serialization can quote the offending input
     * (the file name among it) in its message.
     */
    private fun decodeOffer(envelope: Envelope): Pair<String, AttachmentOffer>? {
        val dto =
            try {
                MobileJson.decodeFromJsonElement<AttachmentOfferedPayloadDto>(envelope.payload)
            } catch (e: IllegalArgumentException) {
                return null
            }
        if (!isAttachmentIdShape(dto.conversationId) || !isAttachmentIdShape(dto.attachmentId)) return null
        return dto.conversationId to AttachmentOffer(dto.attachmentId, attachmentDisplayName(dto.filename))
    }
}
