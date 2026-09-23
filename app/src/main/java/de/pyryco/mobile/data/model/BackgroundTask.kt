package de.pyryco.mobile.data.model

/**
 * One `background_task_updated` frame (#677), held whole. Wire SSOT: pyrycode `docs/protocol-mobile.md`
 * § `background_task_updated`. A mid-life frame fills [patch], a terminal one fills [status] and [summary];
 * [truncatedFields] is **this frame's** cut report and stays with it.
 *
 * All three strings are claude-authored inert display text. [patch] is never parsed (a truncated patch is
 * not valid JSON), and none of them is evaluated, executed, used as a key or logged: [summary] can be a
 * literal command line.
 */
data class BackgroundTaskUpdate(
    val patch: String,
    val status: String,
    val summary: String,
    val truncatedFields: List<String>?,
)

/**
 * One background task claude left running past its turn (#677): the join, on [taskId], of the
 * `background_task_started` frame, the roster row and the updates the phone has seen for it.
 *
 * [toolCallId] is `null` until a started frame names the task, since a roster row carries none.
 * [description] and [truncatedFields] come from the started frame when one arrived, otherwise from the
 * roster row, and [truncatedFields] is that frame's own report. [description] is a literal command line
 * for `local_bash`, so it gets the same inert-text rule as [BackgroundTaskUpdate].
 *
 * The two update slots follow the wire's "disjoint fields" rule: [latestUpdate] is the last mid-life
 * frame (empty `status`) and [finish] the last terminal one (non-empty `status`), so neither erases the
 * other. [isFinished] can be `true` with [finish] `null`: after a reconnect only the knowledge that the
 * task finished carries over, not the terminal frame itself.
 */
data class BackgroundTask(
    val taskId: String,
    val toolCallId: String?,
    val taskType: String,
    val description: String,
    val truncatedFields: List<String>?,
    val latestUpdate: BackgroundTaskUpdate?,
    val finish: BackgroundTaskUpdate?,
    val isFinished: Boolean,
)

/**
 * The background tasks one conversation holds on one host (#677), in claude's order. Absence of a
 * roster means nothing has been reported; an empty one is the daemon's positive statement that nothing
 * is alive. [droppedTasks] is how many tasks the daemon's last roster did not carry.
 */
data class BackgroundTaskRoster(
    val tasks: List<BackgroundTask>,
    val droppedTasks: Int,
) {
    /**
     * Tasks with no terminal status plus [droppedTasks], saturating at [Int.MAX_VALUE] so a
     * daemon-asserted [droppedTasks] near the limit cannot overflow into a negative count.
     */
    val liveCount: Int
        get() = (tasks.count { !it.isFinished }.toLong() + droppedTasks).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
