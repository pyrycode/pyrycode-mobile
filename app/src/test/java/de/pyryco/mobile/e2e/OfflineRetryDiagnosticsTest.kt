package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.RelayLinkStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineRetryDiagnosticsTest {
    @Test
    fun awaitedFailureKeepsOriginalExceptionAndAddsStaticStageEvidence() {
        val secret = "sentinel-credential-and-message"
        val failure = IllegalStateException(secret)
        val events = mutableListOf<String>()
        val diagnostics =
            OfflineRetryDiagnostics(
                { OfflineRetrySnapshot(relay = RelayLinkStatus.UpdateRequired(secret), attempt = 6, recoveryRemainingMs = 9_000) },
                events::add,
            )
        val caught = runCatching { offlineRetryStep(OfflineRetryStage.RestartDaemon, diagnostics) { throw failure } }.exceptionOrNull()
        assertSame(failure, caught)
        val evidence =
            failure.suppressed
                .single()
                .message
                .orEmpty()
        assertTrue(evidence.contains("stage=RestartDaemon"))
        assertTrue(evidence.contains("relay=UpdateRequired"))
        assertTrue(evidence.contains("attempt=6"))
        assertTrue(evidence.contains("recovery_remaining_ms=9000"))
        assertFalse(evidence.contains(secret))
        assertTrue(events.none { it.contains(secret) })
        assertEquals(2, events.size)
    }

    @Test
    fun diagnosticsCannotReplaceOriginalFailureEvenWhenSnapshotAndSinkThrow() {
        val failure = AssertionError("original failure")
        val diagnostics = OfflineRetryDiagnostics({ error("private snapshot") }, { error("private sink") })
        val caught = runCatching { offlineRetryStep(OfflineRetryStage.RenderReply, diagnostics) { throw failure } }.exceptionOrNull()
        assertSame(failure, caught)
        assertEquals("event=offline_retry_failure stage=RenderReply state=unavailable", failure.suppressed.single().message)
    }

    @Test
    fun successfulStepReturnsItsValueEvenWhenEvidenceSinkThrows() {
        val diagnostics = OfflineRetryDiagnostics({ OfflineRetrySnapshot() }, { error("private sink") })
        assertEquals(7, offlineRetryStep(OfflineRetryStage.HostSetup, diagnostics) { 7 })
    }

    @Test
    fun cleanupFailureIsRetainedOnTheOriginalScenarioFailure() {
        val original = AssertionError("original scenario failure")
        val cleanup = IllegalStateException("cleanup failure")
        val diagnostics = OfflineRetryDiagnostics({ OfflineRetrySnapshot() })
        restoreOfflineRetryDaemon(diagnostics, original) { throw cleanup }
        assertSame(cleanup, original.suppressed.single())
        assertTrue(
            cleanup.suppressed
                .single()
                .message
                .orEmpty()
                .contains("stage=RestoreDaemon"),
        )
    }

    @Test
    fun cleanupFailureCannotLeaveASuccessfulScenarioGreen() {
        val cleanup = IllegalStateException("cleanup failure")
        val diagnostics = OfflineRetryDiagnostics({ OfflineRetrySnapshot() })
        val caught = runCatching { restoreOfflineRetryDaemon(diagnostics, null) { throw cleanup } }.exceptionOrNull()
        assertSame(cleanup, caught)
    }
}
