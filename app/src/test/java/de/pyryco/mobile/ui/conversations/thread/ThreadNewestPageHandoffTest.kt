package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import de.pyryco.mobile.data.network.RelayErrorException
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.HistoryCoverage
import de.pyryco.mobile.data.repository.HistoryPage
import de.pyryco.mobile.data.repository.HistoryPosition
import de.pyryco.mobile.data.repository.HistorySpan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadNewestPageHandoffTest {
    private val stores = mutableListOf<ViewModelStore>()
    private val previousSink = RelayLog.sink

    @After
    fun tearDown() {
        stores.forEach { it.clear() }
        Dispatchers.resetMain()
        RelayLog.sink = previousSink
    }

    @Test
    fun pendingArrival_drainsWhenDerivedAvailabilityFinallyPublishesTrue() =
        runTest {
            val availability = LaggingAvailability(initial = false)
            val repo = HistoryRecorder()
            val vm = open(repo, availability)
            runCurrent()
            assertEquals(emptyList<String>(), repo.asks)
            availability.emit(true)
            runCurrent()
            assertFalse(vm.state.value.hostAvailable)
            assertEquals(emptyList<String>(), repo.asks)
            availability.release()
            runCurrent()
            assertTrue(vm.state.value.hostAvailable)
            assertEquals(listOf(""), repo.asks)
            assertEquals(1, repo.maximumActive)
        }

    @Test
    fun initialAvailableArrival_waitsForSeedAndDerivedReadiness_thenAsksOnce() =
        runTest {
            val availability = LaggingAvailability(initial = true)
            val seed = CompletableDeferred<Unit>()
            val repo = HistoryRecorder(seed = seed)
            val vm = open(repo, availability)
            runCurrent()
            availability.release()
            runCurrent()
            assertTrue(vm.state.value.hostAvailable)
            assertEquals(emptyList<String>(), repo.asks)
            seed.complete(Unit)
            runCurrent()
            assertEquals(listOf(""), repo.asks)
            runCurrent()
            assertEquals(listOf(""), repo.asks)
        }

    @Test
    fun repeatedTrueEmissions_addNoArrivalsOrOlderLoop() =
        runTest {
            val availability = LaggingAvailability(initial = false)
            val repo = HistoryRecorder()
            open(repo, availability)
            runCurrent()
            availability.emit(true)
            availability.release()
            runCurrent()
            repeat(3) {
                availability.emit(true)
                availability.release()
                runCurrent()
            }
            assertEquals(listOf(""), repo.asks)
            assertEquals(1, repo.maximumActive)
            assertEquals("backwards", repo.saved?.cursor)
        }

    @Test
    fun reconnectBehindSettlingOlderRequest_successAndFailure_surviveLaggingReadiness() =
        runTest {
            for (failed in listOf(false, true)) {
                for (readyBeforeSettlement in listOf(false, true)) {
                    val availability = LaggingAvailability(initial = true)
                    val older = CompletableDeferred<HistoryPage>()
                    val repo =
                        HistoryRecorder { cursor ->
                            if (cursor.isEmpty()) HistoryPage(emptyList(), "newest-edge", false) else older.await()
                        }
                    val vm = open(repo, availability)
                    runCurrent()
                    availability.release()
                    runCurrent()
                    vm.onDemandOlderHistory()
                    runCurrent()
                    availability.emit(false)
                    runCurrent()
                    availability.emit(true)
                    runCurrent()
                    assertEquals(listOf("", "backwards"), repo.asks)
                    if (readyBeforeSettlement) availability.release()
                    runCurrent()
                    if (failed) {
                        older.completeExceptionally(RelayErrorException("history.unavailable", true, "fixture"))
                    } else {
                        older.complete(HistoryPage(emptyList(), "next-backwards", false))
                    }
                    runCurrent()
                    if (!readyBeforeSettlement) {
                        assertEquals(listOf("", "backwards"), repo.asks)
                        availability.release()
                        runCurrent()
                    }
                    assertEquals(listOf("", "backwards", ""), repo.asks)
                    assertEquals(1, repo.maximumActive)
                    assertEquals(if (failed) "backwards" else "next-backwards", repo.saved?.cursor)
                    assertEquals(if (failed) ThreadHistoryTail.Retry else ThreadHistoryTail.None, vm.state.value.historyTail)
                    runCurrent()
                    assertEquals(3, repo.asks.size)
                    stores.last().clear()
                }
            }
        }

    @Test
    fun multipleArrivalsBehindNewestRequest_areCountedOnce_andFailuresDoNotRetry() =
        runTest {
            val availability = LaggingAvailability(initial = true)
            val first = CompletableDeferred<HistoryPage>()
            var count = 0
            val repo =
                HistoryRecorder { _ ->
                    if (count++ == 0) first.await() else throw RelayErrorException("history.unavailable", true, "fixture")
                }
            val vm = open(repo, availability)
            runCurrent()
            availability.release()
            runCurrent()
            repeat(2) {
                availability.emit(false)
                runCurrent()
                availability.emit(true)
                availability.release()
                runCurrent()
            }
            vm.onDemandOlderHistory()
            vm.onRetryOlderHistory()
            assertEquals(listOf(""), repo.asks)
            first.completeExceptionally(IllegalStateException("fixture"))
            runCurrent()
            assertEquals(listOf("", "", ""), repo.asks)
            assertEquals(1, repo.maximumActive)
            assertEquals("backwards", repo.saved?.cursor)
            assertEquals(ThreadHistoryTail.None, vm.state.value.historyTail)
            runCurrent()
            assertEquals(3, repo.asks.size)
        }

    @Test
    fun savedAtStart_survivesSideAsk_andOfflineReaderDemandIsInert() =
        runTest {
            val availability = LaggingAvailability(initial = false)
            val repo = HistoryRecorder(saved = HistoryPosition("backwards", true))
            val vm = open(repo, availability)
            runCurrent()
            vm.onDemandOlderHistory()
            vm.onRetryOlderHistory()
            vm.onDemandHistoryGap(0)
            runCurrent()
            assertEquals(emptyList<String>(), repo.asks)
            availability.emit(true)
            availability.release()
            runCurrent()
            vm.onDemandOlderHistory()
            runCurrent()
            assertEquals(listOf(""), repo.asks)
            assertEquals("backwards", repo.saved?.cursor)
            assertEquals(true, repo.saved?.atStart)
            assertEquals(ThreadHistoryTail.None, vm.state.value.historyTail)
        }

    @Test
    fun destinationExit_cancelsActiveAndPendingNewestWork_beforeReadiness() =
        runTest {
            val availability = LaggingAvailability(initial = true)
            val first = CompletableDeferred<HistoryPage>()
            val repo = HistoryRecorder { first.await() }
            open(repo, availability)
            runCurrent()
            availability.release()
            runCurrent()
            availability.emit(false)
            runCurrent()
            availability.emit(true)
            runCurrent()
            stores.last().clear()
            runCurrent()
            assertEquals(1, repo.cancellations)
            assertEquals(0, repo.active)
            first.complete(HistoryPage(emptyList(), "edge", false))
            availability.release()
            availability.emit(false)
            availability.emit(true)
            availability.release()
            runCurrent()
            assertEquals(listOf(""), repo.asks)
        }

    @Test
    fun reconnectBehindRetryOrGap_sharesSlot_andReleaseAddsNoReaderDemand() =
        runTest {
            for (gap in listOf(false, true)) {
                val availability = LaggingAvailability(initial = true)
                val response = CompletableDeferred<HistoryPage>()
                val coverage = HistoryCoverage(spans = listOf(HistorySpan(1, 1)), unknown = true, cursors = mapOf(1L to "gap"))
                var readerAsks = 0
                val repo =
                    HistoryRecorder(saved = HistoryPosition("backwards", gap, coverage)) { cursor ->
                        if (cursor.isEmpty()) {
                            HistoryPage(emptyList(), "newest-edge", false)
                        } else if (!gap && readerAsks++ == 0) {
                            throw RelayErrorException("history.unavailable", true, "fixture")
                        } else {
                            response.await()
                        }
                    }
                val vm = open(repo, availability)
                runCurrent()
                availability.release()
                runCurrent()
                if (gap) {
                    vm.onDemandHistoryGap(0)
                } else {
                    vm.onDemandOlderHistory()
                    runCurrent()
                    vm.onRetryOlderHistory()
                }
                runCurrent()
                val before = repo.asks.toList()
                availability.emit(false)
                runCurrent()
                availability.emit(true)
                availability.release()
                runCurrent()
                vm.onDemandHistoryGap(0)
                vm.onDemandOlderHistory()
                vm.onRetryOlderHistory()
                runCurrent()
                assertEquals(before, repo.asks)
                response.complete(HistoryPage(emptyList(), "reader-edge", false))
                runCurrent()
                assertEquals(before + "", repo.asks)
                assertEquals(1, repo.maximumActive)
                assertEquals(if (gap) "backwards" else "reader-edge", repo.saved?.cursor)
                assertEquals(gap, repo.saved?.atStart)
                runCurrent()
                assertEquals(before + "", repo.asks)
                stores.last().clear()
            }
        }

    @Test
    fun newestAsWalksOwnPage_settlesNormally_andItsFailureConsumesArrival() =
        runTest {
            for (failed in listOf(false, true)) {
                val availability = LaggingAvailability(initial = true)
                val repo =
                    HistoryRecorder(saved = null) {
                        if (failed) throw RelayErrorException("history.unavailable", true, "fixture")
                        HistoryPage(emptyList(), "walk-edge", true)
                    }
                val vm = open(repo, availability)
                runCurrent()
                availability.release()
                runCurrent()
                assertEquals(listOf(""), repo.asks)
                assertEquals(if (failed) null else "walk-edge", repo.saved?.cursor)
                assertEquals(if (failed) ThreadHistoryTail.Retry else ThreadHistoryTail.None, vm.state.value.historyTail)
                runCurrent()
                assertEquals(listOf(""), repo.asks)
                stores.last().clear()
            }
        }

    private fun TestScope.open(
        repo: HistoryRecorder,
        availability: LaggingAvailability,
    ): ThreadViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        RelayLog.sink = { _, _, _ -> }
        val vm =
            ThreadViewModel(
                SavedStateHandle(mapOf("serverId" to "host", "conversationId" to "thread")),
                repo,
                FakeConnectionStateSource(),
                ComposerDraftStore(),
                repositoryAvailable = availability.flow,
            )
        stores += ViewModelStore().apply { put("thread", vm) }
        backgroundScope.launch { vm.state.collect {} }
        return vm
    }

    /** Only the first (eager hostAvailable) subscription lags; raw arrivals still reach production. */
    private class LaggingAvailability(
        initial: Boolean,
    ) {
        private val source = MutableSharedFlow<Boolean>(replay = 1).apply { tryEmit(initial) }
        private val ready = Channel<Unit>(Channel.UNLIMITED)
        private var subscriptions = 0
        val flow =
            flow {
                val derived = subscriptions++ == 0
                source.collect { available ->
                    if (derived && available) ready.receive()
                    emit(available)
                }
            }

        suspend fun emit(available: Boolean) = source.emit(available)

        fun release() {
            check(ready.trySend(Unit).isSuccess)
        }
    }

    private class HistoryRecorder(
        var saved: HistoryPosition? = HistoryPosition("backwards", false),
        private val seed: CompletableDeferred<Unit>? = null,
        private val answer: suspend (String) -> HistoryPage = { HistoryPage(emptyList(), "newest-edge", false) },
    ) : ConversationRepository by FakeConversationRepository() {
        val asks = mutableListOf<String>()
        var active = 0
        var maximumActive = 0
        var cancellations = 0

        override suspend fun readHistoryPosition(conversationId: String): HistoryPosition? {
            seed?.await()
            return saved
        }

        override suspend fun writeHistoryPosition(
            conversationId: String,
            position: HistoryPosition?,
        ) {
            saved = position
        }

        override suspend fun requestHistory(
            conversationId: String,
            cursor: String,
            limit: Int,
        ): HistoryPage {
            assertEquals(200, limit)
            asks += cursor
            active++
            maximumActive = maxOf(maximumActive, active)
            try {
                return answer(cursor)
            } catch (error: kotlinx.coroutines.CancellationException) {
                cancellations++
                throw error
            } finally {
                active--
            }
        }
    }
}
