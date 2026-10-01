package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.LiveSessionEvent.TurnState.Phase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * The turn phase of every conversation on one connection (#1313), desktop's per-conversation `phase`:
 * held here, outside any screen, so a thread opened or returned to mid-turn reads the running turn at once
 * instead of waiting for the next `turn_state`. The repository's live-session arm hands it every decoded
 * [LiveSessionEvent] behind the negotiated `interactive` gate; it keeps only the phase writers.
 *
 * One instance per repository, and a fresh repository per connection (#351), so a reconnect starts every
 * conversation idle until the daemon reports a phase again, the effect of desktop's `reconnected` reset.
 */
internal class TurnPhaseProjection {
    /**
     * The conversations whose latest phase is not idle. Absent means idle, so a conversation never heard
     * from and one whose turn ended read the same. Written only from the repository's single inbound
     * collector; the read-modify-write goes through [MutableStateFlow.update].
     */
    private val phases = MutableStateFlow<Map<String, Phase>>(emptyMap())

    /**
     * Fold one live event into its own conversation's phase: a `turn_state` sets it and a `turn_end`, of
     * any outcome, returns it to idle. Every other event is not a phase change and is ignored.
     */
    fun apply(event: LiveSessionEvent) {
        val next =
            when (event) {
                is LiveSessionEvent.TurnState -> event.phase
                is LiveSessionEvent.TurnEnd -> Phase.Idle
                is LiveSessionEvent.AssistantDelta,
                is LiveSessionEvent.ToolUse,
                is LiveSessionEvent.ToolResult,
                is LiveSessionEvent.ReplayGap,
                -> return
            }
        phases.update { if (next == Phase.Idle) it - event.conversationId else it + (event.conversationId to next) }
    }

    /**
     * [conversationId]'s held phase, [Phase.Idle] until a frame says otherwise. A `StateFlow` upstream, so
     * every collector, including one resubscribing after its screen was away, starts from the current
     * value; [distinctUntilChanged] keeps another conversation's change from re-emitting this one.
     */
    fun observe(conversationId: String): Flow<Phase> = phases.map { it[conversationId] ?: Phase.Idle }.distinctUntilChanged()
}
