package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.cache.cacheableThreadRows
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.BackgroundTaskStartedPayloadDto
import de.pyryco.mobile.data.network.BackgroundTaskUpdatedPayloadDto
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.repository.BackgroundTaskProjectionTest.Companion.envelope
import de.pyryco.mobile.data.repository.BackgroundTaskProjectionTest.Companion.midLife
import de.pyryco.mobile.data.repository.BackgroundTaskProjectionTest.Companion.started
import de.pyryco.mobile.data.repository.BackgroundTaskProjectionTest.Companion.terminal
import de.pyryco.mobile.ui.conversations.thread.ThreadRow
import de.pyryco.mobile.ui.conversations.thread.foldQueuedRows
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundTaskLifecycleTest {
    private val first = Instant.parse("2026-05-08T10:33:18Z")
    private val last = Instant.parse("2026-05-08T10:34:18Z")

    private fun entry(
        frame: Envelope,
        id: Long,
        at: Instant = first,
    ) = HistoryEntry(id, frame.type, frame.payload, at)

    private fun reduce(vararg chronological: HistoryEntry): List<ThreadItem> =
        reduceHistoryPage(chronological.reversed(), interactive = true)

    private fun List<ThreadItem>.markers() = filterIsInstance<ThreadItem.BackgroundTaskLifecycle>()

    private fun List<ThreadItem>.keys(): List<String> =
        map { row ->
            when (row) {
                is ThreadItem.BackgroundTaskLifecycle -> "${row.taskId}:${row.terminal != null}"
                is ThreadItem.MessageItem -> row.message.id
                else -> error("unexpected row")
            }
        }

    private fun message(id: String) = ThreadItem.MessageItem(Message(id, "s1", Role.User, "hello", first, isStreaming = false))

    private fun startedDto(frame: Envelope = started("t1")) =
        MobileJson.decodeFromJsonElement<BackgroundTaskStartedPayloadDto>(frame.payload)

    private fun updatedDto(frame: Envelope = terminal("t1", "completed")) =
        MobileJson.decodeFromJsonElement<BackgroundTaskUpdatedPayloadDto>(frame.payload)

    @Test
    fun ordinaryBackfillOverlap_anchorsNewLifecyclePositionsAndSurvivesReplayAndPrepend() =
        runTest {
            val projection = ThreadProjection()
            projection.appendMessages(listOf("c1" to message("m1").message, "c1" to message("m2").message))
            val chronological =
                listOf(
                    entry(envelope("message", """{"conversation_id":"c1","message_id":"m1","role":"user","text":"before"}"""), 1),
                    entry(started("t1"), 2),
                    entry(envelope("message", """{"conversation_id":"c1","message_id":"m2","role":"user","text":"between"}"""), 3),
                    entry(terminal("t1", "completed"), 4, last),
                )
            val page = HistoryPage(chronological.reversed(), "older", false)
            projection.mergeHistoryPage("c1", page, true)
            val merged = projection.observe("c1").first()
            assertEquals(listOf("m1", "t1:false", "m2", "t1:true"), merged.keys())
            assertEquals(listOf(message("m1"), message("m2")), merged.filterIsInstance<ThreadItem.MessageItem>())

            projection.applyBackgroundTaskLifecycle(started("t1"))
            projection.applyBackgroundTaskLifecycle(terminal("t1", "failed"))
            projection.mergeHistoryPage("c1", page, true)
            assertEquals(merged, projection.observe("c1").first())
            val prepended = merged.mergeHistoryRows(listOf(message("m0"), message("m1")))
            assertEquals(listOf("m0") + merged.keys(), prepended.keys())
            assertEquals(merged.markers(), prepended.markers())
        }

    @Test
    fun overlapWithLiveFinish_insertsOnlyMissingLaunchAndRetainsFinishPosition() {
        val live =
            listOf<ThreadItem>(message("m1"), message("m2"))
                .withBackgroundTaskUpdated(updatedDto(), last) + message("m3")
        val launch = emptyList<ThreadItem>().withBackgroundTaskStarted(startedDto(), first).single()
        val finish = emptyList<ThreadItem>().withBackgroundTaskUpdated(updatedDto(), first).single()
        val page = listOf(message("m0"), message("m1"), launch, message("m2"), finish, message("m3"))
        val merged = live.mergeHistoryRows(page)
        assertEquals(listOf("m0", "m1", "t1:false", "m2", "t1:true", "m3"), merged.keys())
        assertEquals(last, merged.markers().last().occurredAt)
        assertEquals("toolu_t1", merged.markers().last().toolCallId)
        assertEquals(merged, merged.mergeHistoryRows(page))
        assertEquals(merged.markers(), merged.mergeHistoryRows(listOf(message("older"))).markers())
    }

    @Test
    fun overlappingPage_preservesLifecycleOrderAtBothEndsAndBetweenSharedAnchors() {
        val launch1 = emptyList<ThreadItem>().withBackgroundTaskStarted(startedDto(), first).single()
        val finish1 = emptyList<ThreadItem>().withBackgroundTaskUpdated(updatedDto(), last).single()
        val launch2 = emptyList<ThreadItem>().withBackgroundTaskStarted(startedDto(started("t2")), first).single()
        val finish2 = emptyList<ThreadItem>().withBackgroundTaskUpdated(updatedDto(terminal("t2", "completed")), last).single()
        val ordinary = listOf<ThreadItem>(message("m1"), message("m2"))
        val page = listOf(launch1, message("m1"), launch2, finish1, message("m2"), finish2)
        val merged = ordinary.mergeHistoryRows(page)
        assertEquals(listOf("t1:false", "m1", "t2:false", "t1:true", "m2", "t2:true"), merged.keys())
        assertEquals(ordinary, merged.filterIsInstance<ThreadItem.MessageItem>())
        assertEquals(merged, merged.mergeHistoryRows(page))
    }

    @Test
    fun pageStartingInsideLoadedThread_usesFollowingAnchorForLeadingEvidence() {
        val launch = emptyList<ThreadItem>().withBackgroundTaskStarted(startedDto(), first).single()
        val finish = emptyList<ThreadItem>().withBackgroundTaskUpdated(updatedDto(), last).single()
        val ordinary = listOf<ThreadItem>(message("older"), message("m1"), message("m2"))
        val page = listOf(launch, message("m1"), finish, message("m2"))
        val merged = ordinary.mergeHistoryRows(page)
        assertEquals(listOf("older", "t1:false", "m1", "t1:true", "m2"), merged.keys())
        assertEquals(merged, merged.mergeHistoryRows(page))
    }

    @Test
    fun freshMessageBetweenRetainedAnchors_cannotMoveFinishBeforeLaunch() {
        val launch = emptyList<ThreadItem>().withBackgroundTaskStarted(startedDto(), first).single()
        val finish = emptyList<ThreadItem>().withBackgroundTaskUpdated(updatedDto(), last).single()
        val held = listOf(message("m1"), launch, message("m3"))
        val page = listOf(message("m1"), launch, message("m2"), finish, message("m3"))
        val merged = held.mergeHistoryRows(page)
        assertEquals(listOf("m2", "m1", "t1:false", "t1:true", "m3"), merged.keys())
        assertEquals(listOf(message("m2"), message("m1"), message("m3")), merged.filterIsInstance<ThreadItem.MessageItem>())
        assertEquals(merged, merged.mergeHistoryRows(page))
        assertEquals(merged, merged.withBackgroundTaskStarted(startedDto(), last).withBackgroundTaskUpdated(updatedDto(), first))
        val prepended = merged.mergeHistoryRows(listOf(message("older")))
        assertEquals(listOf("older") + merged.keys(), prepended.keys())
        assertEquals(merged.markers(), prepended.markers())
    }

    @Test
    fun fullyOverlappingAssistantWithDifferentId_anchorsHistoryFinishAfterText() {
        assertAssistantOverlapPosition(cached = false, heldLaunch = false)
        assertAssistantOverlapPosition(cached = false, heldLaunch = true)
    }

    @Test
    fun fullyOverlappingAssistantWithDifferentId_anchorsCachedFinishAfterText() {
        assertAssistantOverlapPosition(cached = true, heldLaunch = false)
        assertAssistantOverlapPosition(cached = true, heldLaunch = true)
    }

    @Test
    fun leadingHistoryLifecycle_anchorsBeforeFirstOverlappingAssistantSegment() {
        val delta = LiveSessionEvent.AssistantDelta("c1", "turn", 0, "hello")
        val text = emptyList<ThreadItem>().withAssistantDelta(delta, first).withAssistantDelta(delta.copy(seq = 1, text = " world"), last)
        val held = listOf(message("older")) + text
        val page =
            emptyList<ThreadItem>()
                .withBackgroundTaskStarted(startedDto(), first)
                .withAssistantDelta(delta.copy(seq = 1, text = " world"), last)
                .withBackgroundTaskUpdated(updatedDto(), last)
        val merged = held.mergeHistoryRows(page)
        assertEquals(listOf("older", "t1:false", "turn", "t1:true"), merged.keys())
        assertEquals(held, merged.filterIsInstance<ThreadItem.MessageItem>())
        assertEquals(merged, merged.mergeHistoryRows(page))
    }

    private fun assertAssistantOverlapPosition(
        cached: Boolean,
        heldLaunch: Boolean,
    ) {
        val delta = LiveSessionEvent.AssistantDelta("c1", "turn", 0, "hello")
        val text = emptyList<ThreadItem>().withAssistantDelta(delta, first).withAssistantDelta(delta.copy(seq = 1, text = " world"), last)
        val launch = emptyList<ThreadItem>().withBackgroundTaskStarted(startedDto(), first).single()
        val held = if (heldLaunch) listOf(launch) + text else text
        val tail =
            emptyList<ThreadItem>()
                .withAssistantDelta(delta.copy(seq = 1, text = " world"), last)
                .withBackgroundTaskUpdated(updatedDto(), last)
        val incoming = if (heldLaunch) listOf(launch) + tail else tail
        val merged = if (cached) held.mergeCachedRows(incoming) else held.mergeHistoryRows(incoming)
        val expected = if (heldLaunch) listOf("t1:false", "turn", "t1:true") else listOf("turn", "t1:true")
        assertEquals(expected, merged.keys())
        assertEquals(text, merged.filterIsInstance<ThreadItem.MessageItem>())
        assertEquals(merged, if (cached) merged.mergeCachedRows(incoming) else merged.mergeHistoryRows(incoming))
        assertEquals(merged, merged.withBackgroundTaskUpdated(updatedDto(), first))
        assertEquals(listOf("older") + expected, merged.mergeHistoryRows(listOf(message("older"))).keys())
    }

    @Test
    fun historyAndCacheAssistantOverlapAcrossHeldSegments_anchorFinishAfterLastOverlappingSegment() {
        val delta = LiveSessionEvent.AssistantDelta("c1", "turn", 0, "hello")
        val held =
            emptyList<ThreadItem>()
                .withBackgroundTaskStarted(startedDto(), first)
                .withAssistantDelta(delta, first) + message("middle")
        val split = held.withAssistantDelta(delta.copy(seq = 1, text = " world"), last)
        val incoming =
            emptyList<ThreadItem>()
                .withAssistantDelta(delta, first)
                .withAssistantDelta(delta.copy(seq = 1, text = " world"), last)
                .withBackgroundTaskUpdated(updatedDto(), last)
        for (cached in listOf(false, true)) {
            val merged = if (cached) split.mergeCachedRows(incoming) else split.mergeHistoryRows(incoming)
            assertEquals(listOf("t1:false", "turn", "middle", "turn#1", "t1:true"), merged.keys())
            assertEquals(split.filterIsInstance<ThreadItem.MessageItem>(), merged.filterIsInstance<ThreadItem.MessageItem>())
            assertEquals(merged, if (cached) merged.mergeCachedRows(incoming) else merged.mergeHistoryRows(incoming))
        }
    }

    @Test
    fun partiallyOverlappingCachedAssistant_anchorsFinishAfterRetainedSuffix() {
        val delta = LiveSessionEvent.AssistantDelta("c1", "turn", 0, "hello")
        val held =
            emptyList<ThreadItem>()
                .withBackgroundTaskStarted(startedDto(), first)
                .withAssistantDelta(delta.copy(seq = 1, text = " world"), last)
        val cached =
            emptyList<ThreadItem>()
                .withBackgroundTaskStarted(startedDto(), first)
                .withAssistantDelta(delta, first)
                .withAssistantDelta(delta.copy(seq = 1, text = " world"), last)
                .withBackgroundTaskUpdated(updatedDto(), last)
        val merged = held.mergeCachedRows(cached)
        assertEquals(listOf("t1:false", "turn", "t1:true"), merged.keys())
        val message = merged.filterIsInstance<ThreadItem.MessageItem>().single().message
        assertEquals("hello world", message.content)
        assertEquals(listOf(0, 1), message.segment?.deltas?.map { it.seq })
        assertEquals(merged, merged.mergeCachedRows(cached))
    }

    @Test
    fun reconnectMemoryMerge_completesLaunchJoinWithoutMovingCachedFinish() {
        val retained = listOf<ThreadItem>(message("m1")).withBackgroundTaskUpdated(updatedDto(), last) + message("m2")
        val reloaded = listOf<ThreadItem>(message("m1")).withBackgroundTaskStarted(startedDto(), first) + message("m2")
        val merged = reloaded.mergeCachedRows(retained)
        assertEquals(listOf("m1", "t1:true", "t1:false", "m2"), merged.keys())
        assertEquals(last, merged.markers().first().occurredAt)
        assertEquals("toolu_t1", merged.markers().first().toolCallId)
    }

    @Test
    fun newestFirstHistory_retainsLaunchAndFinishAmongOrdinaryEntries() {
        val rows =
            reduce(
                entry(envelope("message", """{"conversation_id":"c1","message_id":"m1","role":"user","text":"before"}"""), 1),
                entry(started("t1"), 2),
                entry(envelope("message", """{"conversation_id":"c1","message_id":"m2","role":"user","text":"between"}"""), 3),
                entry(terminal("t1", "completed", summary = "<script>inert</script>"), 4, last),
            )
        assertEquals(listOf("m1", "t1:false", "m2", "t1:true"), rows.keys())
        val finish = rows.markers().last()
        assertEquals(last, finish.occurredAt)
        assertEquals("toolu_t1", finish.toolCallId)
        assertEquals("sleep 300", finish.description)
        assertEquals("local_bash", finish.taskType)
        assertEquals("<script>inert</script>", finish.terminal?.summary)
    }

    @Test
    fun terminalBeforeStart_backfillsWithoutMovingTheFinish() {
        val initial = listOf<ThreadItem>(message("m1")).withBackgroundTaskUpdated(updatedDto(), last) + message("m2")
        val rows = initial.withBackgroundTaskStarted(startedDto(), first)
        assertEquals(listOf("m1", "t1:true", "m2", "t1:false"), rows.keys())
        assertNull(initial.markers().single().toolCallId)
        val finish = rows.markers().first()
        assertEquals(last, finish.occurredAt)
        assertEquals("toolu_t1", finish.toolCallId)
        assertEquals("sleep 300", finish.description)
        val lateAgent = LiveSessionEvent.ToolUse("c1", "turn1", "toolu_t1", "Agent", "work", parentToolUseId = "parent")
        val withTool = rows.withToolUse(lateAgent, last)
        assertEquals(rows.markers(), withTool.markers())
        assertEquals(
            finish.toolCallId,
            withTool
                .filterIsInstance<ThreadItem.MessageItem>()
                .last()
                .message.id,
        )
        assertEquals(
            "parent",
            withTool
                .filterIsInstance<ThreadItem.MessageItem>()
                .last()
                .message.toolCall
                ?.parentToolUseId,
        )
    }

    @Test
    fun olderStartPage_completesAlreadyHeldFinishAndKeepsExistingPlacement() {
        val current = listOf<ThreadItem>(message("m2")).withBackgroundTaskUpdated(updatedDto(), last) + message("m3")
        val older = reduce(entry(started("t1"), 1))
        val merged = current.mergeHistoryRows(listOf(message("m1")) + older)
        assertEquals(listOf("m1", "t1:false", "m2", "t1:true", "m3"), merged.keys())
        assertEquals(current.markers().single().occurredAt, merged.markers().last().occurredAt)
        assertEquals("toolu_t1", merged.markers().last().toolCallId)
        val prepended = merged.mergeHistoryRows(listOf(message("m0")))
        assertEquals(merged.keys(), prepended.drop(1).keys())
        assertEquals(merged.markers(), prepended.markers())
    }

    @Test
    fun pageAndLiveReplayOverlap_retainsOnePositionOfEachKindAndFirstTerminalContent() {
        val live =
            emptyList<ThreadItem>()
                .withBackgroundTaskStarted(startedDto(), first)
                .withBackgroundTaskUpdated(updatedDto(), last)
        val page = reduce(entry(started("t1"), 1), entry(terminal("t1", "failed", summary = "old"), 2, first))
        val merged = live.mergeHistoryRows(page).mergeHistoryRows(page)
        assertEquals(live, merged)
        assertEquals(listOf("t1:false", "t1:true"), merged.keys())
        assertEquals(
            "completed",
            merged
                .markers()
                .last()
                .terminal
                ?.status,
        )
        assertEquals(merged, merged.withBackgroundTaskStarted(startedDto(), last).withBackgroundTaskUpdated(updatedDto(), first))
    }

    @Test
    fun overlapWithUnknownLiveJoin_fillsItFromHistoryWithoutChangingIdentityOrPosition() {
        val live =
            emptyList<ThreadItem>()
                .withBackgroundTaskStarted(startedDto().copy(toolCallId = ""), last)
                .withBackgroundTaskUpdated(updatedDto(), last)
        val page = reduce(entry(started("t1"), 1), entry(terminal("t1", "completed"), 2))
        val merged = live.mergeHistoryRows(page)
        assertEquals(live.keys(), merged.keys())
        assertTrue(merged.markers().all { it.occurredAt == last && it.toolCallId == "toolu_t1" })
    }

    @Test
    fun duplicateEntriesWithinPage_keepOneLaunchAndFinishPerTask() {
        val rows =
            reduce(
                entry(started("t1"), 1),
                entry(started("t2"), 2),
                entry(started("t1"), 3),
                entry(terminal("t2", "vanished"), 4),
                entry(terminal("t1", "completed"), 5),
                entry(terminal("t1", "failed"), 6),
            )
        assertEquals(listOf("t1:false", "t2:false", "t2:true", "t1:true"), rows.keys())
        assertEquals("vanished", rows.markers()[2].terminal?.status)
        assertEquals("toolu_t2", rows.markers()[2].toolCallId)
    }

    @Test
    fun midLifeAndProgressAndRoster_createNoLifecyclePositions() {
        val rows =
            reduce(
                entry(midLife("t1", "{not parsed}"), 1),
                entry(BackgroundTaskProjectionTest.progress("t1"), 2),
                entry(BackgroundTaskProjectionTest.rosterFrame(listOf(BackgroundTaskProjectionTest.row("t1"))), 3),
            )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun historyWithoutInteractive_dropsLifecycleButKeepsOrdinaryMessage() {
        val entries =
            listOf(
                entry(terminal("t1", "completed"), 3),
                entry(started("t1"), 2),
                entry(envelope("message", """{"conversation_id":"c1","message_id":"m1","role":"user","text":"hello"}"""), 1),
            )
        assertEquals(listOf("m1"), reduceHistoryPage(entries, interactive = false).keys())
    }

    @Test
    fun malformedAndMissingIds_costOnlyTheirEntryAndEmptyTaskIdNeverJoins() {
        val missingId = started("t1").let { it.copy(payload = JsonObject(it.payload.jsonObject - "task_id")) }
        val missingStatus = terminal("t1", "completed").let { it.copy(payload = JsonObject(it.payload.jsonObject - "status")) }
        val wrongId = started("t1").let { it.copy(payload = JsonObject(it.payload.jsonObject + ("task_id" to JsonObject(emptyMap())))) }
        val rows =
            reduce(
                entry(missingId, 1),
                entry(missingStatus, 2),
                entry(started(""), 3),
                entry(terminal("", "completed"), 4),
                entry(wrongId, 5),
                entry(started("good"), 6),
            )
        assertEquals(listOf("good:false"), rows.keys())
    }

    @Test
    fun launchTextAndItsCutReport_backfillVerbatimEvenWithAnEmptyToolId() {
        val dto = startedDto().copy(toolCallId = "", description = "<b>$(touch /tmp/no)</b>", truncatedFields = listOf("description"))
        val rows = emptyList<ThreadItem>().withBackgroundTaskUpdated(updatedDto(), last).withBackgroundTaskStarted(dto, first)
        val finish = rows.markers().first()
        assertNull(finish.toolCallId)
        assertEquals(dto.description, finish.description)
        assertEquals(dto.truncatedFields, finish.truncatedFields)
        assertEquals(dto.taskType, finish.taskType)
    }

    @Test
    fun lifecycleMarkers_doNotSplitAssistantSegmentsOrStopStreaming() {
        val delta = LiveSessionEvent.AssistantDelta("c1", "turn1", 0, "hello")
        val head = emptyList<ThreadItem>().withAssistantDelta(delta, first)
        val marked = head.withBackgroundTaskStarted(startedDto(), first).withBackgroundTaskUpdated(updatedDto(), last)
        assertEquals(
            head.filterIsInstance<ThreadItem.MessageItem>(),
            marked.withOnlyLastRowStreaming().filterIsInstance<ThreadItem.MessageItem>(),
        )
        val extended = marked.withAssistantDelta(delta.copy(seq = 1, text = " world"), last)
        assertEquals(1, extended.filterIsInstance<ThreadItem.MessageItem>().size)
        assertEquals(
            "hello world",
            extended
                .filterIsInstance<ThreadItem.MessageItem>()
                .single()
                .message.content,
        )
        assertEquals(marked.markers(), extended.markers())

        val tail = emptyList<ThreadItem>().withAssistantDelta(delta.copy(seq = 1, text = " world"), last)
        val merged = tail.mergeHistoryRows(marked)
        assertEquals(1, merged.filterIsInstance<ThreadItem.MessageItem>().size)
        assertEquals(
            "hello world",
            merged
                .filterIsInstance<ThreadItem.MessageItem>()
                .single()
                .message.content,
        )
        assertEquals(marked.markers(), merged.markers())
    }

    @Test
    fun evidenceIsExcludedFromRenderAndCache_withoutLosingOrdinaryOrderOrToolParent() {
        val tool =
            envelope(
                "tool_use",
                """{"conversation_id":"c1","turn_id":"turn1","tool_use_id":"toolu_t1","name":"Agent","input_summary":"work","input":{},"parent_tool_use_id":"parent"}""",
            )
        val rows = reduce(entry(started("t1"), 1), entry(tool, 2), entry(terminal("t1", "completed"), 3))
        val ordinary = rows.filterIsInstance<ThreadItem.MessageItem>()
        assertEquals(1, ordinary.size)
        assertEquals(
            "parent",
            ordinary
                .single()
                .message.toolCall
                ?.parentToolUseId,
        )
        assertEquals("toolu_t1", rows.markers().last().toolCallId)
        assertEquals(ordinary.map { ThreadRow.Delivered(it) }, foldQueuedRows(rows, emptyList()))
        assertEquals(listOf(message("m1")), cacheableThreadRows(listOf(message("m1")) + rows))
    }

    @Test
    fun threadProjection_historyRoutingAndLiveJoinsAreConversationLocal() =
        runTest {
            val projection = ThreadProjection()
            projection.applyBackgroundTaskLifecycle(terminal("t1", "completed", conversationId = "c1"))
            projection.applyBackgroundTaskLifecycle(started("t1", conversationId = "c2"))
            assertNull(
                projection
                    .observe("c1")
                    .first()
                    .markers()
                    .single()
                    .toolCallId,
            )
            assertNull(
                projection
                    .observe("c2")
                    .first()
                    .markers()
                    .single()
                    .terminal,
            )
            // Payload c2 cannot choose the destination of a page requested for c1.
            projection.mergeHistoryPage("c1", HistoryPage(listOf(entry(started("t1", conversationId = "c2"), 1)), "", true), true)
            assertEquals(
                "toolu_t1",
                projection
                    .observe("c1")
                    .first()
                    .markers()
                    .last()
                    .toolCallId,
            )
            assertEquals(
                2,
                projection
                    .observe("c1")
                    .first()
                    .markers()
                    .size,
            )
            assertEquals(
                1,
                projection
                    .observe("c2")
                    .first()
                    .markers()
                    .size,
            )
            assertEquals(0, projection.observeRowCounts().first().getValue("c1"))
        }

    @Test
    fun gatedHistoryAndMalformedLiveFrame_doNotAffectThreadProjection() =
        runTest {
            val projection = ThreadProjection()
            projection.mergeHistoryPage("c1", HistoryPage(listOf(entry(started("t1"), 1)), "", true), false)
            projection.applyBackgroundTaskLifecycle(envelope("background_task_started", """{"conversation_id":"c1"}"""))
            projection.applyBackgroundTaskLifecycle(started("t1", conversationId = ""))
            assertTrue(projection.observe("c1").first().isEmpty())
            projection.applyBackgroundTaskLifecycle(started("t1"))
            assertFalse(projection.observe("c1").first().isEmpty())
        }
}
