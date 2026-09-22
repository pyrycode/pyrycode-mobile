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
    fun failed_keepsEveryLoadedRowsPositionAndStopsAsking() {
        val walked = ThreadHistoryDemand().asking().settled(pageCursor = "c1", atStart = false)
        val failed = walked.asking().failed(retryable = true)
        // The walk's position is kept — the cursor and the page count are exactly the walked ones.
        assertEquals("c1", failed.cursor)
        assertEquals(1, failed.pagesLoaded)
        // The in-flight state always clears, so the loading affordance can never be left stuck.
        assertFalse(failed.inFlight)
        assertEquals(HistoryWalkStop.RetryableFailure, failed.stoppedBy)
        // A stopped walk still refuses the SCROLL-driven ask. Recovery is a deliberate gesture.
        assertFalse(failed.canAsk)
    }

    @Test
    fun everyStopReason_refusesFurtherAsks() {
        HistoryWalkStop.entries.forEach { stop ->
            assertFalse("$stop still allowed an ask", ThreadHistoryDemand(stoppedBy = stop).canAsk)
        }
    }

    // --- #778: the retryable split, the retry claim and the two restarts ---------------------------

    @Test
    fun failed_splitsOnRetryableAndOnlyTheRetryableOneOffersARetry() {
        val walked = ThreadHistoryDemand().asking().settled(pageCursor = "c1", atStart = false)
        val retryable = walked.asking().failed(retryable = true)
        val permanent = walked.asking().failed(retryable = false)
        assertEquals(HistoryWalkStop.RetryableFailure, retryable.stoppedBy)
        assertEquals(HistoryWalkStop.PermanentFailure, permanent.stoppedBy)
        assertTrue(retryable.canRetry)
        assertFalse(permanent.canRetry)
        // Both keep the walk's position, so a retry resumes from the page that failed.
        assertEquals("c1", permanent.cursor)
        assertEquals(1, permanent.pagesLoaded)
    }

    @Test
    fun canRetry_isFalseForEveryTerminalStopAndWhileInFlight() {
        HistoryWalkStop.entries
            .filter { it != HistoryWalkStop.RetryableFailure }
            .forEach { stop ->
                assertFalse("$stop offered a retry", ThreadHistoryDemand(stoppedBy = stop).canRetry)
            }
        // Open Question 2: the !inFlight term makes a stale-in-flight retry claim unreachable.
        val inFlight = ThreadHistoryDemand(stoppedBy = HistoryWalkStop.RetryableFailure, inFlight = true)
        assertFalse(inFlight.canRetry)
    }

    @Test
    fun retrying_resumesFromTheSameCursorAtTheSameCostAsAnyOtherPage() {
        val failed =
            ThreadHistoryDemand()
                .asking()
                .settled(pageCursor = "c1", atStart = false)
                .asking()
                .failed(retryable = true)
        val retrying = failed.retrying()
        // AC #1: the same cursor, so the retry loads the page that failed, not the next one.
        assertEquals("c1", retrying.cursor)
        assertEquals(1, retrying.pagesLoaded)
        assertTrue(retrying.inFlight)
        assertNull(retrying.stoppedBy)
        // It costs one page of budget when it settles, exactly like a scroll-driven ask.
        assertEquals(2, retrying.settled(pageCursor = "c2", atStart = false).pagesLoaded)
    }

    @Test
    fun restarted_goesBackToTheNewestPageCarryingTheBudget() {
        val walked =
            ThreadHistoryDemand()
                .asking()
                .settled(pageCursor = "c1", atStart = false)
                .asking()
                .settled(pageCursor = "c2", atStart = false)
        val restarted = walked.restarted()
        // AC #3/#4: newest page, and a cursor minted on the old walk is not carried into the new one.
        assertEquals("", restarted.cursor)
        // The budget is CARRIED, not reset — a restart that reset it would be a bound with an off switch.
        assertEquals(2, restarted.pagesLoaded)
        assertTrue(restarted.inFlight)
        assertNull(restarted.stoppedBy)
        // A settle from the superseded walk is distinguishable by generation, and so droppable.
        assertEquals(walked.walk + 1, restarted.walk)
    }

    @Test
    fun restarted_clearsAFailureSoTheBackgroundForegroundCycleSelfHeals() {
        val failed = ThreadHistoryDemand(cursor = "c1").asking().failed(retryable = false)
        val restarted = failed.restarted()
        assertNull(restarted.stoppedBy)
        assertEquals("", restarted.cursor)
        assertEquals(ThreadHistoryTail.Loading, restarted.tail())
    }

    @Test
    fun restarted_cannotBuyAnAskTheCapAlreadyRefused() {
        var demand = ThreadHistoryDemand()
        repeat(MAX_HISTORY_PAGES) { page ->
            demand = demand.asking().settled(pageCursor = "c$page", atStart = false)
        }
        assertEquals(HistoryWalkStop.PageCap, demand.stoppedBy)

        val restarted = demand.restarted()
        // A flapping connection cannot launder a fresh budget: the restart claims no slot at all...
        assertFalse(restarted.inFlight)
        assertEquals(HistoryWalkStop.PageCap, restarted.stoppedBy)
        assertEquals(MAX_HISTORY_PAGES, restarted.pagesLoaded)
        // ...and still bumps the generation, so an ask in flight from the old walk is invalidated.
        assertEquals(demand.walk + 1, restarted.walk)
    }

    @Test
    fun repeatedRestarts_spendTheBudgetRatherThanResettingIt() {
        // The ticket's security shape: a daemon refusing every cursor while the connection flaps.
        var demand = ThreadHistoryDemand()
        var pages = 0
        repeat(MAX_HISTORY_PAGES * 3) {
            demand = demand.restarted()
            if (demand.inFlight) {
                pages++
                demand = demand.settled(pageCursor = "c$pages", atStart = false)
            }
        }
        assertEquals(MAX_HISTORY_PAGES, pages)
        assertEquals(HistoryWalkStop.PageCap, demand.stoppedBy)
    }

    @Test
    fun tail_showsOneSlotPerWalkStateAndHidesTheTerminationReasons() {
        assertEquals(ThreadHistoryTail.None, ThreadHistoryDemand().tail())
        assertEquals(ThreadHistoryTail.Loading, ThreadHistoryDemand().asking().tail())
        assertEquals(
            ThreadHistoryTail.Retry,
            ThreadHistoryDemand(stoppedBy = HistoryWalkStop.RetryableFailure).tail(),
        )
        assertEquals(
            ThreadHistoryTail.DeadEnd,
            ThreadHistoryDemand(stoppedBy = HistoryWalkStop.PermanentFailure).tail(),
        )
        // A normally-ended walk shows nothing: reaching the start of a log is not a failure.
        listOf(HistoryWalkStop.AtStart, HistoryWalkStop.NotAdvancing, HistoryWalkStop.PageCap)
            .forEach { stop ->
                assertEquals("$stop surfaced a tail row", ThreadHistoryTail.None, ThreadHistoryDemand(stoppedBy = stop).tail())
            }
    }
}
