package de.pyryco.mobile.data.cache

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.CachingConversationRepository
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.HistoryCoverage
import de.pyryco.mobile.data.repository.HistoryPosition
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.ThreadSnapshot
import de.pyryco.mobile.data.repository.ThreadSnapshotSource
import de.pyryco.mobile.data.repository.UnsignedHistorySpan
import de.pyryco.mobile.data.repository.historyKeys
import de.pyryco.mobile.data.repository.historyRowProofs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OwnedLegacyCoverageConcurrencyTest {
    @get:Rule val temporary = TemporaryFolder()
    private var database: ConversationCacheDatabase? = null

    private fun open(
        name: String = "cache",
        beforeCommit: suspend () -> Unit = {},
    ): MigratingConversationCache {
        database?.close()
        val db =
            Room
                .databaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    ConversationCacheDatabase::class.java,
                    File(temporary.root, "$name.db").absolutePath,
                ).build()
        database = db
        return MigratingConversationCache(File(temporary.root, name), db, Dispatchers.IO, beforeCommit)
    }

    @After fun close() {
        database?.close()
    }

    private fun pendingKeys(repository: CachingConversationRepository): Set<String> {
        val field = CachingConversationRepository::class.java.getDeclaredField("acceptedRows")
        field.isAccessible = true
        val conversations = field.get(repository) as Map<*, *>
        return (conversations["c"] as? Map<*, *>)
            ?.keys
            .orEmpty()
            .map { it.toString() }
            .toSet()
    }

    @Test fun intentInvariant_successPrunesOnlySatisfiedRows() =
        runTest {
            for (coverage in listOf(false, true)) {
                val failAt = AtomicInteger()
                val cache =
                    open("intent-$coverage") {
                        if (failAt.get() > 0 && failAt.decrementAndGet() == 0) throw IOException("controlled")
                    }
                val initial = listOf(row(1))
                cache.readThread("Host", "c")
                cache.writeThread("Host", "c", initial).getOrThrow()
                cache.writeHistoryPosition("Host", "c", position(initial)).getOrThrow()
                val source = Source()
                val worker = UnconfinedTestDispatcher(testScheduler)
                val repository = CachingConversationRepository(source, cache, "Host", processingDispatcher = worker)
                val restored = CompletableDeferred<Unit>()
                val accepted = CompletableDeferred<Unit>()
                source.afterEmission = { if (it.rows == initial + row(2)) accepted.complete(Unit) }
                val reader =
                    backgroundScope.launch(worker) {
                        repository.observeThreadSnapshot("c").collect {
                            restored.complete(Unit)
                        }
                    }
                restored.await()
                source.snapshots.value = ThreadSnapshot(initial + row(2))
                accepted.await()
                runCurrent()
                assertEquals(setOf(row(2).legacyRowKey()), pendingKeys(repository))
                if (coverage) {
                    failAt.set(2) // Successful rows retire intents even when the evidence transaction fails.
                    repository.writeHistoryPosition("c", position(initial + row(2)))
                    assertTrue(pendingKeys(repository).isEmpty())
                    assertEquals(position(initial), cache.readHistoryPosition("Host", "c"))
                }
                reader.cancelAndJoin() // Ordinary success and final flush share the same pruning boundary.
                assertTrue("successful $coverage writer retained satisfied payloads", pendingKeys(repository).isEmpty())
                assertEquals(initial + row(2), cache.readThread("Host", "c"))
            }
        }

    @Test fun intentInvariant_rowFailureKeepsRetryableWork() =
        runTest {
            val fail = AtomicBoolean()
            val failedAttempts = AtomicInteger()
            val historyCommit = CompletableDeferred<Unit>()
            val finishCommit = CompletableDeferred<Unit>()
            val writerFailed = CompletableDeferred<Unit>()
            val cache =
                open {
                    if (fail.get()) {
                        val origin = currentCoroutineContext()[CoroutineName]?.name
                        println("controlled_commit origin=$origin virtualTime=${testScheduler.currentTime} heap=${Runtime.getRuntime().maxMemory()}")
                        if (origin == "history") {
                            historyCommit.complete(Unit)
                            finishCommit.await()
                        }
                        failedAttempts.incrementAndGet()
                        if (origin == "writer") writerFailed.complete(Unit)
                        throw IOException("controlled")
                    }
                }
            cache.readThread("Host", "c")
            val readingHistory = CompletableDeferred<Unit>()
            val finishHistory = CompletableDeferred<Unit>()
            val intercepted =
                object : ConversationCache by cache, OwnedLegacyCoverageCache by cache {
                    override suspend fun readHistoryPosition(
                        serverId: String,
                        conversationId: String,
                    ): HistoryPosition? {
                        val result = cache.readHistoryPosition(serverId, conversationId)
                        readingHistory.complete(Unit)
                        finishHistory.await()
                        return result
                    }
                }
            val source = Source()
            val worker = UnconfinedTestDispatcher(testScheduler)
            val repository = CachingConversationRepository(source, intercepted, "Host", processingDispatcher = worker)
            val restored = CompletableDeferred<Unit>()
            val accepted = CompletableDeferred<Unit>()
            source.afterEmission = { if (it.rows == listOf(row(1))) accepted.complete(Unit) }
            val reader = backgroundScope.launch(worker + CoroutineName("writer")) { repository.observeThreadSnapshot("c").collect { restored.complete(Unit) } }
            var saving: kotlinx.coroutines.Deferred<Unit>? = null
            try {
                restored.await()
                source.snapshots.value = ThreadSnapshot(listOf(row(1)))
                readingHistory.await()
                runCurrent()
                assertFalse("scheduler drain cannot complete the held history read", accepted.isCompleted)
                finishHistory.complete(Unit)
                accepted.await() // Source emission returns after the repository's writer accepts this exact snapshot.
                assertEquals(setOf(row(1).legacyRowKey()), pendingKeys(repository))
                fail.set(true)
                saving = async(CoroutineName("history")) { repository.writeHistoryPosition("c", position(listOf(row(1)))) }
                historyCommit.await()
                advanceTimeBy(101)
                runCurrent()
                finishCommit.complete(Unit)
                saving.await()
                writerFailed.await()
                assertEquals(1, failedAttempts.get())
                assertEquals("row failure must retain the accepted intent", setOf(row(1).legacyRowKey()), pendingKeys(repository))
                assertTrue(cache.readThread("Host", "c").isEmpty())
                fail.set(false)
                source.capture = ThreadSnapshot(emptyList()) // Retry must use the retained intent, not a resupplied live row.
                repository.writeHistoryPosition("c", position(listOf(row(1))))
                assertTrue(pendingKeys(repository).isEmpty())
                reader.cancelAndJoin()
                assertEquals(listOf(row(1)), open().readThread("Host", "c"))
            } finally {
                fail.set(false)
                finishHistory.complete(Unit)
                finishCommit.complete(Unit)
                withContext(NonCancellable) {
                    saving?.await()
                    reader.cancelAndJoin()
                }
            }
        }

    @Test fun intentInvariant_concurrentAcceptanceSurvivesOlderSuccess() =
        runTest {
            for ((coverage, replace) in listOf(false to false, true to false, false to true, true to true)) {
                val name = "concurrent-intent-$coverage-$replace"
                val cache = open(name)
                cache.readThread("Host", "c")
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val returned = CompletableDeferred<Unit>()
                val once = AtomicBoolean(true)
                val intercepted =
                    object : ConversationCache by cache, OwnedLegacyCoverageCache by cache {
                        override suspend fun mutateLegacyRows(
                            host: String,
                            conversation: String,
                            upserts: List<OwnedLegacyRow>,
                            removed: Set<String>,
                        ): Result<Unit> {
                            val result = cache.mutateLegacyRows(host, conversation, upserts, removed)
                            if (once.compareAndSet(true, false)) {
                                entered.complete(Unit)
                                release.await()
                                returned.complete(Unit)
                            }
                            return result
                        }
                    }
                val source = Source()
                val worker = UnconfinedTestDispatcher(testScheduler)
                val repository = CachingConversationRepository(source, intercepted, "Host", processingDispatcher = worker)
                val restored = CompletableDeferred<Unit>()
                val reader = backgroundScope.launch(worker) { repository.observeThreadSnapshot("c").collect { restored.complete(Unit) } }
                restored.await()
                source.snapshots.value = ThreadSnapshot(listOf(row(1)))
                runCurrent()
                val save = if (coverage) async { repository.writeHistoryPosition("c", position(listOf(row(1)))) } else null
                if (!coverage) {
                    advanceTimeBy(101)
                    runCurrent()
                }
                entered.await()
                val newer = listOf(row(1, if (replace) "newer" else "text-1"), row(2))
                source.snapshots.value = ThreadSnapshot(newer)
                runCurrent()
                assertEquals(setOf(row(1).legacyRowKey(), row(2).legacyRowKey()), pendingKeys(repository))
                release.complete(Unit)
                returned.await()
                save?.await()
                yield()
                runCurrent()
                val pending = if (replace) newer else newer.drop(1)
                assertEquals(pending.mapTo(HashSet()) { it.legacyRowKey() }, pendingKeys(repository))
                reader.cancelAndJoin()
                assertTrue(pendingKeys(repository).isEmpty())
                assertEquals(newer, open(name).readThread("Host", "c"))
            }
        }

    @Test fun captureInvariant_firstSnapshotCleanupCannotRenewOldCoverage() =
        runTest {
            for (mode in 0..2) {
                val name = "capture-$mode"
                val cache = open(name)
                val oldRows = listOf(row(1, "old"))
                val newRows = listOf(row(1, "new"), row(2))
                cache.readThread("Host", "c")
                cache.writeThread("Host", "c", oldRows).getOrThrow()
                cache.writeHistoryPosition("Host", "c", position(oldRows)).getOrThrow()
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val delivered = CompletableDeferred<Unit>()
                val accepted = CompletableDeferred<Unit>()
                val finishDelivery = CompletableDeferred<Unit>()
                val finishSource = CompletableDeferred<Unit>()
                val calls = AtomicInteger()
                val source =
                    object : ConversationRepository by FakeConversationRepository(), ThreadSnapshotSource {
                        override fun observeThreadSnapshot(conversationId: String): Flow<ThreadSnapshot> =
                            flow {
                                if (calls.getAndIncrement() == 0) {
                                    try {
                                        emit(ThreadSnapshot(oldRows))
                                    } finally {
                                        entered.complete(Unit)
                                        release.await()
                                    }
                                } else {
                                    emit(ThreadSnapshot(newRows))
                                    accepted.complete(Unit)
                                    finishSource.await()
                                }
                            }
                    }
                val worker = UnconfinedTestDispatcher(testScheduler)
                val repository = CachingConversationRepository(source, cache, "Host", processingDispatcher = worker)
                repository.readHistoryPosition("c")
                val older = async { repository.writeHistoryPosition("c", position(oldRows)) }
                entered.await()
                val reader =
                    if (mode == 0) {
                        repository.writeHistoryPosition("c", position(newRows))
                        null
                    } else {
                        async(worker) {
                            repository.observeThreadSnapshot("c").collect {
                                delivered.complete(Unit)
                                if (mode == 1) finishDelivery.await()
                            }
                        }
                    }
                if (mode != 0) delivered.await()
                if (mode == 2) accepted.await()
                release.complete(Unit)
                older.await()
                finishDelivery.complete(Unit)
                finishSource.complete(Unit)
                reader?.await()
                val fresh = open(name)
                assertEquals("newer rows lost in capture mode $mode", newRows, fresh.readThread("Host", "c"))
                if (mode == 0) {
                    assertEquals(position(newRows), fresh.readHistoryPosition("Host", "c"))
                } else {
                    assertTrue(requireNotNull(fresh.readHistoryPosition("Host", "c")?.coverage).proofs.isEmpty())
                }
            }
        }

    private fun row(
        id: Int,
        text: String = "text-$id",
    ) = ThreadItem.MessageItem(
        Message("id-$id", "s", Role.User, text, Instant.fromEpochSeconds(id.toLong()), false),
    )

    private fun position(rows: List<ThreadItem>): HistoryPosition {
        val order = rows.mapIndexed { index, row -> row.historyKeys().first() to (index + 1).toULong() }.toMap()
        return HistoryPosition(
            "received",
            false,
            HistoryCoverage(
                unsignedSpans = listOf(UnsignedHistorySpan(1u, rows.size.toULong())),
                unsignedRowOrder = order,
                unsignedRowEntries = order.mapValues { setOf(it.value) },
                proofs = historyRowProofs(rows),
            ),
        )
    }

    private class Source :
        ConversationRepository by FakeConversationRepository(),
        ThreadSnapshotSource {
        val snapshots = MutableStateFlow(ThreadSnapshot(emptyList()))
        var capture: ThreadSnapshot? = null
        var afterEmission: (ThreadSnapshot) -> Unit = {}

        override fun observeThreadSnapshot(conversationId: String): Flow<ThreadSnapshot> =
            capture?.let { flowOf(it) } ?: flow {
                snapshots.collect {
                    emit(it)
                    afterEmission(it)
                }
            }

        override suspend fun delete(conversationId: String) = Unit
    }

    @Test fun heldInvariant_coverageIncludesNewerAcceptedRowsBeforeSatisfyingTheirCapture() =
        runTest {
            val cache = open()
            cache.readThread("Host", "c")
            cache.writeThread("Host", "c", listOf(row(1))).getOrThrow()
            val source = Source()
            val worker = UnconfinedTestDispatcher(testScheduler)
            val repository = CachingConversationRepository(source, cache, "Host", processingDispatcher = worker)
            val restored = CompletableDeferred<Unit>()
            val newer = CompletableDeferred<Unit>()
            val reader =
                backgroundScope.launch(worker) {
                    repository.observeThreadSnapshot("c").collect {
                        if (it.rows == listOf(row(1))) restored.complete(Unit)
                        if (it.rows == listOf(row(1), row(2))) newer.complete(Unit)
                    }
                }
            restored.await()
            source.snapshots.value = ThreadSnapshot(listOf(row(1), row(2)))
            newer.await()
            runCurrent()
            source.capture = ThreadSnapshot(listOf(row(1)))
            repository.writeHistoryPosition("c", position(listOf(row(1))))
            reader.cancelAndJoin()
            val fresh = open()
            assertEquals(listOf(row(1), row(2)), fresh.readThread("Host", "c"))
            assertEquals(historyRowProofs(listOf(row(1))), fresh.readHistoryPosition("Host", "c")?.coverage?.proofs)
        }

    @Test fun metadataInvariant_clearFencesDelayedReadWithoutDeletingRows() =
        runTest {
            val cache = open()
            val rows = listOf(row(1))
            val initial = position(rows)
            cache.readThread("Host", "c")
            cache.writeThread("Host", "c", rows).getOrThrow()
            cache.writeHistoryPosition("Host", "c", initial).getOrThrow()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val once = AtomicBoolean(true)
            val intercepted =
                object : ConversationCache by cache, OwnedLegacyCoverageCache by cache {
                    override suspend fun readHistoryPosition(
                        serverId: String,
                        conversationId: String,
                    ): HistoryPosition? {
                        val result = cache.readHistoryPosition(serverId, conversationId)
                        if (once.compareAndSet(true, false)) {
                            entered.complete(Unit)
                            release.await()
                        }
                        return result
                    }
                }
            val repository =
                CachingConversationRepository(Source(), intercepted, "Host", processingDispatcher = UnconfinedTestDispatcher(testScheduler))
            val reading = async { repository.readHistoryPosition("c") }
            entered.await()
            repository.writeHistoryPosition("c", null)
            assertNull(cache.readHistoryPosition("Host", "c"))
            release.complete(Unit)
            reading.await()
            repository.writeHistoryPosition("c", initial)
            val fresh = open()
            assertEquals(rows, fresh.readThread("Host", "c"))
            assertEquals(initial, fresh.readHistoryPosition("Host", "c"))
        }

    @Test fun revisionInvariant_rowSuccessSurvivesEvidenceFailureAndCancellation() =
        runTest {
            for (cancel in listOf(false, true)) {
                val failAt = AtomicInteger()
                val name = "revision-$cancel"
                val cache = open(name) { if (failAt.get() > 0 && failAt.decrementAndGet() == 0) throw IOException("controlled") }
                cache.readThread("Host", "c")
                cache.writeThread("Host", "c", listOf(row(1))).getOrThrow()
                cache.writeHistoryPosition("Host", "c", position(listOf(row(1)))).getOrThrow()
                val restoring = CompletableDeferred<Unit>()
                val restore = CompletableDeferred<Unit>()
                val committed = CompletableDeferred<Unit>()
                val finishCommit = CompletableDeferred<Unit>()
                val holdRestore = AtomicBoolean(true)
                val holdCommit = AtomicBoolean(cancel)
                val intercepted =
                    object : ConversationCache by cache, OwnedLegacyCoverageCache by cache {
                        override suspend fun readThread(
                            serverId: String,
                            conversationId: String,
                        ): List<ThreadItem> {
                            val result = cache.readThread(serverId, conversationId)
                            if (holdRestore.compareAndSet(true, false)) {
                                restoring.complete(Unit)
                                restore.await()
                            }
                            return result
                        }

                        override suspend fun mutateLegacyRows(
                            host: String,
                            conversation: String,
                            upserts: List<OwnedLegacyRow>,
                            removed: Set<String>,
                        ): Result<Unit> {
                            val result = cache.mutateLegacyRows(host, conversation, upserts, removed)
                            if (holdCommit.compareAndSet(true, false)) {
                                committed.complete(Unit)
                                finishCommit.await()
                            }
                            return result
                        }
                    }
                val source = Source()
                val worker = UnconfinedTestDispatcher(testScheduler)
                val repository = CachingConversationRepository(source, intercepted, "Host", processingDispatcher = worker)
                val delivered = CompletableDeferred<Unit>()
                val finishDelivery = CompletableDeferred<Unit>()
                val accepted = CompletableDeferred<Unit>()
                source.afterEmission = { if (it.rows == listOf(row(1))) accepted.complete(Unit) }
                val reader =
                    backgroundScope.launch(worker) {
                        repository.observeThreadSnapshot("c").collect {
                            assertEquals(listOf(row(1)), it.rows)
                            delivered.complete(Unit)
                            finishDelivery.await()
                        }
                    }
                var save: Job? = null
                try {
                    restoring.await()
                    source.snapshots.value = ThreadSnapshot(listOf(row(1), row(2)))
                    if (!cancel) failAt.set(2)
                    val saving = async { repository.writeHistoryPosition("c", position(listOf(row(1), row(2)))) }
                    save = saving
                    if (cancel) {
                        committed.await()
                        assertEquals(listOf(row(1), row(2)), cache.readThread("Host", "c"))
                        saving.cancel()
                        runCurrent()
                        assertFalse("cancellation must await the held non-cancellable commit", saving.isCompleted)
                        finishCommit.complete(Unit)
                    }
                    saving.join()
                    assertEquals(cancel, saving.isCancelled)
                    assertEquals(0, failAt.get())
                    assertEquals(listOf(row(1), row(2)), cache.readThread("Host", "c"))
                    assertEquals(position(listOf(row(1))), cache.readHistoryPosition("Host", "c"))
                    source.snapshots.value = ThreadSnapshot(listOf(row(1)))
                    restore.complete(Unit)
                    delivered.await()
                    runCurrent()
                    assertFalse("publication precedes writer acceptance", accepted.isCompleted)
                    finishDelivery.complete(Unit)
                    accepted.await() // Cancellation must follow final acceptance so finish() has a removal candidate.
                    reader.cancelAndJoin() // Final flush must remove row 2 despite matching the original restore.
                    val fresh = open(name)
                    assertEquals(
                        "final accepted snapshot must remove row 2 (cancel=$cancel)",
                        listOf(row(1)),
                        fresh.readThread("Host", "c"),
                    )
                    assertEquals(
                        setOf(row(1).historyKeys().first()),
                        fresh
                            .readHistoryPosition("Host", "c")
                            ?.coverage
                            ?.proofs
                            ?.keys,
                    )
                } finally {
                    restore.complete(Unit)
                    finishCommit.complete(Unit)
                    finishDelivery.complete(Unit)
                    withContext(NonCancellable) {
                        save?.cancelAndJoin()
                        reader.cancelAndJoin()
                    }
                }
            }
        }

    @Test fun captureInvariant_delayedCoverageCannotUndoNewerRows() =
        runTest {
            val cache = open()
            cache.readThread("Host", "c")
            val source = Source()
            val worker = UnconfinedTestDispatcher(testScheduler)
            val repository = CachingConversationRepository(source, cache, "Host", processingDispatcher = worker)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val old =
                backgroundScope.launch(worker) {
                    repository.observeThreadSnapshot("c").collect { snapshot ->
                        if (snapshot.rows == listOf(row(1, "old"))) {
                            entered.complete(Unit)
                            release.await()
                        }
                    }
                }
            source.snapshots.value = ThreadSnapshot(listOf(row(1, "old")))
            entered.await()
            source.snapshots.value = ThreadSnapshot(listOf(row(1, "new")))
            repository.writeHistoryPosition("c", position(listOf(row(1, "new"))))
            val newerDelivered = CompletableDeferred<Unit>()
            val newer = backgroundScope.launch(worker) { repository.observeThreadSnapshot("c").collect { newerDelivered.complete(Unit) } }
            newerDelivered.await()
            newer.cancelAndJoin()
            source.snapshots.value = ThreadSnapshot(emptyList()) // Old collector reconnects after the new save.
            release.complete(Unit)
            runCurrent()
            source.snapshots.value = ThreadSnapshot(listOf(row(3)))
            runCurrent()
            old.cancelAndJoin()
            val fresh = open()
            assertEquals(listOf(row(1, "new"), row(3)), fresh.readThread("Host", "c"))
            assertEquals(historyRowProofs(listOf(row(1, "new"))), fresh.readHistoryPosition("Host", "c")?.coverage?.proofs)
        }

    @Test fun retirementInvariant_coverageCannotResurrectRemovedOwners() =
        runTest {
            for (forget in listOf(false, true)) {
                val armed = AtomicBoolean()
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val name = "retired-$forget"
                val cache =
                    open(name) {
                        if (armed.compareAndSet(true, false)) {
                            entered.complete(Unit)
                            release.await()
                        }
                    }
                cache.readThread("Host", "c")
                cache.readThread("host", "c")
                cache.writeThread("host", "c", listOf(row(1, "other"))).getOrThrow()
                val source = Source()
                val worker = UnconfinedTestDispatcher(testScheduler)
                val repository = CachingConversationRepository(source, cache, "Host", processingDispatcher = worker)
                val initial = CompletableDeferred<Unit>()
                val reader = backgroundScope.launch(worker) { repository.observeThreadSnapshot("c").collect { initial.complete(Unit) } }
                initial.await()
                source.snapshots.value = ThreadSnapshot(listOf(row(1), row(2)))
                armed.set(true)
                val saving = async { repository.writeHistoryPosition("c", position(listOf(row(1), row(2)))) }
                entered.await()
                val removing = async { if (forget) cache.removeHost("Host").getOrThrow() else repository.delete("c") }
                runCurrent()
                assertFalse(removing.isCompleted)
                source.snapshots.value = ThreadSnapshot(listOf(row(1), row(3)))
                release.complete(Unit)
                saving.await()
                removing.await()
                reader.cancelAndJoin()
                val fresh = open(name)
                assertTrue(fresh.readThread("Host", "c").isEmpty())
                assertEquals(listOf(row(1, "other")), fresh.readThread("host", "c"))
            }
        }
}
