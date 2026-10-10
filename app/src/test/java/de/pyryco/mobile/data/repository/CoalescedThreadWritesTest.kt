package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.cache.ConversationCache
import de.pyryco.mobile.data.cache.FileConversationCache
import de.pyryco.mobile.data.cache.MAX_CACHED_THREAD_ROWS
import de.pyryco.mobile.data.model.AssistantSegment
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.SegmentDelta
import de.pyryco.mobile.data.model.ordinaryId
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class CoalescedThreadWritesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val oldSink = RelayLog.sink
    private val logs = mutableListOf<String>()

    @Before fun setup() {
        RelayLog.sink = { _, _, message -> logs += message }
    }

    @After fun teardown() {
        RelayLog.sink = oldSink
    }

    private fun row(
        id: String,
        streaming: Boolean = false,
    ) = ThreadItem.MessageItem(Message(id, "s", Role.User, id, Instant.fromEpochSeconds(1), isStreaming = streaming))

    private class Source :
        ConversationRepository by FakeConversationRepository(),
        ThreadSnapshotSource {
        val snapshots = MutableStateFlow(ThreadSnapshot(emptyList()))
        var refuseDelete = false

        override fun observeThreadSnapshot(conversationId: String): Flow<ThreadSnapshot> = snapshots

        override suspend fun delete(conversationId: String) {
            check(!refuseDelete) { "refused" }
        }
    }

    private class Cache(
        val disk: ConversationCache,
    ) : ConversationCache by disk {
        val writes = mutableListOf<List<ThreadItem>>()
        var failures = 0
        var failRead = false
        var failState = false
        var hold = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun readThread(
            serverId: String,
            conversationId: String,
        ): List<ThreadItem> = if (failRead) emptyList() else disk.readThread(serverId, conversationId)

        override suspend fun writeHistoryPosition(
            serverId: String,
            conversationId: String,
            position: HistoryPosition?,
        ): Result<Unit> =
            if (failState) {
                Result.failure(
                    IllegalStateException("static state failure"),
                )
            } else {
                disk.writeHistoryPosition(serverId, conversationId, position)
            }

        override suspend fun writeThread(
            serverId: String,
            conversationId: String,
            rows: List<ThreadItem>,
        ): Result<Unit> {
            writes += rows
            if (writes.size == hold) {
                entered.complete(Unit)
                release.await()
            }
            if (failures > 0) {
                failures--
                return Result.failure(IllegalStateException("static failure"))
            }
            return disk.writeThread(serverId, conversationId, rows)
        }
    }

    private inner class Fixture(
        val scope: TestScope,
    ) {
        val dispatcher = UnconfinedTestDispatcher(scope.testScheduler)
        val source = Source()
        val cache = Cache(FileConversationCache(tmp.root, dispatcher))
        val repository = CachingConversationRepository(source, cache, "host", processingDispatcher = dispatcher)
        val delivered = mutableListOf<ThreadSnapshot>()
        var deliveryGate: CompletableDeferred<Unit>? = null
        val reader =
            scope.backgroundScope.launch(dispatcher) {
                repository.observeThreadSnapshot("c").collect {
                    delivered += it
                    deliveryGate?.await()
                }
            }

        fun send(vararg rows: ThreadItem) {
            source.snapshots.value = ThreadSnapshot(rows.toList())
        }

        suspend fun restored() = FileConversationCache(tmp.root, dispatcher).readThread("host", "c")
    }

    private class HeldWorker : CoroutineDispatcher() {
        val pending = ArrayDeque<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            pending.addLast(block)
        }

        fun next(last: Boolean = false) {
            (if (last) pending.removeLast() else pending.removeFirst()).run()
        }
    }

    private fun TestScope.drain(worker: HeldWorker) {
        repeat(40) {
            runCurrent()
            if (worker.pending.isEmpty()) return
            worker.next()
        }
        error("worker did not settle")
    }

    private fun TestScope.quiet() {
        advanceTimeBy(100)
        runCurrent()
    }

    @Test fun burstInvariant_onlyLatestChangedCandidateWritesAfter100ms() =
        runTest {
            val f = Fixture(this)
            f.send(row("a"))
            assertTrue(f.cache.writes.isEmpty())
            advanceTimeBy(60)
            f.send(row("a"), row("b"))
            advanceTimeBy(99)
            runCurrent()
            assertTrue(f.cache.writes.isEmpty())
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(listOf(row("a"), row("b"))), f.cache.writes)
            f.reader.cancel()
            f.reader.join()
        }

    @Test fun unchangedInvariant_streamingUpdatesDoNotRestartQuietPeriod() =
        runTest {
            val f = Fixture(this)
            f.send(row("a"))
            advanceTimeBy(60)
            f.send(row("a"), row("stream", true))
            advanceTimeBy(40)
            runCurrent()
            assertEquals(1, f.cache.writes.size)
            assertEquals(listOf(row("a")), f.restored())
            f.send(row("a"), row("other-stream", true))
            quiet()
            assertEquals(1, f.cache.writes.size)
            f.reader.cancel()
            f.reader.join()
        }

    @Test fun deliveryInvariant_heldWriteDoesNotBlockSnapshotsAndPendingRowsAreReplaced() =
        runTest {
            val f = Fixture(this)
            f.cache.hold = 1
            f.send(row("a"))
            quiet()
            assertTrue(f.cache.entered.isCompleted)
            f.send(row("a"), row("b"))
            f.send(row("a"), row("b"), row("c"))
            assertEquals(listOf(row("a"), row("b"), row("c")), f.delivered.last().rows)
            assertEquals(1, f.cache.writes.size)
            quiet()
            f.cache.release.complete(Unit)
            runCurrent()
            assertEquals(listOf(listOf(row("a")), listOf(row("a"), row("b"), row("c"))), f.cache.writes)
            f.reader.cancel()
            f.reader.join()
        }

    @Test fun baselineInvariant_returnToPersistedRowsReplacesObsoletePendingCandidate() =
        runTest {
            val f = Fixture(this)
            f.send(row("a"))
            quiet()
            f.send(row("a"), row("b"))
            advanceTimeBy(50)
            f.send(row("a"))
            quiet()
            assertEquals(listOf(listOf(row("a"))), f.cache.writes)
            assertEquals(listOf(row("a")), f.restored())
            f.reader.cancel()
            f.reader.join()
        }

    @Test fun cancellationInvariant_cleanupWaitsForFinalFlushAndFreshCacheRestoresRows() =
        runTest {
            val f = Fixture(this)
            f.cache.hold = 1
            f.send(row("a"))
            f.reader.cancel()
            runCurrent()
            assertTrue(f.cache.entered.isCompleted)
            assertFalse(f.reader.isCompleted)
            f.cache.release.complete(Unit)
            f.reader.join()
            assertEquals(listOf(row("a")), f.restored())
            quiet()
            assertEquals(1, f.cache.writes.size)
        }

    @Test fun completionInvariant_finiteUpstreamFlushesBeforeCollectionReturns() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val disk = FileConversationCache(tmp.root, dispatcher)
            val source =
                object : ConversationRepository by FakeConversationRepository() {
                    override fun observeMessages(conversationId: String) = flowOf(listOf(row("a")), listOf(row("a"), row("b")))
                }
            CachingConversationRepository(source, disk, "host", processingDispatcher = dispatcher).observeMessages("c").collect {}
            assertEquals(listOf(row("a"), row("b")), FileConversationCache(tmp.root, dispatcher).readThread("host", "c"))
            assertEquals(0, testScheduler.currentTime)
        }

    @Test fun cancellationInvariant_postCommitCancellationFlushesLatestAcceptedRemoval() = postCommitFlush(complete = false)

    @Test fun completionInvariant_postCommitCompletionFlushesLatestAcceptedRemoval() = postCommitFlush(complete = true)

    @Test fun coverageInvariant_postCommitSaveCancellationFlushesLatestAcceptedRemoval() =
        postCommitFlush(
            complete = false,
            coverageSave = true,
        )

    private fun postCommitFlush(
        complete: Boolean,
        coverageSave: Boolean = false,
    ) = runTest {
        val processing = StandardTestDispatcher(testScheduler)
        val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val committed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val hold = AtomicBoolean(false)
        val updates = Channel<ThreadSnapshot>(Channel.UNLIMITED)
        val accepted = Channel<Unit>(Channel.UNLIMITED)
        val delivered = Channel<List<ThreadItem>>(Channel.UNLIMITED)
        val source =
            object : ConversationRepository by FakeConversationRepository(), ThreadSnapshotSource {
                var historySnapshot: ThreadSnapshot? = null

                override fun observeThreadSnapshot(conversationId: String) =
                    historySnapshot?.let { flowOf(it) } ?: flow {
                        for (snapshot in updates) {
                            emit(snapshot)
                            accepted.send(Unit) // emit returns only after the writer accepts the candidate
                        }
                    }
            }
        val disk = FileConversationCache(tmp.root, io)
        val a = listOf(row("a"))
        val ab = a + row("b")
        var reader: Job? = null
        var save: Job? = null
        try {
            RelayLog.sink = { _, _, message ->
                // mutate logs success after atomic replacement, before returning across the I/O dispatcher.
                if (message == "conversation_cache operation=write_thread status=ok" && hold.compareAndSet(true, false)) {
                    committed.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "post-commit release timed out" }
                }
            }
            disk.writeThread("host", "c", a).getOrThrow()
            val repository = CachingConversationRepository(source, disk, "host", processingDispatcher = processing)
            reader =
                launch(processing) {
                    repository.observeThreadSnapshot("c").collect { delivered.send(it.rows) }
                }
            hold.set(true)
            updates.send(ThreadSnapshot(ab))
            assertEquals(ab, delivered.receive())
            accepted.receive()
            if (coverageSave) {
                source.historySnapshot = ThreadSnapshot(ab)
                save =
                    launch(
                        processing,
                    ) { repository.writeHistoryPosition("c", HistoryPosition("older", false, HistoryCoverage(unknown = true))) }
                runCurrent()
            } else {
                quiet()
            }
            assertTrue("row write committed", committed.await(10, TimeUnit.SECONDS))
            assertEquals(ab, FileConversationCache(tmp.root, processing).readThread("host", "c"))
            updates.send(ThreadSnapshot(a))
            assertEquals(a, delivered.receive())
            accepted.receive()
            save?.cancel()
            if (complete) updates.close() else reader.cancel()
            runCurrent()
            assertFalse("cleanup waits for committed I/O", reader.isCompleted)
            release.countDown()
            save?.join()
            reader.join()
            assertEquals(
                "cleanup persists the latest accepted rows",
                a,
                FileConversationCache(tmp.root, processing).readThread("host", "c"),
            )
        } finally {
            release.countDown()
            save?.cancel()
            save?.join()
            reader?.cancel()
            reader?.join()
            io.close()
        }
    }

    @Test fun retryInvariant_failureRetriesOnUnchangedSnapshotButNeverBusyLoops() =
        runTest {
            val f = Fixture(this)
            f.cache.failures = 1
            f.send(row("SECRET"))
            quiet()
            assertEquals(1, f.cache.writes.size)
            advanceTimeBy(1000)
            runCurrent()
            assertEquals(1, f.cache.writes.size)
            f.send(row("SECRET"), row("stream", true))
            quiet()
            assertEquals(2, f.cache.writes.size)
            assertEquals(listOf(row("SECRET")), f.restored())
            assertTrue(logs.any { it == "event=thread_cache_write_failed" })
            assertTrue(logs.none { "SECRET" in it })
            f.reader.cancel()
            f.reader.join()
        }

    @Test fun finalRetryInvariant_failedScheduledWriteGetsOneFinalAttempt() =
        runTest {
            val f = Fixture(this)
            f.cache.failures = 1
            f.send(row("a"))
            quiet()
            f.reader.cancel()
            f.reader.join()
            assertEquals(2, f.cache.writes.size)
            assertEquals(listOf(row("a")), f.restored())
        }

    @Test fun finalFailureInvariant_failedCleanupDoesNotLeaveAnOrphanWriter() =
        runTest {
            val f = Fixture(this)
            f.cache.failures = 2
            f.send(row("a"))
            f.reader.cancel()
            f.reader.join()
            quiet()
            assertEquals(1, f.cache.writes.size)
            assertTrue(f.restored().isEmpty())
        }

    @Test fun cancellationInvariant_inFlightWriteCompletesBeforeFlushingLatestAcceptedRows() =
        runTest {
            val f = Fixture(this)
            f.cache.hold = 1
            f.send(row("a"))
            quiet()
            f.send(row("a"), row("b"))
            f.reader.cancel()
            runCurrent()
            assertFalse(f.reader.isCompleted)
            f.cache.release.complete(Unit)
            f.reader.join()
            assertEquals(listOf(listOf(row("a")), listOf(row("a"), row("b"))), f.cache.writes)
            assertEquals(listOf(row("a"), row("b")), f.restored())
            quiet()
            assertEquals(2, f.cache.writes.size)
        }

    @Test fun completionInvariant_upstreamFailureFlushesAcceptedRowsBeforePropagating() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val disk = FileConversationCache(tmp.root, dispatcher)
            val source =
                object : ConversationRepository by FakeConversationRepository() {
                    override fun observeMessages(conversationId: String) =
                        flow {
                            emit(listOf(row("a")))
                            error("upstream failure")
                        }
                }
            val result =
                runCatching {
                    CachingConversationRepository(source, disk, "host", processingDispatcher = dispatcher).observeMessages("c").collect {}
                }
            assertTrue(result.isFailure)
            assertEquals(listOf(row("a")), FileConversationCache(tmp.root, dispatcher).readThread("host", "c"))
        }

    @Test fun deletionInvariant_pendingAndFinalFlushCannotRecreateConfirmedRemoval() =
        runTest {
            val f = Fixture(this)
            f.send(row("a"))
            f.repository.delete("c")
            quiet()
            f.reader.cancel()
            f.reader.join()
            assertTrue(f.cache.writes.isEmpty())
            assertTrue(f.restored().isEmpty())
            assertNull(f.cache.disk.readHistoryPosition("host", "c"))
        }

    @Test fun deletionInvariant_inFlightWriteFinishesBeforeRemovalAndPendingRowsStayDeleted() =
        runTest {
            val f = Fixture(this)
            f.cache.hold = 1
            f.send(row("a"))
            quiet()
            f.send(row("a"), row("b"))
            val removal = launch { f.repository.delete("c") }
            runCurrent()
            assertFalse(removal.isCompleted)
            f.cache.release.complete(Unit)
            removal.join()
            quiet()
            f.reader.cancel()
            f.reader.join()
            assertEquals(1, f.cache.writes.size)
            assertTrue(f.restored().isEmpty())
        }

    @Test fun deletionInvariant_removalWaitsForFinalFlushThenDeletesItsResults() =
        runTest {
            val f = Fixture(this)
            f.cache.hold = 1
            f.send(row("a"))
            f.reader.cancel()
            runCurrent()
            assertTrue(f.cache.entered.isCompleted)
            val removal = launch { f.repository.delete("c") }
            runCurrent()
            assertFalse(removal.isCompleted)
            f.cache.release.complete(Unit)
            f.reader.join()
            removal.join()
            quiet()
            assertEquals(1, f.cache.writes.size)
            assertTrue(f.restored().isEmpty())
        }

    @Test fun deletionInvariant_refusedDeleteRetainsPendingRowsAndHostIsolation() =
        runTest {
            val f = Fixture(this)
            f.cache.disk.writeThread("other-host", "c", listOf(row("other")))
            f.source.refuseDelete = true
            f.send(row("a"))
            assertTrue(runCatching { f.repository.delete("c") }.isFailure)
            f.reader.cancel()
            f.reader.join()
            assertEquals(listOf(row("a")), f.restored())
            assertEquals(listOf(row("other")), f.cache.disk.readThread("other-host", "c"))
        }

    private fun page(vararg ids: Long) =
        HistoryPage(
            ids.reversed().map { id ->
                HistoryEntry(
                    id,
                    "send_message",
                    MobileJson.parseToJsonElement("""{"conversation_id":"c","message_id":"m$id","text":"$id"}"""),
                    Instant.fromEpochSeconds(1),
                )
            },
            "older",
            false,
        )

    @Test fun coverageInvariant_pendingObserverCannotOverwriteNewerRowsAndClaims() = coverageSupersedesPending(false)

    @Test fun coverageInvariant_successfulRowsSupersedePendingEvenWhenStateWriteFails() = coverageSupersedesPending(true)

    private fun coverageSupersedesPending(failState: Boolean) =
        runTest {
            val f = Fixture(this)
            val a = reduceHistoryPage(page(1).entries, true)
            val ab = reduceHistoryPage(page(1, 2).entries, true)
            f.source.snapshots.value = ThreadSnapshot(a)
            // Hold delivery on an unchanged cacheable reading so the newer history page reaches only the save.
            val gate = CompletableDeferred<Unit>()
            f.deliveryGate = gate
            f.source.snapshots.value = ThreadSnapshot(a + row("stream", true))
            f.source.snapshots.value = ThreadSnapshot(ab)
            f.cache.failState = failState
            f.repository.writeHistoryPosition("c", HistoryPosition("older", false, HistoryCoverage().received(page(1, 2))))
            quiet()
            assertEquals(1, f.cache.writes.size)
            f.reader.cancel()
            f.reader.join()
            assertEquals(1, f.cache.writes.size)
            assertEquals(ab, f.restored())
            if (!failState) {
                val saved = f.cache.disk.readHistoryPosition("host", "c") ?: error("missing history")
                assertEquals(listOf(HistorySpan(1, 2)), saved.coverage?.spans)
                assertEquals("older", saved.cursor)
            }
        }

    @Test fun coverageInvariant_unchangedCandidateRetriesAfterBeingSupersededByUnseenHistory() =
        runTest {
            val f = Fixture(this)
            val a = reduceHistoryPage(page(1).entries, true)
            val ab = reduceHistoryPage(page(1, 2).entries, true)
            f.source.snapshots.value = ThreadSnapshot(a)
            val gate = CompletableDeferred<Unit>()
            f.deliveryGate = gate
            f.source.snapshots.value = ThreadSnapshot(a + row("stream", true))
            f.source.snapshots.value = ThreadSnapshot(ab)
            f.repository.writeHistoryPosition("c", HistoryPosition("older", false, HistoryCoverage().received(page(1, 2))))
            f.source.snapshots.value = ThreadSnapshot(a)
            f.deliveryGate = null
            gate.complete(Unit)
            quiet()
            assertEquals(a, f.restored())
            assertEquals(
                listOf(HistorySpan(1, 1)),
                f.cache.disk
                    .readHistoryPosition("host", "c")
                    ?.coverage
                    ?.spans,
            )
            f.reader.cancel()
            f.reader.join()
        }

    @Test fun coverageInvariant_capturedOlderSnapshotCannotInvalidateNewerSaveWhenWorkerResumes() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val worker = HeldWorker()
            val a = reduceHistoryPage(page(1).entries, true)
            val ab = reduceHistoryPage(page(1, 2).entries, true)
            val source = Source().apply { snapshots.value = ThreadSnapshot(a) }
            val cache = Cache(FileConversationCache(tmp.root, dispatcher))
            val repository = CachingConversationRepository(source, cache, "host", processingDispatcher = worker)
            val reader = backgroundScope.launch(dispatcher) { repository.observeThreadSnapshot("c").collect {} }
            drain(worker)
            source.snapshots.value = ThreadSnapshot(a + row("stream", true)) // capture an older snapshot and hold its merge
            source.snapshots.value = ThreadSnapshot(ab)
            val save =
                launch(dispatcher) {
                    repository.writeHistoryPosition("c", HistoryPosition("older", false, HistoryCoverage().received(page(1, 2))))
                }
            worker.next(last = true) // history cache-policy work can finish on another worker before the older merge
            runCurrent()
            save.join()
            worker.next() // resume the older captured merge
            runCurrent()
            worker.next() // accept its cacheable candidate; hold the subsequent newer merge
            runCurrent()
            quiet()
            if (worker.pending.size > 1) worker.next(last = true) // run a ready disk writer while the newest merge is held
            runCurrent()
            try {
                assertEquals(ab, FileConversationCache(tmp.root, dispatcher).readThread("host", "c"))
                assertEquals(
                    listOf(HistorySpan(1, 2)),
                    cache.disk
                        .readHistoryPosition("host", "c")
                        ?.coverage
                        ?.spans,
                )
                assertEquals(1, cache.writes.size)
            } finally {
                reader.cancel()
                drain(worker)
                reader.join()
            }
        }

    @Test fun coverageInvariant_newerRemovalComparedWithActualPersistedHistoryRows() =
        runTest {
            val f = Fixture(this)
            val a = reduceHistoryPage(page(1).entries, true)
            val ab = reduceHistoryPage(page(1, 2).entries, true)
            f.source.snapshots.value = ThreadSnapshot(a)
            quiet()
            f.source.snapshots.value = ThreadSnapshot(ab)
            f.repository.writeHistoryPosition("c", HistoryPosition("older", false, HistoryCoverage().received(page(1, 2))))
            f.source.snapshots.value = ThreadSnapshot(a)
            quiet()
            assertEquals(a, f.restored())
            assertEquals(
                listOf(HistorySpan(1, 1)),
                f.cache.disk
                    .readHistoryPosition("host", "c")
                    ?.coverage
                    ?.spans,
            )
            f.reader.cancel()
            f.reader.join()
        }

    @Test fun baselineInvariant_resubscriptionPersistsRowsAfterObserverSupersedesCoverage() = resubscribeAfterCoverage(false)

    @Test fun baselineInvariant_resubscriptionFlushesRowsAfterObserverSupersedesCoverage() = resubscribeAfterCoverage(true)

    @Test fun baselineInvariant_completedCandidateCannotOverwriteAnotherCollectorsNewerRows() =
        runTest {
            val f = Fixture(this)
            f.send(row("a"))
            quiet()
            f.deliveryGate = CompletableDeferred()
            f.send(row("a"), row("stream", true))
            val reader = backgroundScope.launch(f.dispatcher) { f.repository.observeThreadSnapshot("c").collect {} }
            f.send(row("a"), row("b"))
            quiet()
            assertEquals(listOf(row("a"), row("b")), f.restored())

            // The held collector already persisted its latest accepted candidate before this newer write.
            f.reader.cancel()
            f.reader.join()
            assertEquals(listOf(row("a"), row("b")), f.restored())
            reader.cancel()
            reader.join()
            assertEquals(2, f.cache.writes.size)
        }

    @Test fun unchangedInvariant_observerBaselineDoesNotTurnFailedRestoreIntoAnEmptyWrite() =
        runTest {
            val f = Fixture(this)
            f.send(row("a"))
            quiet()
            f.reader.cancel()
            f.reader.join()
            f.source.snapshots.value = ThreadSnapshot(emptyList())
            f.cache.failRead = true
            val reader = backgroundScope.launch(f.dispatcher) { f.repository.observeThreadSnapshot("c").collect {} }
            f.send(row("stream", true))
            quiet()
            assertEquals(listOf(row("a")), f.restored())
            reader.cancel()
            reader.join()
            assertEquals(1, f.cache.writes.size)
            assertEquals(listOf(row("a")), f.restored())
        }

    @Test fun unchangedInvariant_retainedCoverageCannotEraseFailedRestoreAfterQuietPeriod() = retainedCoverageAfterFailedRestore(false)

    @Test fun unchangedInvariant_retainedCoverageCannotEraseFailedRestoreOnFinalFlush() = retainedCoverageAfterFailedRestore(true)

    private fun retainedCoverageAfterFailedRestore(flush: Boolean) =
        runTest {
            val f = Fixture(this)
            val saved = reduceHistoryPage(page(1, 2).entries, true)
            f.source.snapshots.value = ThreadSnapshot(saved)
            f.repository.writeHistoryPosition("c", HistoryPosition("older", false, HistoryCoverage().received(page(1, 2))))
            f.reader.cancel()
            f.reader.join()
            assertEquals(saved, f.restored())
            val savedPosition = f.cache.disk.readHistoryPosition("host", "c") ?: error("missing history")
            assertEquals(listOf(HistorySpan(1, 2)), savedPosition.coverage?.spans)
            assertEquals(1, f.cache.writes.size)

            f.source.snapshots.value = ThreadSnapshot(emptyList())
            f.cache.failRead = true
            val delivered = mutableListOf<ThreadSnapshot>()
            val reader = backgroundScope.launch(f.dispatcher) { f.repository.observeThreadSnapshot("c").collect { delivered += it } }
            assertTrue(delivered.single().rows.isEmpty())
            f.send(row("stream", true))
            assertEquals(listOf(row("stream", true)), delivered.last().rows)
            if (!flush) {
                quiet()
                assertEquals(saved, f.restored())
                assertEquals(savedPosition, FileConversationCache(tmp.root, f.dispatcher).readHistoryPosition("host", "c"))
            }
            reader.cancel()
            reader.join()
            assertEquals(saved, f.restored())
            assertEquals(savedPosition, FileConversationCache(tmp.root, f.dispatcher).readHistoryPosition("host", "c"))
            assertEquals(1, f.cache.writes.size)
        }

    @Test fun retryInvariant_retainedCoverageStillAllowsChangedRowsAndUnchangedFailureRetry() =
        runTest {
            val f = Fixture(this)
            val saved = reduceHistoryPage(page(1, 2).entries, true)
            f.source.snapshots.value = ThreadSnapshot(saved)
            f.repository.writeHistoryPosition("c", HistoryPosition("older", false, HistoryCoverage().received(page(1, 2))))
            f.reader.cancel()
            f.reader.join()
            f.source.snapshots.value = ThreadSnapshot(emptyList())
            f.cache.failRead = true
            val reader = backgroundScope.launch(f.dispatcher) { f.repository.observeThreadSnapshot("c").collect {} }
            f.cache.failures = 1
            f.send(row("new"))
            quiet()
            assertEquals(saved, f.restored())
            assertEquals(2, f.cache.writes.size)
            advanceTimeBy(1000)
            runCurrent()
            assertEquals(2, f.cache.writes.size)
            f.send(row("new"), row("stream", true))
            quiet()
            assertEquals(listOf(row("new")), f.restored())
            reader.cancel()
            reader.join()
            assertEquals(3, f.cache.writes.size)
            assertEquals(1, logs.count { it == "event=thread_cache_write_failed" })
        }

    @Test fun coverageInvariant_saveDuringRestoreStillAllowsNewerUnchangedRemoval() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val a = reduceHistoryPage(page(1).entries, true)
            val ab = reduceHistoryPage(page(1, 2).entries, true)
            val disk = FileConversationCache(tmp.root, dispatcher)
            disk.writeThread("host", "c", a).getOrThrow()
            val cache = Cache(disk)
            val restoreEntered = CompletableDeferred<Unit>()
            val restoreRelease = CompletableDeferred<Unit>()
            val heldCache =
                object : ConversationCache by cache {
                    override suspend fun readThread(
                        serverId: String,
                        conversationId: String,
                    ): List<ThreadItem> {
                        val rows = cache.readThread(serverId, conversationId)
                        if (!restoreEntered.isCompleted) {
                            restoreEntered.complete(Unit)
                            restoreRelease.await()
                        }
                        return rows
                    }
                }
            val source = Source().apply { snapshots.value = ThreadSnapshot(a) }
            val repository = CachingConversationRepository(source, heldCache, "host", processingDispatcher = dispatcher)
            val reader = backgroundScope.launch(dispatcher) { repository.observeThreadSnapshot("c").collect {} }
            assertTrue(restoreEntered.isCompleted)
            source.snapshots.value = ThreadSnapshot(ab)
            repository.writeHistoryPosition("c", HistoryPosition("older", false, HistoryCoverage().received(page(1, 2))))
            assertEquals(ab, FileConversationCache(tmp.root, dispatcher).readThread("host", "c"))
            source.snapshots.value = ThreadSnapshot(a)
            restoreRelease.complete(Unit)
            quiet()
            assertEquals(a, FileConversationCache(tmp.root, dispatcher).readThread("host", "c"))
            assertEquals(listOf(ab, a), cache.writes)
            assertEquals(listOf(HistorySpan(1, 1)), disk.readHistoryPosition("host", "c")?.coverage?.spans)
            reader.cancel()
            reader.join()
        }

    @Test fun coverageInvariant_saveStartedBeforeCollectionPersistsNewerRemovalAfterQuietPeriod() = overlappingCoverageDuringRestore(false)

    @Test fun coverageInvariant_saveStartedBeforeCollectionFlushesNewerRemovalOnCancellation() = overlappingCoverageDuringRestore(true)

    private fun overlappingCoverageDuringRestore(flush: Boolean) =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val worker = HeldWorker()
            val a = reduceHistoryPage(page(1).entries, true)
            val ab = reduceHistoryPage(page(1, 2).entries, true)
            val disk = FileConversationCache(tmp.root, dispatcher)
            disk.writeThread("host", "c", a).getOrThrow()
            val cache = Cache(disk)
            val source = Source().apply { snapshots.value = ThreadSnapshot(ab) }
            val repository = CachingConversationRepository(source, cache, "host", processingDispatcher = worker)
            // Capture the save before collection entry; hold its policy work before row I/O.
            val save =
                launch(dispatcher) {
                    repository.writeHistoryPosition("c", HistoryPosition("older", false, HistoryCoverage().received(page(1, 2))))
                }
            assertEquals(1, worker.pending.size)
            assertTrue(cache.writes.isEmpty())
            val delivered = mutableListOf<ThreadSnapshot>()
            val reader = backgroundScope.launch(dispatcher) { repository.observeThreadSnapshot("c").collect { delivered += it } }
            assertEquals(2, worker.pending.size) // restoration has read [a] and is held on the worker
            source.snapshots.value = ThreadSnapshot(a)
            worker.next() // finish the already-captured save while restoration remains held
            runCurrent()
            save.join()
            assertEquals(ab, FileConversationCache(tmp.root, dispatcher).readThread("host", "c"))
            assertEquals(listOf(HistorySpan(1, 2)), disk.readHistoryPosition("host", "c")?.coverage?.spans)
            drain(worker)
            assertEquals(a, delivered.single().rows)
            assertEquals(listOf(ab), cache.writes)
            try {
                if (!flush) {
                    quiet()
                    drain(worker)
                    assertEquals(a, FileConversationCache(tmp.root, dispatcher).readThread("host", "c"))
                    assertEquals(listOf(HistorySpan(1, 1)), disk.readHistoryPosition("host", "c")?.coverage?.spans)
                }
            } finally {
                reader.cancel()
                drain(worker)
                reader.join()
            }
            assertEquals(a, FileConversationCache(tmp.root, dispatcher).readThread("host", "c"))
            assertEquals(listOf(HistorySpan(1, 1)), disk.readHistoryPosition("host", "c")?.coverage?.spans)
            assertEquals(listOf(ab, a), cache.writes)
        }

    private fun resubscribeAfterCoverage(flush: Boolean) =
        runTest {
            val f = Fixture(this)
            val a = reduceHistoryPage(page(1).entries, true)
            val ab = reduceHistoryPage(page(1, 2).entries, true)
            f.source.snapshots.value = ThreadSnapshot(ab)
            f.repository.writeHistoryPosition("c", HistoryPosition("older", false, HistoryCoverage().received(page(1, 2))))
            f.source.snapshots.value = ThreadSnapshot(a)
            quiet()
            assertEquals(a, f.restored())
            f.reader.cancel()
            f.reader.join()
            assertEquals(2, f.cache.writes.size)

            // WhileSubscribed restarts collection on the same destination wrapper with fresh disk rows.
            f.source.snapshots.value = ThreadSnapshot(ab)
            val delivered = mutableListOf<ThreadSnapshot>()
            val reader =
                backgroundScope.launch(f.dispatcher) {
                    f.repository.observeThreadSnapshot("c").collect { delivered += it }
                }
            assertEquals(ab, delivered.single().rows)
            if (!flush) {
                quiet()
                assertEquals(ab, f.restored())
            }
            reader.cancel()
            reader.join()
            assertEquals(ab, f.restored())
            assertEquals(listOf(ab, a, ab), f.cache.writes)
        }

    @Test fun coverageInvariant_newSnapshotDuringCoverageWriteRemainsEligibleAndRowsPrecedeState() =
        runTest {
            val f = Fixture(this)
            val a = reduceHistoryPage(page(1).entries, true)
            val ab = reduceHistoryPage(page(1, 2).entries, true)
            f.source.snapshots.value = ThreadSnapshot(a)
            f.cache.hold = 1
            val save =
                launch {
                    f.repository.writeHistoryPosition("c", HistoryPosition("older", false, HistoryCoverage().received(page(1))))
                }
            runCurrent()
            assertTrue(f.cache.entered.isCompleted)
            assertNull(f.cache.disk.readHistoryPosition("host", "c"))
            f.source.snapshots.value = ThreadSnapshot(ab)
            quiet()
            assertEquals(ab, f.delivered.last().rows)
            f.cache.release.complete(Unit)
            save.join()
            runCurrent()
            assertEquals(ab, f.restored())
            assertEquals(2, f.cache.writes.size)
            assertEquals(
                listOf(HistorySpan(1, 1)),
                f.cache.disk
                    .readHistoryPosition("host", "c")
                    ?.coverage
                    ?.spans,
            )
            f.reader.cancel()
            f.reader.join()
        }

    @Test fun trimmingInvariant_deferredWriteReceivesUntrimmedRowsAndClearsStalePosition() =
        runTest(timeout = 3.minutes) {
            val f = Fixture(this)
            f.send(row("old"))
            quiet()
            f.repository.writeHistoryPosition("c", HistoryPosition("past-old", true))
            val many = List(MAX_CACHED_THREAD_ROWS) { row("new-$it") }
            f.source.snapshots.value = ThreadSnapshot(listOf(row("old")) + many)
            quiet()
            assertEquals(
                MAX_CACHED_THREAD_ROWS + 1,
                f.cache.writes
                    .last()
                    .size,
            )
            assertEquals(many, f.restored())
            assertNull(f.cache.disk.readHistoryPosition("host", "c"))
            f.reader.cancel()
            f.reader.join()
        }

    @Test
    fun identityInvariant_collidingRendererKeysKeepBothIdentitiesThroughPendingReconnect() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val user = row("shared")
            val segment =
                user.copy(
                    message =
                        user.message.copy(
                            role = Role.Assistant,
                            segment = AssistantSegment("shared", listOf(SegmentDelta(0, 6))),
                        ),
                )
            assertEquals(
                "direct merge must retain distinct identities",
                2,
                listOf(user).mergeUnsignedCachedRows(listOf(segment), emptyMap(), rendererOwners = listOf(segment)).size,
            )
            FileConversationCache(tmp.root, dispatcher).writeThread("host", "c", listOf(segment)).getOrThrow()
            val f = Fixture(this)
            assertEquals("initial restore must retain the segment", listOf(segment), f.delivered.single().rows)
            var expected: List<ThreadItem>? = null
            for (snapshot in listOf(ThreadSnapshot(listOf(user)), ThreadSnapshot(emptyList()), ThreadSnapshot(listOf(user)))) {
                f.source.snapshots.value = snapshot
                val messages =
                    f.delivered
                        .last()
                        .rows
                        .map { (it as ThreadItem.MessageItem).message }
                assertEquals(2, messages.size)
                assertEquals(2, messages.map { it.id }.distinct().size)
                assertEquals("shared", messages.single { it.segment != null }.id)
                assertEquals("shared", messages.single { it.role == Role.User }.ordinaryId)
                if (expected == null) expected = f.delivered.last().rows else assertEquals(expected, f.delivered.last().rows)
            }
            quiet()
            assertEquals(expected, f.restored())
            assertEquals(1, f.cache.writes.size)
            f.reader.cancel()
            f.reader.join()
        }

    @Test fun reconnectInvariant_pendingRowsKeepMergedNeighboursSuppressionAndOrderAcrossBoundary() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val seed = listOf(row("a"), row("offer"), row("b"))
            FileConversationCache(tmp.root, dispatcher).writeThread("host", "c", seed)
            val f = Fixture(this)
            for (overlap in listOf(listOf(row("a")), listOf(row("b")), listOf(row("a"), row("b")))) {
                f.source.snapshots.value = ThreadSnapshot(overlap + row("c"))
                assertEquals(seed + row("c"), f.delivered.last().rows)
            }
            f.source.snapshots.value = ThreadSnapshot(emptyList())
            assertEquals(seed + row("c"), f.delivered.last().rows)
            f.source.snapshots.value = ThreadSnapshot(listOf(row("b"), row("c"), row("d")))
            assertEquals(seed + listOf(row("c"), row("d")), f.delivered.last().rows)
            f.source.snapshots.value = ThreadSnapshot(listOf(row("b"), row("c"), row("d")), setOf("a"))
            quiet()
            assertEquals(listOf(row("offer"), row("b"), row("c"), row("d")), f.restored())
            assertEquals(1, f.cache.writes.size)
            f.reader.cancel()
            f.reader.join()
        }
}
