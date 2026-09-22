package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.QueueStatePayloadDto
import de.pyryco.mobile.data.network.toQueue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * The queued-message backlog for every conversation on one connection (#460): its state, its decoder and
 * its read for the `queue_state` event, split out of [RemoteConversationRepository] so each status event
 * lives in its own file. The repository keeps the routing: its `onInbound` arm calls [apply] only behind
 * the negotiated `interactive` gate. [RemoteConversationRepository.dropQueuedMessage] reads [current] to
 * resolve a queued item's echo id before it sends the drop.
 *
 * One instance per repository, and a fresh repository per connection (#351), so the state is
 * connection-scoped exactly as it was when it lived in the repository.
 */
internal class QueueProjection {
    /**
     * `conversationId -> ordered queued-message backlog` (#460) — the messages the daemon has queued
     * while claude is busy, in wire/FIFO order. Written **only** from the single [RemoteConversationRepository] inbound
     * collector: each `queue_state` envelope is a full snapshot that **replaces** that conversation's
     * entry (the wire form of `msgqueue.Snapshot`), leaving every other conversation untouched. Single
     * writer on the one collector coroutine, so snapshots never race; the atomic [MutableStateFlow.update]
     * matches the sibling projections' memory-visibility posture. [observe] fans out from it.
     * Connection-scoped in-memory state — a fresh repository per connection (#351) starts empty, so a
     * backlog never survives a reconnect (it is re-derived from the next live `queue_state`). The backlog
     * is a transient "right now" condition, not durable state.
     */
    private val queuedByConversation = MutableStateFlow<Map<String, List<QueuedMessage>>>(emptyMap())

    /**
     * Apply one `queue_state` envelope. Called only from the repository's `interactive`-gated arm; the
     * reasoning below was written for that arm and moved here with it.
     */
    fun apply(envelope: Envelope) {
        // Queued-backlog snapshot (#460). Same `interactive` gate as the live-session / `stall`
        // siblings: a non-interactive phone never decodes a spurious `queue_state` from a buggy/
        // hostile daemon that ignored the server-side fan-out gate (fail-closed, defence in depth).
        // Each snapshot is the authoritative current backlog (msgqueue.Snapshot), so it FULLY
        // REPLACES this conversation's entry and leaves every other conversation untouched (AC #3);
        // wire array order is preserved verbatim (AC #1). A malformed payload decodes to null and
        // is dropped so the single inbound consumer survives (AC #4). Unlike the live-session arm
        // this folds no thread row and does NOT clear a stall (a backlog is "waiting", not forward
        // progress). Drop silently — queued `text` is user content; nothing here logs the payload.
        decodeQueueState(envelope)?.let { (conversationId, queue) ->
            queuedByConversation.update { it + (conversationId to queue) }
        }
    }

    /**
     * The backlog this connection currently holds for [conversationId], read synchronously by
     * [RemoteConversationRepository.dropQueuedMessage] to resolve an item's echo id before the drop.
     */
    fun current(conversationId: String): List<QueuedMessage> = queuedByConversation.value[conversationId].orEmpty()

    /**
     * Ordered queued-message backlog for [conversationId] (#460), a pure cold projection of the shared
     * [queuedByConversation] `StateFlow`. Issues no request — rides the live `queue_state` snapshots.
     * `orEmpty()` gives empty-until-first-snapshot (AC #2). [distinctUntilChanged] means a `queue_state`
     * for **another** conversation, or a value-identical re-snapshot, does not re-emit this flow (AC #3).
     * A `StateFlow` always has a current value, so every collector (including a `flatMapLatest`
     * re-subscription through the facade) receives the current backlog (empty until one lands) on
     * subscription; the one inbound consumer fans out to unlimited collectors.
     */
    fun observe(conversationId: String): Flow<List<QueuedMessage>> =
        queuedByConversation.map { it[conversationId].orEmpty() }.distinctUntilChanged()

    /**
     * Decode one v2 `queue_state` envelope (#460) to its conversation id and ordered backlog, or
     * **null** when it cannot be read. Decodes the untrusted [Envelope.payload] through the single
     * configured [MobileJson] and maps via `toQueue()`. The whole body is one `try`/`catch
     * (IllegalArgumentException)` ([kotlinx.serialization.SerializationException] ⊂
     * [IllegalArgumentException]), so a malformed payload — a missing/wrong-typed `conversation_id`, a
     * bad item (`queued_msg_id` as a string, missing `text`), or an unparseable item `ts` — yields
     * `null`, dropping the one envelope while the lone inbound collector survives (AC #4). Mirrors
     * [StallProjection]'s drop idiom — **nothing here logs the payload** (queued `text` is user content).
     */
    private fun decodeQueueState(envelope: Envelope): Pair<String, List<QueuedMessage>>? =
        try {
            val dto = MobileJson.decodeFromJsonElement<QueueStatePayloadDto>(envelope.payload)
            dto.conversationId to dto.toQueue()
        } catch (e: IllegalArgumentException) {
            null
        }
}
