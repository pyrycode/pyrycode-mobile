package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.model.Role
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadProjectionTest {
    // #1351 AC #2: the pushed copy of the phone's own send leaves the confirmed row, names and send time included.
    @Test
    fun appendLiveMessage_heldId_leavesRowUnchanged() =
        runTest {
            val projection = ThreadProjection()
            val sent =
                userMessage("mine", "hello", SENT_AT)
                    .copy(attachments = listOf(MessageAttachment(ATTACHMENT_ID, "photo.jpg", "image/jpeg")))
            projection.appendMessages(listOf("c1" to sent))
            val emissions = collect(projection, "c1")
            runCurrent()

            projection.appendLiveMessage(
                "c1",
                userMessage("mine", "hello", PUSHED_AT).copy(attachments = listOf(MessageAttachment(ATTACHMENT_ID))),
            )
            runCurrent()

            assertEquals(listOf(listOf(ThreadItem.MessageItem(sent))), emissions)
        }

    // #1351 AC #1: a new id is appended at the end of its own conversation only.
    @Test
    fun appendLiveMessage_newId_appendsAtEndOfItsConversation() =
        runTest {
            val projection = ThreadProjection()
            val first = userMessage("m1", "first", SENT_AT)
            projection.appendMessages(listOf("c1" to first))
            val c1 = collect(projection, "c1")
            val c2 = collect(projection, "c2")
            runCurrent()

            val peer = userMessage("peer-1", "from desktop", PUSHED_AT)
            projection.appendLiveMessage("c1", peer)
            runCurrent()

            assertEquals(listOf(ThreadItem.MessageItem(first), ThreadItem.MessageItem(peer)), c1.last())
            assertEquals(listOf(emptyList<ThreadItem>()), c2)
        }

    private fun TestScope.collect(
        projection: ThreadProjection,
        conversationId: String,
    ): MutableList<List<ThreadItem>> {
        val emissions = mutableListOf<List<ThreadItem>>()
        backgroundScope.launch { projection.observe(conversationId).collect { emissions += it } }
        return emissions
    }

    private fun userMessage(
        id: String,
        text: String,
        at: Instant,
    ): Message = Message(id = id, sessionId = "", role = Role.User, content = text, timestamp = at, isStreaming = false)

    private companion object {
        const val ATTACHMENT_ID = "3f2b8c1e-5d4a-4b6f-9a2e-7c1d0e9f8a6b"
        val SENT_AT: Instant = Instant.parse("2026-05-31T10:00:00Z")
        val PUSHED_AT: Instant = Instant.parse("2026-05-31T10:00:02Z")
    }
}
