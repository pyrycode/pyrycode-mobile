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
 *
 * [attachmentIds] (#1020) is the optional fifth field a stored `role: "user"` history entry carries when
 * the turn named files (pyrycode#2596), with [SendMessagePayloadDto.attachmentIds]'s name and shape. Absent
 * everywhere else, so [toMessage] ignores it; the history reducer and the live `message` arm read it
 * (#1351), and only on a user row.
 */
@Serializable
data class MessagePayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("message_id") val messageId: String,
    val role: WireRole,
    val text: String,
    @SerialName("attachment_ids") val attachmentIds: List<String>? = null,
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
): Message = toMessage(timestamp = Instant.parse(envelope.ts), sessionId = sessionId)

/**
 * The **payload-level** entry point to the same mapping (#645), for a caller that holds a payload and a
 * timestamp but no [Envelope] — a [de.pyryco.mobile.data.repository.HistoryEntry] carries only `type` /
 * `payload` / `ts`, so a stored `message` frame has no envelope to map against.
 *
 * This is the primary of the pair and the envelope form above delegates to it, so the two lanes cannot
 * drift: there is one mapping, reached two ways. The **only** difference is where the instant comes from
 * — parsed from `envelope.ts` live, read off the stored entry on replay — which is also why the parse
 * (and its [IllegalArgumentException] on a malformed value) stays in the envelope form: a `HistoryEntry`
 * has already had its `ts` parsed at the `toHistoryPage` decode boundary, so this form cannot fail.
 */
fun MessagePayloadDto.toMessage(
    timestamp: Instant,
    sessionId: String,
): Message =
    Message(
        id = messageId,
        sessionId = sessionId,
        role = role.toDomain(),
        content = text,
        timestamp = timestamp,
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

/**
 * Mobile Protocol v2 `message_chunk` application payload (#313): the binary→phone backfill
 * response body. Carries a batch of *finished* [MessagePayloadDto] rows ("same shape as
 * `message.payload`, multiple" — server SSOT `internal/protocol/messaging.go`
 * `MessageChunkPayload`, #272). A single envelope `ts` covers every row, so the repository maps
 * each via [toMessage] against the chunk's own [Envelope] and preserves wire/arrival order — there
 * is no per-row timestamp to sort on. Decode through [MobileJson] only; the phone never sends one.
 */
@Serializable
data class MessageChunkPayloadDto(
    val messages: List<MessagePayloadDto>,
)

/**
 * Mobile Protocol v2 `backfill_since` request payload (#313): the phone→binary catch-up request
 * for a conversation's historical messages. **Encode-only** — the phone sends it; the server
 * replies with `message_chunk` (+ `backfill_done`) correlated via [Envelope.inReplyTo].
 *
 * Wire SSOT: server `internal/protocol/messaging.go` `BackfillSincePayload` (#272). Field
 * declaration order matches the Go struct (`since_ts`, `conversation_id`, `max_messages`).
 *
 *  - [conversationId] is modeled non-null because this repository only ever backfills one specific
 *    conversation. The wire field is `*string` where `null` means "all conversations"; this slice
 *    has no use for that, so the simpler non-null shape is used (always a concrete id on the wire).
 *  - [sinceTs] is an RFC-3339 timestamp; this slice always requests the full thread from the Unix
 *    epoch ("all history on first load").
 *  - [maxMessages] is the server's advisory cap on the returned-message count (the server chunks
 *    the response; this is the total it will deliver).
 */
@Serializable
data class BackfillSincePayloadDto(
    @SerialName("since_ts") val sinceTs: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("max_messages") val maxMessages: Int,
)

/**
 * Mobile Protocol v2 `send_message` request payload (#346): the phone→binary request that posts the
 * user's text to a conversation. **Encode-only** — the phone sends it; the only correlated reply is
 * an empty `ack` on success or an `error` on failure (there is no persisted-`Message` echo to the
 * sender to decode), so the sender's thread updates only via a local confirmed-insert after the ack.
 *
 * Wire SSOT: server `internal/protocol/messaging.go` `SendMessagePayload` (#272). Field declaration
 * order matches the Go struct (`conversation_id`, `message_id`, `text`); all three are required.
 *
 *  - [messageId] is **client-generated** (a minted UUID); the repository interface passes only
 *    `conversationId` + `text`, so the id is the sender's correlation handle for the reconstructed
 *    [Message], distinct from the request *envelope* id used for `ack`/`error` correlation.
 *  - [attachmentIds] (#830) names the uploaded attachments the message references, in the caller's
 *    order (`protocol-mobile.md` § Naming a message's attachments). `null` — never `[]` — for a message
 *    naming none, so [MobileJson] (`explicitNulls = false`) omits the key and a text-only send encodes
 *    exactly as before. Build it through [MessageAttachmentIds.forSend].
 */
@Serializable
data class SendMessagePayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("message_id") val messageId: String,
    val text: String,
    @SerialName("attachment_ids") val attachmentIds: List<String>? = null,
)

/**
 * The published per-message bound on `attachment_ids` (#830): at most [MAX] ids. The daemon counts raw
 * elements and refuses an over-bound list with `protocol.malformed`; this client drops repeats first and
 * sends the distinct list, so the count it checks is the count the daemon sees.
 */
object MessageAttachmentIds {
    const val MAX: Int = 32

    /**
     * The `attachment_ids` value for a send naming [ids]: each id once, in first-seen order, or `null`
     * when there are none. Throws [IllegalArgumentException] when more than [MAX] distinct ids remain.
     */
    fun forSend(ids: List<String>): List<String>? {
        val distinct = ids.distinct()
        require(distinct.size <= MAX) { "A message names at most $MAX attachments, not ${distinct.size}" }
        return distinct.ifEmpty { null }
    }
}
