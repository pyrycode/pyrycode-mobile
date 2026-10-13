package de.pyryco.mobile.e2e

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EmulatorCpuReadinessTest {
    private class Source : Closeable {
        val closes = AtomicInteger()
        val closed = CountDownLatch(1)

        override fun close() {
            closes.incrementAndGet()
            closed.countDown()
        }
    }

    @Test fun heldAcquisitionCannotHoldTheReadinessDeadline() =
        runTest {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val source = Source()
            // Release the broken synchronous baseline so a red run cannot hang the suite.
            val emergency =
                backgroundScope.launch(Dispatchers.IO) {
                    delay(1500)
                    release.countDown()
                }
            val attempt =
                async {
                    runCatching {
                        awaitEmulatorCpuIdle(sample = {
                            withEmulatorCpuSample(acquire = {
                                entered.countDown()
                                check(release.await(3, TimeUnit.SECONDS))
                                source
                            }, read = { error("expired acquisition must never be read") })
                        })
                    }
                }
            try {
                runCurrent()
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                assertEquals("acquisition must still be held while the clock advances", 1L, release.count)
                advanceTimeBy(30000)
                runCurrent()
                assertTrue(attempt.isCompleted)
                assertTrue(attempt.await().exceptionOrNull() is TimeoutCancellationException)
                assertEquals(30000, testScheduler.currentTime)
                assertEquals(0, source.closes.get())
            } finally {
                release.countDown()
                emergency.cancelAndJoin()
            }
            withContext(Dispatchers.IO) { assertTrue(source.closed.await(3, TimeUnit.SECONDS)) }
            assertEquals(1, source.closes.get())
        }

    @Test fun heldReadClosesItsSourceAtTheReadinessDeadline() =
        runTest {
            val source = Source()
            val reading = CountDownLatch(1)
            val attempt =
                async {
                    runCatching {
                        awaitEmulatorCpuIdle(sample = {
                            withEmulatorCpuSample(acquire = { source }, read = {
                                reading.countDown()
                                readEmulatorCpuTicks { _, _, _ -> 0 }
                            }, dispatcher = StandardTestDispatcher(testScheduler))
                        })
                    }
                }
            runCurrent()
            assertEquals(0L, reading.count)
            advanceTimeBy(30000)
            runCurrent()
            assertTrue(attempt.await().exceptionOrNull() is TimeoutCancellationException)
            assertEquals(30000, testScheduler.currentTime)
            assertEquals(1, source.closes.get())
        }

    @Test fun cancellationDuringAcquisitionClosesTheLateSourceWithoutReadingIt() =
        runTest {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val source = Source()
            val attempt =
                launch {
                    withEmulatorCpuSample(acquire = {
                        entered.countDown()
                        check(release.await(3, TimeUnit.SECONDS))
                        source
                    }, read = { error("canceled acquisition must never be read") })
                }
            try {
                runCurrent()
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                attempt.cancelAndJoin()
                assertTrue(attempt.isCancelled)
                assertEquals(0, source.closes.get())
            } finally {
                release.countDown()
            }
            withContext(Dispatchers.IO) { assertTrue(source.closed.await(3, TimeUnit.SECONDS)) }
            assertEquals(1, source.closes.get())
        }

    @Test fun cancellationDuringReadClosesTheSource() =
        runTest {
            val source = Source()
            val reading = CountDownLatch(1)
            val attempt =
                launch {
                    withEmulatorCpuSample(acquire = { source }, read = {
                        reading.countDown()
                        awaitCancellation()
                    }, dispatcher = StandardTestDispatcher(testScheduler))
                }
            runCurrent()
            assertEquals(0L, reading.count)
            attempt.cancelAndJoin()
            assertEquals(1, source.closes.get())
        }

    @Test fun readyWindowsCloseEverySource() =
        runTest {
            val sources = mutableListOf<Source>()
            var ticks = 0L
            awaitEmulatorCpuIdle(sample = {
                withEmulatorCpuSample(acquire = { Source().also(sources::add) }, read = {
                    ticks += 100
                    ticks to ticks
                }, dispatcher = StandardTestDispatcher(testScheduler))
            })
            assertEquals(5, sources.size)
            assertTrue(sources.all { it.closes.get() == 1 })
            assertEquals(1000, testScheduler.currentTime)
        }

    @Test fun malformedReadFailsAndClosesTheSource() =
        runTest {
            val source = Source()
            val result =
                runCatching {
                    withEmulatorCpuSample(
                        acquire = { source },
                        read = { parseEmulatorCpuTicks("invalid") },
                        dispatcher = StandardTestDispatcher(testScheduler),
                    )
                }
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
            assertEquals(1, source.closes.get())
        }

    @Test fun partialNonblockingReadsUseOnlyTheBoundedCpuLine() =
        runTest {
            val chunks = ArrayDeque(listOf("", "cpu 1 2 3", " 4 5 6 7 8\nother data"))
            val ticks =
                readEmulatorCpuTicks { bytes, offset, _ ->
                    val chunk = chunks.removeFirst().encodeToByteArray()
                    chunk.copyInto(bytes, offset)
                    chunk.size
                }
            assertEquals(36L to 9L, ticks)
            assertEquals(10, testScheduler.currentTime)
        }

    @Test fun eofAndOverlongCpuLinesRejectReadiness() =
        runTest {
            for (count in listOf(-1, 4096)) {
                val result = runCatching { readEmulatorCpuTicks { _, _, _ -> count } }
                assertTrue(result.exceptionOrNull() is IllegalArgumentException)
            }
        }

    @Test fun cpuTicksExcludeGuestTimesAlreadyCountedInUserTimes() {
        assertEquals(36L to 9L, parseEmulatorCpuTicks("cpu  1 2 3 4 5 6 7 8 9 10"))
    }

    @Test fun shortOrMalformedCountersRejectReadiness() {
        for (line in listOf("permission denied", "cpu 1 2", "cpu 1 2 3 bad 5 6 7 8")) {
            assertThrows(IllegalArgumentException::class.java) { parseEmulatorCpuTicks(line) }
        }
    }

    @Test fun busyWindowRestartsTheFourConsecutiveIdleWindows() =
        runTest {
            var calls = 0
            var total = 0L
            var idle = 0L
            awaitEmulatorCpuIdle(sample = {
                calls++
                total += 100
                idle += if (calls == 4) 0 else 90
                total to idle
            })
            assertEquals(8, calls)
            assertEquals(1750, testScheduler.currentTime)
        }

    @Test fun continuouslyBusyDeviceFailsInsteadOfChangingTheDrawBound() =
        runTest {
            var total = 0L
            try {
                awaitEmulatorCpuIdle(sample = {
                    total += 100
                    total to 0L
                })
                error("busy device must fail readiness")
            } catch (_: TimeoutCancellationException) {
                assertEquals(30000, testScheduler.currentTime)
            }
        }
}
