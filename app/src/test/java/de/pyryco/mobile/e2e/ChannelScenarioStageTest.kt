package de.pyryco.mobile.e2e

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class ChannelScenarioStageTest {
    private val events = mutableListOf<String>()
    private val snapshot = "targetPresent=true targetRepository=false"

    private fun <T> step(
        stage: ChannelScenarioStage,
        block: () -> T,
    ): T = channelScenarioStep(stage, { snapshot }, events::add, { 123L }, block)

    @Test
    fun successfulOperationReturnsOnceAndReportsCompletion() {
        var calls = 0
        val result = step(ChannelScenarioStage.Creation) { ++calls }
        assertEquals(1, result)
        assertEquals(1, calls)
        assertEquals(
            listOf(
                "event=channel_scenario stage=Creation outcome=start at_ms=123 $snapshot",
                "event=channel_scenario stage=Creation outcome=complete at_ms=123 $snapshot",
            ),
            events,
        )
    }

    @Test
    fun failureIsRecordedBeforeCleanupAndTheOriginalFailureSurvives() {
        listOf(AssertionError("untrusted assertion"), IllegalStateException("untrusted exception")).forEach { original ->
            events.clear()
            val failed =
                runCatching {
                    try {
                        step(ChannelScenarioStage.EditedPromptRead) { throw original }
                    } finally {
                        step(ChannelScenarioStage.CleanupRestore) { events.add("fixture_restored") }
                    }
                }
            assertSame(original, failed.exceptionOrNull())
            assertEquals(
                listOf(
                    "event=channel_scenario stage=EditedPromptRead outcome=start at_ms=123 $snapshot",
                    "event=channel_scenario stage=EditedPromptRead outcome=failed at_ms=123 $snapshot",
                    "event=channel_scenario stage=CleanupRestore outcome=start at_ms=123 $snapshot",
                    "fixture_restored",
                    "event=channel_scenario stage=CleanupRestore outcome=complete at_ms=123 $snapshot",
                ),
                events,
            )
        }
    }

    @Test
    fun cancellationIsRecordedAndRethrownWithoutRetry() {
        val cancelled = CancellationException("private cancellation reason")
        var calls = 0
        val result =
            runCatching {
                step(ChannelScenarioStage.MatchingPromptStatus) {
                    calls++
                    throw cancelled
                }
            }
        assertSame(cancelled, result.exceptionOrNull())
        assertEquals(1, calls)
        assertEquals("event=channel_scenario stage=MatchingPromptStatus outcome=failed at_ms=123 $snapshot", events.last())
    }

    @Test
    fun repeatedStagesRunOnceEachWithoutRetry() {
        var calls = 0
        repeat(2) { step(ChannelScenarioStage.FixtureArchive) { calls++ } }
        assertEquals(2, calls)
        assertEquals(4, events.size)
        assertEquals(2, events.count { "outcome=start" in it })
        assertEquals(2, events.count { "outcome=complete" in it })
    }

    @Test
    fun failureMessagesNeverEnterEvidence() {
        val privateText = "secret-token prompt-text daemon-error"
        runCatching { step(ChannelScenarioStage.PromptClear) { throw IllegalStateException(privateText) } }
        assertEquals(2, events.size)
        assertFalse(events.any { privateText in it })
        assertFalse(events.any { "IllegalStateException" in it })
    }

    @Test
    fun snapshotFailureDoesNotChangeScenarioOutcome() {
        val original = AssertionError("scenario failure")
        var reads = 0
        val result =
            runCatching {
                channelScenarioStep(
                    ChannelScenarioStage.OriginalPromptRead,
                    snapshot = {
                        if (++reads == 2) error("graph closed with private data")
                        snapshot
                    },
                    emit = events::add,
                    nowMs = { 123L },
                ) { throw original }
            }
        assertSame(original, result.exceptionOrNull())
        assertEquals(
            "event=channel_scenario stage=OriginalPromptRead outcome=failed at_ms=123 snapshot=unavailable",
            events.last(),
        )
    }
}
