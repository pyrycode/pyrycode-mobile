package de.pyryco.mobile.ui.conversations.thread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The history walk's transitions (#777), proven without a ViewModel and without a device — the reason
 * the demand is a value rather than a method body on [ThreadViewModel].
 */
class ThreadHistoryDemandTest {
    @Test
    fun freshDemand_asksFromTheNewest() {
        val demand = ThreadHistoryDemand()
        assertTrue(demand.canAsk)
        assertEquals("", demand.cursor)
        assertEquals(0, demand.pagesLoaded)
        assertNull(demand.stoppedBy)
    }

    @Test
    fun asking_claimsTheSlotAndKeepsTheCursor() {
        val asking = ThreadHistoryDemand(cursor = "c1").asking()
        assertTrue(asking.inFlight)
        assertEquals("c1", asking.cursor)
        // One outstanding request per conversation: an ask arriving now is dropped, not queued.
        assertFalse(asking.canAsk)
    }

    @Test
    fun settled_advancesTheCursorAndKeepsWalking() {
        val next = ThreadHistoryDemand().asking().settled(pageCursor = "c1", atStart = false)
        assertEquals("c1", next.cursor)
        assertEquals(1, next.pagesLoaded)
        assertFalse(next.inFlight)
        assertNull(next.stoppedBy)
        assertTrue(next.canAsk)
    }

    @Test
    fun atStart_endsTheWalk() {
        // The daemon's only termination signal. The cursor is empty whenever atStart is true, so this
        // case must not be misread as NotAdvancing — atStart is checked first.
        val next = ThreadHistoryDemand(cursor = "c1").asking().settled(pageCursor = "", atStart = true)
        assertEquals(HistoryWalkStop.AtStart, next.stoppedBy)
        assertFalse(next.canAsk)
    }

    @Test
    fun repeatedCursor_endsTheWalk() {
        // The honest-bug guard: re-asking with the cursor we just asked with would loop forever.
        val next = ThreadHistoryDemand(cursor = "c1").asking().settled(pageCursor = "c1", atStart = false)
        assertEquals(HistoryWalkStop.NotAdvancing, next.stoppedBy)
        assertFalse(next.canAsk)
    }

    @Test
    fun emptyCursorWithoutAtStart_endsTheWalk() {
        // A contract violation (the two are never both meaningful) with no walkable continuation.
        val next = ThreadHistoryDemand(cursor = "c1").asking().settled(pageCursor = "", atStart = false)
        assertEquals(HistoryWalkStop.NotAdvancing, next.stoppedBy)
    }

    @Test
    fun emptyPageWithAnAdvancingCursor_keepsWalking() {
        // An empty entries list is a normal page, not a termination signal — the walk stops on atStart
        // and on the cap, never on a short or empty page. The demand never sees entries at all, so this
        // is the whole of that rule: an advancing cursor keeps it walking.
        val next = ThreadHistoryDemand(cursor = "c1").asking().settled(pageCursor = "c2", atStart = false)
        assertNull(next.stoppedBy)
        assertTrue(next.canAsk)
    }

    @Test
    fun pageCap_endsTheWalkAtExactlyTheLimit() {
        // The load-bearing client-side bound: a daemon alternating cursors defeats the NotAdvancing
        // guard, and only the cap stops it.
        var demand = ThreadHistoryDemand()
        repeat(MAX_HISTORY_PAGES) { page ->
            assertTrue("refused an ask at page $page", demand.canAsk)
            demand = demand.asking().settled(pageCursor = "c$page", atStart = false)
        }
        assertEquals(MAX_HISTORY_PAGES, demand.pagesLoaded)
        assertEquals(HistoryWalkStop.PageCap, demand.stoppedBy)
        assertFalse(demand.canAsk)
    }

    @Test
    fun pageCap_isNotReachedOnePageEarly() {
        var demand = ThreadHistoryDemand()
        repeat(MAX_HISTORY_PAGES - 1) { page ->
            demand = demand.asking().settled(pageCursor = "c$page", atStart = false)
        }
        assertNull(demand.stoppedBy)
        assertTrue(demand.canAsk)
    }

    @Test
    fun failed_keepsTheWalksPositionAndLetsAFreshGestureAskAgain() {
        val walked = ThreadHistoryDemand().asking().settled(pageCursor = "c1", atStart = false)
        val failed = walked.asking().failed(retryable = true)
        // The walk's position is kept — the cursor and the page count are exactly the walked ones.
        assertEquals("c1", failed.cursor)
        assertEquals(1, failed.pagesLoaded)
        // The in-flight state always clears, so the loading affordance can never be left stuck.
        assertFalse(failed.inFlight)
        assertEquals(HistoryWalkStop.RetryableFailure, failed.stoppedBy)
        // #1352: a failure no longer locks the walk; a fresh user gesture may ask again.
        assertTrue(failed.canAsk)
    }

    @Test
    fun onlyTheTerminalStops_refuseFurtherAsks() {
        listOf(HistoryWalkStop.AtStart, HistoryWalkStop.NotAdvancing, HistoryWalkStop.PageCap).forEach { stop ->
            assertFalse("$stop still allowed an ask", ThreadHistoryDemand(stoppedBy = stop).canAsk)
        }
        listOf(HistoryWalkStop.RetryableFailure, HistoryWalkStop.PermanentFailure).forEach { stop ->
            assertTrue("$stop refused a fresh ask", ThreadHistoryDemand(stoppedBy = stop).canAsk)
            assertFalse("$stop allowed an ask while one is in flight", ThreadHistoryDemand(stoppedBy = stop, inFlight = true).canAsk)
        }
    }

    // --- #778: the retryable split and the retry claim ---------------------------------------------

    @Test
    fun failed_splitsOnRetryableAndOnlyTheRetryableOneOffersARetry() {
        val walked = ThreadHistoryDemand().asking().settled(pageCursor = "c1", atStart = false)
        val retryable = walked.asking().failed(retryable = true)
        val permanent = walked.asking().failed(retryable = false)
        assertEquals(HistoryWalkStop.RetryableFailure, retryable.stoppedBy)
        assertEquals(HistoryWalkStop.PermanentFailure, permanent.stoppedBy)
        assertTrue(retryable.canRetry)
        assertFalse(permanent.canRetry)
        // Both keep the walk's position, so the next ask resumes from the page that failed.
        assertEquals("c1", permanent.cursor)
        assertEquals(1, permanent.pagesLoaded)
    }

    @Test
    fun canRetry_isFalseForEveryOtherStopAndWhileInFlight() {
        HistoryWalkStop.entries
            .filter { it != HistoryWalkStop.RetryableFailure }
            .forEach { stop ->
                assertFalse("$stop offered a retry", ThreadHistoryDemand(stoppedBy = stop).canRetry)
            }
        val inFlight = ThreadHistoryDemand(stoppedBy = HistoryWalkStop.RetryableFailure, inFlight = true)
        assertFalse(inFlight.canRetry)
    }

    @Test
    fun askingAfterAFailure_resumesFromTheSameCursorAtTheSameCostAsAnyOtherPage() {
        listOf(true, false).forEach { retryable ->
            val failed =
                ThreadHistoryDemand()
                    .asking()
                    .settled(pageCursor = "c1", atStart = false)
                    .asking()
                    .failed(retryable = retryable)
            val asking = failed.asking()
            // The same cursor, so the ask loads the page that failed, not the next one.
            assertEquals("c1", asking.cursor)
            assertEquals(1, asking.pagesLoaded)
            assertTrue(asking.inFlight)
            assertNull(asking.stoppedBy)
            assertEquals(2, asking.settled(pageCursor = "c2", atStart = false).pagesLoaded)
        }
    }

    // --- #1352: the refused cursor and the offline notice ------------------------------------------

    @Test
    fun cursorRefused_sendsTheNextAskToTheNewestPageWithoutClaimingTheSlot() {
        val walked =
            ThreadHistoryDemand()
                .asking()
                .settled(pageCursor = "c1", atStart = false)
                .asking()
                .settled(pageCursor = "c2", atStart = false)
        val refused = walked.asking().cursorRefused()
        assertEquals("", refused.cursor)
        // The budget is carried, so a daemon refusing every cursor cannot launder a fresh one.
        assertEquals(2, refused.pagesLoaded)
        // No ask follows by itself: the slot is released and the next gesture asks.
        assertFalse(refused.inFlight)
        assertNull(refused.stoppedBy)
        assertTrue(refused.canAsk)
        assertEquals(ThreadHistoryTail.None, refused.tail(connected = true))
    }

    @Test
    fun tail_showsOneSlotPerWalkStateAndHidesTheTerminationReasons() {
        assertEquals(ThreadHistoryTail.None, ThreadHistoryDemand().tail(connected = true))
        assertEquals(ThreadHistoryTail.Loading, ThreadHistoryDemand().asking().tail(connected = true))
        assertEquals(
            ThreadHistoryTail.Retry,
            ThreadHistoryDemand(stoppedBy = HistoryWalkStop.RetryableFailure).tail(connected = true),
        )
        assertEquals(
            ThreadHistoryTail.DeadEnd,
            ThreadHistoryDemand(stoppedBy = HistoryWalkStop.PermanentFailure).tail(connected = true),
        )
        // A normally-ended walk shows nothing: reaching the start of a log is not a failure.
        listOf(HistoryWalkStop.AtStart, HistoryWalkStop.NotAdvancing, HistoryWalkStop.PageCap)
            .forEach { stop ->
                assertEquals(
                    "$stop surfaced a tail row",
                    ThreadHistoryTail.None,
                    ThreadHistoryDemand(stoppedBy = stop).tail(connected = true),
                )
            }
    }

    @Test
    fun tail_whileNotConnected_isTheOfflineNoticeUnlessTheWalkReachedTheStart() {
        listOf(
            ThreadHistoryDemand(),
            ThreadHistoryDemand().asking(),
            ThreadHistoryDemand(stoppedBy = HistoryWalkStop.RetryableFailure),
            ThreadHistoryDemand(stoppedBy = HistoryWalkStop.PermanentFailure),
            ThreadHistoryDemand(stoppedBy = HistoryWalkStop.NotAdvancing),
            ThreadHistoryDemand(stoppedBy = HistoryWalkStop.PageCap),
        ).forEach { demand ->
            assertEquals("$demand", ThreadHistoryTail.Offline, demand.tail(connected = false))
        }
        assertEquals(ThreadHistoryTail.None, ThreadHistoryDemand(stoppedBy = HistoryWalkStop.AtStart).tail(connected = false))
    }
}
