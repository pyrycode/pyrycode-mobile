package de.pyryco.mobile.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mobile Protocol v2 `send_message` request payload (#346): the phone→binary request that posts the
 * user's text to a conversation. **Encode-only** — the phone sends it; the only correlated reply is
 * an empty `ack` on success or an `error` on failure (there is no persisted-`Message` echo to the
 * sender to decode), so the sender's thread updates only via a local confirmed-insert after the ack.
 *
 * Wire SSOT: server `internal/protocol/messaging.go` `SendMessagePayload` (#272). Field declaration
 * order matches the Go struct (`conversation_id`, `message_id`, `text`); all three are required.
 *
 *  - [messageId] is **client-generated** (a minted UUID); the repository interface passes only
 *    `conversationId` + `text`, so the id is the sender's correlation handle for the reconstructed
 *    [Message], distinct from the request *envelope* id used for `ack`/`error` correlation.
 *  - [attachmentIds] (#830) names the uploaded attachments the message references, in the caller's
 *    order (`protocol-mobile.md` § Naming a message's attachments). `null` — never `[]` — for a message
 *    naming none, so [MobileJson] (`explicitNulls = false`) omits the key and a text-only send encodes
 *    exactly as before. Build it through [MessageAttachmentIds.forSend].
 */
@Serializable
data class SendMessagePayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("message_id") val messageId: String,
    val text: String,
    @SerialName("attachment_ids") val attachmentIds: List<String>? = null,
)

/**
 * The published per-message bound on `attachment_ids` (#830): at most [MAX] ids. The daemon counts raw
 * elements and refuses an over-bound list with `protocol.malformed`; this client drops repeats first and
 * sends the distinct list, so the count it checks is the count the daemon sees.
 */
object MessageAttachmentIds {
    const val MAX: Int = 32

    /**
     * The `attachment_ids` value for a send naming [ids]: each id once, in first-seen order, or `null`
     * when there are none. Throws [IllegalArgumentException] when more than [MAX] distinct ids remain.
     */
    fun forSend(ids: List<String>): List<String>? {
        val distinct = ids.distinct()
        require(distinct.size <= MAX) { "A message names at most $MAX attachments, not ${distinct.size}" }
        return distinct.ifEmpty { null }
    }
}
