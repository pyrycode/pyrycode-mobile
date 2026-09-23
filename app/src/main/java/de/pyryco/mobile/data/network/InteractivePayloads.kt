package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.ModalContext
import de.pyryco.mobile.data.model.ModalEvent
import de.pyryco.mobile.data.model.ModalOption
import de.pyryco.mobile.data.model.ToolDenial
import de.pyryco.mobile.data.repository.ApiRetryStatus
import de.pyryco.mobile.data.repository.BoundaryReason
import de.pyryco.mobile.data.repository.QueuedMessage
import de.pyryco.mobile.data.repository.ResetStatus
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.UnrecognizedSite
import de.pyryco.mobile.data.repository.UsageLimitReading
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Mobile Protocol v2 structured live-session stream payloads (#385): the `internal` decode DTOs for
 * the five **binary → phone** interactive envelopes — `turn_state`, `assistant_delta`, `tool_use`,
 * `tool_result`, `turn_end` — plus their `toEvent()` mappers to the portable
 * [LiveSessionEvent] family. **Decode-only** — the phone never sends one. Always decode through
 * [MobileJson] (`MobileJson.decodeFromJsonElement<…>(payload)`), never a default `Json`.
 *
 * Wire SSOT: pyrycode `internal/protocol` interactive structs + `docs/protocol-mobile.md`
 * § "Interactive events (v2, capability-gated)" (#607). Every payload field is **always present**
 * (no `omitempty`), so each DTO field is a required non-null `String`/`Int`/`Boolean`; snake_case
 * wire names map to camelCase via [SerialName] (the Go-interop contract, not cosmetic). This strict
 * shape is the fail-closed posture for an untrusted boundary: a missing/wrong-typed field fails the
 * structural decode with a [kotlinx.serialization.SerializationException] rather than a `null`-pun,
 * so the caller can drop the one malformed envelope and keep the stream alive (AC #4).
 *
 * This file carries multiple top-level types, so the ktlint single-class filename rule does not
 * apply (cf. `MobileWireModels.kt`). The DTOs stay `internal` to `data/network` (the ticket's "raw
 * decode DTOs stay internal"); only [LiveSessionEvent] crosses the package boundary.
 */
@Serializable
internal data class TurnStatePayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    val state: String,
)

@Serializable
internal data class AssistantDeltaPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("turn_id") val turnId: String,
    val seq: Int,
    val text: String,
)

/**
 * `tool_use`. [parentToolUseId] and [input] (#810) are the two **lenient-defaulted** fields in this
 * file, a deliberate departure from the strict-required posture above: the daemon always emits both
 * keys, so an absent one means an older binary, which meant "main thread" and "no fields" — decoding
 * it to `""` / `{}` is what that binary said, whereas failing would drop the whole row.
 *
 * [input] is a raw [JsonElement] rather than a typed map because the AC requires an absent, `null`,
 * empty or non-object input to yield no fields instead of a decode failure; [toInputFields] narrows
 * it. A wire `null` for [parentToolUseId] still fails the decode — the contract never sends one.
 */
@Serializable
internal data class ToolUsePayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("turn_id") val turnId: String,
    @SerialName("tool_use_id") val toolUseId: String,
    val name: String,
    @SerialName("input_summary") val inputSummary: String,
    @SerialName("parent_tool_use_id") val parentToolUseId: String = "",
    val input: JsonElement? = null,
)

/** `tool_result`. [parentToolUseId] is lenient-defaulted for the reason [ToolUsePayloadDto] states (#810). */
@Serializable
internal data class ToolResultPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("turn_id") val turnId: String,
    @SerialName("tool_use_id") val toolUseId: String,
    @SerialName("is_error") val isError: Boolean,
    @SerialName("result_summary") val resultSummary: String,
    @SerialName("parent_tool_use_id") val parentToolUseId: String = "",
)

/**
 * `tool_denied` (#811, pyrycode#2233): claude refused the call [toolUseId] names. The seven strings are
 * strict-required, the [UnrecognizedMessagePayloadDto] posture — a missing or wrong-typed one fails the
 * decode and the one envelope is dropped. The two report arrays take [RateLimitedPayloadDto.truncatedFields]'
 * shape, so `null` and `[]` decode apart; do not coalesce them as [QueueStatePayloadDto.toQueue] does.
 *
 * Not a [LiveSessionEvent]: nothing on the live stream consumes a denial, so it folds into the thread
 * store only. Both lanes decode it through this type and [toDenial].
 */
@Serializable
internal data class ToolDeniedPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("turn_id") val turnId: String,
    @SerialName("tool_use_id") val toolUseId: String,
    @SerialName("tool_name") val toolName: String,
    @SerialName("decision_reason_type") val decisionReasonType: String,
    @SerialName("decision_reason") val decisionReason: String,
    val message: String,
    @SerialName("truncated_fields") val truncatedFields: List<String>? = null,
    @SerialName("dropped_fields") val droppedFields: List<String>? = null,
)

/**
 * Total verbatim copy of a [ToolDeniedPayloadDto] into the retained [ToolDenial]. The routing keys —
 * `conversation_id`, `tool_use_id`, `turn_id` — stay on the DTO for the caller; no token is interpreted.
 */
internal fun ToolDeniedPayloadDto.toDenial(): ToolDenial =
    ToolDenial(
        toolName = toolName,
        decisionReasonType = decisionReasonType,
        decisionReason = decisionReason,
        message = message,
        truncatedFields = truncatedFields,
        droppedFields = droppedFields,
    )

/**
 * `tool_progress` (#812, pyrycode#2324): claude's signed elapsed-seconds reading for the open call
 * [toolUseId] names. All four fields are strict-required — the daemon pins every key — so a missing or
 * wrong-typed one, or a reading outside [Int], drops the one envelope. [toolUseId] is an untrusted join
 * handle and [elapsedSeconds] an upstream reading: neither is routing input, authority or timing evidence.
 *
 * Not a [LiveSessionEvent], for [ToolDeniedPayloadDto]'s reason. Both lanes decode it through this type and
 * fold it with `withToolProgress`; there is no domain type to map into, since the retained value is the
 * one [Int].
 */
@Serializable
internal data class ToolProgressPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("turn_id") val turnId: String,
    @SerialName("tool_use_id") val toolUseId: String,
    @SerialName("elapsed_seconds") val elapsedSeconds: Int,
)

/**
 * `turn_end`. The four trailing fields (#805) are claude's own stop shape and are **optional and open-set**
 * on the wire: an absent one — an older daemon — decodes to its empty value, the lenient-default posture
 * [ToolUsePayloadDto.parentToolUseId] set, and an unrecognised token is just a string. The eight numeric
 * fields on the same frame are not decoded.
 */
@Serializable
internal data class TurnEndPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("turn_id") val turnId: String,
    @SerialName("stop_reason") val stopReason: String,
    val outcome: String = "",
    @SerialName("is_error") val isError: Boolean = false,
    @SerialName("terminal_reason") val terminalReason: String = "",
    @SerialName("error_category") val errorCategory: String = "",
)

/**
 * The `stall` control event (#395, pyrycode#638/#639): the remote claude has stopped making forward
 * progress. The wire payload is `{conversation_id}` only — the peer of [TurnStatePayloadDto] minus
 * `state` — and is **onset-only** (tui-driver's `stall_detected` has no clearing edge; recovery is
 * inferred mobile-side from the next forward-progress event). No `toEvent()` mapper: a stall is
 * *state*, not one of the five [LiveSessionEvent] streaming events, so it never lands on the live
 * event stream. The required-`String` field is the fail-closed posture — a missing/wrong-typed
 * `conversation_id` fails the structural decode and the one envelope is dropped (AC #3).
 */
@Serializable
internal data class StallPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
)

/**
 * The `queue_state` snapshot event (#460, pyrycode#705/#720): the full ordered backlog of messages the
 * daemon has queued for a conversation while claude is busy — the wire form of `msgqueue.Snapshot`, in
 * FIFO/enqueue order. Decode-only — the phone never sends one. Always decode through [MobileJson].
 *
 * Wire SSOT: pyrycode `docs/protocol-mobile.md` § Queue (v2); ADR 025. Shape:
 * `{conversation_id, queued:[{queued_msg_id, message_id, text, ts}]}`. [conversationId], and every
 * [QueuedMessageDto] field, are **required, non-null** — the fail-closed posture of every sibling DTO
 * (a missing/wrong-typed field throws a [kotlinx.serialization.SerializationException], dropping the
 * one envelope). `queued_msg_id` is a wire **uint64** monotonic counter, decoded as a [Long] (the same
 * posture as [Envelope.eventId]) — a `String` would be wrong (pyrycode#720 flags it).
 *
 * The **one** documented latitude is [queued] itself: an empty backlog may marshal to either `null`
 * (`[]QueuedItem(nil)`) or `[]`, so it is nullable-defaulted and [toQueue] coalesces both (and a
 * missing key) to `emptyList()`. Without this, a wire `null` into a non-nullable list would throw
 * ([MobileJson] sets no `coerceInputValues`). Every other field stays strict-required — including
 * [QueuedMessageDto.messageId], whose `""` is a legal *value* rather than an absent field, so the
 * strictness costs nothing a conforming daemon would trip. The consequence, stated once: a daemon
 * predating pyrycode#2092 omits the key entirely and its whole snapshot drops, so the backlog view
 * empties rather than degrading to "no correlation" (#781).
 */
@Serializable
internal data class QueuedMessageDto(
    @SerialName("queued_msg_id") val queuedMsgId: Long,
    /**
     * The `message_id` the client minted on the `send_message` that produced this item, relayed
     * **verbatim** by the daemon (pyrycode#2092, #781) — byte-for-byte what a client sent, never
     * trimmed, lower-cased or re-encoded on the way through, and `""` when the client sent none.
     *
     * It **addresses nothing**: `dequeue_message` still resolves `conversation_id` + `queued_msg_id`,
     * and no daemon path reads this to route, authorize, match or dedupe. It is a correlation key for
     * the client only, uniqueness enforced nowhere — two items may legally carry the same one.
     * Compare it for **equality only**: never render it, never key a list on it, never log it.
     */
    @SerialName("message_id") val messageId: String,
    val text: String,
    val ts: String,
)

@Serializable
internal data class QueueStatePayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    val queued: List<QueuedMessageDto>? = null,
)

/**
 * Map a decoded [QueueStatePayloadDto] to the portable ordered [QueuedMessage] backlog (#460),
 * preserving wire array order verbatim (no sort, no dedup — AC #1). A `null`/absent [queued] coalesces
 * to `emptyList()` (the documented empty-backlog latitude). Element strictness is preserved: a bad item
 * (a non-parseable [QueuedMessageDto.ts]) throws [IllegalArgumentException] inside the `map`, dropping
 * the **whole** snapshot at the decode boundary — the established "one bad row drops the chunk" idiom.
 */
internal fun QueueStatePayloadDto.toQueue(): List<QueuedMessage> =
    queued.orEmpty().map {
        QueuedMessage(
            id = it.queuedMsgId,
            text = it.text,
            timestamp = Instant.parse(it.ts),
            // Verbatim, like `text`: a correlation key is only useful byte-for-byte (#781, AC #1).
            messageId = it.messageId,
        )
    }

/**
 * The `api_retry` control event (#593, pyrycode#1074): claude is stuck retrying an API error, so the
 * thread can say so instead of showing an indefinite thinking spinner. Decode-only — the phone never
 * sends one. Always decode through [MobileJson].
 *
 * Wire SSOT: pyrycode `internal/protocol/interactive.go` (`ApiRetryPayload`) +
 * `docs/protocol-mobile.md` § `api_retry`. Shape: `{conversation_id, active, current, total}`. Every
 * field is **strict-required, non-null** — unlike [QueueStatePayloadDto.queued] this payload has no
 * documented nullable latitude (the Go struct sets no `omitempty`, so boundary values like
 * `active: false` / `current: 0` always serialize), so strictness is uniform: a missing field, or one
 * whose JSON shape cannot be read as its declared type (a number where a `String` is declared, a
 * non-integral or out-of-`Int32` counter), fails the structural decode with a
 * [kotlinx.serialization.SerializationException] and the one envelope is dropped (AC #3). One measured
 * latitude to be aware of when cloning this arm: kotlinx's *tree* decoder accepts a **quoted** primitive
 * whose content is otherwise valid (`"active":"true"`, `"current":"3"`) even with `isLenient = false`,
 * so those decode to the same value an unquoted frame would give rather than dropping — harmless here,
 * and not a strictness probe a test should lean on.
 *
 * [active] is the edge — `true` on onset, `false` once claude recovered. The rising edge **re-fires**
 * whenever the parsed count climbs (`3/10` → `4/10`), so a repeat `active: true` is a counter update,
 * not a redundant onset. The falling edge carries the last-known counter verbatim rather than zeros;
 * [toStatus] is where the "ignore the counter when inactive" contract is enforced.
 */
@Serializable
internal data class ApiRetryPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    val active: Boolean,
    val current: Int,
    val total: Int,
)

/**
 * Map a decoded [ApiRetryPayloadDto] to the portable [ApiRetryStatus]. **Total — never null**,
 * deliberately unlike [TurnStatePayloadDto.toEvent] / [SessionTransitionPayloadDto.toBoundary], whose
 * unrecognized-*value* drop is a mapper concern: there is no unrecognized value to reject here
 * ([active] is a bool, the counter two ints), so structural malformation stays the only path that
 * drops a frame.
 *
 * Three cases. An inactive payload maps to [ApiRetryStatus.NotRetrying] **discarding [current] /
 * [total]** — the single place the "a client ignores the counter on the falling edge" contract is
 * enforced, so the stale counter the daemon copies there structurally cannot reach the projection.
 * An active payload with both counter fields positive carries them verbatim (no clamping: bounding an
 * extreme or incoherent pair is a display concern #594 owns, and rewriting server data at the decode
 * boundary would diverge from every sibling mapper's carry-verbatim posture). Any other active shape —
 * the documented unparsed `{0, 0}`, a partially-zero `{3, 0}`, a negative from a hostile daemon —
 * maps to [ApiRetryStatus.AttemptUnknown]. Treating those as "retrying, count unknown" rather than
 * dropping the envelope is the safer failure: a drop would discard a **real retry onset** and leave
 * the thread on the indefinite spinner this feature exists to replace.
 */
internal fun ApiRetryPayloadDto.toStatus(): ApiRetryStatus =
    when {
        !active -> ApiRetryStatus.NotRetrying
        current > 0 && total > 0 -> ApiRetryStatus.Attempt(current, total)
        else -> ApiRetryStatus.AttemptUnknown
    }

/**
 * The `compacting` control event (#596, pyrycode#1074): claude is auto-compacting its context, so the
 * thread can say so instead of showing a spinner that looks frozen — the daemon's only signal that
 * something is happening while the content channel goes silent for tens of seconds. Decode-only — the
 * phone never sends one. Always decode through [MobileJson].
 *
 * Wire SSOT: pyrycode `internal/protocol/interactive.go` (`CompactingPayload`) +
 * `docs/protocol-mobile.md` § `compacting`. Shape: `{conversation_id, active}` — exactly two fields,
 * both **strict-required, non-null** (the Go struct sets no `omitempty`, so `active: false` always
 * serializes). A missing field, or one whose JSON shape cannot be read as its declared type (a number
 * where a `String` is declared, an object or array where a `Boolean` is), fails the structural decode
 * with a [kotlinx.serialization.SerializationException] and the one envelope is dropped (AC #5). The
 * measured latitude documented on [ApiRetryPayloadDto] applies here too: kotlinx's *tree* decoder
 * accepts a **quoted** primitive (`"active":"true"`) even with `isLenient = false`, so that is not a
 * strictness probe a test should lean on.
 *
 * [active] is the edge — `true` on onset, `false` once compaction finished. Unlike [StallPayloadDto]
 * this carries a **real falling edge**, so the state must never stick after it (the sibling `stall`
 * infers recovery from forward progress instead). **Banner-only:** the upstream detector streams no
 * compaction progress, so there is deliberately no counter, percent, or ETA field.
 *
 * No `toX()` mapper, following [StallPayloadDto]'s precedent and deliberately unlike
 * [ApiRetryPayloadDto.toStatus]: that one exists to collapse four wire fields into a counter-carrying
 * domain type, whereas these two wire fields already *are* the domain shape (a `String` routing key
 * plus a `Boolean`), so a mapper would be a ceremonial identity function. This is also *state*, not
 * one of the five [LiveSessionEvent] streaming events, so it never lands on the live event stream.
 */
@Serializable
internal data class CompactingPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    val active: Boolean,
)

/**
 * The `resetting` control event (#871, pyrycode#2478): the daemon is running a conversation Reset — a
 * wrap-up turn that writes a handoff note, then a respawn of claude under a new session. Decode-only —
 * the phone never sends one. Always decode through [MobileJson].
 *
 * Wire SSOT: pyrycode `docs/protocol-mobile.md` § `resetting`, which owns the edge sequence; it is cited
 * here, not restated. Shape: `{conversation_id, active, phase, handoff}` — four fields, all
 * **strict-required, non-null** with no Kotlin default (the [CompactingPayloadDto] posture): the daemon
 * never omits a key, and a falling edge carries both strings as `""` rather than dropping them. A missing
 * field, or one whose JSON shape cannot be read as its declared type, fails the structural decode and the
 * one envelope is dropped. The quoted-primitive latitude documented on [ApiRetryPayloadDto] applies here
 * too.
 *
 * [phase] and [handoff] are plain `String`s rather than enums: an unrecognised token is a *mapper* drop
 * (see [toStatus]), not a decode failure — the [SessionTransitionPayloadDto.reason] posture. Both are
 * daemon-selected constants, never claude-authored, and no note text crosses the wire.
 *
 * This is **state**, not one of the [LiveSessionEvent] streaming events, so it never lands on the live
 * event stream, and it opens, closes and alters no turn.
 */
@Serializable
internal data class ResettingPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    val active: Boolean,
    val phase: String,
    val handoff: String,
)

/**
 * Map a **rising-edge** [ResettingPayloadDto] to a [ResetStatus], or **null** when [ResettingPayloadDto.phase]
 * or [ResettingPayloadDto.handoff] is outside its closed set — including the empty strings, which belong
 * only to the falling edge. Like [SessionTransitionPayloadDto.toBoundary] the null is an unrecognised-value
 * drop, distinct from a malformed envelope. The falling edge never reaches this mapper: its strings are
 * meaningless, so the caller clears on `active: false` without reading them.
 */
internal fun ResettingPayloadDto.toStatus(): ResetStatus? {
    val resetPhase = phase.toResetPhase() ?: return null
    val resetHandoff = handoff.toResetHandoff() ?: return null
    return ResetStatus(resetPhase, resetHandoff)
}

private fun String.toResetPhase(): ResetStatus.Phase? =
    when (this) {
        "wrapping_up" -> ResetStatus.Phase.WrappingUp
        "restarting" -> ResetStatus.Phase.Restarting
        else -> null
    }

private fun String.toResetHandoff(): ResetStatus.Handoff? =
    when (this) {
        "pending" -> ResetStatus.Handoff.Pending
        "written" -> ResetStatus.Handoff.Written
        "skipped" -> ResetStatus.Handoff.Skipped
        else -> null
    }

/**
 * The `rate_limited` control event (#802, pyrycode#1405/#1410): what claude said about its usage-limit
 * window, so a turn that stops making progress because of one can say why. Decode-only — the phone
 * never sends one. Always decode through [MobileJson].
 *
 * Wire SSOT: pyrycode `internal/protocol/interactive.go` (`RateLimitedPayload`) +
 * `docs/protocol-mobile.md` § `rate_limited`, which is where the field semantics live — cited here,
 * not restated. Shape: `{conversation_id, status, limit_type, resets_at, utilization,
 * truncated_fields}`.
 *
 * **Four fields are strict-required and two are nullable**, and the split follows the wire rather than
 * taste. The four required ones set no `omitempty`, so a missing one — or one whose JSON shape cannot
 * be read as its declared type — fails the structural decode with a
 * [kotlinx.serialization.SerializationException] and the one envelope is dropped (AC #1), the
 * [CompactingPayloadDto] posture. [utilization] and [truncatedFields] are the documented nullables:
 * the daemon always emits both keys, so an unreported value arrives as a literal `null` rather than a
 * dropped key, and the Kotlin defaults only cover a non-conforming producer. [MobileJson]'s
 * `explicitNulls = false` collapsing omitted with explicit-`null` is correct for both — the very
 * collapse `effective_effort` had to *avoid*, because its three states mean three different things.
 * An out-of-contract `[]` on `truncated_fields` decodes to an empty list rather than being punned to
 * `null`, so what arrived is what is retained. The measured latitude documented on
 * [ApiRetryPayloadDto] applies here too: kotlinx's *tree* decoder accepts a **quoted** primitive
 * (`"resets_at":"0"`) even with `isLenient = false`, so that is not a strictness probe a test should
 * lean on.
 *
 * **[resetsAt] is a [Long] and that is load-bearing rather than stylistic.** The Go field is `int64`
 * and the contract admits year-40000 values — roughly `1.2e12` unix seconds, an order of magnitude
 * past `Int32`. An `Int` here would fail the *structural* decode on exactly the out-of-range value the
 * contract requires be **carried**, turning a carry-verbatim rule into a silent drop through a type
 * choice. Nothing clamps or range-checks it; `0` means claude reported no reset, **not** the epoch.
 *
 * **[utilization] is a `Double?` for the same reason it is a `*float64` upstream**: `null` and `0.0`
 * are different facts, and absence is the *common* case. Reading a missing reading as zero renders a
 * fresh window as an exhausted one. It is claude's number and **not a bounded fraction** — nothing
 * clamps, rounds or rescales it here.
 *
 * **SECURITY.** [status] and [limitType] are claude-authored strings that crossed the subprocess trust
 * boundary; the daemon bounds them at construction and does **not** sanitize them. They cross this
 * boundary **verbatim** — never trimmed, normalised, lower-cased, allow-listed or shape-checked. No
 * client-side length cap is added: the daemon bounds both at construction and
 * `OkHttpRelayTransport`'s frame contract bounds the envelope ahead of any parse, so a third bound
 * would defend a failure that cannot reach this code, and [truncatedFields] is how a consumer learns a
 * value lost characters. Nothing on this path is logged. See [UsageLimitReading] for the obligations
 * that travel with the decoded value.
 */
@Serializable
internal data class RateLimitedPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    val status: String,
    @SerialName("limit_type") val limitType: String,
    @SerialName("resets_at") val resetsAt: Long,
    val utilization: Double? = null,
    @SerialName("truncated_fields") val truncatedFields: List<String>? = null,
)

/**
 * The single benign `status` value (#802) — the **one** value anything in this client compares against,
 * and the discriminator that tells a clearing edge from a warning. Every other status is an opaque
 * label to render, never a case to branch on: the value set beyond this one is almost entirely
 * unmeasured, so narrowing it would drop the first real limit that fires. `private` so the comparison
 * cannot spread beyond [toReading].
 */
private const val STATUS_BENIGN = "allowed"

/**
 * Map a decoded [RateLimitedPayloadDto] to the portable [UsageLimitReading], or **null** on the benign
 * **falling edge** — claude's latest reading of the window is benign, so the holder's entry is cleared.
 *
 * This mapper is the **sole owner of the edge semantics**, the [ApiRetryPayloadDto.toStatus] precedent
 * rather than the `compacting` arm's `if (active)`: there a mapper would have been a ceremonial
 * identity function, whereas here five wire fields collapse into a four-field reading plus a routing
 * key the reading must not carry. Keeping the decision here means the benign comparison is expressed
 * **exactly once** and a second `if` in the inbound arm cannot encode the same rule differently.
 *
 * Unlike [TurnStatePayloadDto.toEvent] / [SessionTransitionPayloadDto.toBoundary], a `null` here is
 * **not** an unrecognized-value drop: an unrecognised status is an ordinary warning and surfaces
 * verbatim. That asymmetry is the wire's, not this client's — a reader who assumes the two nulls mean
 * the same thing has it backwards.
 *
 * Every field is otherwise a **total verbatim copy**. Nothing is clamped, rounded, rescaled,
 * range-checked, trimmed or narrowed, and `conversation_id` is deliberately **not** copied into the
 * reading — it stays the caller's routing key (see [UsageLimitReading]).
 */
internal fun RateLimitedPayloadDto.toReading(): UsageLimitReading? =
    if (status == STATUS_BENIGN) {
        null
    } else {
        UsageLimitReading(
            status = status,
            limitType = limitType,
            resetsAt = resetsAt,
            utilization = utilization,
            truncatedFields = truncatedFields,
        )
    }

/**
 * The `thinking_progress` control event (#801, pyrycode#1386): claude is actively reasoning, and
 * roughly how much — its **only** mid-turn proof of life on the stream-json surface, since nothing else
 * crosses the wire during a long assistant turn. Decode-only — the phone never sends one. Always decode
 * through [MobileJson].
 *
 * Wire SSOT: pyrycode `internal/protocol/interactive.go` (`ThinkingProgressPayload`) +
 * `docs/protocol-mobile.md` § `thinking_progress`, which owns the measured consumer hazards; they are
 * restated on [de.pyryco.mobile.data.repository.ThinkingProgress] for the consumers that hold the
 * decoded value, not here. Shape: `{conversation_id, estimated_tokens, estimated_tokens_delta}` — three
 * fields, all **strict-required, non-null** with no Kotlin default (the [CompactingPayloadDto]
 * posture): the Go struct sets no `omitempty`, so a `0` reading arrives **present-and-zero** rather
 * than omitted, which is exactly what lets every field stay required. A missing field, or one whose
 * JSON shape cannot be read as its declared type, fails the structural decode with a
 * [kotlinx.serialization.SerializationException] and the one envelope is dropped (AC #1). The measured
 * latitude documented on [ApiRetryPayloadDto] applies here too: kotlinx's *tree* decoder accepts a
 * **quoted** primitive even with `isLenient = false`, so that is not a strictness probe a test should
 * lean on.
 *
 * Both readings are [Long], not [Int]: the Go fields are 64-bit `int`s, so a narrower Kotlin type would
 * fail to decode a value the wire can legally express and drop the frame — the width trap
 * [de.pyryco.mobile.data.repository.QueuedMessage.id] records for `queued_msg_id`. Neither is validated
 * or bounded here; a reading is carried **verbatim**, including one that falls below its predecessor
 * (the frame is emitted per inference request, and the reading restarts at every request boundary).
 *
 * No `toX()` mapper, following [CompactingPayloadDto]'s precedent and deliberately unlike
 * [ApiRetryPayloadDto.toStatus]: that one exists to collapse four wire fields into a counter-carrying
 * domain type with real edge semantics, whereas here nothing is narrowed, dropped or validated — the
 * two integers already *are* the domain shape — so a mapper would be a ceremonial field copy. The
 * decoder strips the routing id and constructs the domain value inline.
 *
 * This is **state**, not one of the [LiveSessionEvent] streaming events, so it never lands on the live
 * event stream. It carries **no `turn_id`** and drives no turn lifecycle: the daemon emits it during an
 * inference request that may not have produced assistant content yet, so a turn opened on one would
 * have no guaranteed end, and the turn's thinking state is already [LiveSessionEvent.TurnState]'s. It
 * also carries **no reasoning text** — the content of claude's thinking is never forwarded on this wire
 * (ADR 025), so a consumer that tries to render text has nothing to render.
 */
@Serializable
internal data class ThinkingProgressPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("estimated_tokens") val estimatedTokens: Long,
    @SerialName("estimated_tokens_delta") val estimatedTokensDelta: Long,
)

/**
 * Map a decoded [TurnStatePayloadDto] to a [LiveSessionEvent.TurnState], or **null** when [state] is
 * not one of the three documented values (AC #3). Modeling `state` as a plain `String` in the DTO
 * (not a strict enum) keeps the unrecognized-value decision a *mapper* concern: an unknown `state`
 * yields `null` here — the demux drops that one envelope and the stream survives — whereas a strict
 * enum would have failed the *decode* and conflated "unknown state" with "malformed envelope". An
 * **absent** `state` field is a different path: the required `String` makes decode itself throw,
 * also dropped (AC #4). Both converge on drop-without-crash, with no `Phase.Unknown` member to push
 * onto consumers.
 */
internal fun TurnStatePayloadDto.toEvent(): LiveSessionEvent? = state.toPhase()?.let { LiveSessionEvent.TurnState(conversationId, it) }

private fun String.toPhase(): LiveSessionEvent.TurnState.Phase? =
    when (this) {
        "thinking" -> LiveSessionEvent.TurnState.Phase.Thinking
        "responding" -> LiveSessionEvent.TurnState.Phase.Responding
        "idle" -> LiveSessionEvent.TurnState.Phase.Idle
        else -> null
    }

/** Total field copy: every [AssistantDeltaPayloadDto] decodes to a [LiveSessionEvent.AssistantDelta]. */
internal fun AssistantDeltaPayloadDto.toEvent(): LiveSessionEvent = LiveSessionEvent.AssistantDelta(conversationId, turnId, seq, text)

/** Total field copy: every [ToolUsePayloadDto] decodes to a [LiveSessionEvent.ToolUse]. */
internal fun ToolUsePayloadDto.toEvent(): LiveSessionEvent =
    LiveSessionEvent.ToolUse(conversationId, turnId, toolUseId, name, inputSummary, parentToolUseId, input.toInputFields())

/** Total field copy: every [ToolResultPayloadDto] decodes to a [LiveSessionEvent.ToolResult]. */
internal fun ToolResultPayloadDto.toEvent(): LiveSessionEvent =
    LiveSessionEvent.ToolResult(conversationId, turnId, toolUseId, isError, resultSummary, parentToolUseId)

/**
 * `tool_use.input` narrowed to its string fields (#810): anything but a JSON object yields no fields,
 * and within an object each JSON-string value is kept **verbatim** in wire order. A non-string value is
 * off-contract (the daemon stringifies every value) and is skipped rather than rewritten.
 */
private fun JsonElement?.toInputFields(): Map<String, String> {
    if (this !is JsonObject) return emptyMap()
    return buildMap {
        for ((key, value) in this@toInputFields) {
            if (value is JsonPrimitive && value.isString) put(key, value.content)
        }
    }
}

/** Total field copy: every string passes through verbatim (consumers map and sanitize the wire values). */
internal fun TurnEndPayloadDto.toEvent(): LiveSessionEvent =
    LiveSessionEvent.TurnEnd(conversationId, turnId, stopReason, outcome, isError, terminalReason, errorCategory)

/**
 * The two v2 **modal** lifecycle payloads (#437, pyrycode#701/#703): `modal_shown` (a surfaced
 * permission/choice modal) and `modal_dismissed` (its resolution), mapped to the portable [ModalEvent]
 * family. Like the five live-session DTOs above, every field is **always present** (no `omitempty`,
 * pyrycode#701 § Design 2), so each is a required non-null `String`/`List` — the fail-closed posture for
 * an untrusted boundary: a missing/wrong-typed field throws a [kotlinx.serialization.SerializationException]
 * and the one malformed envelope is dropped, keeping the stream alive (AC #4).
 *
 * `modal_id` is the sole correlation key for answers. `modal_shown`'s `conversation_id` (daemon #1065) is
 * an outbound-only scoping stamp that picks the thread that displays the modal (#816). It is the one
 * defaulted field: a frame without it still decodes, as the unscoped `""` that no thread displays,
 * rather than dropping the prompt path. `modal_dismissed` carries no conversation. `class`,
 * `source`, and `outcome` are **plain `String`s carried verbatim** (not Kotlin enums): AC #3 requires an
 * unknown/forward-compat value to survive rather than be coerced or dropped, so the mappers are **total**
 * (never `null`) — the only decode-failure path is a structurally malformed envelope. `class` is a Kotlin
 * keyword, so the DTO property is [ModalShownPayloadDto.modalClass] with `@SerialName("class")`.
 * `default_option_id` MUST equal one of `options[].id` by the producer's invariant; this seam carries it
 * verbatim and does **not** enforce it (a decode asserting it would couple decode to producer correctness
 * and could drop a forward-compat modal).
 *
 * The four permission-context fields (#817, daemon #2346) are the exception to the strict posture: they
 * are optional, omitted at their zero values, and display-only, so each decodes as a defaulted
 * [JsonElement] and a wrong-typed one never drops the prompt it decorates. See [toModalContext].
 */
@Serializable
internal data class ModalOptionDto(
    val id: String,
    val label: String,
)

@Serializable
internal data class ModalShownPayloadDto(
    @SerialName("modal_id") val modalId: String,
    @SerialName("class") val modalClass: String,
    val title: String,
    val prompt: String,
    val options: List<ModalOptionDto>,
    @SerialName("default_option_id") val defaultOptionId: String,
    @SerialName("conversation_id") val conversationId: String = "",
    // Non-null so an explicit JSON `null` decodes as JsonNull (display text) rather than as absent; an absent
    // key takes the empty string, which the wire already defines as absent.
    val reason: JsonElement = JsonPrimitive(""),
    @SerialName("reason_type") val reasonType: JsonElement? = null,
    @SerialName("blocked_path") val blockedPath: JsonElement? = null,
    val description: JsonElement? = null,
    // #818: always present on the wire, but tolerant here like the context fields — a missing or malformed
    // offer is no offer, never a dropped prompt. See [toAlwaysAllowRules].
    @SerialName("always_allow") val alwaysAllow: JsonElement? = null,
)

@Serializable
internal data class ModalDismissedPayloadDto(
    @SerialName("modal_id") val modalId: String,
    val outcome: String,
    val source: String,
)

/** Total field copy. `options.map` preserves wire array order — the canonical display order (AC #1/#5). */
internal fun ModalShownPayloadDto.toEvent(): ModalEvent =
    ModalEvent.Shown(
        modalId = modalId,
        modalClass = modalClass,
        title = title,
        prompt = prompt,
        options = options.map { ModalOption(it.id, it.label) },
        defaultOptionId = defaultOptionId,
        conversationId = conversationId,
        context = toModalContext(),
        alwaysAllowRules = alwaysAllow.toAlwaysAllowRules(),
    )

/**
 * The `always_allow` offer's rules (#818, daemon #2364), or the empty list when no offer is available. The
 * rules are kept only when `offered` is the JSON boolean `true` and `rules` is an array of 1 to
 * [MAX_ALWAYS_ALLOW_RULES] non-empty JSON strings of at most [MAX_ALWAYS_ALLOW_RULE_BYTES] UTF-8 bytes each,
 * the daemon's own bounds. Any violation rejects the whole list: like the daemon, the phone never keeps a
 * prefix of a rejected batch. The rules are claude-authored display text, carried verbatim.
 */
private fun JsonElement?.toAlwaysAllowRules(): List<String> {
    val offer = this as? JsonObject ?: return emptyList()
    val offered = offer["offered"] as? JsonPrimitive
    if (offered == null || offered.isString || offered.content != "true") return emptyList()
    val rules = offer["rules"] as? JsonArray ?: return emptyList()
    if (rules.size !in 1..MAX_ALWAYS_ALLOW_RULES) return emptyList()
    return rules.map { rule ->
        val text = (rule as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (text.isNullOrEmpty() || text.encodeToByteArray().size > MAX_ALWAYS_ALLOW_RULE_BYTES) return emptyList()
        text
    }
}

private const val MAX_ALWAYS_ALLOW_RULES = 16
private const val MAX_ALWAYS_ALLOW_RULE_BYTES = 1024

/**
 * The four optional context fields as display text (#817). `reason` has no guaranteed JSON shape: a string
 * is its content and any other value is its compact JSON text, so `false`, `0` and `null` stay visible (the
 * desktop's `JSON.stringify`). The three string fields keep only a JSON string. Empty means absent, per the
 * wire. Each value is then clamped, since the contract states no bound: [MAX_MODAL_REASON_TYPE_CHARS] for
 * the category token, [MAX_MODAL_CONTEXT_CHARS] for the prose. Nothing else is trimmed or parsed.
 */
private fun ModalShownPayloadDto.toModalContext(): ModalContext =
    ModalContext(
        reason = reason.let { if (it is JsonPrimitive && it.isString) it.content else it.toString() }.clamped(MAX_MODAL_CONTEXT_CHARS),
        reasonType = reasonType.stringContent().clamped(MAX_MODAL_REASON_TYPE_CHARS),
        blockedPath = blockedPath.stringContent().clamped(MAX_MODAL_CONTEXT_CHARS),
        description = description.stringContent().clamped(MAX_MODAL_CONTEXT_CHARS),
    )

private fun JsonElement?.stringContent(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Empty → `null`; otherwise at most [max] chars, never ending on a lone high surrogate. */
private fun String?.clamped(max: Int): String? {
    if (isNullOrEmpty()) return null
    val cut = take(max)
    return if (cut.length < length && cut.last().isHighSurrogate()) cut.dropLast(1) else cut
}

private const val MAX_MODAL_CONTEXT_CHARS = 2048
private const val MAX_MODAL_REASON_TYPE_CHARS = 128

/** Total field copy: [outcome] and [source] pass through verbatim (consumers map the wire values). */
internal fun ModalDismissedPayloadDto.toEvent(): ModalEvent = ModalEvent.Dismissed(modalId, outcome, source)

/**
 * The `session_transition` thread event (#336, pyrycode#656/#657/#740): a session boundary
 * `{conversation_id, previous_session_id, new_session_id, reason, occurred_at, workspace_cwd}`, emitted
 * live and capability-gated, folded into the conversation thread as a [ThreadItem.SessionBoundary] at a
 * `/clear` / idle-evict / workspace-change transition. Decode-only — the phone never sends one. Always
 * decode through [MobileJson].
 *
 * Wire SSOT: pyrycode `docs/protocol-mobile.md` § Interactive events (v2); #656/#740. Every field is
 * **required, non-null** except [workspaceCwd] — the one documented nullable (`null` for `clear` /
 * `idle_evict`, non-null only for `workspace_change`). Default it `= null` (the single-field latitude of
 * [QueueStatePayloadDto.queued]) so both a present-`null` and a (future) omitted key map to `null`
 * without throwing ([MobileJson] sets no `coerceInputValues`); every other field stays strict so a
 * missing/wrong-typed field fails the structural decode and the one envelope is dropped (AC #5). [reason]
 * is a plain `String` (not an enum): an unrecognized value is a *mapper* drop (see [toBoundary]), not a
 * decode failure — the [TurnStatePayloadDto.state] posture.
 *
 * Unlike the five live-session DTOs this is **not** a [LiveSessionEvent] — it produces a [ThreadItem], so
 * it has no `toEvent()` and never lands on the live-event stream (boundaries are thread rows, not
 * streaming events; the thinking-indicator / tool-timeline consumers must not see them).
 */
@Serializable
internal data class SessionTransitionPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("previous_session_id") val previousSessionId: String,
    @SerialName("new_session_id") val newSessionId: String,
    val reason: String,
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("workspace_cwd") val workspaceCwd: String? = null,
)

/**
 * Map a decoded [SessionTransitionPayloadDto] to a [ThreadItem.SessionBoundary], or **null** when
 * [reason] is not one of `clear` / `idle_evict` / `workspace_change` (AC #3 — the unknown-value drop is a
 * mapper concern, like [TurnStatePayloadDto.toEvent], distinct from a malformed envelope). [occurredAt]
 * parses via [Instant.parse], which throws [IllegalArgumentException] on a malformed timestamp — caught
 * at the decode boundary and dropped (AC #5). [workspaceCwd] passes through **verbatim**: the
 * workspaceCwd-non-null-iff-`WorkspaceChange` invariant is a wire guarantee asserted in tests, not
 * enforced here (the [ThreadItem.SessionBoundary] KDoc: "not enforced at construction"). On `idle_evict`
 * the wire carries the evicted id in both [previousSessionId] and [newSessionId]; both copy verbatim,
 * no special-casing.
 */
internal fun SessionTransitionPayloadDto.toBoundary(): ThreadItem.SessionBoundary? =
    reason.toBoundaryReason()?.let { boundaryReason ->
        ThreadItem.SessionBoundary(
            previousSessionId = previousSessionId,
            newSessionId = newSessionId,
            reason = boundaryReason,
            occurredAt = Instant.parse(occurredAt),
            workspaceCwd = workspaceCwd,
        )
    }

private fun String.toBoundaryReason(): BoundaryReason? =
    when (this) {
        "clear" -> BoundaryReason.Clear
        "idle_evict" -> BoundaryReason.IdleEvict
        "workspace_change" -> BoundaryReason.WorkspaceChange
        else -> null
    }

/**
 * The `unrecognized_message` thread event (#609, pyrycode#1074): a claude message the interactive
 * daemon's stream-json parser could not map `{conversation_id, site, message_type, raw, truncated}`,
 * folded into the conversation thread as a [ThreadItem.UnrecognizedMessage]. Unlike its `stall` /
 * `api_retry` / `compacting` neighbours this reports a gap in **our own** mapping, not what claude is
 * doing. Decode-only — the phone never sends one. Always decode through [MobileJson].
 *
 * Wire SSOT: pyrycode `internal/protocol/interactive.go` (`UnrecognizedMessagePayload`) +
 * `docs/protocol-mobile.md` § `unrecognized_message`. All five fields are **strict-required, non-null**
 * with no Kotlin default (the [CompactingPayloadDto] posture): the Go struct sets no `omitempty`, so
 * `message_type: ""` arrives **present-and-empty** and `truncated: false` arrives **present** — both
 * decode cleanly under a strict DTO, which is what lets every field stay required. A missing field, or
 * one whose JSON shape cannot be read as its declared type (an object or array where a `String` is
 * declared), fails the structural decode with a [kotlinx.serialization.SerializationException] and the
 * one envelope is dropped (AC #4). The measured latitude documented on [ApiRetryPayloadDto] applies
 * here too: kotlinx's *tree* decoder accepts a **quoted** primitive (`"truncated":"true"`) even with
 * `isLenient = false`, so that is not a strictness probe a test should lean on.
 *
 * [site] is a plain `String` (not an enum): an unrecognized value is a *mapper* drop (see [toRow]), not
 * a decode failure — the [SessionTransitionPayloadDto.reason] / [TurnStatePayloadDto.state] posture.
 *
 * [raw] is the most untrusted string this seam carries — unbounded, model-adjacent JSON. It crosses
 * **verbatim**, never trimmed, parsed, reformatted, or logged; the render layer owns its posture
 * (#608's `UnrecognizedMessageRow`). No client-side length cap: the daemon truncates at construction to
 * 16 KiB and `OkHttpRelayTransport`'s 65519-byte frame contract bounds it again (~4x headroom), so a
 * third bound would defend a failure that cannot reach this code.
 *
 * Like [SessionTransitionPayloadDto] and unlike the five live-session DTOs this produces a [ThreadItem],
 * so it has no `toEvent()` and never lands on the live-event stream. It carries **no `turn_id`** and
 * drives no turn lifecycle: the daemon could not read the message well enough to attribute a turn to it,
 * and opening one would wedge the conversation because no turn end follows a message nobody could parse.
 */
@Serializable
internal data class UnrecognizedMessagePayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    val site: String,
    @SerialName("message_type") val messageType: String,
    val raw: String,
    val truncated: Boolean,
)

/**
 * Map a decoded [UnrecognizedMessagePayloadDto] to a [ThreadItem.UnrecognizedMessage], or **null** when
 * [site] is not one of the four documented values (AC #4 — the unknown-value drop is a mapper concern,
 * like [SessionTransitionPayloadDto.toBoundary], distinct from a malformed envelope). [site] is the one
 * payload field that is **narrowed** rather than copied: only the four client-owned [UnrecognizedSite]
 * constants can reach the UI's exhaustive label lookup, so a hostile daemon cannot inject a fifth label.
 *
 * [id] and [occurredAt] are **client-owned** and injected by the caller — the wire carries neither a row
 * id nor a timestamp (the [MessagePayloadDto.toMessage] idiom of passing non-wire values in). Keeping
 * [occurredAt] a parameter rather than reading the clock here is what keeps this mapper pure and
 * deterministically testable; the caller stamps both (see `decodeUnrecognizedMessage`).
 *
 * Every other field is a **total verbatim copy**. In particular this does **not** cross-validate the
 * empty-[messageType]-iff-`undecodable` invariant: it is daemon-guaranteed, and enforcing it here would
 * defend an unobserved failure and could drop a valid frame. The desktop client made the same call
 * (`parseUnrecognizedMessagePayload`, `inboundMessage.ts`), so the two clients agree deliberately.
 */
internal fun UnrecognizedMessagePayloadDto.toRow(
    id: String,
    occurredAt: Instant,
): ThreadItem.UnrecognizedMessage? =
    site.toUnrecognizedSite()?.let { unrecognizedSite ->
        ThreadItem.UnrecognizedMessage(
            id = id,
            site = unrecognizedSite,
            messageType = messageType,
            raw = raw,
            truncated = truncated,
            occurredAt = occurredAt,
        )
    }

private fun String.toUnrecognizedSite(): UnrecognizedSite? =
    when (this) {
        "line_type" -> UnrecognizedSite.LineType
        "assistant_block" -> UnrecognizedSite.AssistantBlock
        "user_block" -> UnrecognizedSite.UserBlock
        "undecodable" -> UnrecognizedSite.Undecodable
        else -> null
    }
