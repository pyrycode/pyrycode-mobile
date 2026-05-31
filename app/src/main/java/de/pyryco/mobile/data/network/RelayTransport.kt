package de.pyryco.mobile.data.network

import kotlinx.coroutines.flow.Flow

/**
 * A single-connection relay WebSocket transport (#306): the Phase 4 leg that carries bare
 * [InnerFrameV2] JSON **text** frames between the phone and the paired relay. One [connect] = one
 * socket; this is **not** a reconnecting transport. Auto-reconnect/backoff and the
 * `ConnectionStateSource` swap are the sibling supervisor's concern, built on this surface.
 *
 * Transport-only: it carries opaque frames and **never inspects** their `data` (Noise ciphertext,
 * authenticated one layer up by the #298 session). The relay is content-blind — the phone sends and
 * receives bare [InnerFrameV2] text frames; the `{conn_id, frame}` routing envelope lives on the
 * relay's binary leg only and is never seen here.
 *
 * Single-use lifecycle: `NEW ──connect()──▶ DIALING ──(Up)──▶ UP ──(Down)──▶ terminal`. After the
 * single terminal [Down][TransportEvent.Down], [inbound] and [events] complete and the instance is
 * spent; a supervisor discards it and constructs a fresh one to reconnect (Noise ephemerals are
 * per-handshake, so there is no resumable state).
 *
 * The contract is intentionally minimal and portable (`Flow` + [InnerFrameV2]; no `android.*`): the
 * sibling supervisor, #278, and #302 build on exactly this surface and fake it in their tests.
 */
interface RelayTransport {
    /**
     * Hot, connection-scoped, **single-consumer** stream of received frames. Buffered: frames sent
     * before collection starts are retained. Completes after the terminal [Down][TransportEvent.Down]
     * (buffered frames drain to the consumer first — no truncation).
     */
    val inbound: Flow<InnerFrameV2>

    /**
     * Hot, connection-scoped, **single-consumer** stream of [Up][TransportEvent.Up] /
     * [Down][TransportEvent.Down] transport events. Buffered, so a supervisor subscribing right after
     * [connect] cannot miss the [Up]. Completes after the single terminal [Down].
     */
    val events: Flow<TransportEvent>

    /**
     * Opens one socket (asynchronously). [Up][TransportEvent.Up] / [Down][TransportEvent.Down] arrive
     * on [events]; received frames on [inbound]. Single-use: a second call is a caller bug and throws
     * [IllegalStateException]. Bad stored data (e.g. a malformed `relayUrl`) does **not** throw — it
     * is surfaced uniformly as a [Down] on [events].
     */
    fun connect()

    /**
     * Serializes [frame] (via [MobileJson]) and enqueues it as a bare text frame. Returns OkHttp's
     * enqueue result: `false` if the socket is closed or its send buffer is full. Never throws; safe
     * to call from any dispatcher.
     */
    fun send(frame: InnerFrameV2): Boolean

    /**
     * Tears down the socket and completes the streams via a terminal [Down][TransportEvent.Down].
     * Idempotent.
     */
    fun close()
}

/** A raw transport-level event surfaced on [RelayTransport.events] for a supervisor to drive on. */
sealed interface TransportEvent {
    /** The socket opened; frames may now flow. Emitted once, before any [Down]. */
    data object Up : TransportEvent

    /**
     * The connection ended — terminal. Emitted exactly once.
     *
     * @property code WS close code (`1000`, `4401`, …) on a server/graceful close, or the HTTP status
     *   on a rejected upgrade; `null` when no peer code applies (dial failure, local protocol-violation
     *   close, malformed stored data).
     * @property reason a category-only human string (never the token, dial URL, or frame bytes), or the
     *   server-supplied close reason on a graceful close.
     * @property cause the underlying [Throwable] on an abnormal failure; `null` on a clean close.
     */
    data class Down(
        val code: Int?,
        val reason: String?,
        val cause: Throwable?,
    ) : TransportEvent
}
