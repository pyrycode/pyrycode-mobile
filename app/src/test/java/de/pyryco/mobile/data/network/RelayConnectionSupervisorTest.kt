package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerStore
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    // ---- #498 AC 1: a retry() while Connected must not pre-collapse the next drop's backoff -------
    //
    // retry() writes into the CONFLATED retrySignal. Issued while the loop sits inside events.collect
    // (Connected, no wait in progress), the Unit buffers with no receiver. Without the drain at the top
    // of backoff(), the next drop's first collapsibleWait would consume that stale signal and re-dial
    // instantly — shortening a backoff that should have waited its full jittered interval.
    @Test
    fun retryWhileConnected_doesNotShortenFirstBackoffAfterLaterDrop() =
        runTest {
            val (factory, supervisor) = newPairedSupervisor()
            val interval = intervalsFor(1).first()

            supervisor.connect()
            runCurrent()
            factory.created[0].emitUp()
            runCurrent()
            assertEquals(ConnectionState.Connected, supervisor.state())

            // retry() while Connected: connect() is idempotent (loop already running) and the signal
            // buffers with no receiver — so no re-dial, still Connected.
            supervisor.retry()
            runCurrent()
            assertEquals(ConnectionState.Connected, supervisor.state())
            assertEquals(1, factory.created.size)

            // A later drop starts a fresh backoff; the stale buffered signal must not shorten it.
            factory.created[0].emitDown()
            runCurrent()
            assertEquals(
                ConnectionState.Reconnecting(ceil(interval / 1000.0).toInt()),
                supervisor.state(),
            )

            advanceTimeBy(interval - 1)
            runCurrent()
            assertEquals(ConnectionState.Reconnecting(1), supervisor.state()) // still waiting
            assertEquals(1, factory.created.size) // not re-dialled

            advanceTimeBy(1)
            runCurrent()
            assertEquals(ConnectionState.Connecting, supervisor.state()) // re-dialled at the full interval
            assertEquals(2, factory.created.size)

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
            // #499 AC#1: the relay leg is Idle (not the live-socket Connected) while unpaired.
            assertEquals(RelayLinkStatus.Idle, supervisor.relayStatus.value)
            assertEquals(0, factory.created.size)
            assertNull(supervisor.currentConnection.value)

            // tap-to-retry while unpaired re-checks and stays idle — no dial, no banner regression.
            supervisor.retry()
            advanceUntilIdle()
            assertEquals(ConnectionState.Connected, supervisor.state())
            assertEquals(RelayLinkStatus.Idle, supervisor.relayStatus.value)
            assertEquals(0, factory.created.size)

            supervisor.close()
        }

    // ---- #489 AC 2: each dial re-reads the store, so a re-pair switches the dialed server ---------

    @Test
    fun reloadPerDial_reReadsStore_soRepairSwitchesServer() =
        runTest {
            val factory = FakeRelayTransportFactory()
            val supervisor =
                RelayConnectionSupervisor(
                    transportFactory = factory,
                    // A returns first, then B after the re-pair — the loop must pick up B on redial.
                    pairedServerStore = ScriptedPairedServerStore(listOf(PAIRED, PAIRED_B)),
                    dispatcher = StandardTestDispatcher(testScheduler),
                    random = Random(SEED),
                )

            supervisor.connect()
            runCurrent()
            assertEquals(PAIRED, factory.createdWith[0]) // dial 0 targets the originally-paired server

            factory.created[0].emitUp()
            runCurrent()
            factory.created[0].emitDown()
            runCurrent()
            advanceUntilIdle() // backoff elapses -> redial re-reads the store, now returning B

            assertEquals(2, factory.createdWith.size)
            assertEquals(PAIRED_B, factory.createdWith[1]) // dial 1 targets the newly-paired server

            supervisor.close()
        }

    // ---- #489 AC 3: a later-iteration null read idles at Connected without dialing ----------------

    @Test
    fun reloadPerDial_laterNullRead_idlesAtConnectedWithoutDialing() =
        runTest {
            val factory = FakeRelayTransportFactory()
            val supervisor =
                RelayConnectionSupervisor(
                    transportFactory = factory,
                    // Paired on the first dial, then unpaired (or an undecryptable read) on the second.
                    pairedServerStore = ScriptedPairedServerStore(listOf(PAIRED, null)),
                    dispatcher = StandardTestDispatcher(testScheduler),
                    random = Random(SEED),
                )

            supervisor.connect()
            runCurrent()
            factory.created[0].emitDown()
            runCurrent()
            advanceUntilIdle() // backoff elapses -> redial reads null -> idle, loop ends

            assertEquals(ConnectionState.Connected, supervisor.state())
            // #499 AC#1/#2: the later null read idles the relay leg, not a live-socket Connected.
            assertEquals(RelayLinkStatus.Idle, supervisor.relayStatus.value)
            assertEquals(1, factory.created.size) // no second dial
            assertNull(supervisor.currentConnection.value)

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
            // #499 AC#2: close() is an intentional disconnect — the relay leg goes Idle, not Connected.
            assertEquals(RelayLinkStatus.Idle, supervisor.relayStatus.value)

            // Loop stopped: nothing re-dials over time.
            val dials = factory.created.size
            advanceUntilIdle()
            assertEquals(dials, factory.created.size)

            supervisor.close() // idempotent
        }

    // ---- #496 AC 1, 2, 5: a cancelled old loop's finally must not clear the NEW loop's connection --
    //
    // The interleaving a naive test misses: StandardTestDispatcher resumes the cancelled old loop FIFO
    // *before* the new loop publishes, so its finally would run harmlessly on an already-null field. To
    // reproduce the bug we hold the old loop's cancellation in a NonCancellable cleanup gate PAST the
    // point the new loop reaches Up and publishes its transport, then release it — so the old finally
    // runs last and must leave currentConnection on the new transport (never null).
    @Test
    fun closeThenConnect_oldLoopFinally_doesNotClearNewLoopConnection() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val gated = GatedRelayTransport(release)
            val fresh = FakeRelayTransport()
            val factory = ScriptedRelayTransportFactory(listOf(gated, fresh))
            val supervisor =
                RelayConnectionSupervisor(
                    transportFactory = factory,
                    pairedServerStore = StubPairedServerStore(PAIRED),
                    dispatcher = StandardTestDispatcher(testScheduler),
                    random = Random(SEED),
                )

            // Old loop dials the gated transport, reaches Up, and publishes it.
            supervisor.connect()
            runCurrent()
            assertSame(gated, supervisor.currentConnection.value)
            assertEquals(ConnectionState.Connected, supervisor.state())

            // close() cancels the old loop and synchronously nulls the live connection; connect()
            // immediately starts a fresh loop before the cancelled loop's finally has run.
            supervisor.close()
            supervisor.connect()
            runCurrent() // old loop parks in its NonCancellable gate; new loop dials `fresh`, awaits Up
            assertNull(supervisor.currentConnection.value)

            // New loop reaches Up and publishes its transport — while the old loop's finally is held.
            fresh.emitUp()
            runCurrent()
            assertSame(fresh, supervisor.currentConnection.value)

            // Release the old loop's held finally. On the un-guarded finally this nulls the field,
            // wiping the new loop's live connection; the compare-and-clear guard leaves it intact.
            release.complete(Unit)
            runCurrent()
            assertSame(fresh, supervisor.currentConnection.value)

            // AC 3: the old loop still released its own socket unconditionally in finally.
            assertTrue(gated.closeCalls >= 1)

            supervisor.close()
        }

    // ---- #391 AC 2: a 4404 close maps to DaemonAbsent, distinct from Offline ----------------------

    @Test
    fun daemonAbsentClose_4404_mapsToDaemonAbsentDistinctFromOffline() =
        runTest {
            val (factory, supervisor) = newPairedSupervisor()

            supervisor.connect()
            runCurrent()
            factory.created[0].emitUp()
            runCurrent()
            assertEquals(RelayLinkStatus.Connected, supervisor.relayStatus.value)

            factory.created[0].emitDown(code = 4404)
            runCurrent()

            val status = supervisor.relayStatus.value
            assertEquals(RelayLinkStatus.DaemonAbsent, status)
            assertTrue("DaemonAbsent must not be a Reconnecting countdown", status !is RelayLinkStatus.Reconnecting)
            assertNotEquals(RelayLinkStatus.Offline, status)
            // Legacy single-signal view derives DaemonAbsent to the nearest case, Offline.
            assertEquals(ConnectionState.Offline, supervisor.state())

            supervisor.close()
        }

    // ---- #391 AC 2: DaemonAbsent keeps redialling and flips off when a daemon registers -----------

    @Test
    fun daemonAbsent_keepsRedialling_andFlipsOffWhenDaemonRegisters() =
        runTest {
            val (factory, supervisor) = newPairedSupervisor()

            supervisor.connect()
            runCurrent()
            factory.created[0].emitUp()
            runCurrent()
            factory.created[0].emitDown(code = 4404)
            runCurrent()
            assertEquals(RelayLinkStatus.DaemonAbsent, supervisor.relayStatus.value)

            val dialsBeforeRedial = factory.created.size
            advanceUntilIdle() // the DaemonAbsent backoff wait elapses -> a fresh dial
            assertEquals(RelayLinkStatus.Connecting, supervisor.relayStatus.value)
            assertEquals(dialsBeforeRedial + 1, factory.created.size)

            // A daemon is now registered: Up on the fresh transport -> Connected (the leg flips off).
            factory.created[1].emitUp()
            runCurrent()
            assertEquals(RelayLinkStatus.Connected, supervisor.relayStatus.value)

            supervisor.close()
        }

    // ---- #391 AC 2: repeated 4404 stays DaemonAbsent on the unchanged escalating schedule ---------

    @Test
    fun repeated4404_staysDaemonAbsent_onTheExistingBackoffSchedule() =
        runTest {
            val (factory, supervisor) = newPairedSupervisor()
            // The supervisor consumes one nextDouble() per backoff regardless of DaemonAbsent, so a
            // base-1 then base-2 replay matches the existing escalation (no intervening stability reset).
            val intervals = intervalsFor(1, 2)

            supervisor.connect()
            runCurrent()
            factory.created[0].emitUp()
            runCurrent()

            // First 4404 (attempt 1) -> DaemonAbsent on the base-1 wait.
            factory.created[0].emitDown(code = 4404)
            runCurrent()
            assertEquals(RelayLinkStatus.DaemonAbsent, supervisor.relayStatus.value)
            advanceUntilIdle() // base-1 wait elapses -> re-dial (no Up -> escalation continues)

            // Second 4404 (attempt 2) -> still DaemonAbsent, now on the base-2 wait.
            factory.created[1].emitDown(code = 4404)
            runCurrent()
            assertEquals(RelayLinkStatus.DaemonAbsent, supervisor.relayStatus.value)

            // Prove the wait is the base-2 ±20% jittered interval, i.e. the existing schedule is reused.
            val secondInterval = intervals[1]
            assertTrue("expected base-2 interval, was $secondInterval", secondInterval >= 1600L && secondInterval < 2400L)
            val dialsBeforeRedial = factory.created.size
            advanceTimeBy(secondInterval - 1)
            runCurrent()
            assertEquals(RelayLinkStatus.DaemonAbsent, supervisor.relayStatus.value)
            assertEquals(dialsBeforeRedial, factory.created.size) // not yet re-dialled
            advanceTimeBy(1)
            runCurrent()
            assertEquals(RelayLinkStatus.Connecting, supervisor.relayStatus.value)
            assertEquals(dialsBeforeRedial + 1, factory.created.size) // re-dialled at the interval

            supervisor.close()
        }

    // ---- #391 AC 3: a non-4404 close drives the existing reconnect path, never DaemonAbsent -------

    @Test
    fun nonDaemonClose_followsExistingReconnectPath_neverDaemonAbsent() =
        runTest {
            // 4401 / 4426 halt as a rejected pairing (#841); their neighbours still reconnect.
            for (code in listOf(1006, 1000, 4400, 4427)) {
                val (factory, supervisor) = newPairedSupervisor()

                supervisor.connect()
                runCurrent()
                factory.created[0].emitUp()
                runCurrent()
                factory.created[0].emitDown(code = code)
                runCurrent()

                assertTrue("code $code should reconnect", supervisor.relayStatus.value is RelayLinkStatus.Reconnecting)
                assertNotEquals("code $code must not be DaemonAbsent", RelayLinkStatus.DaemonAbsent, supervisor.relayStatus.value)
                // Legacy view: Reconnecting maps identity, so existing consumers are unchanged.
                assertTrue(supervisor.state() is ConnectionState.Reconnecting)

                supervisor.close()
            }
        }

    // ---- #391 AC 4: a clean dial failure (null code) follows the unreachable path, never DaemonAbsent

    @Test
    fun cleanDialFailure_nullCode_followsUnreachablePath_neverDaemonAbsent() =
        runTest {
            val (factory, supervisor) = newPairedSupervisor()

            supervisor.connect()
            runCurrent()
            // Sub-cap: a null-code drop reconnects, never DaemonAbsent.
            factory.created[0].emitDown(code = null)
            runCurrent()
            assertTrue(supervisor.relayStatus.value is RelayLinkStatus.Reconnecting)
            assertNotEquals(RelayLinkStatus.DaemonAbsent, supervisor.relayStatus.value)

            // Escalate to the cap -> Offline, still never DaemonAbsent.
            repeat(5) { i ->
                advanceUntilIdle() // backoff elapses -> re-dial
                factory.created[i + 1].emitDown(code = null)
                runCurrent()
                assertNotEquals(RelayLinkStatus.DaemonAbsent, supervisor.relayStatus.value)
            }
            assertEquals(RelayLinkStatus.Offline, supervisor.relayStatus.value)

            supervisor.close()
        }

    // ---- #841 AC 1+2: a 4401 / 4426 close is a rejected pairing and halts the redial ---------------

    @Test
    fun rejectedPairingClose_4401or4426_haltsRedial() =
        runTest {
            for (code in listOf(4401, 4426)) {
                val (factory, supervisor) = newPairedSupervisor()

                supervisor.connect()
                runCurrent()
                factory.created[0].emitUp()
                runCurrent()
                // A retry issued while connected is stale by the time of the drop; it must not skip the halt.
                supervisor.retry()
                runCurrent()
                factory.created[0].emitDown(code = code)
                runCurrent()

                assertEquals("code $code", RelayLinkStatus.PairingRejected, supervisor.relayStatus.value)
                assertEquals(ConnectionState.Offline, supervisor.state())
                assertNull(supervisor.currentConnection.value)

                advanceTimeBy(10 * 60_000L) // far past the 30 s cap
                runCurrent()
                assertEquals("code $code must not redial", 1, factory.created.size)
                assertEquals(RelayLinkStatus.PairingRejected, supervisor.relayStatus.value)

                supervisor.close()
            }
        }

    @Test
    fun rejectedPairing_explicitRetryDialsOnce_andARepeatedRejectionHaltsAgain() =
        runTest {
            val (factory, supervisor) = newPairedSupervisor()

            supervisor.connect()
            runCurrent()
            factory.created[0].emitDown(code = 4401)
            runCurrent()
            assertEquals(RelayLinkStatus.PairingRejected, supervisor.relayStatus.value)

            supervisor.retry()
            runCurrent()
            assertEquals(RelayLinkStatus.Connecting, supervisor.relayStatus.value)
            assertEquals(2, factory.created.size)

            factory.created[1].emitDown(code = 4426)
            runCurrent()
            advanceTimeBy(10 * 60_000L)
            runCurrent()
            assertEquals(RelayLinkStatus.PairingRejected, supervisor.relayStatus.value)
            assertEquals(2, factory.created.size)

            supervisor.close()
        }

    @Test
    fun rejectedPairing_nextForegroundConnectDialsOnce() =
        runTest {
            val (factory, supervisor) = newPairedSupervisor()

            supervisor.connect()
            runCurrent()
            factory.created[0].emitDown(code = 4401)
            runCurrent()
            assertEquals(RelayLinkStatus.PairingRejected, supervisor.relayStatus.value)

            // Background close, then foreground connect: the lifecycle driver's pairing of the two calls.
            supervisor.close()
            supervisor.connect()
            runCurrent()
            assertEquals(RelayLinkStatus.Connecting, supervisor.relayStatus.value)
            assertEquals(2, factory.created.size)

            supervisor.close()
        }

    @Test
    fun rejectedPairingOnOneHost_leavesAnotherHostsSupervisorRedialling() =
        runTest {
            val (rejectedFactory, rejected) = newPairedSupervisor()
            val (otherFactory, other) = newPairedSupervisor()

            rejected.connect()
            other.connect()
            runCurrent()
            rejectedFactory.created[0].emitDown(code = 4401)
            otherFactory.created[0].emitDown(code = 1006)
            runCurrent()

            assertEquals(RelayLinkStatus.PairingRejected, rejected.relayStatus.value)
            assertTrue(other.relayStatus.value is RelayLinkStatus.Reconnecting)
            advanceTimeBy(10 * 60_000L)
            runCurrent()
            assertEquals(1, rejectedFactory.created.size)
            assertTrue(otherFactory.created.size > 1)

            rejected.close()
            other.close()
        }

    // ---- #1324: a 4421 protocol-mismatch close halts the redial at Offline ------------------------

    @Test
    fun protocolMismatchClose_4421_haltsRedialAtOffline() =
        runTest {
            val (factory, supervisor) = newPairedSupervisor()

            supervisor.connect()
            runCurrent()
            factory.created[0].emitUp()
            runCurrent()
            // A retry issued while connected is stale by the time of the drop; it must not skip the halt.
            supervisor.retry()
            runCurrent()
            factory.created[0].emitDown(code = 4421)
            runCurrent()

            assertEquals(RelayLinkStatus.Offline, supervisor.relayStatus.value)
            assertNull(supervisor.currentConnection.value)

            advanceTimeBy(10 * 60_000L) // far past the 30 s cap
            runCurrent()
            assertEquals(1, factory.created.size)
            assertEquals(RelayLinkStatus.Offline, supervisor.relayStatus.value)

            supervisor.close()
        }

    @Test
    fun protocolMismatch_explicitRetryDialsOnce_andARepeatedMismatchHaltsAgain() =
        runTest {
            val (factory, supervisor) = newPairedSupervisor()

            supervisor.connect()
            runCurrent()
            factory.created[0].emitDown(code = 4421)
            runCurrent()
            assertEquals(RelayLinkStatus.Offline, supervisor.relayStatus.value)

            supervisor.retry()
            runCurrent()
            assertEquals(RelayLinkStatus.Connecting, supervisor.relayStatus.value)
            assertEquals(2, factory.created.size)

            factory.created[1].emitDown(code = 4421)
            runCurrent()
            advanceTimeBy(10 * 60_000L)
            runCurrent()
            assertEquals(RelayLinkStatus.Offline, supervisor.relayStatus.value)
            assertEquals(2, factory.created.size)

            supervisor.close()
        }

    // ---- #1008: a 4412 close is the app-too-old rejection and halts the redial --------------------

    @Test
    fun updateRequiredClose_4412_haltsRedialWithNoMinimum() =
        runTest {
            val (factory, supervisor) = newPairedSupervisor()

            supervisor.connect()
            runCurrent()
            factory.created[0].emitUp()
            runCurrent()
            // A retry issued while connected is stale by the time of the drop; it must not skip the halt.
            supervisor.retry()
            runCurrent()
            factory.created[0].emitDown(code = 4412, reason = "1.4.0")
            runCurrent()

            // The close reason is never read: only the sealed error supplies a minimum.
            assertEquals(RelayLinkStatus.UpdateRequired(null), supervisor.relayStatus.value)
            assertEquals(ConnectionState.Offline, supervisor.state())
            assertNull(supervisor.currentConnection.value)

            advanceTimeBy(10 * 60_000L)
            runCurrent()
            assertEquals(1, factory.created.size)
            assertEquals(RelayLinkStatus.UpdateRequired(null), supervisor.relayStatus.value)

            supervisor.close()
        }

    @Test
    fun updateRequired_minimumRecordedBeforeTheClose_isCarried() =
        runTest {
            val (factory, supervisor) = newPairedSupervisor()

            supervisor.connect()
            runCurrent()
            factory.created[0].emitUp()
            runCurrent()
            supervisor.recordClientMinimum(factory.created[0], "1.4.0")
            factory.created[0].emitDown(code = 4412)
            runCurrent()

            assertEquals(RelayLinkStatus.UpdateRequired("1.4.0"), supervisor.relayStatus.value)

            supervisor.close()
        }

    @Test
    fun updateRequired_minimumRecordedAfterTheHalt_upgradesTheState() =
        runTest {
            val (factory, supervisor) = newPairedSupervisor()

            supervisor.connect()
            runCurrent()
            factory.created[0].emitUp()
            runCurrent()
            factory.created[0].emitDown(code = 4412)
            runCurrent()
            assertEquals(RelayLinkStatus.UpdateRequired(null), supervisor.relayStatus.value)

            supervisor.recordClientMinimum(factory.created[0], "2.0.10")
            assertEquals(RelayLinkStatus.UpdateRequired("2.0.10"), supervisor.relayStatus.value)
            assertEquals(1, factory.created.size)

            supervisor.close()
        }

    @Test
    fun updateRequired_invalidMinimum_isTreatedAsAbsent() =
        runTest {
            for (invalid in listOf("1.4", "1.4.0.1", "v1.4.0", "1.4.0-beta", "1234567.0.0", " 1.4.0", "")) {
                val (factory, supervisor) = newPairedSupervisor()

                supervisor.connect()
                runCurrent()
                factory.created[0].emitUp()
                runCurrent()
                supervisor.recordClientMinimum(factory.created[0], invalid)
                factory.created[0].emitDown(code = 4412)
                runCurrent()
                supervisor.recordClientMinimum(factory.created[0], invalid)

                assertEquals("minimum '$invalid'", RelayLinkStatus.UpdateRequired(null), supervisor.relayStatus.value)

                supervisor.close()
            }
        }

    @Test
    fun updateRequired_minimumFromAnEarlierDial_isIgnored() =
        runTest {
            val (factory, supervisor) = newPairedSupervisor()

            supervisor.connect()
            runCurrent()
            factory.created[0].emitDown(code = 4412)
            runCurrent()
            supervisor.retry()
            runCurrent()
            assertEquals(2, factory.created.size)

            // The first connection's late error must not reach the second dial.
            supervisor.recordClientMinimum(factory.created[0], "1.4.0")
            factory.created[1].emitDown(code = 4412)
            runCurrent()
            supervisor.recordClientMinimum(factory.created[0], "1.4.0")

            assertEquals(RelayLinkStatus.UpdateRequired(null), supervisor.relayStatus.value)

            supervisor.close()
        }

    @Test
    fun updateRequiredError_withoutA4412Close_keepsReconnecting() =
        runTest {
            val (factory, supervisor) = newPairedSupervisor()

            supervisor.connect()
            runCurrent()
            factory.created[0].emitUp()
            runCurrent()
            supervisor.recordClientMinimum(factory.created[0], "1.4.0")
            factory.created[0].emitDown(code = 1006)
            runCurrent()

            assertTrue(supervisor.relayStatus.value is RelayLinkStatus.Reconnecting)
            advanceUntilIdle()
            assertEquals(2, factory.created.size)

            supervisor.close()
        }

    @Test
    fun updateRequired_explicitRetryDialsOnce_andARepeatedRejectionHaltsWithoutTheOldMinimum() =
        runTest {
            val (factory, supervisor) = newPairedSupervisor()

            supervisor.connect()
            runCurrent()
            supervisor.recordClientMinimum(factory.created[0], "1.4.0")
            factory.created[0].emitDown(code = 4412)
            runCurrent()
            assertEquals(RelayLinkStatus.UpdateRequired("1.4.0"), supervisor.relayStatus.value)

            supervisor.retry()
            runCurrent()
            assertEquals(RelayLinkStatus.Connecting, supervisor.relayStatus.value)
            assertEquals(2, factory.created.size)

            factory.created[1].emitDown(code = 4412)
            runCurrent()
            advanceTimeBy(10 * 60_000L)
            runCurrent()
            assertEquals(RelayLinkStatus.UpdateRequired(null), supervisor.relayStatus.value)
            assertEquals(2, factory.created.size)

            supervisor.close()
        }

    @Test
    fun updateRequired_nextForegroundConnectDialsOnce() =
        runTest {
            val (factory, supervisor) = newPairedSupervisor()

            supervisor.connect()
            runCurrent()
            factory.created[0].emitDown(code = 4412)
            runCurrent()
            assertTrue(supervisor.relayStatus.value is RelayLinkStatus.UpdateRequired)

            supervisor.close()
            // A late minimum after close cannot resurrect the halted state over Idle.
            supervisor.recordClientMinimum(factory.created[0], "1.4.0")
            assertEquals(RelayLinkStatus.Idle, supervisor.relayStatus.value)
            supervisor.connect()
            runCurrent()
            assertEquals(RelayLinkStatus.Connecting, supervisor.relayStatus.value)
            assertEquals(2, factory.created.size)

            supervisor.close()
        }

    @Test
    fun updateRequiredOnOneHost_leavesAnotherHostsSupervisorRedialling() =
        runTest {
            val (tooOldFactory, tooOld) = newPairedSupervisor()
            val (otherFactory, other) = newPairedSupervisor()

            tooOld.connect()
            other.connect()
            runCurrent()
            tooOldFactory.created[0].emitDown(code = 4412)
            otherFactory.created[0].emitDown(code = 1006)
            runCurrent()

            assertTrue(tooOld.relayStatus.value is RelayLinkStatus.UpdateRequired)
            assertTrue(other.relayStatus.value is RelayLinkStatus.Reconnecting)
            advanceTimeBy(10 * 60_000L)
            runCurrent()
            assertEquals(1, tooOldFactory.created.size)
            assertTrue(otherFactory.created.size > 1)

            tooOld.close()
            other.close()
        }

    // ---- #391: toConnectionState() preserves the four legacy cases; DaemonAbsent -> Offline -------

    @Test
    fun toConnectionState_mapsLegacyCasesIdentityAndDaemonAbsentToOffline() {
        assertEquals(ConnectionState.Connected, RelayLinkStatus.Connected.toConnectionState())
        assertEquals(ConnectionState.Connecting, RelayLinkStatus.Connecting.toConnectionState())
        assertEquals(ConnectionState.Reconnecting(7), RelayLinkStatus.Reconnecting(7).toConnectionState())
        assertEquals(ConnectionState.Offline, RelayLinkStatus.Offline.toConnectionState())
        assertEquals(ConnectionState.Offline, RelayLinkStatus.DaemonAbsent.toConnectionState())
        assertEquals(ConnectionState.Offline, RelayLinkStatus.PairingRejected.toConnectionState())
        assertEquals(ConnectionState.Offline, RelayLinkStatus.UpdateRequired("1.4.0").toConnectionState())
        assertEquals(ConnectionState.Offline, RelayLinkStatus.UpdateRequired(null).toConnectionState())
        // #499 AC#3: idle derives to Connected so the banner stays hidden while unpaired/idle.
        assertEquals(ConnectionState.Connected, RelayLinkStatus.Idle.toConnectionState())
    }

    // ---- #1318: the thread's two-leg mapping — Connected only once the handshake has finished --------

    @Test
    fun connectionStatusToConnectionState_isConnectedOnlyWhenBothLegsAreUp() {
        val pyrycodeLegs = listOf(PyrycodeLinkStatus.Handshaking, PyrycodeLinkStatus.Connected, PyrycodeLinkStatus.Down)
        val relayLegs =
            listOf(
                RelayLinkStatus.Idle,
                RelayLinkStatus.Connecting,
                RelayLinkStatus.Connected,
                RelayLinkStatus.Reconnecting(7),
                RelayLinkStatus.DaemonAbsent,
                RelayLinkStatus.PairingRejected,
                RelayLinkStatus.UpdateRequired("1.4.0"),
                RelayLinkStatus.Offline,
            )
        for (relay in relayLegs) {
            for (pyrycode in pyrycodeLegs) {
                val expected =
                    when {
                        relay == RelayLinkStatus.Connected && pyrycode == PyrycodeLinkStatus.Connected -> ConnectionState.Connected
                        relay == RelayLinkStatus.Connected -> ConnectionState.Connecting
                        else -> relay.toConnectionState()
                    }
                assertEquals("$relay + $pyrycode", expected, ConnectionStatus(relay, pyrycode).toConnectionState())
            }
        }
        // The socket is up but the handshake is not finished: never Connected.
        assertEquals(
            ConnectionState.Connecting,
            ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Handshaking).toConnectionState(),
        )
        // Idle stays Connected and the Reconnecting countdown survives, whatever the pyrycode leg says.
        assertEquals(ConnectionState.Connected, ConnectionStatus(RelayLinkStatus.Idle, PyrycodeLinkStatus.Down).toConnectionState())
        assertEquals(
            ConnectionState.Reconnecting(7),
            ConnectionStatus(RelayLinkStatus.Reconnecting(7), PyrycodeLinkStatus.Down).toConnectionState(),
        )
    }

    // ---- #499 AC 2: the pre-connect() seed reads Idle on the relay leg (never a live-socket Connected)
    @Test
    fun initialState_beforeConnect_relayLegIsIdle() =
        runTest {
            val (_, supervisor) = newPairedSupervisor()
            assertEquals(RelayLinkStatus.Idle, supervisor.relayStatus.value)
        }

    @Test
    fun cappedBackoff_passiveRecoveryCannotBeatRetryProof() =
        runTest {
            for (code in listOf(4404, null)) {
                val (factory, supervisor) = newPairedSupervisor()
                supervisor.connect()
                runCurrent()
                for (attempt in 1..5) {
                    factory.created.last().emitDown(code = code)
                    runCurrent()
                    assertEquals(attempt, supervisor.backoffState.value?.attempt)
                    advanceUntilIdle()
                    assertNull(supervisor.backoffState.value)
                }
                factory.created.last().emitDown(code = code)
                runCurrent()
                assertEquals(6, supervisor.backoffState.value?.attempt)
                advanceTimeBy(20_000)
                runCurrent()
                assertEquals("passive redial cannot satisfy the 20 s proof", 6, factory.created.size)
                assertNull(supervisor.currentConnection.value)
                supervisor.retry()
                runCurrent()
                assertEquals(7, factory.created.size)
                assertNull(supervisor.backoffState.value)
                supervisor.close()
            }
        }

    @Test
    fun cappedBackoff_retainedHistoryDoesNotRequireSixMoreFailures() =
        runTest {
            val (factory, supervisor) = newPairedSupervisor()
            supervisor.connect()
            runCurrent()
            repeat(5) {
                factory.created.last().emitDown(code = 4404)
                runCurrent()
                advanceUntilIdle()
            }
            factory.created.last().emitUp()
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            factory.created.last().emitDown(code = 4404)
            runCurrent()
            assertEquals("short successful connection retains five preceding failures", 6, supervisor.backoffState.value?.attempt)
            supervisor.close()
            assertNull(supervisor.backoffState.value)
            runCurrent()
            assertNull(supervisor.backoffState.value)
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

        // A distinct second server, so a re-pair test can assert the next dial switched targets.
        val PAIRED_B =
            PairedServer(
                serverId = "srv-2",
                token = "tok-2",
                relayUrl = "ws://localhost/relay-2",
                serverStaticPublicKey = "key-2",
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

/**
 * A transport for the #496 interleaving test whose cancellation is held open until the test completes
 * [release]. On [Up][TransportEvent.Up] it publishes to the supervisor's `liveConnection`; when the
 * supervision loop is cancelled it parks in a `NonCancellable` finally so the supervisor's own finally
 * (the compare-and-clear fix site) is deferred past the point the *new* loop publishes its transport —
 * the exact race window the guard closes.
 */
private class GatedRelayTransport(
    private val release: CompletableDeferred<Unit>,
) : RelayTransport {
    override val inbound: Flow<InnerFrameV2> = emptyFlow()
    override val events: Flow<TransportEvent> =
        flow {
            emit(TransportEvent.Up)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { release.await() }
            }
        }

    var closeCalls = 0
        private set

    override fun connect() {}

    override fun send(frame: InnerFrameV2): Boolean = true

    override fun close() {
        closeCalls++
    }
}

/** Serves pre-built [transports] in order across successive [create] calls (one per dial). */
private class ScriptedRelayTransportFactory(
    private val transports: List<RelayTransport>,
) : RelayTransportFactory {
    private var index = 0

    override fun create(pairedServer: PairedServer): RelayTransport = transports[index++]
}

private class FakeRelayTransportFactory : RelayTransportFactory {
    val created = mutableListOf<FakeRelayTransport>()

    // The PairedServer each dial targeted, so a test can assert which server dial N read from the store.
    val createdWith = mutableListOf<PairedServer>()

    override fun create(pairedServer: PairedServer): RelayTransport =
        FakeRelayTransport().also {
            created += it
            createdWith += pairedServer
        }
}

private class StubPairedServerStore(
    private val paired: PairedServer?,
) : PairedServerStore {
    override suspend fun load(): PairedServer? = paired

    override suspend fun save(record: PairedServer) = error("save is not exercised by the supervisor")
}

/** Returns [records] in order across successive [load] calls, clamping to the last once exhausted —
 *  so a test can script the paired record changing (or vanishing) between dials. */
private class ScriptedPairedServerStore(
    private val records: List<PairedServer?>,
) : PairedServerStore {
    private var index = 0

    override suspend fun load(): PairedServer? = records[minOf(index++, records.lastIndex)]

    override suspend fun save(record: PairedServer) = error("save is not exercised by the supervisor")
}
