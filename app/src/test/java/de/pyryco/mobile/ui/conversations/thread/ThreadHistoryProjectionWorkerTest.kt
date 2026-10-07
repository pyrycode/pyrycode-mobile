package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.HistoryPage
import de.pyryco.mobile.data.repository.HistoryPosition
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
