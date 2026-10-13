package de.pyryco.mobile.e2e

import androidx.compose.ui.test.ComposeTimeoutException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AnswerHostPairingStageTest {
    @Test
    fun successRunsOnceAndReturnsValue() {
        val records = mutableListOf<String>()
        var runs = 0
        assertEquals(
            7,
            answerHostPairingStep(AnswerHostPairingStage.ReturnList, {
                "registered=true selected=false relay_ready=true daemon_ready=true repository=true"
            }, records::add) {
                runs++
                7
            },
        )
        assertEquals(1, runs)
        assertEquals(2, records.size)
        assertTrue(records.last().contains("outcome=passed"))
    }

    @Test
    fun navigationTimeoutKeepsOriginalCauseWithoutCopyingItsText() {
        val original = ComposeTimeoutException("fixture secret")
        var runs = 0
        val records = mutableListOf<String>()
        try {
            answerHostPairingStep(AnswerHostPairingStage.ReturnList, {
                "registered=true selected=false relay_ready=true daemon_ready=false repository=false"
            }, records::add) {
                runs++
                throw original
            }
            fail("expected timeout")
        } catch (error: AssertionError) {
            assertSame(original, error.cause)
            assertTrue(error.message.orEmpty().contains("return channel list"))
            assertFalse(error.message.orEmpty().contains("fixture secret"))
        }
        assertEquals(1, runs)
        assertTrue(records.last().contains("outcome=timeout"))
        assertTrue(records.none { it.contains("fixture secret") })
    }

    @Test
    fun callerCancellationPropagatesUnchanged() {
        val original = CancellationException("fixture secret")
        try {
            answerHostPairingStep(
                AnswerHostPairingStage.Confirm,
                { error("cancellation must not read diagnostics") },
                {},
            ) { throw original }
            fail("expected cancellation")
        } catch (error: CancellationException) {
            assertSame(original, error)
        }
    }
}
