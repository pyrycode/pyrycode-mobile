package de.pyryco.mobile.e2e

import androidx.compose.ui.test.ComposeTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test

class PermissionHeldStageTest {
    @Test
    fun recordsStaticStagesAndReturnsValueWithoutReadingFailureSnapshot() {
        val events = mutableListOf<String>()
        val result = permissionHeldStep(PermissionHeldStage.Connection, { error("unused snapshot") }, events::add) { 42 }
        assertEquals(42, result)
        assertEquals(listOf("event=permission_held_stage stage=Connection status=started", "event=permission_held_stage stage=Connection status=completed"), events)
    }

    @Test
    fun coroutineTimeoutNamesStageAndPreservesCause() = runTest {
        val timeout = runCatching { withTimeout(1) { delay(2) } }.exceptionOrNull() as TimeoutCancellationException
        assertTimeout(PermissionHeldStage.Connection, timeout)
    }

    @Test
    fun composeTimeoutNamesDisplayStageAndPreservesCause() {
        assertTimeout(PermissionHeldStage.RunningDisplay, ComposeTimeoutException("original"))
    }

    @Test
    fun nestedPeerTimeoutKeepsEntireCauseChain() = runTest {
        val timeout = runCatching { withTimeout(1) { delay(2) } }.exceptionOrNull() as TimeoutCancellationException
        assertTimeout(PermissionHeldStage.PriorPeer, AssertionError("peer diagnostic", timeout))
    }

    @Test
    fun ordinaryFailuresAndCancellationPropagateUnchanged() {
        for (failure in listOf(AssertionError("display regression"), IllegalStateException("refused"), CancellationException("cancelled"))) {
            val events = mutableListOf<String>()
            val thrown = runCatching {
                permissionHeldStep(PermissionHeldStage.Approval, { error("unused snapshot") }, events::add) { throw failure }
            }.exceptionOrNull()
            assertSame(failure, thrown)
            assertEquals("event=permission_held_stage stage=Approval status=failed", events.last())
        }
    }

    private fun assertTimeout(stage: PermissionHeldStage, cause: Throwable) {
        val events = mutableListOf<String>()
        var snapshots = 0
        val thrown = runCatching {
            permissionHeldStep(stage, { snapshots++; "targetPresent=true targetDaemon=Handshaking" }, events::add) { throw cause }
        }.exceptionOrNull()
        assertTrue(thrown is AssertionError)
        assertSame(cause, thrown?.cause)
        assertTrue(thrown?.message.orEmpty().contains("stage=$stage"))
        assertTrue(thrown?.message.orEmpty().contains("targetDaemon=Handshaking"))
        assertEquals(1, snapshots)
        assertEquals(listOf("event=permission_held_stage stage=$stage status=started", "event=permission_held_stage stage=$stage status=failed"), events)
    }
}
