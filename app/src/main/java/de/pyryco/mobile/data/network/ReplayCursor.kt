package de.pyryco.mobile.data.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Reconnect-spanning high-water mark of the interactive structured-stream replay cursor (#412): the
 * latest [Envelope.eventId] observed on the inbound path. It is held here, not in the per-connection
 * [de.pyryco.mobile.data.repository.RemoteConversationRepository] (rebuilt every reconnect), because
 * [latest] must be readable at the *next* connection's handshake-build moment — before that
 * connection's inbound path exists — so #413 can advertise where the phone left off.
 *
 * This slice owns **recording** the cursor; #413 owns reading it into `hello`. The cursor is
 * in-memory only (never persisted): a fresh process start reads `null` ("no cursor", omittable —
 * never `0`).
 *
 * Thread-safe and lock-free: [record] folds via [MutableStateFlow.update] (a TOCTOU-free max-fold,
 * not check-then-set), so a writer (the single inbound collector) and a reader ([latest], from the
 * next connection's hello-build) cannot race. The backing flow is intentionally not exposed —
 * #413 needs a point read, not an observable stream.
 */
class ReplayCursor {
    private val mark = MutableStateFlow<Long?>(null)

    /** The latest recorded `event_id`, or `null` when nothing valid has ever been observed. */
    val latest: Long?
        get() = mark.value

    /**
     * Fold an observed `event_id` into the high-water mark. Fail-closed at the trust boundary:
     * a non-positive value (`0`, a negative, or a wrapped-huge `uint64`) is never recorded; otherwise
     * the mark advances only on a strictly-greater value, so a smaller / equal / out-of-order /
     * replayed value is a no-op (`null` counts as below any value).
     */
    fun record(eventId: Long) {
        if (eventId <= 0) return
        mark.update { current -> if (current == null || eventId > current) eventId else current }
    }

    /**
     * Clear the high-water mark back to its no-cursor state (#417) so the *next* `hello`-build reads
     * `null` and omits `last_event_id` (#416) — a fresh resume. Called on a `resync` marker, the
     * daemon's signal that the advertised position aged out of its bounded ring, so gap-free in-ring
     * replay is impossible. A direct atomic [MutableStateFlow] set, consistent with [record]'s
     * lock-free backing; called only from the single inbound collector (the same writer as [record]),
     * so reset and record never race within a connection, and [latest] — read at the next connection's
     * hello-build — sees the cleared value via the [MutableStateFlow] happens-before.
     */
    fun reset() {
        mark.value = null
    }
}
