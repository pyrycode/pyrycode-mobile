package de.pyryco.mobile.lifecycle

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import de.pyryco.mobile.data.network.RelayConnectionController

/**
 * Wires the whole-app foreground/background signal (`ProcessLifecycleOwner`) to the relay connection
 * supervisor's [RelayConnectionController] seam (#307) — the layer that decides **when** the relay
 * connection should be alive (#302). This is the single legitimate `androidx.lifecycle.*` site; the
 * portable transport contract stays free of platform APIs.
 *
 * Stateless: it forwards lifecycle edges to [controller] and holds no connection state of its own. The
 * supervisor owns the only connection-state source, the supervision loop, and all cancellation; the
 * driver never re-implements the unpaired gate, the idempotent `connect()`, or the full-teardown
 * `close()` it relies on (AC 4).
 *
 * - [onStart] (app foreground) → `connect()` — restart the supervision loop; a fresh socket is dialed.
 * - [onStop]  (app background) → `close()`   — tear down the live socket and stop the loop, so no
 *   on-drop backoff fires while backgrounded. `close()` returns the supervisor to its idle
 *   `Connected` state, so a deliberate background close never surfaces as `Offline`/`Reconnecting`.
 * - [onPushWake] (push-wake) → `connect()` — the same reconnect path as foregrounding (AC 3). Safe
 *   whether foreground (loop already running → no-op) or background (loop idle → starts), because
 *   `connect()` is idempotent — so the driver needs no foreground/background bookkeeping.
 */
class LifecycleConnectionDriver(
    private val controller: RelayConnectionController,
    private val lifecycle: Lifecycle,
) : DefaultLifecycleObserver {
    /** Registers this driver as an observer of the (process) [lifecycle]. */
    fun start() {
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        controller.connect()
    }

    override fun onStop(owner: LifecycleOwner) {
        controller.close()
    }

    /**
     * The stable in-process entry point a future FCM service calls on a push-wake. Payload-free by
     * design: it can only trigger a `connect()`, never inject push-controlled data into the connection
     * path.
     */
    fun onPushWake() {
        controller.connect()
    }
}
