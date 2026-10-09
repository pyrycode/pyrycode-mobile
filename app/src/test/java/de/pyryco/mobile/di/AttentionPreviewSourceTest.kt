package de.pyryco.mobile.di

import de.pyryco.mobile.data.model.AssistantSegment
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.HostModalState
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.ModalUiState
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.Question
import de.pyryco.mobile.data.model.QuestionBatch
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.SegmentDelta
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.HistoryEntry
import de.pyryco.mobile.data.repository.HistoryPage
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.notifications.notificationPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AttentionPreviewSourceTest {
    private val oldSink = RelayLog.sink
    private val oldEnabled = RelayLog.enabled
    private val logs = mutableListOf<String>()

    @Before fun captureLogs() {
        RelayLog.enabled = true
        RelayLog.sink = { _, _, text -> logs += text }
    }

    @After fun restoreLogs() {
        RelayLog.sink = oldSink
        RelayLog.enabled = oldEnabled
        assertTrue(
            logs.none { line ->
                listOf(
                    "sensitive",
                    "gradlew",
                    "Other conversation",
                    "Recovered",
                    "First?",
                    "Second?",
                ).any { it in line }
            },
        )
    }

    @Test
    fun exactTurnLastNonblankTopLevelSegmentAndHostAreSelected() =
        withSource {
            a.page =
                page(
                    delta(1, "turn", 0, "First"),
                    tool(2),
                    delta(3, "turn", 1, "Last"),
                    tool(4),
                    delta(5, "turn", 2, "  "),
                    end(6, "turn"),
                    delta(7, "previous", 0, "Previous"),
                    end(8, "previous"),
                )
            b.page = page(delta(1, "turn", 0, "Other host"), end(2, "turn"))
            events.emit(LiveSessionEvent.TurnEnd("conv", "turn", "end_turn", historyEntryId = 6u))
            runCurrent()
            assertEquals("Last", alerts.single().preview?.invoke())
            assertEquals(1, a.asks)
            assertEquals(0, b.asks)
        }

    @Test
    fun settledLocalPrefixRequiresHistoryThroughCompletion() =
        withSource {
            a.rows.value = listOf(row("turn", "Before", 0))
            a.page = page(delta(1, "turn", 0, "Before"), tool(2), delta(3, "turn", 1, "After"), end(4, "turn"))
            events.emit(LiveSessionEvent.TurnEnd("conv", "turn", "end_turn"))
            runCurrent()
            assertEquals("After", alerts.single().preview?.invoke())
            assertEquals(1, a.asks)
        }

    @Test
    fun settledLocalPrefixFallsBackWhenHistoryCannotCertifyItsTail() =
        withSource {
            a.rows.value = listOf(row("turn", "Before", 0))
            events.emit(LiveSessionEvent.TurnEnd("conv", "turn", "end_turn"))
            runCurrent()
            assertNull(alerts.single().preview?.invoke())
            assertEquals(1, a.asks)
        }

    @Test
    fun malformedStoppedTurnBoundaryNeverAuthorizesJoinedHistoryPreview() =
        withSource {
            val malformedEnd =
                end(2, "turn").copy(
                    payload =
                        Json.parseToJsonElement(
                            """{"conversation_id":"conv","turn_id":"turn","is_error":true,"outcome":"error"}""",
                        ),
                )
            a.page = page(delta(1, "turn", 0, "Before"), malformedEnd, delta(3, "turn", 1, "After"), end(4, "turn"))
            events.emit(LiveSessionEvent.TurnEnd("conv", "turn", "end_turn", historyEntryId = 4u))
            runCurrent()
            assertNull(alerts.single().preview?.invoke())
            assertEquals(1, a.asks)
        }

    @Test
    fun validStoppedTurnBoundarySelectsOnlyTheFollowingHistorySegment() =
        withSource {
            val validEnd =
                end(2, "turn").copy(
                    payload =
                        Json.parseToJsonElement(
                            """{"conversation_id":"conv","turn_id":"turn","stop_reason":"end_turn","is_error":true,"outcome":"error"}""",
                        ),
                )
            a.page = page(delta(1, "turn", 0, "Before"), validEnd, delta(3, "turn", 1, "After"), end(4, "turn"))
            events.emit(LiveSessionEvent.TurnEnd("conv", "turn", "end_turn", historyEntryId = 4u))
            runCurrent()
            assertEquals("After", alerts.single().preview?.invoke())
            assertEquals(1, a.asks)
        }

    @Test
    fun missingTrailingDeltaAndDroppedToolSeamsNeverAuthorizeHistoryPreview() {
        val malformedTool =
            tool(2).copy(
                payload =
                    Json.parseToJsonElement(
                        """{"conversation_id":"conv","turn_id":"turn","tool_use_id":"tool","input_summary":"target"}""",
                    ),
            )
        val wrongAttribution =
            tool(2).copy(
                payload =
                    Json.parseToJsonElement(
                        tool(2).payload.toString().replace("\"conv\"", "42"),
                    ),
            )
        val malformedTail =
            delta(2, "turn", 1, "After").copy(
                payload =
                    Json.parseToJsonElement(
                        """{"conversation_id":"conv","turn_id":"turn","seq":1}""",
                    ),
            )
        listOf(
            page(delta(1, "turn", 0, "Before"), malformedTool, delta(3, "turn", 1, "After"), end(4, "turn")),
            page(delta(1, "turn", 0, "Before"), wrongAttribution, delta(3, "turn", 1, "After"), end(4, "turn")),
            page(delta(1, "turn", 0, "Before"), malformedTail, end(3, "turn")),
        ).forEach { incomplete ->
            withSource {
                a.page = incomplete
                events.emit(LiveSessionEvent.TurnEnd("conv", "turn", "end_turn"))
                runCurrent()
                assertNull(alerts.single().preview?.invoke())
                assertEquals(1, a.asks)
            }
        }
    }

    @Test
    fun previousLegacyChildAndDifferentConversationNeverSubstitute() =
        withSource {
            a.rows.value =
                listOf(
                    row(
                        "previous",
                        "Previous",
                        0,
                    ),
                    row(
                        "turn",
                        "Child",
                        0,
                        parent = "tool",
                    ),
                    ThreadItem.MessageItem(
                        Message(
                            "turn",
                            "",
                            Role.Assistant,
                            "Legacy",
                            at,
                            false,
                        ),
                    ),
                )
            a.otherRows = listOf(row("turn", "Other conversation", 0))
            events.emit(LiveSessionEvent.TurnEnd("conv", "turn", "end_turn"))
            runCurrent()
            assertNull(alerts.single().preview?.invoke())
            assertEquals(listOf("conv" to ""), a.requests)
        }

    @Test
    fun newestHistoryPageRecoversReplyWithoutMergingOrWalkingOlderPages() =
        withSource {
            a.page = page(delta(1, "turn", 0, "Recovered **reply**"), end(2, "turn"))
            events.emit(LiveSessionEvent.TurnEnd("conv", "turn", "end_turn"))
            runCurrent()
            assertEquals("Recovered **reply**", alerts.single().preview?.invoke())
            assertEquals(1, a.asks)
            assertTrue(a.rows.value.isEmpty())
        }

    @Test
    fun incompleteHistoryAndFailureUseNoPreview() {
        listOf(
            page(delta(1, "turn", 1, "Missing start"), end(2, "turn")),
            page(delta(1, "turn", 0, "Missing end")),
            page(delta(1, "old", 0, "Previous"), end(2, "old")),
            page(delta(1, "turn", 0, "Start"), delta(2, "turn", 2, "Gap"), end(3, "turn")),
            page(delta(1, "turn", 0, "Start"), end(3, "turn")),
        ).forEach { page ->
            withSource {
                a.page = page
                events.emit(LiveSessionEvent.TurnEnd("conv", "turn", "end_turn"))
                runCurrent()
                assertNull(alerts.single().preview?.invoke())
                assertEquals(1, a.asks)
            }
        }
        withSource {
            a.fail = true
            events.emit(LiveSessionEvent.TurnEnd("conv", "failure", "end_turn"))
            runCurrent()
            assertNull(alerts.single().preview?.invoke())
        }
    }

    @Test
    fun historyCannotUseAnotherConversationsDeltaOrChildAttribution() =
        withSource {
            val foreign =
                delta(1, "turn", 0, "Other conversation").copy(
                    payload =
                        Json.parseToJsonElement(
                            """
{"conversation_id":"other",
"turn_id":"turn",
"seq":0,
"text":"Other conversation"}
                            """.trimIndent(),
                        ),
                )
            a.page = page(foreign, end(2, "turn"))
            events.emit(LiveSessionEvent.TurnEnd("conv", "turn", "end_turn"))
            runCurrent()
            assertNull(alerts.single().preview?.invoke())
            a.page =
                page(
                    delta(1, "child", 0, "Child").copy(
                        payload =
                            Json.parseToJsonElement(
                                """
{"conversation_id":"conv",
"turn_id":"child",
"seq":0,
"text":"Child",
"parent_tool_use_id":"tool"}
                                """.trimIndent(),
                            ),
                    ),
                    end(2, "child"),
                )
            events.emit(LiveSessionEvent.TurnEnd("conv", "child", "end_turn"))
            runCurrent()
            assertNull(alerts.last().preview?.invoke())
        }

    @Test
    fun localSequenceGapTriggersOneHistoryAskAndTimesOutAtThreeSeconds() =
        withSource {
            a.rows.value = listOf(row("turn", "Start", 0), row("turn", "Gap", 2))
            a.wait = CompletableDeferred()
            events.emit(LiveSessionEvent.TurnEnd("conv", "turn", "end_turn"))
            runCurrent()
            val result = async { alerts.single().preview?.invoke() }
            runCurrent()
            assertEquals(1, a.asks)
            advanceTimeBy(2999)
            assertFalse(result.isCompleted)
            advanceTimeBy(1)
            runCurrent()
            assertNull(result.await())
            assertEquals(1, a.cancelled)
        }

    @Test
    fun repositoryReplacementAndHostRemovalCancelPendingLookups() =
        withSource {
            a.wait = CompletableDeferred()
            events.emit(LiveSessionEvent.TurnEnd("conv", "turn", "end_turn"))
            runCurrent()
            val result = async { alerts.single().preview?.invoke() }
            runCurrent()
            repository.value = b
            runCurrent()
            assertTrue(result.isCancelled)
            assertFalse(alerts.single().isCurrent())
            b.wait = CompletableDeferred()
            events.emit(LiveSessionEvent.TurnEnd("conv", "next", "end_turn"))
            runCurrent()
            val next = async { alerts.last().preview?.invoke() }
            runCurrent()
            connections.value = emptyList()
            runCurrent()
            assertTrue(next.isCancelled)
            assertFalse(alerts.last().isCurrent())
        }

    @Test
    fun permissionUsesDisplayTitleAndPromptAndQuestionsKeepFirstWireOrder() =
        withSource {
            modals.value =
                HostModalState(
                    listOf(
                        modal(
                            "permission",
                            "Allow Bash?",
                            "claude wants to run: ./gradlew lint",
                        ),
                    ),
                )
            runCurrent()
            assertEquals("Allow Bash? claude wants to run: ./gradlew lint", notificationPreview(alerts.last().preview?.invoke()))
            batches.value =
                listOf(
                    QuestionBatch(
                        "conv",
                        "batch",
                        listOf(
                            Question(
                                "First?",
                                "",
                                emptyList(),
                                false,
                            ),
                            Question(
                                "Second?",
                                "",
                                emptyList(),
                                false,
                            ),
                        ),
                    ),
                )
            runCurrent()
            assertEquals("First?", alerts.last().preview?.invoke())
        }

    @Test
    fun blankPermissionPromptBlankFirstQuestionAndOtherModalClassesFallback() =
        withSource {
            listOf(
                modal(
                    "permission",
                    "Allow Bash?",
                    " ",
                ),
                modal(
                    "choice",
                    "Choice",
                    "Private choice",
                ),
            ).forEachIndexed {
                index,
                modal,
                ->
                modals.value = HostModalState(listOf(modal.copy(modalId = "m$index")))
                runCurrent()
                assertNull(alerts.last().preview?.invoke())
            }
            batches.value =
                listOf(
                    QuestionBatch(
                        "conv",
                        "batch",
                        listOf(
                            Question(
                                " ",
                                "",
                                emptyList(),
                                false,
                            ),
                            Question(
                                "Second?",
                                "",
                                emptyList(),
                                false,
                            ),
                        ),
                    ),
                )
            runCurrent()
            assertNull(alerts.last().preview?.invoke())
        }

    private fun modal(
        kind: String,
        title: String,
        prompt: String,
    ) = ModalUiState.Open(
        "m",
        kind,
        title,
        prompt,
        emptyList(),
        "deny",
        "conv",
    )

    private class Repo : ConversationRepository by FakeConversationRepository() {
        val rows = MutableStateFlow<List<ThreadItem>>(emptyList())
        var otherRows = emptyList<ThreadItem>()
        var page = HistoryPage(emptyList(), "older", false)
        var fail = false
        var wait: CompletableDeferred<Unit>? = null
        var asks = 0
        var cancelled = 0
        val requests = mutableListOf<Pair<String, String>>()

        override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> =
            if (conversationId == "conv") rows else flowOf(otherRows)

        override suspend fun requestHistory(
            conversationId: String,
            cursor: String,
            limit: Int,
        ): HistoryPage {
            asks++
            requests += conversationId to cursor
            try {
                wait?.await()
                if (fail) error("sensitive daemon failure")
                return page
            } catch (e: CancellationException) {
                cancelled++
                throw e
            }
        }
    }

    private class Harness(
        val test: TestScope,
    ) {
        val a = Repo()
        val b = Repo()
        val repository = MutableStateFlow<ConversationRepository?>(a)
        val events = MutableSharedFlow<LiveSessionEvent>()
        val modals = MutableStateFlow(HostModalState())
        val batches = MutableStateFlow<List<QuestionBatch>>(emptyList())
        val connections =
            MutableStateFlow(
                listOf(
                    HostConversationConnection(
                        "host",
                        null,
                        repository,
                        MutableStateFlow(
                            ConnectionStatus(
                                RelayLinkStatus.Idle,
                                PyrycodeLinkStatus.Down,
                            ),
                        ),
                        events,
                        modals,
                        batches,
                    ),
                    HostConversationConnection(
                        "other",
                        null,
                        MutableStateFlow(b),
                        MutableStateFlow(
                            ConnectionStatus(
                                RelayLinkStatus.Idle,
                                PyrycodeLinkStatus.Down,
                            ),
                        ),
                    ),
                ),
            )
        val source =
            HostConversationSource(
                connections,
                { repository.value },
                StandardTestDispatcher(test.testScheduler),
            )
        val alerts = mutableListOf<AttentionAlert>()

        fun runCurrent() = test.runCurrent()

        fun advanceTimeBy(ms: Long) = test.advanceTimeBy(ms)

        fun <T> async(block: suspend CoroutineScope.() -> T) = test.async(block = block)
    }

    private fun withSource(block: suspend Harness.() -> Unit) =
        runTest {
            val harness = Harness(this)
            val collecting =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    harness.source.alerts.toList(harness.alerts)
                }
            try {
                runCurrent()
                harness.block()
            } finally {
                harness.source.dispose()
                collecting.cancel()
            }
        }

    private fun row(
        turn: String,
        text: String,
        seq: Int,
        parent: String = "",
    ) = ThreadItem.MessageItem(
        Message(
            "$turn#$seq",
            "",
            Role.Assistant,
            text,
            at,
            false,
            segment =
                AssistantSegment(
                    turn,
                    listOf(
                        SegmentDelta(
                            seq,
                            text.length,
                        ),
                    ),
                ),
            parentToolUseId = parent,
        ),
    )

    private fun page(vararg entries: HistoryEntry) = HistoryPage(entries.reversed(), "older", false)

    private fun delta(
        id: Long,
        turn: String,
        seq: Int,
        text: String,
    ) = HistoryEntry(
        id,
        "assistant_delta",
        Json.parseToJsonElement(
            """
{"conversation_id":"conv",
"turn_id":"$turn",
"seq":$seq,
"text":"$text"}
            """.trimIndent(),
        ),
        at,
    )

    private fun tool(id: Long) =
        HistoryEntry(
            id,
            "tool_use",
            Json.parseToJsonElement(
                """{"conversation_id":"conv","turn_id":"turn","tool_use_id":"tool$id","name":"Bash","input_summary":"target"}""",
            ),
            at,
        )

    private fun end(
        id: Long,
        turn: String,
    ) = HistoryEntry(
        id,
        "turn_end",
        Json.parseToJsonElement(
            """
{"conversation_id":"conv",
"turn_id":"$turn",
"stop_reason":"end_turn"}
            """.trimIndent(),
        ),
        at,
    )

    private companion object {
        val at = Instant.parse("2026-10-08T00:00:00Z")
    }
}
