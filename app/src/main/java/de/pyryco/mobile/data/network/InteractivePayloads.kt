package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.ModalContext
import de.pyryco.mobile.data.model.ModalEvent
import de.pyryco.mobile.data.model.ModalOption
import de.pyryco.mobile.data.repository.AnnouncedModel
import de.pyryco.mobile.data.repository.ApiRetryStatus
import de.pyryco.mobile.data.repository.ResetStatus
import de.pyryco.mobile.data.repository.SessionFacts
import de.pyryco.mobile.data.repository.UsageLimitReading
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
 * Decode-only session failure. All three wire fields must be JSON strings; primitives keep numeric
 * and boolean values distinguishable from strings instead of allowing decoder coercion. The mapper
 * discards daemon prose immediately. Wire SSOT: pyrycode `SessionErrorPayload` and protocol Error codes.
 */
@Serializable
internal class SessionErrorPayloadDto(
    @SerialName("conversation_id") val conversationId: JsonPrimitive,
    val code: JsonPrimitive,
    val message: JsonPrimitive,
)

/** Validates the complete wire shape and retains only the routing id and open-vocabulary code. */
internal fun SessionErrorPayloadDto.toSessionError(): Pair<String, String>? {
    if (!conversationId.isString || !code.isString || !message.isString) return null
    return conversationId.content to code.content
}

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
 * `docs/protocol-mobile.md` § `compacting`. Shape: `{conversation_id, active}` plus the two optional
 * outcome fields below. The two edge fields are **strict-required, non-null** (the Go struct sets no
 * `omitempty`, so `active: false` always serializes). A missing edge field, or one whose JSON shape cannot be read as its declared type (a number
 * where a `String` is declared, an object or array where a `Boolean` is), fails the structural decode
 * with a [kotlinx.serialization.SerializationException] and the one envelope is dropped (AC #5). The
 * measured latitude documented on [ApiRetryPayloadDto] applies here too: kotlinx's *tree* decoder
 * accepts a **quoted** primitive (`"active":"true"`) even with `isLenient = false`, so that is not a
 * strictness probe a test should lean on.
 *
 * [active] is the edge — `true` on onset, `false` once compaction finished. Unlike [StallPayloadDto]
 * this carries a **real falling edge**, so the state must never stick after it (the sibling `stall`
 * infers recovery from forward progress instead). The upstream detector streams no compaction progress,
 * so there is deliberately no counter, percent, or ETA field.
 *
 * No `toX()` mapper, following [StallPayloadDto]'s precedent and deliberately unlike
 * [ApiRetryPayloadDto.toStatus]: that one exists to collapse four wire fields into a counter-carrying
 * domain type, whereas these two wire fields already *are* the domain shape (a `String` routing key
 * plus a `Boolean`), so a mapper would be a ceremonial identity function. This is also *state*, not
 * one of the five [LiveSessionEvent] streaming events, so it never lands on the live event stream.
 *
 * [compactResult] and [compactError] (#1358, pyrycode#2236) are the falling edge's outcome, optional on
 * the wire, so an absent key decodes to `""`. Both are claude-authored: [failed] reduces them to one
 * boolean here, and neither string is held past this DTO, displayed or logged.
 */
@Serializable
internal data class CompactingPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    val active: Boolean,
    @SerialName("compact_result") val compactResult: String = "",
    @SerialName("compact_error") val compactError: String = "",
)

/**
 * Whether the compaction this frame closes failed (#1358), desktop's rule: `compact_result` is exactly
 * `failed`, or `compact_error` is non-empty. `compact_result` is an open set, so any other token is not a
 * failure. Read only on a falling edge; the rising edge carries both fields empty.
 */
internal fun CompactingPayloadDto.failed(): Boolean = compactResult == "failed" || compactError.isNotEmpty()

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
 * The `model_announced` control event (#890, pyrycode#1616/#1638): the model claude named for a turn on its
 * `system/init` line. Decode-only — the phone never sends one. Always decode through [MobileJson].
 *
 * Wire SSOT: pyrycode `docs/protocol-mobile.md` § `model_announced`, cited here, not restated. Shape:
 * `{conversation_id, model, truncated}` — all three **strict-required, non-null** with no Kotlin default (the
 * [CompactingPayloadDto] posture), so a missing field or one whose JSON shape cannot be read as its declared
 * type fails the structural decode and the one envelope is dropped. The quoted-primitive latitude documented
 * on [ApiRetryPayloadDto] applies here too.
 *
 * **Not** the per-session override that `session_settings.model` carries; see [toReading].
 *
 * **SECURITY.** [model] is claude-authored and crossed the subprocess trust boundary; the daemon bounds it at
 * construction and does **not** sanitize it. It crosses this boundary **verbatim**. No client-side length cap
 * is added — the daemon's bound and `OkHttpRelayTransport`'s frame contract already cover it, and [truncated]
 * is how a consumer learns it lost characters. Nothing on this path is logged.
 *
 * This is **state**, not a [LiveSessionEvent]: it opens, closes and alters no turn.
 */
@Serializable
internal data class ModelAnnouncedPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    val model: String,
    val truncated: Boolean,
)

/**
 * Map a decoded [ModelAnnouncedPayloadDto] to an [AnnouncedModel], or **null** when [ModelAnnouncedPayloadDto.model]
 * is empty — the contract says it never is, so an empty one is a malformed frame, dropped like a missing field.
 * Otherwise a total verbatim copy: nothing is trimmed, normalised or looked up, and `conversation_id` stays the
 * caller's routing key.
 */
internal fun ModelAnnouncedPayloadDto.toReading(): AnnouncedModel? = if (model.isEmpty()) null else AnnouncedModel(model, truncated)

/**
 * The `session_facts` control event (#890, pyrycode#2253/#2254): claude's own build and the permission posture
 * it says it runs under, from the same `system/init` line as [ModelAnnouncedPayloadDto]. Decode-only. Always
 * decode through [MobileJson].
 *
 * Wire SSOT: pyrycode `docs/protocol-mobile.md` § `session_facts`. Shape: `{conversation_id,
 * claude_code_version, permission_mode, truncated_fields}`. The three strings are **strict-required,
 * non-null**, and an empty one is valid ("not reported"). [truncatedFields] is the documented nullable: the
 * daemon always writes the key, as a literal `null` when nothing was cut, and the Kotlin default only covers
 * a non-conforming producer — the [RateLimitedPayloadDto] posture, where [MobileJson]'s `explicitNulls = false`
 * collapses an omitted key into `null`. A value that is not an array of strings fails the decode.
 *
 * **SECURITY.** [claudeCodeVersion] and [permissionMode] are claude-authored and crossed the subprocess trust
 * boundary, bounded by the daemon and not sanitized. They cross **verbatim**: the version is never parsed and
 * the posture is an **open set** that nothing allow-lists, because an allow-list would drop the first report
 * of a posture nobody has heard of. [permissionMode] is a claim, never the confirmed permission reading.
 * Nothing on this path is logged.
 *
 * Like [ModelAnnouncedPayloadDto] it is state and opens, closes and alters no turn.
 */
@Serializable
internal data class SessionFactsPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("claude_code_version") val claudeCodeVersion: String,
    @SerialName("permission_mode") val permissionMode: String,
    @SerialName("truncated_fields") val truncatedFields: List<String>? = null,
)

/** Map a decoded [SessionFactsPayloadDto] to [SessionFacts]: a total verbatim copy without the routing key. */
internal fun SessionFactsPayloadDto.toFacts(): SessionFacts = SessionFacts(claudeCodeVersion, permissionMode, truncatedFields)

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
