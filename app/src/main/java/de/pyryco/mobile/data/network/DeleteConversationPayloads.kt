package de.pyryco.mobile.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mobile Protocol v2 `delete_conversation` request payload (#532): the phone→binary request that
 * permanently removes an existing conversation. **Encode-only** — the phone sends it; the daemon
 * replies with a `conversation_deleted` ack carrying only the deleted `id`
 * ([ConversationDeletedPayloadDto]). Always encode through [MobileJson]
 * (`MobileJson.encodeToJsonElement(...)`), never a default `Json`.
 *
 * Wire SSOT: server `internal/protocol/conversations_write.go` `DeleteConversationPayload` (#822 /
 * PR #884), a single required non-pointer `string` `conversation_id`.
 *
 * The single field is **non-null/required** — with no Kotlin default it is always sent
 * ([MobileJson]'s `explicitNulls=false` never elides a non-null `String`). Encode-only: model only
 * what is sent (the [ArchiveConversationPayloadDto] / [RenameConversationPayloadDto] discipline),
 * not the full decode surface (#318's job).
 */
@Serializable
data class DeleteConversationPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
)

/**
 * Mobile Protocol v2 `conversation_deleted` reply payload (#532): the daemon's correlated ack that
 * the conversation was removed. **Decode-only** — the record is gone, so the ack is not a
 * `conversation_updated` (which would need name/cwd/last_used_at the deleted record can't project);
 * it carries **only** the deleted `id`. The decode's sole purpose is validating the reply shape
 * (the [SessionSettingsUpdatedPayloadDto] posture) — the value is discarded, because the repository
 * already holds the id it sent. Request↔reply correlation rides `Envelope.inReplyTo`, not this field.
 *
 * Wire SSOT: server `internal/protocol/conversations_write.go` `ConversationDeletedPayload` (#822 /
 * PR #884). The JSON key is `id` (not `conversation_id`), so no `@SerialName` is needed.
 */
@Serializable
data class ConversationDeletedPayloadDto(
    val id: String,
)
