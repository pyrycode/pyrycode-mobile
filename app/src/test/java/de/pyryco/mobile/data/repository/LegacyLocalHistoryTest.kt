package de.pyryco.mobile.data.repository

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.pyryco.mobile.data.cache.ConversationCacheDatabase
import de.pyryco.mobile.data.cache.MigratingConversationCache
import de.pyryco.mobile.data.cache.OwnedLegacyRow
import de.pyryco.mobile.data.cache.legacyRowKey
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.ui.conversations.thread.ComposerDraftStore
import de.pyryco.mobile.ui.conversations.thread.ThreadHistoryTail
import de.pyryco.mobile.ui.conversations.thread.ThreadViewModel
import de.pyryco.mobile.ui.conversations.thread.fragmentedHistoryFixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.minutes

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class LegacyLocalHistoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private var database: ConversationCacheDatabase? = null

    private fun open(
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        beforeCommit: suspend () -> Unit = {},
    ): MigratingConversationCache {
        database?.close()
        val db =
            Room
                .databaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    ConversationCacheDatabase::class.java,
                    File(temporary.root, "history.db").absolutePath,
                ).build()
        database = db
        return MigratingConversationCache(File(temporary.root, "files"), db, dispatcher, beforeCommit)
    }

    @After fun close() {
        database?.close()
    }

    private fun rows(count: Int) =
        (0 until count).map {
            ThreadItem.MessageItem(Message("row-$it", "s", Role.User, "Row $it.", Instant.fromEpochSeconds(it.toLong()), false))
        }

    private suspend fun seed(
        rows: List<ThreadItem>,
        coverage: HistoryCoverage? = null,
    ): MigratingConversationCache {
        val cache = open()
        cache.readThread("host", "c")
        cache.writeThread("host", "c", rows).getOrThrow()
        if (coverage != null) cache.writeHistoryPosition("host", "c", HistoryPosition("saved", false, coverage)).getOrThrow()
        return cache
    }

    private class Live :
        ConversationRepository by FakeConversationRepository(),
        ThreadSnapshotSource {
        val snapshots = MutableStateFlow(ThreadSnapshot(emptyList()))
        val reply = CompletableDeferred<HistoryPage>()
        val asks = mutableListOf<String>()
        val requested = Channel<String>(Channel.UNLIMITED)

        override fun observeThreadSnapshot(conversationId: String) = snapshots

        override suspend fun requestHistory(
            conversationId: String,
            cursor: String,
            limit: Int,
        ): HistoryPage {
            asks += cursor
            requested.send(cursor)
            return reply.await()
        }
    }

    private class PausedRead : CoroutineDispatcher() {
        val pauseNext = AtomicBoolean(false)
        val entered = CompletableDeferred<Unit>()
        private var pending: Pair<CoroutineContext, Runnable>? = null

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            if (pauseNext.getAndSet(false)) {
                pending = context to block
                entered.complete(Unit)
            } else {
                Dispatchers.IO.dispatch(context, block)
            }
        }

        fun release() {
            pending?.let { (context, block) -> Dispatchers.IO.dispatch(context, block) }
            pending = null
        }
    }

    private fun TestScope.watch(repository: CachingConversationRepository): Pair<Channel<ThreadSnapshot>, kotlinx.coroutines.Job> {
        val readings = Channel<ThreadSnapshot>(Channel.UNLIMITED)
        val job = backgroundScope.launch { repository.observeThreadSnapshot("c").collect { readings.send(it) } }
        return readings to job
    }

    private suspend fun Channel<ThreadSnapshot>.awaitRows(predicate: (ThreadSnapshot) -> Boolean): ThreadSnapshot {
        while (true) {
            val reading = receive()
            if (predicate(reading)) return reading
        }
    }

    @Test fun boundedOpeningInvariant_fragmentedRoomWindow() =
        runTest(timeout = 3.minutes) {
            val (coverage, rows) = fragmentedHistoryFixture((0 until 18000).toList())
            val seed = open()
            seed.readThread("host", "c")
            seed.writeThread("host", "c", rows).getOrThrow()
            seed.writeHistoryPosition("host", "c", HistoryPosition("saved", false, coverage)).getOrThrow()
            val cache = open()
            val repository = CachingConversationRepository(FakeConversationRepository(), cache, "host")
            val reading = repository.observeThreadSnapshot("c").first()
            assertEquals(rows.takeLast(200), reading.rows)
            assertEquals(200, cache.legacyReadCounts.rowReads)
            assertEquals(200, cache.legacyReadCounts.rowDecodes)
            assertTrue(cache.legacyReadCounts.ordinalReads <= 1)
            assertTrue(cache.legacyReadCounts.evidenceReads <= 1002)
            assertTrue(cache.legacyReadCounts.evidenceDecodes <= 1002)
            assertEquals(1uL, requireNotNull(reading.savedLegacyHistory).unknownEdge)
            println("event=bounded_room_open fixture_rows=18000 durable_entries=36000 spans=18000 counts=${cache.legacyReadCounts}")
        }

    @Test fun continuationInvariant_visitsEverySavedRowOnce() =
        runTest(timeout = 3.minutes) {
            val (coverage, rows) = fragmentedHistoryFixture((0 until 18000).toList())
            seed(rows, coverage)
            val cache = open()
            val repository = CachingConversationRepository(Live(), cache, "host")
            val (readings, job) = watch(repository)
            var reading = readings.receive()
            val visited = reading.rows.mapTo(HashSet()) { it.legacyRowKey() }
            assertEquals(rows.takeLast(200), reading.rows)
            for (count in 400..18000 step 200) {
                assertTrue(repository.revealOlderSavedHistory("c"))
                reading = readings.awaitRows { it.rows.size == count }
                assertEquals(rows.takeLast(count), reading.rows)
                val added = reading.rows.take(200).map { it.legacyRowKey() }
                assertTrue(added.none { it in visited })
                visited += added
                assertEquals(count, visited.size)
                assertTrue(cache.legacyReadCounts.rowReads <= 200 && cache.legacyReadCounts.ordinalReads <= 1)
            }
            assertFalse(repository.revealOlderSavedHistory("c"))
            val saved = requireNotNull(reading.savedLegacyHistory).coverage
            assertEquals(coverage.unsignedSpans, saved.unsignedSpans)
            assertEquals(coverage.unsignedGaps, saved.unsignedGaps)
            assertEquals(coverage.unsignedRowOrder, saved.unsignedRowOrder)
            assertEquals(coverage.unsignedRowEntries, saved.unsignedRowEntries)
            assertEquals(coverage.proofs, saved.proofs)
            assertEquals(coverage.unsignedWalks, saved.unsignedWalks)
            assertTrue(saved.unsignedUnknown)
            job.cancelAndJoin()
            assertEquals(rows, open().readThread("host", "c"))
        }

    @Test fun boundedEvidenceInvariant_continuousSpan() =
        runTest(timeout = 3.minutes) {
            val rows = rows(201)
            val order = rows.mapIndexed { index, row -> row.historyKeys().first() to (99800 + index).toULong() }.toMap()
            val older = (2..18001).map { it.toULong() }
            val coverage =
                HistoryCoverage(
                    unsignedSpans = listOf(UnsignedHistorySpan(1u, 100000u)),
                    unsignedRowOrder = order,
                    unsignedRowEntries = order.mapValues { setOf(it.value) },
                    proofs = historyRowProofs(rows),
                    unsignedCursors =
                        older.associateWith { "old-cursor-$it" } + listOf(1uL, 99801uL, 99900uL, 100000uL).associateWith { "cursor-$it" },
                    unsignedWalks = older.associateWith { "old-walk-$it" } + listOf(0uL, 99801uL).associateWith { "walk-$it" },
                    unsignedUnknown = true,
                )
            seed(rows, coverage)
            val cache = open()
            val reading = CachingConversationRepository(Live(), cache, "host").observeThreadSnapshot("c").first()
            assertEquals(rows.takeLast(200), reading.rows)
            assertEquals(200, cache.legacyReadCounts.rowReads)
            assertEquals(609, cache.legacyReadCounts.evidenceReads)
            assertEquals(608, cache.legacyReadCounts.evidenceDecodes)
            println("event=bounded_room_continuous unrelated_cells=36000 counts=${cache.legacyReadCounts}")
        }

    @Test fun continuationInvariant_staleTokenRecoversFromHeldAnchor() =
        runTest {
            val rows = rows(601)
            val cache = seed(rows)
            val repository = CachingConversationRepository(Live(), cache, "host")
            val (readings, job) = watch(repository)
            assertEquals(rows.takeLast(200), readings.receive().rows)
            val changed = rows.last().copy(message = rows.last().message.copy(content = "updated"))
            val removed = rows[500]
            cache.mutateLegacyRows("host", "c", listOf(OwnedLegacyRow(changed, null, null)), setOf(removed.legacyRowKey())).getOrThrow()
            assertTrue(repository.revealOlderSavedHistory("c"))
            assertEquals(rows.takeLast(400).filterNot { it == removed }, readings.awaitRows { it.rows.size == 399 }.rows)
            assertEquals(200, cache.legacyReadCounts.rowReads)
            job.cancelAndJoin()
            assertEquals(changed, open().readThread("host", "c").last())
        }

    @Test fun mergeInvariant_liveAndRemovalWin() =
        runTest {
            val rows = rows(401)
            seed(rows)
            val hold = PausedRead()
            val cache = open(dispatcher = hold)
            val live = Live()
            val repository = CachingConversationRepository(live, cache, "host")
            val (readings, job) = watch(repository)
            readings.receive()
            hold.pauseNext.set(true)
            val pending = async { repository.revealOlderSavedHistory("c") }
            hold.entered.await()
            val changed = rows[350].copy(message = rows[350].message.copy(content = "new live text"))
            val newest = this@LegacyLocalHistoryTest.rows(1000).last()
            live.snapshots.value = ThreadSnapshot(listOf(changed, rows.last(), newest))
            val updated = readings.awaitRows { changed in it.rows }
            assertEquals(201, updated.rows.size)
            live.snapshots.value = ThreadSnapshot(listOf(rows.last(), newest))
            readings.awaitRows { it.rows.none { row -> row.legacyRowKey() == changed.legacyRowKey() } }
            hold.release()
            assertTrue(pending.await())
            val merged = readings.awaitRows { it.rows.size == 400 }
            assertEquals((rows.drop(1).filterNot { it == rows[350] }) + newest, merged.rows)
            assertEquals(
                merged.rows.size,
                merged.rows
                    .map { it.legacyRowKey() }
                    .toSet()
                    .size,
            )
            live.snapshots.value = ThreadSnapshot(emptyList(), setOf(rows.last().message.id))
            readings.awaitRows { it.rows.size == 399 && rows.last() !in it.rows }
            live.snapshots.value = ThreadSnapshot(emptyList())
            assertFalse(rows.last() in readings.receive().rows)
            val delivered = rows.last().copy(message = rows.last().message.copy(content = "confirmed delivery"))
            live.snapshots.value = ThreadSnapshot(listOf(delivered, newest))
            readings.awaitRows { delivered in it.rows }
            live.snapshots.value = ThreadSnapshot(emptyList())
            assertTrue(delivered in readings.receive().rows)
            job.cancelAndJoin()
            assertEquals(rows.filterNot { it == rows[350] || it == rows.last() } + delivered + newest, open().readThread("host", "c"))
        }

    @Test fun demandInvariant_offlineAndHeldNewest() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                for (online in listOf(false, true)) {
                    val rows = rows(601)
                    val cache = seed(rows)
                    val live = Live()
                    val repository = CachingConversationRepository(live, cache, "host")
                    val available = MutableStateFlow(online)
                    val store = ViewModelStore()
                    val vm =
                        ThreadViewModel(
                            SavedStateHandle(mapOf("serverId" to "host", "conversationId" to "c")),
                            repository,
                            FakeConnectionStateSource(),
                            ComposerDraftStore(),
                            repositoryAvailable = available,
                        )
                    store.put("thread", vm)
                    val states = Channel<Pair<Int, Boolean>>(Channel.UNLIMITED)
                    val observer = backgroundScope.launch { vm.state.collect { states.send(it.items.size to it.hasOlderSavedHistory) } }

                    suspend fun size(expected: Int) {
                        while (states.receive() != (expected to (expected < rows.size))) { /* Skip optional projections. */ }
                    }
                    size(200)
                    repository.openingHistoryPosition("c")
                    runCurrent()
                    assertEquals(if (online) listOf("") else emptyList<String>(), live.asks)
                    assertTrue(vm.state.value.hasOlderSavedHistory)
                    vm.onDemandOlderHistory()
                    size(400)
                    assertEquals(if (online) listOf("") else emptyList<String>(), live.asks)
                    vm.onRetryOlderHistory()
                    size(600)
                    assertFalse(live.reply.isCompleted)
                    available.value = false
                    runCurrent()
                    available.value = true
                    runCurrent()
                    assertEquals(600, vm.state.value.items.size)
                    assertEquals(1, live.asks.size)
                    vm.onDemandOlderHistory()
                    size(601)
                    assertFalse(vm.state.value.hasOlderSavedHistory)
                    assertEquals(1, live.asks.size)
                    store.clear()
                    observer.cancelAndJoin()
                }
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test fun openingInvariant_emptyOrUnreadableRoomKeepsNewestRefresh() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                for (unreadable in listOf(false, true)) {
                    val cache = if (unreadable) seed(rows(1)) else open()
                    if (unreadable) {
                        withContext(Dispatchers.IO) {
                            requireNotNull(database).openHelper.writableDatabase.execSQL("UPDATE legacy_rows SET payload='invalid'")
                        }
                    }
                    val live = Live()
                    val repository = CachingConversationRepository(live, cache, "host")
                    val store = ViewModelStore()
                    val vm =
                        ThreadViewModel(
                            SavedStateHandle(mapOf("serverId" to "host", "conversationId" to "c")),
                            repository,
                            FakeConnectionStateSource(),
                            ComposerDraftStore(),
                        )
                    store.put("thread", vm)
                    val observer = backgroundScope.launch { vm.state.collect {} }
                    assertEquals("", live.requested.receive())
                    assertTrue(
                        vm.state
                            .first { it.historyTail == ThreadHistoryTail.Loading }
                            .items
                            .isEmpty(),
                    )
                    assertFalse(live.reply.isCompleted)
                    store.clear()
                    observer.cancelAndJoin()
                }
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test fun retirementInvariant_pendingWorkCannotResurrectOrCrossHosts() =
        runTest {
            for (hostRemoval in listOf(false, true)) {
                val rows = rows(401)
                val seeded = seed(rows)
                seeded.readThread("Host", "c")
                seeded.writeThread("Host", "c", rows.take(2)).getOrThrow()
                val hold = PausedRead()
                val cache = open(dispatcher = hold)
                val live = Live()
                val repository =
                    CachingConversationRepository(live, cache, "host", processingDispatcher = StandardTestDispatcher(testScheduler))
                val (readings, job) = watch(repository)
                readings.receive()
                hold.pauseNext.set(true)
                val page = async { repository.revealOlderSavedHistory("c") }
                hold.entered.await()
                val removal = async { if (hostRemoval) cache.removeHost("host").getOrThrow() else repository.delete("c") }
                runCurrent()
                hold.release()
                page.await()
                removal.await()
                live.snapshots.value = ThreadSnapshot(rows.takeLast(2))
                runCurrent()
                job.cancelAndJoin()
                val fresh = open()
                assertEquals(emptyList<ThreadItem>(), fresh.readThread("host", "c"))
                assertEquals(rows.take(2), fresh.readThread("Host", "c"))
                assertEquals(
                    emptyList<ThreadItem>(),
                    CachingConversationRepository(Live(), fresh, "host").observeThreadSnapshot("c").first().rows,
                )
            }
        }

    @Test fun coverageInvariant_partialReceiptsSplitRealGapsButNeverInventOmittedOnes() {
        val saved =
            SavedLegacyHistory(
                HistoryCoverage(
                    unsignedSpans = listOf(UnsignedHistorySpan(1u, 2u), UnsignedHistorySpan(101u, 102u)),
                    unsignedGaps = listOf(UnsignedHistoryGap(1u, 101u)),
                    unsignedUnknown = true,
                ),
                1u,
                false,
            )
        val split = saved.withNetwork(HistoryCoverage(unsignedSpans = listOf(UnsignedHistorySpan(50u, 51u)), unsignedUnknown = true))
        assertEquals(listOf(UnsignedHistoryGap(1u, 50u), UnsignedHistoryGap(51u, 101u)), split.unsignedGaps)
        val distant = saved.withNetwork(HistoryCoverage(unsignedSpans = listOf(UnsignedHistorySpan(900u, 901u)), unsignedUnknown = true))
        assertEquals(listOf(UnsignedHistoryGap(1u, 101u)), distant.unsignedGaps)
        assertTrue(distant.unsignedUnknown)
        val boundedUnknown =
            SavedLegacyHistory(
                HistoryCoverage(
                    unsignedSpans = listOf(UnsignedHistorySpan(101u, 102u)),
                    unsignedUnknown = true,
                    unsignedCursors = mapOf(1uL to "old-edge", 101uL to "new-edge"),
                ),
                1u,
                false,
            )
        assertEquals("old-edge", boundedUnknown.cursorFor(0u, HistoryCoverage(unknown = true)))
    }

    @Test fun coverageInvariant_unsignedSavedFactsCannotSeedServerExhaustion() =
        runTest {
            val rows = rows(2)
            val order = rows.zip(listOf(1uL, ULong.MAX_VALUE)).associate { (row, id) -> row.historyKeys().first() to id }
            val coverage =
                HistoryCoverage(
                    unsignedSpans = listOf(UnsignedHistorySpan(1u, 1u), UnsignedHistorySpan(ULong.MAX_VALUE, ULong.MAX_VALUE)),
                    unsignedGaps = listOf(UnsignedHistoryGap(1u, ULong.MAX_VALUE)),
                    unsignedRowOrder = order,
                    unsignedRowEntries = order.mapValues { setOf(it.value) },
                    proofs = historyRowProofs(rows),
                )
            val cache = seed(rows, coverage)
            cache.writeHistoryPosition("host", "c", HistoryPosition("saved", true, coverage)).getOrThrow()
            val repository = CachingConversationRepository(Live(), cache, "host")
            val (readings, job) = watch(repository)
            assertEquals(rows, readings.receive().rows)
            val position = requireNotNull(repository.openingHistoryPosition("c"))
            assertFalse(position.atStart)
            assertTrue(requireNotNull(position.coverage).unsignedIncomplete)
            job.cancelAndJoin()
        }

    @Test fun retirementInvariant_lateSaveAfterReAdmissionCannotReviveAClosedWindow() =
        runTest {
            val rows = rows(401)
            val cache = seed(rows)
            val live = Live()
            val repository = CachingConversationRepository(live, cache, "host")
            val (readings, job) = watch(repository)
            readings.receive()
            job.cancelAndJoin()
            cache.removeHost("host").getOrThrow()
            cache.admitHost("host")
            val late = rows.first()
            live.snapshots.value = ThreadSnapshot(listOf(late))
            val key = late.historyKeys().first()
            val coverage =
                HistoryCoverage(
                    unsignedSpans = listOf(UnsignedHistorySpan(1u, 1u)),
                    unsignedRowOrder = mapOf(key to 1uL),
                    unsignedRowEntries = mapOf(key to setOf(1uL)),
                    proofs = historyRowProofs(listOf(late)),
                )
            repository.writeHistoryPosition("c", HistoryPosition("late", false, coverage))
            assertEquals(emptyList<ThreadItem>(), open().readThread("host", "c"))
        }

    @Test fun retirementInvariant_dispatchAfterValidationCannotReviveReadmittedHost() =
        runTest {
            val rows = rows(401)
            val cache = seed(rows)
            val hold = PausedRead()
            val live = Live()
            val repository = CachingConversationRepository(live, cache, "host", processingDispatcher = hold)
            val (readings, job) = watch(repository)
            readings.receive()
            job.cancelAndJoin()
            val changed = rows.last().copy(message = rows.last().message.copy(content = "late changed row"))
            live.snapshots.value = ThreadSnapshot(listOf(changed))
            val key = changed.historyKeys().first()
            val coverage =
                HistoryCoverage(
                    unsignedSpans = listOf(UnsignedHistorySpan(1u, 1u)),
                    unsignedRowOrder = mapOf(key to 1uL),
                    unsignedRowEntries = mapOf(key to setOf(1uL)),
                    proofs = historyRowProofs(listOf(changed)),
                )
            hold.pauseNext.set(true)
            val save = async { repository.writeHistoryPosition("c", HistoryPosition("late", false, coverage)) }
            hold.entered.await()
            cache.removeHost("host").getOrThrow()
            cache.admitHost("host")
            hold.release()
            save.await()
            val fresh = open()
            assertEquals(emptyList<ThreadItem>(), fresh.readThread("host", "c"))
            assertEquals(null, fresh.readHistoryPosition("host", "c"))
        }

    @Test fun suppressionInvariant_unloadedUserRemovalPersistsWithoutDaemonReplyOrPaging() =
        runTest {
            val rows = rows(401)
            val cache = seed(rows)
            val live = Live()
            val repository = CachingConversationRepository(live, cache, "host")
            val (readings, job) = watch(repository)
            assertEquals(rows.takeLast(200), readings.receive().rows)
            val newest = backgroundScope.launch { repository.requestHistory("c", "", 200) }
            assertEquals("", live.requested.receive())
            live.snapshots.value = ThreadSnapshot(emptyList(), setOf(rows.first().message.id))
            assertEquals(rows.takeLast(200), readings.receive().rows)
            // Accept the identical visible list, then flush on exit without a newest reply.
            runCurrent()
            job.cancelAndJoin()
            assertFalse(live.reply.isCompleted)
            newest.cancelAndJoin()
            val fresh = open()
            assertEquals(rows.drop(1), fresh.readThread("host", "c"))
        }

    @Test fun retirementInvariant_allMutationKindsRejectCapturedConversationAndHostEpochs() =
        runTest {
            for (host in listOf(false, true)) {
                val rows = rows(2)
                val cache = seed(rows)
                val epoch = cache.legacyEpoch("host", "c")
                if (host) {
                    cache.removeHost("host").getOrThrow()
                    cache.admitHost("host")
                } else {
                    cache.removeConversation("host", "c").getOrThrow()
                }
                val current = HistoryPosition("new-owner", false, HistoryCoverage(unknown = true))
                cache.writeThread("host", "c", rows.takeLast(1)).getOrThrow()
                cache.writeHistoryPosition("host", "c", current).getOrThrow()
                val retiredRows = cache.mutateLegacyRows("host", "c", listOf(OwnedLegacyRow(rows.first(), null, null)), emptySet(), epoch)
                assertTrue(retiredRows.isFailure)
                assertTrue(cache.writeOwnedLegacyCoverage("host", "c", current.copy(cursor = "old-owner"), null, rows, epoch).isFailure)
                assertTrue(cache.writeHistoryPosition("host", "c", null, epoch).isFailure)
                val fresh = open()
                assertEquals(rows.takeLast(1), fresh.readThread("host", "c"))
                assertEquals("new-owner", fresh.readHistoryPosition("host", "c")?.cursor)
            }
        }

    @Test fun suppressionInvariant_coverageMustCommitUnloadedDeletionBeforeSatisfyingItsCapture() =
        runTest {
            val rows = rows(401)
            val cache = seed(rows)
            val live = Live()
            val repository =
                CachingConversationRepository(
                    live,
                    cache,
                    "host",
                    processingDispatcher = StandardTestDispatcher(testScheduler),
                )
            val (readings, job) = watch(repository)
            readings.receive()
            live.snapshots.value = ThreadSnapshot(emptyList(), setOf(rows.first().message.id))
            readings.receive()
            // A later capture no longer carries the connection-local suppression.
            live.snapshots.value = ThreadSnapshot(emptyList())
            repository.writeHistoryPosition("c", HistoryPosition("newest", false, HistoryCoverage(unknown = true)))
            job.cancelAndJoin()
            assertEquals(rows.drop(1), open().readThread("host", "c"))
        }

    @Test fun retirementInvariant_initialReadCannotPublishAfterConfirmedRemoval() =
        runTest {
            for (hostRemoval in listOf(false, true)) {
                seed(rows(401))
                val hold = PausedRead()
                val cache = open(dispatcher = hold)
                val repository = CachingConversationRepository(Live(), cache, "host")
                hold.pauseNext.set(true)
                val (readings, job) = watch(repository)
                hold.entered.await()
                val removal = async { if (hostRemoval) cache.removeHost("host").getOrThrow() else repository.delete("c") }
                runCurrent()
                hold.release()
                removal.await()
                job.join()
                assertTrue(readings.tryReceive().isFailure)
                assertTrue(open().readThread("host", "c").isEmpty())
            }
        }

    @Test fun retirementInvariant_heldSaveIsRemovedBeforeRestart() =
        runTest {
            for (hostRemoval in listOf(false, true)) {
                val rows = rows(401)
                seed(rows)
                val hold = AtomicBoolean(false)
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val cache =
                    open(beforeCommit = {
                        if (hold.getAndSet(false)) {
                            entered.complete(Unit)
                            release.await()
                        }
                    })
                val live = Live()
                val repository =
                    CachingConversationRepository(live, cache, "host", processingDispatcher = StandardTestDispatcher(testScheduler))
                val (readings, job) = watch(repository)
                readings.receive()
                hold.set(true)
                val changed = rows.last().copy(message = rows.last().message.copy(content = "new settled content"))
                live.snapshots.value = ThreadSnapshot(listOf(changed))
                readings.awaitRows { changed in it.rows }
                runCurrent()
                advanceTimeBy(101)
                runCurrent()
                entered.await()
                val removal = async { if (hostRemoval) cache.removeHost("host").getOrThrow() else repository.delete("c") }
                runCurrent()
                release.complete(Unit)
                removal.await()
                if (hostRemoval) cache.admitHost("host")
                job.cancelAndJoin()
                assertTrue(open().readThread("host", "c").isEmpty())
            }
        }

    @Test fun openingInvariant_recollectionReleasesTheOriginalSeed() =
        runTest {
            val rows = rows(401)
            val cache = seed(rows)
            val subscribed = Channel<Unit>(Channel.UNLIMITED)
            val ready = AtomicBoolean(false)
            val never = CompletableDeferred<Unit>()
            val live =
                object : ConversationRepository by FakeConversationRepository(), ThreadSnapshotSource {
                    override fun observeThreadSnapshot(conversationId: String) =
                        flow {
                            subscribed.send(Unit)
                            if (!ready.get()) never.await()
                            emit(ThreadSnapshot(emptyList()))
                            awaitCancellation()
                        }
                }
            val repository = CachingConversationRepository(live, cache, "host")
            val opening = async { repository.openingHistoryPosition("c") }
            val (_, first) = watch(repository)
            subscribed.receive()
            first.cancelAndJoin()
            assertFalse(opening.isCompleted)
            ready.set(true)
            val (readings, second) = watch(repository)
            assertEquals(rows.takeLast(200), readings.receive().rows)
            assertTrue(opening.await() != null)
            second.cancelAndJoin()
        }
}
