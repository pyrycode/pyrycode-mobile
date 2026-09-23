package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.model.ToolDenial
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.ToolProgressPayloadDto
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the history-page reduction and merge (#645) — the pure half, with no relay, no
 * repository and no coroutine. The wiring half (`requestHistory` folds its page into the thread) lives
 * in `RemoteConversationRepositoryTest`.
 *
 * Every fixture is built as wire JSON rather than as a DTO, because the reduction's contract is
 * *decoding the stored payload with the live lane's own arms* — a DTO fixture would skip the boundary
 * under test.
 */
class HistoryPageReducerTest {
    // ---- AC #1: a page reduces oldest-first --------------------------------------------------------

    @Test
    fun reduce_reversesWireOrderToOldestFirst() {
        val rows =
            reduceHistoryPage(
                listOf(
                    entry(3, "message", messagePayload("m3", "assistant", "third"), ts = "2026-09-05T10:03:00Z"),
                    entry(2, "message", messagePayload("m2", "user", "second"), ts = "2026-09-05T10:02:00Z"),
                    entry(1, "message", messagePayload("m1", "user", "first"), ts = "2026-09-05T10:01:00Z"),
                ),
                interactive = true,
            )

        assertEquals(listOf("m1", "m2", "m3"), rows.messageIds())
        // The stored entry's own ts becomes the row timestamp — never the moment of replay.
        assertEquals(Instant.parse("2026-09-05T10:01:00Z"), rows.messageRow("m1")?.timestamp)
    }

    @Test
    fun reduce_storedSendMessage_becomesUserRowKeyedOnMessageId() {
        val rows = reduceHistoryPage(listOf(entry(1, "send_message", sendMessagePayload("s1", "hi"))), interactive = true)

        val row = rows.messageRow("s1")
        assertEquals(Role.User, row?.role)
        assertEquals("hi", row?.content)
        assertEquals(TS_INSTANT, row?.timestamp)
        assertFalse(row?.isStreaming ?: true)
    }

    // ---- The within-page fold is the live lane's fold, not a second one ----------------------------

    @Test
    fun reduce_toolUseAndResultInOnePage_foldToOneCompletedRow() {
        val rows =
            reduceHistoryPage(
                listOf(
                    entry(2, "tool_result", toolResultPayload("t1", isError = false, summary = "ok")),
                    entry(1, "tool_use", toolUsePayload("t1", name = "Read", input = "a.kt")),
                ),
                interactive = true,
            )

        assertEquals(listOf("t1"), rows.messageIds())
        val toolCall = rows.messageRow("t1")?.toolCall
        assertEquals("Read", toolCall?.toolName)
        assertEquals("ok", toolCall?.output)
        assertEquals(ToolCallStatus.Done, toolCall?.status)
    }

    // ---- #810: the tool call's own input fields and its parent identity reach the row --------------

    @Test
    fun reduce_toolUse_carriesInputFieldsAndParentVerbatim() {
        val rows =
            reduceHistoryPage(
                listOf(
                    entry(
                        1,
                        "tool_use",
                        toolUsePayload(
                            "t1",
                            name = "Edit",
                            input = "a.kt",
                            extra = """"parent_tool_use_id":"agent-1","input":{"file_path":"../a.kt","old_string":"x…"}""",
                        ),
                    ),
                ),
                interactive = true,
            )

        val toolCall = rows.messageRow("t1")?.toolCall
        assertEquals(mapOf("file_path" to "../a.kt", "old_string" to "x…"), toolCall?.inputFields)
        assertEquals("agent-1", toolCall?.parentToolUseId)
        assertEquals("a.kt", toolCall?.input)
    }

    @Test
    fun reduce_toolUseWithoutTheNewKeys_isATopLevelRowWithNoFields() {
        val rows = reduceHistoryPage(listOf(entry(1, "tool_use", toolUsePayload("t1", name = "Read", input = "a.kt"))), interactive = true)

        val toolCall = rows.messageRow("t1")?.toolCall
        assertEquals(emptyMap<String, String>(), toolCall?.inputFields)
        assertEquals("", toolCall?.parentToolUseId)
    }

    @Test
    fun reduce_toolResultNamingAParent_setsTheRowsParent() {
        val rows =
            reduceHistoryPage(
                listOf(
                    entry(
                        2,
                        "tool_result",
                        toolResultPayload("t1", isError = false, summary = "ok", extra = """"parent_tool_use_id":"agent-1""""),
                    ),
                    entry(1, "tool_use", toolUsePayload("t1", name = "Read", input = "a.kt")),
                ),
                interactive = true,
            )

        assertEquals("agent-1", rows.messageRow("t1")?.toolCall?.parentToolUseId)
    }

    @Test
    fun reduce_toolResultWithEmptyParent_keepsTheParentItsUseNamed() {
        val rows =
            reduceHistoryPage(
                listOf(
                    entry(2, "tool_result", toolResultPayload("t1", isError = true, summary = "no", extra = """"parent_tool_use_id":""""")),
                    entry(
                        1,
                        "tool_use",
                        toolUsePayload("t1", name = "Read", input = "a.kt", extra = """"parent_tool_use_id":"agent-1","input":{"k":"v"}"""),
                    ),
                ),
                interactive = true,
            )

        val toolCall = rows.messageRow("t1")?.toolCall
        assertEquals("agent-1", toolCall?.parentToolUseId)
        assertEquals(mapOf("k" to "v"), toolCall?.inputFields)
        assertEquals(ToolCallStatus.Failed, toolCall?.status)
    }

    @Test
    fun reduce_assistantDeltasAndTurnEnd_foldToOneFinalizedRow() {
        val rows =
            reduceHistoryPage(
                listOf(
                    entry(3, "turn_end", turnEndPayload("turn-1")),
                    entry(2, "assistant_delta", assistantDeltaPayload("turn-1", seq = 2, text = " world")),
                    entry(1, "assistant_delta", assistantDeltaPayload("turn-1", seq = 1, text = "hello")),
                ),
                interactive = true,
            )

        assertEquals(listOf("turn-1"), rows.messageIds())
        assertEquals("hello world", rows.messageRow("turn-1")?.content)
        assertFalse(rows.messageRow("turn-1")?.isStreaming ?: true)
    }

    @Test
    fun reduce_sessionTransition_readsOccurredAtFromThePayload() {
        val rows =
            reduceHistoryPage(
                listOf(entry(1, "session_transition", sessionTransitionPayload("s-old", "s-new", "clear"), ts = TS)),
                interactive = true,
            )

        val boundary = rows.single() as ThreadItem.SessionBoundary
        assertEquals(BoundaryReason.Clear, boundary.reason)
        assertEquals(Instant.parse(OCCURRED_AT), boundary.occurredAt)
    }

    @Test
    fun reduce_unrecognizedMessage_takesAnIdDerivedFromTheEntry() {
        val rows = reduceHistoryPage(listOf(entry(77, "unrecognized_message", unrecognizedPayload("line_type"))), interactive = true)

        val row = rows.single() as ThreadItem.UnrecognizedMessage
        assertEquals(UnrecognizedSite.LineType, row.site)
        assertEquals(TS_INSTANT, row.occurredAt)
        // Derived from the durable log id, so a re-reduction produces the identical id (see the
        // re-merge test below). The value itself is not the contract; its determinism is.
        assertEquals(reduceHistoryPage(listOf(entry(77, "unrecognized_message", unrecognizedPayload("line_type"))), true), rows)
    }

    // ---- Forward compatibility: an unrecognised type or a bad payload costs one entry, not the page --

    @Test
    fun reduce_unknownType_dropsOneEntryAndKeepsTheRest() {
        val rows =
            reduceHistoryPage(
                listOf(
                    entry(3, "message", messagePayload("m2", "user", "after")),
                    entry(2, "some_future_verb", """{"whatever":true}"""),
                    entry(1, "message", messagePayload("m1", "user", "before")),
                ),
                interactive = true,
            )

        assertEquals(listOf("m1", "m2"), rows.messageIds())
    }

    @Test
    fun reduce_malformedPayloadOnAKnownType_dropsOnlyThatEntry() {
        val rows =
            reduceHistoryPage(
                listOf(
                    entry(3, "message", messagePayload("m2", "user", "after")),
                    // `role` is required and "system" has no domain target, so the decode fails.
                    entry(2, "message", messagePayload("bad", "system", "unmappable")),
                    entry(1, "message", messagePayload("m1", "user", "before")),
                ),
                interactive = true,
            )

        assertEquals(listOf("m1", "m2"), rows.messageIds())
    }

    @Test
    fun reduce_malformedTimestampOnTheEntry_isNotReachable() {
        // A HistoryEntry cannot hold a malformed ts — `toHistoryPage` parses it at the decode boundary
        // — so the reduction has no timestamp failure of its own. Pinned so nobody adds a guard for it.
        val rows = reduceHistoryPage(listOf(entry(1, "message", messagePayload("m1", "user", "x"))), interactive = true)
        assertEquals(TS_INSTANT, rows.messageRow("m1")?.timestamp)
    }

    // ---- AC #4: nothing that drives live state can be produced by a reduction ----------------------

    @Test
    fun reduce_stateAndModalTypes_produceNoRowsAtAll() {
        val rows =
            reduceHistoryPage(
                listOf(
                    entry(7, "modal_dismissed", """{"modal_id":"md","outcome":"answered","source":"phone"}"""),
                    entry(6, "modal_shown", modalShownPayload()),
                    entry(5, "compacting", """{"conversation_id":"c1","active":true}"""),
                    entry(4, "api_retry", """{"conversation_id":"c1","active":true,"attempt":{"current":1,"total":3}}"""),
                    entry(3, "queue_state", """{"conversation_id":"c1","queued":[]}"""),
                    entry(2, "stall", """{"conversation_id":"c1"}"""),
                    entry(1, "turn_state", """{"conversation_id":"c1","state":"thinking"}"""),
                ),
                interactive = true,
            )

        assertEquals(emptyList<ThreadItem>(), rows)
    }

    // ---- The capability gate mirrors the live lane arm-for-arm ------------------------------------

    @Test
    fun reduce_withoutInteractive_dropsStructuredArmsAndKeepsMessages() {
        val entries =
            listOf(
                entry(5, "unrecognized_message", unrecognizedPayload("line_type")),
                entry(4, "session_transition", sessionTransitionPayload("s-old", "s-new", "clear")),
                entry(3, "tool_use", toolUsePayload("t1", name = "Read", input = "a.kt")),
                entry(2, "assistant_delta", assistantDeltaPayload("turn-1", seq = 1, text = "hi")),
                entry(1, "message", messagePayload("m1", "user", "before")),
            )

        assertEquals(listOf("m1"), reduceHistoryPage(entries, interactive = false).messageIds())
        assertEquals(1, reduceHistoryPage(entries, interactive = false).size)
        assertEquals(5, reduceHistoryPage(entries, interactive = true).size)
    }

    // ---- AC #3: a page cannot contribute two rows that the thread's LazyColumn keys the same --------

    @Test
    fun reduce_repeatedUnrecognizedLogId_yieldsOneRow() {
        val rows =
            reduceHistoryPage(
                listOf(
                    entry(9, "unrecognized_message", unrecognizedPayload("user_block")),
                    entry(9, "unrecognized_message", unrecognizedPayload("line_type")),
                ),
                interactive = true,
            )

        assertEquals(1, rows.size)
    }

    @Test
    fun reduce_boundariesSharingASessionPairButNotAnInstant_yieldTwoRows() {
        // #775: one session evicted twice is two real delimiters with one pair.
        val rows =
            reduceHistoryPage(
                listOf(
                    entry(
                        2,
                        "session_transition",
                        sessionTransitionPayload("s-old", "s-new", "clear", occurredAt = "2026-09-05T11:00:00Z"),
                    ),
                    entry(1, "session_transition", sessionTransitionPayload("s-old", "s-new", "clear", occurredAt = OCCURRED_AT)),
                ),
                interactive = true,
            )

        assertEquals(2, rows.size)
    }

    @Test
    fun reduce_boundariesSharingPairAndInstant_yieldOneRow() {
        val rows =
            reduceHistoryPage(
                listOf(
                    entry(2, "session_transition", sessionTransitionPayload("s-old", "s-new", "clear")),
                    entry(1, "session_transition", sessionTransitionPayload("s-old", "s-new", "clear")),
                ),
                interactive = true,
            )

        assertEquals(1, rows.size)
    }

    // ---- AC #1: the merge is a prepend, and it never reorders what is already there ----------------

    @Test
    fun merge_putsReducedRowsAheadOfAnUntouchedExistingThread() {
        val existing = listOf(messageItem("live-1"), messageItem("live-2"))
        val reduced =
            reduceHistoryPage(
                listOf(
                    entry(2, "message", messagePayload("h2", "user", "b")),
                    entry(1, "message", messagePayload("h1", "user", "a")),
                ),
                interactive = true,
            )

        val merged = existing.mergeHistoryRows(reduced)

        assertEquals(listOf("h1", "h2", "live-1", "live-2"), merged.messageIds())
    }

    // ---- AC #2: a stored send and its local echo are one row, keyed on message_id ------------------

    @Test
    fun merge_storedSendMessageCollapsesWithItsLocalEcho() {
        val echo = listOf(messageItem("sent-1", content = "hello"))
        val reduced = reduceHistoryPage(listOf(entry(1, "send_message", sendMessagePayload("sent-1", "hello"))), interactive = true)

        val merged = echo.mergeHistoryRows(reduced)

        assertEquals(listOf("sent-1"), merged.messageIds())
    }

    @Test
    fun merge_twoSendsOfIdenticalTextWithDifferentIds_stayTwoRows() {
        val echo = listOf(messageItem("sent-1", content = "hello"))
        val reduced = reduceHistoryPage(listOf(entry(1, "send_message", sendMessagePayload("sent-2", "hello"))), interactive = true)

        val merged = echo.mergeHistoryRows(reduced)

        assertEquals(listOf("sent-2", "sent-1"), merged.messageIds())
    }

    // ---- AC #3: re-reducing an overlapping page adds nothing, for every row kind -------------------

    @Test
    fun merge_reMergingTheSamePage_addsNoRowOfAnyKind() {
        val entries =
            listOf(
                entry(3, "unrecognized_message", unrecognizedPayload("line_type")),
                entry(2, "session_transition", sessionTransitionPayload("s-old", "s-new", "clear")),
                entry(1, "message", messagePayload("m1", "user", "a")),
            )
        val once = emptyList<ThreadItem>().mergeHistoryRows(reduceHistoryPage(entries, interactive = true))

        val twice = once.mergeHistoryRows(reduceHistoryPage(entries, interactive = true))

        assertEquals(3, once.size)
        assertEquals(once, twice)
    }

    @Test
    fun merge_aPageWhoseBoundaryIsAlreadyLive_addsNoSecondBoundary() {
        // The boundary's every field is payload-derived, so a page twin is `==` to its live twin.
        val live = reduceHistoryPage(listOf(entry(1, "session_transition", sessionTransitionPayload("s-old", "s-new", "clear"))), true)
        val merged = live.mergeHistoryRows(live)

        assertEquals(1, merged.size)
    }

    @Test
    fun merge_boundaryDifferingOnlyInOccurredAt_isAdmitted() {
        // #775: a session evicted, woken and evicted again emits the same pair twice with different
        // instants. Both are real delimiters, and ThreadScreen keys a boundary on the pair AND occurredAt,
        // so admitting the older one gives the LazyColumn two distinct keys.
        val live = reduceHistoryPage(listOf(entry(1, "session_transition", sessionTransitionPayload("s-old", "s-new", "clear"))), true)
        val later =
            reduceHistoryPage(
                listOf(
                    entry(
                        2,
                        "session_transition",
                        sessionTransitionPayload("s-old", "s-new", "clear", occurredAt = "2026-09-05T12:00:00Z"),
                    ),
                ),
                true,
            )

        val merged = live.mergeHistoryRows(later)

        assertEquals(2, merged.size)
        assertEquals(later + live, merged)
    }

    @Test
    fun merge_boundaryMatchingOnPairAndInstantButNotReason_addsNoSecondRow() {
        // The identity is the triple; a reason mismatch does not make a second row, because the key would
        // not tell the two apart.
        val live = reduceHistoryPage(listOf(entry(1, "session_transition", sessionTransitionPayload("s-old", "s-new", "clear"))), true)
        val page = reduceHistoryPage(listOf(entry(1, "session_transition", sessionTransitionPayload("s-old", "s-new", "idle_evict"))), true)

        assertEquals(live, live.mergeHistoryRows(page))
    }

    @Test
    fun merge_emptyPage_leavesTheThreadIdentical() {
        val existing: List<ThreadItem> = listOf(messageItem("live-1"))

        assertTrue(existing.mergeHistoryRows(emptyList()) === existing)
        assertEquals(listOf("live-1"), existing.mergeHistoryRows(emptyList()).messageIds())
    }

    // ---- #811: a refused call replays as denied, never as failed -----------------------------------

    @Test
    fun reduce_useDeniedResult_foldToOneDeniedRowCarryingTheDenialVerbatim() {
        val rows =
            reduceHistoryPage(
                listOf(
                    entry(3, "tool_result", toolResultPayload("t1", isError = true, summary = "refused")),
                    entry(
                        2,
                        "tool_denied",
                        toolDeniedPayload(
                            "t1",
                            toolName = "Bash",
                            reasonType = "rule",
                            reason = "not allowed",
                            message = "Permission to use Bash denied in /home/op/project",
                        ),
                    ),
                    entry(1, "tool_use", toolUsePayload("t1", name = "Bash", input = "rm -rf x")),
                ),
                interactive = true,
            )

        assertEquals(listOf("t1"), rows.messageIds())
        val toolCall = rows.messageRow("t1")?.toolCall
        assertEquals(ToolCallStatus.Denied, toolCall?.status)
        assertEquals("refused", toolCall?.output)
        assertEquals(
            ToolDenial(
                toolName = "Bash",
                decisionReasonType = "rule",
                decisionReason = "not allowed",
                message = "Permission to use Bash denied in /home/op/project",
                truncatedFields = null,
                droppedFields = null,
            ),
            toolCall?.denial,
        )
    }

    @Test
    fun reduce_resultBeforeDenial_stillDenied() {
        // The daemon's result-line recovery reports a denial for a call whose result already shipped.
        val rows =
            reduceHistoryPage(
                listOf(
                    entry(3, "tool_denied", toolDeniedPayload("t1")),
                    entry(2, "tool_result", toolResultPayload("t1", isError = true, summary = "refused")),
                    entry(1, "tool_use", toolUsePayload("t1", name = "Bash", input = "ls")),
                ),
                interactive = true,
            )

        val toolCall = rows.messageRow("t1")?.toolCall
        assertEquals(ToolCallStatus.Denied, toolCall?.status)
        assertEquals("refused", toolCall?.output)
    }

    @Test
    fun reduce_errorResultWithoutDenial_staysFailed() {
        val rows =
            reduceHistoryPage(
                listOf(
                    entry(2, "tool_result", toolResultPayload("t1", isError = true, summary = "exit 1")),
                    entry(1, "tool_use", toolUsePayload("t1", name = "Bash", input = "ls")),
                ),
                interactive = true,
            )

        assertEquals(ToolCallStatus.Failed, rows.messageRow("t1")?.toolCall?.status)
        assertEquals(null, rows.messageRow("t1")?.toolCall?.denial)
    }

    @Test
    fun reduce_denialNamingNoRow_addsNoRow() {
        val rows =
            reduceHistoryPage(
                listOf(
                    entry(2, "tool_denied", toolDeniedPayload("tX")),
                    entry(1, "tool_use", toolUsePayload("t1", name = "Bash", input = "ls")),
                ),
                interactive = true,
            )

        assertEquals(listOf("t1"), rows.messageIds())
        assertEquals(ToolCallStatus.Running, rows.messageRow("t1")?.toolCall?.status)
    }

    @Test
    fun reduce_denialReportArrays_keepNullEmptyAndNamedFieldsApart() {
        val rows =
            reduceHistoryPage(
                listOf(
                    // Nothing cut or dropped: both reports null, empty reasons mean claude sent nothing.
                    entry(6, "tool_denied", toolDeniedPayload("t3", reason = "", truncated = "[]", dropped = "null")),
                    // The daemon emptied an over-cap tool name.
                    entry(5, "tool_denied", toolDeniedPayload("t2", toolName = "", dropped = """["tool_name"]""")),
                    // The daemon cut the message to fit.
                    entry(4, "tool_denied", toolDeniedPayload("t1", message = "cut…", truncated = """["message"]""")),
                    entry(3, "tool_use", toolUsePayload("t3", name = "Bash", input = "c")),
                    entry(2, "tool_use", toolUsePayload("t2", name = "Bash", input = "b")),
                    entry(1, "tool_use", toolUsePayload("t1", name = "Bash", input = "a")),
                ),
                interactive = true,
            )

        val cut = rows.messageRow("t1")?.toolCall?.denial
        assertEquals("cut…", cut?.message)
        assertEquals(listOf("message"), cut?.truncatedFields)
        assertEquals(null, cut?.droppedFields)

        val emptied = rows.messageRow("t2")?.toolCall?.denial
        assertEquals("", emptied?.toolName)
        assertEquals(listOf("tool_name"), emptied?.droppedFields)
        assertEquals(null, emptied?.truncatedFields)

        val unsent = rows.messageRow("t3")?.toolCall?.denial
        assertEquals("", unsent?.decisionReason)
        assertEquals(emptyList<String>(), unsent?.truncatedFields)
        assertEquals(null, unsent?.droppedFields)
    }

    @Test
    fun reduce_malformedDenial_dropsOnlyThatEntry() {
        val rows =
            reduceHistoryPage(
                listOf(
                    entry(3, "tool_result", toolResultPayload("t1", isError = true, summary = "refused")),
                    // `message` missing: the strict-required shape fails the decode.
                    entry(
                        2,
                        "tool_denied",
                        """{"conversation_id":"$CONVERSATION","turn_id":"turn-1","tool_use_id":"t1","tool_name":"Bash",""" +
                            """"decision_reason_type":"","decision_reason":"","truncated_fields":null,"dropped_fields":null}""",
                    ),
                    entry(1, "tool_use", toolUsePayload("t1", name = "Bash", input = "ls")),
                ),
                interactive = true,
            )

        assertEquals(listOf("t1"), rows.messageIds())
        assertEquals(ToolCallStatus.Failed, rows.messageRow("t1")?.toolCall?.status)
        assertEquals("refused", rows.messageRow("t1")?.toolCall?.output)
    }

    @Test
    fun reduce_denialWithoutInteractive_isIgnored() {
        val rows =
            reduceHistoryPage(
                listOf(
                    entry(2, "tool_denied", toolDeniedPayload("t1")),
                    entry(1, "tool_use", toolUsePayload("t1", name = "Bash", input = "ls")),
                ),
                interactive = false,
            )

        assertEquals(emptyList<String>(), rows.messageIds())
    }

    @Test
    fun withToolResult_onDeniedRow_keepsDeniedAndAttachesOutput() {
        val denial = ToolDenial("Bash", "", "", "no", truncatedFields = null, droppedFields = null)
        val denied =
            listOf<ThreadItem>()
                .withToolUse(LiveSessionEvent.ToolUse(CONVERSATION, "turn-1", "t1", "Bash", "ls"), TS_INSTANT)
                .withToolDenied("t1", denial)

        val folded =
            denied.withToolResult(
                LiveSessionEvent.ToolResult(CONVERSATION, "turn-1", "t1", isError = false, resultSummary = "late"),
            )

        val toolCall = folded.messageRow("t1")?.toolCall
        assertEquals(ToolCallStatus.Denied, toolCall?.status)
        assertEquals(denial, toolCall?.denial)
        assertEquals("late", toolCall?.output)
    }

    // ---- #812: claude's elapsed-seconds reading on an open tool row --------------------------------

    @Test
    fun reduce_useThenProgress_retainsTheLatestReadingOnOneRunningRow() {
        val rows =
            reduceHistoryPage(
                listOf(
                    entry(3, "tool_progress", toolProgressPayload("t1", 60)),
                    entry(2, "tool_progress", toolProgressPayload("t1", 30)),
                    entry(1, "tool_use", toolUsePayload("t1", name = "Bash", input = "sleep 90")),
                ),
                interactive = true,
            )

        assertEquals(listOf("t1"), rows.messageIds())
        val toolCall = rows.messageRow("t1")?.toolCall
        assertEquals(ToolCallStatus.Running, toolCall?.status)
        assertEquals(60, toolCall?.elapsedSeconds)
        assertEquals("sleep 90", toolCall?.input)
    }

    @Test
    fun withToolProgress_keepsEveryReadingVerbatimAndARepeatIsANoOp() {
        var rows =
            listOf<ThreadItem>()
                .withToolUse(LiveSessionEvent.ToolUse(CONVERSATION, "turn-1", "t1", "Bash", "ls"), TS_INSTANT)

        // Zero, negative and backwards readings are upstream values: kept as sent, never clamped.
        for (seconds in listOf(30, 0, -65, 12)) {
            rows = rows.withToolProgress(progress("t1", seconds))
            assertEquals(seconds, rows.messageRow("t1")?.toolCall?.elapsedSeconds)
        }

        assertTrue(rows.withToolProgress(progress("t1", 12)) === rows)
        assertEquals(listOf("t1"), rows.messageIds())
    }

    @Test
    fun reduce_progressAfterResult_neitherReopensNorOverwrites() {
        val rows =
            reduceHistoryPage(
                listOf(
                    entry(4, "tool_progress", toolProgressPayload("t1", 90)),
                    entry(3, "tool_result", toolResultPayload("t1", isError = false, summary = "done")),
                    entry(2, "tool_progress", toolProgressPayload("t1", 30)),
                    entry(1, "tool_use", toolUsePayload("t1", name = "Bash", input = "ls")),
                ),
                interactive = true,
            )

        val toolCall = rows.messageRow("t1")?.toolCall
        assertEquals(ToolCallStatus.Done, toolCall?.status)
        assertEquals("done", toolCall?.output)
        // Closing the call clears the reading, and the late heartbeat does not bring it back.
        assertEquals(null, toolCall?.elapsedSeconds)
    }

    @Test
    fun reduce_progressAroundDenial_clearedAndLateProgressIgnored() {
        val rows =
            reduceHistoryPage(
                listOf(
                    entry(4, "tool_progress", toolProgressPayload("t1", 90)),
                    entry(3, "tool_denied", toolDeniedPayload("t1")),
                    entry(2, "tool_progress", toolProgressPayload("t1", 30)),
                    entry(1, "tool_use", toolUsePayload("t1", name = "Bash", input = "rm x")),
                ),
                interactive = true,
            )

        val toolCall = rows.messageRow("t1")?.toolCall
        assertEquals(ToolCallStatus.Denied, toolCall?.status)
        assertEquals(null, toolCall?.elapsedSeconds)
    }

    @Test
    fun reduce_progressNamingNoRow_addsNoRow() {
        val rows =
            reduceHistoryPage(
                listOf(
                    entry(3, "tool_progress", toolProgressPayload("tX", 30)),
                    entry(2, "tool_use", toolUsePayload("t1", name = "Bash", input = "ls")),
                    // Before its own tool_use: nothing to join yet, so the use opens with no reading.
                    entry(1, "tool_progress", toolProgressPayload("t1", 5)),
                ),
                interactive = true,
            )

        assertEquals(listOf("t1"), rows.messageIds())
        assertEquals(null, rows.messageRow("t1")?.toolCall?.elapsedSeconds)
    }

    @Test
    fun reduce_malformedProgress_dropsOnlyThatEntry() {
        val rows =
            reduceHistoryPage(
                listOf(
                    // `elapsed_seconds` missing: the strict-required shape fails the decode.
                    entry(4, "tool_progress", """{"conversation_id":"$CONVERSATION","turn_id":"turn-1","tool_use_id":"t1"}"""),
                    // Not a number at all.
                    entry(
                        3,
                        "tool_progress",
                        """{"conversation_id":"$CONVERSATION","turn_id":"turn-1","tool_use_id":"t1","elapsed_seconds":"soon"}""",
                    ),
                    entry(2, "tool_progress", toolProgressPayload("t1", 30)),
                    entry(1, "tool_use", toolUsePayload("t1", name = "Bash", input = "ls")),
                ),
                interactive = true,
            )

        assertEquals(listOf("t1"), rows.messageIds())
        assertEquals(30, rows.messageRow("t1")?.toolCall?.elapsedSeconds)
    }

    @Test
    fun reduce_progressWithoutInteractive_isIgnored() {
        val rows =
            reduceHistoryPage(
                listOf(
                    entry(2, "tool_progress", toolProgressPayload("t1", 30)),
                    entry(1, "message", messagePayload("m1", "assistant", "hi")),
                ),
                interactive = false,
            )

        assertEquals(listOf("m1"), rows.messageIds())
    }

    // ---- Fixtures ---------------------------------------------------------------------------------

    private fun entry(
        id: Long,
        type: String,
        payload: String,
        ts: String = TS,
    ): HistoryEntry =
        HistoryEntry(
            id = id,
            type = type,
            payload = MobileJson.parseToJsonElement(payload),
            timestamp = Instant.parse(ts),
        )

    private fun messagePayload(
        messageId: String,
        role: String,
        text: String,
    ): String = """{"conversation_id":"$CONVERSATION","message_id":"$messageId","role":"$role","text":"$text"}"""

    private fun sendMessagePayload(
        messageId: String,
        text: String,
    ): String = """{"conversation_id":"$CONVERSATION","message_id":"$messageId","text":"$text"}"""

    private fun toolUsePayload(
        toolUseId: String,
        name: String,
        input: String,
        extra: String = "",
    ): String =
        """{"conversation_id":"$CONVERSATION","turn_id":"turn-1","tool_use_id":"$toolUseId","name":"$name","input_summary":"$input"""" +
            extraFields(extra) + "}"

    private fun toolResultPayload(
        toolUseId: String,
        isError: Boolean,
        summary: String,
        extra: String = "",
    ): String =
        """{"conversation_id":"$CONVERSATION","turn_id":"turn-1","tool_use_id":"$toolUseId",""" +
            """"is_error":$isError,"result_summary":"$summary"""" + extraFields(extra) + "}"

    private fun toolDeniedPayload(
        toolUseId: String,
        toolName: String = "Bash",
        reasonType: String = "",
        reason: String = "",
        message: String = "denied",
        truncated: String = "null",
        dropped: String = "null",
    ): String =
        """{"conversation_id":"$CONVERSATION","turn_id":"turn-1","tool_use_id":"$toolUseId","tool_name":"$toolName",""" +
            """"decision_reason_type":"$reasonType","decision_reason":"$reason","message":"$message",""" +
            """"truncated_fields":$truncated,"dropped_fields":$dropped}"""

    private fun toolProgressPayload(
        toolUseId: String,
        elapsedSeconds: Int,
    ): String = """{"conversation_id":"$CONVERSATION","turn_id":"turn-1","tool_use_id":"$toolUseId","elapsed_seconds":$elapsedSeconds}"""

    private fun progress(
        toolUseId: String,
        elapsedSeconds: Int,
    ): ToolProgressPayloadDto = ToolProgressPayloadDto(CONVERSATION, "turn-1", toolUseId, elapsedSeconds)

    /** An optional `"key":value` fragment appended to a payload; empty leaves the payload as it was. */
    private fun extraFields(extra: String): String = if (extra.isEmpty()) "" else ",$extra"

    private fun assistantDeltaPayload(
        turnId: String,
        seq: Int,
        text: String,
    ): String = """{"conversation_id":"$CONVERSATION","turn_id":"$turnId","seq":$seq,"text":"$text"}"""

    private fun turnEndPayload(turnId: String): String =
        """{"conversation_id":"$CONVERSATION","turn_id":"$turnId","stop_reason":"end_turn"}"""

    private fun sessionTransitionPayload(
        previous: String,
        new: String,
        reason: String,
        occurredAt: String = OCCURRED_AT,
    ): String =
        """{"conversation_id":"$CONVERSATION","previous_session_id":"$previous","new_session_id":"$new",""" +
            """"reason":"$reason","occurred_at":"$occurredAt"}"""

    private fun modalShownPayload(): String =
        """{"modal_id":"md","class":"permission","title":"t","prompt":"p","options":[],"default_option_id":""}"""

    private fun unrecognizedPayload(site: String): String =
        """{"conversation_id":"$CONVERSATION","site":"$site","message_type":"weird","raw":"{}","truncated":false}"""

    private fun messageItem(
        id: String,
        content: String = "x",
        role: Role = Role.User,
    ): ThreadItem.MessageItem =
        ThreadItem.MessageItem(
            Message(id = id, sessionId = "", role = role, content = content, timestamp = TS_INSTANT, isStreaming = false),
        )

    private fun List<ThreadItem>.messageIds(): List<String> = filterIsInstance<ThreadItem.MessageItem>().map { it.message.id }

    private fun List<ThreadItem>.messageRow(id: String): Message? =
        filterIsInstance<ThreadItem.MessageItem>().firstOrNull { it.message.id == id }?.message

    private companion object {
        const val CONVERSATION = "c1"
        const val TS = "2026-09-05T10:00:00Z"
        const val OCCURRED_AT = "2026-09-05T09:59:00Z"
        val TS_INSTANT: Instant = Instant.parse(TS)
    }
}
