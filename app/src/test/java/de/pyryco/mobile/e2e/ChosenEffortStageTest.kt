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

class ChosenEffortStageTest {
    @Test
    fun exceptionIdentifiesOperationAndRetainsCauseWhileCleanupRuns() =
        runTest {
            val cause = IllegalStateException("private settings and identity")
            val logs = mutableListOf<String>()
            val failure =
                runCatching {
                    try {
                        chosenEffortStep(ChosenEffortStage.InitialSettings, logs::add) { throw cause }
                    } finally {
                        chosenEffortStep(ChosenEffortStage.Cleanup, logs::add) { Unit }
                    }
                }.exceptionOrNull()
            assertEquals("chosen effort stage=InitialSettings failed", failure?.message)
            assertSame(cause, failure?.cause)
            assertEquals(
                listOf(
                    "event=chosen_effort_step stage=InitialSettings outcome=start",
                    "event=chosen_effort_step stage=InitialSettings outcome=failure",
                    "event=chosen_effort_step stage=Cleanup outcome=start",
                    "event=chosen_effort_step stage=Cleanup outcome=complete",
                ),
                logs,
            )
            assertFalse(logs.any { "private" in it })
        }

    @Test
    fun deadlineInsideFixtureKeepsTimeoutAsCause() =
        runTest {
            val logs = mutableListOf<String>()
            val failure =
                runCatching {
                    withTimeout(30) {
                        chosenEffortStep(ChosenEffortStage.RenameChat, logs::add) { delay(31) }
                    }
                }.exceptionOrNull()
            assertEquals("chosen effort stage=RenameChat failed", failure?.message)
            // Coroutine stack recovery may copy the wrapper and keep the original below that copy.
            assertTrue(generateSequence(failure) { it.cause }.any { it is TimeoutCancellationException })
            assertEquals("event=chosen_effort_step stage=RenameChat outcome=failure", logs.last())
        }

    @Test
    fun assertionIdentifiesAppliedReadingWithoutLoggingValue() {
        val cause = AssertionError("private applied effort")
        val logs = mutableListOf<String>()
        val failure = runCatching { chosenEffortStep(ChosenEffortStage.AppliedReading, logs::add) { throw cause } }.exceptionOrNull()
        assertEquals("chosen effort stage=AppliedReading failed", failure?.message)
        assertSame(cause, failure?.cause)
        assertEquals("event=chosen_effort_step stage=AppliedReading outcome=failure", logs.last())
        assertFalse(logs.any { "private" in it })
    }

    @Test
    fun successfulOperationReturnsOriginalResult() {
        val logs = mutableListOf<String>()
        assertEquals(42, chosenEffortStep(ChosenEffortStage.CreateChat, logs::add) { 42 })
        assertEquals(
            listOf(
                "event=chosen_effort_step stage=CreateChat outcome=start",
                "event=chosen_effort_step stage=CreateChat outcome=complete",
            ),
            logs,
        )
    }
}
