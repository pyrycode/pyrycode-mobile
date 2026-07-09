package de.pyryco.mobile.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mobile Protocol v2 `rename_conversation` request payload (#530): the phone→binary request that
 * renames an existing conversation (channel or discussion). **Encode-only** — the phone sends it;
 * the server replies with a `conversation_updated` envelope carrying the bare updated conversation
 * object, decoded through the #318 [ConversationResponseDto] boundary. Always encode through
 * [MobileJson] (`MobileJson.encodeToJsonElement(...)`), never a default `Json`.
 *
 * Wire SSOT: server `internal/protocol/conversations_write.go` `RenameConversationPayload` (#820),
 * whose two fields are both **required** non-pointer `string` (`conversation_id`, `name`).
 * Deliberately **not** a reuse of [PromoteConversationPayloadDto]: a promote also carries a required
 * `cwd`, which a rename neither has nor means. Field declaration order matches the Go struct (decode
 * is by key name, so order has no wire effect; mirrored for readability).
 *
 * Both fields are **non-null/required** — with no Kotlin defaults every field is always sent
 * ([MobileJson]'s `explicitNulls=false` never elides a non-null `String`). The [name] is forwarded
 * verbatim (the `RenameDialog` is the sole trim authority); the daemon still rejects empty/whitespace
 * titles server-side (`protocol.malformed`), treated as an ordinary server error.
 *
 * Encode-only — model only what is sent (the [PromoteConversationPayloadDto] /
 * [SendMessagePayloadDto] discipline), not the full decode surface (#318's job).
 */
@Serializable
data class RenameConversationPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    val name: String,
)
