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
        if (task.type == "local_agent" &&
            tool.toolName in setOf("Agent", "Task") &&
            tool.inputFields["run_in_background"] == "true"
        ) {
            roots.putIfAbsent(id, task)
        }
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
    val running = roots.filterValues { !it.finished }.keys
    val knownLaunches =
        running
            .filter { roots.getValue(it).launchOrder != null }
            .sortedBy { roots.getValue(it).launchOrder }
            .iterator()
    // A newest-first page can reveal a newer start before an older start. Keep
    // unknown launches in their roster slots until their own history is loaded.
    running.forEach { id ->
        val orderedId = if (roots.getValue(id).launchOrder == null) id else knownLaunches.next()
        result += blocks.getValue(orderedId)
    }
    return result
}

/** Run expansion after a block change; [pending] holds block tools that left an open run alone. */
internal data class CarriedExpansion(
    val expandedRuns: Set<String>,
    val pending: Set<String>,
)

/**
 * A late join or a parent backfill moves tools between runs. A run that receives a tool from an open run opens,
 * once. A block tool left alone keeps that intent until its block forms a run. Without a block change, a run
 * the reader closed stays closed.
 */
internal fun carryRunExpansion(
    previous: List<ThreadRow>,
    current: List<ThreadRow>,
    expandedRuns: Set<String>,
    pending: Set<String>,
): CarriedExpansion {
    val previousBlocks = previous.toolBlocks()
    val currentBlocks = current.toolBlocks()
    val moved = currentBlocks.any { (id, block) -> id in previousBlocks && previousBlocks[id] != block }
    if (!moved && pending.isEmpty()) return CarriedExpansion(expandedRuns, pending)
    val currentRuns =
        foldToolRuns(current, emptySet())
            .filterIsInstance<ThreadRow.ToolRun>()
            .flatMap { run -> run.tools.map { it.id to run.runId } }
            .toMap()
    val intent = pending.toMutableSet()
    if (moved) {
        foldToolRuns(previous, expandedRuns)
            .filterIsInstance<ThreadRow.ToolRun>()
            .filter { it.expanded }
            .forEach { run -> run.tools.forEach { if (currentRuns[it.id] != run.runId) intent += it.id } }
    }
    return CarriedExpansion(
        expandedRuns = expandedRuns + intent.mapNotNull { currentRuns[it] },
        pending = intent.filterTo(mutableSetOf()) { it !in currentRuns && currentBlocks[it] != null },
    )
}

private fun List<ThreadRow>.toolBlocks(): Map<String, String?> =
    filterIsInstance<ThreadRow.Delivered>()
        .filter { it.isToolRow() }
        .associate { (it.item as ThreadItem.MessageItem).message.id to it.agentBlockId }

private data class AgentEvidence(
    val toolId: String? = null,
    val type: String? = null,
    val description: String? = null,
    val finished: Boolean = false,
    val finishPosition: Int? = null,
    val launchOrder: Int? = null,
)
