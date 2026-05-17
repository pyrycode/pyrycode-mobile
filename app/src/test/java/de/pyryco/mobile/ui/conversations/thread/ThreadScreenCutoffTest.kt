package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.BoundaryReason
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadScreenCutoffTest {
    @Test
    fun emptyList_returnsNegativeOne() {
        assertEquals(-1, mostRecentSessionBoundaryIndex(emptyList()))
    }

    @Test
    fun messagesOnly_returnsNegativeOne() {
        val items =
            listOf(
                msg("u1", "s1", Role.User, "2026-05-17T14:32:00Z"),
                msg("a1", "s1", Role.Assistant, "2026-05-17T14:32:10Z"),
                msg("u2", "s1", Role.User, "2026-05-17T14:32:20Z"),
            )
        assertEquals(-1, mostRecentSessionBoundaryIndex(items))
    }

    @Test
    fun singleBoundary_returnsItsChronologicalIndex() {
        val items =
            listOf(
                msg("u1", "s1", Role.User, "2026-05-17T14:32:00Z"),
                msg("a1", "s1", Role.Assistant, "2026-05-17T14:32:10Z"),
                boundary("s1", "s2", "2026-05-17T14:32:20Z"),
                msg("u2", "s2", Role.User, "2026-05-17T14:32:30Z"),
            )
        assertEquals(2, mostRecentSessionBoundaryIndex(items))
    }

    @Test
    fun multipleBoundaries_returnsIndexOfLatest() {
        val items =
            listOf(
                msg("u1", "s1", Role.User, "2026-05-17T14:32:00Z"),
                boundary("s1", "s2", "2026-05-17T14:32:10Z"),
                msg("u2", "s2", Role.User, "2026-05-17T14:32:20Z"),
                boundary("s2", "s3", "2026-05-17T14:32:30Z"),
                msg("u3", "s3", Role.User, "2026-05-17T14:32:40Z"),
                boundary("s3", "s4", "2026-05-17T14:32:50Z"),
                msg("u4", "s4", Role.User, "2026-05-17T14:33:00Z"),
            )
        assertEquals(5, mostRecentSessionBoundaryIndex(items))
    }

    @Test
    fun boundaryAtFirstPosition_returnsZero() {
        val items =
            listOf(
                boundary("s0", "s1", "2026-05-17T14:32:00Z"),
                msg("u1", "s1", Role.User, "2026-05-17T14:32:10Z"),
                msg("a1", "s1", Role.Assistant, "2026-05-17T14:32:20Z"),
            )
        assertEquals(0, mostRecentSessionBoundaryIndex(items))
    }

    @Test
    fun boundaryAtLastPosition_returnsLastIndex() {
        val items =
            listOf(
                msg("u1", "s1", Role.User, "2026-05-17T14:32:00Z"),
                msg("a1", "s1", Role.Assistant, "2026-05-17T14:32:10Z"),
                boundary("s1", "s2", "2026-05-17T14:32:20Z"),
            )
        assertEquals(2, mostRecentSessionBoundaryIndex(items))
    }

    private fun msg(
        id: String,
        sessionId: String,
        role: Role,
        timestamp: String,
    ): ThreadItem.MessageItem =
        ThreadItem.MessageItem(
            Message(
                id = id,
                sessionId = sessionId,
                role = role,
                content = "",
                timestamp = Instant.parse(timestamp),
                isStreaming = false,
            ),
        )

    private fun boundary(
        previousSessionId: String,
        newSessionId: String,
        occurredAt: String,
    ): ThreadItem.SessionBoundary =
        ThreadItem.SessionBoundary(
            previousSessionId = previousSessionId,
            newSessionId = newSessionId,
            reason = BoundaryReason.Clear,
            occurredAt = Instant.parse(occurredAt),
            workspaceCwd = null,
        )
}
