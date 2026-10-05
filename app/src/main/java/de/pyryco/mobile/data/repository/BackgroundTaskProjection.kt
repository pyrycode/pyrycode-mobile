package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.BackgroundTask
import de.pyryco.mobile.data.model.BackgroundTaskProgress
import de.pyryco.mobile.data.model.BackgroundTaskRoster
import de.pyryco.mobile.data.model.BackgroundTaskUpdate
import de.pyryco.mobile.data.network.BackgroundTaskProgressPayloadDto
import de.pyryco.mobile.data.network.BackgroundTaskRosterPayloadDto
import de.pyryco.mobile.data.network.BackgroundTaskRowDto
import de.pyryco.mobile.data.network.BackgroundTaskStartedPayloadDto
import de.pyryco.mobile.data.network.BackgroundTaskUpdatedPayloadDto
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Which background tasks each conversation on one host has finished (#677). It is the one piece of
 * background-task state that outlives a connection: claude does not reliably send a roster after a
 * completion, and the daemon re-sends its retained roster to every reconnecting client, which mobile is on
 * every return to the foreground. Without this a finished task would come back as live each time.
 *
 * [RelayRepositoryCoordinator] owns the instance for the life of the host and threads it into each
 * connection's [RemoteConversationRepository], the [de.pyryco.mobile.data.network.ReplayCursor] shape.
 * Written through [MutableStateFlow.update] because a torn-down connection's collector can finish one fold
 * after the next connection has started. In memory only, so a process restart forgets it.
 */
class FinishedBackgroundTasks {
    private val finished = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    /** Records that [taskId] in [conversationId] finished, whether or not the phone knows the task yet. */
    fun mark(
        conversationId: String,
        taskId: String,
    ) {
        finished.update { it + (conversationId to (it[conversationId].orEmpty() + taskId)) }
    }

    fun contains(
        conversationId: String,
        taskId: String,
    ): Boolean = finished.value[conversationId]?.contains(taskId) == true

    /** Forgets the ids a roster for [conversationId] omits, so the set stays bounded by what is listed. */
    fun retainOnly(
        conversationId: String,
        taskIds: Set<String>,
    ) {
        finished.update { current ->
            val kept = current[conversationId]?.intersect(taskIds) ?: return@update current
            if (kept.isEmpty()) current - conversationId else current + (conversationId to kept)
        }
    }
}

/**
 * The background tasks every conversation holds on one connection (#677): the state, decoder and read for
 * `background_task_started`, `background_task_updated`, `background_task_roster` and
 * `background_task_progress` (#1042), split out of
 * [RemoteConversationRepository] like [QueueProjection]. The repository's `onInbound` arm calls [apply]
 * only behind the negotiated `interactive` gate. The panel remains replacement state; scalar lifecycle
 * evidence is retained separately by [ThreadProjection].
 *
 * One instance per repository, and a fresh repository per connection, so after a reconnect a conversation
 * holds only what that connection's frames report. The only state carried over is [finished].
 *
 * The four frames join on `task_id` in whatever order they arrive. Merge rules follow desktop's
 * `backgroundTaskRosterStore`, with three differences: an update keeps its `status` and `summary`
 * (desktop#1558 drops them), any non-empty `status` finishes a task rather than a closed set of three, and a
 * started task is listed at once rather than held until a roster lists it.
 *
 * Nothing here logs. Every field is claude-authored text or an id, and a caught decode exception is
 * discarded unread because kotlinx messages can quote the input.
 */
internal class BackgroundTaskProjection(
    private val finished: FinishedBackgroundTasks,
) {
    /**
     * `conversationId -> roster`. A conversation with no key has reported nothing, which is distinct from
     * an empty roster. Written only by the single inbound collector.
     */
    private val mutableRosters = MutableStateFlow<Map<String, BackgroundTaskRoster>>(emptyMap())
    val rosters: StateFlow<Map<String, BackgroundTaskRoster>> = mutableRosters.asStateFlow()

    // A roster join no longer proves a started frame supplied the longer description.
    private val startedTasks = mutableSetOf<Pair<String, String>>()

    /**
     * Updates and progress for tasks a conversation does not hold yet, keyed `conversationId -> taskId`, so a start or
     * roster that arrives later joins them. Confined to the single inbound collector like [mutableRosters];
     * a roster for the conversation clears its entry, which keeps it bounded.
     */
    private val pending = mutableMapOf<String, Map<String, Slots>>()

    /** The frame slots of one task; see [BackgroundTask.latestUpdate], [BackgroundTask.finish] and [BackgroundTask.progress]. */
    private data class Slots(
        val latestUpdate: BackgroundTaskUpdate? = null,
        val finish: BackgroundTaskUpdate? = null,
        val progress: BackgroundTaskProgress? = null,
    )

    fun apply(envelope: Envelope) {
        try {
            when (envelope.type) {
                RemoteConversationRepository.TYPE_BACKGROUND_TASK_STARTED ->
                    applyStarted(MobileJson.decodeFromJsonElement<BackgroundTaskStartedPayloadDto>(envelope.payload))
                RemoteConversationRepository.TYPE_BACKGROUND_TASK_UPDATED ->
                    applyUpdated(MobileJson.decodeFromJsonElement<BackgroundTaskUpdatedPayloadDto>(envelope.payload))
                RemoteConversationRepository.TYPE_BACKGROUND_TASK_ROSTER ->
                    applyRoster(MobileJson.decodeFromJsonElement<BackgroundTaskRosterPayloadDto>(envelope.payload))
                RemoteConversationRepository.TYPE_BACKGROUND_TASK_PROGRESS ->
                    applyProgress(MobileJson.decodeFromJsonElement<BackgroundTaskProgressPayloadDto>(envelope.payload))
            }
        } catch (e: IllegalArgumentException) {
            return
        }
    }

    /** Upserts the task in place, creating the conversation's roster if this is its first frame. */
    private fun applyStarted(dto: BackgroundTaskStartedPayloadDto) {
        val conversationId = dto.conversationId
        startedTasks += conversationId to dto.taskId
        val roster = mutableRosters.value[conversationId] ?: BackgroundTaskRoster(emptyList(), droppedTasks = 0)
        val held = roster.tasks.firstOrNull { it.taskId == dto.taskId }
        val slots = held?.slots() ?: takePending(conversationId, dto.taskId)
        val task =
            task(
                conversationId = conversationId,
                taskId = dto.taskId,
                toolCallId = dto.toolCallId.takeIf { it.isNotEmpty() } ?: held?.toolCallId,
                taskType = dto.taskType,
                description = dto.description,
                truncatedFields = dto.truncatedFields,
                slots = slots,
            )
        val tasks = if (held == null) roster.tasks + task else roster.tasks.map { if (it.taskId == dto.taskId) task else it }
        publish(conversationId, roster.copy(tasks = tasks))
    }

    /**
     * A non-empty `status` marks the task finished before anything else, even for a task the phone does not
     * know yet, so the mark does not depend on arrival order. An update alone never creates a roster: that
     * would read as an empty roster, a fact the daemon did not state.
     */
    private fun applyUpdated(dto: BackgroundTaskUpdatedPayloadDto) {
        val conversationId = dto.conversationId
        val update = BackgroundTaskUpdate(dto.patch, dto.status, dto.summary, dto.truncatedFields)
        val terminal = dto.status.isNotEmpty()
        if (terminal) finished.mark(conversationId, dto.taskId)
        val roster = mutableRosters.value[conversationId]
        val held = roster?.tasks?.firstOrNull { it.taskId == dto.taskId }
        if (roster == null || held == null) {
            val byTask = pending[conversationId].orEmpty()
            val slots = byTask[dto.taskId] ?: Slots()
            pending[conversationId] = byTask + (dto.taskId to slots.with(update, terminal))
            return
        }
        val task = held.withSlots(conversationId, held.slots().with(update, terminal))
        publish(conversationId, roster.copy(tasks = roster.tasks.map { if (it.taskId == dto.taskId) task else it }))
    }

    /**
     * Replaces the task's progress whole, nothing summed. A task known to be finished, on this connection or an
     * earlier one, takes none. Like an update, progress for a task not held yet waits in [pending] and never
     * creates a roster.
     */
    private fun applyProgress(dto: BackgroundTaskProgressPayloadDto) {
        val conversationId = dto.conversationId
        if (finished.contains(conversationId, dto.taskId)) return
        val progress =
            BackgroundTaskProgress(
                description = dto.description,
                subagentType = dto.subagentType,
                lastToolName = dto.lastToolName,
                totalTokens = dto.totalTokens,
                toolUses = dto.toolUses,
                durationMs = dto.durationMs,
                truncatedFields = dto.truncatedFields,
            )
        val roster = mutableRosters.value[conversationId]
        val held = roster?.tasks?.firstOrNull { it.taskId == dto.taskId }
        if (roster == null || held == null) {
            val byTask = pending[conversationId].orEmpty()
            val slots = byTask[dto.taskId] ?: Slots()
            pending[conversationId] = byTask + (dto.taskId to slots.copy(progress = progress))
            return
        }
        val task = held.withSlots(conversationId, held.slots().copy(progress = progress))
        publish(conversationId, roster.copy(tasks = roster.tasks.map { if (it.taskId == dto.taskId) task else it }))
    }

    /**
     * Replaces the conversation's task set and `dropped_tasks`. A task the roster omits is dropped, not
     * finished, and its finished mark and pending updates are forgotten. A row repeating a `task_id` is
     * ignored so every consumer can key by it. A negative `dropped_tasks` is out of contract and drops the
     * frame.
     */
    private fun applyRoster(dto: BackgroundTaskRosterPayloadDto) {
        if (dto.droppedTasks < 0) return
        val conversationId = dto.conversationId
        val previous =
            mutableRosters.value[conversationId]
                ?.tasks
                .orEmpty()
                .associateBy { it.taskId }
        val waiting = pending.remove(conversationId).orEmpty()
        val rows = dto.tasks.distinctBy { it.taskId }
        val taskIds = rows.mapTo(mutableSetOf()) { it.taskId }
        startedTasks.removeAll { (conversation, taskId) -> conversation == conversationId && taskId !in taskIds }
        finished.retainOnly(conversationId, taskIds)
        val tasks = rows.map { row -> rowTask(conversationId, row, previous[row.taskId], waiting[row.taskId]) }
        publish(conversationId, BackgroundTaskRoster(tasks, dto.droppedTasks))
    }

    /** A held record a started frame named is kept whole: its full-length description beats the row's tighter cap. */
    private fun rowTask(
        conversationId: String,
        row: BackgroundTaskRowDto,
        held: BackgroundTask?,
        waiting: Slots?,
    ): BackgroundTask {
        val rowJoin = row.toolCallId.takeIf { it.isNotEmpty() }
        val fromStarted = (conversationId to row.taskId) in startedTasks
        val join = if (fromStarted) held?.toolCallId ?: rowJoin else rowJoin ?: held?.toolCallId
        if (held != null && fromStarted) {
            return held.copy(toolCallId = join).withSlots(conversationId, held.slots())
        }
        return task(
            conversationId = conversationId,
            taskId = row.taskId,
            toolCallId = join,
            taskType = row.taskType,
            description = row.description,
            truncatedFields = row.truncatedFields,
            slots = held?.slots() ?: waiting ?: Slots(),
        )
    }

    private fun task(
        conversationId: String,
        taskId: String,
        toolCallId: String?,
        taskType: String,
        description: String,
        truncatedFields: List<String>?,
        slots: Slots,
    ): BackgroundTask {
        val isFinished = slots.finish != null || finished.contains(conversationId, taskId)
        return BackgroundTask(
            taskId = taskId,
            toolCallId = toolCallId,
            taskType = taskType,
            description = description,
            truncatedFields = truncatedFields,
            latestUpdate = slots.latestUpdate,
            finish = slots.finish,
            isFinished = isFinished,
            progress = if (isFinished) null else slots.progress,
        )
    }

    private fun takePending(
        conversationId: String,
        taskId: String,
    ): Slots {
        val byTask = pending[conversationId] ?: return Slots()
        val slots = byTask[taskId] ?: return Slots()
        val rest = byTask - taskId
        if (rest.isEmpty()) pending.remove(conversationId) else pending[conversationId] = rest
        return slots
    }

    private fun publish(
        conversationId: String,
        roster: BackgroundTaskRoster,
    ) {
        mutableRosters.update { it + (conversationId to roster) }
    }

    private fun BackgroundTask.slots(): Slots = Slots(latestUpdate, finish, progress)

    private fun BackgroundTask.withSlots(
        conversationId: String,
        slots: Slots,
    ): BackgroundTask {
        val isFinished = slots.finish != null || finished.contains(conversationId, taskId)
        return copy(
            latestUpdate = slots.latestUpdate,
            finish = slots.finish,
            isFinished = isFinished,
            progress = if (isFinished) null else slots.progress,
        )
    }

    /** A terminal update also drops the progress: a finished task carries none. */
    private fun Slots.with(
        update: BackgroundTaskUpdate,
        terminal: Boolean,
    ): Slots = if (terminal) copy(finish = update, progress = null) else copy(latestUpdate = update)
}
