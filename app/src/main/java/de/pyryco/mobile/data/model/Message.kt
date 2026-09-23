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
)

enum class Role { User, Assistant, Tool }

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
 */
data class ToolCall(
    val toolName: String,
    val input: String,
    val output: String,
    val status: ToolCallStatus = ToolCallStatus.Done,
    val inputFields: Map<String, String> = emptyMap(),
    val parentToolUseId: String = "",
    val denial: ToolDenial? = null,
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
