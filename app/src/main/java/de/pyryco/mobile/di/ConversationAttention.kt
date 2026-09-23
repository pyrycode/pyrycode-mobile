package de.pyryco.mobile.di

import de.pyryco.mobile.data.cache.ReadPosition
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.ModalUiState
import de.pyryco.mobile.data.model.QuestionBatch
import de.pyryco.mobile.data.model.batchFor
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.ui.conversations.components.TurnOutcomeReport
import de.pyryco.mobile.ui.conversations.components.turnOutcomeReport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What one conversation on one host needs from the operator (#877), as exactly one value. Declared in
 * precedence order: desktop's `resolveConversationStatus` order with mobile's [Failed] after [Running].
 */
enum class ConversationAttention {
    /** A permission prompt or a clarification batch for this conversation is outstanding. */
    WaitingForAnswer,

    /** A turn is in progress: `turn_state` Thinking or Responding, until Idle or `turn_end`. */
    Running,

    /** The latest turn ended Failed or StoppedEarly per `turnOutcomeReport` (#805), unopened since. */
    Failed,

    /** A turn completed live while the operator was not viewing the conversation. */
    Unread,

    Idle,
}

/** The one attention state for these facts. Parameter order is the precedence order. */
fun resolveAttention(
    waiting: Boolean,
    running: Boolean,
    failed: Boolean,
    unread: Boolean,
): ConversationAttention =
    when {
        waiting -> ConversationAttention.WaitingForAnswer
        running -> ConversationAttention.Running
        failed -> ConversationAttention.Failed
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
 * [positions] is the persisted part. [running] and [failed] are live-only, and [counted] holds each
 * conversation's recently completed turn ids so a turn the daemon delivers again counts once.
 */
internal data class HostAttentionState(
    val running: Set<String> = emptySet(),
    val failed: Set<String> = emptySet(),
    val positions: Map<String, ReadPosition> = emptyMap(),
    val counted: Map<String, List<String>> = emptyMap(),
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
                    // A new turn starting is what retires the previous turn's failure.
                    else -> copy(running = running + id, failed = failed - id)
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
        val kind = turnOutcomeReport(event)?.kind
        val failure = !viewing && (kind == TurnOutcomeReport.Kind.Failed || kind == TurnOutcomeReport.Kind.StoppedEarly)
        val position = ReadPosition(turnId, if (viewing) turnId else positions[id]?.readTurnId)
        return ended.copy(
            failed = if (failure) failed + id else failed - id,
            positions = boundedPositions((positions - id) + (id to position)),
            counted = counted + (id to (counted[id].orEmpty() + turnId).takeLast(MAX_COUNTED_TURNS_PER_CONVERSATION)),
        )
    }

    private fun isCounted(
        id: String,
        turnId: String,
    ): Boolean = turnId in counted[id].orEmpty() || positions[id]?.let { it.completedTurnId == turnId || it.readTurnId == turnId } == true

    /** The operator opened [conversationId]: what it had completed is read, and its failure is seen. */
    fun opened(conversationId: String): HostAttentionState {
        val position = positions[conversationId]
        if (position == null && conversationId !in failed) return this
        return copy(
            failed = failed - conversationId,
            positions = position?.let { positions + (conversationId to it.copy(readTurnId = it.completedTurnId)) } ?: positions,
        )
    }

    /** The host's connection is gone, so no turn on it can be seen running. Everything else stays. */
    fun disconnected(): HostAttentionState = if (running.isEmpty()) this else copy(running = emptySet())

    /** Merges positions read back from storage under the live ones: a live completion always wins. */
    fun restored(stored: Map<String, ReadPosition>): HostAttentionState = copy(positions = boundedPositions(stored + positions))

    /** The non-Idle states, keyed by conversation id; a missing id is Idle. A blank-id prompt waits for no row. */
    fun resolve(
        modal: ModalUiState,
        batches: List<QuestionBatch>,
    ): Map<String, ConversationAttention> {
        val prompted = (modal as? ModalUiState.Open)?.conversationId?.takeIf { it.isNotBlank() }
        val ids = running + failed + positions.keys + batches.map { it.conversationId } + listOfNotNull(prompted)
        return ids
            .associateWith { id ->
                resolveAttention(
                    waiting = id == prompted || batches.batchFor(id) != null,
                    running = id in running,
                    failed = id in failed,
                    unread = positions[id]?.unread == true,
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
 * and the collectors that come with it. The source folds [viewed] into each host's attention: a viewed
 * conversation is opened, and a turn completing in it is read. Holds ids only as keys and logs none.
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
