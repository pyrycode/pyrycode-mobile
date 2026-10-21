package de.pyryco.mobile.di

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

/** Per-host transient activity and daemon-owned unread facts. */
internal data class HostAttentionState(
    val running: Set<String> = emptySet(),
    val busy: Set<String> = emptySet(),
    val readMarks: Map<String, ConversationReadMarks> = emptyMap(),
) {
    fun onEvent(event: LiveSessionEvent): HostAttentionState {
        val phase = (event as LiveSessionEvent.TurnState).phase
        return copy(
            running =
                if (phase ==
                    LiveSessionEvent.TurnState.Phase.Idle
                ) {
                    running - event.conversationId
                } else {
                    running + event.conversationId
                },
        )
    }

    fun withBusy(ids: Set<String>): HostAttentionState = if (ids == busy) this else copy(busy = ids)

    fun withReadMarks(marks: Map<String, ConversationReadMarks>): HostAttentionState =
        if (marks == readMarks) this else copy(readMarks = marks)

    fun disconnected(): HostAttentionState =
        if (running.isEmpty() && busy.isEmpty()) this else copy(running = emptySet(), busy = emptySet())

    fun resolve(
        prompts: List<ModalUiState.Open>,
        batches: List<QuestionBatch>,
    ): Map<String, ConversationAttention> {
        val prompted = prompts.map { it.conversationId }.filter { it.isNotBlank() }.toSet()
        val ids = running + busy + readMarks.keys + batches.map { it.conversationId } + prompted
        return ids
            .associateWith { id ->
                val marks = readMarks[id]
                resolveAttention(
                    waiting = id in prompted || batches.batchFor(id) != null,
                    running = id in running || id in busy,
                    unread = marks?.readUpTo?.let { read -> marks.unreadWatermark?.let { it > read } } == true,
                )
            }.filterValues { it != ConversationAttention.Idle }
    }
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
