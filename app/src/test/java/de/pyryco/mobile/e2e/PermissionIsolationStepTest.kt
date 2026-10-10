package de.pyryco.mobile.e2e

import androidx.compose.ui.test.ComposeTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PermissionIsolationStepTest {
    @Test
    fun `every wait retains its original deadline and names the failed operation`() =
        runTest {
            for (stage in PermissionIsolationStage.entries) {
                val started = currentTime
                var reads = 0
                val failure =
                    runCatching {
                        permissionIsolationStep(stage, {
                            reads += 1
                            LINK
                        }) {
                            withTimeout(30_000) { awaitCancellation() }
                        }
                    }.exceptionOrNull()
                assertTrue(failure is AssertionError)
                assertEquals("permission-isolation step '${stage.label}' timed out; $LINK", failure?.message)
                assertTrue(failure?.cause is TimeoutCancellationException)
                assertEquals(30_000, currentTime - started)
                assertEquals(1, reads)
            }
        }

    @Test
    fun `phone rendering timeout retains the cause without copying its content`() {
        val timeout = ComposeTimeoutException("untrusted prompt fixture")
        val failure =
            runCatching {
                permissionIsolationStep(PermissionIsolationStage.DrawA, { LINK }) { throw timeout }
            }.exceptionOrNull()
        assertEquals("permission-isolation step 'draw A permission on phone' timed out; $LINK", failure?.message)
        assertSame(timeout, failure?.cause)
    }

    @Test
    fun `successful steps and ordinary failures do not read diagnostics`() {
        val value = Any()
        assertSame(value, permissionIsolationStep(PermissionIsolationStage.CreateA, { error("must stay lazy") }) { value })
        for (failure in listOf(IllegalStateException("refused"), CancellationException("cancelled"))) {
            val caught =
                runCatching {
                    permissionIsolationStep(PermissionIsolationStage.CreateA, { error("must stay lazy") }) { throw failure }
                }.exceptionOrNull()
            assertSame(failure, caught)
        }
    }

    private companion object {
        const val LINK = "session open (link 1, replaced 0×)"
    }
}
