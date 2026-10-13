package de.pyryco.mobile.e2e

import androidx.compose.ui.test.ComposeTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun successRunsOnceReturnsValueAndDoesNotReadFailureSnapshot() {
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
    fun repeatedStagesRunEachOperationOnce() {
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
    fun composeTimeoutRetainsOriginalCause() {
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
    fun assertionRetainsOriginalCauseWithoutPublishingItsContent() {
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
    fun operationFailureRunsOnceAndRetainsCause() {
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
    fun callerCancellationPropagatesUnchanged() {
        val original = CancellationException("caller cancelled")
        val failure =
            runCatching {
                interruptedUploadStep(InterruptedUploadStage.RetryReadiness, ::links, records::add) { throw original }
            }.exceptionOrNull()
        assertSame(original, failure)
        assertEquals(0, snapshots)
    }
}
