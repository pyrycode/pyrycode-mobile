package de.pyryco.mobile.data.model

/** Session-owned transient phase; content comes from daemon-built thread items. */
sealed interface LiveSessionEvent {
    val conversationId: String

    /**
     * A coarse turn-lifecycle transition (`turn_state`). Carries no `turn_id` — the wire payload is
     * `{conversation_id, state}` only. [Phase] is exactly the three documented states; an
     * unrecognized or absent wire `state` is dropped at the mapper rather than surfaced (AC #3).
     */
    data class TurnState(
        override val conversationId: String,
        val phase: Phase,
    ) : LiveSessionEvent {
        /** The three documented `turn_state.state` values (`thinking`/`responding`/`idle`). */
        enum class Phase { Thinking, Responding, Idle }
    }
}
