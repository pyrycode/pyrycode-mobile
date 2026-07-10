package de.pyryco.mobile.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mobile Protocol v2 `archive_conversation` / `unarchive_conversation` request payload (#549): the
 * phone→binary request that archives or restores an existing conversation. **Encode-only** — the phone
 * sends it; the server replies with a `conversation_updated` envelope carrying the bare updated
 * conversation object (now including `is_archived`, pyrycode#881), decoded through the #318
 * [ConversationResponseDto] boundary. Always encode through [MobileJson]
 * (`MobileJson.encodeToJsonElement(...)`), never a default `Json`.
 *
 * **One DTO serves both verbs.** Wire SSOT: server `ArchiveConversationPayload` (pyrycode#881), a
 * single required non-pointer `string` `conversation_id`. Archive and restore are a symmetric toggle of
 * one durable flag — same request shape, same handler structure, same not-found and reply behaviour — so
 * pyrycode#881 defined one payload for the pair and this mirrors that; the [RemoteConversationRepository]
 * disambiguates by the [Envelope.type] string, not the payload shape. The name reads slightly oddly for
 * the unarchive direction; this KDoc resolves that (it is the server pair's name).
 *
 * The single field is **non-null/required** — with no Kotlin default it is always sent
 * ([MobileJson]'s `explicitNulls=false` never elides a non-null `String`). Encode-only: model only what
 * is sent (the [RenameConversationPayloadDto] / [PromoteConversationPayloadDto] discipline), not the
 * full decode surface (#318's job).
 */
@Serializable
data class ArchiveConversationPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
)
