package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.BackgroundTaskUpdate
import de.pyryco.mobile.data.network.BackgroundTaskStartedPayloadDto
import de.pyryco.mobile.data.network.BackgroundTaskUpdatedPayloadDto
import kotlinx.datetime.Instant

/** Shared live/history launch fold. Empty ids cannot create a join; replay keeps the first position. */
internal fun List<ThreadItem>.withBackgroundTaskStarted(
    dto: BackgroundTaskStartedPayloadDto,
    timestamp: Instant,
): List<ThreadItem> {
    if (dto.taskId.isEmpty()) return this
    return withBackgroundTaskLifecycle(
        ThreadItem.BackgroundTaskLifecycle(
            taskId = dto.taskId,
            occurredAt = timestamp,
            toolCallId = dto.toolCallId.takeIf { it.isNotEmpty() },
            description = dto.description,
            taskType = dto.taskType,
            truncatedFields = dto.truncatedFields,
        ),
    )
}

/** Mid-life updates add no position. Unknown non-empty statuses are terminal too. */
internal fun List<ThreadItem>.withBackgroundTaskUpdated(
    dto: BackgroundTaskUpdatedPayloadDto,
    timestamp: Instant,
): List<ThreadItem> {
    if (dto.taskId.isEmpty() || dto.status.isEmpty()) return this
    return withBackgroundTaskLifecycle(
        ThreadItem.BackgroundTaskLifecycle(
            taskId = dto.taskId,
            occurredAt = timestamp,
            terminal = BackgroundTaskUpdate(dto.patch, dto.status, dto.summary, dto.truncatedFields),
        ),
    )
}

private fun List<ThreadItem>.withBackgroundTaskLifecycle(row: ThreadItem.BackgroundTaskLifecycle): List<ThreadItem> {
    val index = indexOfFirst { it is ThreadItem.BackgroundTaskLifecycle && it.samePosition(row) }
    val next =
        if (index < 0) {
            this + row
        } else {
            toMutableList().apply {
                this[index] = (this[index] as ThreadItem.BackgroundTaskLifecycle).withLaunchFrom(row)
            }
        }
    return next.withBackgroundTaskLaunches()
}

internal fun ThreadItem.BackgroundTaskLifecycle.samePosition(other: ThreadItem.BackgroundTaskLifecycle): Boolean =
    taskId == other.taskId && (terminal != null) == (other.terminal != null)

/** Complete unknown launch fields only; first-seen content and positions win over replay. */
internal fun ThreadItem.BackgroundTaskLifecycle.withLaunchFrom(
    source: ThreadItem.BackgroundTaskLifecycle,
): ThreadItem.BackgroundTaskLifecycle =
    copy(
        toolCallId = toolCallId ?: source.toolCallId,
        description = description ?: source.description,
        taskType = taskType ?: source.taskType,
        truncatedFields = if (description == null) source.truncatedFields else truncatedFields,
    )

/** Joins across page seams and terminal-before-start arrival without moving either marker. */
internal fun List<ThreadItem>.withBackgroundTaskLaunches(): List<ThreadItem> {
    val launches = filterIsInstance<ThreadItem.BackgroundTaskLifecycle>().filter { it.terminal == null }.associateBy { it.taskId }
    if (launches.isEmpty()) return this
    return map { row ->
        if (row is ThreadItem.BackgroundTaskLifecycle && row.terminal != null) {
            launches[row.taskId]?.let { row.withLaunchFrom(it) } ?: row
        } else {
            row
        }
    }
}

/** Fill a duplicate's unknown join before history overlap drops it, keeping live positions/content. */
internal fun List<ThreadItem>.withBackgroundTaskHintsFrom(rows: List<ThreadItem>): List<ThreadItem> {
    val twins = rows.filterIsInstance<ThreadItem.BackgroundTaskLifecycle>().associateBy { it.taskId to (it.terminal != null) }
    if (twins.isEmpty()) return this
    return map { row ->
        if (row is ThreadItem.BackgroundTaskLifecycle) {
            twins[row.taskId to (row.terminal != null)]?.let { row.withLaunchFrom(it) } ?: row
        } else {
            row
        }
    }
}
