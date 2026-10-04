package de.pyryco.mobile.e2e

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveSetupStepTest {
    @Test
    fun `connection timeout names readiness rather than progress arrival`() =
        runTest {
            val failure =
                runCatching {
                    liveSetupStep(LiveSetupStage.ConnectionReadiness) { withTimeout(TIMEOUT_MS) { awaitCancellation() } }
                }.exceptionOrNull()

            assertTrue(failure is AssertionError)
            assertEquals("background progress setup 'phone connection readiness' timed out", failure?.message)
            assertTrue(failure?.cause is TimeoutCancellationException)
            assertEquals(TIMEOUT_MS, currentTime)
        }

    @Test
    fun `chat creation timeout names the repository operation`() =
        runTest {
            val failure =
                runCatching {
                    liveSetupStep(LiveSetupStage.ChatCreation) { withTimeout(TIMEOUT_MS) { awaitCancellation() } }
                }.exceptionOrNull()

            assertTrue(failure is AssertionError)
            assertEquals("background progress setup 'chat creation' timed out", failure?.message)
            assertTrue(failure?.cause is TimeoutCancellationException)
            assertEquals(TIMEOUT_MS, currentTime)
        }

    @Test
    fun `unsettled peer names opening and never reaches progress or rendering`() =
        runTest {
            var attempts = 0
            var reachedProgress = false
            val link =
                RedialingLink<Unit>(
                    scope = backgroundScope,
                    dial = {
                        attempts += 1
                        null
                    },
                    awaitEnd = { error("an unsettled link cannot end") },
                    release = { error("no settled link to release") },
                )
            try {
                val failure =
                    runCatching {
                        liveSetupStep(LiveSetupStage.ConnectionReadiness) { Unit }
                        val chat = liveSetupStep(LiveSetupStage.ChatCreation) { "synthetic-chat" }
                        assertEquals("synthetic-chat", chat)
                        liveSetupStep(LiveSetupStage.PeerOpening, link::describe) {
                            withTimeout(TIMEOUT_MS) { link.start() }
                        }
                        reachedProgress = true
                    }.exceptionOrNull()

                assertTrue(failure is AssertionError)
                assertEquals(
                    "background progress setup 'peer opening' timed out; no open session: none settled yet; redialing",
                    failure?.message,
                )
                assertTrue(failure?.cause is TimeoutCancellationException)
                assertTrue("the opening wait retried rejected dials", attempts > 1)
                assertFalse("a setup failure cannot exercise card rendering", reachedProgress)
                assertEquals(TIMEOUT_MS, currentTime)
            } finally {
                link.close()
            }
        }

    @Test
    fun `successful setup returns its result and never reads failure diagnostics`() {
        val expected = Any()
        val actual =
            liveSetupStep(LiveSetupStage.ChatCreation, { error("only inspect diagnostics after a timeout") }) { expected }

        assertSame(expected, actual)
    }

    @Test
    fun `a refusal keeps its original failure rather than becoming a timeout`() {
        val refusal = IllegalStateException("synthetic refusal")
        val failure = runCatching { liveSetupStep(LiveSetupStage.ChatCreation) { throw refusal } }.exceptionOrNull()

        assertSame(refusal, failure)
    }

    @Test
    fun `ordinary cancellation keeps its original cause`() {
        val cancelled = CancellationException("synthetic cancellation")
        val failure = runCatching { liveSetupStep(LiveSetupStage.ConnectionReadiness) { throw cancelled } }.exceptionOrNull()

        assertSame(cancelled, failure)
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}
