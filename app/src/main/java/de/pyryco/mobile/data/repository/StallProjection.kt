package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.StallPayloadDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Stall state for every conversation on one connection (#395): its state, its decoder and its read for the
 * `stall` event, split out of [RemoteConversationRepository] so each status event lives in its own file.
 * The repository keeps the routing: its `onInbound` arm calls [apply] only behind the negotiated
 * `interactive` gate. A live-session event is forward progress, so the repository's live-session arm calls
 * [clear] for every event it decodes: the wire carries onset only.
 *
 * One instance per repository, and a fresh repository per connection (#351), so the state is
 * connection-scoped exactly as it was when it lived in the repository.
 */
internal class StallProjection {
    /**
     * The set of conversation ids currently in a stall (#395) — membership = stalled. Written **only**
     * from the repository's single inbound collector: a `stall` envelope adds its id (onset), and any
     * successfully-decoded forward-progress [LiveSessionEvent] removes its conversation (clearing —
     * the wire carries no clearing edge, so recovery is inferred from forward progress). Single writer
     * on the one collector coroutine, so onset and clearing never race; the atomic
     * [MutableStateFlow.update] matches the sibling projections' memory-visibility posture.
     * [observe] fans out from it. Connection-scoped in-memory state — a fresh repository per
     * connection (#351) starts empty, so a stall never survives a reconnect (it is re-derived from the
     * live stream). A stall is a transient "right now" condition, not durable state.
     */
    private val stalledConversations = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Apply one `stall` envelope. Called only from the repository's `interactive`-gated arm; the
     * reasoning below was written for that arm and moved here with it.
     */
    fun apply(envelope: Envelope) {
        // Stall onset (#395). Same `interactive` gate as the live-session arm: a non-interactive
        // phone never decodes a spurious `stall` from a buggy/hostile daemon that ignored the
        // server-side fan-out gate (fail-closed, defence in depth). A malformed payload decodes
        // to null and is dropped so the single inbound consumer survives (AC #3). The wire is
        // onset-only ({conversation_id}, no clearing edge); re-receipt for an already-stalled
        // conversation is an idempotent Set add. Drop silently — nothing here logs the payload.
        decodeStall(envelope)?.let { conversationId ->
            stalledConversations.update { it + conversationId }
        }
    }

    /**
     * Forward progress ends any active stall for [conversationId] (#395, AC #2). Called by the
     * repository's live-session arm for every decoded event; removing an absent id is a no-op.
     */
    fun clear(conversationId: String) {
        stalledConversations.update { it - conversationId }
    }

    /**
     * Whether [conversationId] is currently stalled (#395), a pure cold projection of the shared
     * [stalledConversations] `StateFlow` (membership = stalled). Issues no request — rides the live
     * interactive stream (onset on a `stall` envelope, clearing on the next forward-progress event).
     * [distinctUntilChanged] means a stall change to **another** conversation does not re-emit this
     * flow. A `StateFlow` always has a current value, so every collector (including a `flatMapLatest`
     * re-subscription through the facade) receives the current state (`false` until a stall lands) on
     * subscription; the one inbound consumer fans out to unlimited collectors.
     */
    fun observe(conversationId: String): Flow<Boolean> = stalledConversations.map { conversationId in it }.distinctUntilChanged()

    /** Every conversation stalled right now (#1452), for the host's list. Same edges as [observe]. */
    fun observeIds(): Flow<Set<String>> = stalledConversations

    /**
     * Decode one v2 `stall` envelope (#395) to its conversation id, or **null** when it cannot be
     * read. Decodes the untrusted [Envelope.payload] through the single configured [MobileJson]; the
     * whole body is one `try`/`catch (IllegalArgumentException)`
     * ([kotlinx.serialization.SerializationException] ⊂ [IllegalArgumentException]), so a malformed
     * payload — a missing or wrong-typed `conversation_id` (AC #3) — yields `null`, dropping the one
     * envelope while the lone inbound collector survives. Mirrors [RemoteConversationRepository.decodeLiveSessionEvent]'s drop
     * idiom — **nothing here logs the payload** (uniform with every other `onInbound` arm).
     */
    private fun decodeStall(envelope: Envelope): String? =
        try {
            MobileJson.decodeFromJsonElement<StallPayloadDto>(envelope.payload).conversationId
        } catch (e: IllegalArgumentException) {
            null
        }
}
