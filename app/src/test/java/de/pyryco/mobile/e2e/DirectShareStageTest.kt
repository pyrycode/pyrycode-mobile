package de.pyryco.mobile.e2e

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectShareStageTest {
    @Test
    fun successfulAsynchronousStagesReturnTheirResultOnce() =
        runTest {
            val events = mutableListOf<String>()
            DirectShareStage.entries.forEach { stage ->
                val value =
                    directShareStep(stage, { error("success needs no snapshot") }, events::add) {
                        delay(1)
                        42
                    }
                assertEquals(42, value)
                assertEquals(
                    listOf(
                        "event=direct_share_stage stage=$stage status=started",
                        "event=direct_share_stage stage=$stage status=completed",
                    ),
                    events.takeLast(2),
                )
            }
            assertEquals(DirectShareStage.entries.size * 2, events.size)
        }

    @Test
    fun outerCreationDeadlineRecordsTheInnerStageBeforeCleanup() =
        runTest {
            val events = mutableListOf<String>()
            var cleaned = false
            var nextStage = false
            val failure =
                runCatching {
                    try {
                        withTimeout(30_000) {
                            directShareStep(
                                DirectShareStage.CreateX,
                                { "cleaned=$cleaned repository=true generation=1" },
                                events::add,
                            ) { delay(30_001) }
                            nextStage = true
                        }
                    } finally {
                        cleaned = true
                    }
                }.exceptionOrNull()
            assertTrue(generateSequence(failure) { it.cause }.any { it is TimeoutCancellationException })
            assertTrue(cleaned)
            assertFalse(nextStage)
            assertTrue(events.last().contains("stage=CreateX status=failed"))
            assertTrue(events.last().contains("cleaned=false"))
            assertTrue(diagnostics(failure).any { "stage=CreateX" in it })
        }

    @Test
    fun nestedPeerTimeoutKeepsItsCauseAndFailedStage() =
        runTest {
            val events = mutableListOf<String>()
            var original: Throwable? = null
            val failure =
                runCatching {
                    directShareStep(DirectShareStage.Retrieval, { "peer=closed frames=2" }, events::add) {
                        withTimeout(1_000) {
                            try {
                                withTimeout(10) { delay(11) }
                            } catch (cause: TimeoutCancellationException) {
                                original = AssertionError("peer wait", cause)
                                throw checkNotNull(original)
                            }
                        }
                    }
                }.exceptionOrNull()
            assertTrue(
                "coroutine recovery retains the original peer failure",
                generateSequence(failure) { it.cause }.any { it === original },
            )
            assertTrue(generateSequence(failure) { it.cause }.any { it is TimeoutCancellationException })
            assertTrue(diagnostics(failure).any { "stage=Retrieval" in it })
            assertEquals(2, events.size)
            assertTrue(events.last().contains("status=failed; peer=closed frames=2"))
        }

    @Test
    fun diagnosticFailureDoesNotMaskTheAsynchronousOperation() =
        runTest {
            val events = mutableListOf<String>()
            val original = IllegalStateException("private operation detail")
            val failure =
                runCatching {
                    directShareStep(DirectShareStage.RenameY, { error("private diagnostic detail") }, events::add) {
                        delay(1)
                        throw original
                    }
                }.exceptionOrNull()
            assertSame(original, failure)
            assertTrue(events.last().endsWith("status=failed; diagnostic=unavailable"))
            assertFalse(events.joinToString().contains("private"))
        }

    @Test
    fun recorderFailureDoesNotMaskTheOperationOrItsSnapshot() =
        runTest {
            val original = AssertionError("private operation detail")
            var called = false
            val failure =
                runCatching {
                    directShareStep(
                        DirectShareStage.Capture,
                        { "repository=false" },
                        { error("recorder unavailable") },
                    ) {
                        delay(1)
                        called = true
                        throw original
                    }
                }.exceptionOrNull()
            assertTrue(called)
            assertSame(original, failure)
            assertTrue(
                checkNotNull(failure)
                    .suppressed
                    .single()
                    .message
                    .orEmpty()
                    .contains("repository=false"),
            )
        }

    @Test
    fun diagnosticSnapshotIsBounded() =
        runTest {
            val events = mutableListOf<String>()
            val original = AssertionError()
            runCatching { directShareStep(DirectShareStage.HistoryY, { "x".repeat(2_000) }, events::add) { throw original } }
            assertTrue(events.last().length < 650)
            assertTrue(
                original.suppressed
                    .single()
                    .message
                    .orEmpty()
                    .length < 650,
            )
        }

    private fun diagnostics(failure: Throwable?): List<String> =
        generateSequence(failure) { it.cause }
            .flatMap {
                sequenceOf(it.message.orEmpty()) + it.suppressed.asSequence().map { diagnostic -> diagnostic.message.orEmpty() }
            }.toList()
}
