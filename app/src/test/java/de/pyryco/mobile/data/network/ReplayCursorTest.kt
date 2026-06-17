package de.pyryco.mobile.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Behaviour tests for the reconnect-spanning replay cursor (#412): a strictly-advancing,
 * positive-guarded, null-initial high-water mark over the interactive structured-stream `event_id`.
 */
class ReplayCursorTest {
    @Test
    fun freshCursor_readsNoCursor_notZero() {
        val cursor = ReplayCursor()
        assertNull("a fresh cursor is 'no cursor', never 0", cursor.latest)
    }

    @Test
    fun record_advancesOnlyOnStrictlyGreater() {
        val cursor = ReplayCursor()

        cursor.record(5)
        assertEquals(5L, cursor.latest)

        cursor.record(3) // smaller → no-op
        assertEquals(5L, cursor.latest)

        cursor.record(5) // equal → no-op
        assertEquals(5L, cursor.latest)

        cursor.record(9) // strictly greater → advances
        assertEquals(9L, cursor.latest)
    }

    @Test
    fun record_ignoresNonPositiveOnFreshCursor() {
        val cursor = ReplayCursor()

        cursor.record(0)
        assertNull("0 is never recorded as the high-water mark", cursor.latest)

        cursor.record(-1)
        assertNull("a negative (e.g. a wrapped uint64) is never recorded", cursor.latest)
    }

    @Test
    fun record_nonPositiveDoesNotDisturbAnExistingMark() {
        val cursor = ReplayCursor()
        cursor.record(7)

        cursor.record(0)
        assertEquals(7L, cursor.latest)

        cursor.record(-3)
        assertEquals(7L, cursor.latest)
    }

    // ---- #417: reset() — clear the mark so the next hello-build omits last_event_id -----------------

    @Test
    fun reset_afterRecord_clearsToNoCursor() {
        val cursor = ReplayCursor()
        cursor.record(7)
        assertEquals(7L, cursor.latest)

        cursor.reset()
        assertNull("reset clears the high-water mark back to 'no cursor'", cursor.latest)
    }

    @Test
    fun reset_onFreshCursor_isNoOp() {
        val cursor = ReplayCursor()

        cursor.reset()
        assertNull("reset on a fresh cursor is an idempotent no-op", cursor.latest)
    }

    @Test
    fun reset_thenRecord_readvancesFromNoCursor() {
        val cursor = ReplayCursor()
        cursor.record(5)

        cursor.reset()
        assertNull(cursor.latest)

        // Post-resync the cursor re-advances on new frames — the next reconnect would advertise the
        // new position. The strictly-greater fold restarts from "no cursor", so any positive advances.
        cursor.record(8)
        assertEquals(8L, cursor.latest)
    }
}
