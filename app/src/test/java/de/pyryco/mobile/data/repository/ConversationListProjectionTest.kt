package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.ConversationResponseDto
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The archive stamp across the list projection's two writers (#1332): a `conversations` snapshot carries
 * `archived_at`, and a `conversation_updated` record never does, so the merge decides what it keeps.
 */
class ConversationListProjectionTest {
    @Test
    fun conversationUpdated_keepsTheStoredStampWhileArchived_andClearsItOnUnarchive() {
        val projection = ConversationListProjection()
        projection.applySnapshot(snapshot(STAMP))
        assertEquals(Instant.parse(STAMP), projection.current().single().archivedAt)

        // A rename of the still-archived row: the record has no stamp, and the stored one stays.
        val renamed = projection.upsertConversation(record(name = "renamed", archived = true))
        assertEquals(Instant.parse(STAMP), renamed.archivedAt)
        assertEquals("renamed", projection.current().single().name)
        assertEquals(Instant.parse(STAMP), projection.current().single().archivedAt)

        // Unarchived: the stamp goes with the flag.
        val restored = projection.upsertConversation(record(name = "renamed", archived = false))
        assertNull(restored.archivedAt)
        assertNull(projection.current().single().archivedAt)

        // Archived again from this phone: no stamp until the next snapshot brings the daemon's.
        projection.upsertConversation(record(name = "renamed", archived = true))
        assertNull(projection.current().single().archivedAt)
    }

    private fun snapshot(archivedAt: String): Envelope =
        Envelope(
            id = 1,
            type = "conversations",
            ts = "2026-06-10T09:00:00Z",
            payload =
                MobileJson.parseToJsonElement(
                    """{"conversations":[{"id":"$ID","name":"chat","is_promoted":false,"is_archived":true,"cwd":"/p",""" +
                        """"archived_at":"$archivedAt","last_message_ts":"$LAST_USED","last_used_at":"$LAST_USED"}]}""",
                ),
        )

    private fun record(
        name: String,
        archived: Boolean,
    ) = ConversationResponseDto(
        id = ID,
        name = name,
        isPromoted = false,
        isArchived = archived,
        cwd = "/p",
        lastUsedAt = Instant.parse(LAST_USED),
    )

    private companion object {
        const val ID = "conv-1"
        const val STAMP = "2026-06-10T08:00:00Z"
        const val LAST_USED = "2026-05-01T00:00:00Z"
    }
}
