package de.pyryco.mobile.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The four v2 **background-task** payloads: `background_task_started`, `background_task_updated`,
 * `background_task_roster` (#677) and `background_task_progress` (#1042). Wire SSOT: pyrycode `docs/protocol-mobile.md`, the sections of the same
 * names. The daemon's structs set no `omitempty`, so every key is always present and each is required
 * here (except the additive roster join), the [QuestionShownPayloadDto] posture: a missing or wrong-typed key fails the whole frame.
 * `truncated_fields` is an explicit `null` when nothing was cut. Unknown keys are tolerated through
 * [MobileJson]. Always decode through [MobileJson].
 *
 * `description`, `patch`, `status`, `summary`, `subagent_type` and `last_tool_name` are claude-authored and
 * unsanitised, `description` and `summary` can be literal command lines, and a progress `description` names
 * host files. They are carried verbatim, and nothing here parses them,
 * logs them or puts them in an exception message.
 */
@Serializable
internal data class BackgroundTaskStartedPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("task_id") val taskId: String,
    @SerialName("tool_call_id") val toolCallId: String,
    val description: String,
    @SerialName("task_type") val taskType: String,
    @SerialName("truncated_fields") val truncatedFields: List<String>?,
)

/** A mid-life frame fills [patch]; a terminal one fills [status] and [summary]. `status != ""` is the finish. */
@Serializable
internal data class BackgroundTaskUpdatedPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("task_id") val taskId: String,
    val patch: String,
    val status: String,
    val summary: String,
    @SerialName("truncated_fields") val truncatedFields: List<String>?,
)

/** One roster row; the additive [toolCallId] is empty when unknown or absent on older daemons. */
@Serializable
internal data class BackgroundTaskRowDto(
    @SerialName("task_id") val taskId: String,
    @SerialName("task_type") val taskType: String,
    val description: String,
    @SerialName("truncated_fields") val truncatedFields: List<String>?,
    @SerialName("tool_call_id") val toolCallId: String = "",
)

/** A snapshot, not a delta. [tasks] is never `null` on the wire; [droppedTasks] is the only truncation report. */
@Serializable
internal data class BackgroundTaskRosterPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    val tasks: List<BackgroundTaskRowDto>,
    @SerialName("dropped_tasks") val droppedTasks: Int,
)

/**
 * What a running task is doing right now (#1042). [description] is the **current activity**, not the task's
 * opening description. The three counters are cumulative per task, taken as sent and not guaranteed
 * monotonic; they decode as [Long] because the daemon's `int` is 64-bit.
 */
@Serializable
internal data class BackgroundTaskProgressPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("task_id") val taskId: String,
    val description: String,
    @SerialName("subagent_type") val subagentType: String,
    @SerialName("last_tool_name") val lastToolName: String,
    @SerialName("total_tokens") val totalTokens: Long,
    @SerialName("tool_uses") val toolUses: Long,
    @SerialName("duration_ms") val durationMs: Long,
    @SerialName("truncated_fields") val truncatedFields: List<String>?,
)
