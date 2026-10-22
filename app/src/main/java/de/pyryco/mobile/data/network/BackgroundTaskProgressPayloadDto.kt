package de.pyryco.mobile.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
