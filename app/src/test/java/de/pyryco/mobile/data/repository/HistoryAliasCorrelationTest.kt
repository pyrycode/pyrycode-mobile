package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.cache.FileConversationCache
import de.pyryco.mobile.data.model.AssistantSegment
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.SegmentDelta
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.ui.conversations.thread.StreamingTurn
import de.pyryco.mobile.ui.conversations.thread.ThreadFold
import de.pyryco.mobile.ui.conversations.thread.ThreadRow
import de.pyryco.mobile.ui.conversations.thread.foldBackgroundAgentBlocks
import de.pyryco.mobile.ui.conversations.thread.foldQueuedRows
import de.pyryco.mobile.ui.conversations.thread.foldToolRuns
import de.pyryco.mobile.ui.conversations.thread.forBackgroundAgentRows
import de.pyryco.mobile.ui.conversations.thread.listKey
import de.pyryco.mobile.ui.conversations.thread.render
import de.pyryco.mobile.ui.conversations.thread.toolNestingDepths
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryAliasCorrelationTest {
    @get:Rule val tmp = TemporaryFolder()
    private val previousSink = RelayLog.sink

    @Before fun muteLogs() {
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun restoreLogs() {
        RelayLog.sink = previousSink
    }

    private val ts = Instant.parse("2026-10-08T00:00:00Z")
    private val wireId = "t#2~3"
    private val segment =
        Message(
            wireId,
            "s",
            Role.Assistant,
            "held",
            Instant.parse("2026-10-07T00:00:00Z"),
            false,
            segment = AssistantSegment("t", listOf(SegmentDelta(2, 4))),
        )

    @Test fun readCheckpointBindsAliasedLegacyAndOriginalReplayButNotChangedContent() =
        runTest {
            val projection = ThreadProjection()
            val opener = segment.copy(id = "t", segment = AssistantSegment("t", listOf(SegmentDelta(0, 4))))
            projection.appendMessages(listOf("c" to opener))
            val legacy =
                entry(
                    2u,
                    "message",
                    """{"conversation_id":"c",
            "message_id":"t",
            "role":"assistant",
            "text":"new legacy"}""",
                )
            for (entries in listOf(listOf(legacy), listOf(legacy), emptyList())) {
                projection.mergeHistoryPage("c", HistoryPage(entries, "", true), true)
                val snapshot = projection.observeSnapshot("c").first()
                val expected = Message("t#0", "", Role.Assistant, "new legacy", ts, false, reconciliationId = "t")
                assertEquals(listOf(ThreadItem.MessageItem(opener), ThreadItem.MessageItem(expected)), snapshot.rows)
                assertEquals(2uL, snapshot.readEvidence.checkpoint(snapshot.rows.last(), 0u))
                assertNull(snapshot.readEvidence.checkpoint(ThreadItem.MessageItem(expected.copy(content = "unseen")), 0u))
            }
            val changed =
                legacy.copy(
                    payload =
                        MobileJson.parseToJsonElement(
                            """{"conversation_id":"c",
            "message_id":"t",
            "role":"assistant",
            "text":"different"}""",
                        ),
                )
            projection.mergeHistoryPage("c", HistoryPage(listOf(changed.copy(unsignedId = 3u)), "", true), true)
            val snapshot = projection.observeSnapshot("c").first()
            assertEquals(2uL, snapshot.readEvidence.checkpoint(snapshot.rows.last(), 0u))
        }

    @Test fun aliasedToolProgressAndResultReachLogicalOwnerAndKeepHeldRendererKeys() =
        runTest {
            val projection = toolProjection()
            val initial = projection.observe("c").first()
            val tool = (initial.last() as ThreadItem.MessageItem).message
            projection.applyToolUse(LiveSessionEvent.ToolUse("c", "turn", wireId, "Read", "repeat"))
            assertEquals(initial, projection.observe("c").first())
            projection.applyToolProgress(
                frame(
                    "tool_progress",
                    """{"conversation_id":"c",
            "turn_id":"turn",
            "tool_use_id":"$wireId",
            "elapsed_seconds":7}""",
                ),
            )
            val progress = tool.copy(toolCall = tool.toolCall?.copy(elapsedSeconds = 7))
            assertEquals(listOf(ThreadItem.MessageItem(segment), ThreadItem.MessageItem(progress)), projection.observe("c").first())
            projection.applyToolResult(LiveSessionEvent.ToolResult("c", "turn", wireId, false, "done", resultDetail = "1 line"))
            val completed =
                tool.copy(
                    toolCall = tool.toolCall?.copy(status = ToolCallStatus.Done, output = "done", resultDetail = "1 line"),
                )
            val expected = listOf(ThreadItem.MessageItem(segment), ThreadItem.MessageItem(completed))
            assertEquals(expected, projection.observe("c").first())
            projection.mergeHistoryPage("c", HistoryPage(listOf(toolEntry()), "", true), true)
            projection.applyToolProgress(
                frame(
                    "tool_progress",
                    """{"conversation_id":"c",
            "turn_id":"turn",
            "tool_use_id":"$wireId",
            "elapsed_seconds":99}""",
                ),
            )
            assertEquals(expected, projection.observe("c").first())
        }

    @Test fun aliasedToolDenialAndItsResultRemainOnTheTool() =
        runTest {
            val projection = toolProjection()
            val denial =
                """{"conversation_id":"c","turn_id":"turn","tool_use_id":"$wireId","tool_name":"Read",
            |"decision_reason_type":"rule","decision_reason":"blocked","message":"denied"}
                """.trimMargin()
            projection.applyToolDenied(frame("tool_denied", denial))
            val denied = (projection.observe("c").first().last() as ThreadItem.MessageItem).message
            assertEquals(ToolCallStatus.Denied, denied.toolCall?.status)
            assertEquals("denied", denied.toolCall?.denial?.message)
            projection.applyToolResult(LiveSessionEvent.ToolResult("c", "turn", wireId, true, "refused"))
            val expected = denied.copy(toolCall = denied.toolCall?.copy(output = "refused", resultDetail = ""))
            assertEquals(listOf(ThreadItem.MessageItem(segment), ThreadItem.MessageItem(expected)), projection.observe("c").first())
            projection.applyToolDenied(frame("tool_denied", denial))
            assertEquals(expected, (projection.observe("c").first().last() as ThreadItem.MessageItem).message)
        }

    @Test fun aliasedUserPushAndUpdateUseLogicalIdAndQueuedKeyUsesRendererId() =
        runTest {
            val projection = ThreadProjection()
            projection.appendMessages(listOf("c" to segment))
            val userEntry =
                entry(
                    2u,
                    "message",
                    """{"conversation_id":"c",
            "message_id":"$wireId",
            "role":"user",
            "text":"user"}""",
                )
            projection.mergeHistoryPage("c", HistoryPage(listOf(userEntry), "", true), true)
            val user = Message(wireId, "", Role.User, "user", ts, false)
            val aliased = user.copy(id = "$wireId~1", reconciliationId = wireId)
            val expected = listOf(ThreadItem.MessageItem(segment), ThreadItem.MessageItem(aliased))
            assertEquals(expected, projection.observe("c").first())
            projection.appendLiveMessage("c", user)
            assertEquals(expected, projection.observe("c").first())
            projection.appendMessages(listOf("c" to user.copy(content = "updated")))
            val updated = aliased.copy(content = "updated")
            val actual = projection.observe("c").first()
            assertEquals(listOf(ThreadItem.MessageItem(segment), ThreadItem.MessageItem(updated)), actual)
            val queued = foldQueuedRows(actual, listOf(QueuedMessage(9, "queue", ts, wireId)))
            assertEquals(listOf(ThreadRow.Delivered(actual.first()), ThreadRow.Queued(9, "updated", updated.id)), queued)
            assertEquals(listOf("msg:$wireId", "msg:${updated.id}"), queued.mapIndexed { index, row -> row.listKey(index) })
        }

    @Test fun aliasedLegacyTurnEndSettlesItWithoutChangingEitherIdentity() {
        val opener = segment.copy(id = "t", segment = AssistantSegment("t", listOf(SegmentDelta(0, 4))))
        val legacy = Message("t", "s", Role.Assistant, "legacy", ts, true)
        val held = listOf(ThreadItem.MessageItem(opener)).mergeHistoryRows(listOf(ThreadItem.MessageItem(legacy)))
        val aliased = legacy.copy(id = "t#0", reconciliationId = "t")
        assertEquals(listOf(ThreadItem.MessageItem(opener), ThreadItem.MessageItem(aliased)), held)
        assertEquals(
            listOf(ThreadItem.MessageItem(opener), ThreadItem.MessageItem(aliased.copy(isStreaming = false))),
            held.withSettledTurns(setOf("t")),
        )
    }

    @Test fun restoredOrdinaryAliasesStillCorrelateWhenTheirOldRendererOwnerIsAbsent() =
        runTest {
            val projection = toolProjection()
            val aliasedTool = (projection.observe("c").first().last() as ThreadItem.MessageItem).message
            val restored = ThreadProjection()
            restored.appendMessages(listOf("c" to aliasedTool))
            restored.applyToolUse(LiveSessionEvent.ToolUse("c", "turn", wireId, "Read", "repeat"))
            assertEquals(listOf(ThreadItem.MessageItem(aliasedTool)), restored.observe("c").first())
            val user = Message("user~1", "s", Role.User, "held user", ts, false, reconciliationId = "user")
            restored.appendMessages(listOf("u" to user))
            restored.appendLiveMessage("u", user.copy(id = "user", reconciliationId = null))
            assertEquals(listOf(ThreadItem.MessageItem(user)), restored.observe("u").first())
            restored.appendMessages(listOf("u" to user.copy(id = "user", reconciliationId = null, content = "updated")))
            assertEquals(listOf(ThreadItem.MessageItem(user.copy(content = "updated"))), restored.observe("u").first())
        }

    @Test fun queuedAliasedOwnEchoIsPassedOverSuppressedAndDeliveredByLogicalId() =
        runTest {
            val projection = ThreadProjection()
            val user = Message("$wireId~1", "s", Role.User, "own", ts, false, reconciliationId = wireId)
            projection.appendMessages(listOf("c" to segment, "c" to user))
            projection.recordMinted("c", wireId)
            val queue = QueueProjection()
            queue.apply(
                frame(
                    "queue_state",
                    """{"conversation_id":"c",
            "queued":[{"queued_msg_id":9,
            "message_id":"$wireId",
            "text":"own",
            "ts":"$ts"}]}""",
                ),
            )
            projection.settleQueuedEchoes(queue) { true }
            projection.applyAssistantDelta(LiveSessionEvent.AssistantDelta("c", "t", 3, "!"))
            val extended =
                segment.copy(
                    content = "held!",
                    isStreaming = true,
                    segment = AssistantSegment("t", listOf(SegmentDelta(2, 4), SegmentDelta(3, 1))),
                )
            assertEquals(listOf(ThreadItem.MessageItem(extended), ThreadItem.MessageItem(user)), projection.observe("c").first())
            queue.apply(
                frame(
                    "queue_state",
                    """{"conversation_id":"c",
            "queued":[]}""",
                ),
            )
            projection.settleQueuedEchoes(queue) { true }
            val suppressed = projection.observeSnapshot("c").first()
            assertEquals(setOf(wireId), suppressed.suppressedUserMessageIds)
            assertEquals(listOf(ThreadItem.MessageItem(extended)), suppressed.rows)
            projection.appendLiveMessage("c", user.copy(id = wireId, reconciliationId = null), queuedMessageId = 9)
            assertEquals(
                listOf(ThreadItem.MessageItem(extended.copy(isStreaming = false)), ThreadItem.MessageItem(user)),
                projection.observe("c").first(),
            )
            projection.appendLiveMessage("c", user.copy(id = wireId, reconciliationId = null), queuedMessageId = 9)
            assertEquals(2, projection.observe("c").first().size)
        }

    @Test fun restoredAliasedUserSuppressionAppliesAtObservationAndHistoryWriteBoundaries() =
        runTest {
            val cache = FileConversationCache(tmp.newFolder(), UnconfinedTestDispatcher(testScheduler))
            val user = Message("$wireId~1", "s", Role.User, "own", ts, false, reconciliationId = wireId)
            val segmentRow = ThreadItem.MessageItem(segment)
            cache.writeThread("host", "c", listOf(segmentRow, ThreadItem.MessageItem(user))).getOrThrow()
            val delegate =
                object : ConversationRepository by FakeConversationRepository(), ThreadSnapshotSource {
                    override fun observeThreadSnapshot(conversationId: String) = flowOf(ThreadSnapshot(listOf(segmentRow), setOf(wireId)))
                }
            val repository = CachingConversationRepository(delegate, cache, "host")
            assertEquals(listOf(segmentRow), repository.observeMessages("c").first())
            repository.writeHistoryPosition("c", HistoryPosition("", false, coverage = HistoryCoverage()))
            assertEquals(listOf(segmentRow), cache.readThread("host", "c"))
        }

    @Test fun aliasedAgentParentChainsAndLifecycleGroupOnceWithRendererKeysAndReadEvidence() {
        val root =
            Message(
                wireId,
                "s",
                Role.Tool,
                "Agent",
                ts,
                false,
                toolCall =
                    ToolCall(
                        "Agent",
                        "",
                        "",
                        inputFields =
                            mapOf(
                                "run_in_background" to "true",
                            ),
                    ),
            )
        val child = root.copy(id = "child", content = "Read", toolCall = ToolCall("Read", "", "", parentToolUseId = wireId))
        val grandchild = child.copy(id = "grandchild", toolCall = child.toolCall?.copy(parentToolUseId = "child"))
        val held =
            listOf(
                ThreadItem.MessageItem(segment),
            ).mergeHistoryRows(listOf(root, child, grandchild).map { ThreadItem.MessageItem(it) })
        val aliasedRoot = root.copy(id = "$wireId~1", reconciliationId = wireId)
        assertEquals(listOf(segment, aliasedRoot, child, grandchild).map { ThreadItem.MessageItem(it) }, held)
        assertEquals(mapOf("child" to 1, "grandchild" to 2), toolNestingDepths(held))
        val launch = ThreadItem.BackgroundTaskLifecycle("task", ts, wireId, "launch", "local_agent")
        val items = held + launch
        val rows = foldBackgroundAgentBlocks(foldQueuedRows(items, emptyList()), items, null)
        assertEquals(
            listOf("msg:$wireId", "agent-start:${aliasedRoot.id}", "msg:${aliasedRoot.id}", "msg:child", "msg:grandchild"),
            rows.mapIndexed {
                index,
                row,
                ->
                row.listKey(index)
            },
        )
        val grouped = rows.filterIsInstance<ThreadRow.Delivered>().filter { it.agentBlockId != null }
        assertEquals(listOf(aliasedRoot.id, aliasedRoot.id, aliasedRoot.id), grouped.map { it.agentBlockId })
        val folded = foldToolRuns(rows, emptySet())
        assertEquals(aliasedRoot.id, ((folded[2] as ThreadRow.Delivered).item as ThreadItem.MessageItem).message.id)
        val evidence =
            ThreadReadEvidence(
                versions = mapOf(held[1] to setOf(1uL), launch to setOf(2uL)),
                facts =
                    mapOf(
                        1uL to false,
                        2uL to true,
                    ),
            )
        val projected = grouped.first().item
        assertEquals(2uL, evidence.forBackgroundAgentRows(items, rows).checkpoint(projected, 0u))
    }

    @Test fun nestedAliasedToolDepthIsIndexedByItsRendererKey() {
        val parent = Message("parent", "s", Role.Tool, "Agent", ts, false, toolCall = ToolCall("Agent", "", ""))
        val child = parent.copy(id = wireId, content = "Read", toolCall = ToolCall("Read", "", "", parentToolUseId = "parent"))
        val items =
            listOf(
                ThreadItem.MessageItem(segment),
                ThreadItem.MessageItem(parent),
            ).mergeHistoryRows(listOf(ThreadItem.MessageItem(child)))
        assertEquals(mapOf("$wireId~1" to 1), toolNestingDepths(items))
    }

    @Test fun aliasedLegacyTurnDoesNotGainADuplicateSyntheticStreamingRow() {
        val legacy = Message("t#0", "s", Role.Assistant, "legacy", ts, false, reconciliationId = "t")
        val rows = listOf(ThreadItem.MessageItem(legacy))
        val fold = ThreadFold(rows, StreamingTurn("t", "legacy", 0, false, emptySet(), ts))
        assertEquals(rows, fold.render())
    }

    private suspend fun toolProjection(): ThreadProjection {
        val projection = ThreadProjection()
        projection.appendMessages(listOf("c" to segment))
        projection.mergeHistoryPage("c", HistoryPage(listOf(toolEntry()), "", true), true)
        val tool =
            Message(
                "$wireId~1",
                "",
                Role.Tool,
                "Read",
                ts,
                false,
                toolCall = ToolCall("Read", "input", "", ToolCallStatus.Running),
                reconciliationId = wireId,
            )
        assertEquals(listOf(ThreadItem.MessageItem(segment), ThreadItem.MessageItem(tool)), projection.observe("c").first())
        return projection
    }

    private fun toolEntry() =
        entry(
            2u,
            "tool_use",
            """{"conversation_id":"c",
            "turn_id":"turn",
            "tool_use_id":"$wireId",
            "name":"Read",
            "input_summary":"input"}""",
        )

    private fun entry(
        id: ULong,
        type: String,
        payload: String,
    ) = HistoryEntry(unsignedId = id, type = type, payload = MobileJson.parseToJsonElement(payload), timestamp = ts)

    private fun frame(
        type: String,
        payload: String,
    ) = Envelope(1, type, ts.toString(), MobileJson.parseToJsonElement(payload))
}
