package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
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

    // ---- #1358: a compaction leaves a divider from its falling edge, filled in by the boundary ----

    // AC 1: a failed outcome, by either field, draws one failed divider at the falling edge's ts.
    @Test
    fun compacting_fallingEdgeReportingFailure_addsOneFailedDivider() =
        runTest {
            for (outcome in listOf(""","compact_result":"failed"""", ""","compact_error":"<b>x</b> https://e.example"""")) {
                val projection = ThreadProjection()
                val thread = collect(projection, "c1")
                runCurrent()

                projection.applyCompacting(compacting(active = true, ts = RISE))
                projection.applyCompacting(compacting(active = false, ts = FALL, outcome = outcome))
                runCurrent()

                assertEquals(listOf(divider(FALL, failed = true)), thread.last())
            }
        }

    // AC 1: success draws an unreported divider, and the boundary replaces it in place with its own ts.
    @Test
    fun compacting_successThenBoundary_replacesTheDividerInPlace() =
        runTest {
            val projection = ThreadProjection()
            val thread = collect(projection, "c1")
            projection.appendMessages(listOf("c1" to userMessage("m1", "before", SENT_AT)))
            projection.applyCompacting(compacting(active = true, ts = RISE))
            projection.applyCompacting(compacting(active = false, ts = FALL, outcome = ""","compact_result":"success""""))
            runCurrent()
            assertEquals(divider(FALL), thread.last().last())

            projection.appendMessages(listOf("c1" to userMessage("m2", "after", PUSHED_AT)))
            projection.applyCompactionBoundary(boundary(ts = BOUNDARY))
            runCurrent()

            assertEquals(
                listOf(
                    ThreadItem.MessageItem(userMessage("m1", "before", SENT_AT)),
                    ThreadItem.CompactionBoundary(24000L, 3000L, manual = true, occurredAt = Instant.parse(BOUNDARY)),
                    ThreadItem.MessageItem(userMessage("m2", "after", PUSHED_AT)),
                ),
                thread.last(),
            )
        }

    // AC 1: a boundary with no edge before it appends one divider, as before #1358.
    @Test
    fun compactionBoundary_withNoEdge_appendsOneDivider() =
        runTest {
            val projection = ThreadProjection()
            val thread = collect(projection, "c1")

            projection.applyCompactionBoundary(boundary(ts = BOUNDARY))
            projection.applyCompactionBoundary(boundary(ts = BOUNDARY))
            runCurrent()

            assertEquals(
                listOf(ThreadItem.CompactionBoundary(24000L, 3000L, manual = true, occurredAt = Instant.parse(BOUNDARY))),
                thread.last(),
            )
        }

    // Desktop's edges: a falling edge with no rising edge draws nothing, a failed divider is never filled
    // in, and a new rising edge forgets the pending divider.
    @Test
    fun compacting_edgeRules_followDesktop() =
        runTest {
            val projection = ThreadProjection()
            val thread = collect(projection, "c1")

            projection.applyCompacting(compacting(active = false, ts = RISE))
            runCurrent()
            assertEquals(emptyList<ThreadItem>(), thread.last())

            projection.applyCompacting(compacting(active = true, ts = RISE))
            projection.applyCompacting(compacting(active = false, ts = FALL, outcome = ""","compact_result":"failed""""))
            projection.applyCompactionBoundary(boundary(ts = BOUNDARY))
            runCurrent()
            assertEquals(listOf(divider(FALL, failed = true), counted(BOUNDARY)), thread.last())

            projection.applyCompacting(compacting(active = true, ts = LATER_RISE))
            projection.applyCompacting(compacting(active = false, ts = LATER_FALL))
            projection.applyCompacting(compacting(active = true, ts = LAST_RISE))
            projection.applyCompactionBoundary(boundary(ts = LAST_BOUNDARY))
            runCurrent()
            assertEquals(
                listOf(divider(FALL, failed = true), counted(BOUNDARY), divider(LATER_FALL), counted(LAST_BOUNDARY)),
                thread.last(),
            )
        }

    // A history page that raced the live lane brought this compaction's filled-in row first: the live
    // boundary removes the pending divider instead of writing a second row with the held ts.
    @Test
    fun compactionBoundary_alreadyMergedFromHistory_removesThePendingDivider() =
        runTest {
            val projection = ThreadProjection()
            val thread = collect(projection, "c1")
            projection.applyCompacting(compacting(active = true, ts = RISE))
            projection.applyCompacting(compacting(active = false, ts = FALL))
            val page =
                HistoryPage(
                    entries =
                        listOf(
                            HistoryEntry(3, "compaction_boundary", boundary(ts = BOUNDARY).payload, Instant.parse(BOUNDARY)),
                            HistoryEntry(2, "compacting", compacting(active = false, ts = FALL).payload, Instant.parse(FALL)),
                            HistoryEntry(1, "compacting", compacting(active = true, ts = RISE).payload, Instant.parse(RISE)),
                        ),
                    cursor = "",
                    atStart = true,
                )
            projection.mergeHistoryPage("c1", page, interactive = true)
            runCurrent()
            assertEquals(listOf(counted(BOUNDARY), divider(FALL)), thread.last())

            projection.applyCompactionBoundary(boundary(ts = BOUNDARY))
            runCurrent()

            assertEquals(listOf(counted(BOUNDARY)), thread.last())
        }

    private fun compacting(
        active: Boolean,
        ts: String,
        outcome: String = "",
    ): Envelope =
        Envelope(
            id = 1L,
            type = "compacting",
            ts = ts,
            payload = MobileJson.parseToJsonElement("""{"conversation_id":"c1","active":$active$outcome}"""),
        )

    private fun boundary(ts: String): Envelope =
        Envelope(
            id = 1L,
            type = "compaction_boundary",
            ts = ts,
            payload =
                MobileJson.parseToJsonElement(
                    """{"conversation_id":"c1","trigger":"manual","pre_tokens":24000,"post_tokens":3000}""",
                ),
        )

    private fun divider(
        at: String,
        failed: Boolean = false,
    ) = ThreadItem.CompactionBoundary(null, null, manual = false, occurredAt = Instant.parse(at), failed = failed)

    private fun counted(at: String) = ThreadItem.CompactionBoundary(24000L, 3000L, manual = true, occurredAt = Instant.parse(at))

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
        const val RISE = "2026-05-31T10:01:00Z"
        const val FALL = "2026-05-31T10:01:20Z"
        const val BOUNDARY = "2026-05-31T10:01:21Z"
        const val LATER_RISE = "2026-05-31T10:02:00Z"
        const val LATER_FALL = "2026-05-31T10:02:20Z"
        const val LAST_RISE = "2026-05-31T10:03:00Z"
        const val LAST_BOUNDARY = "2026-05-31T10:03:21Z"
    }
}
