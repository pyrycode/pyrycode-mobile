package de.pyryco.mobile.e2e

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class McpScenarioDiagnosticsTest {
    @Test
    fun everyOperationFailureNamesItsStageAndPreservesTheCause() {
        for (stage in McpScenarioStage.entries) {
            val original = IllegalStateException("original failure")
            val events = mutableListOf<String>()
            val failure =
                runCatching {
                    mcpScenarioStep(stage, { "targetRelay=Connected targetDaemon=Down selectedTarget=false" }, events::add) {
                        throw original
                    }
                }.exceptionOrNull()

            assertTrue(failure is AssertionError)
            assertSame(original, failure?.cause)
            assertTrue(failure?.message.orEmpty().contains(stage.name))
            assertTrue(failure?.message.orEmpty().contains("targetDaemon=Down"))
            assertEquals(
                listOf(
                    "event=mcp_scenario stage=${stage.name} status=started",
                    "event=mcp_scenario stage=${stage.name} status=failed targetRelay=Connected targetDaemon=Down selectedTarget=false",
                ),
                events,
            )
        }
    }

    @Test
    fun successReturnsTheValueWithoutSamplingFailureEvidence() {
        val events = mutableListOf<String>()
        val result = mcpScenarioStep(McpScenarioStage.McpReport, { error("must not sample") }, events::add) { 42 }

        assertEquals(42, result)
        assertEquals(
            listOf(
                "event=mcp_scenario stage=McpReport status=started",
                "event=mcp_scenario stage=McpReport status=completed",
            ),
            events,
        )
    }

    @Test
    fun unavailableSnapshotCannotReplaceTheScenarioFailure() {
        val original = AssertionError("original assertion")
        val events = mutableListOf<String>()
        val failure =
            runCatching {
                mcpScenarioStep(McpScenarioStage.BuiltInRow, { error("unavailable graph") }, events::add) { throw original }
            }.exceptionOrNull()

        assertSame(original, failure?.cause)
        assertEquals("MCP scenario failed at BuiltInRow; connection=unknown", failure?.message)
        assertEquals("event=mcp_scenario stage=BuiltInRow status=failed connection=unknown", events.last())
    }

    @Test
    fun repeatedStageDoesNotRetainPreviousFailureOrSnapshot() {
        var snapshots = 0
        val events = mutableListOf<String>()
        val snapshot = {
            snapshots++
            "connection=unknown"
        }

        mcpScenarioStep(McpScenarioStage.Connection, snapshot, events::add) { Unit }
        runCatching { mcpScenarioStep(McpScenarioStage.Connection, snapshot, events::add) { error("offline") } }
        assertEquals(7, mcpScenarioStep(McpScenarioStage.Connection, snapshot, events::add) { 7 })

        assertEquals(1, snapshots)
        assertEquals(3, events.count { it.endsWith("status=started") })
        assertEquals(2, events.count { it.endsWith("status=completed") })
        assertEquals(1, events.count { "status=failed" in it })
    }

    @Test
    fun coroutineTimeoutIsPreservedWithoutAnotherDeadlineOrRetry() =
        runTest {
            val original = requireNotNull(runCatching { withTimeout(137) { delay(138) } }.exceptionOrNull())
            val failure =
                runCatching {
                    mcpScenarioStep(McpScenarioStage.Connection, { "connection=unknown" }, {}) { throw original }
                }.exceptionOrNull()

            assertSame(original, failure?.cause)
            assertEquals(137L, testScheduler.currentTime)
        }
}
