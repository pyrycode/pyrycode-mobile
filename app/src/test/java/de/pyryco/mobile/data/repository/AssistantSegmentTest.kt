package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.MobileJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * Per-segment assistant rows (#1350): a turn's text after a tool call or a user message starts a new row
 * below it, live and in a replayed page, and the segment key holds across page boundaries, the page-live
 * join and the cache.
 *
 * Each scenario is one script of [Step]s, played live through [ThreadProjection] and turned into history
 * entries, so the two lanes are compared on the same input.
 */
class AssistantSegmentTest {
    // ---- AC 1: live ---------------------------------------------------------------------------------

    @Test
    fun live_textToolText_drawsBubbleToolBubbleInOrder_andOnlyTheNewestStreams() =
        runTest {
            val projection = ThreadProjection()
            projection.play(FULL.dropLast(1))

            val rows = projection.rows()
            assertEquals(EXPECTED, rows.summary())
            assertEquals(listOf(false, false, true), rows.streaming())
        }

    @Test
    fun live_aRowAfterTheTextStopsItStreaming() =
        runTest {
            val projection = ThreadProjection()
            projection.play(listOf(Delta(0, "Let me "), Tool("u1")))

            assertEquals(listOf(false, false), projection.rows().streaming())
        }

    @Test
    fun live_turnEndFinishesEverySegmentOfTheTurn() =
        runTest {
            val projection = ThreadProjection()
            projection.play(FULL)

            assertEquals(EXPECTED, projection.rows().summary())
            assertEquals(listOf(false, false, false), projection.rows().streaming())
        }

    @Test
    fun live_userMessageMidTurn_startsANewBubbleBelowIt() =
        runTest {
            val projection = ThreadProjection()
            projection.play(listOf(Delta(0, "Let me "), Delta(1, "look. "), User("m1", "wait"), Delta(2, "Found it.")))

            assertEquals(
                listOf(TURN to "Let me look. ", "m1" to "wait", "$TURN#2" to "Found it."),
                projection.rows().summary(),
            )
            assertEquals(listOf(false, false, true), projection.rows().streaming())
        }

    @Test
    fun withFinalizedTurn_leavesAnotherTurnsSegmentStreaming() {
        val rows =
            emptyList<ThreadItem>()
                .withAssistantDelta(delta(0, "a", turn = "other"), TS_INSTANT)
                .withFinalizedTurn(LiveSessionEvent.TurnEnd(CONVERSATION, TURN, "end_turn"))

        assertEquals(listOf(true), rows.streaming())
    }

    // ---- AC 2: a replayed page matches live and merges into it without a new row ------------------

    @Test
    fun history_sameEntries_reduceToTheLiveRowsAndIds() =
        runTest {
            val projection = ThreadProjection()
            projection.play(FULL)

            assertEquals(projection.rows().summary(), reduceHistoryPage(page(entries(FULL)), interactive = true).summary())
        }

    @Test
    fun history_pageMergedIntoTheLiveRows_addsNoRow() =
        runTest {
            val projection = ThreadProjection()
            projection.play(FULL)
            val live = projection.rows()

            projection.mergeHistoryPage(CONVERSATION, HistoryPage(page(entries(FULL)), "", atStart = true), interactive = true)

            assertEquals(live.summary(), projection.rows().summary())
            assertEquals(live.summary(), live.mergeHistoryRows(reduceHistoryPage(page(entries(FULL)), interactive = true)).summary())
        }

    @Test
    fun cache_mergedUnderTheLiveRows_addsNoRow() =
        runTest {
            val projection = ThreadProjection()
            projection.play(FULL)
            val live = projection.rows()

            assertEquals(live.summary(), live.mergeCachedRows(reduceHistoryPage(page(entries(FULL)), interactive = true)).summary())
        }

    @Test
    fun cache_aRowFromBeforeSegments_countsAsTheFirstSegment() =
        runTest {
            val projection = ThreadProjection()
            projection.play(FULL)
            val live = projection.rows()
            // The pre-#1350 shape: one row keyed by the bare turn id, holding the whole turn's text.
            val legacy = assistantRow(TURN, "Let me look. Found it.")

            val drawn = live.mergeCachedRows(listOf(legacy, live[1]))

            assertEquals(EXPECTED, drawn.summary())
        }

    // ---- AC 3: a page boundary inside a segment --------------------------------------------------

    @Test
    fun backwardWalk_everyCutPoint_mergesToTheUncutRows() =
        runTest {
            val all = entries(FULL)
            // A newest page holding only the turn_end is not a cut inside a segment; that case is #1419's, below.
            for (cut in 1 until all.size - 1) {
                val projection = ThreadProjection()
                projection.mergeHistoryPage(CONVERSATION, HistoryPage(page(all.drop(cut)), "c", atStart = false), interactive = true)
                projection.mergeHistoryPage(CONVERSATION, HistoryPage(page(all.take(cut)), "", atStart = true), interactive = true)

                val rows = projection.rows()
                assertEquals("cut at $cut", EXPECTED, rows.summary())
                assertEquals("cut at $cut", listOf(false, false, false), rows.streaming())
            }
        }

    @Test
    fun backwardWalk_threePagesCutInsideOneSegment_mergeToTheUncutRows() =
        runTest {
            val script = listOf(Delta(0, "a"), Delta(1, "b"), Delta(2, "c"), Delta(3, "d"), End)
            val all = entries(script)
            for (first in 1 until all.size) {
                for (second in first + 1 until all.size - 1) {
                    val projection = ThreadProjection()
                    for (slice in listOf(all.drop(second), all.subList(first, second), all.take(first))) {
                        projection.mergeHistoryPage(CONVERSATION, HistoryPage(page(slice), "c", atStart = false), interactive = true)
                    }

                    assertEquals("cuts $first,$second", listOf(TURN to "abcd"), projection.rows().summary())
                    assertEquals("cuts $first,$second", listOf(false), projection.rows().streaming())
                }
            }
        }

    @Ignore("blocked on #1419: a turn_end that arrives before its rows leaves them streaming")
    @Test
    fun turnEndOnANewerPageThanItsRows_settlesThem() =
        runTest {
            val all = entries(FULL)
            val projection = ThreadProjection()
            projection.mergeHistoryPage(CONVERSATION, HistoryPage(page(all.takeLast(1)), "c", atStart = false), interactive = true)
            projection.mergeHistoryPage(CONVERSATION, HistoryPage(page(all.dropLast(1)), "", atStart = true), interactive = true)

            assertEquals(listOf(false, false, false), projection.rows().streaming())
        }

    @Test
    fun newestPageMeetsLive_everyJoinPointAndOverlap_holdsEachDeltaOnce() =
        runTest {
            val all = entries(FULL)
            val lastDelta = FULL.indexOfLast { it is Delta }
            for (join in 0..lastDelta) {
                for (end in join..all.size) {
                    val projection = ThreadProjection()
                    projection.play(FULL.drop(join))
                    projection.mergeHistoryPage(CONVERSATION, HistoryPage(page(all.take(end)), "c", atStart = false), interactive = true)

                    val rows = projection.rows()
                    assertEquals("join $join, page to $end", EXPECTED, rows.summary())
                    assertEquals("join $join, page to $end", listOf(false, false, false), rows.streaming())
                }
            }
        }

    @Test
    fun cache_underANewestPageCutInsideASegment_holdsEachDeltaOnce() {
        val all = entries(FULL)
        val cached = reduceHistoryPage(page(all), interactive = true)
        for (cut in 0..all.size) {
            val live = reduceHistoryPage(page(all.drop(cut)), interactive = true)

            assertEquals("cut at $cut", EXPECTED, live.mergeCachedRows(cached).summary())
        }
    }

    // ---- AC 4: no input puts two rows with one key in the thread ---------------------------------

    @Test
    fun repeatedFirstDeltaAfterAToolRow_changesNothing() =
        runTest {
            val projection = ThreadProjection()
            projection.play(listOf(Delta(0, "Let me "), Tool("u1"), Result("u1")))
            val before = projection.rows()

            projection.play(listOf(Delta(0, "Let me ")))

            assertEquals(before, projection.rows())
        }

    @Test
    fun deltaAtOrBelowTheTurnsHighestSeq_changesNothing() {
        val start = emptyList<ThreadItem>().withAssistantDelta(delta(0, "a"), TS_INSTANT).withAssistantDelta(delta(1, "b"), TS_INSTANT)

        assertEquals(start, start.withAssistantDelta(delta(1, "again"), TS_INSTANT))
        val afterTool = start.withToolUse(toolUse("u1"), TS_INSTANT)
        assertEquals(afterTool, afterTool.withAssistantDelta(delta(1, "again"), TS_INSTANT))
        assertEquals(afterTool, afterTool.withAssistantDelta(delta(0, "a"), TS_INSTANT))
    }

    @Test
    fun deltaWhoseSegmentKeyAToolRowHolds_addsNoRow() {
        val rows =
            emptyList<ThreadItem>()
                .withAssistantDelta(delta(0, "a"), TS_INSTANT)
                .withToolUse(toolUse("$TURN#1"), TS_INSTANT)
                .withAssistantDelta(delta(1, "b"), TS_INSTANT)

        assertEquals(listOf(TURN, "$TURN#1"), rows.ids())
        assertEquals(Role.Tool, (rows[1] as ThreadItem.MessageItem).message.role)
    }

    @Test
    fun toolUseWhoseIdAnAssistantRowHolds_addsNoRow() {
        val rows = emptyList<ThreadItem>().withAssistantDelta(delta(0, "a"), TS_INSTANT).withToolUse(toolUse(TURN), TS_INSTANT)

        assertEquals(listOf(TURN), rows.ids())
    }

    @Test
    fun hostileTurnIdSpellingAnotherTurnsSegmentKey_neverRepeatsAKey() {
        val rows =
            emptyList<ThreadItem>()
                .withAssistantDelta(delta(0, "a"), TS_INSTANT)
                .withToolUse(toolUse("u1"), TS_INSTANT)
                .withAssistantDelta(delta(2, "b"), TS_INSTANT)
                .withToolUse(toolUse("u2"), TS_INSTANT)
                .withAssistantDelta(delta(0, "spoof", turn = "$TURN#2"), TS_INSTANT)

        assertEquals(rows.ids().distinct(), rows.ids())
        assertEquals(listOf(TURN, "u1", "$TURN#2", "u2"), rows.ids())
    }

    @Test
    fun onlyLastRowStreaming_returnsTheSameListWhenNothingChanges() {
        val rows = listOf(assistantRow("a", "x"), assistantRow("b", "y").let { it.copy(message = it.message.copy(isStreaming = true)) })

        assertTrue(rows.withOnlyLastRowStreaming() === rows)
        assertFalse((rows.reversed().withOnlyLastRowStreaming()[0] as ThreadItem.MessageItem).message.isStreaming)
    }

    // ---- Script ----------------------------------------------------------------------------------

    private sealed interface Step

    private data class Delta(
        val seq: Int,
        val text: String,
    ) : Step

    private data class Tool(
        val id: String,
    ) : Step

    private data class Result(
        val id: String,
    ) : Step

    private data class User(
        val id: String,
        val text: String,
    ) : Step

    private data object End : Step

    private fun ThreadProjection.play(steps: List<Step>) {
        for (step in steps) {
            when (step) {
                is Delta -> applyAssistantDelta(delta(step.seq, step.text))
                is Tool -> applyToolUse(toolUse(step.id))
                is Result -> applyToolResult(LiveSessionEvent.ToolResult(CONVERSATION, TURN, step.id, false, "ok"))
                is User -> appendMessages(listOf(CONVERSATION to Message(step.id, "", Role.User, step.text, TS_INSTANT, false)))
                End -> finalizeAssistantTurn(LiveSessionEvent.TurnEnd(CONVERSATION, TURN, "end_turn"))
            }
        }
    }

    /** The script's history entries, oldest-first, with ids counting up from 1. */
    private fun entries(steps: List<Step>): List<HistoryEntry> =
        steps.mapIndexed { index, step ->
            val (type, payload) =
                when (step) {
                    is Delta ->
                        "assistant_delta" to
                            """{"conversation_id":"$CONVERSATION","turn_id":"$TURN","seq":${step.seq},"text":"${step.text}"}"""
                    is Tool ->
                        "tool_use" to
                            """{"conversation_id":"$CONVERSATION","turn_id":"$TURN","tool_use_id":"${step.id}","name":"Bash","input_summary":"ls"}"""
                    is Result ->
                        "tool_result" to
                            """{"conversation_id":"$CONVERSATION","turn_id":"$TURN","tool_use_id":"${step.id}","is_error":false,"result_summary":"ok"}"""
                    is User -> "send_message" to """{"conversation_id":"$CONVERSATION","message_id":"${step.id}","text":"${step.text}"}"""
                    End -> "turn_end" to """{"conversation_id":"$CONVERSATION","turn_id":"$TURN","stop_reason":"end_turn"}"""
                }
            HistoryEntry(index + 1L, type, MobileJson.parseToJsonElement(payload), TS_INSTANT)
        }

    /** [oldestFirst] in the wire's newest-first order. */
    private fun page(oldestFirst: List<HistoryEntry>): List<HistoryEntry> = oldestFirst.reversed()

    private suspend fun ThreadProjection.rows(): List<ThreadItem> = observe(CONVERSATION).first()

    private fun delta(
        seq: Int,
        text: String,
        turn: String = TURN,
    ) = LiveSessionEvent.AssistantDelta(CONVERSATION, turn, seq, text)

    private fun toolUse(id: String) = LiveSessionEvent.ToolUse(CONVERSATION, TURN, id, "Bash", "ls")

    private fun assistantRow(
        id: String,
        content: String,
    ) = ThreadItem.MessageItem(Message(id, "", Role.Assistant, content, TS_INSTANT, isStreaming = false))

    private fun List<ThreadItem>.ids(): List<String> = filterIsInstance<ThreadItem.MessageItem>().map { it.message.id }

    /** Each row as (id, content), after checking no two rows share an id — the `LazyColumn` key. */
    private fun List<ThreadItem>.summary(): List<Pair<String, String>> {
        assertEquals("row ids must be distinct", ids().distinct(), ids())
        return filterIsInstance<ThreadItem.MessageItem>().map { it.message.id to it.message.content }
    }

    private fun List<ThreadItem>.streaming(): List<Boolean> = filterIsInstance<ThreadItem.MessageItem>().map { it.message.isStreaming }

    private companion object {
        const val CONVERSATION = "c1"
        const val TURN = "t1"
        val TS_INSTANT: Instant = Instant.parse("2026-10-01T10:00:00Z")

        /** Text, a tool call and its result, more text, the turn's end. */
        val FULL: List<Step> =
            listOf(Delta(0, "Let me "), Delta(1, "look. "), Tool("u1"), Result("u1"), Delta(2, "Found "), Delta(3, "it."), End)

        val EXPECTED = listOf(TURN to "Let me look. ", "u1" to "Bash", "$TURN#2" to "Found it.")
    }
}
