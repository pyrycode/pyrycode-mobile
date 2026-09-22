package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.CompactingPayloadDto
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Context-compaction status for every conversation on one connection (#596): its state, its decoder and
 * its read for the `compacting` event, split out of [RemoteConversationRepository] so each status event
 * lives in its own file. The repository keeps the routing: its `onInbound` arm calls [apply] only behind
 * the negotiated `interactive` gate.
 *
 * One instance per repository, and a fresh repository per connection (#351), so the state is
 * connection-scoped exactly as it was when it lived in the repository.
 */
internal class CompactingProjection {
    /**
     * The set of conversation ids claude is currently auto-compacting (#596) — membership = compacting.
     * Written **only** from the repository's single inbound collector: a `compacting` envelope's rising edge
     * adds its id and its falling edge removes it. Single writer on the one collector coroutine, so the
     * rising and falling edges never race; the write is a genuine **read-modify-write** on the set
     * (`it + id` / `it - id`), unlike [ApiRetryProjection]'s pure replace, so the atomic
     * [MutableStateFlow.update] is load-bearing rather than stylistic — a
     * `.value = compactingConversations.value + id` formulation would open a real check-then-mutate
     * window. [observe] fans out from it. Connection-scoped in-memory state — a fresh
     * repository per connection (#351) starts empty, so a compaction state never survives a reconnect
     * (it is re-derived from the live stream). Compaction is a transient "right now" condition, not
     * durable state.
     *
     * The one place [StallProjection]' shape does not transfer: removal here is driven by an
     * **explicit wire falling edge**, not inferred from the next forward-progress event.
     */
    private val compactingConversations = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Apply one `compacting` envelope. Called only from the repository's `interactive`-gated arm; the
     * reasoning below was written for that arm and moved here with it.
     */
    fun apply(envelope: Envelope) {
        // Context-compaction status (#596). Same `interactive` gate as the live-session /
        // `stall` / `queue_state` / `api_retry` siblings: a non-interactive phone never decodes a
        // spurious `compacting` from a buggy/hostile daemon that ignored the server-side fan-out
        // gate (fail-closed, defence in depth). One membership transition for this conversation,
        // leaving every other conversation untouched (AC #3) — and here the `if (active)` belongs
        // in the arm rather than a mapper: unlike `api_retry` there is no mapper to own the edge
        // semantics (the two wire fields already are the domain shape), so the membership
        // transition *is* the edge, expressed exactly once. `it - conversationId` on an absent id
        // is a no-op, so a falling edge with no prior rising edge is harmlessly inert, and a
        // repeated rising edge is an idempotent Set add. A malformed payload decodes to null and
        // is dropped so the single inbound consumer survives (AC #5). Like the `queue_state` /
        // `api_retry` siblings and unlike the live-session arm, this folds no thread row and does
        // NOT clear a stall — compaction is claude busy elsewhere, not turn forward progress, and
        // clearing a stall here would let a daemon suppress the phone's stall indicator by
        // emitting `compacting` frames. Drop silently — nothing here logs the payload (a logged
        // conversation_id is a cross-conversation correlation leak).
        decodeCompacting(envelope)?.let { (conversationId, active) ->
            compactingConversations.update { if (active) it + conversationId else it - conversationId }
        }
    }

    /**
     * Whether claude is currently auto-compacting [conversationId]'s context (#596), a pure cold
     * projection of the shared [compactingConversations] `StateFlow` (membership = compacting). Issues no
     * request — rides the live `compacting` edges. Membership over an empty set gives
     * not-compacting-until-the-first-frame with no default needed: "absent" and "not compacting" are the
     * same thing by construction, tidier than [ApiRetryProjection]'s stored falling edge.
     * [distinctUntilChanged] suppresses only value-*identical* re-emissions, so a `compacting` for
     * **another** conversation does not re-emit this flow and a repeated rising edge is genuinely nothing
     * new — #593's no-dedup hazard does not transfer here, because that one existed only to let a
     * climbing counter through and a bool has no intermediate values to collapse. A `StateFlow` always
     * has a current value, so every collector (including a `flatMapLatest` re-subscription through the
     * facade) receives the current state (`false` until a compaction lands) on subscription; the one
     * inbound consumer fans out to unlimited collectors.
     */
    fun observe(conversationId: String): Flow<Boolean> = compactingConversations.map { conversationId in it }.distinctUntilChanged()

    /**
     * Decode one v2 `compacting` envelope (#596) to its conversation id and edge bool, or **null** when
     * it cannot be read. Decodes the untrusted [Envelope.payload] through the single configured
     * [MobileJson]; returning a [Pair] of already-trusted primitives rather than the DTO keeps the
     * untrusted wire type from escaping the boundary, matching every sibling decoder. The whole body is
     * one `try`/`catch (IllegalArgumentException)`
     * ([kotlinx.serialization.SerializationException] ⊂ [IllegalArgumentException]), so a malformed
     * payload — a missing field, or one whose JSON shape cannot be read as its declared type — yields
     * `null`, dropping the one envelope while the lone inbound collector survives (AC #5). Structural
     * malformation is the **only** null path: there is no unrecognized *value* to reject (`active` is a
     * bool), so unlike [RemoteConversationRepository.decodeLiveSessionEvent] no mapper drop exists here. Mirrors [StallProjection] /
     * [ApiRetryProjection]'s drop idiom — **nothing here logs the payload** (uniform with every other
     * `onInbound` arm).
     */
    private fun decodeCompacting(envelope: Envelope): Pair<String, Boolean>? =
        try {
            val dto = MobileJson.decodeFromJsonElement<CompactingPayloadDto>(envelope.payload)
            dto.conversationId to dto.active
        } catch (e: IllegalArgumentException) {
            null
        }
}
