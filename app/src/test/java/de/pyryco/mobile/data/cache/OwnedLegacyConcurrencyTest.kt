package de.pyryco.mobile.data.cache

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.CachingConversationRepository
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.ThreadSnapshot
import de.pyryco.mobile.data.repository.ThreadSnapshotSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OwnedLegacyConcurrencyTest {
    @get:Rule val temporary = TemporaryFolder()
    private var database: ConversationCacheDatabase? = null

    private fun open(beforeCommit: suspend () -> Unit = {}): MigratingConversationCache {
        database?.close()
        val db =
            Room
                .databaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    ConversationCacheDatabase::class.java,
                    File(temporary.root, "cache.db").absolutePath,
                ).build()
        database = db
        return MigratingConversationCache(File(temporary.root, "files"), db, Dispatchers.IO, beforeCommit)
    }

    @After fun close() {
        database?.close()
    }

    private fun row(
        id: Int,
        text: String = "text-$id",
    ) = ThreadItem.MessageItem(Message("id-$id", "s", Role.User, text, Instant.fromEpochSeconds(id.toLong()), false))

    private class Source :
        ConversationRepository by FakeConversationRepository(),
        ThreadSnapshotSource {
        private val next = AtomicInteger()
        val inputs = List(3) { Channel<Pair<ThreadSnapshot, CompletableDeferred<Unit>>>(Channel.UNLIMITED) }

        override fun observeThreadSnapshot(conversationId: String): Flow<ThreadSnapshot> =
            flow {
                for ((snapshot, accepted) in inputs[next.getAndIncrement()]) {
                    emit(snapshot)
                    accepted.complete(Unit)
                }
            }

        override suspend fun delete(conversationId: String) = Unit

        suspend fun send(
            index: Int,
            vararg rows: ThreadItem,
        ) {
            val accepted = CompletableDeferred<Unit>()
            inputs[index].send(ThreadSnapshot(rows.toList()) to accepted)
            accepted.await()
        }
    }

    private fun TestScope.reader(repository: CachingConversationRepository): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repository.observeThreadSnapshot("c").collect {} }

    private fun TestScope.quiet() {
        advanceTimeBy(101)
        runCurrent()
    }

    @Test fun orderingInvariant_newerOwnedChangesWinHeldWrites() =
        runTest {
            val armed = AtomicBoolean()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val cache =
                open {
                    if (armed.compareAndSet(true, false)) {
                        entered.complete(Unit)
                        release.await()
                    }
                }
            cache.readThread("Host", "c")
            cache.readThread("host", "c")
            cache.writeThread("host", "c", listOf(row(1, "other-host"))).getOrThrow()
            val source = Source()
            val repository =
                CachingConversationRepository(source, cache, "Host", processingDispatcher = UnconfinedTestDispatcher(testScheduler))
            val reader = reader(repository)
            source.send(0)
            source.send(0, row(1), row(2))
            armed.set(true)
            quiet()
            entered.await()
            source.send(0, row(1), row(3))
            source.send(0, row(1), row(4))
            quiet()
            val closing = async { reader.cancelAndJoin() }
            runCurrent()
            assertFalse(closing.isCompleted)
            release.complete(Unit)
            closing.await()
            val fresh = open()
            assertEquals(listOf(row(1), row(4)), fresh.readThread("Host", "c"))
            assertEquals(listOf(row(1, "other-host")), fresh.readThread("host", "c"))
            // A reconnect restores the same committed snapshot and creates no removal by omission.
            val again =
                reader(CachingConversationRepository(source, fresh, "Host", processingDispatcher = UnconfinedTestDispatcher(testScheduler)))
            source.send(1)
            again.cancelAndJoin()
            assertEquals(listOf(row(1), row(4)), open().readThread("Host", "c"))
        }

    @Test fun retirementInvariant_removalWinsHeldWritesAndRestart() =
        runTest {
            for (forget in listOf(false, true)) {
                val armed = AtomicBoolean()
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val cache =
                    open {
                        if (armed.compareAndSet(true, false)) {
                            entered.complete(Unit)
                            release.await()
                        }
                    }
                cache.readThread("Host", "c")
                cache.readThread("host", "c")
                cache.writeThread("host", "c", listOf(row(1, "other"))).getOrThrow()
                val source = Source()
                val repository =
                    CachingConversationRepository(source, cache, "Host", processingDispatcher = UnconfinedTestDispatcher(testScheduler))
                val reader = reader(repository)
                source.send(0)
                source.send(0, row(1), row(2))
                armed.set(true)
                quiet()
                entered.await()
                source.send(0, row(1), row(3))
                val removal = async { if (forget) cache.removeHost("Host").getOrThrow() else repository.delete("c") }
                runCurrent()
                assertFalse(removal.isCompleted)
                release.complete(Unit)
                removal.await()
                reader.cancelAndJoin()
                val fresh = open()
                assertTrue(fresh.readThread("Host", "c").isEmpty())
                assertEquals(listOf(row(1, "other")), fresh.readThread("host", "c"))
            }
        }

    @Test fun failureInvariant_unchangedEmissionAndFinalFlushRetry() =
        runTest {
            for (finalOnly in listOf(false, true)) {
                val fail = AtomicBoolean()
                val failed = CompletableDeferred<Unit>()
                val retried = CompletableDeferred<Unit>()
                val attempts = AtomicInteger()
                val cache =
                    open {
                        if (fail.compareAndSet(true, false)) {
                            attempts.incrementAndGet()
                            failed.complete(Unit)
                            throw IOException("controlled")
                        }
                        if (failed.isCompleted) {
                            attempts.incrementAndGet()
                            retried.complete(Unit)
                        }
                    }
                cache.readThread("Host", "c")
                val source = Source()
                val repository =
                    CachingConversationRepository(source, cache, "Host", processingDispatcher = UnconfinedTestDispatcher(testScheduler))
                val reader = reader(repository)
                source.send(0)
                source.send(0, row(1, "newest"))
                fail.set(true)
                quiet()
                failed.await()
                // Acquiring the cache mutex waits for rollback. Returning to this dispatcher lets retry bookkeeping finish.
                assertTrue(cache.readThread("Host", "c").isEmpty())
                runCurrent()
                if (!finalOnly) {
                    source.send(0, row(1, "newest"))
                    quiet()
                    retried.await()
                    assertEquals(listOf(row(1, "newest")), cache.readThread("Host", "c"))
                }
                reader.cancelAndJoin()
                assertEquals(2, attempts.get())
                assertEquals(listOf(row(1, "newest")), open().readThread("Host", "c"))
                open().removeConversation("Host", "c").getOrThrow()
            }
        }

    @Test fun captureInvariant_oldCollectorsCannotUndoNewerChanges() =
        runTest {
            val cache = open()
            cache.readThread("Host", "c")
            val source = Source()
            val worker = UnconfinedTestDispatcher(testScheduler)
            val repository = CachingConversationRepository(source, cache, "Host", processingDispatcher = worker)
            val held = CompletableDeferred<Unit>()
            val delivered = CompletableDeferred<Unit>()
            val old =
                backgroundScope.launch(worker) {
                    repository.observeThreadSnapshot("c").collect { snapshot ->
                        if (snapshot.rows == listOf(row(1, "old"))) {
                            delivered.complete(Unit)
                            held.await()
                        }
                    }
                }
            source.send(0)
            val sending = async { source.send(0, row(1, "old")) }
            delivered.await()
            val newer = reader(repository)
            source.send(1)
            source.send(1, row(1, "new"))
            newer.cancelAndJoin()
            held.complete(Unit)
            sending.await()
            source.send(0) // The older collector reconnects without replaying the newer content yet.
            source.send(0, row(2))
            old.cancelAndJoin()
            assertEquals(listOf(row(1, "new"), row(2)), open().readThread("Host", "c"))
        }

    @Test fun cancellationInvariant_committedWriteAndBaselineFinishTogether() =
        runTest {
            val cache = open()
            cache.readThread("Host", "c")
            val committed = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val hold = AtomicBoolean(true)
            val intercepted =
                object : ConversationCache by cache, OwnedLegacyCache {
                    override suspend fun mutateLegacyRows(
                        host: String,
                        conversation: String,
                        upserts: List<OwnedLegacyRow>,
                        removed: Set<String>,
                    ): Result<Unit> {
                        val result = cache.mutateLegacyRows(host, conversation, upserts, removed)
                        if (hold.compareAndSet(true, false)) {
                            committed.complete(Unit)
                            release.await()
                        }
                        return result
                    }
                }
            val source = Source()
            val repository =
                CachingConversationRepository(source, intercepted, "Host", processingDispatcher = UnconfinedTestDispatcher(testScheduler))
            val reader = reader(repository)
            source.send(0)
            source.send(0, row(1), row(2))
            quiet()
            committed.await()
            assertEquals(listOf(row(1), row(2)), cache.readThread("Host", "c"))
            source.send(0, row(1))
            val closing = async { reader.cancelAndJoin() }
            runCurrent()
            assertFalse(closing.isCompleted)
            release.complete(Unit)
            closing.await()
            assertEquals(listOf(row(1)), open().readThread("Host", "c"))
        }
}
