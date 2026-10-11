package de.pyryco.mobile.e2e

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class RememberedEffortStageTest {
    @Test
    fun failureNamesStageAndRetainsCauseWhileFinallyRuns() =
        runTest {
            val cause = AssertionError("private settings")
            val logs = mutableListOf<String>()
            val failure =
                runCatching {
                    try {
                        rememberedEffortStep(RememberedEffortStage.PrimingPersistence, logs::add) { throw cause }
                    } finally {
                        rememberedEffortStep(RememberedEffortStage.CleanupSettings, logs::add) { Unit }
                        rememberedEffortStep(RememberedEffortStage.CleanupActivity, logs::add) { Unit }
                    }
                }.exceptionOrNull()
            assertEquals("remembered effort stage=PrimingPersistence failed", failure?.message)
            assertSame(cause, failure?.cause)
            assertEquals(6, logs.size)
            assertEquals("event=remembered_effort_step stage=PrimingPersistence outcome=failure", logs[1])
            assertEquals("event=remembered_effort_step stage=CleanupActivity outcome=complete", logs.last())
            assertFalse(logs.any { "private settings" in it })
        }

    @Test
    fun successfulStageReturnsOriginalResult() =
        runTest {
            val logs = mutableListOf<String>()
            val result = rememberedEffortStep(RememberedEffortStage.GraphRestart, logs::add) { 42 }
            assertEquals(42, result)
            assertEquals(
                listOf(
                    "event=remembered_effort_step stage=GraphRestart outcome=start",
                    "event=remembered_effort_step stage=GraphRestart outcome=complete",
                ),
                logs,
            )
        }
}
