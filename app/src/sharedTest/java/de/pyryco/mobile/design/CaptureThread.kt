package de.pyryco.mobile.design

import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.repository.AuthoritativeLiveProgress
import de.pyryco.mobile.data.repository.AuthoritativeThreadStore
import de.pyryco.mobile.data.repository.NegotiatedThread
import de.pyryco.mobile.data.repository.QueuedMessage
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.ThreadSupport
import de.pyryco.mobile.ui.conversations.thread.authoritativeItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A capture authors a complete scene; it never installs a runtime message reducer. */
internal class CaptureThread(
    private val base: NegotiatedThread,
    private val extras: StateFlow<List<ThreadItem>> = MutableStateFlow(emptyList()),
    private val showSeed: StateFlow<Boolean> = MutableStateFlow(true),
    private val queue: StateFlow<List<QueuedMessage>> = MutableStateFlow(emptyList()),
    private val taskFacts: StateFlow<List<ThreadItemDto>> = MutableStateFlow(emptyList()),
    private val taskProgress: StateFlow<AuthoritativeLiveProgress> = MutableStateFlow(AuthoritativeLiveProgress()),
) : NegotiatedThread() {
    override val host = base.host
    override val support = MutableStateFlow<ThreadSupport>(ThreadSupport.Supported(this))
    override val store = AuthoritativeThreadStore()

    override fun observe(conversationId: String) =
        combine(base.observe(conversationId), extras, showSeed, queue, taskFacts) { seed, rows, shown, queued, tasks ->
            val ids =
                rows
                    .mapIndexedNotNull { index, row ->
                        (row as? ThreadItem.MessageItem)?.message?.id?.let { it to index + 10_000L }
                    }.toMap()
            val authored =
                (if (shown) seed.items else emptyList()) + tasks + rows.mapIndexed { index, row -> fact(index + 10_000L, row, ids) } +
                    queued.mapIndexed { index, item ->
                        authoritativeItem(
                            index + 20_000L,
                            "user_message",
                            order = null,
                            active = true,
                            status = "queued",
                            content =
                                JsonObject(
                                    mapOf("text" to JsonPrimitive(item.text), "queued_msg_id" to JsonPrimitive(item.id)),
                                ).toString(),
                        )
                    }
            store.reset(host, conversationId, "capture")
            store.establishComplete(
                host,
                conversationId,
                "capture",
                maxOf(30_000, authored.maxOfOrNull { it.rev } ?: 0),
                authored,
                store.current(host, conversationId).generation,
            )
            store.current(host, conversationId)
        }

    override fun progress(
        conversationId: String,
        connection: Any,
    ) = combine(extras, taskProgress) { rows, progress ->
        val messages = rows.filterIsInstance<ThreadItem.MessageItem>().map { it.message }
        AuthoritativeLiveProgress(
            sessionId = messages.firstOrNull { it.toolCall != null }?.sessionId ?: progress.sessionId,
            tools = messages.mapNotNull { message -> message.toolCall?.elapsedSeconds?.let { message.id to it } }.toMap(),
            tasks = progress.tasks,
        )
    }

    override suspend fun open(conversationId: String) = true

    override fun older(conversationId: String) = false

    override fun cancel(conversationId: String) = Unit

    private fun fact(
        id: Long,
        row: ThreadItem,
        ids: Map<String, Long>,
    ): ThreadItemDto {
        var kind = "notice"
        var subtype: String? = null
        val summary = ""
        var active = false
        var status = "done"
        var parent: Long? = null
        var session = "old-session"
        val fields =
            buildMap<String, kotlinx.serialization.json.JsonElement> {
                fun text(
                    key: String,
                    value: String,
                ) {
                    put(key, JsonPrimitive(value))
                }
                when (row) {
                    is ThreadItem.MessageItem -> {
                        val message = row.message
                        val tool = message.toolCall
                        session = message.sessionId
                        parent = ids[tool?.parentToolUseId?.takeIf { it.isNotEmpty() } ?: message.parentToolUseId]
                        kind =
                            when (message.role) {
                                Role.User -> "user_message"
                                Role.Assistant -> "assistant_message"
                                Role.Tool -> if (tool?.toolName in setOf("Agent", "Task")) "agent" else "tool_call"
                            }
                        active = message.isStreaming
                        text("text", message.content)
                        text("message_id", message.id)
                        text("sent_at", message.timestamp.toString())
                        put("attachment_ids", JsonArray(message.attachments.map { JsonPrimitive(it.attachmentId) }))
                        if (tool != null) {
                            active = tool.status == ToolCallStatus.Running
                            status =
                                when (tool.status) {
                                    ToolCallStatus.Running -> "running"
                                    ToolCallStatus.Failed -> "failed"
                                    ToolCallStatus.Denied -> "denied"
                                    ToolCallStatus.Done -> "done"
                                }
                            text("name", tool.toolName)
                            text("tool_use_id", message.id)
                            text("input_summary", tool.input)
                            put("input", JsonObject(tool.inputFields.mapValues { JsonPrimitive(it.value) }))
                            put(
                                "result",
                                JsonObject(
                                    buildMap {
                                        put("result_summary", JsonPrimitive(tool.output))
                                        tool.resultDetail?.let { put("result_detail", JsonPrimitive(it)) }
                                    },
                                ),
                            )
                        }
                    }
                    is ThreadItem.SessionBoundary -> {
                        kind = "session_divider"
                        text("previous_session_id", row.previousSessionId)
                        text("new_session_id", row.newSessionId)
                        text(
                            "reason",
                            when (row.reason) {
                                de.pyryco.mobile.data.repository.BoundaryReason.Clear -> "clear"
                                de.pyryco.mobile.data.repository.BoundaryReason.IdleEvict -> "idle_evict"
                                de.pyryco.mobile.data.repository.BoundaryReason.WorkspaceChange -> "workspace_change"
                            },
                        )
                        text("occurred_at", row.occurredAt.toString())
                        row.workspaceCwd?.let { text("workspace_cwd", it) }
                    }
                    is ThreadItem.Banner -> {
                        subtype = "banner"
                        text("occurred_at", row.occurredAt.toString())
                        text("text", row.text)
                        text("level", row.level.name.lowercase())
                    }
                    is ThreadItem.ModelRefusal -> {
                        subtype = if (row.fallbackModel == null) "model_refusal_no_fallback" else "model_refusal_fallback"
                        text("occurred_at", row.occurredAt.toString())
                        text("banner", row.banner)
                        text("original_model", row.originalModel)
                        row.fallbackModel?.let { text("fallback_model", it) }
                    }
                    is ThreadItem.StoppedTurn -> {
                        kind = "turn_end"
                        text("occurred_at", row.occurredAt.toString())
                        text("terminal_reason", row.reason)
                        text("error_category", row.category)
                    }
                    is ThreadItem.CompactionBoundary -> {
                        kind = "compaction"
                        text("occurred_at", row.occurredAt.toString())
                        text("trigger", if (row.manual) "manual" else "auto")
                    }
                    is ThreadItem.UnrecognizedMessage -> {
                        subtype = "unrecognized_message"
                        text("occurred_at", row.occurredAt.toString())
                        text("raw", row.raw)
                        text("message_type", row.messageType)
                        text(
                            "site",
                            when (row.site) {
                                de.pyryco.mobile.data.repository.UnrecognizedSite.LineType -> "line_type"
                                de.pyryco.mobile.data.repository.UnrecognizedSite.AssistantBlock -> "assistant_block"
                                de.pyryco.mobile.data.repository.UnrecognizedSite.UserBlock -> "user_block"
                                de.pyryco.mobile.data.repository.UnrecognizedSite.Undecodable -> "undecodable"
                                de.pyryco.mobile.data.repository.UnrecognizedSite.CodexMethod -> "codex_method"
                                de.pyryco.mobile.data.repository.UnrecognizedSite.CodexItem -> "codex_item"
                            },
                        )
                    }
                    is ThreadItem.BackgroundTaskLifecycle -> error("capture must author an agent fact")
                }
            }
        return authoritativeItem(
            id,
            kind,
            parent = parent,
            session = session,
            active = active,
            status = status,
            subtype = subtype,
            summary = summary,
            content = JsonObject(fields).toString(),
        )
    }
}

/** Explicit hidden task facts exercise the production count/panel path without inferred roster state. */
internal fun captureTaskCount(count: Int) =
    (0 until count).map { index ->
        de.pyryco.mobile.ui.conversations.thread.authoritativeAgent(
            id = 40_000L + index,
            shown = false,
            task = "capture-task-$index",
        )
    }

internal fun captureTasks(roster: de.pyryco.mobile.data.model.BackgroundTaskRoster) =
    roster.tasks.mapIndexed { index, task ->
        authoritativeItem(
            id = 40_000L + index,
            kind = if (task.taskType == "local_agent") "agent" else "tool_call",
            shown = false,
            active = !task.isFinished,
            status = if (task.isFinished) task.finish?.status ?: "finished" else "running",
            content =
                JsonObject(
                    mapOf(
                        "name" to JsonPrimitive(if (task.taskType == "local_agent") "Agent" else "Bash"),
                        "task_id" to JsonPrimitive(task.taskId),
                        "task_link" to
                            JsonObject(
                                mapOf(
                                    "task_type" to JsonPrimitive(task.taskType),
                                    "description" to JsonPrimitive(task.description),
                                ),
                            ),
                        "task_report" to JsonObject(mapOf("summary" to JsonPrimitive(task.finish?.summary.orEmpty()))),
                    ),
                ).toString(),
        )
    }
