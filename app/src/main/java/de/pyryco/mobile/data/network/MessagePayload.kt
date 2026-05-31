package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mobile Protocol v2 `message` application payload (#317): the single typed
 * decode-and-validate boundary that turns the untrusted [Envelope.payload]
 * ([kotlinx.serialization.json.JsonElement]) of a `message` envelope into a domain
 * [Message] for the message-thread read path (#312). The server returns this same
 * `message` payload as the persisted-message echo to a `send_message`, so that response
 * mapping is covered by this one boundary too.
 *
 * Wire SSOT: server `internal/protocol/messaging.go` `MessagePayload` (#272);
 * `protocol-mobile.md § Application message types` confirms v2 adds/removes no fields vs
 * v1. The payload IS the object (NOT object-wrapped, unlike #316's array), carrying
 * exactly four required fields. The message timestamp is the **envelope** `ts`, not a
 * payload field — see [toMessage]. Snake_case wire names map to camelCase Kotlin via
 * [SerialName] (the Go-interop contract, not cosmetic). Always decode through [MobileJson]
 * (`MobileJson.decodeFromJsonElement<MessagePayloadDto>(payload)`), never a default `Json`.
 * Decode-only: the phone never sends a `message` payload.
 *
 * [conversationId] is modeled for wire fidelity but maps to **no** domain [Message] field
 * (the domain message carries no conversation id; the consuming repository already knows
 * which conversation it requested). Keeping it a required `String` is the deliberate
 * strict-decode posture for an untrusted boundary — mirrors #316's unused `last_message_ts`.
 */
@Serializable
data class MessagePayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("message_id") val messageId: String,
    val role: WireRole,
    val text: String,
)

/**
 * The closed set of wire `role` values this mapper **accepts** — NOT the full set the wire
 * can emit. The wire role set is `{user, assistant, system}` today (`tool` is a documented
 * future-additive role, #272); `system` is deliberately omitted because the domain [Role]
 * enum has no `System` member and is fixed, so a `system` message has no domain target.
 *
 * Modeling only the mappable roles makes kotlinx-serialization reject `system` and any
 * unknown string at decode with a [kotlinx.serialization.SerializationException] — the
 * unmappable-role failure happens at the structural decode boundary, before any [Message] is
 * built, never as a crash or a `null`-pun (AC #3). This holds under [MobileJson]:
 * `ignoreUnknownKeys` affects unknown *keys*, not enum *values*, and `coerceInputValues` is
 * not set; `role` also has no default, so coercion could not apply even if it were added.
 */
@Serializable
enum class WireRole {
    @SerialName("user")
    User,

    @SerialName("assistant")
    Assistant,
}

/**
 * Map a decoded [MessagePayloadDto] together with its [envelope] and the caller-supplied
 * [sessionId] to a domain [Message]. Every field is a defined value (AC #2: no `null`-punning):
 *
 *  - `id`          ← [messageId]
 *  - `sessionId`   ← [sessionId] param — caller-supplied (#312 passes the active session id at
 *                    map time); not wire-carried, the payload has no `session_id`.
 *  - `role`        ← [WireRole.toDomain]
 *  - `content`     ← [text]
 *  - `timestamp`   ← `Instant.parse(envelope.ts)` — the envelope RFC-3339 string; a malformed
 *                    value throws [IllegalArgumentException] (kotlinx-datetime), the only failure
 *                    site after a successful decode.
 *  - `isStreaming` = `false` — the protocol emits one *finished* message per `message` envelope
 *                    (token-by-token streaming is the separate `message_chunk` type, out of scope).
 *  - `toolCall`    = `null` — see the mapping site.
 *
 * Takes the whole [Envelope] (not just `ts`) per the ticket, keeping the seam stable for the
 * `send_message`-echo path (which may later want `envelope.inReplyTo` for request correlation);
 * only `envelope.ts` is read today. It does NOT check `envelope.type` — the caller (#312) routes
 * by type and guarantees the envelope/payload correspondence.
 */
fun MessagePayloadDto.toMessage(
    envelope: Envelope,
    sessionId: String,
): Message =
    Message(
        id = messageId,
        sessionId = sessionId,
        role = role.toDomain(),
        content = text,
        timestamp = Instant.parse(envelope.ts),
        isStreaming = false,
        // Role.Tool / ToolCall are unreachable from a `message` payload today (no `tool` wire
        // role), so the domain "non-null toolCall iff role == Tool" invariant holds vacuously.
        // Do NOT "wire it up" — there is no tool-call field on this payload.
        toolCall = null,
    )

private fun WireRole.toDomain(): Role =
    when (this) {
        WireRole.User -> Role.User
        WireRole.Assistant -> Role.Assistant
    }
