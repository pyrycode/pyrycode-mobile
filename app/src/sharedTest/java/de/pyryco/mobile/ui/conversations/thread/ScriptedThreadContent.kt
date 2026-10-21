package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.SessionProvenance
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Scenario-side daemon facts. Only supplied items and supported live readings cross the pump. */
internal class ScriptedThreadContent(
    private val deliver: (Envelope) -> Unit,
) {
    private val documents = mutableMapOf<String, LinkedHashMap<Long, JsonObject>>()
    private val identities = mutableMapOf<Pair<String, String>, Long>()
    private val sessions = mutableMapOf<String, String>()
    private val summaries = mutableMapOf<String, JsonObject>()
    private val replies = mutableMapOf<Pair<String, String>, String>()
    private val sequences = mutableSetOf<Triple<String, String, String>>()
    private var nextIdentity = 0L
    private var version = 1000L
    private var segment = 0L

    @Synchronized fun id(
        conversation: String,
        name: String,
    ): Long = identities.getOrPut(conversation to name) { ++nextIdentity }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.content.orEmpty()

    private fun document(conversation: String) = documents.getOrPut(conversation) { linkedMapOf() }

    private fun session(conversation: String) = sessions[conversation] ?: "session"

    private fun frame(
        type: String,
        payload: JsonObject,
        reply: Long? = null,
    ) = Envelope(9000, type, TS, payload, inReplyTo = reply)

    private fun live(
        envelope: Envelope,
        conversation: String,
    ) {
        val fields = envelope.payload as? JsonObject
        deliver(
            envelope.copy(
                conversationId = JsonPrimitive(conversation),
                sessionProvenance =
                    SessionProvenance(
                        JsonPrimitive(fields?.text("session_id")?.takeIf { it.isNotEmpty() } ?: session(conversation)),
                    ),
            ),
        )
    }

    private fun clear(
        conversation: String,
        type: String,
    ) = live(
        frame(type, buildJsonObject {}).copy(sessionStateCleared = JsonPrimitive(true)),
        conversation,
    )

    private fun put(
        conversation: String,
        key: String,
        kind: String,
        content: JsonObject,
        turn: String = "",
        parent: String = "",
        active: Boolean = false,
        status: String = "done",
        subtype: String? = null,
        shown: Boolean = true,
    ) {
        val itemId = id(conversation, key)
        val held = document(conversation)[itemId]
        val revision = ++version
        val item =
            buildJsonObject {
                put("id", itemId)
                put("rev", revision)
                put("order", held?.get("order") ?: JsonPrimitive(revision))
                put("kind", kind)
                put("session", held?.get("session") ?: JsonPrimitive(session(conversation)))
                put("agent", held?.get("agent") ?: summaries[conversation]?.get("agent") ?: JsonPrimitive("claude"))
                put("turn", held?.get("turn") ?: JsonPrimitive(turn))
                if (parent.isNotEmpty()) put("parent", id(conversation, parent))
                put("active", active)
                put("status", status)
                put("shown", shown)
                put("summary", content.text("summary").ifEmpty { kind })
                subtype?.let { put("subtype", it) }
                put("content", content)
            }
        document(conversation)[itemId] = item
        deliver(
            frame(
                "thread_item_added",
                buildJsonObject {
                    put("conversation_id", conversation)
                    put("epoch", EPOCH)
                    put("version", revision)
                    put("item", item)
                },
            ),
        )
    }

    private fun update(
        conversation: String,
        key: String,
        changes: Map<String, kotlinx.serialization.json.JsonElement>,
    ) {
        val held = document(conversation)[id(conversation, key)] ?: return
        val revision = ++version
        val item = JsonObject(held + changes + ("rev" to JsonPrimitive(revision)))
        document(conversation)[id(conversation, key)] = item
        deliver(
            frame(
                "thread_item_added",
                buildJsonObject {
                    put("conversation_id", conversation)
                    put("epoch", EPOCH)
                    put("version", revision)
                    put("item", item)
                },
            ),
        )
    }

    /** Complete the real repository's query, with a separate correlated success marker. */
    @Synchronized fun respond(request: Envelope) {
        if (request.type !in setOf("thread_catch_up", "thread_page")) return
        val fields = request.payload.jsonObject
        val conversation = fields.text("conversation_id")
        val facts = document(conversation).values.toList()
        val captured = maxOf(facts.maxOfOrNull { it.text("rev").toLong() } ?: 0, version.takeIf { facts.isNotEmpty() } ?: 0)
        val base =
            buildJsonObject {
                put("conversation_id", conversation)
                put("epoch", EPOCH)
                put("version", captured)
            }
        deliver(frame("thread_items", JsonObject(base + mapOf("items" to JsonArray(facts), "final" to JsonPrimitive(false))), request.id))
        val coverage =
            buildJsonObject {
                if (request.type == "thread_catch_up" && fields.text("version") != "0") {
                    put("from_version", fields.getValue("version"))
                } else {
                    val upper = if (request.type == "thread_page") fields.text("before_order").toLong() else captured + 1
                    put("lower_order", 0)
                    put("upper_order", upper)
                    put("continuation", 0)
                    put("older_exists", false)
                }
            }
        val terminal = JsonObject(base + coverage + mapOf("items" to JsonArray(emptyList()), "final" to JsonPrimitive(true)))
        deliver(frame("thread_items", terminal, request.id))
    }

    @Synchronized fun push(envelope: Envelope) {
        val fields = envelope.payload as? JsonObject ?: return deliver(envelope)
        val conversation = fields.text("conversation_id")
        val turn = fields.text("turn_id")
        val parent = fields.text("parent_tool_use_id")
        when (envelope.type) {
            "conversations" -> {
                val rows =
                    (fields["conversations"] as JsonArray).map { raw ->
                        val summary = raw.jsonObject
                        val c = summary.text("id")
                        val supplied = summary.text("current_session_id").ifEmpty { session(c) }
                        sessions[c] = supplied
                        JsonObject(summary + ("current_session_id" to JsonPrimitive(supplied))).also { summaries[c] = it }
                    }
                deliver(envelope.copy(payload = JsonObject(fields + ("conversations" to JsonArray(rows)))))
            }
            "assistant_delta" -> {
                if (!sequences.add(Triple(conversation, turn, fields.text("seq")))) return
                val key = replies.getOrPut(conversation to turn) { "reply:$turn:${++segment}" }
                val prior = document(conversation)[id(conversation, key)]?.get("content") as? JsonObject
                put(
                    conversation,
                    key,
                    "assistant_message",
                    buildJsonObject { put("text", prior?.text("text").orEmpty() + fields.text("text")) },
                    turn,
                    parent,
                    true,
                    "running",
                )
            }
            "message" -> {
                replies.keys.removeAll { it.first == conversation }
                put(
                    conversation,
                    "message:${fields.text("message_id")}",
                    if (fields.text("role") == "user") "user_message" else "assistant_message",
                    JsonObject(fields + ("occurred_at" to JsonPrimitive(envelope.ts))),
                    turn,
                    parent,
                    status = if (fields.text("role") == "user") "delivered" else "done",
                )
            }
            "tool_use" -> {
                if (parent.isEmpty()) replies.remove(conversation to turn)
                val key = fields.text("tool_use_id")
                // A replay describes the same supplied item, not a second invocation.
                if (document(conversation).containsKey(id(conversation, key))) return
                put(
                    conversation,
                    key,
                    if (fields.text("name") in setOf("Agent", "Task")) "agent" else "tool_call",
                    fields,
                    turn,
                    parent,
                    true,
                    "running",
                )
            }
            "tool_result", "tool_denied" -> {
                val key = fields.text("tool_use_id")
                val held = document(conversation)[id(conversation, key)] ?: return
                val content = held.getValue("content").jsonObject
                val task = content["task_link"] != null
                val denied = envelope.type == "tool_denied"
                update(
                    conversation,
                    key,
                    mapOf(
                        "content" to JsonObject(content + ((if (denied) "denial" else "result") to fields)),
                        "active" to JsonPrimitive(task),
                        "status" to
                            JsonPrimitive(
                                when {
                                    task -> "running"
                                    denied -> "denied"
                                    fields.text("is_error") == "true" -> "failed"
                                    else -> "done"
                                },
                            ),
                    ),
                )
            }
            "background_task_started", "background_task_updated" -> {
                val task = fields.text("task_id")
                val held =
                    if (envelope.type == "background_task_started") {
                        document(conversation)[id(conversation, fields.text("tool_call_id"))]
                    } else {
                        document(conversation).values.singleOrNull { (it["content"] as? JsonObject)?.text("task_id") == task }
                    }
                if (held == null) return
                val key =
                    identities.entries
                        .single { it.key.first == conversation && it.value == held.text("id").toLong() }
                        .key.second
                val content = held.getValue("content").jsonObject
                val started = envelope.type == "background_task_started"
                update(
                    conversation,
                    key,
                    buildMap {
                        val taskFields =
                            if (started) {
                                mapOf("task_id" to JsonPrimitive(task), "task_link" to fields)
                            } else {
                                mapOf("ending" to fields)
                            }
                        put("content", JsonObject(content + taskFields))
                        put("active", JsonPrimitive(started))
                        put("status", JsonPrimitive(if (started) "running" else "finished"))
                        if (!started) put("ended_order", JsonPrimitive(version + 1))
                    },
                )
            }
            "turn_end" -> {
                document(conversation)
                    .values
                    .filter {
                        it.text("kind") == "assistant_message" && it.text("turn") == turn && it.text("active") == "true"
                    }.forEach { item ->
                        val key =
                            identities.entries
                                .single { it.key.first == conversation && it.value == item.text("id").toLong() }
                                .key.second
                        update(conversation, key, mapOf("active" to JsonPrimitive(false), "status" to JsonPrimitive("done")))
                    }
                replies.remove(conversation to turn)
                put(
                    conversation,
                    "terminal:$turn",
                    "turn_end",
                    fields,
                    turn,
                    parent,
                    status = if (fields.text("is_error") == "true") "failed" else "done",
                    shown = fields.text("terminal_reason").isNotEmpty() || fields.text("stop_reason") !in setOf("", "end_turn"),
                )
                clear(conversation, "thinking_progress")
                live(
                    frame(
                        "turn_state",
                        buildJsonObject {
                            put("conversation_id", conversation)
                            put("state", "idle")
                        },
                    ),
                    conversation,
                )
            }
            "session_transition" -> {
                val next = fields.text("new_session_id")
                put(conversation, "divider:$next", "session_divider", JsonObject(fields + ("occurred_at" to JsonPrimitive(envelope.ts))))
                sessions[conversation] = next
                summaries[conversation]?.let { summary ->
                    val changed = JsonObject(summary + ("current_session_id" to JsonPrimitive(next)))
                    push(frame("conversations", buildJsonObject { put("conversations", JsonArray(listOf(changed))) }))
                }
                listOf("thinking_progress", "tool_progress", "api_retry", "compacting", "resetting", "turn_state")
                    .forEach { clear(conversation, it) }
            }
            "unrecognized_message" ->
                put(
                    conversation,
                    "notice:${++segment}",
                    "notice",
                    JsonObject(fields + ("occurred_at" to JsonPrimitive(envelope.ts))),
                    subtype = "unrecognized_message",
                )
            "thread_item_added" -> {
                val item = fields.getValue("item").jsonObject
                val itemId = item.text("id").toLong()
                val held = document(conversation)[itemId]
                if (held == null || held.text("rev").toLong() < item.text("rev").toLong()) document(conversation)[itemId] = item
                version = maxOf(version, fields.text("version").toLong())
                deliver(envelope)
            }
            else -> {
                if (conversation.isNotEmpty() && envelope.type !in setOf("ack", "error", "thread_items")) {
                    live(envelope, conversation)
                } else {
                    deliver(envelope)
                }
            }
        }
    }

    private companion object {
        const val EPOCH = "scripted"
        const val TS = "2026-05-31T00:00:00Z"
    }
}
