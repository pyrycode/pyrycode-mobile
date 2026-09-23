package de.pyryco.mobile.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The three v2 **background-task** payloads (#677): `background_task_started`, `background_task_updated`
 * and `background_task_roster`. Wire SSOT: pyrycode `docs/protocol-mobile.md`, the sections of the same
 * names. The daemon's structs set no `omitempty`, so every key is always present and each is required
 * here, the [QuestionShownPayloadDto] posture: a missing or wrong-typed key fails the whole frame.
 * `truncated_fields` is an explicit `null` when nothing was cut. Unknown keys are tolerated through
 * [MobileJson]. Always decode through [MobileJson].
 *
 * `description`, `patch`, `status` and `summary` are claude-authored and unsanitised, and `description`
 * and `summary` can be literal command lines. They are carried verbatim, and nothing here parses them,
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

/** One roster row; [truncatedFields] is this row's own report. */
@Serializable
internal data class BackgroundTaskRowDto(
    @SerialName("task_id") val taskId: String,
    @SerialName("task_type") val taskType: String,
    val description: String,
    @SerialName("truncated_fields") val truncatedFields: List<String>?,
)

/** A snapshot, not a delta. [tasks] is never `null` on the wire; [droppedTasks] is the only truncation report. */
@Serializable
internal data class BackgroundTaskRosterPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    val tasks: List<BackgroundTaskRowDto>,
    @SerialName("dropped_tasks") val droppedTasks: Int,
)
