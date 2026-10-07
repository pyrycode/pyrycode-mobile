package de.pyryco.mobile.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Encode-only request. Wire SSOT: daemon docs/protocol-mobile.md, “Marking a conversation read”. */
@Serializable
internal data class MarkConversationReadPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("up_to") val upTo: ULong,
)
