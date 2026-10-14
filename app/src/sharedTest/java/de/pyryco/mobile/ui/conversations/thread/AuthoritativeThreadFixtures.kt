package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.repository.AuthoritativePendingSend
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun authoritativeItem(
    id: Long,
    kind: String = "assistant_message",
    order: Long? = id,
    parent: Long? = null,
    active: Boolean = false,
    status: String = "done",
    shown: Boolean = true,
    rev: Long = id,
    endedOrder: Long? = null,
    session: String = "old-session",
    agent: String? = "claude",
    subtype: String? = null,
    summary: String = "summary-$id",
    content: String = """{"text":"text-$id"}""",
): ThreadItemDto =
    ThreadItemDto.decode(
        JsonObject(
            buildMap {
                put("id", JsonPrimitive(id))
                put("kind", JsonPrimitive(kind))
                put("rev", JsonPrimitive(rev))
                order?.let { put("order", JsonPrimitive(it)) }
                parent?.let { put("parent", JsonPrimitive(it)) }
                endedOrder?.let { put("ended_order", JsonPrimitive(it)) }
                put("session", JsonPrimitive(session))
                agent?.let { put("agent", JsonPrimitive(it)) }
                put("active", JsonPrimitive(active))
                put("status", JsonPrimitive(status))
                put("shown", JsonPrimitive(shown))
                put("summary", JsonPrimitive(summary))
                subtype?.let { put("subtype", JsonPrimitive(it)) }
                put("content", Json.parseToJsonElement(content))
            },
        ),
    )

internal fun authoritativeAgent(
    id: Long,
    active: Boolean = true,
    status: String = if (active) "running" else "finished",
    endedOrder: Long? = null,
    task: String? = "task-$id",
    session: String = "old-session",
    shown: Boolean = true,
    parent: Long? = null,
    order: Long = id,
) = authoritativeItem(
    id,
    "agent",
    order = order,
    parent = parent,
    active = active,
    status = status,
    endedOrder = endedOrder,
    session = session,
    shown = shown,
    content = """{"name":"Agent","input_summary":"agent-$id","input":{"description":"agent-$id"}${task?.let {
        ",\"task_id\":\"$it\",\"task_link\":{\"task_type\":\"local_agent\",\"description\":\"agent-$id\"}"
    }.orEmpty()},"result":{"result_summary":"instant launch result"},"ending":{"cause":"idle_sleep"}}""",
)

internal fun authoritativeSnapshot(
    vararg items: ThreadItemDto,
    epoch: String = "epoch",
) = AuthoritativeThreadSnapshot(epoch = epoch, items = items.toList(), completedVersion = 100, checkpoint = 90)

/** An unread delivered row followed by feedback that carries no ordered read evidence. */
internal fun authoritativeSendTail(status: String): AuthoritativeThreadSnapshot {
    val history = authoritativeItem(1, rev = 4)
    val snapshot = authoritativeSnapshot(history).copy(checkpoint = 4)
    return if (status == "sending") {
        snapshot.copy(
            pendingSends =
                listOf(
                    AuthoritativePendingSend(
                        deviceId = "device",
                        message = Message("local", "old-session", Role.User, "pending text", Instant.fromEpochMilliseconds(0), false),
                    ),
                ),
        )
    } else {
        snapshot.copy(
            items =
                listOf(
                    history,
                    authoritativeItem(2, kind = "user_message", order = null, active = status == "queued", status = status, rev = 3),
                ),
        )
    }
}

/** Daemon prompt_answered notice facts, never an outstanding live request. */
internal fun authoritativeAnsweredPrompt(
    id: Long,
    correlation: String,
    question: Boolean,
) = authoritativeItem(
    id,
    "notice",
    subtype = "prompt_answered",
    session = "new",
    summary = "Prompt answered: ${if (question) "answer" else "allow"}",
    content = """{"conversation_id":"c","correlation_id":"$correlation","session_id":"new",
        "decision":"${if (question) "answer" else "allow"}","behavior":"allow","context":{
        "tool":"${if (question) "AskUserQuestion" else "Bash"}","class":"${if (question) "question" else "permission"}",
        "questions":${if (question) """[{"index":0,"text":"Proceed?","values":[{"text":"Yes","meaning":"agree"}]}]""" else "[]"}}}""",
)
