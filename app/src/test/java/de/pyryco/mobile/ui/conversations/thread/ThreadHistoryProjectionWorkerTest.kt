package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.HistoryCoverage
import de.pyryco.mobile.data.repository.HistoryPage
import de.pyryco.mobile.data.repository.HistoryPosition
import de.pyryco.mobile.data.repository.QueuedMessage
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.historyKeys
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.CoroutineContext

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ThreadHistoryProjectionWorkerTest {
    private val oldSink = RelayLog.sink

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun teardown() {
        Dispatchers.resetMain()
        RelayLog.sink = oldSink
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

    @Test fun leadingLifecycleInvariant_oldestAndInternalMarkersReachRenderedDeliveredRows() =
        runTest {
            val (coverage, rows) = fragmentedHistoryFixture(listOf(9000, 9001, 17000, 17999))
            val held = listOf(ThreadItem.BackgroundTaskLifecycle("task", Instant.fromEpochSeconds(0))) + rows
            withProjection(held, coverage) { vm, _ ->
                assertRenderedProjection(vm.state.value, listOf(35998uL to rows[0], 36002uL to rows[1]))
            }
        }

    @Test fun queuedEligibilityInvariant_oldestPositionAndBothAdjacentSpansUseOnlyDeliveredRows() =
        runTest {
            val (coverage, rows) = fragmentedHistoryFixture(listOf(9000, 9001, 17000, 17999))
            val cases =
                listOf(
                    listOf(0) to listOf(36002uL to rows[1]),
                    listOf(1) to listOf(35998uL to rows[0]),
                    listOf(0, 1) to listOf(67998uL to rows[2]),
                    rows.indices.toList() to emptyList(),
                )
            for ((queuedIndices, expected) in cases) {
                withProjection(rows, coverage, queuedIndices.map { queuedEcho(rows[it], it.toLong()) }) { vm, _ ->
                    assertRenderedProjection(vm.state.value, expected)
                }
            }
            // An invisible lifecycle position and unmatched queue tail establish no displayed history either.
            withProjection(
                listOf(ThreadItem.BackgroundTaskLifecycle("task", Instant.fromEpochSeconds(0))),
                coverage,
                listOf(queuedEcho(rows[0], 0)),
            ) { vm, _ -> assertRenderedProjection(vm.state.value, emptyList()) }
        }

    @Test fun internalTargetInvariant_skipsQueuedFirstRowWithinAnOccupiedSpan() =
        runTest {
            val (base, rows) = fragmentedHistoryFixture(listOf(9000, 9001, 17000, 17999))
            val shadow = ThreadItem.MessageItem((rows[1] as ThreadItem.MessageItem).message.copy(id = "queued-shadow"))
            val lifecycle = ThreadItem.BackgroundTaskLifecycle("task", Instant.fromEpochSeconds(0))
            val coverage = base.copy(unsignedRowOrder = base.unsignedRowOrder + shadow.historyKeys().associateWith { 36005uL })
            val held = listOf(rows[0], lifecycle, shadow) + rows.drop(1)
            withProjection(held, coverage, listOf(queuedEcho(shadow, 0))) { vm, _ ->
                assertRenderedProjection(vm.state.value, listOf(35998uL to rows[0], 36002uL to rows[1]))
                assertEquals(
                    "internal-gap",
                    coverage.cursorForUnsigned(
                        vm.state.value.historyMarkers
                            .last()
                            .unsignedAnchor,
                    ),
                )
            }
        }

    @Test fun queueTransitionInvariant_reprojectsOldestAndInternalTargets_withoutFetchingOrMovingHeldRows() =
        runTest {
            val (coverage, rows) = fragmentedHistoryFixture(listOf(9000, 9001, 17000, 17999))
            withProjection(rows, coverage, listOf(queuedEcho(rows[0], 0))) { vm, queue ->
                assertRenderedProjection(vm.state.value, listOf(36002uL to rows[1]))
                queue.value = emptyList()
                runCurrent()
                assertRenderedProjection(vm.state.value, listOf(35998uL to rows[0], 36002uL to rows[1]))
                queue.value = listOf(queuedEcho(rows[1], 1))
                runCurrent()
                assertRenderedProjection(vm.state.value, listOf(35998uL to rows[0]))
                queue.value = emptyList()
                runCurrent()
                assertRenderedProjection(vm.state.value, listOf(35998uL to rows[0], 36002uL to rows[1]))
                assertEquals("selected-gap", coverage.cursorForUnsigned(35998uL))
                assertEquals("internal-gap", coverage.cursorForUnsigned(36002uL))
            }
        }

    private fun queuedEcho(
        row: ThreadItem,
        id: Long,
    ): QueuedMessage {
        val message = (row as ThreadItem.MessageItem).message
        return QueuedMessage(id, message.content, message.timestamp, message.id)
    }

    private fun assertRenderedProjection(
        state: ThreadUiState,
        expected: List<Pair<ULong, ThreadItem>>,
    ) {
        assertEquals(expected.map { it.first }, state.historyMarkers.map { it.unsignedAnchor })
        assertEquals(expected.map { it.second.historyKeys().first() }, state.historyMarkers.map { it.beforeRow })
        val rendered = foldQueuedRows(state.items, state.queuedMessages)
        val targets =
            rendered.flatMap { row ->
                historyMarkersFor(row, state.historyMarkers).map { marker ->
                    marker.unsignedAnchor to (row as ThreadRow.Delivered).item
                }
            }
        assertEquals("each eligible marker must render at its delivered target exactly once", expected, targets)
    }

    private suspend fun TestScope.withProjection(
        rows: List<ThreadItem>,
        coverage: HistoryCoverage,
        initialQueue: List<QueuedMessage> = emptyList(),
        probe: suspend (ThreadViewModel, MutableStateFlow<List<QueuedMessage>>) -> Unit,
    ) {
        val queue = MutableStateFlow(initialQueue)
        var asks = 0
        val saved = HistoryPosition("independent", true, coverage)
        val repo =
            object : ConversationRepository by FakeConversationRepository() {
                override fun observeMessages(conversationId: String) = flowOf(rows)

                override fun observeQueue(conversationId: String) = queue

                override suspend fun readHistoryPosition(conversationId: String) = saved

                override suspend fun requestHistory(
                    conversationId: String,
                    cursor: String,
                    limit: Int,
                ): HistoryPage {
                    asks++
                    return HistoryPage(emptyList(), "unused", false)
                }
            }
        val vm =
            ThreadViewModel(
                SavedStateHandle(mapOf("conversationId" to "c")),
                repo,
                FakeConnectionStateSource(),
                ComposerDraftStore(),
                repositoryAvailable = flowOf(false),
                projectionDispatcher = StandardTestDispatcher(testScheduler),
            )
        val store = ViewModelStore().apply { put("vm", vm) }
        val reader = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        try {
            runCurrent()
            probe(vm, queue)
            assertEquals(rows, vm.state.value.items)
            rows.zip(vm.state.value.items).forEach { (held, displayed) -> assertSame(held, displayed) }
            assertEquals(coverage, repo.readHistoryPosition("c").coverage)
            assertEquals(17999, coverage.unsignedGaps.size)
            assertEquals(0, asks)
        } finally {
            store.clear()
            reader.cancel()
        }
    }

    @Test fun mainProgressInvariant_pendingWorkerAllowsDraftEdit_latestInputWins_andExitCancels() =
        runTest {
            val (coverage, rows) = fragmentedHistoryFixture(listOf(9000, 9001, 17000, 17999))
            val messages = MutableStateFlow(rows)
            val repo =
                object : ConversationRepository by FakeConversationRepository() {
                    override fun observeMessages(conversationId: String) = messages

                    override suspend fun readHistoryPosition(conversationId: String) = HistoryPosition("oldest", true, coverage)
                }
            val worker = HeldWorker()
            val vm =
                ThreadViewModel(
                    SavedStateHandle(mapOf("serverId" to "h", "conversationId" to "c")),
                    repo,
                    FakeConnectionStateSource(),
                    ComposerDraftStore(),
                    repositoryAvailable = flowOf(false),
                    projectionDispatcher = worker,
                )
            val store = ViewModelStore().apply { put("vm", vm) }
            val reader = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            runCurrent()
            // Drain cancelled startup snapshots, then hold the active row projection.
            while (worker.pending
                    .firstOrNull()
                    ?.first
                    ?.get(Job)
                    ?.isCancelled == true
            ) {
                worker.pending
                    .removeFirst()
                    .second
                    .run()
                runCurrent()
            }
            assertTrue(worker.pending.isNotEmpty())
            vm.onDraftChange("main still responds")
            runCurrent()
            assertEquals("main still responds", vm.draft.value)
            val oldJob = worker.pending.first().first[Job] ?: error("missing worker job")
            assertFalse("the held projection must be active before superseding it", oldJob.isCancelled)
            messages.value = rows.take(2)
            runCurrent()
            assertTrue(oldJob.isCancelled)
            while (worker.pending.isNotEmpty()) {
                worker.drain()
                runCurrent()
            }
            assertEquals(rows.take(2), vm.state.value.items)
            assertEquals(
                listOf(35998uL, 36002uL),
                vm.state.value.historyMarkers
                    .map { it.unsignedAnchor },
            )
            messages.value = rows
            runCurrent()
            assertTrue(worker.pending.isNotEmpty())
            val pendingJob = worker.pending.first().first[Job] ?: error("missing exit job")
            store.clear()
            runCurrent()
            assertTrue(pendingJob.isCancelled)
            worker.drain()
            runCurrent()
            assertEquals(rows.take(2), vm.state.value.items)
            reader.cancel()
        }

    @Test fun restoredLargeAndSparseViewModelProjectsExactRows_withoutProjectionDrivenRequests() =
        runTest {
            for (indices in listOf(listOf(9000, 9001, 17000, 17999), (0 until 18000).toList())) {
                val (coverage, rows) = fragmentedHistoryFixture(indices)
                var asks = 0
                val repo =
                    object : ConversationRepository by FakeConversationRepository() {
                        override fun observeMessages(conversationId: String) = flowOf(rows)

                        override suspend fun readHistoryPosition(conversationId: String) = HistoryPosition("oldest", true, coverage)

                        override suspend fun requestHistory(
                            conversationId: String,
                            cursor: String,
                            limit: Int,
                        ): HistoryPage {
                            asks++
                            return HistoryPage(emptyList(), "unused", false)
                        }
                    }
                val vm =
                    ThreadViewModel(
                        SavedStateHandle(mapOf("conversationId" to "c")),
                        repo,
                        FakeConnectionStateSource(),
                        ComposerDraftStore(),
                        repositoryAvailable = flowOf(false),
                        projectionDispatcher = StandardTestDispatcher(testScheduler),
                    )
                val store = ViewModelStore().apply { put("vm", vm) }
                val reader = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
                runCurrent()
                assertEquals(rows, vm.state.value.items)
                assertEquals(
                    if (indices.size == 4) listOf(35998uL, 36002uL) else listOf(0uL) + coverage.unsignedGaps.map { it.anchor },
                    vm.state.value.historyMarkers
                        .map { it.unsignedAnchor },
                )
                assertEquals(0, asks)
                store.clear()
                reader.cancel()
            }
        }
}
