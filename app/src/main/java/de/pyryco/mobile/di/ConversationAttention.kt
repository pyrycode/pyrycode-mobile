package de.pyryco.mobile.di

import de.pyryco.mobile.data.cache.ReadPosition
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.ModalUiState
import de.pyryco.mobile.data.model.QuestionBatch
import de.pyryco.mobile.data.model.batchFor
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.ConversationReadMarks
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What one conversation on one host needs from the operator (#877), as exactly one value. Declared in
 * precedence order: desktop's `resolveConversationStatus` order. A turn that ended Failed or StoppedEarly
 * is an ordinary completed turn here, as on desktop (#1451).
 */
enum class ConversationAttention {
    /** A permission prompt or a clarification batch for this conversation is outstanding. */
    WaitingForAnswer,

    /**
     * A turn is in progress: `turn_state` Thinking or Responding, until Idle or `turn_end`. Or the
     * conversation is busy outside a turn (#1452): stalled, retrying the API, compacting or resetting.
     */
    Running,

    /** A known durable entry exceeds the shared mark, or a legacy local change has not been opened. */
    Unread,

    Idle,
}

/** The one attention state for these facts. Parameter order is the precedence order. */
fun resolveAttention(
    waiting: Boolean,
    running: Boolean,
    unread: Boolean,
): ConversationAttention =
    when {
        waiting -> ConversationAttention.WaitingForAnswer
        running -> ConversationAttention.Running
        unread -> ConversationAttention.Unread
        else -> ConversationAttention.Idle
    }

/** A longer turn id is not a daemon id; it is never counted, so it can never reach storage. */
internal const val MAX_TURN_ID_CHARS = 256

/** Enough recent turns per conversation to recognise a re-delivered one without growing per turn. */
internal const val MAX_COUNTED_TURNS_PER_CONVERSATION = 16

/** Per host; the least recently completed conversation's position is dropped first. */
internal const val MAX_READ_POSITIONS = 1000

/**
 * The attention fold for one host (#877), keyed by that host's conversation ids. Pure: no clock, no I/O
 * and no logging, because every id in it is daemon-authored and used only as an equality key.
 *
 * [positions] is the persisted legacy fallback. [readMarks] holds repository-authored durable facts.
 * [running] and [busy] are live-only, and [counted] holds each
 * conversation's recently completed turn ids so a turn the daemon delivers again counts once.
 */
internal data class HostAttentionState(
    val running: Set<String> = emptySet(),
    val busy: Set<String> = emptySet(),
    val positions: Map<String, ReadPosition> = emptyMap(),
    val counted: Map<String, List<String>> = emptyMap(),
    val readMarks: Map<String, ConversationReadMarks> = emptyMap(),
) {
    /** Folds one live event; [viewing] is whether the operator has [event]'s conversation open now. */
    fun onEvent(
        event: LiveSessionEvent,
        viewing: Boolean,
    ): HostAttentionState {
        val id = event.conversationId
        return when (event) {
            is LiveSessionEvent.TurnState ->
                when (event.phase) {
                    LiveSessionEvent.TurnState.Phase.Idle -> copy(running = running - id)
                    else -> copy(running = running + id)
                }
            is LiveSessionEvent.TurnEnd -> completed(event, viewing)
            else -> this
        }
    }

    private fun completed(
        event: LiveSessionEvent.TurnEnd,
        viewing: Boolean,
    ): HostAttentionState {
        val id = event.conversationId
        val turnId = event.turnId
        val ended = copy(running = running - id)
        if (turnId.isBlank() || turnId.length > MAX_TURN_ID_CHARS || isCounted(id, turnId)) return ended
        val position = ReadPosition(turnId, if (viewing) turnId else positions[id]?.readTurnId)
        return ended.copy(
            positions = if (usesSharedMark(id)) positions else boundedPositions((positions - id) + (id to position)),
            counted = counted + (id to (counted[id].orEmpty() + turnId).takeLast(MAX_COUNTED_TURNS_PER_CONVERSATION)),
        )
    }

    /**
     * [conversationId]'s thread gained a row (#1361). A viewed conversation is already read, and an unread
     * one keeps its position, so a stored turn id stays recognisable to [isCounted]. Otherwise [token], a
     * client-minted value unique to this change, becomes its unread position.
     */
    fun rowsAdded(
        conversationId: String,
        viewing: Boolean,
        token: String,
    ): HostAttentionState {
        val position = positions[conversationId]
        if (usesSharedMark(conversationId) || viewing || position?.unread == true) return this
        val unread = ReadPosition(token, position?.readTurnId)
        return copy(positions = boundedPositions((positions - conversationId) + (conversationId to unread)))
    }

    private fun isCounted(
        id: String,
        turnId: String,
    ): Boolean = turnId in counted[id].orEmpty() || positions[id]?.let { it.completedTurnId == turnId || it.readTurnId == turnId } == true

    /** The operator opened [conversationId]: what it had completed is read. */
    fun opened(conversationId: String): HostAttentionState {
        if (usesSharedMark(conversationId)) return this
        val position = positions[conversationId] ?: return this
        return copy(positions = positions + (conversationId to position.copy(readTurnId = position.completedTurnId)))
    }

    /** The host's busy conversations now (#1452), replacing the previous set: the repository holds the edges. */
    fun withBusy(ids: Set<String>): HostAttentionState = if (ids == busy) this else copy(busy = ids)

    /** The repository already merged these facts; replace them, never infer durable identity locally. */
    fun withReadMarks(marks: Map<String, ConversationReadMarks>): HostAttentionState =
        if (marks == readMarks) this else copy(readMarks = marks)

    private fun usesSharedMark(id: String): Boolean = readMarks[id]?.readUpTo != null

    private fun unread(id: String): Boolean {
        val marks = readMarks[id]
        val confirmed = marks?.readUpTo ?: return positions[id]?.unread == true
        return marks.latestEntryId?.let { it > confirmed } == true
    }

    /** The host's connection is gone, so nothing on it can be seen running or busy. Everything else stays. */
    fun disconnected(): HostAttentionState =
        if (running.isEmpty() && busy.isEmpty()) this else copy(running = emptySet(), busy = emptySet())

    /** Merges positions read back from storage under the live ones: a live completion always wins. */
    fun restored(stored: Map<String, ReadPosition>): HostAttentionState = copy(positions = boundedPositions(stored + positions))

    /**
     * The non-Idle states, keyed by conversation id; a missing id is Idle. A conversation waits while any of
     * the host's outstanding [prompts] belongs to it (#1338, desktop `selectHasOutstandingFor`). A blank-id
     * prompt waits for no row.
     */
    fun resolve(
        prompts: List<ModalUiState.Open>,
        batches: List<QuestionBatch>,
    ): Map<String, ConversationAttention> {
        val prompted = prompts.map { it.conversationId }.filter { it.isNotBlank() }.toSet()
        val ids = running + busy + positions.keys + readMarks.keys + batches.map { it.conversationId } + prompted
        return ids
            .associateWith { id ->
                resolveAttention(
                    waiting = id in prompted || batches.batchFor(id) != null,
                    running = id in running || id in busy,
                    unread = unread(id),
                )
            }.filterValues { it != ConversationAttention.Idle }
    }

    private fun boundedPositions(all: Map<String, ReadPosition>): Map<String, ReadPosition> =
        if (all.size <= MAX_READ_POSITIONS) all else all.entries.drop(all.size - MAX_READ_POSITIONS).associate { it.key to it.value }
}

/**
 * Which conversations the operator has open right now (#877), per host: the thread's viewing signal.
 *
 * Its own small type so a thread destination can hold a view without resolving [HostConversationSource]
 * and the collectors that come with it. The source folds [viewed] into legacy local read positions;
 * modern conversations require confirmed daemon marks. Holds ids only as keys and logs none.
 */
class ConversationViewing {
    private val views = MutableStateFlow<Map<Pair<String, String>, Int>>(emptyMap())

    /** Every (serverId, conversationId) with at least one open view. */
    val viewed: Flow<Set<Pair<String, String>>> = views.map { it.keys }.distinctUntilChanged()

    fun isViewing(
        serverId: String,
        conversationId: String,
    ): Boolean = views.value.containsKey(serverId to conversationId)

    /**
     * Views [conversationId] on [serverId] until the returned handle closes. Views are counted, since two
     * views of one thread can overlap, and closing a handle twice releases its view only once.
     */
    fun view(
        serverId: String,
        conversationId: String,
    ): Closeable {
        if (serverId.isEmpty() || conversationId.isEmpty()) return Closeable {}
        val key = serverId to conversationId
        views.update { it + (key to (it[key] ?: 0) + 1) }
        RelayLog.d { "event=conversation_view_opened" }
        val closed = AtomicBoolean(false)
        return Closeable {
            if (closed.compareAndSet(false, true)) {
                views.update { current ->
                    val remaining = (current[key] ?: 1) - 1
                    if (remaining <= 0) current - key else current + (key to remaining)
                }
                RelayLog.d { "event=conversation_view_closed" }
            }
        }
    }
}
