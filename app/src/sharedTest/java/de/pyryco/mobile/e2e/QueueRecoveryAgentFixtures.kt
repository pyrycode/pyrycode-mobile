package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** #2174 daemon facts shaped like the scripted fixtures, for the JVM and screen probes. */
internal fun scenarioItem(
    id: Long,
    kind: String = "assistant_message",
    text: String = "",
    order: Long? = id,
    rev: Long = id,
    active: Boolean = false,
    status: String = if (active) "running" else "done",
    parent: Long? = null,
    turn: String = "turn",
    endedOrder: Long? = null,
): ThreadItemDto =
    ThreadItemDto.decode(
        buildJsonObject {
            put("id", id)
            put("rev", rev)
            put("kind", kind)
            order?.let { put("order", it) }
            endedOrder?.let { put("ended_order", it) }
            put("session", "session")
            put("turn", turn)
            put("agent", "claude")
            parent?.let { put("parent", it) }
            put("status", status)
            put("active", active)
            put("shown", true)
            put("summary", "fallback")
            put(
                "content",
                buildJsonObject {
                    put("text", text)
                    when (kind) {
                        "tool_call" -> put("name", "Read")
                        "agent" -> {
                            put("name", "Agent")
                            put("task_id", "task1783")
                            put("task_link", buildJsonObject { put("task_type", "local_agent") })
                        }
                    }
                },
            )
        },
    )

internal fun scenarioUser(
    id: Long,
    text: String,
    status: String = "delivered",
    order: Long? = id,
) = scenarioItem(id, "user_message", text, order = order, status = status)

internal fun scenarioSnapshot(items: List<ThreadItemDto>) = AuthoritativeThreadSnapshot(epoch = "epoch", generation = 3, items = items)

/** `replay-order.jsonl`'s three settled main items after the delivered prompt. */
internal fun replayScenarioItems() =
    listOf(scenarioUser(1, "hello"), scenarioItem(2, text = "alpha "), scenarioItem(3, text = "bravo "), scenarioItem(4, text = "charlie"))

/** `background-agent-open.jsonl`: owned and unmatched prose, main prose and three owned tools, the last running. */
internal fun runningAgentScenarioItems() =
    listOf(
        scenarioUser(1, "hello"),
        scenarioItem(2, "agent", active = true),
        scenarioItem(3, text = "child1827-before", parent = 2),
        scenarioItem(4, text = "unmatched1827", parent = 99),
        scenarioItem(5, text = "main1827"),
        scenarioItem(6, "tool_call", parent = 2),
        scenarioItem(7, "tool_call", parent = 2),
        scenarioItem(8, "tool_call", parent = 2, active = true),
    )

/** `background-agent-finish.jsonl` applied: newer revisions settle the agent and its last tool before the reply. */
internal fun finishedAgentScenarioItems() =
    runningAgentScenarioItems().take(7) +
        listOf(
            scenarioItem(2, "agent", rev = 11, status = "finished", endedOrder = 11),
            scenarioItem(8, "tool_call", rev = 10, parent = 2),
            scenarioItem(9, text = "child1827-after", parent = 2),
            scenarioItem(10, text = "after1783", order = 12),
        )
