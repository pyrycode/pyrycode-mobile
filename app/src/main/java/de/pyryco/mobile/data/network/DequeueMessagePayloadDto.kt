package de.pyryco.mobile.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mobile Protocol v2 `dequeue_message` control payload (#466): the phone→binary message that drops a
 * not-yet-drained message from a conversation's queued backlog (#460). **Encode-only** — the phone
 * sends it; the only correlated reply is an empty `ack` on success or an `error` on failure, there is
 * no typed response payload to decode. Always encode through [MobileJson]
 * (`MobileJson.encodeToJsonElement(...)`), never a default `Json`. The outbound peer of the inbound
 * `queue_state` decode DTO [QueueStatePayloadDto].
 *
 * Wire SSOT: pyrycode spec #720 + ADR 025 (`dequeue_message = {conversation_id, queued_msg_id}`);
 * daemon handler live (pyrycode#723). Two fields, **both required**, in Go-struct order
 * (`conversation_id`, then `queued_msg_id`).
 *
 *  - [conversationId] is the conversation whose backlog the drop targets.
 *  - [queuedMsgId] is the daemon's per-conversation `queued_msg_id` — a wire **uint64** monotonic
 *    ordinal, so a [Long] encoding to a JSON **number** (symmetric with the inbound
 *    [QueuedMessageDto.queuedMsgId]); a `String` would be wrong (the pyrycode#720 trap). The caller
 *    echoes back the `QueuedMessage.id` it received from `observeQueue` (#460) verbatim — this DTO
 *    neither re-derives nor trusts it; the daemon validates the pair against the per-conversation
 *    queue and stale-id rejects a mismatch.
 *
 * Never log the payload or these fields — the no-log posture every sibling outbound control send holds
 * (mirroring [ModalCancelPayloadDto] / [RegisterPushTokenPayloadDto]). Neither field is a secret, but
 * the `data/network` layer carries no logger and adds none here.
 */
@Serializable
internal data class DequeueMessagePayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("queued_msg_id") val queuedMsgId: Long,
)
