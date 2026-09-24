package de.pyryco.mobile.e2e

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1029: the live-e2e reads follow a host's redial instead of staying on a torn-down connection. */
class LiveConnectionReadsTest {
    /** One connection's source: a menu-like projection and a call that can fail. */
    private class Source(
        val name: String,
    ) {
        val menu = MutableStateFlow<List<String>?>(null)
        var failure: Exception? = null
        var calls = 0
    }

    private suspend fun Source.call(): String {
        calls += 1
        failure?.let { throw it }
        return name
    }

    @Test
    fun `firstOnLive reads the replacement when the connection it started on is replaced`() =
        runTest {
            val first = Source("first")
            val second = Source("second")
            val live = MutableStateFlow<Source?>(first)
            val read = async { live.firstOnLive({ it.menu }) { it.isNotEmpty() } }
            runCurrent()

            live.value = second
            second.menu.value = listOf("/compact")
            runCurrent()

            assertEquals(listOf("/compact"), read.await())
        }

    @Test
    fun `firstOnLive waits through no connection and skips values it does not accept`() =
        runTest {
            val source = Source("only")
            val live = MutableStateFlow<Source?>(null)
            val read = async { live.firstOnLive({ it.menu }) { it.isNotEmpty() } }
            runCurrent()

            live.value = source
            source.menu.value = emptyList()
            runCurrent()
            assertFalse("an empty menu was accepted", read.isCompleted)

            source.menu.value = listOf("/clear")
            runCurrent()
            assertEquals(listOf("/clear"), read.await())
        }

    @Test
    fun `callOnLive retries on the replacement when the call fails and the connection is replaced`() =
        runTest {
            val first = Source("first").apply { failure = IllegalStateException("connection torn down before reply") }
            val second = Source("second")
            val live = MutableStateFlow<Source?>(first)
            val result = async { live.callOnLive(REPLACEMENT_WAIT_MS) { it.call() } }
            runCurrent()
            assertFalse(result.isCompleted)

            live.value = second
            runCurrent()

            assertEquals("second", result.await())
            assertEquals(1, first.calls)
        }

    @Test
    fun `callOnLive rethrows the failure when the connection is not replaced in time`() =
        runTest {
            val failure = IllegalStateException("refused")
            val source = Source("only").apply { this.failure = failure }
            val live = MutableStateFlow<Source?>(source)
            val result = async { runCatching { live.callOnLive(REPLACEMENT_WAIT_MS) { it.call() } } }

            advanceTimeBy(REPLACEMENT_WAIT_MS + 1)
            runCurrent()

            assertTrue(result.isCompleted)
            assertSame(failure, result.await().exceptionOrNull())
            assertEquals("a failure on a connection that stayed up was retried", 1, source.calls)
        }

    private companion object {
        const val REPLACEMENT_WAIT_MS = 10_000L
    }
}
