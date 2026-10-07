package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TransientErrorNoticeStateTest {
    private val enabled = RelayLog.enabled

    @Before fun silenceAndroidLog() {
        RelayLog.enabled = false
    }

    @After fun restoreLog() {
        RelayLog.enabled = enabled
    }

    @Test fun queuedErrors_getTheirOwnFullTimeout_inSubmissionOrder() =
        runTest {
            val state = TransientErrorNoticeState { 4_000 }
            val first = launch { state.show("first") }
            val second = launch { state.show("second") }
            val third = launch { state.show("third") }
            runCurrent()
            assertEquals("first", state.currentMessage)
            advanceTimeBy(3_999)
            assertEquals("first", state.currentMessage)
            advanceTimeBy(1)
            runCurrent()
            assertEquals("second", state.currentMessage)
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals("third", state.currentMessage)
            advanceTimeBy(4_000)
            runCurrent()
            assertNull(state.currentMessage)
            listOf(first, second, third).forEach { assertEquals(true, it.isCompleted) }
        }

    @Test fun cancellingAQueuedError_doesNotRemoveTheActiveOne_orLeaveItQueued() =
        runTest {
            val state = TransientErrorNoticeState { 4_000 }
            launch { state.show("active") }
            val queued = launch { state.show("cancelled") }
            runCurrent()
            queued.cancel()
            runCurrent()
            assertEquals("active", state.currentMessage)
            advanceTimeBy(4_000)
            runCurrent()
            assertNull(state.currentMessage)
        }

    @Test fun cancellingTheActiveError_clearsIt_andReleasesTheNextCaller() =
        runTest {
            val state = TransientErrorNoticeState { 4_000 }
            val active = launch { state.show("active") }
            val next = launch { state.show("next") }
            runCurrent()
            active.cancel()
            runCurrent()
            assertEquals("next", state.currentMessage)
            next.cancel()
            runCurrent()
            assertNull(state.currentMessage)
        }

    @Test fun timeoutIsCalculatedForEachNotice_whenItStarts() =
        runTest {
            var timeout = 12_000L
            val state = TransientErrorNoticeState { timeout }
            launch { state.show("extended") }
            launch { state.show("short") }
            runCurrent()
            timeout = 4_000L
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals("extended", state.currentMessage)
            advanceTimeBy(8_000)
            runCurrent()
            assertEquals("short", state.currentMessage)
            advanceTimeBy(4_000)
            runCurrent()
            assertNull(state.currentMessage)
        }

    @Test fun confirmationQueue_preservesEachArrivalPermutation_includingEqualOccurrences() =
        runTest {
            val orders =
                listOf(
                    listOf("dismissal", "saved", "saved"),
                    listOf("saved", "dismissal", "saved"),
                    listOf("saved", "saved", "dismissal"),
                    emptyList(),
                    listOf("saved"),
                )
            for (order in orders) {
                val state = TransientErrorNoticeState("transient_confirmation_notice") { 4_000 }
                assertNull(state.currentMessage)
                order.forEach { state.enqueue(this, it) }
                order.forEachIndexed { index, text ->
                    assertEquals(text, state.currentMessage)
                    assertEquals(index + 1L, state.currentOccurrence)
                    advanceTimeBy(3_999)
                    runCurrent()
                    assertEquals(text, state.currentMessage)
                    assertEquals(index + 1L, state.currentOccurrence)
                    advanceTimeBy(1)
                    runCurrent()
                }
                assertNull(state.currentMessage)
            }
        }

    @Test fun confirmationOwnerCancellation_betweenAnyAdmissions_clearsActiveAndAllQueued() =
        runTest {
            for (count in 1..3) {
                val owner = CoroutineScope(coroutineContext + Job())
                val state = TransientErrorNoticeState("transient_confirmation_notice") { 4_000 }
                val jobs = (1..count).map { state.enqueue(owner, "saved") }
                assertEquals("saved", state.currentMessage)
                owner.cancel()
                runCurrent()
                assertNull(state.currentMessage)
                assertEquals(true, jobs.all { it.isCancelled })
                advanceTimeBy(12_000)
                runCurrent()
                assertNull(state.currentMessage)
            }
        }

    @Test fun confirmationQueue_doesNotHoldOrResetTheIndependentErrorQueue() =
        runTest {
            val confirmations = TransientErrorNoticeState("transient_confirmation_notice") { 4_000 }
            val errors = TransientErrorNoticeState { 4_000 }
            confirmations.enqueue(this, "saved")
            confirmations.enqueue(this, "saved")
            advanceTimeBy(1_000)
            errors.enqueue(this, "error")
            assertEquals("error", errors.currentMessage)
            advanceTimeBy(3_000)
            runCurrent()
            assertEquals(2L, confirmations.currentOccurrence)
            assertEquals("error", errors.currentMessage)
            advanceTimeBy(1_000)
            runCurrent()
            assertNull(errors.currentMessage)
            assertEquals("saved", confirmations.currentMessage)
            advanceTimeBy(3_000)
            runCurrent()
            assertNull(confirmations.currentMessage)
        }
}
