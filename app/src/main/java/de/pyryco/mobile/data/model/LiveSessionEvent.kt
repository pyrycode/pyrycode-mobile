package de.pyryco.mobile.data.model

/**
 * A decoded v2 structured live-session stream event (#385): the typed in-process form of one of the
 * five **binary → phone** interactive envelopes — `turn_state`, `assistant_delta`, `tool_use`,
 * `tool_result`, `turn_end` — that the daemon emits **only** to a phone whose `interactive`
 * capability was echoed in `hello_ack` (pyrycode#607 wire types, #616 capability-gated fan-out).
 *
 * Part of the Phase 2 structured-streaming exit-gate (pyrycode#596, ADR 025). The decode boundary
 * lives in `data/network` (the `…PayloadDto.toEvent()` mappers); this is the **portable** typed
 * surface consumers read off `RemoteConversationRepository.liveSessionEvents`. Decode-only: nothing
 * here correlates `tool_use`↔`tool_result`, accumulates `assistant_delta` text, or drives a
 * turn-lifecycle state machine — those are the consumer slices (the thinking indicator, the tool-use
 * timeline, live assistant text, #386/#387/#337).
 *
 * Every event carries [conversationId] (every wire payload does) so a consumer can route per
 * conversation without a `when`. The free-form text fields ([AssistantDelta.text],
 * [ToolUse.inputSummary], [ToolResult.resultSummary], [TurnEnd.stopReason]) are carried **verbatim**
 * — the decode seam neither trims, parses, nor sanitizes them. They may contain sensitive
 * user/session content, so a rendering consumer MUST treat them as inert data (not
 * markup/HTML/active content) and own its own output-encoding at render time.
 *
 * The family also carries one **control-derived** signal beyond the five binary→phone render
 * envelopes: [ReplayGap] (#417), derived from the daemon's `resync` marker. It is not itself a render
 * event and carries no verbatim user/tool text — only the conversation id the gap concerns.
 *
 * Pure data, no Android imports — kept portable per CLAUDE.md (`data/` is a Compose Multiplatform
 * walk-back surface). The subtype names mirror the wire `type` strings 1:1.
 */
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

    /** Incremental assistant text (`assistant_delta`). [turnId] and [seq] identify one lane; each lane
     * starts its counter independently. [parentToolUseId] is verbatim inert grouping data, empty for the
     * main lane. It grants no authority and must never be logged or interpreted as a path or command. */
    data class AssistantDelta(
        override val conversationId: String,
        val turnId: String,
        val seq: Int,
        val text: String,
        val parentToolUseId: String = "",
    ) : LiveSessionEvent

    /**
     * A tool invocation (`tool_use`). [toolUseId] correlates this call with its later [ToolResult]
     * (correlation itself is a consumer concern). [inputSummary] is a server-authored précis.
     *
     * [parentToolUseId] (#810) is the `Agent`/`Task` call that spawned the subagent making this call —
     * `""` means the main thread. [input] is the tool input's own top-level fields, verbatim. Both are
     * inert display and grouping data the daemon neither resolved nor validated: a value may be a
     * traversing path or a literal shell command line, and the parent may name a call never seen here.
     */
    data class ToolUse(
        override val conversationId: String,
        val turnId: String,
        val toolUseId: String,
        val name: String,
        val inputSummary: String,
        val parentToolUseId: String = "",
        val input: Map<String, String> = emptyMap(),
    ) : LiveSessionEvent

    /** A tool invocation's result (`tool_result`), matched to its [ToolUse] by [toolUseId].
     *  [resultSummary] is a server-authored précis (not the raw output). [parentToolUseId] is as on
     *  [ToolUse] (#810). [resultDetail] (#1316) is the daemon's count of what the call returned, verbatim,
     *  `""` when there is none: display text only, never parsed into a number, logged or linked. */
    data class ToolResult(
        override val conversationId: String,
        val turnId: String,
        val toolUseId: String,
        val isError: Boolean,
        val resultSummary: String,
        val parentToolUseId: String = "",
        val resultDetail: String = "",
    ) : LiveSessionEvent

    /**
     * End of a turn (`turn_end`). [stopReason] is the daemon's classification, carried as a plain string
     * (wire values include `end_turn`/`max_tokens`/`max_turn_requests`/`refusal`/`cancelled`); consumers
     * map it.
     *
     * The four defaulted fields (#805) are claude's own account of the stop, each independent of the
     * others: [isError] is claude's flag and is never implied by [outcome] (`outcome = "success"` with
     * `isError = true` is a real failed turn), and [outcome] may disagree with [stopReason] by design.
     * [errorCategory] is claude's report of an API error, not a verified account state. `""` / `false`
     * means the daemon did not say. [outcome], [terminalReason] and [errorCategory] are claude-authored
     * and unsanitized: render them only as inert, attributed text.
     *
     * [costUsdTotal] (#1346) is Claude's own estimate of what the **whole session** has cost so far, which
     * the daemon does not verify: `null` when not reported or not a number, otherwise the number verbatim
     * (zero, negative or infinite included). Attribute it to Claude when shown, and never log it.
     */
    data class TurnEnd(
        override val conversationId: String,
        val turnId: String,
        val stopReason: String,
        val outcome: String = "",
        val isError: Boolean = false,
        val terminalReason: String = "",
        val errorCategory: String = "",
        val costUsdTotal: Double? = null,
    ) : LiveSessionEvent

    /**
     * A replay gap (#417), surfaced when the daemon emits a `resync` marker because the phone's
     * advertised replay position aged out of its bounded ring, so gap-free in-ring replay was
     * impossible. Carries only [conversationId] (the conversation the gap concerns) — no turn / event /
     * text content. An observable signal a UI layer can later render (e.g. a "messages may be missing"
     * affordance); this slice does not render it. Unlike the five wire-event subtypes it is
     * control-derived, not a decoded render envelope.
     */
    data class ReplayGap(
        override val conversationId: String,
    ) : LiveSessionEvent
}
