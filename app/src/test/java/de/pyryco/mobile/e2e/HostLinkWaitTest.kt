package de.pyryco.mobile.e2e

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HostLinkWaitTest {
    @Test
    fun timeoutNamesRepositoryWaitAndCurrentRegistrationWithoutRetry() =
        runTest {
            val available = MutableStateFlow(false)
            var calls = 0
            var snapshot = "registered=true socket=false daemon=Down repository=false"
            val records = mutableListOf<String>()
            val wait =
                async {
                    runCatching {
                        awaitHostLinkChange(true, 30_000, { calls++ }, available, { snapshot }, records::add)
                    }.exceptionOrNull()
                }
            runCurrent()
            snapshot = "registered=false socket=true daemon=Handshaking repository=false"
            val failure = wait.await()
            assertTrue(failure is AssertionError)
            assertTrue(failure?.cause is TimeoutCancellationException)
            assertEquals("host link reconnect timed out; stage=repository; $snapshot", failure?.message)
            assertEquals(30_000L, currentTime)
            assertEquals(1, calls)
            assertEquals(3, records.size)
            assertTrue(records.last().contains("stage=repository outcome=timeout"))
        }

    @Test
    fun restoreWaitsForPublicationAndCloseWaitsForAbsence() =
        runTest {
            for (up in listOf(true, false)) {
                val available = MutableStateFlow(!up)
                val records = mutableListOf<String>()
                val wait = async { awaitHostLinkChange(up, 30_000, {}, available, { "content-free" }, records::add) }
                runCurrent()
                assertTrue(!wait.isCompleted)
                available.value = up
                wait.await()
                assertEquals(3, records.size)
                assertTrue(records.last().contains("stage=repository outcome=complete"))
            }
            assertEquals(0L, currentTime)
        }

    @Test
    fun ordinaryFailureAndCancellationPropagateUnchanged() =
        runTest {
            for (failure in listOf(IllegalStateException("fixture refusal"), CancellationException("fixture cancellation"))) {
                var calls = 0
                val caught =
                    runCatching {
                        awaitHostLinkChange(true, 30_000, {
                            calls++
                            throw failure
                        }, MutableStateFlow(false), { "static" }, {})
                    }.exceptionOrNull()
                assertEquals(failure.javaClass, caught?.javaClass)
                assertEquals(failure.message, caught?.message)
                assertEquals(1, calls)
            }
        }
}
