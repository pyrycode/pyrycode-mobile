package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.repository.BoundaryReason
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The #782 render-time join: the thread's items against the daemon's `queue_state` backlog, one row
 * list out. Drives the five rules the plan states (and `pyrycode-desktop`'s `fixtureQueuedRows.ts` states
 * for the sibling client), plus the key derivation the thread's `LazyColumn` depends on.
 *
 * A pure function, so this is a JVM unit test: no Compose, no repository, no clock.
 */
class ThreadRowsTest {
    private val ts: Instant = Instant.parse("2026-09-22T10:00:00Z")

    private fun boundary(
        previous: String = "s0",
        new: String = "s1",
        occurredAt: Instant = ts,
        reason: BoundaryReason = BoundaryReason.Clear,
    ): ThreadItem.SessionBoundary =
        ThreadItem.SessionBoundary(
            previousSessionId = previous,
            newSessionId = new,
            reason = reason,
            occurredAt = occurredAt,
        )

    private fun ThreadItem.key(): String = ThreadRow.Delivered(this).listKey(0)

    // AC #1 — a send the daemon parked draws ONE row, at the position it was sent, carrying the queue
    // treatment's two values. Nothing is appended at the tail, which is the whole double-draw bug.
    @Test
    fun `boundaries sharing a session pair but not an instant get distinct keys`() {
        val first = boundary(previous = "A", new = "A", reason = BoundaryReason.IdleEvict)
        val second = first.copy(occurredAt = Instant.parse("2026-09-22T11:00:00Z"))

        assertNotEquals(first.key(), second.key())
    }

    // #775 — the ids are daemon-supplied, so a delimiter inside one must not let two different triples
    // concatenate to one key.
    @Test
    fun `boundary ids that would concatenate identically still get distinct keys`() {
        assertNotEquals(boundary(previous = "a->b", new = "c").key(), boundary(previous = "a", new = "b->c").key())
        assertNotEquals(boundary(previous = "a1:", new = "b").key(), boundary(previous = "a", new = "1:b").key())
    }

    // #775 — the key reads exactly the triple the dedups compare: equal triples key equally whatever the
    // reason, which is why the dedups must collapse them.
    @Test
    fun `a boundary's key reads its session pair and instant and nothing else`() {
        assertEquals(boundary().key(), boundary().key())
        assertEquals(boundary().key(), boundary(reason = BoundaryReason.IdleEvict).key())
        assertNotEquals(boundary().key(), boundary(new = "s2").key())
        assertNotEquals(boundary().key(), boundary(previous = "s9").key())
    }
}
