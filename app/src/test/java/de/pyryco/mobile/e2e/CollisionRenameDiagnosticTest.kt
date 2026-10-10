package de.pyryco.mobile.e2e

import androidx.compose.ui.test.ComposeTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CollisionRenameDiagnosticTest {
    @Test fun menuTimeoutRetainsTheOriginalExceptionAndInStepEvidence() {
        val timeout = ComposeTimeoutException("original menu deadline")
        val caught = runCatching { collisionRenameStep({ "owner_live=true menu_open=true" }) { throw timeout } }.exceptionOrNull()
        assertSame(timeout, caught)
        assertEquals("collision rename: owner_live=true menu_open=true", timeout.suppressed.single().message)
    }

    @Test fun diagnosticFailureCannotMaskTheMenuTimeout() {
        val timeout = ComposeTimeoutException("original menu deadline")
        val diagnosticFailure = IllegalStateException("fixture diagnostic failure")
        val caught = runCatching { collisionRenameStep({ throw diagnosticFailure }) { throw timeout } }.exceptionOrNull()
        assertSame(timeout, caught)
        assertSame(diagnosticFailure, timeout.suppressed.single())
    }

    @Test fun successDoesNotReadFailureDiagnostics() {
        assertEquals(7, collisionRenameStep({ error("unexpected diagnostic read") }) { 7 })
    }
}
