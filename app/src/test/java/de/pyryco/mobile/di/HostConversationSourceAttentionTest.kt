package de.pyryco.mobile.di

import de.pyryco.mobile.data.cache.ConversationCache
import de.pyryco.mobile.data.cache.ReadPosition
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.HostModalState
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.ModalUiState
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.QuestionBatch
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The per-host attention plumbing (#877): each host's own events, prompts, read marks and legs. */
@OptIn(ExperimentalCoroutinesApi::class)
class HostConversationSourceAttentionTest {
    private val oldSink = RelayLog.sink
    private val oldEnabled = RelayLog.enabled
    private val logs = mutableListOf<String>()

    @Before
    fun captureLogs() {
        RelayLog.enabled = true
        RelayLog.sink = { _, _, text -> logs += text }
    }

    @After
    fun restoreLogs() {
        RelayLog.sink = oldSink
        RelayLog.enabled = oldEnabled
    }

    @Test
    fun twoHostsSharingAConversationIdKeepSeparateStateAndOpeningTouchesOneHost() =
        withSource { a, b, source ->
            a.events.emit(end("same", "t1"))
            b.events.emit(end("same", "t1"))
            b.events.emit(LiveSessionEvent.TurnState("same", LiveSessionEvent.TurnState.Phase.Thinking))
            runCurrent()
            assertEquals(
                mapOf("a" to mapOf("same" to ConversationAttention.Unread), "b" to mapOf("same" to ConversationAttention.Running)),
                source.attention.value,
            )

            source.markOpened("a", "same")
            b.events.emit(LiveSessionEvent.TurnState("same", LiveSessionEvent.TurnState.Phase.Idle))
            runCurrent()
            assertEquals(mapOf("a" to emptyMap(), "b" to mapOf("same" to ConversationAttention.Unread)), source.attention.value)
            assertTrue(logs.none { it.contains("same") })
        }

    @Test
    fun oneHostLosingItsConnectionStopsOnlyItsRunningAndKeepsBothLegs() =
        withSource { a, b, source ->
            a.events.emit(LiveSessionEvent.TurnState("c", LiveSessionEvent.TurnState.Phase.Responding))
            b.events.emit(LiveSessionEvent.TurnState("c", LiveSessionEvent.TurnState.Phase.Thinking))
            a.status.value = LIVE
            b.status.value = LIVE
            runCurrent()

            a.repositories.value = null
            a.status.value = ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Down)
            runCurrent()

            assertEquals(mapOf("a" to emptyMap(), "b" to mapOf("c" to ConversationAttention.Running)), source.attention.value)
            val (hostA, hostB) = source.snapshots.value
            assertEquals(RelayLinkStatus.Connected, hostA.connectionStatus.relay)
            assertEquals(PyrycodeLinkStatus.Down, hostA.connectionStatus.pyrycode)
            assertEquals(LIVE, hostB.connectionStatus)
        }

    @Test
    fun aTurnReDeliveredAfterAReconnectMarksNothing() =
        withSource { a, _, source ->
            a.events.emit(end("c", "t1", isError = true))
            runCurrent()
            assertEquals(mapOf("c" to ConversationAttention.Unread), source.attention.value["a"])
            source.markOpened("a", "c")

            a.repositories.value = null
            runCurrent()
            a.repositories.value = FakeConversationRepository()
            a.events.emit(end("c", "t1", isError = true))
            runCurrent()

            assertEquals(emptyMap<String, ConversationAttention>(), source.attention.value["a"])
        }

    @Test
    fun aViewedConversationIsNotMarkedUnreadUntilItsLastViewCloses() =
        withSource { a, b, source ->
            a.events.emit(end("c", "t1"))
            b.events.emit(end("c", "t1"))
            runCurrent()
            val first = viewing.view("a", "c")
            val second = viewing.view("a", "c")
            runCurrent()
            // Viewing opens the conversation on its own host only.
            assertEquals(mapOf("a" to emptyMap(), "b" to mapOf("c" to ConversationAttention.Unread)), source.attention.value)

            a.events.emit(end("c", "t2"))
            first.close()
            first.close()
            a.events.emit(end("c", "t3"))
            runCurrent()
            assertEquals(emptyMap<String, ConversationAttention>(), source.attention.value["a"])

            second.close()
            a.events.emit(end("c", "t4"))
            runCurrent()
            assertEquals(mapOf("c" to ConversationAttention.Unread), source.attention.value["a"])
        }

    @Test
    fun aPromptOrQuestionForTheConversationWaitsForAnswer() =
        withSource { a, b, source ->
            a.prompts("c" to "m")
            b.prompts("" to "m")
            b.batches.value = listOf(QuestionBatch("q", "batch", emptyList()))
            runCurrent()
            assertEquals(
                mapOf(
                    "a" to mapOf("c" to ConversationAttention.WaitingForAnswer),
                    "b" to mapOf("q" to ConversationAttention.WaitingForAnswer),
                ),
                source.attention.value,
            )

            a.prompts()
            b.batches.value = emptyList()
            runCurrent()
            assertEquals(mapOf("a" to emptyMap<String, ConversationAttention>(), "b" to emptyMap()), source.attention.value)
        }

    // #1338: a second prompt is never hidden behind the first, in the list or in the alerts.
    @Test
    fun everyChatHoldingAPromptWaitsAndAlertsOnce_andAnsweringOneLeavesTheOther() =
        withSource { a, _, source ->
            val alerts = collectAlerts(source)
            a.prompts("chat-a" to "m1")
            runCurrent()
            a.prompts("chat-a" to "m1", "chat-b" to "m2")
            runCurrent()
            a.prompts("chat-a" to "m1", "chat-b" to "m2")
            runCurrent()
            assertEquals(
                mapOf("chat-a" to ConversationAttention.WaitingForAnswer, "chat-b" to ConversationAttention.WaitingForAnswer),
                source.attention.value["a"],
            )
            assertEquals(
                listOf(
                    AttentionAlert("a", "chat-a", AttentionAlert.Kind.Prompt, "modal:m1"),
                    AttentionAlert("a", "chat-b", AttentionAlert.Kind.Prompt, "modal:m2"),
                ),
                alerts,
            )

            a.prompts("chat-b" to "m2")
            runCurrent()
            assertEquals(mapOf("chat-b" to ConversationAttention.WaitingForAnswer), source.attention.value["a"])
            assertEquals(2, alerts.size)
        }

    @Test
    fun readPositionsSurviveARestartAndAnUnknownConversationStartsRead() =
        runTest {
            val cache = MemoryCache()
            val first = Host("a")
            val before =
                HostConversationSource(MutableStateFlow(listOf(first.entry)), { null }, StandardTestDispatcher(testScheduler), cache)
            runCurrent()
            first.events.emit(end("unread", "t1"))
            first.events.emit(end("read", "t1"))
            runCurrent()
            before.markOpened("a", "read")
            runCurrent()
            before.dispose()
            assertEquals(ReadPosition("t1", null), cache.positions.getValue("a")["unread"])

            val second = Host("a")
            val after =
                HostConversationSource(MutableStateFlow(listOf(second.entry)), { null }, StandardTestDispatcher(testScheduler), cache)
            try {
                runCurrent()
                assertEquals(mapOf("unread" to ConversationAttention.Unread), after.attention.value["a"])
                // The daemon re-sends the read turn to a cold process: it stays read.
                second.events.emit(end("read", "t1"))
                runCurrent()
                assertEquals(mapOf("unread" to ConversationAttention.Unread), after.attention.value["a"])
                after.markOpened("a", "unread")
                runCurrent()
                assertEquals(ReadPosition("t1", "t1"), cache.positions.getValue("a")["unread"])
            } finally {
                after.dispose()
            }
        }

    @Test
    fun aNewlyCompletedTurnAlertsOnceEvenWhileViewedAndARedeliveryAlertsNothing() =
        withSource { a, _, source ->
            val alerts = collectAlerts(source)
            viewing.view("a", "c")
            a.events.emit(end("c", "t1"))
            a.events.emit(end("c", "t1"))
            a.events.emit(end("c", ""))
            a.events.emit(LiveSessionEvent.TurnState("c", LiveSessionEvent.TurnState.Phase.Thinking))
            runCurrent()

            assertEquals(listOf(AttentionAlert("a", "c", AttentionAlert.Kind.TurnCompleted, "t1")), alerts)
        }

    @Test
    fun theSameConversationIdOnTwoHostsGivesTwoAlerts() =
        withSource { a, b, source ->
            val alerts = collectAlerts(source)
            a.events.emit(end("same", "t1"))
            b.events.emit(end("same", "t1"))
            runCurrent()

            assertEquals(listOf("a", "b"), alerts.map { it.serverId }.sorted())
            assertTrue(alerts.all { it.conversationId == "same" && it.key == "t1" })
        }

    @Test
    fun aPromptAlertsOncePerModalOrBatchAndABlankConversationPromptAlertsNothing() =
        withSource { a, b, source ->
            val alerts = collectAlerts(source)
            val open = ModalUiState.Open("m1", "permission", "t", "p", emptyList(), "deny", "c")
            a.modals.value = HostModalState(listOf(open))
            runCurrent()
            a.modals.value = HostModalState(listOf(open.copy(prompt = "re-shown")))
            a.batches.value = listOf(QuestionBatch("c", "q1", emptyList()))
            b.modals.value = HostModalState(listOf(open.copy(conversationId = "")))
            b.batches.value = listOf(QuestionBatch(" ", "q0", emptyList()))
            runCurrent()
            a.batches.value = listOf(QuestionBatch("c", "q1", emptyList()), QuestionBatch("d", "q2", emptyList()))
            runCurrent()

            assertEquals(
                listOf(
                    AttentionAlert("a", "c", AttentionAlert.Kind.Prompt, "modal:m1"),
                    AttentionAlert("a", "c", AttentionAlert.Kind.Prompt, "batch:q1"),
                    AttentionAlert("a", "d", AttentionAlert.Kind.Prompt, "batch:q2"),
                ),
                alerts,
            )
        }

    private fun TestScope.collectAlerts(source: HostConversationSource): List<AttentionAlert> {
        val alerts = mutableListOf<AttentionAlert>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.alerts.toList(alerts) }
        return alerts
    }

    private val viewing = ConversationViewing()

    private fun withSource(block: suspend TestScope.(Host, Host, HostConversationSource) -> Unit) =
        runTest {
            val a = Host("a")
            val b = Host("b")
            val source =
                HostConversationSource(
                    MutableStateFlow(listOf(a.entry, b.entry)),
                    { null },
                    StandardTestDispatcher(testScheduler),
                    viewing = viewing,
                )
            try {
                runCurrent()
                block(a, b, source)
            } finally {
                source.dispose()
            }
        }

    private class Host(
        id: String,
    ) {
        val repositories = MutableStateFlow<ConversationRepository?>(FakeConversationRepository())
        val status = MutableStateFlow(ConnectionStatus(RelayLinkStatus.Idle, PyrycodeLinkStatus.Down))
        val events = MutableSharedFlow<LiveSessionEvent>(extraBufferCapacity = 16)
        val modals = MutableStateFlow(HostModalState())
        val batches = MutableStateFlow<List<QuestionBatch>>(emptyList())
        val entry = HostConversationConnection(id, null, repositories, status, events, modals, batches)

        /** Holds one permission prompt per (conversationId, modalId) pair, in order. */
        fun prompts(vararg held: Pair<String, String>) {
            modals.value =
                HostModalState(
                    held.map { (conversationId, modalId) ->
                        ModalUiState.Open(modalId, "permission", "t", "p", emptyList(), "deny", conversationId)
                    },
                )
        }
    }

    private class MemoryCache : ConversationCache {
        val positions = mutableMapOf<String, Map<String, ReadPosition>>()

        override suspend fun readConversations(serverId: String) = emptyList<Conversation>()

        override suspend fun writeConversations(
            serverId: String,
            conversations: List<Conversation>,
        ) = Result.success(Unit)

        override suspend fun readReadPositions(serverId: String) = positions[serverId].orEmpty()

        override suspend fun writeReadPositions(
            serverId: String,
            positions: Map<String, ReadPosition>,
        ): Result<Unit> {
            this.positions[serverId] = positions
            return Result.success(Unit)
        }

        override suspend fun removeHost(serverId: String) = Result.success(Unit)

        override suspend fun removeConversation(
            serverId: String,
            conversationId: String,
        ) = Result.success(Unit)
    }

    private fun end(
        id: String,
        turnId: String,
        isError: Boolean = false,
    ) = LiveSessionEvent.TurnEnd(id, turnId, "end_turn", isError = isError)

    private companion object {
        val LIVE = ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected)
    }
}
