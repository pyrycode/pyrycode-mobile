package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun agentReplyItem(
    id: Long,
    kind: String = "assistant_message",
    parent: Long? = null,
    turn: String = "main",
    session: String = "session",
    text: String = "text-$id",
    active: Boolean = false,
    status: String = "done",
    content: JsonObject? = null,
) = ThreadItemDto.decode(
    buildJsonObject {
        put("id", id)
        put("rev", id)
        put("kind", kind)
        put("order", id)
        put("session", session)
        put("turn", turn)
        parent?.let { put("parent", it) }
        put("status", status)
        put("active", active)
        put("shown", true)
        put("summary", "summary-$id")
        put(
            "content",
            content ?: buildJsonObject {
                put("text", text)
                put("name", "Bash")
                put("tool_use_id", "raw-tool-$id")
            },
        )
    },
)

internal fun agentReplyRoot(
    id: Long = 1,
    task: String = "raw-task",
    toolUse: String? = "raw-agent",
    active: Boolean = true,
) = agentReplyItem(
    id,
    "agent",
    turn = "launch",
    active = active,
    status = if (active) "running" else "finished",
    content =
        buildJsonObject {
            put("name", "Agent")
            put("task_id", task)
            toolUse?.let { put("tool_use_id", it) }
            put("task_link", buildJsonObject { put("task_type", "local_agent") })
        },
)

/** Agent 1 with prose 2 and tools 3 and 4 in the child turn; main reply 5 in the main turn. */
internal fun agentReplySnapshot(
    child: ThreadItemDto = agentReplyItem(2, parent = 1, turn = "child"),
    main: ThreadItemDto = agentReplyItem(5),
    root: ThreadItemDto = agentReplyRoot(),
) = AuthoritativeThreadSnapshot(
    epoch = "epoch",
    generation = 3,
    items =
        listOf(
            root,
            child,
            agentReplyItem(3, "tool_call", parent = 1, turn = "child"),
            agentReplyItem(4, "tool_call", parent = 1, turn = "child"),
            main,
        ),
)
