package de.pyryco.mobile.data.model

import kotlinx.datetime.Instant

data class Message(
    val id: String,
    val sessionId: String,
    val role: Role,
    val content: String,
    val timestamp: Instant,
    val isStreaming: Boolean,
    /** Non-null iff [role] is [Role.Tool]. */
    val toolCall: ToolCall? = null,
    /** The files this message references (#983), in send, wire or arrival order; see [MessageAttachment]. */
    val attachments: List<MessageAttachment> = emptyList(),
    /** Non-null exactly on an assistant row folded from `assistant_delta`s (#1350); see [AssistantSegment]. */
    val segment: AssistantSegment? = null,
    /** Assistant parent attribution, verbatim inert grouping data; never authority, a path or a log field.
     * Empty for main/unknown/cache-only rows. Tool attribution stays on [ToolCall.parentToolUseId]. */
    val parentToolUseId: String = "",
    /** Original ordinary-row id when [id] is a local collision alias; never inferred from alias text. */
    val reconciliationId: String? = null,
)

enum class Role { User, Assistant, Tool }

/**
 * One run of a turn's assistant text with no other row inside it (#1350): text, a tool call, more text is
 * two segments. [turnId] is the deltas' `turn_id`, and [deltas] records each folded delta's `seq` and the
 * length of its text in [Message.content], in fold order, so the lengths sum to the content's length.
 *
 * The record is what lets two copies of one segment cut by a page boundary, or met across the page and the
 * live lane, join into one row with each delta's text once. It carries no text, so [toString] is safe.
 */
data class AssistantSegment(
    val turnId: String,
    val deltas: List<SegmentDelta>,
) {
    val firstSeq: Int get() = deltas.firstOrNull()?.seq ?: -1
    val lastSeq: Int get() = deltas.lastOrNull()?.seq ?: -1
}

/** One folded `assistant_delta`: its per-turn [seq] and the [length] of its text (#1350). */
data class SegmentDelta(
    val seq: Int,
    val length: Int,
)

/**
 * One file a thread message references (#983): a file the operator sent with it, one a replayed
 * `send_message` named, or one claude offered. [attachmentId] is the id to fetch the bytes by. [displayName]
 * and [mimeType] are hints, `null` when not known: a history entry names ids only. `""` is a name that was
 * known and empty.
 *
 * **SECURITY.** Both hints are untrusted display text even after cleaning. Render them as inert text only:
 * never as a path or part of one, a cache key or a log field, and never pick a viewer from either. [toString]
 * leaves them out.
 */
data class MessageAttachment(
    val attachmentId: String,
    val displayName: String? = null,
    val mimeType: String? = null,
) {
    override fun toString(): String = "MessageAttachment(attachmentId=$attachmentId)"
}

/**
 * Lifecycle of a tool call surfaced live in the thread (#387). A `tool_use` event opens the row as
 * [Running]; the correlated `tool_result` updates it in place to [Done] (or [Failed] when the result
 * is an error). Defaults to [Done] on [ToolCall] so existing finished-tool constructions (the fake's
 * seeds, the previews) compile and stay semantically correct without a fixture cascade.
 *
 * [Denied] (#811) is set by a `tool_denied` frame: claude refused the call. It wins over whatever the
 * row held and a later `tool_result` does not overwrite it, so a blocked call is never retained as a
 * tool that broke.
 */
enum class ToolCallStatus { Running, Done, Failed, Denied }

/**
 * [input] is the server's one-line précis. [inputFields] (#810) is the tool input's own top-level
 * fields, verbatim, and [parentToolUseId] names the `Agent`/`Task` call that spawned this one (`""`
 * is the main thread). Both are inert display and grouping data — never a path to open, a command to
 * run, a URL or a log line. Defaulted, like [status], so existing constructions need no change.
 * [denial] (#811) is non-null only on a [ToolCallStatus.Denied] row; see [ToolDenial].
 *
 * [elapsedSeconds] (#812) is claude's latest `tool_progress` reading for the call, kept exactly as sent:
 * zero, negative and backwards values are upstream readings, not errors. It is non-null only while the row
 * is [ToolCallStatus.Running]; closing the call clears it and a late reading is ignored. `null` means no
 * reading arrived, which proves nothing — a short call finishes before claude's first heartbeat, and a
 * heartbeat can be lost. Never subtract readings or treat one as timing evidence; displaying it is #658's.
 *
 * [resultDetail] (#1316) is the first `tool_result`'s count of what the call returned ("265 lines"), verbatim,
 * `""` when the result had none. `null` means no result has been folded into this row: a running call, a
 * denial whose result has not arrived, or a row restored from the disk cache, which keeps no count. It is
 * inert display text — never parsed into a number, logged or used as a link.
 */
data class ToolCall(
    val toolName: String,
    val input: String,
    val output: String,
    val status: ToolCallStatus = ToolCallStatus.Done,
    val inputFields: Map<String, String> = emptyMap(),
    val parentToolUseId: String = "",
    val denial: ToolDenial? = null,
    val elapsedSeconds: Int? = null,
    val resultDetail: String? = null,
)

/**
 * claude's account of why it refused a tool call (`tool_denied`, #811), carried on a [ToolCallStatus.Denied]
 * row as [ToolCall.denial]. Every field is a verbatim copy of the frame; a `Denied` row restored from the
 * disk cache carries no denial, because the cache persists only the status.
 *
 * [truncatedFields] and [droppedFields] name this frame's wire keys (`tool_name`, `message`, …) and keep
 * three readings of an empty-or-short value apart: named in neither, claude sent it as it is; empty and
 * named in [droppedFields], the daemon emptied an over-cap value; named in [truncatedFields], the daemon
 * cut claude's text. `null` means nothing was cut or dropped and is not the same as an empty list.
 *
 * [message] and [decisionReason] are claude-authored prose, bounded but not sanitised — they may quote a
 * refused command line and name absolute host paths. Render them only as inert text attributed to claude,
 * never as a URL, a path to open, markup or a log line. [toolName] and [decisionReasonType] are short
 * tokens from open sets: compare them against known values and treat anything else as unknown.
 */
data class ToolDenial(
    val toolName: String,
    val decisionReasonType: String,
    val decisionReason: String,
    val message: String,
    val truncatedFields: List<String>?,
    val droppedFields: List<String>?,
)
