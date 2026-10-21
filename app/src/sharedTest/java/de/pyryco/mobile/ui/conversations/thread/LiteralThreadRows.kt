package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.model.BackgroundTaskRoster
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.model.ordinaryId
import de.pyryco.mobile.data.repository.QueuedMessage
import de.pyryco.mobile.data.repository.ThreadItem

/** Supplies literal presentation inputs without running an app content reducer. */
internal fun ThreadUiState.withLiteralRows(): ThreadUiState =
    copy(displayRows = fixtureBackgroundRows(fixtureQueuedRows(items, queuedMessages), items, backgroundTasks))

internal fun fixtureQueuedRows(
    items: List<ThreadItem>,
    queued: List<QueuedMessage>,
): List<ThreadRow> {
    // echo id -> the positions in `items` carrying it, in thread order. Built only from user rows with
    // a non-empty id, so rules 3 and 4 are satisfied by what goes IN rather than by a check at every
    // lookup. Removing from the front of each list is what makes the assignment first-come.
    val echoPositions = mutableMapOf<String, MutableList<Int>>()
    items.forEachIndexed { index, item ->
        val id = item.userEchoId() ?: return@forEachIndexed
        echoPositions.getOrPut(id) { mutableListOf() }.add(index)
    }

    val claimed = mutableMapOf<Int, ThreadRow.Queued>()
    val unmatched = mutableListOf<ThreadRow.Queued>()
    val occurrences = mutableMapOf<Long, Int>()
    for (entry in queued) {
        val occurrence = occurrences[entry.id] ?: 0
        occurrences[entry.id] = occurrence + 1
        val position = entry.messageId.takeIf { it.isNotEmpty() }?.let { echoPositions[it]?.removeFirstOrNull() }
        if (position == null) {
            // Carries the snapshot's own text and NO echo id: an id this device did not mint must never
            // look like one it did, or a later reader would treat somebody else's message as this
            // device's own.
            unmatched += ThreadRow.Queued(queuedMessageId = entry.id, text = entry.text, echoId = null, occurrence = occurrence)
        } else {
            // The echo's own content, not the snapshot's: the two are the same message, and the one this
            // device sent is the one it can vouch for.
            val echo = (items[position] as ThreadItem.MessageItem).message
            claimed[position] = ThreadRow.Queued(queuedMessageId = entry.id, text = echo.content, echoId = echo.id, occurrence = occurrence)
        }
    }

    return items.mapIndexedNotNull { index, item ->
        if (item is ThreadItem.BackgroundTaskLifecycle) null else claimed[index] ?: ThreadRow.Delivered(item)
    } + unmatched
}

/**
 * This row's correlation candidacy: the echo id a backlog item may claim, or `null` when the row is not
 * a candidate at all. Rules 3 and 4 of [fixtureQueuedRows], in one place — a row that is not a
 * user-authored message, or carries no id, correlates with nothing.
 */
private fun ThreadItem.userEchoId(): String? =
    (this as? ThreadItem.MessageItem)
        ?.message
        ?.takeIf { it.role == Role.User && !it.ordinaryId.isNullOrEmpty() }
        ?.ordinaryId

/** Display-only placement. Lifecycle positions stay in repository order; no tool rows are invented. */
internal fun fixtureBackgroundRows(
    rows: List<ThreadRow>,
    items: List<ThreadItem>,
    roster: BackgroundTaskRoster?,
): List<ThreadRow> {
    val evidence = linkedMapOf<String, AgentEvidence>()
    roster?.tasks?.forEach { task ->
        evidence.putIfAbsent(task.taskId, AgentEvidence(task.toolCallId, task.taskType, task.description, task.isFinished))
    }
    roster?.settledTasks?.forEach { task ->
        val held = evidence[task.taskId] ?: AgentEvidence()
        evidence[task.taskId] =
            held.copy(
                toolId = held.toolId ?: task.toolCallId,
                type = held.type ?: task.taskType,
                description = held.description ?: task.description,
                finished = true,
            )
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
            .associateBy { ((it.item as ThreadItem.MessageItem).message.ordinaryId) }
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
    for (id in tools.keys.filterNotNull()) {
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
    val claimed = mutableSetOf<String>()
    rows.forEachIndexed { index, row ->
        val message = ((row as? ThreadRow.Delivered)?.item as? ThreadItem.MessageItem)?.message
        val root =
            when (message?.role) {
                Role.Tool -> owner[message.ordinaryId]
                Role.Assistant -> message.parentToolUseId.takeIf { it.isNotEmpty() }?.let { owner[it] }
                else -> null
            }
        if (root != null && message != null) {
            claimed += message.id
            val task = roots.getValue(root)
            val projected =
                if (message.role == Role.Tool && message.ordinaryId == root) {
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
            val rendererId = (tools.getValue(root).item as ThreadItem.MessageItem).message.id
            blocks.getValue(root) += row.copy(item = ThreadItem.MessageItem(projected), agentBlockId = rendererId)
        }
    }
    val settled = roots.filterValues { it.finished }.keys.groupBy { roots.getValue(it).finishPosition ?: (rootPositions.getValue(it) + 1) }
    val result = mutableListOf<ThreadRow>()
    rows.forEachIndexed { index, row ->
        settled[index]?.forEach { result += blocks.getValue(it) }
        val message = ((row as? ThreadRow.Delivered)?.item as? ThreadItem.MessageItem)?.message
        val id = message?.ordinaryId
        when {
            id in roots -> {
                val task = roots.getValue(checkNotNull(id))
                // Own destination distinguishes relocation from a neighbour inserting before this block.
                val finishAnchor = task.finishPosition?.let { rows.getOrNull(it - 1)?.listKey(0) ?: "thread-start" }
                result +=
                    ThreadRow.AgentStartMarker(checkNotNull(message).id, task.description.orEmpty().take(4096), task.finished, finishAnchor)
            }
            message?.id in claimed -> Unit
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

private data class AgentEvidence(
    val toolId: String? = null,
    val type: String? = null,
    val description: String? = null,
    val finished: Boolean = false,
    val finishPosition: Int? = null,
    val launchOrder: Int? = null,
)
