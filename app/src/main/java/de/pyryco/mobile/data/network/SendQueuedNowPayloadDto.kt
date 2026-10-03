package de.pyryco.mobile.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Encode-only control; wire SSOT: ../pyrycode/docs/protocol-mobile.md, Queue (v2). No reply. */
@Serializable
internal data class SendQueuedNowPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("queued_msg_id") val queuedMsgId: Long,
)
