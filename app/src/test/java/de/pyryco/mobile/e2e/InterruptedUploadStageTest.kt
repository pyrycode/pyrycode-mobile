package de.pyryco.mobile.e2e

import androidx.compose.ui.test.ComposeTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InterruptedUploadStageTest {
    private val records = mutableListOf<String>()
    private var snapshots = 0

    private fun links(): String {
        snapshots++
        return "host=true selected=false relay=Connected daemon=Handshaking repository=false"
    }

    @Test
    fun successRunsOnceReturnsValueAndDoesNotReadFailureSnapshot() =
        runTest {
            var calls = 0
            val value = interruptedUploadStep(InterruptedUploadStage.Retry, ::links, records::add) { ++calls }
            assertEquals(1, value)
            assertEquals(1, calls)
            assertEquals(0, snapshots)
            assertEquals(
                listOf("event=interrupted_upload stage=Retry outcome=start", "event=interrupted_upload stage=Retry outcome=pass"),
                records,
            )
        }

    @Test
    fun repeatedStagesRunEachOperationOnce() =
        runTest {
            var calls = 0
            repeat(2) { interruptedUploadStep(InterruptedUploadStage.PeerHistory, ::links, records::add) { calls++ } }
            assertEquals(2, calls)
            assertEquals(4, records.size)
        }

    @Test
    fun timeoutPreservesDeadlineCauseAndSingleExecution() =
        runTest {
            var calls = 0
            var original: TimeoutCancellationException? = null
            val failure =
                runCatching {
                    interruptedUploadStep(InterruptedUploadStage.SetupReadiness, ::links, records::add) {
                        calls++
                        try {
                            withTimeout(50) { delay(51) }
                        } catch (e: TimeoutCancellationException) {
                            original = e
                            throw e
                        }
                    }
                }.exceptionOrNull()
            assertTrue(failure is AssertionError)
            assertSame(original, failure?.cause)
            assertEquals(50L, currentTime)
            assertEquals(1, calls)
            assertTrue(failure?.message.orEmpty().contains("stage=SetupReadiness outcome=timeout"))
            assertEquals(1, snapshots)
        }

    @Test
    fun composeTimeoutRetainsOriginalCause() =
        runTest {
            val original = ComposeTimeoutException("private composer detail")
            val failure =
                runCatching {
                    interruptedUploadStep(InterruptedUploadStage.ComposerRetention, ::links, records::add) { throw original }
                }.exceptionOrNull()
            assertTrue(failure is AssertionError)
            assertSame(original, failure?.cause)
            assertFalse(records.joinToString().contains("private composer detail"))
            assertFalse(failure?.message.orEmpty().contains("private composer detail"))
        }

    @Test
    fun assertionRetainsOriginalCauseWithoutPublishingItsContent() =
        runTest {
            val original = AssertionError("private file or prompt detail")
            val failure =
                runCatching {
                    interruptedUploadStep(InterruptedUploadStage.FinalHistory, ::links, records::add) { throw original }
                }.exceptionOrNull()
            assertSame(original, failure?.cause)
            assertTrue(records.last().contains("stage=FinalHistory outcome=failed"))
            assertFalse(records.joinToString().contains("private file or prompt detail"))
            assertFalse(failure?.message.orEmpty().contains("private file or prompt detail"))
        }

    @Test
    fun operationFailureRunsOnceAndRetainsCause() =
        runTest {
            val original = IllegalStateException("private peer detail")
            var calls = 0
            val failure =
                runCatching {
                    interruptedUploadStep(InterruptedUploadStage.Retrieval, ::links, records::add) {
                        calls++
                        throw original
                    }
                }.exceptionOrNull()
            assertSame(original, failure?.cause)
            assertEquals(1, calls)
            assertFalse(records.joinToString().contains("private peer detail"))
        }

    @Test
    fun callerCancellationPropagatesUnchanged() =
        runTest {
            val original = CancellationException("caller cancelled")
            val failure =
                runCatching {
                    interruptedUploadStep(InterruptedUploadStage.RetryReadiness, ::links, records::add) { throw original }
                }.exceptionOrNull()
            assertSame(original, failure)
            assertEquals(0, snapshots)
        }

    @Test
    fun callerTimeoutOrNullReturnsNullWithoutFailureDiagnostics() =
        runTest {
            var calls = 0
            val value =
                withTimeoutOrNull(50) {
                    interruptedUploadStep(InterruptedUploadStage.Reconnect, ::links, records::add) {
                        calls++
                        delay(51)
                        true
                    }
                }
            assertNull(value)
            assertEquals(50L, currentTime)
            assertEquals(1, calls)
            assertEquals(0, snapshots)
            assertEquals(listOf("event=interrupted_upload stage=Reconnect outcome=start"), records)
        }

    @Test
    fun callerTimeoutRetainsExactCancellation() =
        runTest {
            var calls = 0
            var original: TimeoutCancellationException? = null
            var propagated: Throwable? = null
            val failure =
                runCatching {
                    withTimeout(50) {
                        try {
                            interruptedUploadStep(InterruptedUploadStage.RetryReadiness, ::links, records::add) {
                                calls++
                                try {
                                    delay(51)
                                } catch (e: TimeoutCancellationException) {
                                    original = e
                                    throw e
                                }
                            }
                        } catch (e: Throwable) {
                            propagated = e
                            throw e
                        }
                    }
                }.exceptionOrNull()
            assertTrue(failure is TimeoutCancellationException)
            // The enclosing withTimeout may recover a stack trace; inspect identity at the boundary.
            assertSame(original, propagated)
            assertEquals(50L, currentTime)
            assertEquals(1, calls)
            assertEquals(0, snapshots)
            assertEquals(1, records.size)
        }

    @Test
    fun wrappedCallerTimeoutStillReturnsNull() =
        runTest {
            var calls = 0
            val value =
                withTimeoutOrNull(50) {
                    interruptedUploadStep(InterruptedUploadStage.PeerHistory, ::links, records::add) {
                        calls++
                        try {
                            delay(51)
                        } catch (e: TimeoutCancellationException) {
                            throw AssertionError("private history detail", e)
                        }
                    }
                }
            assertNull(value)
            assertEquals(50L, currentTime)
            assertEquals(1, calls)
            assertEquals(0, snapshots)
            assertEquals(1, records.size)
        }

    @Test
    fun asynchronousCallerCancellationPropagatesThroughAnOperationWrapper() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val observed = CompletableDeferred<Throwable?>()
            val original = CancellationException("private caller detail")
            var calls = 0
            val job =
                launch {
                    observed.complete(
                        runCatching {
                            interruptedUploadStep(InterruptedUploadStage.ChunkCut, ::links, records::add) {
                                calls++
                                started.complete(Unit)
                                try {
                                    awaitCancellation()
                                } catch (e: CancellationException) {
                                    throw AssertionError("private chunk detail", e)
                                }
                            }
                        }.exceptionOrNull(),
                    )
                }
            started.await()
            job.cancel(original)
            job.join()
            assertSame(original, observed.await())
            assertEquals(1, calls)
            assertEquals(0, snapshots)
            assertEquals(1, records.size)
        }

    @Test
    fun wrappedOperationDeadlineRetainsExactWrapperAndDeadline() =
        runTest {
            var calls = 0
            var original: AssertionError? = null
            var timeout: TimeoutCancellationException? = null
            val failure =
                runCatching {
                    interruptedUploadStep(InterruptedUploadStage.TurnCompletion, ::links, records::add) {
                        calls++
                        try {
                            withTimeout(50) { delay(51) }
                        } catch (e: TimeoutCancellationException) {
                            timeout = e
                            val wrapper = AssertionError("private turn detail", e)
                            original = wrapper
                            throw wrapper
                        }
                    }
                }.exceptionOrNull()
            assertSame(original, failure?.cause)
            assertSame(timeout, failure?.cause?.cause)
            assertEquals(50L, currentTime)
            assertEquals(1, calls)
            assertTimeoutRecord(failure, InterruptedUploadStage.TurnCompletion)
        }

    @Test
    fun multiplyWrappedCoroutineTimeoutRetainsEntireChain() =
        runTest {
            val timeout = checkNotNull(runCatching { withTimeout(50) { delay(51) } }.exceptionOrNull())
            verifyTimeoutChain(timeout, InterruptedUploadStage.Reconnect)
        }

    @Test
    fun wrappedComposeTimeoutRetainsExactWrapper() =
        runTest {
            val timeout = ComposeTimeoutException("private composer detail")
            val original = AssertionError("private retention detail", timeout)
            var calls = 0
            val failure =
                runCatching {
                    interruptedUploadStep(InterruptedUploadStage.ComposerRetention, ::links, records::add) {
                        calls++
                        throw original
                    }
                }.exceptionOrNull()
            assertSame(original, failure?.cause)
            assertSame(timeout, failure?.cause?.cause)
            assertEquals(1, calls)
            assertTimeoutRecord(failure, InterruptedUploadStage.ComposerRetention)
        }

    @Test
    fun multiplyWrappedComposeTimeoutRetainsEntireChain() =
        runTest {
            verifyTimeoutChain(ComposeTimeoutException("private compose detail"), InterruptedUploadStage.ChunkCut)
        }

    @Test
    fun cyclicOrdinaryCausesTerminateWithoutChangingTheChain() =
        runTest {
            val original = AssertionError("private assertion detail")
            val nested = IllegalStateException("private nested detail", original)
            original.initCause(nested)
            var calls = 0
            val failure =
                runCatching {
                    interruptedUploadStep(InterruptedUploadStage.FinalHistory, ::links, records::add) {
                        calls++
                        throw original
                    }
                }.exceptionOrNull()
            assertSame(original, failure?.cause)
            assertSame(nested, original.cause)
            assertSame(original, nested.cause)
            assertEquals(1, calls)
            assertEquals(1, snapshots)
            assertTrue(records.last().contains("stage=FinalHistory outcome=failed"))
            assertFalse(records.joinToString().contains("private"))
        }

    private suspend fun verifyTimeoutChain(
        timeout: Throwable,
        stage: InterruptedUploadStage,
    ) {
        val middle = IllegalStateException("private nested detail", timeout)
        val original = AssertionError("private outer detail", middle)
        var calls = 0
        val failure =
            runCatching {
                interruptedUploadStep(stage, ::links, records::add) {
                    calls++
                    throw original
                }
            }.exceptionOrNull()
        assertSame(original, failure?.cause)
        assertSame(middle, original.cause)
        assertSame(timeout, middle.cause)
        assertEquals(1, calls)
        assertTimeoutRecord(failure, stage)
    }

    private fun assertTimeoutRecord(
        failure: Throwable?,
        stage: InterruptedUploadStage,
    ) {
        assertTrue(failure is AssertionError)
        assertTrue(records.last().contains("stage=${stage.name} outcome=timeout"))
        assertEquals(2, records.size)
        assertEquals(1, snapshots)
        assertFalse(records.joinToString().contains("private"))
        assertFalse(failure?.message.orEmpty().contains("private"))
    }
}
