package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import de.pyryco.mobile.data.cache.ConversationCache
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.BoundaryReason
import de.pyryco.mobile.data.repository.CachingConversationRepository
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.HistoryCoverage
import de.pyryco.mobile.data.repository.HistoryPage
import de.pyryco.mobile.data.repository.HistoryPosition
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.ThreadReadEvidence
import de.pyryco.mobile.data.repository.ThreadSnapshot
import de.pyryco.mobile.data.repository.ThreadSnapshotSource
import de.pyryco.mobile.data.repository.UnsignedHistoryGap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.CoroutineContext

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ThreadFramePacingTest {
    private val oldSink = RelayLog.sink

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun teardown() {
        Dispatchers.resetMain()
        RelayLog.sink = oldSink
    }

    @Test fun readVersionInvariant_rowsAndEvidencePublishTogether() =
        runTest {
            fun snapshot(
                text: String,
                id: ULong,
            ): ThreadSnapshot {
                val row = ThreadItem.MessageItem(Message("m", "s", Role.Assistant, text, Instant.fromEpochSeconds(1), isStreaming = false))
                return ThreadSnapshot(
                    listOf(row),
                    readEvidence = ThreadReadEvidence(versions = mapOf(row to setOf(id)), facts = mapOf(id to false)),
                )
            }
            val first = snapshot("first", 1u)
            val second = snapshot("second", 2u)
            val received = MutableStateFlow(first)
            val repository: ConversationRepository =
                object : ConversationRepository by FakeConversationRepository(), ThreadSnapshotSource {
                    override fun observeThreadSnapshot(conversationId: String) = received
                }
            val vm =
                ThreadViewModel(
                    SavedStateHandle(mapOf("conversationId" to "c")),
                    repository,
                    FakeConnectionStateSource(),
                    ComposerDraftStore(),
                    repositoryAvailable = flowOf(false),
                )
            val store = ViewModelStore().apply { put("vm", vm) }
            val seen = mutableListOf<ThreadUiState>()
            val reader = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { seen += it } }
            try {
                runCurrent()
                received.value = second
                runCurrent()
                assertTrue(
                    "new evidence must never accompany an older displayed row",
                    seen.none {
                        it.items == first.rows && it.readEvidence == second.readEvidence
                    },
                )
                assertTrue(
                    "old evidence must never accompany a newer displayed row",
                    seen.none {
                        it.items == second.rows && it.readEvidence == first.readEvidence
                    },
                )
            } finally {
                store.clear()
                reader.cancel()
            }
        }

    private class ManualFrames {
        private val ticks = Channel<Long>(Channel.UNLIMITED)
        var waiting = 0
        var nanos = 0L

        suspend fun await(): Long {
            waiting++
            return try {
                ticks.receive()
            } finally {
                waiting--
            }
        }

        fun fire(period: Long = 16_666_667L) {
            assertEquals("one frame awaiter per active destination", 1, waiting)
            nanos += period
            check(ticks.trySend(nanos).isSuccess)
        }
    }

    private class HeldWorker : CoroutineDispatcher() {
        val pending = ArrayDeque<Pair<CoroutineContext, Runnable>>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            pending.addLast(context to block)
        }

        fun drain() {
            while (pending.isNotEmpty()) pending.removeFirst().second.run()
        }
    }

    private class Harness(
        val scope: TestScope,
        initial: ThreadSnapshot = ThreadSnapshot(emptyList()),
        worker: CoroutineDispatcher = StandardTestDispatcher(scope.testScheduler),
        coverage: HistoryCoverage = HistoryCoverage(),
        cacheRows: List<ThreadItem>? = null,
    ) {
        val frames = ManualFrames()
        val snapshots = MutableStateFlow(initial)
        val events = MutableSharedFlow<LiveSessionEvent>(extraBufferCapacity = 256)
        var subscriptions = 0
        var asks = 0
        val acknowledged = mutableListOf<ULong>()
        private val source: ConversationRepository =
            object : ConversationRepository by FakeConversationRepository(), ThreadSnapshotSource {
                override fun observeThreadSnapshot(conversationId: String) = snapshots.onStart { subscriptions++ }

                override suspend fun readHistoryPosition(conversationId: String) = HistoryPosition("held", true, coverage)

                override suspend fun acknowledgeReadCheckpoint(
                    conversationId: String,
                    checkpoint: ULong,
                ) {
                    acknowledged += checkpoint
                }

                override suspend fun requestHistory(
                    conversationId: String,
                    cursor: String,
                    limit: Int,
                ): HistoryPage {
                    asks++
                    return HistoryPage(emptyList(), "", false)
                }
            }
        val repository: ConversationRepository =
            if (cacheRows == null) {
                source
            } else {
                CachingConversationRepository(
                    source,
                    object : ConversationCache {
                        override suspend fun readConversations(serverId: String) = emptyList<Conversation>()

                        override suspend fun writeConversations(
                            serverId: String,
                            conversations: List<Conversation>,
                        ) = Result.success(Unit)

                        override suspend fun removeHost(serverId: String) = Result.success(Unit)

                        override suspend fun removeConversation(
                            serverId: String,
                            conversationId: String,
                        ) = Result.success(Unit)

                        override suspend fun readThread(
                            serverId: String,
                            conversationId: String,
                        ) = cacheRows
                    },
                    "h",
                    processingDispatcher = StandardTestDispatcher(scope.testScheduler),
                )
            }
        val vm =
            ThreadViewModel(
                SavedStateHandle(mapOf("serverId" to "h", "conversationId" to "c")),
                repository,
                FakeConnectionStateSource(),
                ComposerDraftStore(),
                liveSessionEvents = events,
                repositoryAvailable = flowOf(false),
                projectionDispatcher = StandardTestDispatcher(scope.testScheduler),
                contentScheduling = ThreadContentScheduling(worker, frames::await),
            )
        private val store = ViewModelStore().apply { put("vm", vm) }
        val publications = mutableListOf<Pair<Long, List<ThreadItem>>>()
        private var previous = emptyList<ThreadItem>()
        var reader: Job? = null

        fun collect() {
            reader =
                scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
                    vm.state.collect {
                        if (it.items != previous) {
                            previous = it.items
                            publications += frames.nanos to it.items
                        }
                    }
                }
        }

        fun event(event: LiveSessionEvent) {
            check(events.tryEmit(event))
        }

        fun close() {
            store.clear()
            reader?.cancel()
        }
    }

    private fun row(
        id: String,
        text: String = id,
        second: Long = 1,
        role: Role = Role.User,
    ) = ThreadItem.MessageItem(Message(id, "s", role, text, Instant.fromEpochSeconds(second), isStreaming = false))

    private fun reading(
        row: ThreadItem,
        id: ULong,
        facts: Map<ULong, Boolean?> = mapOf(id to false),
    ) = ThreadSnapshot(listOf(row), readEvidence = ThreadReadEvidence(versions = mapOf(row to setOf(id)), facts = facts))

    @Test fun completeStateInvariant_initialAndFinalDeliveryAt60And120Hz() =
        runTest {
            for (period in listOf(16_666_667L, 8_333_333L)) {
                val initial = row("user")
                val h = Harness(this, ThreadSnapshot(listOf(initial)))
                h.collect()
                try {
                    runCurrent()
                    assertTrue(
                        h.vm.state.value.items
                            .isEmpty(),
                    )
                    advanceTimeBy(1000)
                    runCurrent()
                    assertTrue(
                        "elapsed time is not a display frame",
                        h.vm.state.value.items
                            .isEmpty(),
                    )
                    h.frames.fire(period)
                    runCurrent()
                    assertEquals(listOf(initial), h.vm.state.value.items)
                    for (seq in 1..100) {
                        h.event(LiveSessionEvent.AssistantDelta("c", "turn", seq, "$seq "))
                        runCurrent()
                    }
                    assertEquals(1, h.publications.size)
                    h.vm.onDraftChange("responsive draft")
                    h.vm.onOverflowEvent(ThreadEvent.Rename)
                    runCurrent()
                    assertEquals("responsive draft", h.vm.draft.value)
                    assertTrue(h.vm.state.value.showRenameDialog)
                    assertEquals(1, h.publications.size)
                    h.frames.fire(period)
                    runCurrent()
                    val text = (1..100).joinToString("") { "$it " }
                    assertEquals(
                        text,
                        (
                            h.vm.state.value.items
                                .last() as ThreadItem.MessageItem
                        ).message.content,
                    )
                    h.event(LiveSessionEvent.TurnEnd("c", "turn", "end_turn"))
                    runCurrent()
                    assertTrue(
                        (
                            h.vm.state.value.items
                                .last() as ThreadItem.MessageItem
                        ).message.isStreaming,
                    )
                    h.frames.fire(period)
                    runCurrent()
                    assertFalse(
                        (
                            h.vm.state.value.items
                                .last() as ThreadItem.MessageItem
                        ).message.isStreaming,
                    )
                    assertEquals(3, h.publications.size)
                    assertTrue(
                        h.publications
                            .groupBy { it.first }
                            .values
                            .all { it.size == 1 },
                    )
                    assertEquals(0, h.frames.waiting)
                    assertEquals(1, h.subscriptions)
                } finally {
                    h.close()
                    runCurrent()
                }
            }
        }

    @Test fun rawOrderInvariant_allDeltasReduceBeforeDisplay() =
        runTest {
            val held = row("held")
            val h = Harness(this, ThreadSnapshot(listOf(held)))
            h.collect()
            try {
                runCurrent()
                val inputs =
                    listOf(
                        LiveSessionEvent.AssistantDelta("c", "turn", 1, "a"),
                        LiveSessionEvent.AssistantDelta("elsewhere", "turn", 2, "foreign"),
                        LiveSessionEvent.AssistantDelta("c", "turn", 2, "b"),
                        LiveSessionEvent.AssistantDelta("c", "turn", 2, "duplicate"),
                        LiveSessionEvent.ReplayGap("c"),
                        LiveSessionEvent.AssistantDelta("c", "turn", 3, "c"),
                        LiveSessionEvent.TurnEnd("c", "turn", "end_turn"),
                    )
                var expected = ThreadFold(listOf(held), null)
                for (event in inputs) {
                    expected = expected.reduce(ThreadInput.Live(event, Instant.fromEpochSeconds(1)), "c")
                    h.event(event)
                    runCurrent()
                }
                h.frames.fire()
                runCurrent()
                val actual = h.vm.state.value.items
                assertSame(held, actual.first())
                assertEquals(
                    expected.render().map { (it as ThreadItem.MessageItem).message.content },
                    actual.map { (it as ThreadItem.MessageItem).message.content },
                )
                assertEquals("abc", (actual.last() as ThreadItem.MessageItem).message.content)
                assertFalse((actual.last() as ThreadItem.MessageItem).message.isStreaming)
                assertEquals(1, h.publications.size)
            } finally {
                h.close()
                runCurrent()
            }
        }

    @Test fun correlationInvariant_finalMessageAndTurnEndPermutations() =
        runTest {
            for (finalFirst in listOf(true, false)) {
                for (sameId in listOf(true, false)) {
                    val user = row("user")
                    val h = Harness(this, ThreadSnapshot(listOf(user)))
                    h.collect()
                    try {
                        runCurrent()
                        h.event(LiveSessionEvent.AssistantDelta("c", "turn", 1, "first "))
                        runCurrent()
                        h.event(LiveSessionEvent.AssistantDelta("c", "turn", 2, "last"))
                        runCurrent()
                        val final = row(if (sameId) "turn" else "final", "first last", role = Role.Assistant)
                        val end = LiveSessionEvent.TurnEnd("c", "turn", "end_turn")
                        if (!finalFirst) {
                            h.event(end)
                            runCurrent()
                        }
                        h.snapshots.value = ThreadSnapshot(listOf(user, final))
                        runCurrent()
                        if (finalFirst) {
                            h.event(end)
                            runCurrent()
                        }
                        h.frames.fire()
                        runCurrent()
                        assertEquals(listOf(user, final), h.vm.state.value.items)
                        assertSame(
                            final,
                            h.vm.state.value.items
                                .last(),
                        )
                        assertEquals(1, h.publications.size)
                    } finally {
                        h.close()
                        runCurrent()
                    }
                }
            }
        }

    @Test fun boundaryInvariant_skippedDisplayStillClearsOutcome() =
        runTest {
            val user = row("user")
            val h = Harness(this, ThreadSnapshot(listOf(user)))
            h.collect()
            try {
                runCurrent()
                h.event(LiveSessionEvent.TurnEnd("c", "turn", "end_turn", isError = true, errorCategory = "billing_error"))
                runCurrent()
                assertTrue(h.vm.turnOutcome.value != null)
                val boundary = ThreadItem.SessionBoundary("s", "next", BoundaryReason.Clear, Instant.fromEpochSeconds(2))
                h.snapshots.value = ThreadSnapshot(listOf(user, boundary))
                runCurrent()
                assertNull("boundary effects must not wait for display", h.vm.turnOutcome.value)
                val newer = row("new", second = 3)
                h.snapshots.value = ThreadSnapshot(listOf(user, boundary, newer))
                runCurrent()
                assertTrue(h.publications.isEmpty())
                h.frames.fire()
                runCurrent()
                assertEquals(listOf(user, boundary, newer), h.vm.state.value.items)
            } finally {
                h.close()
                runCurrent()
            }
        }

    @Test fun boundaryInvariant_heldWorkerCannotClearALaterTurnOutcome() =
        runTest {
            val user = row("user")
            val worker = HeldWorker()
            val h = Harness(this, ThreadSnapshot(listOf(user)), worker)
            h.collect()
            try {
                runCurrent()
                while (worker.pending.isNotEmpty()) {
                    worker.drain()
                    runCurrent()
                }
                h.frames.fire()
                runCurrent()
                h.event(LiveSessionEvent.TurnEnd("c", "old", "end_turn", isError = true, errorCategory = "billing_error"))
                runCurrent()
                while (worker.pending.isNotEmpty()) {
                    worker.drain()
                    runCurrent()
                }
                assertTrue(h.vm.turnOutcome.value != null)
                // Hold an earlier receipt too: the boundary must not wait behind any queued reduction.
                h.snapshots.value = ThreadSnapshot(listOf(user), readEvidence = ThreadReadEvidence(facts = mapOf(1uL to false)))
                runCurrent()
                assertTrue(worker.pending.isNotEmpty())
                val boundary = ThreadItem.SessionBoundary("s", "next", BoundaryReason.Clear, Instant.fromEpochSeconds(2))
                h.snapshots.value = ThreadSnapshot(listOf(user, boundary))
                runCurrent()
                assertTrue(worker.pending.isNotEmpty())
                assertNull("boundary effects retain their main-owned input order while worker is held", h.vm.turnOutcome.value)
                h.event(LiveSessionEvent.TurnEnd("c", "new", "end_turn", isError = true, errorCategory = "billing_error"))
                runCurrent()
                val newer = h.vm.turnOutcome.value
                assertTrue(newer != null)
                while (worker.pending.isNotEmpty()) {
                    worker.drain()
                    runCurrent()
                }
                h.frames.fire()
                runCurrent()
                assertEquals("a delayed old boundary cannot clear the new turn's outcome", newer, h.vm.turnOutcome.value)
            } finally {
                h.close()
                runCurrent()
            }
        }

    @Test fun heldMergeInvariant_reconnectAndOverlapMatchUnpacedFold() =
        runTest {
            val cacheOnly = row("cache", second = 0)
            val held = row("held")
            val boundary = ThreadItem.SessionBoundary("s", "next", BoundaryReason.Clear, Instant.fromEpochSeconds(2))
            val newest = row("new", second = 3)
            val h = Harness(this, ThreadSnapshot(listOf(held)), cacheRows = listOf(cacheOnly, held))
            h.collect()
            try {
                runCurrent()
                var expected = ThreadFold(listOf(cacheOnly, held), null)
                for (rows in listOf(emptyList(), listOf(held, boundary), listOf(held, boundary, newest))) {
                    h.snapshots.value = ThreadSnapshot(rows)
                    runCurrent()
                    val accumulated = if (rows.isEmpty()) listOf(cacheOnly, held) else listOf(cacheOnly) + rows
                    expected = expected.reduce(ThreadInput.Finished(accumulated), "c")
                }
                // A history overlap reintroduces the old cache row without duplicating or replacing held owners.
                h.snapshots.value = ThreadSnapshot(listOf(cacheOnly.copy(), held.copy(), boundary, newest))
                runCurrent()
                h.event(LiveSessionEvent.ReplayGap("c"))
                runCurrent()
                h.frames.fire()
                runCurrent()
                assertEquals(expected.render(), h.vm.state.value.items)
                listOf(cacheOnly, held, boundary, newest).zip(h.vm.state.value.items).forEach { (a, b) -> assertSame(a, b) }
                assertEquals(0, h.asks)
            } finally {
                h.close()
                runCurrent()
            }
        }

    @Test fun readVersionInvariant_pendingAndSkippedEvidenceCannotGrantSight() =
        runTest {
            val old = row("reply", "old", role = Role.Assistant)
            val newer = row("reply", "newer", role = Role.Assistant)
            val newest = row("reply", "newest", role = Role.Assistant)
            val h = Harness(this, reading(old, 1u))
            h.collect()
            try {
                runCurrent()
                h.frames.fire()
                runCurrent()
                val presented = h.vm.state.value
                assertEquals(1uL, presented.readEvidence?.checkpoint(old, 0u))
                // Nonvisual evidence arriving for unchanged text is also held for its frame.
                h.snapshots.value = reading(old, 1u, mapOf(1uL to false, 2uL to true))
                runCurrent()
                assertEquals(
                    1uL,
                    h.vm.state.value.readEvidence
                        ?.checkpoint(old, 0u),
                )
                h.snapshots.value = reading(newer, 3u, mapOf(1uL to false, 2uL to true, 3uL to false))
                runCurrent()
                h.snapshots.value = reading(newest, 4u, mapOf(1uL to false, 2uL to true, 3uL to false, 4uL to false))
                runCurrent()
                assertEquals(listOf(old), h.vm.state.value.items)
                assertNull(
                    h.vm.state.value.readEvidence
                        ?.checkpoint(newer, 0u),
                )
                assertNull(
                    h.vm.state.value.readEvidence
                        ?.checkpoint(newest, 0u),
                )
                // A previously captured sight event remains bounded to its displayed proof.
                h.vm.onOverflowEvent(ThreadEvent.NewestContentPresented(old, checkNotNull(presented.readEvidence?.checkpoint(old, 0u))))
                runCurrent()
                assertEquals(listOf(1uL), h.acknowledged)
                h.frames.fire()
                runCurrent()
                assertEquals(listOf(newest), h.vm.state.value.items)
                assertNull(
                    h.vm.state.value.readEvidence
                        ?.checkpoint(newer, 0u),
                )
                val qualified =
                    checkNotNull(
                        h.vm.state.value.readEvidence
                            ?.checkpoint(newest, 1u),
                    )
                h.vm.onOverflowEvent(ThreadEvent.NewestContentPresented(newest, qualified))
                runCurrent()
                assertEquals(listOf(1uL, 4uL), h.acknowledged)
                assertEquals(1, h.subscriptions)
                assertEquals(0, h.asks)
            } finally {
                h.close()
                runCurrent()
            }
        }

    @Test fun readBarrierInvariant_pacingRetainsReceiptBarriers() =
        runTest {
            val restored = row("restored")
            val live = row("live", second = 3)
            val gap = UnsignedHistoryGap(1u, 3u)
            for (evidence in listOf(
                ThreadReadEvidence(),
                ThreadReadEvidence(versions = mapOf(live to setOf(3u)), facts = mapOf(1uL to false, 2uL to null, 3uL to false)),
                ThreadReadEvidence(versions = mapOf(live to setOf(3u)), facts = mapOf(1uL to false, 3uL to false)),
                ThreadReadEvidence(
                    versions = mapOf(live to setOf(3u)),
                    facts = mapOf(1uL to false, 2uL to false, 3uL to false),
                    unidentified = setOf(Triple("assistant_delta", "timestamp", kotlinx.serialization.json.JsonNull)),
                ),
            )) {
                val h = Harness(this, ThreadSnapshot(listOf(restored, live), readEvidence = evidence))
                h.collect()
                try {
                    runCurrent()
                    h.frames.fire()
                    runCurrent()
                    assertNull(
                        h.vm.state.value.readEvidence
                            ?.checkpoint(restored, 0u),
                    )
                    assertNull(
                        h.vm.state.value.readEvidence
                            ?.checkpoint(live, 0u),
                    )
                } finally {
                    h.close()
                    runCurrent()
                }
            }
            val h =
                Harness(
                    this,
                    reading(live, 3u, mapOf(1uL to false, 2uL to false, 3uL to false)),
                    coverage = HistoryCoverage().copy(unsignedGaps = listOf(gap)),
                )
            h.collect()
            try {
                runCurrent()
                h.frames.fire()
                runCurrent()
                assertNull(
                    h.vm.state.value.readEvidence
                        ?.checkpoint(live, 0u),
                )
            } finally {
                h.close()
                runCurrent()
            }
        }

    @Test fun mainProgressInvariant_heldFoldPreservesInputsAndAllowsActions() =
        runTest {
            val rows = (0 until 10_000).map { row("m$it", second = it.toLong()) }
            val worker = HeldWorker()
            val h = Harness(this, ThreadSnapshot(rows), worker)
            h.collect()
            try {
                runCurrent()
                assertTrue(worker.pending.isNotEmpty())
                h.vm.onDraftChange("main progresses")
                h.vm.onOverflowEvent(ThreadEvent.Rename)
                runCurrent()
                assertEquals("main progresses", h.vm.draft.value)
                for (seq in 1..100) h.event(LiveSessionEvent.AssistantDelta("c", "turn", seq, "x"))
                runCurrent()
                assertTrue(
                    h.vm.state.value.items
                        .isEmpty(),
                )
                while (worker.pending.isNotEmpty()) {
                    worker.drain()
                    runCurrent()
                }
                h.frames.fire()
                runCurrent()
                assertTrue(h.vm.state.value.showRenameDialog)
                assertEquals(
                    rows,
                    h.vm.state.value.items
                        .dropLast(1),
                )
                assertEquals(
                    "x".repeat(100),
                    (
                        h.vm.state.value.items
                            .last() as ThreadItem.MessageItem
                    ).message.content,
                )
                h.event(LiveSessionEvent.AssistantDelta("c", "turn", 101, "late"))
                runCurrent()
                val job = checkNotNull(worker.pending.first().first[Job])
                h.close()
                runCurrent()
                assertTrue(job.isCancelled)
                worker.drain()
                runCurrent()
                assertEquals(
                    "x".repeat(100),
                    (
                        h.vm.state.value.items
                            .last() as ThreadItem.MessageItem
                    ).message.content,
                )
            } finally {
                h.close()
                runCurrent()
            }
        }

    @Test fun cleanupInvariant_cancelAndRecollectDropPendingFrames() =
        runTest {
            val initial = row("initial")
            val skipped = row("skipped", second = 2)
            val fresh = row("fresh", second = 3)
            val h = Harness(this, ThreadSnapshot(listOf(initial)))
            h.collect()
            try {
                runCurrent()
                h.frames.fire()
                runCurrent()
                h.snapshots.value = ThreadSnapshot(listOf(initial, skipped))
                runCurrent()
                assertEquals(1, h.frames.waiting)
                h.reader?.cancel()
                runCurrent()
                assertEquals(0, h.frames.waiting)
                h.snapshots.value = ThreadSnapshot(listOf(initial, fresh))
                runCurrent()
                assertEquals(listOf(initial), h.vm.state.value.items)
                repeat(2) {
                    h.collect()
                    runCurrent()
                    assertEquals(1, h.frames.waiting)
                    h.reader?.cancel()
                    runCurrent()
                    assertEquals(0, h.frames.waiting)
                }
                h.collect()
                runCurrent()
                h.frames.fire()
                runCurrent()
                assertEquals(listOf(initial, fresh), h.vm.state.value.items)
                assertEquals(4, h.subscriptions)
                assertEquals(0, h.asks)
                assertTrue(h.publications.none { skipped in it.second })
                h.snapshots.value = ThreadSnapshot(listOf(skipped))
                runCurrent()
                h.close()
                runCurrent()
                assertEquals(0, h.frames.waiting)
            } finally {
                h.close()
                runCurrent()
            }
        }

    @Test fun completeStateInvariant_finiteBurstDeliversFinalWithoutNewInput() =
        runTest {
            val frames = ManualFrames()
            val seen = mutableListOf<Int>()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    flowOf(1, 2, 3)
                        .paceThreadContent(ThreadContentScheduling(StandardTestDispatcher(testScheduler), frames::await))
                        .collect { seen += it }
                }
            runCurrent()
            assertTrue(seen.isEmpty())
            frames.fire()
            runCurrent()
            assertEquals(listOf(3), seen)
            assertTrue(job.isCompleted)
            assertEquals(0, frames.waiting)
        }
}
