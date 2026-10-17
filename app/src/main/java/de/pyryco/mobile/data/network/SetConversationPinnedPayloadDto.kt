package de.pyryco.mobile.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mobile Protocol v2 `set_conversation_pinned` request payload (#2214, server pyrycode#3538): pins or
 * unpins a conversation on its own host. **Encode-only** — the server replies with a
 * `conversation_updated` envelope carrying the stored `pinned_at`, decoded through
 * [ConversationResponseDto]. Always encode through [MobileJson].
 *
 * Both fields are required with no Kotlin default, so `pinned = false` is always on the wire: the daemon
 * refuses an absent or `null` `pinned` as `protocol.malformed` rather than reading it as `false`.
 */
@Serializable
data class SetConversationPinnedPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("pinned") val pinned: Boolean,
)
