package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.ThinkingProgressPayloadDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * The thinking-progress reading for every conversation on one connection (#801): its state, its decoder
 * and its read for the `thinking_progress` event, split out of [RemoteConversationRepository] so each
 * status event lives in its own file. The repository keeps the routing: its `onInbound` arm calls [apply]
 * only behind the negotiated `interactive` gate. The wire carries no falling edge, so the repository calls
 * [clear] from the two arms that end a reading: the conversation's `turn_end` and its
 * `session_transition`.
 *
 * One instance per repository, and a fresh repository per connection (#351), so the state is
 * connection-scoped exactly as it was when it lived in the repository.
 */
internal class ThinkingProgressProjection {
    /**
     * `conversationId -> latest thinking-progress reading` (#801) — how far that conversation's current
     * reasoning has got. A payload-carrying `Map`, [ApiRetryProjection] / [ModelMenuProjection.modelMenusByConversation]'s
     * shape rather than [CompactingProjection]' bare `Set`, for the reason #593 chose it: the wire
     * carries a reading, and membership cannot represent one.
     *
     * **The write is a pure replace, and that is the mechanism rather than a convention.** Each frame
     * stores its own reading with `it + (id to reading)`; the prior value is never read, so there is
     * nowhere for a running maximum, a difference between readings, or a truthiness gate to live. That
     * matters here more than on any sibling: the reading **restarts near zero at every inference-request
     * boundary**, repeatedly inside one turn, so a merge-shaped write is exactly where a well-meaning
     * monotonicity guard would appear and silently pin the reading at a stale peak.
     *
     * Three writers, all on the repository's single inbound collector so no two race: the `thinking_progress`
     * arm stores a reading, and the `turn_end` and `session_transition` arms each remove a key. The
     * removals are the **only** clears, because the wire has no falling edge of its own — receiving a
     * frame neither opens nor closes a turn. Removal of an absent key is a no-op and [MutableStateFlow]
     * conflates the equal map, so a turn ending on a conversation with no reading emits nothing.
     * [observe] fans out from it.
     *
     * Connection-scoped in-memory state — a fresh repository per connection (#351) starts empty, so a
     * reading **never survives a reconnect**, which is a correctness requirement and not merely tidiness:
     * the daemon re-asserts no `thinking_progress` on connect, so a held one would report the depth of a
     * think that has since finished.
     */
    private val thinkingProgressByConversation = MutableStateFlow<Map<String, ThinkingProgress>>(emptyMap())

    /**
     * Apply one `thinking_progress` envelope. Called only from the repository's `interactive`-gated arm; the
     * reasoning below was written for that arm and moved here with it.
     */
    fun apply(envelope: Envelope) {
        // How far this conversation's reasoning has got (#801). Same `interactive` gate as the
        // live-session / `stall` / `queue_state` / `api_retry` / `compacting` siblings: a
        // non-interactive phone never decodes a spurious `thinking_progress` from a buggy/hostile
        // daemon that ignored the server-side fan-out gate (fail-closed, defence in depth). One
        // unconditional REPLACE of this conversation's entry, leaving every other conversation
        // untouched (AC #3) — and the replace is load-bearing rather than incidental: the reading
        // is NOT monotonic (it restarts near zero at every inference-request boundary, several
        // times inside one turn), so it is carried verbatim with no max guard, no difference
        // against the prior reading, and no truthiness check that would read a legitimate `0`
        // restart as absence (AC #2). A malformed payload decodes to null and is dropped so the
        // single inbound consumer survives (AC #1).
        //
        // There is deliberately NO clearing branch here: unlike `compacting` / `api_retry` the
        // wire carries no edge, so the clears live on the `turn_end` and `session_transition`
        // arms instead. This frame opens and closes no turn — it carries no turn_id, the daemon
        // emits it during an inference request that may not have produced assistant content yet,
        // and the turn's thinking state is already `turn_state`'s.
        //
        // Like the `queue_state` / `api_retry` / `compacting` siblings and unlike the
        // live-session arm, this folds no thread row and touches stalledConversations in NEITHER
        // direction (AC #4). Not raising one is the wire contract's explicit rule — the rate
        // bound means a quiet window is not a stall, and the PTY surface emits none of these at
        // all, so nothing here may infer a stall from a gap. Not clearing one is the `compacting`
        // rule for the same reason it exists there: a reading is claude busy, not turn forward
        // progress, and clearing a stall here would let a daemon suppress the phone's stall
        // indicator by emitting these frames. Drop silently — nothing here logs the payload (a
        // logged conversation_id is a cross-conversation correlation leak).
        decodeThinkingProgress(envelope)?.let { (conversationId, reading) ->
            thinkingProgressByConversation.update { it + (conversationId to reading) }
        }
    }

    /**
     * Drop [conversationId]'s reading (#801). Called on that conversation's turn end and on its session
     * transition, the only clears this reading has; removing an absent key is a no-op.
     */
    fun clear(conversationId: String) {
        thinkingProgressByConversation.update { it - conversationId }
    }

    /**
     * How far [conversationId]'s current reasoning has got (#801), a pure cold projection of the shared
     * [thinkingProgressByConversation] `StateFlow`. Issues no request — rides the live
     * `thinking_progress` frames. An absent key is `null`, which is **no reading**: a normal resting
     * state covering a conversation this connection heard no frame for, the window before the first
     * frame, and the state after a clear — never a statement that claude is not thinking, since absence
     * proves nothing on this wire.
     *
     * [distinctUntilChanged] suppresses only value-*identical* re-emissions, so a `thinking_progress`
     * for **another** conversation does not re-emit this flow. It is **not** a monotonicity filter and
     * **not** a truthiness gate: a *lower* reading is a different [ThinkingProgress] value and does
     * reach the collector (the [ApiRetryProjection] climbed-counter property, in the opposite direction),
     * and so does a reading of `0` — precisely what a membership `Set` could not express. A
     * value-identical repeat costs a consumer nothing and leaves the held reading exactly what the
     * daemon sent.
     *
     * A `StateFlow` always has a current value, so every collector (including a `flatMapLatest`
     * re-subscription through the facade) receives the current reading (`null` until a frame lands) on
     * subscription; the one inbound consumer fans out to unlimited collectors.
     */
    fun observe(conversationId: String): Flow<ThinkingProgress?> =
        thinkingProgressByConversation.map { it[conversationId] }.distinctUntilChanged()

    /**
     * Decode one v2 `thinking_progress` envelope (#801) to its routing conversation id and the
     * [ThinkingProgress] reading, or **null** when it cannot be read. Decodes the untrusted
     * [Envelope.payload] through the single configured [MobileJson]; returning a [Pair] of the routing
     * id and the already-mapped domain value keeps the untrusted wire DTO from escaping this boundary,
     * matching every sibling decoder. The whole body is one `try`/`catch (IllegalArgumentException)`
     * ([kotlinx.serialization.SerializationException] ⊂ [IllegalArgumentException]), so a malformed
     * payload — a missing field, or one whose JSON shape cannot be read as its declared type — yields
     * `null`, dropping the one envelope while the lone inbound collector survives (AC #1).
     *
     * Structural malformation is the **only** null path: both readings are plain [Long]s carried
     * verbatim, so there is no unrecognized *value* to reject and no mapper drop exists here (unlike
     * [RemoteConversationRepository.decodeLiveSessionEvent] / [RemoteConversationRepository.decodeSessionTransition]). In particular a **negative** reading is not
     * rejected — rewriting server data at the decode boundary would diverge from every sibling's
     * carry-verbatim posture, and the wire documents no lower bound to enforce. The domain value is
     * constructed inline rather than by a `toX()` mapper, following [CompactingProjection]: nothing is
     * narrowed or validated, so a mapper would be a ceremonial field copy.
     *
     * Mirrors [StallProjection] / [ApiRetryProjection]'s drop idiom — **nothing here logs the payload** (a
     * logged conversation_id is a cross-conversation correlation leak), and the caught throwable is
     * discarded rather than surfaced, since kotlinx-serialization can quote the offending input in its
     * message.
     */
    private fun decodeThinkingProgress(envelope: Envelope): Pair<String, ThinkingProgress>? =
        try {
            val dto = MobileJson.decodeFromJsonElement<ThinkingProgressPayloadDto>(envelope.payload)
            dto.conversationId to ThinkingProgress(dto.estimatedTokens, dto.estimatedTokensDelta)
        } catch (e: IllegalArgumentException) {
            null
        }
}
