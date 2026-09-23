package de.pyryco.mobile.lifecycle

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import de.pyryco.mobile.data.network.RelayConnectionController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How long a push wake keeps the saved hosts connected while the app stays in the background (#361). */
val PUSH_WAKE_WINDOW: Duration = 30.seconds

/**
 * Wires the whole-app foreground/background signal (`ProcessLifecycleOwner`) to the relay connection
 * supervisor's [RelayConnectionController] seam (#307) — the layer that decides **when** the relay
 * connection should be alive (#302). This is the single legitimate `androidx.lifecycle.*` site; the
 * portable transport contract stays free of platform APIs.
 *
 * It holds no connection state of its own. The supervisor owns the only connection-state source, the
 * supervision loop, and all cancellation; the driver never re-implements the unpaired gate, the
 * idempotent `connect()`, or the full-teardown `close()` it relies on (AC 4). Its only state is whether
 * the app is foregrounded and whether a push-wake window is open (#361).
 *
 * - [onStart] (app foreground) → `connect()` — restart the supervision loop; a fresh socket is dialed.
 * - [onStop]  (app background) → `close()`   — tear down the live socket and stop the loop, so no
 *   on-drop backoff fires while backgrounded. `close()` returns the supervisor to its idle
 *   `Connected` state, so a deliberate background close never surfaces as `Offline`/`Reconnecting`.
 * - [onPushWake] (push-wake, backgrounded) → `connect()`, then `close()` once [wakeWindow] has passed
 *   unless the app came to the foreground first (#361). A wake while foregrounded, or while a window is
 *   already open, does nothing: it opens no second connection and does not extend the window.
 *
 * [onPushWake] arrives on an FCM worker thread, lifecycle callbacks on main and the window expiry on
 * [dispatcher], so every state change holds this object's lock. The registry never calls back into the
 * driver, so driver → controller is the only lock order.
 */
class LifecycleConnectionDriver(
    private val controller: RelayConnectionController,
    private val lifecycle: Lifecycle,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val wakeWindow: Duration = PUSH_WAKE_WINDOW,
) : DefaultLifecycleObserver {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    // A process started by a push never reaches ON_START, so it begins in the background.
    private var foreground = false
    private var wakeJob: Job? = null

    /** Registers this driver as an observer of the (process) [lifecycle]. */
    fun start() {
        lifecycle.addObserver(this)
    }

    /** The foreground takes over any open wake window: it owns the connections until the next stop. */
    @Synchronized
    override fun onStart(owner: LifecycleOwner) {
        foreground = true
        wakeJob?.cancel()
        wakeJob = null
        controller.connect()
    }

    @Synchronized
    override fun onStop(owner: LifecycleOwner) {
        foreground = false
        controller.close()
    }

    /**
     * The in-process entry point the FCM service calls on a push-wake. Payload-free by design: it can
     * only trigger a `connect()` of every saved host, never inject push-controlled data into the
     * connection path.
     */
    @Synchronized
    fun onPushWake() {
        if (foreground || wakeJob != null) return
        controller.connect()
        // Lazy, so the window is recorded before its timer can run on an immediate dispatcher.
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                delay(wakeWindow)
                expireWake(coroutineContext.job)
            }
        wakeJob = job
        job.start()
    }

    /** A stale timer (cancelled by a foreground, or superseded) must never close the connections. */
    @Synchronized
    private fun expireWake(job: Job) {
        if (wakeJob !== job) return
        wakeJob = null
        if (foreground) return
        controller.close()
    }

    /** Cancels any open window's timer. Koin calls this on close. */
    fun dispose() {
        scope.cancel()
    }
}
