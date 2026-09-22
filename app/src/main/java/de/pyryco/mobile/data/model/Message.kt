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
 */
enum class ToolCallStatus { Running, Done, Failed }

/**
 * [input] is the server's one-line précis. [inputFields] (#810) is the tool input's own top-level
 * fields, verbatim, and [parentToolUseId] names the `Agent`/`Task` call that spawned this one (`""`
 * is the main thread). Both are inert display and grouping data — never a path to open, a command to
 * run, a URL or a log line. Defaulted, like [status], so existing constructions need no change.
 */
data class ToolCall(
    val toolName: String,
    val input: String,
    val output: String,
    val status: ToolCallStatus = ToolCallStatus.Done,
    val inputFields: Map<String, String> = emptyMap(),
    val parentToolUseId: String = "",
)
