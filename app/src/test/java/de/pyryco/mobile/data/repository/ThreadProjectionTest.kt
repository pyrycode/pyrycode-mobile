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
