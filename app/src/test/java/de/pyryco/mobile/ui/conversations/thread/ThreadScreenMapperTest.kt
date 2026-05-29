package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.BoundaryReason
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.components.formatRelativeTime
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadScreenMapperTest {
    private val now = Instant.parse("2026-05-29T12:00:00Z")

    private fun message(
        id: String,
        timestamp: Instant,
    ): ThreadItem.MessageItem =
        ThreadItem.MessageItem(
            Message(
                id = id,
                sessionId = "s1",
                role = Role.User,
                content = "hi",
                timestamp = timestamp,
                isStreaming = false,
            ),
        )

    @Test
    fun toChannelInfoUiModel_derivesLabelsCountsAndPassesIngredientsThrough() {
        val earliest = Instant.parse("2026-05-20T10:00:00Z")
        val later = Instant.parse("2026-05-20T10:05:00Z")
        val boundary =
            ThreadItem.SessionBoundary(
                previousSessionId = "s1",
                newSessionId = "s2",
                reason = BoundaryReason.Clear,
                occurredAt = Instant.parse("2026-05-20T10:10:00Z"),
            )
        val lastUsed = Instant.parse("2026-05-29T10:00:00Z")
        val state =
            ThreadUiState(
                conversationId = "ch_1",
                displayName = "kitchenclaw refactor",
                workspacePath = "~/Workspace/Projects/KitchenClaw",
                lastUsedAt = lastUsed,
                sessionCount = 4,
                items = listOf(message("m0", earliest), message("m1", later), boundary),
            )

        val model = state.toChannelInfoUiModel(now)

        assertEquals("kitchenclaw refactor", model.conversationName)
        assertEquals("~/Workspace/Projects/KitchenClaw", model.workspacePath)
        assertEquals("ch_1", model.channelId)
        assertEquals(4, model.sessionCount)
        // counts only MessageItems, not the SessionBoundary
        assertEquals(2, model.messageCount)
        // created-date derives from the earliest thread item
        assertEquals(formatRelativeTime(earliest, now), model.createdLabel)
        assertEquals(formatRelativeTime(lastUsed, now), model.lastActivityLabel)
        assertTrue(model.memoryPlugins.isEmpty())
    }

    @Test
    fun toChannelInfoUiModel_emptyItems_rendersDashCreatedAndZeroMessages() {
        val state =
            ThreadUiState(
                conversationId = "ch_1",
                displayName = "x",
                lastUsedAt = Instant.parse("2026-05-29T10:00:00Z"),
                items = emptyList(),
            )

        val model = state.toChannelInfoUiModel(now)

        assertEquals("—", model.createdLabel)
        assertEquals(0, model.messageCount)
    }

    @Test
    fun toChannelInfoUiModel_nullLastUsedAt_rendersDashLastActivity() {
        val state =
            ThreadUiState(
                conversationId = "ch_1",
                displayName = "x",
                lastUsedAt = null,
                items = listOf(message("m0", Instant.parse("2026-05-20T10:00:00Z"))),
            )

        val model = state.toChannelInfoUiModel(now)

        assertEquals("—", model.lastActivityLabel)
    }
}
