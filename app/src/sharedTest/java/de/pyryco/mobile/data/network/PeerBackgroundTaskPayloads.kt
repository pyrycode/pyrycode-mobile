package de.pyryco.mobile.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Independent producer diagnostics only. These frames never populate phone content. */
@Serializable
internal data class BackgroundTaskStartedPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("task_id") val taskId: String,
    @SerialName("tool_call_id") val toolCallId: String,
    val description: String,
    @SerialName("task_type") val taskType: String,
    @SerialName("truncated_fields") val truncatedFields: List<String>?,
)

@Serializable
internal data class BackgroundTaskUpdatedPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("task_id") val taskId: String,
    val patch: String,
    val status: String,
    val summary: String,
    @SerialName("truncated_fields") val truncatedFields: List<String>?,
)

@Serializable
internal data class BackgroundTaskRowDto(
    @SerialName("task_id") val taskId: String,
    @SerialName("task_type") val taskType: String,
    val description: String,
    @SerialName("truncated_fields") val truncatedFields: List<String>?,
    @SerialName("tool_call_id") val toolCallId: String = "",
)

@Serializable
internal data class BackgroundTaskRosterPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    val tasks: List<BackgroundTaskRowDto>,
    @SerialName("dropped_tasks") val droppedTasks: Int,
)
