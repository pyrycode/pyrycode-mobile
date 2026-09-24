package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.model.Role
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
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * The files the daemon has offered in each conversation on one connection (#898): the state, decoder and
 * read for the `attachment_offered` event. The repository keeps the routing and calls [apply] for every
 * such frame. Wire SSOT: pyrycode `docs/protocol-mobile.md` § Attachments → `attachment_offered`.
 *
 * One instance per repository, and a fresh repository per connection (#351), so offers last as long as the
 * connection and no longer. That matches the wire: the offer is live-only, with no replay and no list verb,
 * so the offers held here are the ones this connection happened to receive, not the conversation's files.
 *
 * The first offer of each attachment id is also appended to [threadProjection] as an [attachmentOfferRow]
 * (#983), so the thread and its disk cache keep what the wire will not replay.
 */
internal class AttachmentOfferProjection(
    private val threadProjection: ThreadProjection,
) {
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
     * id wins: a repeat neither moves nor renames it, and adds no thread row. Only the validated attachment
     * id is logged; the conversation id and the name never are.
     */
    fun apply(envelope: Envelope) {
        val offer = decodeOffer(envelope)
        if (offer == null) {
            RelayLog.d { "event=attachment_offered outcome=dropped" }
            return
        }
        val (conversationId, attachmentOffer) = offer
        RelayLog.d { "event=attachment_offered id=${attachmentOffer.attachmentId}" }
        var firstSeen = false
        offersByConversation.update { offers ->
            val held = offers[conversationId].orEmpty()
            firstSeen = held.none { it.attachmentId == attachmentOffer.attachmentId }
            if (firstSeen) offers + (conversationId to held + attachmentOffer) else offers
        }
        if (firstSeen) {
            threadProjection.appendMessages(listOf(conversationId to attachmentOfferRow(attachmentOffer, Clock.System.now())))
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

/**
 * The thread row for one offered file (#983): an assistant-side message with no text and one reference
 * carrying the offer's cleaned name and no MIME hint, since the wire sends none. Its id is derived from the
 * validated attachment id, so the same offer on another connection, or the row restored from the disk cache,
 * joins this one on `message_id` rather than adding a second. The prefix keeps it clear of the UUIDs the
 * wire uses for `message_id`, `turn_id` and `tool_use_id`. [timestamp] is the arrival instant, the clock of
 * every locally assembled live row.
 */
internal fun attachmentOfferRow(
    offer: AttachmentOffer,
    timestamp: Instant,
): Message =
    Message(
        id = "attachment-offer-${offer.attachmentId}",
        sessionId = "",
        role = Role.Assistant,
        content = "",
        timestamp = timestamp,
        isStreaming = false,
        attachments = listOf(MessageAttachment(offer.attachmentId, offer.displayName)),
    )
