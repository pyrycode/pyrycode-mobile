package de.pyryco.mobile.e2e

import androidx.compose.ui.test.ComposeTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SendNowStepTest {
    @Test
    fun coroutineTimeoutNamesStageAndCurrentLinksAtOriginalDeadline() =
        runTest {
            for (stage in SendNowStage.entries) {
                val started = currentTime
                var reads = 0
                var calls = 0
                var links = "initial"
                val failure =
                    runCatching {
                        sendNowStep(stage, {
                            reads += 1
                            links
                        }) {
                            calls += 1
                            withTimeout(30_000) {
                                delay(10_000)
                                links = LINKS
                                awaitCancellation()
                            }
                        }
                    }.exceptionOrNull()

                assertTrue(failure is AssertionError)
                assertEquals("Send now step '${stage.label}' timed out; $LINKS", failure?.message)
                assertTrue(failure?.cause is TimeoutCancellationException)
                assertEquals(30_000, currentTime - started)
                assertEquals(1, reads)
                assertEquals("never retry the failing operation", 1, calls)
            }
        }

    @Test
    fun composeTimeoutNamesStageAndPreservesCause() {
        val timeout = ComposeTimeoutException("fixture content stays in the cause")
        val failure =
            runCatching {
                sendNowStep(SendNowStage.AwaitSendNow, { LINKS }) { throw timeout }
            }.exceptionOrNull()

        assertEquals("Send now step 'await phone Send now control' timed out; $LINKS", failure?.message)
        assertSame(timeout, failure?.cause)
    }

    @Test
    fun permissionAwareTimeoutKeepsItsCauseAndGetsStageLinks() =
        runTest {
            val failure =
                runCatching {
                    sendNowStep(SendNowStage.AwaitHeldTool, { LINKS }) {
                        withTimeoutDiagnostic({ "held tool wait expired" }) {
                            withTimeout(30_000) { awaitCancellation() }
                        }
                    }
                }.exceptionOrNull()
            assertEquals("Send now step 'await held Bash progress' timed out; $LINKS", failure?.message)
            assertTrue(failure?.cause is AssertionError)
            assertTrue(failure?.cause?.cause is TimeoutCancellationException)
            assertEquals(30_000, currentTime)
        }

    @Test
    fun successDoesNotReadDiagnostics() {
        val value = Any()
        assertSame(value, sendNowStep(SendNowStage.OpenPeer, { error("must stay lazy") }) { value })
    }

    @Test
    fun cancellationPropagatesWithoutRetryOrDiagnostics() {
        assertPropagates(CancellationException("cancelled"))
    }

    @Test
    fun nonTimeoutFailurePropagatesWithoutRetryOrDiagnostics() {
        assertPropagates(IllegalStateException("static refusal"))
        val assertion = AssertionError("proof failed")
        assertSame(
            assertion,
            runCatching {
                sendNowStep(SendNowStage.AwaitTurnEnd, { error("must stay lazy") }) { throw assertion }
            }.exceptionOrNull(),
        )
    }

    private fun assertPropagates(failure: Exception) {
        var calls = 0
        val caught =
            runCatching {
                sendNowStep(SendNowStage.SendHeldTurn, { error("must stay lazy") }) {
                    calls += 1
                    throw failure
                }
            }.exceptionOrNull()
        assertSame(failure, caught)
        assertEquals(1, calls)
    }

    private companion object {
        const val LINKS =
            "phone host=true selected=true relay=Connected daemon=Connected repository=true; " +
                "peer=session open (link 2, replaced 1×)"
    }
}
