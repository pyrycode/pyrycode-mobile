package de.pyryco.mobile.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mobile Protocol v2 `promote_conversation` request payload (#348): the phone→binary request that
 * promotes an existing (scratch) conversation into a named, persistent channel. **Encode-only** —
 * the phone sends it; the server replies with a `conversation_updated` envelope carrying the bare
 * updated conversation object, decoded through the #318 [ConversationResponseDto] boundary. Always
 * encode through [MobileJson] (`MobileJson.encodeToJsonElement(...)`), never a default `Json`.
 *
 * Wire SSOT: server `internal/protocol/conversations_write.go` `PromoteConversationPayload` (#274),
 * whose three fields are all **required** non-pointer `string` (`conversation_id`, `name`, `cwd`):
 * "a promoted conversation must carry a name and an effective cwd, and the `conversation_id` must
 * resolve to an existing row." Field declaration order matches the Go struct (decode is by key name,
 * so order has no wire effect; mirrored for readability).
 *
 * All three fields are **non-null/required** — contrast [CreateConversationPayloadDto]'s optional
 * `cwd`. Do NOT relax [cwd] to nullable: the caller resolves a null `workspace` to the conversation's
 * existing cwd before encoding, so the wire always carries a concrete `cwd`. With no Kotlin defaults
 * every field is always sent ([MobileJson]'s `explicitNulls=false` never elides a non-null `String`).
 *
 * Encode-only — model only what is sent (the [CreateConversationPayloadDto] / [SendMessagePayloadDto]
 * discipline), not the full decode surface (#318's job).
 */
@Serializable
data class PromoteConversationPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    val name: String,
    val cwd: String,
)
