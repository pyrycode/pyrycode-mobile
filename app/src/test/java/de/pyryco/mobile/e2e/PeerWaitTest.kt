package de.pyryco.mobile.e2e

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** #1059: a live peer's wait fails at once, naming what it awaited, when the peer closes under it. */
class PeerWaitTest {
    private val closed = MutableStateFlow(false)

    private suspend fun <T> await(block: suspend () -> T): T = awaitPeer("modal_shown", TIMEOUT_MS, closed, block)

    @Test
    fun `a wait that is answered returns the answer`() =
        runTest {
            val answer = CompletableDeferred<String>()
            val wait = async { await { answer.await() } }
            runCurrent()

            answer.complete("modal-1")

            assertEquals("modal-1", wait.await())
            assertEquals(0L, currentTime)
        }

    @Test
    fun `closing the peer fails a pending wait at once, naming its frame`() =
        runTest {
            val wait = async { runCatching { await { awaitCancellation() } } }
            runCurrent()

            closed.value = true
            runCurrent()

            assertTrue(wait.isCompleted)
            val error = wait.await().exceptionOrNull()
            assertTrue("$error", error is AssertionError)
            assertTrue("${error?.message}", error?.message.orEmpty().contains("closed while awaiting modal_shown"))
            assertEquals(0L, currentTime)
        }

    @Test
    fun `closing the peer fails a polling wait at once, naming its frame`() =
        runTest {
            // #1064: allowPromptsUntil polls a snapshot of the peer's frames and delays between polls.
            val frames = MutableStateFlow(emptyList<String>())
            val wait =
                async {
                    runCatching {
                        awaitPeer("background_task_started", TIMEOUT_MS, closed) {
                            while (frames.value.none { it == "background_task_started" }) delay(POLL_MS)
                        }
                    }
                }
            advanceTimeBy(3 * POLL_MS)

            closed.value = true
            runCurrent()

            assertTrue(wait.isCompleted)
            val error = wait.await().exceptionOrNull()
            assertTrue("$error", error is AssertionError)
            assertTrue("${error?.message}", error?.message.orEmpty().contains("closed while awaiting background_task_started"))
            assertTrue("$currentTime", currentTime < TIMEOUT_MS)
        }

    @Test
    fun `a wait on an already closed peer fails at once`() =
        runTest {
            closed.value = true

            val error = runCatching { await { awaitCancellation() } }.exceptionOrNull()

            assertTrue("$error", error is AssertionError)
            assertTrue("${error?.message}", error?.message.orEmpty().contains("closed while awaiting modal_shown"))
            assertEquals(0L, currentTime)
        }

    @Test
    fun `a wait on an open peer that runs out times out as before`() =
        runTest {
            // Scenarios catch the timeout to name their step, so it must stay a TimeoutCancellationException.
            val error = runCatching { await { awaitCancellation() } }.exceptionOrNull()

            assertTrue("$error", error is TimeoutCancellationException)
            assertEquals(TIMEOUT_MS, currentTime)
        }

    @Test
    fun `a failure of the wait itself passes through unchanged`() =
        runTest {
            val refusal = IllegalStateException("peer send_message refused: rate_limited")

            try {
                await<Unit> { throw refusal }
                fail("the refusal was swallowed")
            } catch (e: IllegalStateException) {
                // Coroutine stack-trace recovery may hand back a copy, so compare the message, not identity.
                assertEquals(refusal.message, e.message)
            }
        }

    private companion object {
        const val TIMEOUT_MS = 90_000L
        const val POLL_MS = 250L
    }
}
