package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.cache.ConversationCache
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.model.Role
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class CachedThreadWorkerTest {
    private class HeldWorker : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()
        var running = false
            private set
        val count get() = pending.size

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            pending.addLast(block)
        }

        fun next() {
            running = true
            try {
                pending.removeFirst().run()
            } finally {
                running = false
            }
        }
    }

    private fun TestScope.drain(worker: HeldWorker) {
        repeat(20) {
            runCurrent()
            if (worker.count == 0) return
            worker.next()
        }
        error("worker did not settle")
    }

    private class Source(
        initial: ThreadSnapshot,
    ) : ConversationRepository by FakeConversationRepository(),
        ThreadSnapshotSource {
        val snapshots = MutableStateFlow(initial)

        override fun observeThreadSnapshot(conversationId: String): Flow<ThreadSnapshot> = snapshots

        override suspend fun requestHistory(
            conversationId: String,
            cursor: String,
            limit: Int,
        ): HistoryPage = error("restore cannot request history")

        override suspend fun markConversationRead(
            conversationId: String,
            upTo: ULong,
        ) = error("restore cannot grant sight")
    }

    private class Cache(
        val seed: List<ThreadItem>,
        val position: HistoryPosition? = null,
    ) : ConversationCache {
        val writes = mutableListOf<List<ThreadItem>>()

        override suspend fun readThread(
            serverId: String,
            conversationId: String,
        ) = seed

        override suspend fun readHistoryPosition(
            serverId: String,
            conversationId: String,
        ) = position

        override suspend fun writeThread(
            serverId: String,
            conversationId: String,
            rows: List<ThreadItem>,
        ): Result<Unit> {
            writes += rows
            return Result.success(Unit)
        }

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
    }

    private fun row(
        id: String,
        streaming: Boolean = false,
        role: Role = Role.Assistant,
    ) = ThreadItem.MessageItem(Message(id, "s", role, id, Instant.fromEpochSeconds(1), streaming))

    @Test fun processingInvariant_100000RowsRunOnHeldWorker_mainAndDeliveryProgress() =
        runTest {
            val worker = HeldWorker()
            val rows = (0 until 100_000).map { row("m$it", role = Role.User) }
            var reads = 0
            val guarded =
                object : AbstractList<ThreadItem>() {
                    override val size get() = rows.size

                    override fun get(index: Int): ThreadItem {
                        assertTrue("cached traversal must run on worker", worker.running)
                        reads++
                        return rows[index]
                    }
                }
            val order = mapOf(historyIdentity(rows.first().mergeIdentity()) to 1uL)
            val cache = Cache(guarded, HistoryPosition("", false, HistoryCoverage(unsignedSpans = emptyList(), unsignedRowOrder = order)))
            val source = Source(ThreadSnapshot(emptyList()))
            val repository = CachingConversationRepository(source, cache, "host", processingDispatcher = worker)
            val delivered = mutableListOf<ThreadSnapshot>()
            val gate = CompletableDeferred<Unit>()
            val reader =
                backgroundScope.launch {
                    repository.observeThreadSnapshot("c").collect {
                        assertFalse("delivery stays on main", worker.running)
                        delivered += it
                        if (delivered.size == 1) gate.await()
                    }
                }
            runCurrent()
            assertTrue(worker.count > 0)
            assertTrue(delivered.isEmpty())
            var mainProgress = false
            launch { mainProgress = true }
            runCurrent()
            assertTrue(mainProgress)
            drain(worker)
            assertEquals(100_000, delivered.single().rows.size)
            assertEquals(ThreadReadEvidence(), delivered.single().readEvidence)
            assertTrue(reads >= 100_000)
            assertEquals(0, worker.count)
            assertTrue(cache.writes.isEmpty())
            val beforeEquality = reads
            gate.complete(Unit)
            runCurrent()
            assertTrue("cache filtering/equality waits for delivery", worker.count > 0)
            drain(worker)
            assertTrue("unchanged-set equality traverses cached rows on worker", reads > beforeEquality)
            assertTrue(cache.writes.isEmpty())

            val live = listOf(row("m99999"), row("stream", streaming = true))
            var liveReads = 0
            val guardedLive =
                object : AbstractList<ThreadItem>() {
                    override val size get() = live.size

                    override fun get(index: Int): ThreadItem {
                        assertTrue("live merge traversal must run on worker", worker.running)
                        liveReads++
                        return live[index]
                    }
                }
            val suppression = setOf("m0")
            val evidence = ThreadReadEvidence(versions = mapOf(live.first() to setOf(2uL)))
            val snapshot =
                ThreadSnapshot(
                    guardedLive,
                    suppression,
                    unsignedHistoryOrder = mapOf(live.first().mergeIdentity() to 2uL),
                    readEvidence = evidence,
                )
            source.snapshots.value = snapshot
            runCurrent()
            assertEquals(1, delivered.size)
            drain(worker)
            assertTrue(liveReads > 0)
            val result = delivered.last()
            assertSame(suppression, result.suppressedUserMessageIds)
            assertSame(snapshot.unsignedHistoryOrder, result.unsignedHistoryOrder)
            assertSame(evidence, result.readEvidence)
            assertEquals(100_000, result.rows.size)
            assertFalse(result.rows.contains(rows.first()))
            assertEquals(live.last(), result.rows.last())
            assertEquals(result.rows, cache.writes.single()) // untrimmed, including streaming input
            reader.cancel()
        }

    @Test fun cachePolicyInvariant_100000LiveRowsAreFilteredOnlyAfterMainDelivery() =
        runTest {
            val worker = HeldWorker()
            val rows = (0 until 100_000).map { row("m$it", streaming = it == 99_999) }
            var reads = 0
            val guarded =
                object : AbstractList<ThreadItem>() {
                    override val size get() = rows.size

                    override fun get(index: Int): ThreadItem {
                        assertTrue("cache policy traversal must run on worker", worker.running)
                        reads++
                        return rows[index]
                    }
                }
            val cache = Cache(emptyList())
            val repository = CachingConversationRepository(Source(ThreadSnapshot(guarded)), cache, "h", processingDispatcher = worker)
            val gate = CompletableDeferred<Unit>()
            val readings = mutableListOf<ThreadSnapshot>()
            val job =
                backgroundScope.launch {
                    repository.observeThreadSnapshot("c").collect {
                        assertFalse(worker.running)
                        readings += it
                        gate.await()
                    }
                }
            drain(worker)
            assertSame(guarded, readings.single().rows) // no cache base: merge returns the receiver
            assertEquals(0, reads)
            assertTrue(cache.writes.isEmpty())
            gate.complete(Unit)
            runCurrent()
            assertEquals(0, reads)
            drain(worker)
            assertEquals(100_000, reads)
            assertSame(guarded, cache.writes.single()) // write sees untrimmed drawn rows
            job.cancel()
        }

    @Test
    fun boundaryInvariant_suppressionAndDisconnectKeepUnsignedOrder() =
        runTest(UnconfinedTestDispatcher()) {
            val old = row("old")
            val mine =
                row("mine", role = Role.User).let {
                    it.copy(message = it.message.copy(attachments = listOf(MessageAttachment("file", "name"))))
                }
            val newest = row("new")
            val transient = row("stream", streaming = true)
            val upper = Long.MAX_VALUE.toULong() + 1u
            val coverage =
                HistoryCoverage(
                    unsignedSpans = emptyList(),
                    unsignedRowOrder = mapOf(historyIdentity(old.mergeIdentity()) to upper),
                )
            val position = HistoryPosition("", false, coverage)
            val cache = Cache(listOf(old, mine), position)
            val source =
                Source(
                    ThreadSnapshot(
                        listOf(mine, newest, transient),
                        unsignedHistoryOrder = mapOf(newest.mergeIdentity() to ULong.MAX_VALUE),
                    ),
                )
            val repository =
                CachingConversationRepository(source, cache, "h", processingDispatcher = UnconfinedTestDispatcher(testScheduler))
            val readings = mutableListOf<ThreadSnapshot>()
            val job = launch { repository.observeThreadSnapshot("c").collect { readings += it } }
            assertEquals(listOf(old, mine, newest, transient), readings.last().rows)
            repeat(2) {
                source.snapshots.value = ThreadSnapshot(emptyList(), setOf("mine", "pending$it"))
                assertEquals(listOf(old), readings.last().rows)
            }
            val delivered = mine.copy(message = mine.message.copy(attachments = listOf(MessageAttachment("file"))))
            source.snapshots.value =
                ThreadSnapshot(
                    listOf(delivered, newest, transient),
                    unsignedHistoryOrder = mapOf(newest.mergeIdentity() to ULong.MAX_VALUE),
                )
            assertEquals(listOf(old, mine, newest, transient), readings.last().rows)
            source.snapshots.value = ThreadSnapshot(emptyList())
            assertEquals(listOf(old, mine, newest), readings.last().rows)
            val middle = row("middle")
            source.snapshots.value = ThreadSnapshot(listOf(middle), unsignedHistoryOrder = mapOf(middle.mergeIdentity() to upper + 1u))
            // Old's cached and new's live unsigned order must both survive the empty rebase.
            assertEquals(listOf(old, mine, middle, newest), readings.last().rows)
            assertEquals(ThreadReadEvidence(), readings.last().readEvidence)
            job.cancel()
        }

    @Test fun identityInvariant_overlapReplayAndReconnectKeepCacheOnlyNeighbours() =
        runTest(UnconfinedTestDispatcher()) {
            val a = row("a")
            val b = row("b")
            val c = row("c")
            val neighbour = row("offer")
            val seed = listOf(a, neighbour, b, c)
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            for (page in listOf(listOf(a), listOf(b), listOf(c), listOf(a, b, c), emptyList())) {
                val source = Source(ThreadSnapshot(page))
                val repository =
                    CachingConversationRepository(
                        source,
                        Cache(seed),
                        "h",
                        processingDispatcher = dispatcher,
                    )
                val readings = mutableListOf<ThreadSnapshot>()
                val job = launch { repository.observeThreadSnapshot("c").collect { readings += it } }
                assertEquals(seed, readings.last().rows)
                source.snapshots.value = ThreadSnapshot(emptyList())
                source.snapshots.value = ThreadSnapshot(page)
                assertEquals(seed, readings.last().rows)
                repeat(2) { replay ->
                    source.snapshots.value =
                        ThreadSnapshot(
                            listOf(a, b, c),
                            readEvidence = ThreadReadEvidence(facts = mapOf((replay + 1).toULong() to true)),
                        )
                    assertEquals(seed, readings.last().rows)
                    assertEquals(listOf("a", "offer", "b", "c"), readings.last().rows.map { (it as ThreadItem.MessageItem).message.id })
                }
                job.cancel()
            }
        }

    @Test fun snapshotInvariant_heldGenerationKeepsRowsSuppressionOrderAndEvidenceTogether() =
        runTest {
            val worker = HeldWorker()
            val a = row("a", role = Role.User)
            val b = row("b", role = Role.User)
            val cache = Cache(listOf(a, b))
            val first =
                ThreadSnapshot(
                    listOf(a),
                    setOf("b"),
                    unsignedHistoryOrder = mapOf(a.mergeIdentity() to 1uL),
                    readEvidence = ThreadReadEvidence(versions = mapOf(a to setOf(1uL))),
                )
            val second =
                ThreadSnapshot(
                    listOf(b),
                    setOf("a"),
                    unsignedHistoryOrder = mapOf(b.mergeIdentity() to 2uL),
                    readEvidence = ThreadReadEvidence(versions = mapOf(b to setOf(2uL))),
                )
            val source = Source(first)
            val repository = CachingConversationRepository(source, cache, "h", processingDispatcher = worker)
            val readings = mutableListOf<ThreadSnapshot>()
            val job = backgroundScope.launch { repository.observeThreadSnapshot("c").collect { readings += it } }
            runCurrent()
            worker.next() // initialize restored order
            runCurrent()
            assertTrue(worker.count > 0) // first generation is captured, merge is held
            source.snapshots.value = second
            drain(worker)
            assertEquals(2, readings.size)
            for ((original, result) in listOf(first, second).zip(readings)) {
                assertEquals(original.rows, result.rows)
                assertSame(original.suppressedUserMessageIds, result.suppressedUserMessageIds)
                assertSame(original.unsignedHistoryOrder, result.unsignedHistoryOrder)
                assertSame(original.readEvidence, result.readEvidence)
            }
            assertEquals(listOf(listOf(a), listOf(b)), cache.writes)
            job.cancel()
        }

    @Test fun equalityInvariant_listObserverWaitsForWorkerAndSuppressesUnchangedRows() =
        runTest {
            val worker = HeldWorker()
            val a = row("a")
            val source = Source(ThreadSnapshot(listOf(a)))
            val cache = Cache(emptyList())
            val repository = CachingConversationRepository(source, cache, "h", processingDispatcher = worker)
            val readings = mutableListOf<List<ThreadItem>>()
            val job = backgroundScope.launch { repository.observeMessages("c").collect { readings += it } }
            runCurrent()
            worker.next() // initialize order
            runCurrent()
            worker.next() // merge
            runCurrent()
            assertTrue("list equality is held on the worker", worker.count > 0)
            assertTrue(readings.isEmpty())
            drain(worker)
            assertEquals(listOf(listOf(a)), readings)
            source.snapshots.value = ThreadSnapshot(listOf(a), readEvidence = ThreadReadEvidence(facts = mapOf(1uL to true)))
            drain(worker)
            assertEquals(listOf(listOf(a)), readings)
            assertEquals(listOf(listOf(a)), cache.writes)
            job.cancel()
        }

    @Test fun cancellationInvariant_heldWorkerCannotPublishOrWriteAfterExit() =
        runTest {
            val worker = HeldWorker()
            val cache = Cache(listOf(row("old")))
            val source = Source(ThreadSnapshot(listOf(row("new"))))
            val repository = CachingConversationRepository(source, cache, "h", processingDispatcher = worker)
            val readings = mutableListOf<ThreadSnapshot>()
            val job = backgroundScope.launch { repository.observeThreadSnapshot("c").collect { readings += it } }
            runCurrent()
            worker.next() // initialize order, then hold the captured live merge
            runCurrent()
            assertTrue(worker.count > 0)
            job.cancel()
            drain(worker)
            assertTrue(readings.isEmpty())
            assertTrue(cache.writes.isEmpty())
        }
}
