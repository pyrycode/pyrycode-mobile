package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.repository.ThreadItem

/** Run expansion after a supplied block change; [pending] holds tools that left an open run alone. */
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

/** Hidden task receipt is nonvisual except when it changes a displayed Agent block. */
