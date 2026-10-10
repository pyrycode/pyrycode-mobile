package de.pyryco.mobile.e2e

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

internal fun sessionErrorDiagnosticFailure(
    arm: String,
    phase: String,
    fixtureReady: Boolean,
    failure: Throwable,
    snapshot: () -> String,
): AssertionError {
    val observed = runCatching(snapshot).getOrDefault("snapshot=unavailable")
    return AssertionError("session-error arm=$arm phase=$phase fixture_ready=$fixtureReady $observed", failure)
}

internal fun <T> sessionErrorCleanup(
    cleanup: () -> Unit,
    block: () -> T,
): T {
    var primary: Throwable? = null
    try {
        return block()
    } catch (failure: Throwable) {
        primary = failure
        throw failure
    } finally {
        try {
            cleanup()
        } catch (failure: Throwable) {
            val original = primary
            if (original == null) throw failure
            if (original !== failure) original.addSuppressed(failure)
        }
    }
}

@RunWith(AndroidJUnit4::class)
class SessionErrorDiagnosticsTest {
    @Test
    fun bothArmsRetainPhaseReadinessAndOriginalTimeoutWithoutFormattingIt() {
        val timeout = ComposeTimeoutException("private-pairing-token authorization private-frame")
        for (arm in listOf("retained", "dropped")) {
            val failure = sessionErrorDiagnosticFailure(arm, "pair_return", true, timeout) { "saved=true connection=false" }
            assertEquals("session-error arm=$arm phase=pair_return fixture_ready=true saved=true connection=false", failure.message)
            assertSame(timeout, failure.cause)
            assertFalse(failure.message.orEmpty().contains("private"))
        }
    }

    @Test
    fun unavailableSnapshotCannotReplaceOriginalTimeoutOrPublishItsOwnDetails() {
        val timeout = ComposeTimeoutException("original timeout")
        val failure =
            sessionErrorDiagnosticFailure("dropped", "pair_return", true, timeout) {
                throw IllegalStateException("private-pairing-token")
            }
        assertSame(timeout, failure.cause)
        assertEquals("session-error arm=dropped phase=pair_return fixture_ready=true snapshot=unavailable", failure.message)
    }

    @Test
    fun cleanupRetainsPrimaryFailureAndStillRuns() {
        val timeout = ComposeTimeoutException("original timeout")
        val teardown = IllegalStateException("teardown")
        var cleaned = false
        val failure =
            assertThrows(ComposeTimeoutException::class.java) {
                sessionErrorCleanup(cleanup = {
                    cleaned = true
                    throw teardown
                }) { throw timeout }
            }
        assertTrue(cleaned)
        assertSame(timeout, failure)
        assertEquals(listOf(teardown), failure.suppressed.toList())
    }

    @Test
    fun cleanupFailureWithoutPrimaryFailureRemainsAFailure() {
        val teardown = IllegalStateException("teardown")
        val failure =
            assertThrows(IllegalStateException::class.java) {
                sessionErrorCleanup(cleanup = { throw teardown }) { Unit }
            }
        assertSame(teardown, failure)
    }
}
