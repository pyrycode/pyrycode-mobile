package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerStore
import de.pyryco.mobile.data.network.InnerFrameV2
import de.pyryco.mobile.data.network.RelayBackoff
import de.pyryco.mobile.data.network.RelayConnectionSupervisor
import de.pyryco.mobile.data.network.RelayTransport
import de.pyryco.mobile.data.network.RelayTransportFactory
import de.pyryco.mobile.data.network.TransportEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineRetryWindowTest {
    @Test
    fun immediateDaemonAbsentDials_doNotRequireEveryConnectingEmission() =
        runTest {
            var dials = 0
            val supervisor =
                RelayConnectionSupervisor(
                    transportFactory =
                        RelayTransportFactory {
                            dials++
                            object : RelayTransport {
                                override val inbound = emptyFlow<InnerFrameV2>()

                                // Already-buffered terminal dial failure: no suspension between Connecting and backoff.
                                override val events = flowOf(TransportEvent.Down(4404, null, null))

                                override fun connect() {}

                                override fun send(frame: InnerFrameV2) = false

                                override fun close() {}
                            }
                        },
                    pairedServerStore =
                        object : PairedServerStore {
                            override suspend fun load() = PairedServer("test", "test", "ws://localhost", "test")

                            override suspend fun save(record: PairedServer) {}
                        },
                    dispatcher = StandardTestDispatcher(testScheduler),
                    random = Random(42),
                )
            val deadline = async { runCatching { OfflineRetryWindow.awaitDeadline(supervisor) } }
            runCurrent()
            supervisor.connect()
            try {
                advanceTimeBy(90_001)
                runCurrent()
                assertTrue("real dials reached the cap: $dials", dials >= 6)
                assertTrue("the exact Retry observer must resolve the actual capped wait", deadline.isCompleted)
                val result = deadline.await()
                assertTrue(
                    "dials=$dials, status=${supervisor.relayStatus.value}, " +
                        "observation=${result.exceptionOrNull()}",
                    result.isSuccess,
                )
            } finally {
                supervisor.close()
            }
        }

    @Test
    fun delayedObservationAndStartup_consumeTheOriginalBudget() {
        val clock = TestTimeSource()
        val backoff = RelayBackoff(6, clock.markNow())
        clock += 7.seconds
        val deadline = OfflineRetryWindow.deadlineFor(backoff)
        assertEquals(13_000L, OfflineRetryWindow.remainingMs(deadline))
        clock += 12.seconds
        assertEquals(1_000L, OfflineRetryWindow.remainingMs(deadline))
        clock += 1.seconds
        assertThrows(IllegalStateException::class.java) { OfflineRetryWindow.remainingMs(deadline) }
    }

    @Test
    fun subcapBackoff_cannotSupplyARetryProofDeadline() {
        assertThrows(IllegalStateException::class.java) {
            OfflineRetryWindow.deadlineFor(RelayBackoff(5, TestTimeSource().markNow()))
        }
    }
}
