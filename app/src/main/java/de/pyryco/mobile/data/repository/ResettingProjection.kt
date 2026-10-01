package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.ResettingPayloadDto
import de.pyryco.mobile.data.network.toStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * The reset-phase reading for every conversation on one connection (#871): its state, its decoder and its
 * read for the `resetting` event, in its own file like every other status event. The repository keeps the
 * routing: its `onInbound` arm calls [apply] only behind the negotiated `interactive` gate, and its
 * `session_transition` arm calls [clear].
 *
 * One instance per repository, and a fresh repository per connection (#351), so the state is
 * connection-scoped.
 */
internal class ResettingProjection {
    /**
     * `conversationId -> where its running reset is` (#871). A payload-carrying `Map`, the
     * [ThinkingProgressProjection] shape rather than [CompactingProjection]'s bare `Set`: the wire carries a
     * phase and a handoff outcome, and membership cannot represent either.
     *
     * **A rising edge replaces, it never stacks.** One reset sends two rising edges before its falling edge,
     * and the second is a phase change of the same reset, so there is nothing to count. Three writers, all on
     * the repository's single inbound collector so no two race: a rising edge stores its reading, and the
     * falling edge and the conversation's `session_transition` each remove the key. Removing an absent key is
     * a no-op and [MutableStateFlow] conflates the equal map, so a falling edge with no prior rising edge
     * emits nothing. [observe] fans out from it.
     *
     * Connection-scoped in-memory state — a fresh repository per connection starts empty, so a reading never
     * survives a reconnect.
     */
    private val resetByConversation = MutableStateFlow<Map<String, ResetStatus>>(emptyMap())

    /** Apply one `resetting` envelope. Called only from the repository's `interactive`-gated arm. */
    fun apply(envelope: Envelope) {
        // Same `interactive` gate as the `compacting` / `thinking_progress` siblings: a non-interactive
        // phone never decodes a spurious `resetting` from a daemon that ignored the server-side fan-out
        // gate (fail-closed, defence in depth). A malformed payload, or a rising edge with a token outside
        // the closed sets, decodes to null and is dropped so the single inbound consumer survives, leaving
        // any prior reading standing. The edge branch lives in the decoder's `null` status — a falling edge
        // — rather than in a mapper: the falling edge's strings are meaningless and must not gate the clear.
        //
        // Like the `compacting` sibling this folds no thread row and touches no stall in EITHER direction.
        // A reset is the daemon's own routine, not claude's turn forward progress, so clearing a stall here
        // would let a daemon suppress the phone's stall indicator by emitting these frames. Drop silently —
        // nothing here logs the payload (a logged conversation_id is a cross-conversation correlation leak).
        decodeResetting(envelope)?.let { (conversationId, status) ->
            resetByConversation.update { if (status != null) it + (conversationId to status) else it - conversationId }
        }
    }

    /**
     * Drop [conversationId]'s reading (#871). Called on that conversation's session transition: the daemon
     * emits `restarting` before it respawns claude, so no rising edge can follow the transition. Removing an
     * absent key is a no-op.
     */
    fun clear(conversationId: String) {
        resetByConversation.update { it - conversationId }
    }

    /**
     * Where [conversationId]'s running reset is (#871), a pure cold projection of [resetByConversation].
     * Issues no request. An absent key is `null`: no reset running. [distinctUntilChanged] suppresses only
     * value-identical re-emissions, so another conversation's frame does not re-emit this flow while the
     * `wrapping_up` → `restarting` phase change, a different [ResetStatus], does. A `StateFlow` always has a
     * current value, so every collector (including a `flatMapLatest` re-subscription through the facade)
     * receives the current reading on subscription.
     */
    fun observe(conversationId: String): Flow<ResetStatus?> = resetByConversation.map { it[conversationId] }.distinctUntilChanged()

    /** Every conversation resetting right now (#1452), for the host's list. Same edges as [observe]. */
    fun observeIds(): Flow<Set<String>> = resetByConversation.map { it.keys }.distinctUntilChanged()

    /**
     * Decode one v2 `resetting` envelope (#871) to its routing conversation id and its reading, or **null**
     * when the envelope must be dropped. A non-null result carries a **`null` status for the falling edge**
     * and a [ResetStatus] for a recognised rising edge. Decodes the untrusted [Envelope.payload] through the
     * single configured [MobileJson]; returning the routing id and an already-narrowed domain value keeps the
     * untrusted wire DTO from escaping this boundary. The whole body is one `try`/`catch
     * (IllegalArgumentException)` ([kotlinx.serialization.SerializationException] ⊂
     * [IllegalArgumentException]), so a malformed payload yields `null`; the caught throwable is discarded,
     * since kotlinx-serialization can quote the offending input in its message.
     */
    private fun decodeResetting(envelope: Envelope): Pair<String, ResetStatus?>? =
        try {
            val dto = MobileJson.decodeFromJsonElement<ResettingPayloadDto>(envelope.payload)
            when {
                !dto.active -> dto.conversationId to null
                else -> dto.toStatus()?.let { dto.conversationId to it }
            }
        } catch (e: IllegalArgumentException) {
            null
        }
}
