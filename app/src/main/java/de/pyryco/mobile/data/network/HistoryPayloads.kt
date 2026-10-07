package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.repository.HistoryEntry
import de.pyryco.mobile.data.repository.HistoryPage
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Mobile Protocol v2 `request_history` request payload (#623): the phone→binary ask for one backward
 * step of a conversation's scroll-back. **Encode-only** — the phone sends it; the daemon replies with
 * a correlated [HistoryPagePayloadDto]. Always encode through [MobileJson].
 *
 * Wire SSOT: `../pyrycode/docs/protocol-mobile.md` § *Conversation history (v2)* (declared by
 * pyrycode#2113, answered by pyrycode#2116). **All three keys are always present** — the wire has no
 * `omitempty` here and a decoder may rely on all three — so no field carries a default and
 * [MobileJson]'s `explicitNulls = false` has nothing to elide.
 *
 * [cursor] is the previous page's position echoed **verbatim**; the phone never parses, rebuilds or
 * validates one. Empty means "start at the newest", the normal opening value of a walk. [limit] is a
 * request and not a guarantee: `0` asks the daemon to choose, a large ask is clamped, and a page may
 * come back shorter to fit the daemon's frame size cap. Correlation rides [Envelope.inReplyTo], so
 * there is no request-id key.
 */
@Serializable
data class RequestHistoryPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    val cursor: String,
    val limit: Int,
)

/**
 * Mobile Protocol v2 `history_page` reply payload (#623): the daemon's correlated answer to one
 * `request_history`. **Decode-only** — the single typed boundary that turns the untrusted
 * [Envelope.payload] of a `history_page` envelope into a domain [HistoryPage] via [toHistoryPage].
 *
 * **Every field is required, with no default.** The wire emits all three unconditionally, so an
 * absent key is a malformed page rather than a silently-defaulted one — and `at_start` in particular
 * must never default to `false`, which would read as "keep walking" on a page that said nothing about
 * the end of the log. `entries` is likewise always present (an empty page carries `[]`, never `null`).
 *
 * It deliberately carries **no** `conversation_id`: the client knows which conversation it asked about
 * because it knows which envelope this answers, the shape `session_settings_updated` already takes.
 */
@Serializable
data class HistoryPagePayloadDto(
    val entries: List<HistoryEntryDto>,
    val cursor: String,
    @SerialName("at_start") val atStart: Boolean,
)

/**
 * One entry of a [HistoryPagePayloadDto] (#623) — one stored wire envelope's worth. **Decode-only.**
 *
 * [payload] is kept as a raw [JsonElement] and is **not** decoded here: it is the stored frame's body
 * verbatim, and the consumer that re-reduces a page feeds it back into the same per-type decode arms
 * the live lane uses. [type] is a stored string nothing re-validates, so it stays an open `String`
 * rather than an enum — an unrecognised type must survive the decode rather than fail it. Both are
 * replayed content and stay untrusted; see [HistoryEntry].
 *
 * [id] is the exact unsigned durable on-disk log id, never an `event_id`. Strict numeric parsing
 * rejects coercion; [toHistoryPage] rejects zero. See [HistoryEntry.unsignedId].
 */
@Serializable
data class HistoryEntryDto(
    @Serializable(with = ReadMarkIdSerializer::class) val id: ULong,
    val type: String,
    val payload: JsonElement,
    val ts: String,
)

/**
 * Map a decoded [HistoryPagePayloadDto] to its domain [HistoryPage], preserving the wire's
 * newest-first entry order verbatim (no re-sort, no re-key, no dedup — ordering is daemon-authoritative).
 *
 * Positive identity validation and `ts` → [HistoryEntry.timestamp] via `Instant.parse` can fail after
 * structural decode: zero or a malformed timestamp throws [IllegalArgumentException] rather
 * than punning a default, exactly as [MessagePayloadDto.toMessage] does for an envelope `ts`. Both
 * failure kinds are scoped to the one awaiting caller and mutate nothing.
 */
fun HistoryPagePayloadDto.toHistoryPage(): HistoryPage =
    HistoryPage(
        entries =
            entries.map { entry ->
                HistoryEntry(
                    unsignedId = entry.id,
                    type = entry.type,
                    payload = entry.payload,
                    timestamp = Instant.parse(entry.ts),
                )
            },
        cursor = cursor,
        atStart = atStart,
    )
