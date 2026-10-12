package de.pyryco.mobile.e2e

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectFooterStageTest {
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
