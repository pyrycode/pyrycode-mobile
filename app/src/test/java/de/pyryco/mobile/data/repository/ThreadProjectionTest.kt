package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.LiveSessionEvent
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
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadProjectionTest {
    @Test
    fun modernDelivery_delayedDrainsAcrossBothReplies_preserveFifoAndSegments() =
        runTest { assertModernDeliverySequence(removeBeforePush = false) }

    @Test
    fun modernDelivery_removalBeforeEachPush_preservesStreamPlacement() = runTest { assertModernDeliverySequence(removeBeforePush = true) }

    private fun TestScope.assertModernDeliverySequence(removeBeforePush: Boolean) {
        val projection = ThreadProjection()
        val queue = QueueProjection()
        val thread = collect(projection, "c1")
        val first = startQueuedTurn(projection, queue)
        val second = sendOwn(projection, "second", "second text")
        projection.onQueueState(queue, 42L to "mine", 43L to "second")
        projection.applyAssistantDelta(delta("turn-1", 1, "Done"))
        projection.finalizeAssistantTurn(LiveSessionEvent.TurnEnd("c1", "turn-1", "end_turn"))

        fun expect(vararg expected: String) {
            runCurrent()
            assertEquals(expected.toList(), ids(thread.last()))
            for (rows in thread) {
                val pending = ids(rows).filter { it in setOf("mine", "second") }
                if (pending.size == 2) assertEquals(listOf("mine", "second"), pending)
                for (id in listOf("turn-2", "turn-3")) {
                    assertEquals("split $id: ${ids(rows)}", 0, ids(rows).count { it.startsWith("$id#") })
                }
            }
        }
        expect("turn-1", "tool-1", "turn-1#1", "mine", "second")
        if (removeBeforePush) projection.onQueueState(queue, 43L to "second", turnOpen = false)
        projection.appendLiveMessage("c1", userMessage("mine", "daemon copy", PUSHED_AT), queuedMessageId = 42L)
        projection.applyAssistantDelta(delta("turn-2", 0, "B0"))
        expect("turn-1", "tool-1", "turn-1#1", "mine", "turn-2", "second")
        projection.onQueueState(queue, 42L to "mine", 43L to "second")
        projection.settleQueuedEchoes(queue) { true } // Another conversation's unchanged snapshot.
        projection.applyAssistantDelta(delta("turn-2", 1, "B1"))
        projection.finalizeAssistantTurn(LiveSessionEvent.TurnEnd("c1", "turn-2", "end_turn"))
        expect("turn-1", "tool-1", "turn-1#1", "mine", "turn-2", "second")
        if (removeBeforePush) projection.onQueueState(queue, turnOpen = false)
        projection.appendLiveMessage("c1", userMessage("second", "daemon copy", PUSHED_AT), queuedMessageId = 43L)
        projection.applyAssistantDelta(delta("turn-3", 0, "C0"))
        projection.applyAssistantDelta(delta("turn-3", 1, "C1"))
        projection.onQueueState(queue, 43L to "second") // B's late drain, during C's reply.
        expect("turn-1", "tool-1", "turn-1#1", "mine", "turn-2", "second", "turn-3")
        projection.onQueueState(queue)
        projection.onQueueState(queue, 42L to "mine", 43L to "second") // Stale reassertion after absence.
        projection.appendLiveMessage("c1", userMessage("mine", "duplicate", PUSHED_AT), queuedMessageId = 42L)
        projection.appendLiveMessage("c1", userMessage("second", "duplicate", PUSHED_AT), queuedMessageId = 43L)
        projection.applyAssistantDelta(delta("turn-3", 2, "C2"))
        projection.finalizeAssistantTurn(LiveSessionEvent.TurnEnd("c1", "turn-3", "end_turn"))
        expect("turn-1", "tool-1", "turn-1#1", "mine", "turn-2", "second", "turn-3")
        assertEquals(ThreadItem.MessageItem(first), thread.last()[3])
        assertEquals(ThreadItem.MessageItem(second), thread.last()[5])
        assertEquals("B0B1", (thread.last()[4] as ThreadItem.MessageItem).message.content)
        val reply = (thread.last()[6] as ThreadItem.MessageItem).message
        assertEquals("C0C1C2", reply.content)
        assertEquals(listOf(0, 1, 2), reply.segment?.deltas?.map { it.seq })
    }

    @Test
    fun modernDelivery_noEchoTimingUsesReceivedPositionOnce() =
        runTest {
            val projection = ThreadProjection()
            val queue = QueueProjection()
            val thread = collect(projection, "c1")
            val own = startQueuedTurn(projection, queue)
            projection.finalizeAssistantTurn(LiveSessionEvent.TurnEnd("c1", "turn-1", "end_turn"))
            projection.applyAssistantDelta(delta("turn-2", 0, "Before"))
            projection.applyAssistantDelta(delta("turn-2", 1, " delivery"))
            projection.appendLiveMessage("c1", userMessage("mine", "copy", PUSHED_AT), queuedMessageId = 42L)
            projection.applyAssistantDelta(delta("turn-2", 2, "After"))
            projection.appendLiveMessage("c1", userMessage("mine", "copy", PUSHED_AT), queuedMessageId = 42L)
            projection.onQueueState(queue)
            runCurrent()
            assertEquals(listOf("turn-1", "tool-1", "turn-2", "mine", "turn-2#2"), ids(thread.last()))
            assertEquals(ThreadItem.MessageItem(own), thread.last()[3])
        }

    @Test
    fun modernConsumption_duplicatePeerIdsDoNotSpendTheNextEntry_evenAfterAbsentSnapshots() =
        runTest {
            for (reuseOwn in listOf(false, true)) {
                val projection = ThreadProjection()
                val queue = QueueProjection()
                val thread = collect(projection, "c1")
                projection.applyAssistantDelta(delta("turn-1", 0, "First"))
                if (reuseOwn) sendOwn(projection, "same", "original")
                sendOwn(projection, "mine", "last")
                projection.onQueueState(queue, 41L to "same", 42L to "same", 43L to "mine")
                projection.finalizeAssistantTurn(LiveSessionEvent.TurnEnd("c1", "turn-1", "end_turn"))
                projection.appendLiveMessage("c1", userMessage("same", "copy", PUSHED_AT), queuedMessageId = 41L)
                projection.applyAssistantDelta(delta("turn-2", 0, "A"))
                projection.onQueueState(queue, 42L to "same", 43L to "mine")
                projection.finalizeAssistantTurn(LiveSessionEvent.TurnEnd("c1", "turn-2", "end_turn"))
                projection.appendLiveMessage("c1", userMessage("same", "copy", PUSHED_AT), queuedMessageId = 42L)
                projection.applyAssistantDelta(delta("turn-3", 0, "B"))
                projection.onQueueState(queue, 43L to "mine")
                projection.onQueueState(queue, 41L to "same", 42L to "same", 43L to "mine")
                projection.finalizeAssistantTurn(LiveSessionEvent.TurnEnd("c1", "turn-3", "end_turn"))
                projection.applyAssistantDelta(delta("turn-4", 0, "C0"))
                // Mixed/legacy delivery uses reservation, exposing consumption independently of modern relocation.
                projection.appendLiveMessage("c1", userMessage("mine", "copy", PUSHED_AT))
                projection.applyAssistantDelta(delta("turn-4", 1, "C1"))
                runCurrent()
                assertEquals(listOf("turn-1", "same", "turn-2", "turn-3", "mine", "turn-4"), ids(thread.last()))
                assertEquals("C0C1", (thread.last().last() as ThreadItem.MessageItem).message.content)
            }
        }

    @Test
    fun modernDelivery_foreignAndNonUserRowsNeverRelocate_andConfirmedDropsStaySpent() =
        runTest {
            for (kind in listOf("foreign", "non-user", "dropped")) {
                val projection = ThreadProjection()
                val queue = QueueProjection()
                val thread = collect(projection, "c1")
                val held = userMessage("mine", "original", SENT_AT).copy(role = if (kind == "non-user") Role.Assistant else Role.User)
                projection.appendMessages(listOf("c1" to held))
                if (kind != "foreign") projection.recordMinted("c1", "mine")
                projection.applyAssistantDelta(delta("turn-1", 0, "First"))
                projection.onQueueState(queue, 42L to "mine")
                if (kind == "dropped") {
                    projection.recordDrop("c1", 42L, "mine")
                    projection.onQueueState(queue)
                    projection.onQueueState(queue, 42L to "mine")
                    projection.finalizeAssistantTurn(LiveSessionEvent.TurnEnd("c1", "turn-1", "end_turn"))
                    projection.onQueueState(queue)
                    runCurrent()
                    assertEquals(listOf("turn-1"), ids(thread.last()))
                } else {
                    projection.finalizeAssistantTurn(LiveSessionEvent.TurnEnd("c1", "turn-1", "end_turn"))
                    projection.appendLiveMessage("c1", userMessage("mine", "copy", PUSHED_AT), queuedMessageId = 42L)
                    projection.onQueueState(queue)
                    runCurrent()
                    assertEquals(listOf("mine", "turn-1"), ids(thread.last()))
                    assertEquals(ThreadItem.MessageItem(held), thread.last().first())
                }
            }
        }

    @Test
    fun modernDelivery_idleEchoKeepsTapTime_andSendNowUsesItsPushPosition() =
        runTest {
            for ((idle, localIntent) in listOf(true to false, false to true, false to false)) {
                val projection = ThreadProjection()
                val queue = QueueProjection()
                val thread = collect(projection, "c1")
                if (!idle) projection.applyAssistantDelta(delta("turn-1", 0, "First"))
                val held = sendOwn(projection, "mine", "original")
                projection.onQueueState(queue, 42L to "mine", turnOpen = !idle)
                projection.applyAssistantDelta(delta("turn-1", if (idle) 0 else 1, "Before"))
                if (localIntent) projection.recordSendNow("c1", "mine")
                projection.onQueueState(queue)
                projection.applyToolUse(toolUse("turn-1", "tool-1"))
                projection.appendLiveMessage("c1", userMessage("mine", "copy", PUSHED_AT), sentNow = !idle, queuedMessageId = 42L)
                projection.applyAssistantDelta(delta("turn-1", 2, "After"))
                runCurrent()
                val expected = if (idle) listOf("mine", "turn-1", "tool-1", "turn-1#2") else listOf("turn-1", "tool-1", "mine", "turn-1#2")
                assertEquals(expected, ids(thread.last()))
                assertEquals(ThreadItem.MessageItem(held), thread.last()[if (idle) 0 else 2])
            }
        }

    @Test
    fun modernConsumption_isConversationAndConnectionLocal_andReplayBeforeSnapshotStaysConsumed() =
        runTest {
            for (replayed in listOf(false, true)) {
                val projection = ThreadProjection() // Fresh connection must not inherit the preceding iteration.
                val queue = QueueProjection()
                val thread = collect(projection, "c1")
                projection.applyAssistantDelta(delta("turn-1", 0, "First"))
                sendOwn(projection, "mine", "last")
                projection.appendLiveMessage("other", userMessage("peer", "other", PUSHED_AT), queuedMessageId = 41L)
                if (replayed) {
                    projection.appendLiveMessage("c1", userMessage("peer", "replayed", PUSHED_AT), queuedMessageId = 41L)
                }
                projection.onQueueState(queue, 41L to "peer", 42L to "mine")
                projection.finalizeAssistantTurn(LiveSessionEvent.TurnEnd("c1", "turn-1", "end_turn"))
                if (!replayed) projection.appendLiveMessage("c1", userMessage("peer", "live", PUSHED_AT), queuedMessageId = 41L)
                projection.applyAssistantDelta(delta("turn-2", 0, "Peer reply"))
                projection.onQueueState(queue, 41L to "peer", 42L to "mine")
                projection.finalizeAssistantTurn(LiveSessionEvent.TurnEnd("c1", "turn-2", "end_turn"))
                projection.applyAssistantDelta(delta("turn-3", 0, "Own reply"))
                projection.appendLiveMessage("c1", userMessage("mine", "legacy confirmation", PUSHED_AT))
                runCurrent()
                val expected =
                    if (replayed) {
                        listOf(
                            "turn-1",
                            "peer",
                            "mine",
                            "turn-2",
                            "turn-3",
                        )
                    } else {
                        listOf("turn-1", "peer", "turn-2", "mine", "turn-3")
                    }
                assertEquals(expected, ids(thread.last()))
            }
        }

    @Test
    fun modernDelivery_historyOverlapOnEitherSide_keepsHeldMetadataAndReplayIdentity() =
        runTest {
            for (historyFirst in listOf(false, true)) {
                val projection = ThreadProjection()
                val queue = QueueProjection()
                val thread = collect(projection, "c1")
                val held = startQueuedTurn(projection, queue)
                val entry =
                    HistoryEntry(
                        10L,
                        "message",
                        MobileJson.parseToJsonElement(
                            """{"conversation_id":"c1","message_id":"mine","role":"user","text":"history copy","queued_msg_id":42,"attachment_ids":["$ATTACHMENT_ID"]}""",
                        ),
                        PUSHED_AT,
                    )
                val page = HistoryPage(listOf(entry), cursor = "", atStart = true)
                projection.finalizeAssistantTurn(LiveSessionEvent.TurnEnd("c1", "turn-1", "end_turn"))
                if (historyFirst) projection.mergeHistoryPage("c1", page, interactive = true)
                projection.appendLiveMessage("c1", userMessage("mine", "push copy", PUSHED_AT), queuedMessageId = 42L)
                projection.applyAssistantDelta(delta("turn-2", 0, "Reply"))
                projection.mergeHistoryPage("c1", page, interactive = true)
                projection.appendLiveMessage("c1", userMessage("mine", "replay copy", PUSHED_AT), queuedMessageId = 42L)
                projection.applyAssistantDelta(delta("turn-2", 0, "Reply")) // Replayed delta does not duplicate text.
                projection.applyAssistantDelta(delta("turn-2", 1, " done"))
                projection.onQueueState(queue, 42L to "mine")
                runCurrent()
                assertEquals(listOf("turn-1", "tool-1", "mine", "turn-2"), ids(thread.last()))
                assertEquals(ThreadItem.MessageItem(held), thread.last()[2])
                assertEquals("Reply done", (thread.last()[3] as ThreadItem.MessageItem).message.content)
                val fresh = ThreadProjection()
                val replayedThread = collect(fresh, "c1")
                fresh.mergeHistoryPage("c1", page, interactive = true)
                fresh.appendLiveMessage("c1", userMessage("mine", "replay copy", PUSHED_AT), queuedMessageId = 42L)
                fresh.appendLiveMessage("c1", userMessage("mine", "duplicate", PUSHED_AT), queuedMessageId = 42L)
                runCurrent()
                assertEquals(
                    listOf(
                        ThreadItem.MessageItem(
                            held.copy(
                                content = "history copy",
                                timestamp = PUSHED_AT,
                                attachments = listOf(MessageAttachment(ATTACHMENT_ID)),
                            ),
                        ),
                    ),
                    replayedThread.last(),
                )
            }
        }

    @Test
    fun sendNow_pendingPushDoesNotSplitRunningAssistantDeltas() =
        runTest {
            val projection = ThreadProjection()
            val queue = QueueProjection()
            val thread = collect(projection, "c1")
            projection.applyAssistantDelta(delta("turn-1", 0, "Waiting"))
            sendOwn(projection, "mine", "hello")
            projection.onQueueState(queue, 42L to "mine")
            projection.recordSendNow("c1", "mine")
            projection.onQueueState(queue)
            projection.applyAssistantDelta(delta("turn-1", 1, " for"))
            projection.applyAssistantDelta(delta("turn-1", 2, " input"))
            runCurrent()
            assertEquals(listOf("turn-1"), ids(thread.last()))
            assertEquals("Waiting for input", (thread.last().single() as ThreadItem.MessageItem).message.content)
            projection.appendLiveMessage("c1", userMessage("mine", "hello", PUSHED_AT))
            runCurrent()
            assertEquals(listOf("turn-1", "mine"), ids(thread.last()))
        }

    @Test
    fun sendNow_queueRemovalWaitsForPushAfterInterveningTool() =
        runTest {
            val projection = ThreadProjection()
            val queue = QueueProjection()
            val thread = collect(projection, "c1")
            val sent = startQueuedTurn(projection, queue)
            projection.recordSendNow("c1", "mine")
            projection.onQueueState(queue)
            runCurrent()
            assertEquals(listOf("turn-1", "tool-1"), ids(thread.last()))
            projection.applyToolUse(toolUse("turn-1", "tool-2"))
            projection.appendLiveMessage("c1", userMessage("mine", "hello", PUSHED_AT))
            projection.appendLiveMessage("c1", userMessage("mine", "hello", PUSHED_AT))
            projection.applyAssistantDelta(delta("turn-1", 2, "Marker"))
            runCurrent()
            assertEquals(listOf("turn-1", "tool-1", "tool-2", "mine", "turn-1#2"), ids(thread.last()))
            assertEquals(ThreadItem.MessageItem(sent), thread.last()[3])
        }

    @Test
    fun peerSendNow_queueRemovalWaitsForPushAfterInterveningTool() =
        runTest {
            val projection = ThreadProjection()
            val queue = QueueProjection()
            val thread = collect(projection, "c1")
            val sent = startQueuedTurn(projection, queue)
            projection.onQueueState(queue)
            runCurrent()
            assertEquals(listOf("turn-1", "tool-1"), ids(thread.last()))
            projection.applyToolUse(toolUse("turn-1", "tool-2"))
            projection.appendLiveMessage("c1", userMessage("mine", "hello", PUSHED_AT))
            projection.appendLiveMessage("c1", userMessage("mine", "hello", PUSHED_AT))
            projection.applyAssistantDelta(delta("turn-1", 2, "Marker"))
            runCurrent()
            assertEquals(listOf("turn-1", "tool-1", "tool-2", "mine", "turn-1#2"), ids(thread.last()))
            assertEquals(ThreadItem.MessageItem(sent), thread.last()[3])
        }

    @Test
    fun sendNow_pushBeforeRemoval_settlesOnce() =
        runTest {
            val projection = ThreadProjection()
            val queue = QueueProjection()
            val thread = collect(projection, "c1")
            startQueuedTurn(projection, queue)
            projection.recordSendNow("c1", "mine")
            projection.appendLiveMessage("c1", userMessage("mine", "hello", PUSHED_AT))
            projection.onQueueState(queue)
            projection.appendLiveMessage("c1", userMessage("mine", "hello", PUSHED_AT))
            runCurrent()
            assertEquals(listOf("turn-1", "tool-1", "mine"), ids(thread.last()))
        }

    @Test
    fun sendNow_turnEndsBeforeQueueRemoval_stillWaitsForDeliveredPush() =
        runTest {
            val projection = ThreadProjection()
            val queue = QueueProjection()
            val thread = collect(projection, "c1")
            startQueuedTurn(projection, queue)
            assertTrue(projection.recordSendNow("c1", "mine"))
            projection.finalizeAssistantTurn(LiveSessionEvent.TurnEnd("c1", "turn-1", "end_turn"))
            projection.onQueueState(queue, turnOpen = false)
            runCurrent()
            assertEquals(listOf("turn-1", "tool-1"), ids(thread.last()))
            projection.applyToolUse(toolUse("turn-2", "tool-2"))
            projection.appendLiveMessage("c1", userMessage("mine", "hello", PUSHED_AT))
            runCurrent()
            assertEquals(listOf("turn-1", "tool-1", "tool-2", "mine"), ids(thread.last()))
        }

    @Test
    fun peerSendNow_turnEndsBeforeQueueRemoval_deliveredPushCorrectsPlacementOnce() =
        runTest {
            val projection = ThreadProjection()
            val queue = QueueProjection()
            val thread = collect(projection, "c1")
            val sent = startQueuedTurn(projection, queue)
            projection.finalizeAssistantTurn(LiveSessionEvent.TurnEnd("c1", "turn-1", "end_turn"))
            projection.onQueueState(queue, turnOpen = false)
            runCurrent()
            // Closed-turn removal retains ordinary drain's immediate settlement until delivery is reported.
            assertEquals(listOf("turn-1", "tool-1", "mine"), ids(thread.last()))
            projection.applyToolUse(toolUse("turn-2", "tool-2"))
            projection.appendLiveMessage("c1", userMessage("mine", "hello", PUSHED_AT), sentNow = true)
            runCurrent()
            assertEquals(listOf("turn-1", "tool-1", "tool-2", "mine"), ids(thread.last()))
            assertEquals(ThreadItem.MessageItem(sent), thread.last().last())
            projection.applyToolUse(toolUse("turn-2", "tool-3"))
            projection.appendLiveMessage("c1", userMessage("mine", "hello", PUSHED_AT), sentNow = true)
            projection.onQueueState(queue, 43L to "mine")
            projection.onQueueState(queue)
            runCurrent()
            assertEquals(listOf("turn-1", "tool-1", "tool-2", "mine", "tool-3"), ids(thread.last()))
        }

    @Test
    fun sendNow_foreignId_cannotHideOrMoveHeldRows() =
        runTest {
            val projection = ThreadProjection()
            val queue = QueueProjection()
            val thread = collect(projection, "c1")
            projection.appendLiveMessage("c1", userMessage("foreign", "peer", SENT_AT))
            projection.recordSendNow("c1", "foreign")
            projection.onQueueState(queue, 42L to "foreign")
            projection.onQueueState(queue)
            projection.applyToolUse(toolUse("turn-1", "tool-1"))
            projection.appendLiveMessage("c1", userMessage("foreign", "peer", PUSHED_AT))
            runCurrent()
            assertEquals(listOf("foreign", "tool-1"), ids(thread.last()))
        }

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

    // ---- #1558: a queued own echo draws below the turn it waits behind ----

    // AC 1: while queued, the echo reads below every row of the running turn, including a delta and a tool
    // row that arrive after it was sent; the running segment stays one streaming row.
    @Test
    fun queuedOwnEcho_readsBelowTheRunningTurn_includingRowsThatArriveLater() =
        runTest {
            val projection = ThreadProjection()
            val queue = QueueProjection()
            val thread = collect(projection, "c1")
            projection.applyAssistantDelta(delta("turn-1", 0, "Waiting"))
            sendOwn(projection, "mine", "hello")
            projection.onQueueState(queue, 42L to "mine")
            projection.applyAssistantDelta(delta("turn-1", 1, " a minute"))
            runCurrent()

            assertEquals(listOf("turn-1", "mine"), ids(thread.last()))
            val running = (thread.last()[0] as ThreadItem.MessageItem).message
            assertEquals("Waiting a minute" to true, running.content to running.isStreaming)

            projection.applyToolUse(toolUse("turn-1", "tool-1"))
            projection.applyAssistantDelta(delta("turn-1", 2, "Done"))
            runCurrent()

            assertEquals(listOf("turn-1", "tool-1", "turn-1#2", "mine"), ids(thread.last()))
            assertEquals(true, (thread.last()[2] as ThreadItem.MessageItem).message.isStreaming)
        }

    @Test
    fun parkedOwnEcho_drainFirstMidNextReply_preservesOneSegmentAndOriginalEcho() =
        runTest { assertParkedEchoConfirmedMidReply(drainFirst = true) }

    @Test
    fun parkedOwnEcho_pushFirstMidNextReply_preservesOneSegmentAndOriginalEcho() =
        runTest { assertParkedEchoConfirmedMidReply(drainFirst = false) }

    private fun TestScope.assertParkedEchoConfirmedMidReply(drainFirst: Boolean) {
        val projection = ThreadProjection()
        val queue = QueueProjection()
        val thread = collect(projection, "c1")
        val sent = startQueuedTurn(projection, queue)
        projection.applyAssistantDelta(delta("turn-1", 1, "Done"))
        projection.finalizeAssistantTurn(LiveSessionEvent.TurnEnd("c1", "turn-1", "end_turn"))
        runCurrent()
        val firstTurn = thread.last().filterNot { it is ThreadItem.MessageItem && it.message.id == "mine" }
        assertEquals(listOf("turn-1", "tool-1", "turn-1#1", "mine"), ids(thread.last()))

        projection.applyAssistantDelta(delta("turn-2", 0, "Hello, "))
        projection.applyAssistantDelta(delta("turn-2", 1, "streamed "))
        runCurrent()
        // Until delivery the queued treatment remains below the running reply.
        assertEquals(listOf("turn-1", "tool-1", "turn-1#1", "turn-2", "mine"), ids(thread.last()))
        val pushed = userMessage("mine", "daemon copy", PUSHED_AT).copy(attachments = listOf(MessageAttachment(ATTACHMENT_ID)))
        if (drainFirst) projection.onQueueState(queue) else projection.appendLiveMessage("c1", pushed)
        runCurrent()
        val expectedIds = listOf("turn-1", "tool-1", "turn-1#1", "mine", "turn-2")
        assertEquals(expectedIds, ids(thread.last()))
        assertEquals(ThreadItem.MessageItem(sent), thread.last()[3])

        projection.applyAssistantDelta(delta("turn-2", 2, "world"))
        runCurrent()
        val delivered = thread.last()
        assertEquals(expectedIds, ids(delivered))
        assertEquals(firstTurn, delivered.take(3))
        assertEquals(ThreadItem.MessageItem(sent), delivered[3])
        val reply = (delivered.last() as ThreadItem.MessageItem).message
        assertEquals("Hello, streamed world", reply.content)
        assertEquals(listOf(0, 1, 2), reply.segment?.deltas?.map { it.seq })
        assertTrue(reply.isStreaming)

        if (drainFirst) projection.appendLiveMessage("c1", pushed) else projection.onQueueState(queue)
        projection.appendLiveMessage("c1", pushed)
        projection.onQueueState(queue, 43L to "mine")
        projection.onQueueState(queue)
        runCurrent()
        assertEquals(delivered, thread.last())
        assertNeverAboveTheTool(thread)
    }

    @Test
    fun parkedOwnEcho_historyEndBeforeLiveEndDoesNotConsumeTheReservationBoundary() =
        runTest {
            val projection = ThreadProjection()
            val queue = QueueProjection()
            val thread = collect(projection, "c1")
            startQueuedTurn(projection, queue)
            projection.mergeHistoryPage(
                "c1",
                HistoryPage(
                    entries =
                        listOf(
                            HistoryEntry(
                                1L,
                                "turn_end",
                                MobileJson.parseToJsonElement("""{"conversation_id":"c1","turn_id":"turn-1","stop_reason":"end_turn"}"""),
                                PUSHED_AT,
                            ),
                        ),
                    cursor = "",
                    atStart = true,
                ),
                interactive = true,
            )
            projection.finalizeAssistantTurn(LiveSessionEvent.TurnEnd("c1", "turn-1", "end_turn"))
            projection.applyAssistantDelta(delta("turn-2", 0, "Reply"))
            projection.appendLiveMessage("c1", userMessage("mine", "hello", PUSHED_AT))
            runCurrent()
            assertEquals(listOf("turn-1", "tool-1", "mine", "turn-2"), ids(thread.last()))
        }

    @Test
    fun parkedOwnEcho_repeatedEndDoesNotReserveAnEchoBehindANewerTurn() =
        runTest {
            val projection = ThreadProjection()
            val queue = QueueProjection()
            val thread = collect(projection, "c1")
            val oldEnd = LiveSessionEvent.TurnEnd("c1", "turn-0", "end_turn")
            projection.finalizeAssistantTurn(oldEnd)
            startQueuedTurn(projection, queue)
            projection.finalizeAssistantTurn(oldEnd)
            projection.applyToolUse(toolUse("turn-1", "tool-2"))
            projection.appendLiveMessage("c1", userMessage("mine", "hello", PUSHED_AT))
            runCurrent()
            assertEquals(listOf("turn-1", "tool-1", "tool-2", "mine"), ids(thread.last()))
        }

    @Test
    fun parkedOwnEcho_anotherConversationsEndDoesNotReserveItsSlot() =
        runTest {
            val projection = ThreadProjection()
            val queue = QueueProjection()
            val thread = collect(projection, "c1")
            startQueuedTurn(projection, queue)
            projection.finalizeAssistantTurn(LiveSessionEvent.TurnEnd("c-other", "turn-1", "end_turn"))
            projection.applyToolUse(toolUse("turn-1", "tool-2"))
            projection.appendLiveMessage("c1", userMessage("mine", "hello", PUSHED_AT))
            runCurrent()
            assertEquals(listOf("turn-1", "tool-1", "tool-2", "mine"), ids(thread.last()))
        }

    @Test
    fun parkedOwnEcho_fifoReservesEachEchoAfterItsOwnWaitingTurn() =
        runTest {
            val projection = ThreadProjection()
            val queue = QueueProjection()
            val thread = collect(projection, "c1")
            projection.applyAssistantDelta(delta("turn-1", 0, "Waiting"))
            val first = sendOwn(projection, "mine-1", "first")
            val second = sendOwn(projection, "mine-2", "second")
            projection.onQueueState(queue, 42L to "mine-1", 43L to "mine-2")
            projection.applyToolUse(toolUse("turn-1", "tool-1"))
            projection.finalizeAssistantTurn(LiveSessionEvent.TurnEnd("c1", "turn-1", "end_turn"))
            runCurrent()
            assertEquals(listOf("turn-1", "tool-1", "mine-1", "mine-2"), ids(thread.last()))
            projection.applyAssistantDelta(delta("turn-2", 0, "Reply"))
            runCurrent()
            assertEquals(listOf("turn-1", "tool-1", "turn-2", "mine-1", "mine-2"), ids(thread.last()))
            projection.appendLiveMessage("c1", userMessage("mine-1", "first", PUSHED_AT))
            projection.onQueueState(queue, 43L to "mine-2")
            projection.finalizeAssistantTurn(LiveSessionEvent.TurnEnd("c1", "turn-2", "end_turn"))
            projection.applyAssistantDelta(delta("turn-3", 0, "Second reply"))
            projection.onQueueState(queue)
            runCurrent()
            assertEquals(listOf("turn-1", "tool-1", "mine-1", "turn-2", "mine-2", "turn-3"), ids(thread.last()))
            assertEquals(ThreadItem.MessageItem(first), thread.last()[2])
            assertEquals(ThreadItem.MessageItem(second), thread.last()[4])
        }

    @Test
    fun parkedOwnEcho_foreignQueueHeadKeepsOwnEchoWaitingForTheFollowingTurn() =
        runTest {
            val projection = ThreadProjection()
            val queue = QueueProjection()
            val thread = collect(projection, "c1")
            startQueuedTurn(projection, queue)
            projection.onQueueState(queue, 41L to "peer", 42L to "mine")
            projection.finalizeAssistantTurn(LiveSessionEvent.TurnEnd("c1", "turn-1", "end_turn"))
            projection.appendLiveMessage("c1", userMessage("peer", "from desktop", PUSHED_AT))
            projection.applyAssistantDelta(delta("turn-2", 0, "Peer reply"))
            projection.onQueueState(queue, 42L to "mine")
            projection.finalizeAssistantTurn(LiveSessionEvent.TurnEnd("c1", "turn-2", "end_turn"))
            projection.applyAssistantDelta(delta("turn-3", 0, "Own reply"))
            projection.appendLiveMessage("c1", userMessage("mine", "hello", PUSHED_AT))
            runCurrent()
            assertEquals(listOf("turn-1", "tool-1", "peer", "turn-2", "mine", "turn-3"), ids(thread.last()))
        }

    @Test
    fun parkedOwnEcho_peerConsumptionSurvivesAnotherConversationsSnapshot() =
        runTest { assertPeerConsumptionSurvivesSnapshot(repeatOwnSnapshot = false) }

    @Test
    fun parkedOwnEcho_peerConsumptionSurvivesRepeatedSnapshot() =
        runTest { assertPeerConsumptionSurvivesSnapshot(repeatOwnSnapshot = true) }

    private fun TestScope.assertPeerConsumptionSurvivesSnapshot(repeatOwnSnapshot: Boolean) {
        val projection = ThreadProjection()
        val queue = QueueProjection()
        val thread = collect(projection, "c1")
        val original = startQueuedTurn(projection, queue)
        projection.onQueueState(queue, 41L to "peer", 42L to "mine")
        projection.finalizeAssistantTurn(LiveSessionEvent.TurnEnd("c1", "turn-1", "end_turn"))
        projection.appendLiveMessage("c1", userMessage("peer", "from desktop", PUSHED_AT))
        projection.applyAssistantDelta(delta("turn-2", 0, "Peer reply"))
        repeat(2) {
            if (repeatOwnSnapshot) {
                projection.onQueueState(queue, 41L to "peer", 42L to "mine")
            } else {
                queue.apply(
                    Envelope(
                        id = 2L,
                        type = "queue_state",
                        ts = RISE,
                        payload = MobileJson.parseToJsonElement("""{"conversation_id":"c2","queued":[]}"""),
                    ),
                )
                projection.settleDrops(queue)
                projection.settleQueuedEchoes(queue) { true }
            }
        }
        projection.finalizeAssistantTurn(LiveSessionEvent.TurnEnd("c1", "turn-2", "end_turn"))
        projection.applyAssistantDelta(delta("turn-3", 0, "Hello, "))
        projection.applyAssistantDelta(delta("turn-3", 1, "streamed "))
        projection.appendLiveMessage("c1", userMessage("mine", "daemon copy", PUSHED_AT))
        projection.applyAssistantDelta(delta("turn-3", 2, "world"))
        runCurrent()
        assertEquals(listOf("turn-1", "tool-1", "peer", "turn-2", "mine", "turn-3"), ids(thread.last()))
        assertEquals(ThreadItem.MessageItem(original), thread.last()[4])
        assertEquals("Hello, streamed world", (thread.last().last() as ThreadItem.MessageItem).message.content)
    }

    @Test
    fun parkedOwnEcho_toolOnlyOrFailedTurn_reservesAfterAllEndingRows() =
        runTest {
            for (failed in listOf(false, true)) {
                val projection = ThreadProjection()
                val queue = QueueProjection()
                val thread = collect(projection, "c1")
                val sent = sendOwn(projection, "mine", "hello")
                projection.onQueueState(queue, 42L to "mine")
                projection.applyToolUse(toolUse("turn-1", "tool-1"))
                projection.finalizeAssistantTurn(LiveSessionEvent.TurnEnd("c1", "turn-1", "end_turn", isError = failed))
                projection.applyAssistantDelta(delta("turn-2", 0, "Reply"))
                projection.appendLiveMessage("c1", userMessage("mine", "daemon copy", PUSHED_AT))
                runCurrent()
                val rows = thread.last()
                assertEquals("tool-1", (rows.first() as ThreadItem.MessageItem).message.id)
                if (failed) assertTrue(rows[1] is ThreadItem.StoppedTurn)
                assertEquals(ThreadItem.MessageItem(sent), rows[rows.lastIndex - 1])
                assertEquals("turn-2", (rows.last() as ThreadItem.MessageItem).message.id)
                assertEquals(if (failed) 4 else 3, rows.size)
            }
        }

    @Test
    fun ordinaryDrain_delayedUserPushAfterReplyStarts_doesNotRelocateOrSplitReply() =
        runTest {
            val projection = ThreadProjection()
            val queue = QueueProjection()
            val thread = collect(projection, "c1")
            val sent = startQueuedTurn(projection, queue)

            projection.finalizeAssistantTurn(LiveSessionEvent.TurnEnd("c1", "turn-1", "end_turn"))
            projection.onQueueState(queue, turnOpen = false)
            runCurrent()
            assertEquals(listOf("turn-1", "tool-1", "mine"), ids(thread.last()))
            projection.applyAssistantDelta(delta("turn-2", 0, "Reply"))
            runCurrent()
            assertEquals(listOf("turn-1", "tool-1", "mine", "turn-2"), ids(thread.last()))
            projection.appendLiveMessage("c1", userMessage("mine", "hello", PUSHED_AT))
            projection.applyAssistantDelta(delta("turn-2", 1, " continues"))
            runCurrent()

            assertEquals(listOf("turn-1", "tool-1", "mine", "turn-2"), ids(thread.last()))
            assertEquals(ThreadItem.MessageItem(sent), thread.last()[2])
            assertNeverAboveTheTool(thread)
        }

    @Test
    fun peerSendNow_delayedUserPushAfterReplyStarts_usesReportedPosition() =
        runTest {
            val projection = ThreadProjection()
            val queue = QueueProjection()
            val thread = collect(projection, "c1")
            startQueuedTurn(projection, queue)
            projection.finalizeAssistantTurn(LiveSessionEvent.TurnEnd("c1", "turn-1", "end_turn"))
            projection.onQueueState(queue, turnOpen = false)
            projection.applyAssistantDelta(delta("turn-2", 0, "Before input"))
            projection.appendLiveMessage("c1", userMessage("mine", "hello", PUSHED_AT), sentNow = true)
            projection.applyAssistantDelta(delta("turn-2", 1, "After input"))
            projection.appendLiveMessage("c1", userMessage("mine", "hello", PUSHED_AT), sentNow = true)
            runCurrent()
            assertEquals(listOf("turn-1", "tool-1", "turn-2", "mine", "turn-2#1"), ids(thread.last()))
        }

    // AC 2: the drain's queue_state first, then the pushed message: the echo settles after the turn's last
    // row, unchanged, the reply follows it, and no emission puts it back above the turn's tool row.
    @Test
    fun queuedOwnEcho_drainThenPushedMessage_settlesAfterTheTurnUnchanged() =
        runTest {
            val projection = ThreadProjection()
            val queue = QueueProjection()
            val thread = collect(projection, "c1")
            val sent = startQueuedTurn(projection, queue)

            projection.finalizeAssistantTurn(LiveSessionEvent.TurnEnd("c1", "turn-1", "end_turn"))
            projection.onQueueState(queue, turnOpen = false)
            runCurrent()
            assertEquals(listOf("turn-1", "tool-1", "mine"), ids(thread.last()))
            projection.appendLiveMessage("c1", userMessage("mine", "hello", PUSHED_AT))
            projection.applyAssistantDelta(delta("turn-2", 0, "Reply"))
            runCurrent()

            assertEquals(listOf("turn-1", "tool-1", "mine", "turn-2"), ids(thread.last()))
            assertEquals(ThreadItem.MessageItem(sent), thread.last()[2])
            assertNeverAboveTheTool(thread)
        }

    // AC 2: the pushed message first, then the drain's queue_state: the same settled order.
    @Test
    fun queuedOwnEcho_pushedMessageThenDrain_settlesAfterTheTurnUnchanged() =
        runTest {
            val projection = ThreadProjection()
            val queue = QueueProjection()
            val thread = collect(projection, "c1")
            val sent = startQueuedTurn(projection, queue)

            projection.finalizeAssistantTurn(LiveSessionEvent.TurnEnd("c1", "turn-1", "end_turn"))
            projection.appendLiveMessage("c1", userMessage("mine", "hello", PUSHED_AT))
            runCurrent()
            projection.applyAssistantDelta(delta("turn-2", 0, "Reply"))
            projection.onQueueState(queue, turnOpen = false)
            runCurrent()

            assertEquals(listOf("turn-1", "tool-1", "mine", "turn-2"), ids(thread.last()))
            assertEquals(ThreadItem.MessageItem(sent), thread.last()[2])
            assertNeverAboveTheTool(thread)
        }

    // AC 2: a later snapshot repeating a delivered id (a legal duplicate message_id) never parks it again.
    @Test
    fun deliveredOwnEcho_laterSnapshotRepeatingItsId_staysAboveItsReply() =
        runTest {
            val projection = ThreadProjection()
            val queue = QueueProjection()
            val thread = collect(projection, "c1")
            startQueuedTurn(projection, queue)
            projection.finalizeAssistantTurn(LiveSessionEvent.TurnEnd("c1", "turn-1", "end_turn"))
            projection.onQueueState(queue, turnOpen = false)
            projection.applyAssistantDelta(delta("turn-2", 0, "Reply"))

            projection.onQueueState(queue, 43L to "mine")
            runCurrent()

            assertEquals(listOf("turn-1", "tool-1", "mine", "turn-2"), ids(thread.last()))
        }

    // #1636: an echo queued while no turn runs waits behind nothing. Its delivery confirmation can reach the
    // phone after its own reply began (the daemon confirms only once its write returns), and between two
    // deltas it must not move the echo below the reply's start and split "Hello, streamed world" in two.
    @Test
    fun ownEchoQueuedWhileIdle_deliveredMidReply_staysAboveOneReplySegment() =
        runTest {
            val deliveries =
                listOf<ThreadProjection.(QueueProjection) -> Unit>(
                    { queue -> onQueueState(queue, turnOpen = true) },
                    { appendLiveMessage("c1", userMessage("mine", "hello", PUSHED_AT)) },
                )
            for (deliver in deliveries) {
                val projection = ThreadProjection()
                val queue = QueueProjection()
                val thread = collect(projection, "c1")
                sendOwn(projection, "mine", "hello")
                projection.onQueueState(queue, 42L to "mine", turnOpen = false)
                projection.applyAssistantDelta(delta("turn-1", 0, "Hello, "))
                projection.applyAssistantDelta(delta("turn-1", 1, "streamed "))
                projection.deliver(queue)
                projection.applyAssistantDelta(delta("turn-1", 2, "world"))
                runCurrent()

                assertEquals(listOf("mine", "turn-1"), ids(thread.last()))
                assertEquals("Hello, streamed world", (thread.last()[1] as ThreadItem.MessageItem).message.content)
            }
        }

    @Test
    fun idleSendNow_removalBeforePush_staysAboveOneReplySegment() =
        runTest {
            val projection = ThreadProjection()
            val queue = QueueProjection()
            val thread = collect(projection, "c1")
            val sent = sendOwn(projection, "mine", "hello")
            projection.onQueueState(queue, 42L to "mine", turnOpen = false)
            projection.recordSendNow("c1", "mine")
            projection.applyAssistantDelta(delta("turn-1", 0, "Hello, "))
            projection.applyAssistantDelta(delta("turn-1", 1, "streamed "))
            projection.onQueueState(queue, turnOpen = true)
            runCurrent()
            assertEquals(listOf("mine", "turn-1"), ids(thread.last()))
            projection.appendLiveMessage("c1", userMessage("mine", "hello", PUSHED_AT))
            projection.applyAssistantDelta(delta("turn-1", 2, "world"))
            runCurrent()
            assertEquals(listOf("mine", "turn-1"), ids(thread.last()))
            assertEquals(ThreadItem.MessageItem(sent), thread.last()[0])
            assertEquals("Hello, streamed world", (thread.last()[1] as ThreadItem.MessageItem).message.content)
        }

    @Test
    fun idleSendNow_pushBeforeRemoval_staysAboveOneReplySegment() =
        runTest {
            val projection = ThreadProjection()
            val queue = QueueProjection()
            val thread = collect(projection, "c1")
            val sent = sendOwn(projection, "mine", "hello")
            projection.onQueueState(queue, 42L to "mine", turnOpen = false)
            projection.recordSendNow("c1", "mine")
            projection.applyAssistantDelta(delta("turn-1", 0, "Hello, "))
            projection.applyAssistantDelta(delta("turn-1", 1, "streamed "))
            projection.appendLiveMessage("c1", userMessage("mine", "hello", PUSHED_AT))
            runCurrent()
            assertEquals(listOf("mine", "turn-1"), ids(thread.last()))
            projection.onQueueState(queue, turnOpen = true)
            projection.applyAssistantDelta(delta("turn-1", 2, "world"))
            runCurrent()
            assertEquals(listOf("mine", "turn-1"), ids(thread.last()))
            assertEquals(ThreadItem.MessageItem(sent), thread.last()[0])
            assertEquals("Hello, streamed world", (thread.last()[1] as ThreadItem.MessageItem).message.content)
        }

    // AC 4: a queued id this device did not mint never moves the row carrying it, queued or delivered.
    @Test
    fun queuedForeignId_neverMovesTheRow() =
        runTest {
            val projection = ThreadProjection()
            val queue = QueueProjection()
            val thread = collect(projection, "c1")
            projection.appendMessages(listOf("c1" to userMessage("peer-1", "from desktop", SENT_AT)))
            projection.applyToolUse(toolUse("turn-1", "tool-1"))
            projection.onQueueState(queue, 42L to "peer-1")
            runCurrent()
            assertEquals(listOf("peer-1", "tool-1"), ids(thread.last()))

            projection.finalizeAssistantTurn(LiveSessionEvent.TurnEnd("c1", "turn-1", "end_turn"))
            projection.onQueueState(queue)
            projection.appendLiveMessage("c1", userMessage("peer-1", "from desktop", PUSHED_AT))
            runCurrent()

            assertEquals(listOf("peer-1", "tool-1"), ids(thread.last()))
        }

    // AC 4: a minted id whose held row is not a user row is never moved or changed, queued or delivered.
    @Test
    fun queuedMintedIdOnANonUserRow_neverMovesOrChangesIt() =
        runTest {
            val projection = ThreadProjection()
            val queue = QueueProjection()
            val thread = collect(projection, "c1")
            projection.applyToolUse(toolUse("turn-1", "tool-1"))
            projection.recordMinted("c1", "tool-1")
            projection.appendMessages(listOf("c1" to userMessage("m1", "after", SENT_AT)))
            runCurrent()
            val before = thread.last()

            projection.onQueueState(queue, 42L to "tool-1")
            runCurrent()
            assertEquals(before, thread.last())

            projection.finalizeAssistantTurn(LiveSessionEvent.TurnEnd("c1", "turn-1", "end_turn"))
            projection.appendLiveMessage("c1", userMessage("tool-1", "collides", PUSHED_AT))
            projection.onQueueState(queue)
            runCurrent()
            assertEquals(before, thread.last())
        }

    // AC 3: a queued own echo this device drops (#859) is removed when the drop settles, not moved.
    @Test
    fun droppedOwnEcho_isRemovedNotMoved() =
        runTest {
            val projection = ThreadProjection()
            val queue = QueueProjection()
            val thread = collect(projection, "c1")
            startQueuedTurn(projection, queue)

            projection.finalizeAssistantTurn(LiveSessionEvent.TurnEnd("c1", "turn-1", "end_turn"))
            projection.recordDrop("c1", 42L, "mine")
            projection.onQueueState(queue)
            runCurrent()

            assertEquals(listOf("turn-1", "tool-1"), ids(thread.last()))
        }

    @Test
    fun droppedOwnEcho_lateDeliveredPushRendersOnceAtDaemonPosition() =
        runTest {
            val projection = ThreadProjection()
            val queue = QueueProjection()
            val thread = collect(projection, "c1")
            startQueuedTurn(projection, queue)
            projection.recordDrop("c1", 42L, "mine")
            projection.onQueueState(queue)
            runCurrent()
            assertEquals(listOf("turn-1", "tool-1"), ids(thread.last()))
            projection.applyToolUse(toolUse("turn-1", "tool-2"))
            // Delivery may win the drop race; follow the daemon's push after the local id was spent.
            projection.appendLiveMessage("c1", userMessage("mine", "hello", PUSHED_AT))
            projection.appendLiveMessage("c1", userMessage("mine", "hello", PUSHED_AT))
            runCurrent()
            assertEquals(listOf("turn-1", "tool-1", "tool-2", "mine"), ids(thread.last()))
        }

    // #1356 AC #1: a live failed turn_end leaves one stopped row after the turn's last row, and it stays
    // there once the next turn starts; a duplicate turn_end and a cancelled one add nothing.
    @Test
    fun finalizeAssistantTurn_failedTurn_appendsOneStoppedRowThatOutlivesTheNextTurn() =
        runTest {
            val projection = ThreadProjection()
            val rows = collect(projection, "c1")
            runCurrent()

            projection.applyAssistantDelta(LiveSessionEvent.AssistantDelta("c1", "turn-1", 0, "partial"))
            val failed = LiveSessionEvent.TurnEnd("c1", "turn-1", "end_turn", isError = true, terminalReason = "max_turns")
            projection.finalizeAssistantTurn(failed)
            projection.finalizeAssistantTurn(failed)
            projection.finalizeAssistantTurn(LiveSessionEvent.TurnEnd("c1", "turn-0", "cancelled", isError = true))
            projection.applyAssistantDelta(LiveSessionEvent.AssistantDelta("c1", "turn-2", 0, "next"))
            runCurrent()

            val thread = rows.last()
            assertEquals(3, thread.size)
            assertEquals("turn-1", (thread[0] as ThreadItem.MessageItem).message.id)
            val stopped = thread[1] as ThreadItem.StoppedTurn
            assertEquals("turn-1" to "max_turns", stopped.turnId to stopped.reason)
            assertEquals("turn-2", (thread[2] as ThreadItem.MessageItem).message.id)
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
            assertEquals(listOf(divider(FALL), counted(BOUNDARY)), thread.last())

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

    /** What `MessageCommands.sendMessage` does to the projection: draw the echo, then record its id as minted. */
    private fun sendOwn(
        projection: ThreadProjection,
        id: String,
        text: String,
    ): Message {
        val sent =
            userMessage(id, text, SENT_AT)
                .copy(attachments = listOf(MessageAttachment(ATTACHMENT_ID, "photo.jpg", "image/jpeg")))
        projection.appendMessages(listOf("c1" to sent))
        projection.recordMinted("c1", id)
        return sent
    }

    /**
     * turn-1 streams, "mine" is sent and queued behind it, and turn-1 then runs a tool: the store holds the
     * echo between the turn's text and its tool row, which is the tap-time slot the read must never show.
     */
    private fun startQueuedTurn(
        projection: ThreadProjection,
        queue: QueueProjection,
    ): Message {
        projection.applyAssistantDelta(delta("turn-1", 0, "Waiting"))
        val sent = sendOwn(projection, "mine", "hello")
        projection.onQueueState(queue, 42L to "mine")
        projection.applyToolUse(toolUse("turn-1", "tool-1"))
        return sent
    }

    /**
     * What the repository's `queue_state` arm does: apply the snapshot of `c1`, then settle against it, with
     * [turnOpen] as the turn phase. Open by default, the #1558 scenario of an echo waiting behind a turn.
     */
    private fun ThreadProjection.onQueueState(
        queue: QueueProjection,
        vararg items: Pair<Long, String>,
        turnOpen: Boolean = true,
    ) {
        val queued =
            items.joinToString(",") { (queuedMsgId, messageId) ->
                """{"queued_msg_id":$queuedMsgId,"message_id":"$messageId","text":"t","ts":"$RISE"}"""
            }
        queue.apply(
            Envelope(
                id = 1L,
                type = "queue_state",
                ts = RISE,
                payload = MobileJson.parseToJsonElement("""{"conversation_id":"c1","queued":[$queued]}"""),
            ),
        )
        settleDrops(queue)
        settleQueuedEchoes(queue) { turnOpen }
    }

    /** No emission holding both draws the echo "mine" above turn-1's tool row, its tap-time slot. */
    private fun assertNeverAboveTheTool(emissions: List<List<ThreadItem>>) {
        for (rows in emissions.map(::ids)) {
            if ("mine" in rows &&
                "tool-1" in rows
            ) {
                assertTrue("echo above the tool row: $rows", rows.indexOf("mine") > rows.indexOf("tool-1"))
            }
        }
    }

    private fun ids(rows: List<ThreadItem>): List<String> = rows.map { (it as ThreadItem.MessageItem).message.id }

    private fun delta(
        turnId: String,
        seq: Int,
        text: String,
    ) = LiveSessionEvent.AssistantDelta("c1", turnId, seq, text)

    private fun toolUse(
        turnId: String,
        toolUseId: String,
    ) = LiveSessionEvent.ToolUse("c1", turnId, toolUseId, name = "Bash", inputSummary = "sleep 60")

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
