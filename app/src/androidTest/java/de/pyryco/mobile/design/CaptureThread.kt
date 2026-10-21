package de.pyryco.mobile.design

import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.ThreadItemDto
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
    private val extras: StateFlow<List<ThreadItem>>,
    private val showSeed: StateFlow<Boolean>,
    private val queue: StateFlow<List<QueuedMessage>>,
) : NegotiatedThread() {
    override val host = base.host
    override val support = MutableStateFlow<ThreadSupport>(ThreadSupport.Supported(this))
    override val store = AuthoritativeThreadStore()

    override fun observe(conversationId: String) =
        combine(base.observe(conversationId), extras, showSeed, queue) { seed, rows, shown, queued ->
            val authored =
                (if (shown) seed.items else emptyList()) + rows.mapIndexed { index, row -> fact(index + 10_000L, row) } +
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
            store.establishComplete(host, conversationId, "capture", 30_000, authored, store.current(host, conversationId).generation)
            store.current(host, conversationId)
        }

    override suspend fun open(conversationId: String) = true

    override fun older(conversationId: String) = false

    override fun cancel(conversationId: String) = Unit

    private fun fact(
        id: Long,
        row: ThreadItem,
    ): ThreadItemDto {
        var kind = "notice"
        var subtype: String? = null
        var summary = ""
        var active = false
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
                        kind =
                            when (message.role) {
                                Role.User -> "user_message"
                                Role.Assistant -> "assistant_message"
                                Role.Tool -> "tool_call"
                            }
                        active = message.isStreaming
                        text("text", message.content)
                        text("message_id", message.id)
                        text("sent_at", message.timestamp.toString())
                        put("attachment_ids", JsonArray(message.attachments.map { JsonPrimitive(it.attachmentId) }))
                        if (tool != null) {
                            text("name", tool.toolName)
                            text("input_summary", tool.input)
                            put("input", JsonObject(tool.inputFields.mapValues { JsonPrimitive(it.value) }))
                            put("result", JsonObject(mapOf("result_summary" to JsonPrimitive(tool.output))))
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
                        text("text", row.text)
                        text("level", row.level.name.lowercase())
                    }
                    is ThreadItem.ModelRefusal -> {
                        subtype = if (row.fallbackModel == null) "model_refusal_no_fallback" else "model_refusal_fallback"
                        text("banner", row.banner)
                        text("original_model", row.originalModel)
                        row.fallbackModel?.let { text("fallback_model", it) }
                    }
                    is ThreadItem.StoppedTurn -> {
                        kind = "turn_end"
                        text("terminal_reason", row.reason)
                        text("error_category", row.category)
                    }
                    is ThreadItem.CompactionBoundary -> {
                        kind = "compaction"
                        text("trigger", if (row.manual) "manual" else "auto")
                    }
                    is ThreadItem.UnrecognizedMessage -> {
                        summary = row.raw
                    }
                    is ThreadItem.BackgroundTaskLifecycle -> error("capture must author an agent fact")
                }
            }
        return authoritativeItem(id, kind, active = active, subtype = subtype, summary = summary, content = JsonObject(fields).toString())
    }
}
