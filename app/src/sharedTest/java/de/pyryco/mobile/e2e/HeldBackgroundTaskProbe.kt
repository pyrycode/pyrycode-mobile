package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.BackgroundTaskRosterPayloadDto
import de.pyryco.mobile.data.network.BackgroundTaskStartedPayloadDto
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.ToolUsePayloadDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Joins the exact launching call to the latest retained roster, independent of arrival order/prose. */
internal class HeldBackgroundTaskProbe(
    private val conversationId: String,
    private val command: String,
) {
    fun taskId(frames: List<Envelope>): String? {
        val own = frames.filter { field(it, "conversation_id") == conversationId }
        val tools =
            own
                .filter { it.type == "tool_use" }
                .mapNotNull {
                    runCatching { MobileJson.decodeFromJsonElement(ToolUsePayloadDto.serializer(), it.payload) }.getOrNull()
                }.filter {
                    val input = it.input as? JsonObject
                    it.name == "Bash" &&
                        it.toolUseId.isNotEmpty() &&
                        (input?.get("command") as? JsonPrimitive)?.contentOrNull == command &&
                        (input?.get("run_in_background") as? JsonPrimitive)?.contentOrNull == "true"
                }.mapTo(HashSet()) { it.toolUseId }
        val starts =
            own
                .filter { it.type == "background_task_started" }
                .mapNotNull {
                    runCatching { MobileJson.decodeFromJsonElement(BackgroundTaskStartedPayloadDto.serializer(), it.payload) }.getOrNull()
                }.filter { it.taskId.isNotEmpty() && it.toolCallId in tools && validKeys(it.truncatedFields) }
        val roster =
            own.lastOrNull { it.type == "background_task_roster" }?.let {
                runCatching { MobileJson.decodeFromJsonElement(BackgroundTaskRosterPayloadDto.serializer(), it.payload) }.getOrNull()
            } ?: return null
        return roster.tasks
            .singleOrNull { row ->
                row.taskId.isNotEmpty() &&
                    row.taskType == "local_bash" &&
                    validKeys(row.truncatedFields) &&
                    (row.toolCallId in tools || starts.any { it.taskId == row.taskId })
            }?.taskId
    }

    fun completed(
        frame: Envelope,
        taskId: String,
    ): Boolean {
        if (field(frame, "conversation_id") != conversationId) return false
        if (frame.type == "background_task_updated") {
            return field(frame, "task_id") == taskId && field(frame, "status") == "stopped"
        }
        if (frame.type != "background_task_roster") return false
        val roster =
            runCatching { MobileJson.decodeFromJsonElement(BackgroundTaskRosterPayloadDto.serializer(), frame.payload) }.getOrNull()
                ?: return false
        // A capped roster cannot prove an omitted id is absent rather than merely hidden.
        return roster.droppedTasks == 0 && roster.tasks.none { it.taskId == taskId }
    }

    private fun validKeys(cut: List<String>?): Boolean = cut?.any { it == "task_id" || it == "tool_call_id" } != true

    private fun field(
        frame: Envelope,
        key: String,
    ): String? = ((frame.payload as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull
}
