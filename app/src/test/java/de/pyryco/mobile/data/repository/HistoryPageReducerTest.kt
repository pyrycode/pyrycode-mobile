package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.model.ToolDenial
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.ToolProgressPayloadDto
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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

    // ---- #983: a stored send's attachment_ids become references ---------------------------------

    @Test
    fun reduce_storedSendNamingAttachments_carriesOneReferencePerIdInWireOrder_withNoHints() {
        val rows =
            reduceHistoryPage(
                listOf(entry(1, "send_message", sendMessagePayload("s1", "look", ids = listOf(ID_B, ID_A)))),
                interactive = true,
            )

        assertEquals(listOf(MessageAttachment(ID_B), MessageAttachment(ID_A)), rows.messageRow("s1")?.attachments)
    }

    @Test
    fun reduce_storedSendWithEmptyTextAndAttachments_isStillAUserRow() {
        val rows =
            reduceHistoryPage(listOf(entry(1, "send_message", sendMessagePayload("s1", "", ids = listOf(ID_A)))), interactive = true)

        val row = rows.messageRow("s1")
        assertEquals(Role.User, row?.role)
        assertEquals("", row?.content)
        assertEquals(listOf(MessageAttachment(ID_A)), row?.attachments)
    }

    @Test
    fun reduce_storedSendWithANonConformingId_dropsOnlyThatId() {
        val ids = listOf(ID_A, ID_A.uppercase(), "../etc/passwd", "", ID_B, ID_A)
        val rows =
            reduceHistoryPage(listOf(entry(1, "send_message", sendMessagePayload("s1", "hi", ids = ids))), interactive = true)

        val row = rows.messageRow("s1")
        assertEquals("hi", row?.content)
        assertEquals(listOf(MessageAttachment(ID_A), MessageAttachment(ID_B)), row?.attachments)
    }

    @Test
    fun reduce_storedSendNamingMoreThanTheBound_keepsTheFirstMax() {
        val ids = (0..40).map { "00000000-0000-4000-8000-%012d".format(it) }
        val rows =
            reduceHistoryPage(listOf(entry(1, "send_message", sendMessagePayload("s1", "hi", ids = ids))), interactive = true)

        assertEquals(ids.take(32).map { MessageAttachment(it) }, rows.messageRow("s1")?.attachments)
    }

    @Test
    fun reduce_textOnlyStoredSend_hasNoReferences() {
        val plain = reduceHistoryPage(listOf(entry(1, "send_message", sendMessagePayload("s1", "hi"))), interactive = true)
        val explicitNull =
            reduceHistoryPage(
                listOf(
                    entry(1, "send_message", """{"conversation_id":"$CONVERSATION","message_id":"s1","text":"hi","attachment_ids":null}"""),
                ),
                interactive = true,
            )

        val expected = listOf(messageItem("s1", content = "hi"))
        assertEquals(expected, plain)
        assertEquals(expected, explicitNull)
    }

    // ---- #1020: a stored user message's attachment_ids become references too -----------------------

    @Test
    fun reduce_storedUserMessageNamingAttachments_carriesOneReferencePerIdInWireOrder_withNoHints() {
        val rows =
            reduceHistoryPage(
                listOf(entry(1, "message", messagePayload("m1", "user", "look", ids = listOf(ID_B, ID_A)))),
                interactive = true,
            )

        val row = rows.messageRow("m1")
        assertEquals(Role.User, row?.role)
        assertEquals("look", row?.content)
        assertEquals(listOf(MessageAttachment(ID_B), MessageAttachment(ID_A)), row?.attachments)
    }

    @Test
    fun reduce_storedUserMessageIds_areFilteredDeduplicatedAndCappedAsASend() {
        val bad = listOf(ID_A, ID_A.uppercase(), "../etc/passwd", "", ID_B, ID_A)
        val over = (0..40).map { "00000000-0000-4000-8000-%012d".format(it) }
        val rows =
            reduceHistoryPage(
                listOf(
                    entry(2, "message", messagePayload("m2", "user", "many", ids = over)),
                    entry(1, "message", messagePayload("m1", "user", "hi", ids = bad)),
                ),
                interactive = true,
            )

        assertEquals(listOf(MessageAttachment(ID_A), MessageAttachment(ID_B)), rows.messageRow("m1")?.attachments)
        assertEquals(over.take(32).map { MessageAttachment(it) }, rows.messageRow("m2")?.attachments)
    }

    @Test
    fun reduce_storedUserMessageWithoutIds_reducesAsBefore() {
        val plain = reduceHistoryPage(listOf(entry(1, "message", messagePayload("m1", "user", "hi"))), interactive = true)
        val explicitNull =
            reduceHistoryPage(
                listOf(
                    entry(
                        1,
                        "message",
                        """{"conversation_id":"$CONVERSATION","message_id":"m1","role":"user","text":"hi","attachment_ids":null}""",
                    ),
                ),
                interactive = true,
            )

        val expected = listOf(messageItem("m1", content = "hi"))
        assertEquals(expected, plain)
        assertEquals(expected, explicitNull)
    }

    @Test
    fun reduce_storedAssistantMessageNamingIds_carriesNoReferences() {
        val rows =
            reduceHistoryPage(
                listOf(entry(1, "message", messagePayload("m1", "assistant", "hi", ids = listOf(ID_A)))),
                interactive = true,
            )

        assertEquals(listOf(messageItem("m1", content = "hi", role = Role.Assistant)), rows)
    }

    @Test
    fun merge_aHistoryTwinFillsTheMissingHintsOfTheRowKept_inPlace() {
        val named = MessageAttachment(ID_A, "photo.jpg", "image/jpeg")
        val live =
            listOf(messageItem("before"), messageItem("sent-1", attachments = listOf(MessageAttachment(ID_A), MessageAttachment(ID_B))))
        val cached = listOf(messageItem("sent-1", attachments = listOf(named)), messageItem("older"))

        val merged = live.mergeHistoryRows(cached)

        assertEquals(listOf("older", "before", "sent-1"), merged.messageIds())
        assertEquals(listOf(named, MessageAttachment(ID_B)), merged.messageRow("sent-1")?.attachments)
    }

    // ---- #1353: the cache merge joins each row kind on its key alone ------------------------------

    @Test
    fun mergeCached_eachKindJoinsItsLiveTwinOnItsKeyAlone() {
        val at = { second: Int -> Instant.parse("2026-09-05T10:00:0${second}Z") }
        val live =
            listOf(
                messageItem("m1", content = "live"),
                ThreadItem.SessionBoundary("s1", "s2", BoundaryReason.Clear, at(1)),
                ThreadItem.UnrecognizedMessage("u1", UnrecognizedSite.Undecodable, "", "live", false, at(2)),
                ThreadItem.Banner(BannerLevel.Warning, "live", false, at(3)),
                ThreadItem.CompactionBoundary(24000, 3000, true, at(4)),
                ThreadItem.ModelRefusal("opus", "sonnet", "live", false, at(5)),
                ThreadItem.StoppedTurn("turn-1", "max_turns", "", at(6)),
            )
        // Each twin differs from its live row only outside the key; the last refusal is the other frame type.
        val otherType = ThreadItem.ModelRefusal("opus", null, "cached", false, at(5))
        val cached =
            listOf(
                messageItem("older"),
                messageItem("m1", content = "cached"),
                ThreadItem.SessionBoundary("s1", "s2", BoundaryReason.IdleEvict, at(1), workspaceCwd = "/w"),
                ThreadItem.UnrecognizedMessage("u1", UnrecognizedSite.Undecodable, "", "cached", true, at(2)),
                ThreadItem.Banner(BannerLevel.Info, "cached", true, at(3)),
                ThreadItem.CompactionBoundary(null, null, false, at(4)),
                ThreadItem.ModelRefusal("haiku", "sonnet-5", "cached", true, at(5)),
                ThreadItem.StoppedTurn("turn-1", "api_error", "overloaded", at(7)),
                otherType,
            )

        assertEquals(listOf(messageItem("older")) + live + otherType, live.mergeCachedRows(cached))
    }

    @Test
    fun merge_aNamelessHistoryTwinNeverReplacesAKnownName() {
        val named = MessageAttachment(ID_A, "photo.jpg", "image/jpeg")
        val echo = listOf(messageItem("sent-1", attachments = listOf(named)))
        val reduced =
            reduceHistoryPage(listOf(entry(1, "send_message", sendMessagePayload("sent-1", "x", ids = listOf(ID_A)))), interactive = true)

        val merged = echo.mergeHistoryRows(reduced)

        assertSame(echo, merged)
    }

    @Test
    fun merge_aTwinWithDifferentNamesNeverOverwritesAKnownOne() {
        val echo = listOf(messageItem("sent-1", attachments = listOf(MessageAttachment(ID_A, "mine.jpg", null))))
        val other = listOf(messageItem("sent-1", attachments = listOf(MessageAttachment(ID_A, "theirs.jpg", "image/jpeg"))))

        val merged = echo.mergeHistoryRows(other)

        assertEquals(listOf(MessageAttachment(ID_A, "mine.jpg", "image/jpeg")), merged.messageRow("sent-1")?.attachments)
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
                    entry(2, "assistant_delta", assistantDeltaPayload("turn-1", seq = 1, text = " world")),
                    entry(1, "assistant_delta", assistantDeltaPayload("turn-1", seq = 0, text = "hello")),
                ),
                interactive = true,
            )

        // Every turn's text starts at seq 0, the delta that keys its first segment by the bare turn id (#1350).
        assertEquals(listOf("turn-1"), rows.messageIds())
        assertEquals("hello world", rows.messageRow("turn-1")?.content)
        assertFalse(rows.messageRow("turn-1")?.isStreaming ?: true)
    }

    // ---- #1356: a stopped turn leaves its reason in the thread --------------------------------------

    @Test
    fun reduce_aFailedTurnEnd_addsOneStoppedRowAfterTheTurnsLastRow_stampedWithTheEntryTs() {
        val rows =
            reduceHistoryPage(
                listOf(
                    entry(4, "message", messagePayload("m2", "user", "next"), ts = "2026-09-05T10:04:00Z"),
                    entry(3, "turn_end", turnEndPayload("turn-1", failure = FAILED), ts = "2026-09-05T10:03:00Z"),
                    entry(2, "tool_use", toolUsePayload("t1", name = "Bash", input = "ls")),
                    entry(1, "assistant_delta", assistantDeltaPayload("turn-1", seq = 0, text = "hello")),
                ),
                interactive = true,
            )

        assertEquals(
            ThreadItem.StoppedTurn("turn-1", "prompt_too_long", "invalid_request", Instant.parse("2026-09-05T10:03:00Z")),
            rows[2],
        )
        assertEquals(listOf("turn-1", "t1", "m2"), rows.messageIds())
        assertEquals(4, rows.size)
    }

    @Test
    fun reduce_aCancelledOrCleanTurnEnd_addsNoRow() {
        val rows =
            reduceHistoryPage(
                listOf(
                    entry(2, "turn_end", turnEndPayload("turn-2", stopReason = "cancelled", failure = FAILED)),
                    entry(1, "turn_end", turnEndPayload("turn-1", failure = """"outcome":"success"""")),
                ),
                interactive = true,
            )

        assertTrue(rows.isEmpty())
    }

    @Test
    fun reduce_aRepeatedTurnEnd_addsOneStoppedRow() {
        val rows =
            reduceHistoryPage(
                listOf(
                    entry(2, "turn_end", turnEndPayload("turn-1", failure = FAILED), ts = "2026-09-05T10:02:00Z"),
                    entry(1, "turn_end", turnEndPayload("turn-1", failure = FAILED), ts = "2026-09-05T10:01:00Z"),
                ),
                interactive = true,
            )

        assertEquals(listOf("turn-1"), rows.filterIsInstance<ThreadItem.StoppedTurn>().map { it.turnId })
    }

    @Test
    fun merge_aHistoryStoppedRow_joinsTheLiveOneByTurnId() {
        val event =
            LiveSessionEvent.TurnEnd(CONVERSATION, "turn-1", "end_turn", isError = true, terminalReason = "prompt_too_long")
        val live = listOf(messageItem("m0")).withFinalizedTurn(event, Instant.parse("2026-09-05T11:00:00Z"))
        val page = reduceHistoryPage(listOf(entry(1, "turn_end", turnEndPayload("turn-1", failure = FAILED))), interactive = true)

        val merged = live.mergeHistoryRows(page)

        assertSame(live, merged)
        assertEquals(1, merged.count { it is ThreadItem.StoppedTurn })
    }

    @Test
    fun withFinalizedTurn_appendsTheStoppedRowOnce_andAnotherTurnsRowStill() {
        val at = Instant.parse("2026-09-05T11:00:00Z")
        val failed = LiveSessionEvent.TurnEnd(CONVERSATION, "turn-1", "end_turn", outcome = "error_max_turns")

        val once = emptyList<ThreadItem>().withFinalizedTurn(failed, at)
        val twice = once.withFinalizedTurn(failed, Instant.parse("2026-09-05T11:00:01Z"))
        val other = twice.withFinalizedTurn(failed.copy(turnId = "turn-2"), at)

        assertEquals(listOf(ThreadItem.StoppedTurn("turn-1", "max_turns", "", at)), once)
        assertSame(once, twice)
        assertEquals(listOf("turn-1", "turn-2"), other.map { (it as ThreadItem.StoppedTurn).turnId })
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

    // #1109: the Codex translator's two sites fold on history reload; any other unknown site still drops.
    @Test
    fun reduce_unrecognizedMessage_codexSitesFoldAndAnUnknownSiteDrops() {
        val rows =
            reduceHistoryPage(
                listOf(
                    entry(3, "unrecognized_message", unrecognizedPayload("wormhole")),
                    entry(2, "unrecognized_message", unrecognizedPayload("codex_item")),
                    entry(1, "unrecognized_message", unrecognizedPayload("codex_method")),
                ),
                interactive = true,
            )

        assertEquals(
            listOf(UnrecognizedSite.CodexMethod, UnrecognizedSite.CodexItem),
            rows.map { (it as ThreadItem.UnrecognizedMessage).site },
        )
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

    // ---- #873: a stored banner replays as the row the live lane drew ------------------------------

    @Test
    fun reduce_storedBanner_becomesARowStampedWithTheEntryTimestamp() {
        val rows = reduceHistoryPage(listOf(entry(5, "banner", bannerPayload("warning", "Blocked by hook", truncated = true))), true)

        val row = rows.single() as ThreadItem.Banner
        assertEquals(BannerLevel.Warning, row.level)
        assertEquals("Blocked by hook", row.text)
        assertTrue(row.truncated)
        assertEquals(TS_INSTANT, row.occurredAt)
    }

    @Test
    fun reduce_storedBannerOfAnyOtherLevel_readsAsANotice_exceptInfo() {
        val levels = listOf("info", "notice", "suggestion", "", "brand-new")
        val entries = levels.mapIndexed { i, level -> entry(i + 1L, "banner", bannerPayload(level), ts = "2026-09-05T10:0$i:00Z") }

        val rows = reduceHistoryPage(entries, true)

        // #1359: info keeps its row but reads as Info, which the thread does not draw. The page reads
        // newest first, so the oldest entry, `info`, is last.
        assertEquals(List(4) { BannerLevel.Notice } + BannerLevel.Info, rows.map { (it as ThreadItem.Banner).level })
    }

    @Test
    fun reduce_storedBannerWithoutInteractive_yieldsNothing() {
        assertEquals(emptyList<ThreadItem>(), reduceHistoryPage(listOf(entry(1, "banner", bannerPayload("warning"))), false))
    }

    @Test
    fun reduce_malformedBanner_costsOnlyThatEntry() {
        val rows =
            reduceHistoryPage(
                listOf(
                    entry(3, "message", messagePayload("m1", "user", "after")),
                    // Missing the required stops_turn.
                    entry(
                        2,
                        "banner",
                        """{"conversation_id":"$CONVERSATION","level":"warning","text":"x","truncated":false}""",
                        ts = "2026-09-05T10:02:00Z",
                    ),
                    entry(1, "banner", bannerPayload("warning"), ts = "2026-09-05T10:01:00Z"),
                ),
                true,
            )

        assertEquals(2, rows.size)
        assertEquals(Instant.parse("2026-09-05T10:01:00Z"), (rows.first() as ThreadItem.Banner).occurredAt)
    }

    @Test
    fun reduce_bannersRepeatingOneTimestamp_yieldOneRow() {
        val rows =
            reduceHistoryPage(
                listOf(entry(2, "banner", bannerPayload("notice", "second")), entry(1, "banner", bannerPayload("warning", "first"))),
                true,
            )

        assertEquals(listOf("first"), rows.map { (it as ThreadItem.Banner).text })
    }

    @Test
    fun merge_aPageWhoseBannerIsAlreadyLive_addsNoSecondRow() {
        // The live lane stamps the row with the envelope ts, which the daemon also hands the log entry.
        val live: List<ThreadItem> = listOf(ThreadItem.Banner(BannerLevel.Warning, "Blocked by hook", false, TS_INSTANT))
        val page = reduceHistoryPage(listOf(entry(1, "banner", bannerPayload("warning", "Blocked by hook"))), true)

        assertEquals(live, live.mergeHistoryRows(page))
    }

    @Test
    fun merge_bannerAtADifferentTimestamp_isAdmitted() {
        val live: List<ThreadItem> = listOf(ThreadItem.Banner(BannerLevel.Warning, "Blocked by hook", false, TS_INSTANT))
        val page = reduceHistoryPage(listOf(entry(1, "banner", bannerPayload("warning", "Blocked by hook"), ts = OCCURRED_AT)), true)

        assertEquals(page + live, live.mergeHistoryRows(page))
    }

    // ---- #875: a stored model refusal replays as the row the live lane drew ------------------------

    @Test
    fun reduce_storedRefusalsOfBothTypes_becomeRowsStampedWithTheEntryTimestamp() {
        val rows =
            reduceHistoryPage(
                listOf(
                    entry(2, "model_refusal_no_fallback", refusalPayload(null, banner = "", truncated = "null"), ts = OCCURRED_AT),
                    entry(1, "model_refusal_fallback", refusalPayload("claude-sonnet-5", truncated = """["banner"]""")),
                ),
                true,
            )

        assertEquals(
            listOf(
                ThreadItem.ModelRefusal("claude-opus-5-5", "claude-sonnet-5", "Declined.", true, TS_INSTANT),
                ThreadItem.ModelRefusal("claude-opus-5-5", null, "", false, Instant.parse(OCCURRED_AT)),
            ),
            rows,
        )
    }

    @Test
    fun reduce_storedRefusalWithoutInteractive_yieldsNothing() {
        val entries =
            listOf(
                entry(2, "model_refusal_no_fallback", refusalPayload(null), ts = OCCURRED_AT),
                entry(1, "model_refusal_fallback", refusalPayload("b")),
            )

        assertEquals(emptyList<ThreadItem>(), reduceHistoryPage(entries, false))
    }

    @Test
    fun reduce_malformedRefusal_costsOnlyThatEntry() {
        val rows =
            reduceHistoryPage(
                listOf(
                    entry(3, "message", messagePayload("m1", "user", "after")),
                    // A fallback frame stored without its fallback_model.
                    entry(2, "model_refusal_fallback", refusalPayload(null), ts = "2026-09-05T10:02:00Z"),
                    entry(1, "model_refusal_no_fallback", refusalPayload(null), ts = "2026-09-05T10:01:00Z"),
                ),
                true,
            )

        assertEquals(2, rows.size)
        assertEquals(Instant.parse("2026-09-05T10:01:00Z"), (rows.first() as ThreadItem.ModelRefusal).occurredAt)
    }

    @Test
    fun merge_aPageWhoseRefusalIsAlreadyLive_addsNoSecondRow() {
        // The live lane stamps the row with the envelope ts, which the daemon also hands the log entry.
        val live: List<ThreadItem> = listOf(ThreadItem.ModelRefusal("claude-opus-5-5", "b", "Declined.", false, TS_INSTANT))
        val page = reduceHistoryPage(listOf(entry(1, "model_refusal_fallback", refusalPayload("b"))), true)

        assertEquals(live, live.mergeHistoryRows(page))
    }

    @Test
    fun merge_theSiblingRefusalTypeAtOneTimestamp_isAdmitted() {
        val live: List<ThreadItem> = listOf(ThreadItem.ModelRefusal("claude-opus-5-5", "b", "Declined.", false, TS_INSTANT))
        val page = reduceHistoryPage(listOf(entry(1, "model_refusal_no_fallback", refusalPayload(null))), true)

        assertEquals(page + live, live.mergeHistoryRows(page))
    }

    // ---- #874: a stored compaction boundary replays as the divider the live lane drew ---------------

    @Test
    fun reduce_storedCompactionBoundary_becomesARowStampedWithTheEntryTimestamp() {
        val rows = reduceHistoryPage(listOf(entry(5, "compaction_boundary", compactionPayload())), true)

        assertEquals(listOf(ThreadItem.CompactionBoundary(24000L, 3000L, manual = true, occurredAt = TS_INSTANT)), rows)
    }

    @Test
    fun reduce_storedCompactionBoundaryWithNullOrInvalidCounts_claimsNoSize() {
        val rows =
            reduceHistoryPage(
                listOf(
                    entry(2, "compaction_boundary", compactionPayload(trigger = "", pre = "-1"), ts = "2026-09-05T10:02:00Z"),
                    entry(1, "compaction_boundary", compactionPayload(trigger = "auto", post = "null"), ts = "2026-09-05T10:01:00Z"),
                ),
                true,
            )

        assertEquals(
            listOf(Triple(24000L, null, false), Triple(null, 3000L, false)),
            rows.map { (it as ThreadItem.CompactionBoundary).let { row -> Triple(row.preTokens, row.postTokens, row.manual) } },
        )
    }

    @Test
    fun reduce_storedCompactionBoundaryWithoutInteractive_yieldsNothing() {
        assertEquals(emptyList<ThreadItem>(), reduceHistoryPage(listOf(entry(1, "compaction_boundary", compactionPayload())), false))
    }

    @Test
    fun reduce_malformedCompactionBoundary_costsOnlyThatEntry() {
        val rows =
            reduceHistoryPage(
                listOf(
                    entry(3, "message", messagePayload("m1", "user", "after")),
                    // A count that is no integer.
                    entry(2, "compaction_boundary", compactionPayload(pre = "1.5"), ts = "2026-09-05T10:02:00Z"),
                    entry(1, "compaction_boundary", compactionPayload(), ts = "2026-09-05T10:01:00Z"),
                ),
                true,
            )

        assertEquals(2, rows.size)
        assertEquals(Instant.parse("2026-09-05T10:01:00Z"), (rows.first() as ThreadItem.CompactionBoundary).occurredAt)
    }

    @Test
    fun reduce_compactionBoundariesRepeatingOneTimestamp_yieldOneRow() {
        val rows =
            reduceHistoryPage(
                listOf(
                    entry(2, "compaction_boundary", compactionPayload(pre = "2")),
                    entry(1, "compaction_boundary", compactionPayload(pre = "1")),
                ),
                true,
            )

        assertEquals(listOf(1L), rows.map { (it as ThreadItem.CompactionBoundary).preTokens })
    }

    @Test
    fun merge_aPageWhoseCompactionBoundaryIsAlreadyLive_addsNoSecondRow() {
        // The live lane stamps the row with the envelope ts, which the daemon also hands the log entry.
        val live: List<ThreadItem> = listOf(ThreadItem.CompactionBoundary(24000L, 3000L, manual = true, occurredAt = TS_INSTANT))
        val page = reduceHistoryPage(listOf(entry(1, "compaction_boundary", compactionPayload())), true)

        assertEquals(live, live.mergeHistoryRows(page))
    }

    @Test
    fun merge_compactionBoundaryAtADifferentTimestamp_isAdmitted() {
        val live: List<ThreadItem> = listOf(ThreadItem.CompactionBoundary(24000L, 3000L, manual = true, occurredAt = TS_INSTANT))
        val page = reduceHistoryPage(listOf(entry(1, "compaction_boundary", compactionPayload(), ts = OCCURRED_AT)), true)

        assertEquals(page + live, live.mergeHistoryRows(page))
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

    // ---- #1316: the result's count, and the first result and first denial win ------------------------

    @Test
    fun reduce_resultDetail_isCarriedOntoTheRow() {
        val rows =
            reduceHistoryPage(
                listOf(
                    entry(
                        2,
                        "tool_result",
                        toolResultPayload("t1", isError = false, summary = "ok", extra = """"result_detail":"265 lines""""),
                    ),
                    entry(1, "tool_use", toolUsePayload("t1", name = "Read", input = "a.kt")),
                ),
                interactive = true,
            )

        assertEquals("265 lines", rows.messageRow("t1")?.toolCall?.resultDetail)
    }

    @Test
    fun withToolResult_aSecondResultChangesNothing() {
        val first = runningRow().withToolResult(result("first", isError = false, detail = "3 lines"))

        val second = first.withToolResult(result("second", isError = true, detail = "9 lines"))

        assertSame(first, second)
        val toolCall = second.messageRow("t1")?.toolCall
        assertEquals("first", toolCall?.output)
        assertEquals(ToolCallStatus.Done, toolCall?.status)
        assertEquals("3 lines", toolCall?.resultDetail)
    }

    @Test
    fun withToolDenied_aSecondDenialChangesNothing() {
        val first = runningRow().withToolDenied("t1", denial("first"))

        val second = first.withToolDenied("t1", denial("second"))

        assertSame(first, second)
        assertEquals(denial("first"), second.messageRow("t1")?.toolCall?.denial)
    }

    @Test
    fun resultThenDenial_marksDeniedAndKeepsTheResult() {
        val rows =
            runningRow()
                .withToolResult(result("out", isError = false, detail = "2 lines"))
                .withToolDenied("t1", denial("no"))

        val toolCall = rows.messageRow("t1")?.toolCall
        assertEquals(ToolCallStatus.Denied, toolCall?.status)
        assertEquals(denial("no"), toolCall?.denial)
        assertEquals("out", toolCall?.output)
        assertEquals("2 lines", toolCall?.resultDetail)
    }

    @Test
    fun denialThenResult_fillsTheOutputAndALaterResultChangesNothing() {
        val filled =
            runningRow()
                .withToolDenied("t1", denial("no"))
                .withToolResult(result("out", isError = true, detail = ""))

        val toolCall = filled.messageRow("t1")?.toolCall
        assertEquals(ToolCallStatus.Denied, toolCall?.status)
        assertEquals("out", toolCall?.output)
        assertEquals("", toolCall?.resultDetail)

        assertSame(filled, filled.withToolResult(result("again", isError = false, detail = "5 lines")))
    }

    @Test
    fun withToolResult_onAResolvedRowWithoutAResult_changesNothing() {
        // The shape of a row restored from the disk cache: resolved, no result folded on this device.
        val restored =
            runningRow().map { item ->
                val message = (item as ThreadItem.MessageItem).message
                ThreadItem.MessageItem(message.copy(toolCall = message.toolCall?.copy(status = ToolCallStatus.Done, output = "cached")))
            }

        assertSame(restored, restored.withToolResult(result("replayed", isError = false, detail = "4 lines")))
    }

    private fun runningRow(): List<ThreadItem> =
        listOf<ThreadItem>().withToolUse(LiveSessionEvent.ToolUse(CONVERSATION, "turn-1", "t1", "Bash", "ls"), TS_INSTANT)

    private fun result(
        summary: String,
        isError: Boolean,
        detail: String,
    ) = LiveSessionEvent.ToolResult(CONVERSATION, "turn-1", "t1", isError = isError, resultSummary = summary, resultDetail = detail)

    private fun denial(message: String) = ToolDenial("Bash", "", "", message, truncatedFields = null, droppedFields = null)

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

    /**
     * A stored model refusal payload (#875): the fallback shape when [fallbackModel] is non-null, else the
     * no-fallback shape. [truncated] is a raw JSON literal for `truncated_fields`.
     */
    private fun refusalPayload(
        fallbackModel: String?,
        banner: String = "Declined.",
        truncated: String = "null",
    ): String {
        val fallback = fallbackModel?.let { ""","fallback_model":"$it","scope":"session"""" }.orEmpty()
        return """{"conversation_id":"$CONVERSATION","original_model":"claude-opus-5-5","refusal_category":"cyber",""" +
            """"banner":"$banner","truncated_fields":$truncated,"dropped_fields":null$fallback}"""
    }

    private fun messagePayload(
        messageId: String,
        role: String,
        text: String,
        ids: List<String>? = null,
    ): String = """{"conversation_id":"$CONVERSATION","message_id":"$messageId","role":"$role","text":"$text"${attachmentIdsField(ids)}}"""

    private fun attachmentIdsField(ids: List<String>?): String =
        ids?.let { list -> ""","attachment_ids":[${list.joinToString(",") { "\"$it\"" }}]""" }.orEmpty()

    private fun sendMessagePayload(
        messageId: String,
        text: String,
        ids: List<String>? = null,
    ): String = """{"conversation_id":"$CONVERSATION","message_id":"$messageId","text":"$text"${attachmentIdsField(ids)}}"""

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

    /** [failure] is a raw `"key":value` fragment of the outcome fields (#1356); empty leaves a clean turn. */
    private fun turnEndPayload(
        turnId: String,
        stopReason: String = "end_turn",
        failure: String = "",
    ): String = """{"conversation_id":"$CONVERSATION","turn_id":"$turnId","stop_reason":"$stopReason"${extraFields(failure)}}"""

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

    private fun bannerPayload(
        level: String,
        text: String = "Blocked by hook",
        truncated: Boolean = false,
    ): String = """{"conversation_id":"$CONVERSATION","level":"$level","text":"$text","truncated":$truncated,"stops_turn":true}"""

    private fun compactionPayload(
        trigger: String = "manual",
        pre: String = "24000",
        post: String = "3000",
    ): String = """{"conversation_id":"$CONVERSATION","trigger":"$trigger","pre_tokens":$pre,"post_tokens":$post}"""

    private fun messageItem(
        id: String,
        content: String = "x",
        role: Role = Role.User,
        attachments: List<MessageAttachment> = emptyList(),
    ): ThreadItem.MessageItem =
        ThreadItem.MessageItem(
            Message(
                id = id,
                sessionId = "",
                role = role,
                content = content,
                timestamp = TS_INSTANT,
                isStreaming = false,
                attachments = attachments,
            ),
        )

    private fun List<ThreadItem>.messageIds(): List<String> = filterIsInstance<ThreadItem.MessageItem>().map { it.message.id }

    private fun List<ThreadItem>.messageRow(id: String): Message? =
        filterIsInstance<ThreadItem.MessageItem>().firstOrNull { it.message.id == id }?.message

    private companion object {
        const val CONVERSATION = "c1"
        const val ID_A = "0f4c8a52-3d1e-4b7a-9c6d-2e5f8a1b3c4d"
        const val ID_B = "7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
        const val TS = "2026-09-05T10:00:00Z"
        const val OCCURRED_AT = "2026-09-05T09:59:00Z"
        const val FAILED = """"outcome":"success","is_error":true,"terminal_reason":"prompt_too_long","error_category":"invalid_request""""
        val TS_INSTANT: Instant = Instant.parse(TS)
    }
}
