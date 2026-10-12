package de.pyryco.mobile.e2e

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectFooterStageTest {
    @Test
    fun createAndRenameShareOneDeadlineWithTheFailingOperationLabel() =
        runTest {
            val records = mutableListOf<String>()
            var creates = 0
            var renames = 0
            val caught =
                runCatching {
                    reconnectFooterFixture(30_000, records::add, { "repository=true" }, create = {
                        creates++
                        delay(20_000)
                        "created"
                    }, rename = {
                        renames++
                        delay(20_000)
                        "renamed"
                    })
                }.exceptionOrNull()
            assertEquals(30_000, testScheduler.currentTime)
            assertEquals(1, creates)
            assertEquals(1, renames)
            assertEquals("reconnect footer stage=RenameChat failed; repository=true", caught?.message)
            assertTrue(generateSequence(caught) { it.cause }.last() is TimeoutCancellationException)
            assertTrue(records.last().contains("stage=RenameChat outcome=failure"))
        }

    @Test
    fun failedSettingsRestorationPreservesTheEarliestOperationAndRecordsCleanupSeparately() {
        assertCleanupFailure(ReconnectFooterStage.CleanupSettings)
    }

    @Test
    fun failedRememberedEffortClearingPreservesTheEarliestOperationAndRecordsCleanupSeparately() {
        assertCleanupFailure(ReconnectFooterStage.CleanupRememberedEffort)
    }

    private fun assertCleanupFailure(cleanupStage: ReconnectFooterStage) {
        val records = mutableListOf<String>()
        val original = IllegalStateException("private operation payload")
        val cleanupFailure = IllegalStateException("private cleanup payload")
        var operations = 0
        var cleanups = 0
        val caught =
            runCatching {
                reconnectFooterScenario(cleanup = {
                    reconnectFooterStep(cleanupStage, records::add, { "repository=false" }) {
                        cleanups++
                        throw cleanupFailure
                    }
                }) {
                    reconnectFooterStep(ReconnectFooterStage.ReconnectedSettings, records::add, { "repository=false" }) {
                        operations++
                        throw original
                    }
                }
            }.exceptionOrNull()
        assertEquals("reconnect footer stage=ReconnectedSettings failed; repository=false", caught?.message)
        assertSame(original, caught?.cause)
        val suppressed = requireNotNull(caught).suppressed.single()
        assertEquals("reconnect footer stage=${cleanupStage.name} failed; repository=false", suppressed.message)
        assertSame(cleanupFailure, suppressed.cause)
        assertEquals(1, operations)
        assertEquals(1, cleanups)
        assertEquals(
            listOf(
                "event=reconnect_footer stage=ReconnectedSettings outcome=start repository=false",
                "event=reconnect_footer stage=ReconnectedSettings outcome=failure repository=false",
                "event=reconnect_footer stage=${cleanupStage.name} outcome=start repository=false",
                "event=reconnect_footer stage=${cleanupStage.name} outcome=failure repository=false",
            ),
            records,
        )
    }

    @Test
    fun cleanupFailureAfterSuccessStillFailsTheScenario() {
        val failure = IllegalStateException("cleanup")
        val caught = runCatching { reconnectFooterScenario(cleanup = { throw failure }) { "success" } }.exceptionOrNull()
        assertSame(failure, caught)
    }

    @Test
    fun successfulOperationRunsOnceAndReturnsItsValueWithOrderedEvidence() {
        val records = mutableListOf<String>()
        val value = Any()
        var calls = 0
        val result =
            reconnectFooterStep(ReconnectFooterStage.CreateChat, records::add, { "repository=true" }) {
                calls++
                value
            }
        assertSame(value, result)
        assertEquals(1, calls)
        assertEquals(
            listOf(
                "event=reconnect_footer stage=CreateChat outcome=start repository=true",
                "event=reconnect_footer stage=CreateChat outcome=complete repository=true",
            ),
            records,
        )
    }

    @Test
    fun connectionFailureNamesTheOperationKeepsTheCauseAndDoesNotRetry() {
        val records = mutableListOf<String>()
        val failure = IllegalStateException("private payload must not be logged")
        var calls = 0
        val caught =
            runCatching {
                reconnectFooterStep(ReconnectFooterStage.RenameChat, records::add, { "repository=false" }) {
                    calls++
                    throw failure
                }
            }.exceptionOrNull()
        assertTrue(caught is AssertionError)
        assertEquals("reconnect footer stage=RenameChat failed; repository=false", caught?.message)
        assertSame(failure, caught?.cause)
        assertEquals(1, calls)
        assertEquals(
            listOf(
                "event=reconnect_footer stage=RenameChat outcome=start repository=false",
                "event=reconnect_footer stage=RenameChat outcome=failure repository=false",
            ),
            records,
        )
    }

    @Test
    fun assertionFailureKeepsTheConditionAndLabelsItsOperation() {
        val failure = AssertionError("original condition")
        val caught =
            runCatching {
                reconnectFooterStep(ReconnectFooterStage.HeldContext, {}, { "relay=Idle" }) { throw failure }
            }.exceptionOrNull()
        assertEquals("reconnect footer stage=HeldContext failed; relay=Idle", caught?.message)
        assertSame(failure, caught?.cause)
    }
}
