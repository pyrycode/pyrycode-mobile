package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.model.Conversation
import kotlinx.datetime.Instant
import kotlinx.datetime.serializers.InstantIso8601Serializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mobile Protocol v2 mutation-response payload (#318): the single typed decode-and-validate
 * boundary that turns the untrusted [Envelope.payload]
 * ([kotlinx.serialization.json.JsonElement]) of a `conversation_created` (reply to
 * `create_conversation`) or `conversation_updated` (reply to `promote_conversation`) frame into
 * a domain [Conversation] for the future mutation paths of `RemoteConversationRepository`.
 *
 * **One DTO models BOTH response types.** Wire SSOT: server `internal/protocol/conversations_write.go`
 * (#274). `ConversationCreatedPayload` and `ConversationUpdatedPayload` carry the identical field
 * set — `id`, `is_promoted`, `name` (nullable), `cwd`, `last_used_at` — and differ only in struct
 * key order (`cwd`↔`name` swapped). JSON object key order is semantically irrelevant and
 * kotlinx-serialization decodes by key **name**, not position, so this one DTO decodes both wire
 * orderings losslessly. The `conversation_created` vs `conversation_updated` distinction is a
 * wire-`type`-string routing concern handled at the [Envelope.type] layer by the future mutation
 * slices — NOT a shape concern at the decode boundary. Do NOT "fix" this by splitting into two
 * byte-identical classes.
 *
 * Both payloads are a **bare conversation object** at [Envelope.payload] — NOT object-wrapped
 * (contrast #316's `conversations`, which wraps an array). So [Envelope.payload] decodes directly
 * to this type. Neither payload carries `last_message_ts`; this DTO has exactly one timestamp,
 * `last_used_at`.
 *
 * Snake_case wire names map to camelCase Kotlin via [SerialName] (the Go-interop contract, not
 * cosmetic). Always decode through [MobileJson]
 * (`MobileJson.decodeFromJsonElement<ConversationResponseDto>(payload)`), never a default `Json`.
 * Decode-only: the phone never sends these payloads.
 *
 * [name] is nullable with no default — `null` is the valid "unnamed conversation" state, preserved
 * into the domain. [lastUsedAt] decodes to [Instant] (via [InstantIso8601Serializer]) so a
 * malformed RFC 3339 string fails at decode — making decode the single validate boundary and
 * keeping [toConversation] a total, pure field copy with no second throw site.
 */
@Serializable
data class ConversationResponseDto(
    val id: String,
    val name: String?,
    @SerialName("is_promoted") val isPromoted: Boolean,
    val cwd: String,
    @SerialName("last_used_at")
    @Serializable(with = InstantIso8601Serializer::class)
    val lastUsedAt: Instant,
)

/**
 * Map the decoded mutation-response payload to a domain [Conversation], preserving `name`
 * nullability and the `cwd` verbatim (incl. [de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD]).
 * Pure and total over a validly-decoded DTO — decode is the single failure surface, so this
 * never throws.
 */
fun ConversationResponseDto.toConversation(): Conversation =
    Conversation(
        id = id,
        name = name,
        cwd = cwd,
        isPromoted = isPromoted,
        lastUsedAt = lastUsedAt,
        // Mutation-response-tier placeholders: the create/promote response does not carry these.
        // Same rule as #316 (the canonical rule source) — full session / sleep / archive state
        // arrives via the detail + message read paths, not a mutation response. They are defined,
        // non-null defaults — do NOT "fix" by null-punning or by plumbing upstream enrichment here.
        currentSessionId = "",
        sessionHistory = emptyList(),
        isSleeping = false,
        archived = false,
    )
