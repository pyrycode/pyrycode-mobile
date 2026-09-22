package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.ApiRetryPayloadDto
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.toStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * API-retry status for every conversation on one connection (#593): its state, its decoder and its read
 * for the `api_retry` event, split out of [RemoteConversationRepository] so each status event lives in its
 * own file. The repository keeps the routing: its `onInbound` arm calls [apply] only behind the negotiated
 * `interactive` gate.
 *
 * One instance per repository, and a fresh repository per connection (#351), so the state is
 * connection-scoped exactly as it was when it lived in the repository.
 */
internal class ApiRetryProjection {
    /**
     * `conversationId -> current API-retry status` (#593) — whether claude is stuck retrying an API
     * error, and at which attempt. Written **only** from the repository's single inbound collector: each
     * `api_retry` envelope **replaces** that conversation's entry (a rising edge with the current
     * counter, a re-fired rising edge with the climbed one, or [ApiRetryStatus.NotRetrying] on the
     * falling edge), leaving every other conversation untouched. Single writer on the one collector
     * coroutine, so the rising and falling edges never race; the write is a pure replace, not a
     * read-modify-write, so there is no check-then-mutate window even in principle, and the atomic
     * [MutableStateFlow.update] matches the sibling projections' memory-visibility posture.
     * [observe] fans out from it. A falling edge **stores** [ApiRetryStatus.NotRetrying]
     * rather than removing the key — observationally identical to an absent key, since the observer
     * defaults an absent one the same way. Connection-scoped in-memory state — a fresh repository per
     * connection (#351) starts empty, so a retry state never survives a reconnect (it is re-derived
     * from the live stream). A retry is a transient "right now" condition, not durable state.
     */
    private val apiRetryByConversation = MutableStateFlow<Map<String, ApiRetryStatus>>(emptyMap())

    /**
     * Apply one `api_retry` envelope. Called only from the repository's `interactive`-gated arm; the
     * reasoning below was written for that arm and moved here with it.
     */
    fun apply(envelope: Envelope) {
        // API-retry status (#593). Same `interactive` gate as the live-session / `stall` /
        // `queue_state` siblings: a non-interactive phone never decodes a spurious `api_retry`
        // from a buggy/hostile daemon that ignored the server-side fan-out gate (fail-closed,
        // defence in depth). One unconditional replace of this conversation's entry, leaving
        // every other conversation untouched (AC #3) — deliberately no branch on `active` here:
        // `toStatus()` is the sole owner of the edge semantics (including discarding the stale
        // counter the falling edge carries), and a second `if` would encode the same rule twice.
        // A malformed payload decodes to null and is dropped so the single inbound consumer
        // survives (AC #3). Like the `queue_state` sibling and unlike the live-session arm, this
        // folds no thread row and does NOT clear a stall — a retry is claude stuck, not turn
        // forward progress (AC #4). Drop silently — nothing here logs the payload (the counter is
        // screen-derived data crossing the tui-driver substrate seal).
        decodeApiRetry(envelope)?.let { (conversationId, status) ->
            apiRetryByConversation.update { it + (conversationId to status) }
        }
    }

    /**
     * Current API-retry status for [conversationId] (#593), a pure cold projection of the shared
     * [apiRetryByConversation] `StateFlow`. Issues no request — rides the live `api_retry` edges. The
     * [ApiRetryStatus.NotRetrying] default gives not-retrying-until-the-first-frame (AC #1) and makes an
     * absent key indistinguishable from a stored falling edge. [distinctUntilChanged] suppresses only
     * value-*identical* re-emissions, so an `api_retry` for **another** conversation does not re-emit
     * this flow, while a **climbed counter is a different [ApiRetryStatus.Attempt] value and does reach
     * the collector as a new emission** (AC #2) — precisely what a membership `Set` could not do, since
     * `true` → `true` would collapse the climb. A `StateFlow` always has a current value, so every
     * collector (including a `flatMapLatest` re-subscription through the facade) receives the current
     * status on subscription; the one inbound consumer fans out to unlimited collectors.
     */
    fun observe(conversationId: String): Flow<ApiRetryStatus> =
        apiRetryByConversation.map { it[conversationId] ?: ApiRetryStatus.NotRetrying }.distinctUntilChanged()

    /**
     * Decode one v2 `api_retry` envelope (#593) to its conversation id and mapped [ApiRetryStatus], or
     * **null** when it cannot be read. Decodes the untrusted [Envelope.payload] through the single
     * configured [MobileJson] and maps via `toStatus()`. The whole body is one `try`/`catch
     * (IllegalArgumentException)` ([kotlinx.serialization.SerializationException] ⊂
     * [IllegalArgumentException]), so a malformed payload — a missing field, or one whose JSON shape
     * cannot be read as its declared type — yields `null`, dropping the one envelope while the lone
     * inbound collector survives (AC #3). Because `toStatus()` is **total**, structural malformation is the only
     * null path: an undocumented counter shape maps to [ApiRetryStatus.AttemptUnknown] rather than
     * discarding a real retry onset. Mirrors [StallProjection] / [QueueProjection]'s drop idiom —
     * **nothing here logs the payload** (uniform with every other `onInbound` arm).
     */
    private fun decodeApiRetry(envelope: Envelope): Pair<String, ApiRetryStatus>? =
        try {
            val dto = MobileJson.decodeFromJsonElement<ApiRetryPayloadDto>(envelope.payload)
            dto.conversationId to dto.toStatus()
        } catch (e: IllegalArgumentException) {
            null
        }
}
