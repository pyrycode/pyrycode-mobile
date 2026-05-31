package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerStore
import de.pyryco.mobile.data.model.ConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil
import kotlin.random.Random

/**
 * JVM unit tests for the relay reconnect supervisor (#307), driven with `runTest`'s virtual clock,
 * a fake [RelayTransport] surface, and a seeded [Random] so backoff intervals are deterministic.
 * No device — data-layer, same posture as [OkHttpRelayTransportTest].
 *
 * State is read via `observe().first()` (the observed flow is a `StateFlow`, so `first()` returns the
 * current value immediately). Transitions driven by an event are settled with [runCurrent] (no virtual
 * time elapses, so the 60 s stability timer never fires accidentally); backoff waits are stepped with
 * [advanceTimeBy] / [advanceUntilIdle].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayConnectionSupervisorTest {
    // ---- AC 1: dial, Connected on Up, per-second countdown, recover on a fresh transport ----------

    @Test
    fun singleDrop_countsDownPerSecondAndRecoversOnAFreshTransport() =
        runTest {
            val (factory, supervisor) = newPairedSupervisor()
            val firstInterval = intervalsFor(1).first()

            supervisor.connect()
            runCurrent()
            assertEquals(ConnectionState.Connecting, supervisor.state())
            assertNull(supervisor.currentConnection.value)

            factory.created[0].emitUp()
            runCurrent()
            assertEquals(ConnectionState.Connected, supervisor.state())
            assertSame(factory.created[0], supervisor.currentConnection.value)

            factory.created[0].emitDown()
            runCurrent()

            // Connected -> Reconnecting(k…1): one emission per second, decrementing.
            var remaining = firstInterval
            while (remaining > 0) {
                assertEquals(
                    ConnectionState.Reconnecting(ceil(remaining / 1000.0).toInt()),
                    supervisor.state(),
                )
                val step = minOf(1000L, remaining)
                advanceTimeBy(step)
                runCurrent()
                remaining -= step
            }

            // … -> Connecting on a brand-new transport instance.
            assertEquals(ConnectionState.Connecting, supervisor.state())
            assertEquals(2, factory.created.size)
            assertNull(supervisor.currentConnection.value)

            factory.created[1].emitUp()
            runCurrent()
            assertEquals(ConnectionState.Connected, supervisor.state())
            assertSame(factory.created[1], supervisor.currentConnection.value)

            supervisor.close()
        }

    // ---- AC 1, AC 5: capped-exponential backoff bases 1 / 2 / 4 / 8 / 16 with ±20% jitter ---------

    @Test
    fun backoffProgression_basesFollow1_2_4_8_16() =
        runTest {
            val (factory, supervisor) = newPairedSupervisor()
            val intervals = intervalsFor(1, 2, 3, 4, 5)

            supervisor.connect()
            runCurrent()

            for (attempt in 1..5) {
                factory.created[attempt - 1].emitDown()
                runCurrent()

                val base = 1 shl (attempt - 1) // 1, 2, 4, 8, 16
                val interval = intervals[attempt - 1]
                // The interval must sit inside the base's ±20% jitter band.
                assertTrue(
                    "attempt $attempt interval $interval outside band for base $base",
                    interval >= base * 800L && interval < base * 1200L,
                )
                assertEquals(
                    ConnectionState.Reconnecting(ceil(interval / 1000.0).toInt()),
                    supervisor.state(),
                )

                advanceUntilIdle() // run this backoff to completion -> re-dial
                assertEquals(ConnectionState.Connecting, supervisor.state())
            }

            assertEquals(6, factory.created.size) // one initial dial + five re-dials
            supervisor.close()
        }

    // ---- AC 1, AC 5: Offline at the 30 s cap, and it keeps retrying ------------------------------

    @Test
    fun offline_atCapKeepsRetrying() =
        runTest {
            val (factory, supervisor) = newPairedSupervisor()

            supervisor.connect()
            runCurrent()
            repeat(5) { i ->
                factory.created[i].emitDown()
                runCurrent()
                advanceUntilIdle() // sub-cap backoff -> re-dial
            }

            // attempt 6 reaches the 30 s cap -> Offline (no countdown).
            factory.created[5].emitDown()
            runCurrent()
            assertEquals(ConnectionState.Offline, supervisor.state())

            val dialsBeforeCapWait = factory.created.size
            advanceUntilIdle() // the cap wait elapses -> it re-dials anyway
            assertEquals(ConnectionState.Connecting, supervisor.state())
            assertEquals(dialsBeforeCapWait + 1, factory.created.size)

            // A further drop stays Offline (still at the cap).
            factory.created.last().emitDown()
            runCurrent()
            assertEquals(ConnectionState.Offline, supervisor.state())

            supervisor.close()
        }

    // ---- AC 2, AC 5: retry() collapses the pending backoff and never throws ----------------------

    @Test
    fun retry_collapsesPendingBackoffWithoutThrowing() =
        runTest {
            val (factory, supervisor) = newPairedSupervisor()

            supervisor.connect()
            runCurrent()
            factory.created[0].emitUp()
            runCurrent()
            factory.created[0].emitDown()
            runCurrent()
            assertTrue(supervisor.state() is ConnectionState.Reconnecting)

            val dialsBefore = factory.created.size
            supervisor.retry() // must not throw
            runCurrent() // no virtual time advanced

            assertEquals(ConnectionState.Connecting, supervisor.state())
            assertEquals(dialsBefore + 1, factory.created.size) // dialed immediately, without waiting

            supervisor.close()
        }

    // ---- AC 5: a ≥60 s stable connection resets the backoff escalation ---------------------------

    @Test
    fun stabilityReset_after60sStable_resetsToBase1() =
        runTest {
            val (factory, supervisor) = newPairedSupervisor()
            val intervals = intervalsFor(1, 1) // first drop base 1, second drop base 1 (reset)

            supervisor.connect()
            runCurrent()
            factory.created[0].emitUp()
            runCurrent()

            // First drop before 60 s of stability -> base 1.
            factory.created[0].emitDown()
            runCurrent()
            assertEquals(
                ConnectionState.Reconnecting(ceil(intervals[0] / 1000.0).toInt()),
                supervisor.state(),
            )
            advanceUntilIdle() // backoff -> re-dial

            factory.created[1].emitUp()
            runCurrent()
            advanceTimeBy(60_000) // ≥60 s stable
            runCurrent()

            // Drop after stability -> escalation reset, so this backoff is base 1 again.
            factory.created[1].emitDown()
            runCurrent()
            val second = intervals[1]
            assertTrue("expected base-1 interval, was $second", second >= 800L && second < 1200L)
            assertEquals(
                ConnectionState.Reconnecting(ceil(second / 1000.0).toInt()),
                supervisor.state(),
            )

            supervisor.close()
        }

    @Test
    fun noStabilityReset_under60sStable_escalatesBackoff() =
        runTest {
            val (factory, supervisor) = newPairedSupervisor()
            val intervals = intervalsFor(1, 2) // first drop base 1, second drop base 2 (no reset)

            supervisor.connect()
            runCurrent()
            factory.created[0].emitUp()
            runCurrent()
            factory.created[0].emitDown()
            runCurrent()
            advanceUntilIdle() // backoff(1) -> re-dial

            factory.created[1].emitUp()
            runCurrent()
            advanceTimeBy(30_000) // < 60 s — not stable
            runCurrent()

            // Drop without stability -> escalation continues, so this backoff is base 2.
            factory.created[1].emitDown()
            runCurrent()
            val second = intervals[1]
            assertTrue("expected base-2 interval, was $second", second >= 1600L && second < 2400L)
            assertEquals(
                ConnectionState.Reconnecting(ceil(second / 1000.0).toInt()),
                supervisor.state(),
            )

            supervisor.close()
        }

    // ---- AC 3: benign-unpaired — no dial, stays Connected so the banner stays hidden -------------

    @Test
    fun benignUnpaired_doesNotDialAndStaysConnected() =
        runTest {
            val factory = FakeRelayTransportFactory()
            val supervisor =
                RelayConnectionSupervisor(
                    transportFactory = factory,
                    pairedServerStore = StubPairedServerStore(null),
                    dispatcher = StandardTestDispatcher(testScheduler),
                    random = Random(SEED),
                )

            supervisor.connect()
            advanceUntilIdle()
            assertEquals(ConnectionState.Connected, supervisor.state())
            assertEquals(0, factory.created.size)
            assertNull(supervisor.currentConnection.value)

            // tap-to-retry while unpaired re-checks and stays idle — no dial, no banner regression.
            supervisor.retry()
            advanceUntilIdle()
            assertEquals(ConnectionState.Connected, supervisor.state())
            assertEquals(0, factory.created.size)

            supervisor.close()
        }

    // ---- AC 4: close() tears down the live transport and stops the loop --------------------------

    @Test
    fun close_tearsDownTransportAndStopsLoop() =
        runTest {
            val (factory, supervisor) = newPairedSupervisor()

            supervisor.connect()
            runCurrent()
            factory.created[0].emitUp()
            runCurrent()
            assertEquals(ConnectionState.Connected, supervisor.state())

            supervisor.close()
            runCurrent() // let the cancelled loop unwind its finally
            assertNull(supervisor.currentConnection.value)
            assertTrue(factory.created[0].closeCalls >= 1)
            assertEquals(ConnectionState.Connected, supervisor.state())

            // Loop stopped: nothing re-dials over time.
            val dials = factory.created.size
            advanceUntilIdle()
            assertEquals(dials, factory.created.size)

            supervisor.close() // idempotent
        }

    // ---- helpers ---------------------------------------------------------------------------------

    private suspend fun RelayConnectionSupervisor.state(): ConnectionState = observe().first()

    private fun TestScope.newPairedSupervisor(): Pair<FakeRelayTransportFactory, RelayConnectionSupervisor> {
        val factory = FakeRelayTransportFactory()
        val supervisor =
            RelayConnectionSupervisor(
                transportFactory = factory,
                pairedServerStore = StubPairedServerStore(PAIRED),
                dispatcher = StandardTestDispatcher(testScheduler),
                random = Random(SEED),
            )
        return factory to supervisor
    }

    /** Replays [jitteredBackoffMs] for [attempts] in order through a fresh `Random(SEED)`, matching
     *  the supervisor's one-`nextDouble()`-per-backoff consumption so expected intervals are exact. */
    private fun intervalsFor(vararg attempts: Int): List<Long> {
        val replay = Random(SEED)
        return attempts.map { jitteredBackoffMs(it, replay) }
    }

    private companion object {
        const val SEED = 42L
        val PAIRED =
            PairedServer(
                serverId = "srv-1",
                token = "tok",
                relayUrl = "ws://localhost/relay",
                serverStaticPublicKey = "key",
            )
    }
}

/** Drives the #306 `events` surface: the test pushes `Up` / `Down` and records lifecycle calls. */
private class FakeRelayTransport : RelayTransport {
    private val eventsChannel = Channel<TransportEvent>(Channel.UNLIMITED)

    override val inbound: Flow<InnerFrameV2> = emptyFlow()
    override val events: Flow<TransportEvent> = eventsChannel.receiveAsFlow()

    var connectCalls = 0
        private set
    var closeCalls = 0
        private set

    override fun connect() {
        connectCalls++
    }

    override fun send(frame: InnerFrameV2): Boolean = true

    override fun close() {
        closeCalls++
        eventsChannel.close()
    }

    fun emitUp() {
        eventsChannel.trySend(TransportEvent.Up)
    }

    fun emitDown(
        code: Int? = null,
        reason: String? = null,
        cause: Throwable? = null,
    ) {
        eventsChannel.trySend(TransportEvent.Down(code, reason, cause))
        eventsChannel.close() // single terminal Down completes the stream (#306 contract)
    }
}

private class FakeRelayTransportFactory : RelayTransportFactory {
    val created = mutableListOf<FakeRelayTransport>()

    override fun create(pairedServer: PairedServer): RelayTransport = FakeRelayTransport().also { created += it }
}

private class StubPairedServerStore(
    private val paired: PairedServer?,
) : PairedServerStore {
    override suspend fun load(): PairedServer? = paired

    override suspend fun save(record: PairedServer) = error("save is not exercised by the supervisor")
}
