package de.pyryco.mobile.e2e

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
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

    // #1063: assertPeerAnswers labels both ways a peer's request goes unanswered as a relay or daemon fault.
    private suspend fun <T> answered(block: suspend () -> T): T = requirePeerAnswer(TIMEOUT_MS) { await(block) }

    @Test
    fun `a peer that answers returns its answer`() =
        runTest {
            assertEquals("page", answered { "page" })
            assertEquals(0L, currentTime)
        }

    @Test
    fun `a peer closed during its request fails at once as a relay or daemon fault`() =
        runTest {
            val wait = async { runCatching { answered { awaitCancellation() } } }
            runCurrent()

            closed.value = true
            runCurrent()

            assertTrue(wait.isCompleted)
            assertClosedFault(wait.await().exceptionOrNull())
        }

    @Test
    fun `a peer already closed fails at once as a relay or daemon fault`() =
        runTest {
            closed.value = true

            assertClosedFault(runCatching { answered { awaitCancellation() } }.exceptionOrNull())
        }

    @Test
    fun `a peer that never answers keeps its timeout label`() =
        runTest {
            val error = runCatching { answered { awaitCancellation() } }.exceptionOrNull()

            assertTrue("$error", error is AssertionError)
            assertEquals(
                "the peer's open session answered no request within $TIMEOUT_MS ms: a relay or daemon fault",
                error?.message,
            )
            assertEquals(TIMEOUT_MS, currentTime)
        }

    @Test
    fun `a peer's refusal is not relabelled`() =
        runTest {
            val error = runCatching { answered<Unit> { error("peer request_history refused: unknown_conversation") } }.exceptionOrNull()

            assertTrue("$error", error is IllegalStateException)
            assertEquals("peer request_history refused: unknown_conversation", error?.message)
        }

    private fun TestScope.assertClosedFault(error: Throwable?) {
        assertTrue("$error", error is AssertionError)
        assertEquals("the peer's session closed before it answered a request: a relay or daemon fault", error?.message)
        assertEquals(0L, currentTime)
    }

    private companion object {
        const val TIMEOUT_MS = 90_000L
        const val POLL_MS = 250L
    }
}
