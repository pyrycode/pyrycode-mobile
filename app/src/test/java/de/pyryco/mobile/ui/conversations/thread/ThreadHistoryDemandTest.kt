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
        val failed = walked.asking().failed()
        // The walk's position is kept — the cursor and the page count are exactly the walked ones.
        assertEquals("c1", failed.cursor)
        assertEquals(1, failed.pagesLoaded)
        // The in-flight state always clears, so the loading affordance can never be left stuck.
        assertFalse(failed.inFlight)
        assertEquals(HistoryWalkStop.Failed, failed.stoppedBy)
        assertFalse(failed.canAsk)
    }

    @Test
    fun everyStopReason_refusesFurtherAsks() {
        HistoryWalkStop.entries.forEach { stop ->
            assertFalse("$stop still allowed an ask", ThreadHistoryDemand(stoppedBy = stop).canAsk)
        }
    }
}
