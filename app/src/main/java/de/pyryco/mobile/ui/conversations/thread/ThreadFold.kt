package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant

// ---- #337: live assistant-delta accumulation (pure) ---------------------------------------------

/** The merged input to the thread fold: the finished projection plus the live structured stream. */
internal sealed interface ThreadInput {
    /** A finished-message projection from `observeMessages` (#313). */
    data class Finished(
        val items: List<ThreadItem>,
    ) : ThreadInput

    /** One decoded live structured event from `liveSessionEvents` (#385). */
    data class Live(
        val event: LiveSessionEvent,
    ) : ThreadInput
}

/** The in-flight streaming turn being accumulated, or `null` between turns. */
internal data class StreamingTurn(
    val turnId: String,
    /** Accumulated [LiveSessionEvent.AssistantDelta] text, in `seq` order. */
    val text: String,
    /** Ordering/dup guard for the current turn — deltas with `seq <= lastSeq` are ignored. */
    val lastSeq: Int,
    /** `turn_end` seen → render `isStreaming = false` (settled) but keep the item until the finished message. */
    val ended: Boolean,
    /** Assistant [ThreadItem.MessageItem] ids present when the turn started — the finalise oracle. */
    val baselineAssistantIds: Set<String>,
)

/** The fold accumulator: the latest finished projection plus the current [StreamingTurn]. */
internal data class ThreadFold(
    val finished: List<ThreadItem>,
    val stream: StreamingTurn?,
)

/**
 * Folds one [ThreadInput] into the next [ThreadFold]. Pure and side-effect-free — **no logging** of
 * delta text or any payload field (the verbatim text is untrusted/sensitive; see [LiveSessionEvent]).
 *
 * The conversation-id guard is checked **first** for every live event (AC #2/#3 confidentiality —
 * mirrors `thinkingTransition`). Finalise is structural, not id-correlated: the streaming item is
 * dropped the moment the finished list gains an assistant message whose id was not present when the
 * turn started — i.e. this turn's persisted message has arrived, whether before or after `turn_end`.
 */
internal fun ThreadFold.reduce(
    input: ThreadInput,
    conversationId: String,
): ThreadFold =
    when (input) {
        is ThreadInput.Finished -> {
            val turnPersisted =
                stream != null &&
                    input.items.any {
                        it is ThreadItem.MessageItem &&
                            it.message.role == Role.Assistant &&
                            it.message.id !in stream.baselineAssistantIds
                    }
            ThreadFold(finished = input.items, stream = if (turnPersisted) null else stream)
        }
        is ThreadInput.Live -> reduceLive(input.event, conversationId)
    }

private fun ThreadFold.reduceLive(
    event: LiveSessionEvent,
    conversationId: String,
): ThreadFold {
    if (event.conversationId != conversationId) return this
    return when (event) {
        is LiveSessionEvent.AssistantDelta -> reduceDelta(event)
        is LiveSessionEvent.TurnEnd -> {
            val current = stream
            if (current != null && current.turnId == event.turnId) {
                copy(stream = current.copy(ended = true))
            } else {
                this
            }
        }
        is LiveSessionEvent.TurnState,
        is LiveSessionEvent.ToolUse,
        is LiveSessionEvent.ToolResult,
        is LiveSessionEvent.ReplayGap,
        -> this
    }
}

private fun ThreadFold.reduceDelta(delta: LiveSessionEvent.AssistantDelta): ThreadFold {
    val current = stream
    return when {
        // A new turn (or first delta) — supersedes any unfinalised prior turn.
        current == null || current.turnId != delta.turnId ->
            copy(
                stream =
                    StreamingTurn(
                        turnId = delta.turnId,
                        text = delta.text,
                        lastSeq = delta.seq,
                        ended = false,
                        baselineAssistantIds = finished.assistantIds(),
                    ),
            )
        // In-order delta for the current turn — append.
        delta.seq > current.lastSeq ->
            copy(stream = current.copy(text = current.text + delta.text, lastSeq = delta.seq))
        // Out-of-order or replayed delta — ignore (AC #2).
        else -> this
    }
}

/** Renders the fold to thread rows: the finished projection, plus the streaming turn appended last. */
internal fun ThreadFold.render(): List<ThreadItem> {
    val turn = stream ?: return finished
    // Key-uniqueness guard (#425): the daemon may set `turnId == message_id`, so the synthetic's id can
    // equal a persisted message's id and the structural finalise can miss the collision (when the colliding
    // id was already in the turn's baseline). The thread keys every MessageItem as "msg:<id>", so two items
    // sharing an id crash LazyColumn. Append the synthetic only when no finished message already carries
    // this turn's id — render-time, source-independent, total over every interleaving.
    if (finished.any { it is ThreadItem.MessageItem && it.message.id == turn.turnId }) return finished
    val lastMessage = finished.lastOrNull { it is ThreadItem.MessageItem } as? ThreadItem.MessageItem
    val synthetic =
        Message(
            id = turn.turnId,
            sessionId = lastMessage?.message?.sessionId.orEmpty(),
            role = Role.Assistant,
            content = turn.text,
            timestamp = lastMessage?.message?.timestamp ?: Instant.fromEpochMilliseconds(0),
            isStreaming = !turn.ended,
        )
    return finished + ThreadItem.MessageItem(synthetic)
}

private fun List<ThreadItem>.assistantIds(): Set<String> =
    asSequence()
        .filterIsInstance<ThreadItem.MessageItem>()
        .filter { it.message.role == Role.Assistant }
        .map { it.message.id }
        .toSet()
