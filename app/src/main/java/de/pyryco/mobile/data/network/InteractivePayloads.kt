package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.ModalEvent
import de.pyryco.mobile.data.model.ModalOption
import de.pyryco.mobile.data.repository.ApiRetryStatus
import de.pyryco.mobile.data.repository.BoundaryReason
import de.pyryco.mobile.data.repository.QueuedMessage
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.UnrecognizedSite
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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

@Serializable
internal data class ToolUsePayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("turn_id") val turnId: String,
    @SerialName("tool_use_id") val toolUseId: String,
    val name: String,
    @SerialName("input_summary") val inputSummary: String,
)

@Serializable
internal data class ToolResultPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("turn_id") val turnId: String,
    @SerialName("tool_use_id") val toolUseId: String,
    @SerialName("is_error") val isError: Boolean,
    @SerialName("result_summary") val resultSummary: String,
)

@Serializable
internal data class TurnEndPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("turn_id") val turnId: String,
    @SerialName("stop_reason") val stopReason: String,
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
internal fun ToolUsePayloadDto.toEvent(): LiveSessionEvent = LiveSessionEvent.ToolUse(conversationId, turnId, toolUseId, name, inputSummary)

/** Total field copy: every [ToolResultPayloadDto] decodes to a [LiveSessionEvent.ToolResult]. */
internal fun ToolResultPayloadDto.toEvent(): LiveSessionEvent =
    LiveSessionEvent.ToolResult(conversationId, turnId, toolUseId, isError, resultSummary)

/** Total field copy: [stopReason] passes through verbatim (consumers map the wire value). */
internal fun TurnEndPayloadDto.toEvent(): LiveSessionEvent = LiveSessionEvent.TurnEnd(conversationId, turnId, stopReason)

/**
 * The two v2 **modal** lifecycle payloads (#437, pyrycode#701/#703): `modal_shown` (a surfaced
 * permission/choice modal) and `modal_dismissed` (its resolution), mapped to the portable [ModalEvent]
 * family. Like the five live-session DTOs above, every field is **always present** (no `omitempty`,
 * pyrycode#701 § Design 2), so each is a required non-null `String`/`List` — the fail-closed posture for
 * an untrusted boundary: a missing/wrong-typed field throws a [kotlinx.serialization.SerializationException]
 * and the one malformed envelope is dropped, keeping the stream alive (AC #4).
 *
 * Modal payloads carry **no `conversation_id`** — `modal_id` is the sole correlation key. `class`,
 * `source`, and `outcome` are **plain `String`s carried verbatim** (not Kotlin enums): AC #3 requires an
 * unknown/forward-compat value to survive rather than be coerced or dropped, so the mappers are **total**
 * (never `null`) — the only decode-failure path is a structurally malformed envelope. `class` is a Kotlin
 * keyword, so the DTO property is [ModalShownPayloadDto.modalClass] with `@SerialName("class")`.
 * `default_option_id` MUST equal one of `options[].id` by the producer's invariant; this seam carries it
 * verbatim and does **not** enforce it (a decode asserting it would couple decode to producer correctness
 * and could drop a forward-compat modal).
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
    )

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
