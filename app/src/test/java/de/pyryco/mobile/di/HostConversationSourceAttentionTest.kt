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
import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.ConversationReadMarks
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.RemoteConversationRepository
import de.pyryco.mobile.data.repository.SessionPump
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
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
import kotlin.coroutines.CoroutineContext

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

    @Test
    fun aNewRowMarksABackgroundConversationUnreadBeforeItsTurnEndsAndGrowthOfARowDoesNot() =
        withSource { a, _, source ->
            val alerts = collectAlerts(source)
            a.rows.counts.value = mapOf("c" to 1)
            runCurrent()
            assertEquals(mapOf("a" to mapOf("c" to ConversationAttention.Unread), "b" to emptyMap()), source.attention.value)

            source.markOpened("a", "c")
            // More text in the same bubble or a tool result changes no count; another conversation's row is its own.
            a.rows.counts.value = mapOf("c" to 1, "d" to 1)
            runCurrent()
            assertEquals(mapOf("d" to ConversationAttention.Unread), source.attention.value["a"])

            a.rows.counts.value = mapOf("c" to 2, "d" to 1)
            runCurrent()
            assertEquals(mapOf("c" to ConversationAttention.Unread, "d" to ConversationAttention.Unread), source.attention.value["a"])
            // The turn-completed alert stays on `TurnEnd`.
            assertEquals(emptyList<AttentionAlert>(), alerts)
        }

    @Test
    fun aViewedConversationNeverTurnsUnreadFromItsOwnRowsAndOpeningItReadsIt() =
        withSource { a, _, source ->
            val view = viewing.view("a", "c")
            // The thread's own backfill on opening.
            a.rows.counts.value = mapOf("c" to 5)
            runCurrent()
            assertEquals(emptyMap<String, ConversationAttention>(), source.attention.value["a"])

            view.close()
            a.rows.counts.value = mapOf("c" to 6)
            runCurrent()
            assertEquals(mapOf("c" to ConversationAttention.Unread), source.attention.value["a"])

            viewing.view("a", "c")
            a.rows.counts.value = mapOf("c" to 7)
            runCurrent()
            assertEquals(emptyMap<String, ConversationAttention>(), source.attention.value["a"])
        }

    @Test
    fun rowUnreadSurvivesARestartBesideAPositionStoredBeforeIt() =
        runTest {
            val cache = MemoryCache()
            cache.positions["a"] = mapOf("legacy" to ReadPosition("t1", null), "seen" to ReadPosition("t2", "t2"))
            val first = Host("a")
            val before =
                HostConversationSource(MutableStateFlow(listOf(first.entry)), { null }, StandardTestDispatcher(testScheduler), cache)
            runCurrent()
            first.rows.counts.value = mapOf("c" to 1)
            runCurrent()
            before.dispose()

            val after =
                HostConversationSource(MutableStateFlow(listOf(Host("a").entry)), { null }, StandardTestDispatcher(testScheduler), cache)
            try {
                runCurrent()
                assertEquals(
                    mapOf("legacy" to ConversationAttention.Unread, "c" to ConversationAttention.Unread),
                    after.attention.value["a"],
                )
                after.markOpened("a", "c")
                runCurrent()
                val stored = cache.positions.getValue("a").getValue("c")
                assertEquals(stored.completedTurnId, stored.readTurnId)
            } finally {
                after.dispose()
            }
        }

    @Test
    fun aReplacedRepositoryCountsFromZeroSoReplayedRowsMarkUnreadAndBackfillDoesNot() =
        withSource { a, _, source ->
            a.rows.counts.value = mapOf("replayed" to 3, "quiet" to 2, "open" to 4)
            runCurrent()
            source.markOpened("a", "replayed")
            source.markOpened("a", "quiet")
            viewing.view("a", "open")
            runCurrent()
            assertEquals(emptyMap<String, ConversationAttention>(), source.attention.value["a"])

            a.repositories.value = null
            runCurrent()
            // The replay lands in the new repository's empty projection before the source first reads it,
            // and the open thread backfills its history again.
            val next = RowCountingRepository()
            next.counts.value = mapOf("replayed" to 1, "open" to 4)
            a.repositories.value = next
            runCurrent()

            assertEquals(mapOf("replayed" to ConversationAttention.Unread), source.attention.value["a"])
        }

    // #1452: each busy fact blinks only its own conversation on its own host, until that fact's clear edge.
    @Test
    fun eachBusyFactRunsOnlyItsConversationUntilItsOwnClearEdge() =
        withRemoteSource { a, b, source, _ ->
            val facts =
                listOf(
                    "stall" to listOf(::stall to ::liveEvent),
                    "api_retry" to listOf(::apiRetry to ::apiRetryEnd),
                    "compacting" to listOf(::compacting to ::compactingEnd),
                    "resetting" to listOf(::resetting to ::resettingEnd, ::resetting to ::sessionTransition),
                )
            facts.forEach { (fact, edges) ->
                edges.forEach { (on, off) ->
                    listOf(Triple(a, "a", "c1"), Triple(b, "b", "c2")).forEach { (pump, host, id) ->
                        pump.push(on(id))
                        runCurrent()
                        val other = if (host == "a") "b" else "a"
                        assertEquals(
                            fact,
                            mapOf(host to mapOf(id to ConversationAttention.Running), other to emptyMap()),
                            source.attention.value,
                        )

                        pump.push(off(id))
                        runCurrent()
                        // #1361: a session transition appends a boundary row, and (#1358) a compacting falling
                        // edge appends a compaction divider; either marks the background chat unread, so reading
                        // it leaves only the busy edge under test.
                        if (off(id).first == "session_transition" || fact == "compacting") source.markOpened(host, id)
                        assertEquals(
                            fact,
                            mapOf("a" to emptyMap<String, ConversationAttention>(), "b" to emptyMap()),
                            source.attention.value,
                        )
                    }
                }
            }
        }

    @Test
    fun disconnectingAHostClearsOnlyItsBusyBlinks() =
        withRemoteSource { a, b, source, hostA ->
            a.push(stall("c1"))
            a.push(compacting("c2"))
            b.push(apiRetry("c1"))
            runCurrent()
            assertEquals(
                mapOf(
                    "a" to mapOf("c1" to ConversationAttention.Running, "c2" to ConversationAttention.Running),
                    "b" to mapOf("c1" to ConversationAttention.Running),
                ),
                source.attention.value,
            )

            hostA.repositories.value = null
            runCurrent()
            assertEquals(mapOf("a" to emptyMap(), "b" to mapOf("c1" to ConversationAttention.Running)), source.attention.value)

            // A new connection's repository starts with nothing busy.
            hostA.repositories.value =
                RemoteConversationRepository(BusyPump(), backgroundScope, negotiatedCapabilities = { setOf(CAPABILITY_INTERACTIVE) })
            runCurrent()
            assertEquals(mapOf("a" to emptyMap(), "b" to mapOf("c1" to ConversationAttention.Running)), source.attention.value)
        }

    @Test
    fun daemonListAndPeerPushRecomputeWithoutOpeningAndIgnoreLocalOpen() =
        withRemoteSource { a, b, source, _ ->
            a.push(readList("same", 2u, 5u))
            b.push(readList("same", 0u, 0u))
            runCurrent()
            assertEquals(mapOf("same" to ConversationAttention.Unread), source.attention.value["a"])
            assertEquals(emptyMap<String, ConversationAttention>(), source.attention.value["b"])
            source.markOpened("a", "same")
            val view = viewing.view("a", "same")
            runCurrent()
            assertEquals(mapOf("same" to ConversationAttention.Unread), source.attention.value["a"])
            a.push(readUpdate("same", 4u))
            runCurrent()
            assertEquals(mapOf("same" to ConversationAttention.Unread), source.attention.value["a"])
            a.push(readUpdate("same", 5u))
            runCurrent()
            assertEquals(emptyMap<String, ConversationAttention>(), source.attention.value["a"])
            view.close()
            a.push(readList("same", 5u, 6u))
            runCurrent()
            assertEquals(mapOf("same" to ConversationAttention.Unread), source.attention.value["a"])
            // A late list cannot roll either fact back; the repository is the only merge owner.
            a.push(readList("same", 2u, 3u))
            a.push(readUpdate("same", 6u))
            runCurrent()
            assertEquals(emptyMap<String, ConversationAttention>(), source.attention.value["a"])
        }

    @Test
    fun receivedDurableIdentityAfterAMarkMakesUnreadAndReplayCannotUndoPeerRead() =
        withRemoteSource { a, _, source, host ->
            a.push(readList("c", 10u, 10u))
            runCurrent()
            a.push(durableMessage("c", 11u), 11u)
            runCurrent()
            assertEquals(mapOf("c" to ConversationAttention.Unread), source.attention.value["a"])
            a.push(readUpdate("c", 11u))
            runCurrent()
            assertEquals(emptyMap<String, ConversationAttention>(), source.attention.value["a"])
            repeat(2) {
                a.push(durableMessage("c", 11u), 11u)
                host.events.emit(end("c", "replayed-turn"))
                runCurrent()
                assertEquals(emptyMap<String, ConversationAttention>(), source.attention.value["a"])
            }
            a.push(durableMessage("c", 12u), 12u)
            runCurrent()
            assertEquals(mapOf("c" to ConversationAttention.Unread), source.attention.value["a"])
        }

    @Test
    fun correlatedReadReplyClearsAttentionOnlyToItsConfirmedClampedMark() =
        withRemoteSource { a, _, source, host ->
            a.push(readList("c", 0u, 10u))
            runCurrent()
            val request = async { requireNotNull(host.repositories.value).markConversationRead("c", 100u) }
            runCurrent()
            assertEquals(mapOf("c" to ConversationAttention.Unread), source.attention.value["a"])
            val command = a.sent.single { it.type == "mark_conversation_read" }
            a.push(readUpdate("c", 8u), inReplyTo = command.id)
            runCurrent()
            assertEquals(8uL, request.await().getOrThrow())
            assertEquals(mapOf("c" to ConversationAttention.Unread), source.attention.value["a"])
            a.push(readUpdate("c", 10u))
            runCurrent()
            assertEquals(emptyMap<String, ConversationAttention>(), source.attention.value["a"])
        }

    @Test
    fun sharedUnreadStillYieldsToBusyAndEveryOutstandingPermission() =
        withRemoteSource { a, _, source, host ->
            a.push(readList("c", 0u, 1u))
            a.push(stall("c"))
            runCurrent()
            assertEquals(mapOf("c" to ConversationAttention.Running), source.attention.value["a"])
            host.prompts("c" to "m")
            runCurrent()
            assertEquals(mapOf("c" to ConversationAttention.WaitingForAnswer), source.attention.value["a"])
            a.push(readUpdate("c", 1u))
            runCurrent()
            assertEquals(mapOf("c" to ConversationAttention.WaitingForAnswer), source.attention.value["a"])
            host.prompts()
            runCurrent()
            assertEquals(mapOf("c" to ConversationAttention.Running), source.attention.value["a"])
            a.push(liveEvent("c"))
            runCurrent()
            assertEquals(emptyMap<String, ConversationAttention>(), source.attention.value["a"])
        }

    @Test
    fun modernAndLegacyHostsStaySeparateAndReplacementOmittingFieldsUsesLocalFallback() =
        withRemoteSource { a, b, source, host ->
            a.push(readList("same", 0u, 9u))
            b.push(readList("same", null, null))
            b.push(durableMessage("same", 9u), 9u)
            runCurrent()
            assertEquals(mapOf("same" to ConversationAttention.Unread), source.attention.value["a"])
            assertEquals(mapOf("same" to ConversationAttention.Unread), source.attention.value["b"])
            source.markOpened("a", "same")
            source.markOpened("b", "same")
            runCurrent()
            assertEquals(mapOf("same" to ConversationAttention.Unread), source.attention.value["a"])
            assertEquals(emptyMap<String, ConversationAttention>(), source.attention.value["b"])
            host.repositories.value = null
            runCurrent()
            assertEquals(mapOf("same" to ConversationAttention.Unread), source.attention.value["a"])
            val legacy = BusyPump()
            host.repositories.value =
                RemoteConversationRepository(legacy, backgroundScope, negotiatedCapabilities = { setOf(CAPABILITY_INTERACTIVE) })
            legacy.push(readList("same", null, null))
            runCurrent()
            assertEquals(emptyMap<String, ConversationAttention>(), source.attention.value["a"])
            legacy.push(durableMessage("same", 9u), 9u)
            runCurrent()
            assertEquals(mapOf("same" to ConversationAttention.Unread), source.attention.value["a"])
            // Late callbacks from the replaced modern repository cannot re-negotiate support.
            a.push(readUpdate("same", 9u))
            runCurrent()
            source.markOpened("a", "same")
            runCurrent()
            assertEquals(emptyMap<String, ConversationAttention>(), source.attention.value["a"])
        }

    @Test
    fun replacementReplayRowsBeforeReadCollectorEstablishLegacyUnread() {
        val dispatcher = ReorderingDispatcher()
        val host = Host("a")
        host.rows.marks.value = mapOf("same" to ConversationReadMarks(9u, 9u))
        val source = HostConversationSource(MutableStateFlow(listOf(host.entry)), { null }, dispatcher)
        try {
            dispatcher.drain()
            val legacy = RowCountingRepository()
            legacy.counts.value = mapOf("same" to 1)
            host.repositories.value = legacy
            // Run replacement consumers in reverse order: replay rows precede the read collector.
            dispatcher.drainNewestFirst()
            assertEquals(mapOf("same" to ConversationAttention.Unread), source.attention.value["a"])
            source.markOpened("a", "same")
            dispatcher.drain()
            assertEquals(emptyMap<String, ConversationAttention>(), source.attention.value["a"])
        } finally {
            source.dispose()
            dispatcher.drain()
        }
    }

    @Test
    fun replacementCompletionBeforeRepositoryCollectorsEstablishesLegacyUnreadOnce() {
        val dispatcher = ReorderingDispatcher()
        val host = Host("a")
        host.rows.marks.value = mapOf("same" to ConversationReadMarks(9u, 9u))
        val source = HostConversationSource(MutableStateFlow(listOf(host.entry)), { null }, dispatcher)
        try {
            dispatcher.drain()
            host.repositories.value = RowCountingRepository()
            assertTrue(host.events.tryEmit(end("same", "legacy-turn")))
            // The live event was queued last; consume it before any repository replacement collector.
            dispatcher.runNewest()
            assertEquals(mapOf("same" to ConversationAttention.Unread), source.attention.value["a"])
            dispatcher.drain()
            source.markOpened("a", "same")
            assertTrue(host.events.tryEmit(end("same", "legacy-turn")))
            dispatcher.drain()
            assertEquals(emptyMap<String, ConversationAttention>(), source.attention.value["a"])
        } finally {
            source.dispose()
            dispatcher.drain()
        }
    }

    @Test
    fun modernFactsOverrideRestoredTokensAndLocalActivityDoesNotWriteFallback() =
        runTest {
            val cache = MemoryCache()
            val stored = mapOf("same" to ReadPosition("old-local", null))
            cache.positions["a"] = stored
            cache.positions["b"] = stored
            val modern = Host("a")
            val legacy = Host("b")
            modern.rows.marks.value = mapOf("same" to ConversationReadMarks(7u, 7u))
            val source =
                HostConversationSource(MutableStateFlow(listOf(modern.entry, legacy.entry)), {
                    null
                }, StandardTestDispatcher(testScheduler), cache, viewing = viewing)
            try {
                runCurrent()
                assertEquals(emptyMap<String, ConversationAttention>(), source.attention.value["a"])
                assertEquals(mapOf("same" to ConversationAttention.Unread), source.attention.value["b"])
                source.markOpened("a", "same")
                modern.rows.counts.value = mapOf("same" to 1)
                modern.events.emit(end("same", "modern-turn"))
                runCurrent()
                assertEquals(stored, cache.positions["a"])
                assertEquals(emptyMap<String, ConversationAttention>(), source.attention.value["a"])
                source.markOpened("b", "same")
                runCurrent()
                assertEquals(ReadPosition("old-local", "old-local"), cache.positions.getValue("b")["same"])
                // A replacement omitting fields revives only that host's saved legacy fallback.
                modern.repositories.value = RowCountingRepository()
                runCurrent()
                assertEquals(mapOf("same" to ConversationAttention.Unread), source.attention.value["a"])
                source.markOpened("a", "same")
                runCurrent()
                assertEquals(ReadPosition("old-local", "old-local"), cache.positions.getValue("a")["same"])
            } finally {
                source.dispose()
            }
        }

    private fun readList(
        id: String,
        read: ULong?,
        latest: ULong?,
    ): Pair<String, String> {
        val fields = listOfNotNull(read?.let { "\"read_up_to\":$it" }, latest?.let { "\"latest_entry_id\":$it" })
        val extra = if (fields.isEmpty()) "" else "," + fields.joinToString(",")
        return "conversations" to
            """{"conversations":[{"id":"$id","name":"test","is_promoted":true,"cwd":"/test","last_message_ts":"$TS","last_used_at":"$TS"$extra}]}"""
    }

    private fun readUpdate(
        id: String,
        read: ULong,
    ) = "conversation_updated" to
        """{"id":"$id","name":"test","is_promoted":true,"cwd":"/test","last_used_at":"$TS","read_up_to":$read}"""

    private fun durableMessage(
        id: String,
        durableId: ULong,
    ) = "message" to
        """{"conversation_id":"$id","message_id":"m$durableId","role":"user","text":"received"}"""

    /** Two hosts, each over a real [RemoteConversationRepository] whose frames the pumps deliver; host `a` last. */
    private fun withRemoteSource(block: suspend TestScope.(BusyPump, BusyPump, HostConversationSource, Host) -> Unit) =
        runTest {
            val pumps = listOf(BusyPump(), BusyPump())
            val hosts = listOf(Host("a"), Host("b"))
            hosts.zip(pumps).forEach { (host, pump) ->
                host.repositories.value =
                    RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf(CAPABILITY_INTERACTIVE) })
            }
            val source =
                HostConversationSource(
                    MutableStateFlow(hosts.map { it.entry }),
                    { null },
                    StandardTestDispatcher(testScheduler),
                    viewing = viewing,
                )
            try {
                runCurrent()
                block(pumps[0], pumps[1], source, hosts[0])
            } finally {
                source.dispose()
            }
        }

    /** Channel-backed inbound surface: unlimited buffer so a frame pushed before collection survives. */
    private class BusyPump : SessionPump {
        private val inboundChannel = Channel<Envelope>(Channel.UNLIMITED)
        private var nextId = 1L

        override val inbound: Flow<Envelope> = inboundChannel.receiveAsFlow()

        val sent = mutableListOf<Envelope>()

        override fun send(envelope: Envelope): Boolean {
            sent += envelope
            return true
        }

        fun push(
            frame: Pair<String, String>,
            durableId: ULong? = null,
            inReplyTo: Long? = null,
        ) {
            inboundChannel.trySend(
                Envelope(
                    id = nextId++,
                    type = frame.first,
                    ts = TS,
                    payload = MobileJson.parseToJsonElement(frame.second),
                    historyEntryId = durableId,
                    inReplyTo = inReplyTo,
                ),
            )
        }
    }

    private fun stall(id: String) = "stall" to """{"conversation_id":"$id"}"""

    private fun liveEvent(id: String) = "turn_state" to """{"conversation_id":"$id","state":"idle"}"""

    private fun apiRetry(id: String) = "api_retry" to """{"conversation_id":"$id","active":true,"current":2,"total":10}"""

    private fun apiRetryEnd(id: String) = "api_retry" to """{"conversation_id":"$id","active":false,"current":2,"total":10}"""

    private fun compacting(id: String) = "compacting" to """{"conversation_id":"$id","active":true}"""

    private fun compactingEnd(id: String) = "compacting" to """{"conversation_id":"$id","active":false}"""

    private fun resetting(id: String) =
        "resetting" to """{"conversation_id":"$id","active":true,"phase":"wrapping_up","handoff":"pending"}"""

    private fun resettingEnd(id: String) = "resetting" to """{"conversation_id":"$id","active":false,"phase":"","handoff":""}"""

    private fun sessionTransition(id: String) =
        "session_transition" to
            """{"conversation_id":"$id","previous_session_id":"s1","new_session_id":"s2","reason":"clear","occurred_at":"$TS","workspace_cwd":null}"""

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
        val rows = RowCountingRepository()
        val repositories = MutableStateFlow<ConversationRepository?>(rows)
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

    /** A connection's repository whose thread row counts the test sets directly (#1361). */
    private class RowCountingRepository : ConversationRepository by FakeConversationRepository() {
        val counts = MutableStateFlow<Map<String, Int>>(emptyMap())
        val marks = MutableStateFlow<Map<String, ConversationReadMarks>>(emptyMap())

        override fun observeHostReadMarks() = marks

        override fun observeThreadRowCounts() = counts
    }

    /** Forces an actual consumer-before-reset ordering without timing or a scheduler race. */
    private class ReorderingDispatcher : CoroutineDispatcher() {
        private val queued = mutableListOf<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            queued += block
        }

        fun runNewest() = queued.removeAt(queued.lastIndex).run()

        fun drainNewestFirst() {
            while (queued.isNotEmpty()) runNewest()
        }

        fun drain() {
            while (queued.isNotEmpty()) queued.removeAt(0).run()
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
        const val TS = "2026-10-02T10:00:00Z"
    }
}
