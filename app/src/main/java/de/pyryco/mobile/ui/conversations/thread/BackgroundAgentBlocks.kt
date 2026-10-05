package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.model.BackgroundTaskRoster
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.repository.ThreadItem

/** Display-only placement. Lifecycle positions stay in repository order; no tool rows are invented. */
internal fun foldBackgroundAgentBlocks(
    rows: List<ThreadRow>,
    items: List<ThreadItem>,
    roster: BackgroundTaskRoster?,
): List<ThreadRow> {
    val evidence = linkedMapOf<String, AgentEvidence>()
    roster?.tasks?.forEach { task ->
        evidence.putIfAbsent(task.taskId, AgentEvidence(task.toolCallId, task.taskType, task.description, task.isFinished))
    }
    var visiblePosition = 0
    items.forEachIndexed { index, item ->
        if (item is ThreadItem.BackgroundTaskLifecycle) {
            val held = evidence[item.taskId] ?: AgentEvidence()
            evidence[item.taskId] =
                held.copy(
                    toolId = item.toolCallId?.takeIf { it.isNotEmpty() } ?: held.toolId,
                    type = item.taskType ?: held.type,
                    description = item.description ?: held.description,
                    finished = held.finished || item.terminal != null,
                    finishPosition = if (item.terminal != null) held.finishPosition ?: visiblePosition else held.finishPosition,
                    launchOrder = if (item.terminal == null) held.launchOrder ?: index else held.launchOrder,
                )
        } else {
            visiblePosition++
        }
    }
    val tools =
        rows
            .filterIsInstance<ThreadRow.Delivered>()
            .filter { it.isToolRow() }
            .associateBy { ((it.item as ThreadItem.MessageItem).message.id) }
    val roots = linkedMapOf<String, AgentEvidence>()
    for (task in evidence.values) {
        val id = task.toolId?.takeIf { it.isNotEmpty() } ?: continue
        val tool = ((tools[id]?.item as? ThreadItem.MessageItem)?.message)?.toolCall ?: continue
        if (task.type == "local_agent" && tool.toolName in setOf("Agent", "Task")) roots.putIfAbsent(id, task)
    }
    if (roots.isEmpty()) return rows

    // Memoise the ownership of loaded parent chains, including unmatched/cyclic paths.
    val owner = mutableMapOf<String, String?>()
    for (id in tools.keys) {
        var current = id
        val path = linkedSetOf<String>()
        while (current !in owner && current !in roots && current in tools && path.add(current)) {
            current = ((tools.getValue(current).item as ThreadItem.MessageItem).message.toolCall?.parentToolUseId).orEmpty()
        }
        val root = if (current in roots) current else owner[current]
        path.forEach { owner[it] = root }
        if (id in roots) owner[id] = id
    }
    val blocks = roots.keys.associateWith { mutableListOf<ThreadRow>() }
    val rootPositions = mutableMapOf<String, Int>()
    rows.forEachIndexed { index, row ->
        val message = ((row as? ThreadRow.Delivered)?.item as? ThreadItem.MessageItem)?.message
        val root = message?.id?.let { owner[it] }
        if (root != null) {
            val task = roots.getValue(root)
            val projected =
                if (message.id == root) {
                    rootPositions[root] = index
                    message.copy(
                        toolCall =
                            message.toolCall?.copy(
                                status = if (task.finished) ToolCallStatus.Done else ToolCallStatus.Running,
                                elapsedSeconds = null,
                            ),
                    )
                } else {
                    message
                }
            blocks.getValue(root) += row.copy(item = ThreadItem.MessageItem(projected), agentBlockId = root)
        }
    }
    val settled = roots.filterValues { it.finished }.keys.groupBy { roots.getValue(it).finishPosition ?: (rootPositions.getValue(it) + 1) }
    val result = mutableListOf<ThreadRow>()
    rows.forEachIndexed { index, row ->
        settled[index]?.forEach { result += blocks.getValue(it) }
        val id = ((row as? ThreadRow.Delivered)?.item as? ThreadItem.MessageItem)?.message?.id
        when {
            id in roots -> {
                val task = roots.getValue(checkNotNull(id))
                result += ThreadRow.AgentStartMarker(id, task.description.orEmpty().take(4096), task.finished)
            }
            id?.let { owner[it] } != null -> Unit
            else -> result += row
        }
    }
    // A terminal after the last visible entry still precedes subsequent unmatched queued rows.
    settled[rows.size]?.forEach { result += blocks.getValue(it) }
    roots
        .filterValues { !it.finished }
        .keys
        .sortedWith(compareBy { roots.getValue(it).launchOrder ?: Int.MAX_VALUE })
        .forEach { result += blocks.getValue(it) }
    return result
}

private data class AgentEvidence(
    val toolId: String? = null,
    val type: String? = null,
    val description: String? = null,
    val finished: Boolean = false,
    val finishPosition: Int? = null,
    val launchOrder: Int? = null,
)
