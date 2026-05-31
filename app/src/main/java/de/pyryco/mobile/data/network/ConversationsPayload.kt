package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.model.Conversation
import kotlinx.datetime.Instant
import kotlinx.datetime.serializers.InstantIso8601Serializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mobile Protocol v2 `conversations` application payload (#316): the single typed
 * decode-and-validate boundary that turns the untrusted [Envelope.payload]
 * ([kotlinx.serialization.json.JsonElement]) of a `list_conversations` reply into domain
 * [Conversation] values for the conversation-list read path (#312).
 *
 * Wire SSOT: server `internal/protocol/conversations_read.go` (#273). The array is
 * **object-wrapped** under the `conversations` key — NOT a bare top-level array — so the
 * frame decodes to this wrapper, whose [conversations] field is the row list. Snake_case
 * wire names map to camelCase Kotlin via [SerialName] (the Go-interop contract, not
 * cosmetic). Always decode through [MobileJson]
 * (`MobileJson.decodeFromJsonElement<ConversationsPayload>(payload)`), never a default
 * `Json`. Decode-only: the phone never sends `conversations`.
 */
@Serializable
data class ConversationsPayload(
    val conversations: List<ConversationSummaryDto>,
)

/**
 * One per-conversation summary row.
 *
 * [name] is nullable with no default — `null` is the valid "unnamed scratch conversation"
 * state, preserved into the domain. [lastMessageTs] is carried for wire fidelity but maps
 * to **no** domain field today (the domain [Conversation] carries [Conversation.lastUsedAt]
 * only); #303 currently projects `last_message_ts = last_used_at`.
 *
 * Timestamps decode to [Instant] here (via [InstantIso8601Serializer]) so a malformed
 * RFC 3339 string fails at decode — making decode the single validate boundary and keeping
 * [toConversations] a total, pure field copy with no second throw site.
 */
@Serializable
data class ConversationSummaryDto(
    val id: String,
    val name: String?,
    @SerialName("is_promoted") val isPromoted: Boolean,
    val cwd: String,
    @SerialName("last_message_ts")
    @Serializable(with = InstantIso8601Serializer::class)
    val lastMessageTs: Instant,
    @SerialName("last_used_at")
    @Serializable(with = InstantIso8601Serializer::class)
    val lastUsedAt: Instant,
)

/**
 * Map the decoded payload to domain [Conversation] values, preserving wire order and each
 * row's `is_promoted` flag. Pure and total over a validly-decoded payload — decode is the
 * single failure surface, so this never throws.
 *
 * No tiering, sorting, or filtering: splitting channels (promoted) from discussions
 * (unpromoted) and any display ordering are #312's concern.
 */
fun ConversationsPayload.toConversations(): List<Conversation> = conversations.map { it.toConversation() }

private fun ConversationSummaryDto.toConversation(): Conversation =
    Conversation(
        id = id,
        name = name,
        cwd = cwd,
        isPromoted = isPromoted,
        lastUsedAt = lastUsedAt,
        // List-tier placeholders: the conversation-list payload does not carry these.
        // Full session / sleep / archive state arrives via the detail + message read paths.
        // They are defined, non-null defaults — do NOT "fix" by null-punning or by plumbing
        // upstream enrichment here.
        currentSessionId = "",
        sessionHistory = emptyList(),
        isSleeping = false,
        archived = false,
    )
