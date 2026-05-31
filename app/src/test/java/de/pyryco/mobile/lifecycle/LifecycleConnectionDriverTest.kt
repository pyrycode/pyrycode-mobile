package de.pyryco.mobile.lifecycle

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import de.pyryco.mobile.data.network.RelayConnectionController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the process-lifecycle driver (#302). No instrumentation, no Robolectric, no real
 * network/transport (AC 5): the supervisor seam is a recording [FakeRelayConnectionController] and the
 * lifecycle seam is a [LifecycleRegistry.createUnsafe] (the test-only factory that skips the
 * main-thread check, so a real `ProcessLifecycleOwner` is unnecessary).
 *
 * The driver launches no coroutines, so there is no virtual clock here — each `handleLifecycleEvent`
 * dispatches synchronously and the recorded [Call] list is the full, ordered assertion target.
 */
class LifecycleConnectionDriverTest {
    // ---- AC 2: foreground dials (connect), and only connect ---------------------------------------

    @Test
    fun foreground_drivesConnectOnly() {
        val (owner, controller) = newDriver()

        owner.foreground()

        assertEquals(listOf(Call.Connect), controller.calls)
    }

    // ---- AC 1: background closes (close), and only close ------------------------------------------

    @Test
    fun background_drivesClose() {
        val (owner, controller) = newDriver()

        owner.foreground()
        owner.background()

        assertEquals(listOf(Call.Connect, Call.Close), controller.calls)
    }

    // ---- AC 1 + AC 2: background then foreground re-dials a fresh connection ----------------------

    @Test
    fun backgroundThenForeground_closesThenReconnects() {
        val (owner, controller) = newDriver()

        owner.foreground()
        owner.background()
        owner.foreground()

        assertEquals(listOf(Call.Connect, Call.Close, Call.Connect), controller.calls)
    }

    // ---- State + concurrency: cold start (INITIALIZED → CREATED → STARTED) connects, no close -----

    @Test
    fun coldStart_drivesConnectOnly() {
        val (owner, controller) = newDriver()

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)

        assertEquals(listOf(Call.Connect), controller.calls)
    }

    // ---- AC 3: push-wake while backgrounded takes the same reconnect path as foregrounding --------

    @Test
    fun pushWakeWhileBackgrounded_drivesConnectNotClose() {
        val (owner, controller) = newDriver()

        owner.foreground()
        owner.background()
        assertEquals(listOf(Call.Connect, Call.Close), controller.calls)

        owner.driver.onPushWake()

        // Identical to the foreground path: one extra connect(), no extra close().
        assertEquals(listOf(Call.Connect, Call.Close, Call.Connect), controller.calls)
    }

    // ---- AC 4: rapid background/foreground toggling never doubles a connect without a close -------

    @Test
    fun rapidToggle_strictlyAlternatesConnectAndClose() {
        val (owner, controller) = newDriver()

        repeat(3) {
            owner.foreground()
            owner.background()
        }

        assertEquals(
            listOf(
                Call.Connect,
                Call.Close,
                Call.Connect,
                Call.Close,
                Call.Connect,
                Call.Close,
            ),
            controller.calls,
        )
        // The driver-pairing invariant: no two consecutive identical edges.
        controller.calls.zipWithNext().forEach { (a, b) ->
            assertTrue("two consecutive $a calls without an intervening edge", a != b)
        }
    }

    // ---- start() is what registers the observer: no events reach the driver before it ------------

    @Test
    fun withoutStart_noEventsReachDriver_thenStartRegisters() {
        val owner = FakeLifecycleOwner()
        val controller = FakeRelayConnectionController()
        // Driver constructed but NOT started — it is not yet observing the lifecycle.
        val driver = LifecycleConnectionDriver(controller, owner.lifecycle)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertEquals(emptyList<Call>(), controller.calls)

        // start() registers the observer; it syncs to the current STARTED state → connect().
        driver.start()

        assertEquals(listOf(Call.Connect), controller.calls)
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private fun newDriver(): Harness {
        val owner = FakeLifecycleOwner()
        val controller = FakeRelayConnectionController()
        val driver = LifecycleConnectionDriver(controller, owner.lifecycle)
        driver.start()
        owner.driver = driver
        return Harness(owner, controller)
    }

    private data class Harness(
        val owner: FakeLifecycleOwner,
        val controller: FakeRelayConnectionController,
    )
}

private enum class Call { Connect, Close }

private class FakeRelayConnectionController : RelayConnectionController {
    val calls = mutableListOf<Call>()

    override fun connect() {
        calls += Call.Connect
    }

    override fun close() {
        calls += Call.Close
    }
}

private class FakeLifecycleOwner : LifecycleOwner {
    val registry = LifecycleRegistry.createUnsafe(this)
    lateinit var driver: LifecycleConnectionDriver

    override val lifecycle: Lifecycle get() = registry

    /** ON_CREATE → ON_START: the whole-app foreground edge ProcessLifecycleOwner emits. */
    fun foreground() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    /** ON_STOP: the whole-app background edge (STARTED → CREATED). */
    fun background() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }
}
