package de.pyryco.mobile.e2e

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EmulatorCpuReadinessTest {
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
