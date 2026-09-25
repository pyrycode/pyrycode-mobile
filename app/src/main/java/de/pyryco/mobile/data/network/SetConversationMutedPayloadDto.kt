package de.pyryco.mobile.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mobile Protocol v2 `set_conversation_muted` request payload (#1000, server pyrycode#2572): sets or
 * clears a conversation's mute-notifications flag. **Encode-only** — the server replies with a
 * `conversation_updated` envelope carrying `is_muted`, decoded through [ConversationResponseDto]. Always
 * encode through [MobileJson].
 *
 * Both fields are required with no Kotlin default, so `muted = false` is always on the wire: the daemon
 * refuses an absent or `null` `muted` as `protocol.malformed` rather than reading it as `false`.
 */
@Serializable
data class SetConversationMutedPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("muted") val muted: Boolean,
)
