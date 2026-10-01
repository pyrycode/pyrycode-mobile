package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.LiveSessionEvent.TurnState.Phase
import de.pyryco.mobile.data.repository.ResetStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * One `Unit` per running → not-running turn transition of any conversation in [events] (#1309), desktop's
 * `createRunConfigRefreshTrigger`. Running is `thinking` or `responding`. The running set belongs to one
 * collection, so a new connection's collection starts empty and a turn that was running when the link
 * dropped cannot end on the next one.
 *
 * The phase is kept per conversation: one host-wide flag would let one conversation's `idle` end another's
 * turn and would fire on a re-asserted `idle`. The conversation id is only a set key. It is never logged and
 * never names what is re-read. `turn_end` is not an edge; the `idle` beside it is.
 */
internal fun turnEndEdges(events: Flow<LiveSessionEvent>): Flow<Unit> =
    flow {
        val running = mutableSetOf<String>()
        events.collect { event ->
            if (event !is LiveSessionEvent.TurnState) return@collect
            if (event.phase == Phase.Thinking || event.phase == Phase.Responding) {
                running += event.conversationId
            } else if (running.remove(event.conversationId)) {
                emit(Unit)
            }
        }
    }

/** One `Unit` each time [resetting] goes from a running reset to none (#1309). */
internal fun resetEndEdges(resetting: Flow<ResetStatus?>): Flow<Unit> =
    flow {
        var wasResetting = false
        resetting.collect { status ->
            if (wasResetting && status == null) emit(Unit)
            wasResetting = status != null
        }
    }
