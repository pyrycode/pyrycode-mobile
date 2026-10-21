package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.ToolDenial
import de.pyryco.mobile.data.repository.BannerLevel
import de.pyryco.mobile.data.repository.BoundaryReason
import de.pyryco.mobile.data.repository.QueuedMessage
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.UnrecognizedSite
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Independent daemon-producer observations used only by retained native evidence. */
@Serializable
internal data class AssistantDeltaPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("turn_id") val turnId: String,
    val seq: Int,
    val text: String,
    // Older daemons omit the hint; explicit null still fails the non-null string decode.
    @SerialName("parent_tool_use_id") val parentToolUseId: String = "",
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

/**
 * `tool_result`. [parentToolUseId] is lenient-defaulted for the reason [ToolUsePayloadDto] states (#810).
 * [resultDetail] (#1316, pyrycode#2024) is the daemon's count of what the call returned, such as
 * `"265 lines"`; `""` means no count, and a daemon predating the field omits it, which decodes the same.
 */
@Serializable
internal data class ToolResultPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("turn_id") val turnId: String,
    @SerialName("tool_use_id") val toolUseId: String,
    @SerialName("is_error") val isError: Boolean,
    @SerialName("result_summary") val resultSummary: String,
    @SerialName("parent_tool_use_id") val parentToolUseId: String = "",
    @SerialName("result_detail") val resultDetail: String = "",
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
 * `turn_end`. The four trailing fields (#805) are claude's own stop shape and are **optional and open-set**
 * on the wire: an absent one — an older daemon — decodes to its empty value, the lenient-default posture
 * [ToolUsePayloadDto.parentToolUseId] set, and an unrecognised token is just a string. Of the eight numeric
 * fields on the same frame only `cost_usd_total` is decoded (#1346).
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
    // #1346: an element, not a `Double?`, so a non-number is absent instead of failing the whole frame.
    @SerialName("cost_usd_total") val costUsdTotal: JsonElement? = null,
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

@Serializable
internal data class MessagePayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("message_id") val messageId: String,
    val role: WireRole,
    val text: String,
    @SerialName("attachment_ids") val attachmentIds: List<String>? = null,
)

@Serializable
internal enum class WireRole {
    @SerialName("user")
    User,

    @SerialName("assistant")
    Assistant,
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
 * [site] is not one of the six documented values (AC #4 — the unknown-value drop is a mapper concern,
 * like [SessionTransitionPayloadDto.toBoundary], distinct from a malformed envelope). [site] is the one
 * payload field that is **narrowed** rather than copied: only the six client-owned [UnrecognizedSite]
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

/**
 * The `banner` thread event (#873, pyrycode#2256): text claude printed about the session
 * `{conversation_id, level, text, truncated, stops_turn}`, folded into the conversation thread as a
 * [ThreadItem.Banner]. Decode-only — the phone never sends one. Always decode through [MobileJson].
 *
 * Wire SSOT: pyrycode `internal/protocol/interactive.go` (`BannerPayload`) + `docs/protocol-mobile.md`
 * § `banner`. All five fields are **strict-required** with no Kotlin default (the
 * [UnrecognizedMessagePayloadDto] posture): the Go struct sets no `omitempty`, so an empty `level` and a
 * `false` bool arrive present. A missing or wrong-typed field fails the decode and drops the one frame.
 *
 * [stopsTurn] is decoded so its shape is checked, and then deliberately dropped by [toRow]: it is a
 * report, and the protocol forbids letting it end, cancel or change a turn.
 */
@Serializable
internal data class BannerPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    val level: String,
    val text: String,
    val truncated: Boolean,
    @SerialName("stops_turn") val stopsTurn: Boolean,
)

/**
 * Map a decoded [BannerPayloadDto] to a [ThreadItem.Banner]. **Total** — `info` is a [BannerLevel.Info]
 * (#1359), and an unrecognised `level` is not a drop but a [BannerLevel.Notice], the protocol's "treat it as
 * unknown" rule. [text] is copied verbatim;
 * stripping is the renderer's. [occurredAt] is the caller's: the envelope `ts` on the live lane, the
 * entry timestamp on replay, which is what lets the two lanes join on one identity.
 */
internal fun BannerPayloadDto.toRow(occurredAt: Instant): ThreadItem.Banner =
    ThreadItem.Banner(
        level =
            when (level) {
                "warning" -> BannerLevel.Warning
                "info" -> BannerLevel.Info
                else -> BannerLevel.Notice
            },
        text = text,
        truncated = truncated,
        occurredAt = occurredAt,
    )

/**
 * The `model_refusal_fallback` thread event (#875, pyrycode#2265): claude refused a turn on one model and
 * retried it on another, folded into the conversation thread as a [ThreadItem.ModelRefusal]. Decode-only —
 * the phone never sends one. Always decode through [MobileJson].
 *
 * Wire SSOT: pyrycode `internal/protocol/interactive.go` (`ModelRefusalFallbackPayload`) +
 * `docs/protocol-mobile.md` § `model_refusal_fallback`. The six strings are strict-required with no Kotlin
 * default: the Go struct sets no `omitempty`, so each always arrives present. The two report arrays are
 * nullable because `null` is their normal wire value. [scope] and [refusalCategory] are decoded so their
 * shape is checked and then dropped by [toRow]: they are claude's open assertions and drive nothing.
 */
@Serializable
internal data class ModelRefusalFallbackPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("original_model") val originalModel: String,
    @SerialName("fallback_model") val fallbackModel: String,
    val scope: String,
    @SerialName("refusal_category") val refusalCategory: String,
    val banner: String,
    @SerialName("truncated_fields") val truncatedFields: List<String>?,
    @SerialName("dropped_fields") val droppedFields: List<String>?,
)

/**
 * The `model_refusal_no_fallback` thread event (#875, pyrycode#2266): the no-retry sibling of
 * [ModelRefusalFallbackPayloadDto], told apart by envelope type, with no `fallback_model` or `scope`. The
 * same strictness and the same inert [refusalCategory].
 */
@Serializable
internal data class ModelRefusalNoFallbackPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("original_model") val originalModel: String,
    @SerialName("refusal_category") val refusalCategory: String,
    val banner: String,
    @SerialName("truncated_fields") val truncatedFields: List<String>?,
    @SerialName("dropped_fields") val droppedFields: List<String>?,
)

/**
 * Map a decoded [ModelRefusalFallbackPayloadDto] to a [ThreadItem.ModelRefusal]. **Total.** Strings are
 * copied verbatim; stripping is the renderer's. [occurredAt] is the caller's: the envelope `ts` live, the
 * entry timestamp on replay, which is what lets the two lanes join on one identity.
 */
internal fun ModelRefusalFallbackPayloadDto.toRow(occurredAt: Instant): ThreadItem.ModelRefusal =
    ThreadItem.ModelRefusal(
        originalModel = originalModel,
        fallbackModel = fallbackModel,
        banner = banner,
        bannerTruncated = bannerWasCut(truncatedFields),
        occurredAt = occurredAt,
    )

internal fun ModelRefusalNoFallbackPayloadDto.toRow(occurredAt: Instant): ThreadItem.ModelRefusal =
    ThreadItem.ModelRefusal(
        originalModel = originalModel,
        fallbackModel = null,
        banner = banner,
        bannerTruncated = bannerWasCut(truncatedFields),
        occurredAt = occurredAt,
    )

private fun bannerWasCut(truncatedFields: List<String>?): Boolean = truncatedFields.orEmpty().contains("banner")

/**
 * The `compaction_boundary` thread event (#874, pyrycode#2237): a finished compaction
 * `{conversation_id, trigger, pre_tokens, post_tokens}`, folded into the conversation thread as a
 * [ThreadItem.CompactionBoundary]. Decode-only — the phone never sends one. Always decode through [MobileJson].
 *
 * Wire SSOT: pyrycode `internal/protocol/interactive.go` (`CompactionBoundaryPayload`) +
 * `docs/protocol-mobile.md` § `compaction_boundary`. [conversationId] and [trigger] are strict-required.
 * The two counts default to null, so a missing key reads as "no count stated" and the divider still draws
 * without a size. A count that is not a JSON integer fitting a [Long] fails the decode and drops the one
 * frame — the daemon's own posture for a count it cannot read as an integer is no frame at all.
 */
@Serializable
internal data class CompactionBoundaryPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    val trigger: String,
    @SerialName("pre_tokens") val preTokens: Long? = null,
    @SerialName("post_tokens") val postTokens: Long? = null,
)

/**
 * Map a decoded [CompactionBoundaryPayloadDto] to a [ThreadItem.CompactionBoundary]. **Total** — an
 * unrecognised `trigger` is not a drop but "not manual", and an invalid count is not a drop but no count.
 * Only validated numbers and the recognised `manual` token cross into the domain, so no claude-authored
 * string can reach the label. [occurredAt] is the caller's: the envelope `ts` live, the entry timestamp on
 * replay, which is what lets the two lanes join on one identity.
 */
internal fun CompactionBoundaryPayloadDto.toRow(occurredAt: Instant): ThreadItem.CompactionBoundary =
    ThreadItem.CompactionBoundary(
        preTokens = validTokenCount(preTokens),
        postTokens = validTokenCount(postTokens),
        manual = trigger == "manual",
        occurredAt = occurredAt,
    )

private const val MAX_SAFE_TOKEN_COUNT = 9_007_199_254_740_991L

private fun validTokenCount(value: Long?): Long? = value?.takeIf { it in 0..MAX_SAFE_TOKEN_COUNT }

private fun String.toUnrecognizedSite(): UnrecognizedSite? =
    when (this) {
        "line_type" -> UnrecognizedSite.LineType
        "assistant_block" -> UnrecognizedSite.AssistantBlock
        "user_block" -> UnrecognizedSite.UserBlock
        "undecodable" -> UnrecognizedSite.Undecodable
        "codex_method" -> UnrecognizedSite.CodexMethod
        "codex_item" -> UnrecognizedSite.CodexItem
        else -> null
    }
