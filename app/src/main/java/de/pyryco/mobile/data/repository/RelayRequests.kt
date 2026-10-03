package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.ErrorPayload
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayErrorException
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.ERROR_CONVERSATION_NOT_FOUND
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.ERROR_MALFORMED_REPLY
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.PENDING_REQUEST_TORN_DOWN
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The request↔reply plumbing of one connection (#914): the one envelope-id counter, the reply waiters,
 * the correlated await, the teardown sweep and the `error` mapping, split out of
 * [RemoteConversationRepository] the way the projections were (#912, #913). The repository keeps the
 * routing: its `onInbound` arms look up a waiter through [waiter] and complete or fail it, and the inbound
 * collector's `finally` calls [failAllPending] last.
 *
 * Every request on the connection takes its envelope id from [nextRequestId], the fire-and-forget frames
 * and the asks [ModelMenuProjection] and [QuestionBatchProjection] send included, so the model-list ask
 * ledger and [pendingRequests] stay disjoint.
 *
 * [send] is the repository's pump send. One instance per repository, and a fresh repository per
 * connection (#351), so the state is connection-scoped exactly as it was when it lived in the repository.
 * Nothing here logs.
 */
internal class RelayRequests(
    private val send: (Envelope) -> Boolean,
) {
    private val requestId = AtomicLong(0)

    /**
     * Request *envelope* id ([nextRequestId], **not** the payload `message_id`) ->
     * the deferred awaiting that request's correlated reply (#346). A request registers its deferred
     * here before sending; the repository's single inbound collector completes it on the matching `ack`
     * (success) or `error` (failure) by [Envelope.inReplyTo]; the awaiting caller removes its own entry in a
     * `finally`. Touched from the collector coroutine and arbitrary caller coroutines, so
     * [ConcurrentHashMap] — the same `java.util.concurrent` posture as [requestId]. This is the
     * shared request↔reply correlation primitive #347/#348 reuse.
     */
    private val pendingRequests = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()

    /** The next envelope id on this connection, for every request, awaited or not. */
    fun nextRequestId(): Long = requestId.incrementAndGet()

    /**
     * The deferred still awaiting the reply to [inReplyTo], or `null` when the id is null or matches no
     * pending request (a stale, duplicate or unsolicited reply). Returned rather than completed here so
     * each `onInbound` arm keeps its own completion rule.
     */
    fun waiter(inReplyTo: Long?): CompletableDeferred<JsonElement>? = inReplyTo?.let { id -> pendingRequests[id] }

    /**
     * Map a server `error` reply payload (#346) to the domain exception the awaiting suspend throws.
     * Decodes [ErrorPayload] through [MobileJson]; `conversation.not_found` becomes the
     * [IllegalArgumentException] the [ConversationRepository] contract pins for an unknown
     * conversation (AC #3, mirroring the fake's type), and every other code becomes a
     * [RelayErrorException] carrying the structured `code`/`retryable`/`message` (AC #4). A
     * malformed/undecodable payload yields a fallback [RelayErrorException] so the waiter is always
     * unblocked rather than left hanging. Never logs the payload (message content stays off the log).
     */
    fun mapError(payload: JsonElement): Throwable {
        val error =
            try {
                MobileJson.decodeFromJsonElement<ErrorPayload>(payload)
            } catch (e: IllegalArgumentException) {
                return RelayErrorException(code = ERROR_MALFORMED_REPLY, retryable = false, message = "Malformed error reply")
            }
        return if (error.code == ERROR_CONVERSATION_NOT_FOUND) {
            IllegalArgumentException("Unknown conversation: ${error.message}")
        } else {
            RelayErrorException(code = error.code, retryable = error.retryable, message = error.message)
        }
    }

    /**
     * Register a deferred for [request]'s reply, send the request, and await the correlated
     * `ack`/`error` (#346) — the reusable request↔reply primitive #347/#348 inherit. Registers
     * **before** sending (no lost-reply race), throws [IllegalStateException] without awaiting if the
     * pump is not `Open` ([SessionPump.send] returns `false`, AC #4), and removes the entry in a
     * `finally` covering success, error, and caller cancellation. Returns the reply payload (the
     * empty `{}` for an `ack`); rethrows the collector's exceptional completion on an `error`.
     *
     * [onSent] runs once the open connection has taken the request (#1564), so a caller can tell a request
     * that went out from one that never did. [onReply] then runs where the reply lands, on the inbound
     * collector, with `null` for a success or the failure the await rethrows: an `error` or the teardown
     * sweep. It runs in arrival order with every other frame, which the caller's resumption does not. A caller
     * cancelled before the reply gets no [onReply].
     */
    suspend fun sendAndAwaitReply(
        request: Envelope,
        onSent: () -> Unit = {},
        onReply: (Throwable?) -> Unit = {},
    ): JsonElement {
        val deferred = CompletableDeferred<JsonElement>()
        pendingRequests[request.id] = deferred
        return try {
            check(send(request)) { "${request.type} not sent: session not connected" }
            onSent()
            deferred.invokeOnCompletion(onReply)
            deferred.await()
        } finally {
            pendingRequests.remove(request.id)
        }
    }

    /**
     * Teardown sweep (#488): complete every still-registered [pendingRequests] deferred exceptionally
     * and remove it, so an awaiting [sendAndAwaitReply] caller (a tapped permission answer, a sent
     * message, a promote, …) throws **promptly** instead of suspending forever once the connection
     * tears down. Runs in the repository's inbound collector's `finally`, on scope cancellation (the primary
     * trigger, [RelayRepositoryCoordinator.teardownActive]) or `pump.inbound` completing.
     *
     * Fails with a plain [IllegalStateException] — the exact type [sendAndAwaitReply]'s not-connected
     * `check(...)` already surfaces, so every caller handles teardown-mid-await through the one failure
     * mode it already has. Deliberately **not** a [kotlinx.coroutines.CancellationException] (which
     * `extends IllegalStateException` on the JVM): completing with cancellation would make `await()`
     * read as the caller's own scope dying, losing the surfaceable error. **Non-suspending**
     * ([CompletableDeferred.completeExceptionally] returns `Boolean`), so it runs to completion even
     * inside the cancelling collector coroutine, and idempotent against the caller's own
     * `finally { remove }` (completing an already-removed deferred is a no-op). Emits **no log**: the
     * swept reply payloads are discarded and the message is a static literal (never-log contract).
     */
    fun failAllPending() {
        val cause = IllegalStateException(PENDING_REQUEST_TORN_DOWN)
        val iterator = pendingRequests.values.iterator()
        while (iterator.hasNext()) {
            iterator.next().completeExceptionally(cause)
            iterator.remove()
        }
    }
}
