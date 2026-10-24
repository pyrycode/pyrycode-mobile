package de.pyryco.mobile.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.pyryco.mobile.data.cache.AuthoritativeUpsertCache
import de.pyryco.mobile.data.cache.ConversationCache
import de.pyryco.mobile.data.cache.ConversationCacheDatabase
import de.pyryco.mobile.data.cache.FileConversationCache
import de.pyryco.mobile.data.cache.MigratingConversationCache
import de.pyryco.mobile.data.cache.SavedAuthoritativeThread
import de.pyryco.mobile.data.cache.SavedThreadCoverage
import de.pyryco.mobile.data.cache.toSavedAuthoritativeThread
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.ThreadItemDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class AuthoritativeOwnedRemovalTest {
    @get:Rule val temporary = TemporaryFolder()
    private val databases = mutableListOf<ConversationCacheDatabase>()
    private var removalProgress: ((String) -> Unit)? = null

    @After fun close() {
        removalProgress?.invoke("close_begin")
        databases.forEach { it.close() }
        removalProgress?.invoke("close_done")
    }

    private fun TestScope.open(beforeCommit: suspend () -> Unit = {}): MigratingConversationCache {
        val db =
            Room
                .databaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    ConversationCacheDatabase::class.java,
                    File(temporary.root, "cache.db").absolutePath,
                ).build()
        databases += db
        return MigratingConversationCache(File(temporary.root, "files"), db, StandardTestDispatcher(testScheduler), beforeCommit)
    }

    private fun raw(
        id: Int,
        rev: Int = id,
        order: Int = id,
    ): JsonObject =
        MobileJson
            .parseToJsonElement(
                """{"id":$id,"rev":$rev,"order":$order,"parent":1,"kind":"future","status":"done","active":false,"shown":true,"summary":"private","content":null,"unknown":{"clear":null}}""",
            ).jsonObject

    private fun saved(count: Int = 18_000) =
        SavedAuthoritativeThread(
            "e",
            (1..count).map { raw(it) },
            20_000,
            19_000,
            SavedThreadCoverage(1, 18_001, 1, false),
        )

    private fun TestScope.wrapper(cache: ConversationCache) =
        CachingConversationRepository(
            FakeConversationRepository(),
            cache,
            "host",
            processingDispatcher = StandardTestDispatcher(testScheduler),
        )

    private suspend fun TestScope.observe(repo: CachingConversationRepository): kotlinx.coroutines.Job {
        val ready = CompletableDeferred<Unit>()
        val job = backgroundScope.launch { repo.observeAuthoritativeThread("c").collect { ready.complete(Unit) } }
        ready.await()
        runCurrent()
        return job
    }

    @Test fun ownershipInvariant_partialRemovalPreservesEveryOtherFact() =
        runTest {
            val cache = open()
            val original = saved()
            cache.writeAuthoritativeThread("host", "c", original).getOrThrow()
            cache.writeAuthoritativeThread("other", "c", original).getOrThrow()
            val partial =
                object : ConversationCache by cache, AuthoritativeUpsertCache by cache {
                    override suspend fun readAuthoritativeThread(
                        serverId: String,
                        conversationId: String,
                    ) = cache.readAuthoritativeThread(serverId, conversationId)?.let { it.copy(items = it.items.takeLast(200)) }
                }
            val repo = wrapper(partial)
            val observer = observe(repo)
            assertEquals(
                200,
                repo.authoritativeThreads
                    .current("host", "c")
                    .items.size,
            )
            val token = requireNotNull(cache.newestAuthoritativePage("host", "c", 200)?.continuation)
            val fullReads = cache.authoritativeFullReads
            val before = ordinals()
            val target = requireNotNull(repo.authoritativeThreads.captureRemoval("host", "c", 18_000))
            repo.removeAuthoritativeFact(target).getOrThrow()
            observer.cancelAndJoin()
            val fresh = open()
            val retained = requireNotNull(fresh.readAuthoritativeThread("host", "c"))
            assertEquals(17_999, retained.items.size)
            assertEquals(original.items.dropLast(1), retained.items)
            assertEquals(0L, retained.checkpoint)
            assertNull(retained.coverage)
            assertEquals(original.appliedVersion, retained.appliedVersion)
            assertEquals(original, fresh.readAuthoritativeThread("other", "c"))
            assertEquals(
                retained.items,
                wrapper(fresh)
                    .observeAuthoritativeThread("c")
                    .first()
                    .items
                    .map { it.raw },
            )
            assertEquals(fullReads, cache.authoritativeFullReads)
            assertEquals(0, cache.authoritativeWriteCounts.itemWrites)
            assertEquals(0, cache.authoritativeWriteCounts.itemSerializations)
            assertEquals(1, cache.authoritativeWriteCounts.itemDeletes)
            assertEquals(1, cache.authoritativeWriteCounts.headerWrites)
            assertEquals(1, cache.authoritativeWriteCounts.retirementWrites)
            println("owned_removal_work=${cache.authoritativeWriteCounts}")
            assertEquals(before.filterKeys { it != 18_000L }, ordinals())
            assertNull(cache.beforeAuthoritativePage("host", "c", token, 200))
            var page = requireNotNull(fresh.newestAuthoritativePage("host", "c", 512))
            var walked = page.items
            while (page.continuation != null) {
                page = requireNotNull(fresh.beforeAuthoritativePage("host", "c", requireNotNull(page.continuation), 512))
                walked = page.items + walked
            }
            assertEquals(retained.items, walked)
        }

    private fun ordinals(): Map<Long, Int> =
        databases
            .first()
            .openHelper.readableDatabase
            .query(
                "SELECT id,ordinal FROM authoritative_items WHERE host='host' AND conversation='c' ORDER BY id",
            ).use { cursor -> buildMap { while (cursor.moveToNext()) put(cursor.getLong(0), cursor.getInt(1)) } }

    @Test fun ownershipInvariant_staleCapturesCannotRemoveNewerFacts() =
        runTest {
            val cache = open()
            cache.writeAuthoritativeThread("host", "c", saved(2)).getOrThrow()
            val repo = wrapper(cache)
            val observer = observe(repo)
            val target = requireNotNull(repo.authoritativeThreads.captureRemoval("host", "c", 2))
            val newer = ThreadItemDto.decodeReplacement(raw(2, 20_001))
            repo.authoritativeThreads.applyBatch("host", "c", "e", 20_001, listOf(newer), 0)
            assertTrue(repo.removeAuthoritativeFact(target).isFailure)
            val other = wrapper(cache)
            other.observeAuthoritativeThread("c").first()
            assertTrue(other.removeAuthoritativeFact(target).isFailure)
            repo.authoritativeThreads.applyBatch("other", "c", "e", 2, listOf(ThreadItemDto.decodeReplacement(raw(2))), 0)
            assertTrue(repo.removeAuthoritativeFact(requireNotNull(repo.authoritativeThreads.captureRemoval("other", "c", 2))).isFailure)
            assertEquals(
                listOf(2L),
                repo.authoritativeThreads
                    .current("other", "c")
                    .items
                    .map { it.id },
            )
            observer.cancelAndJoin()
            assertEquals(listOf(raw(1), newer.raw), open().readAuthoritativeThread("host", "c")?.items)
        }

    @Test fun atomicInvariant_failurePreservesCertificateAndRetryCommits() =
        runTest {
            val fail = AtomicBoolean(false)
            val cache = open { if (fail.get()) throw IOException("private failure") }
            val original = saved(2)
            cache.writeAuthoritativeThread("host", "c", original).getOrThrow()
            val repo = wrapper(cache)
            val observer = observe(repo)
            val target = requireNotNull(repo.authoritativeThreads.captureRemoval("host", "c", 2))
            fail.set(true)
            val failed = repo.removeAuthoritativeFact(target)
            assertTrue(failed.isFailure)
            assertFalse(failed.exceptionOrNull().toString().contains("private failure"))
            assertEquals(original, open().readAuthoritativeThread("host", "c"))
            fail.set(false)
            repo.removeAuthoritativeFact(target).getOrThrow()
            repo.removeAuthoritativeFact(target).getOrThrow()
            observer.cancelAndJoin()
            val fresh = requireNotNull(open().readAuthoritativeThread("host", "c"))
            assertEquals(listOf(raw(1)), fresh.items)
            assertEquals(0L, fresh.checkpoint)
            assertNull(fresh.coverage)
        }

    @Test fun retirementInvariant_replayAndRestoreCannotResurrect() =
        runTest {
            val cache = open()
            val original = saved(3)
            cache.writeAuthoritativeThread("host", "c", original).getOrThrow()
            val repo = wrapper(cache)
            val observer = observe(repo)
            val stale = repo.authoritativeThreads.current("host", "c")
            repo.removeAuthoritativeFact(requireNotNull(repo.authoritativeThreads.captureRemoval("host", "c", 1))).getOrThrow()
            repo.authoritativeThreads.restore("host", "c", stale, 0)
            repo.authoritativeThreads.applyBatch("host", "c", "e", 30_000, listOf(ThreadItemDto.decodeReplacement(raw(1, 30_000))), 0)
            assertEquals(
                listOf(2L, 3L),
                repo.authoritativeThreads
                    .current("host", "c")
                    .items
                    .map { it.id },
            )
            observer.cancelAndJoin()
            val fresh = open()
            val restarted = wrapper(fresh)
            val second = observe(restarted)
            restarted.authoritativeThreads.restore("host", "c", stale, 0)
            restarted.authoritativeThreads.applyBatch("host", "c", "e", 30_001, listOf(ThreadItemDto.decodeReplacement(raw(1, 30_001))), 0)
            second.cancelAndJoin()
            assertEquals(original.items.drop(1), open().readAuthoritativeThread("host", "c")?.items)
        }

    @Test fun retirementInvariant_heldUpsertThenRemovalCommitsFence() =
        runTest {
            val hold = AtomicBoolean(false)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val cache =
                open {
                    if (hold.compareAndSet(true, false)) {
                        started.complete(Unit)
                        release.await()
                    }
                }
            cache.writeAuthoritativeThread("host", "c", saved(3)).getOrThrow()
            val repo = wrapper(cache)
            val observer = observe(repo)
            hold.set(true)
            repo.authoritativeThreads.applyBatch("host", "c", "e", 20_001, listOf(ThreadItemDto.decodeReplacement(raw(2, 20_001))), 0)
            advanceTimeBy(101)
            started.await()
            val target = requireNotNull(repo.authoritativeThreads.captureRemoval("host", "c", 2))
            val removed = CompletableDeferred<Result<Unit>>()
            val removal = backgroundScope.launch { removed.complete(repo.removeAuthoritativeFact(target)) }
            runCurrent()
            try {
                assertFalse(removed.isCompleted)
            } finally {
                release.complete(Unit)
            }
            removal.join()
            removed.await().getOrThrow()
            observer.cancelAndJoin()
            assertEquals(listOf(raw(1), raw(3)), open().readAuthoritativeThread("host", "c")?.items)
        }

    @Test fun retirementInvariant_resetDuringRemovalCreatesNewIncarnation() =
        runTest {
            val hold = AtomicBoolean(false)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val cache =
                open {
                    if (hold.compareAndSet(true, false)) {
                        started.complete(Unit)
                        release.await()
                    }
                }
            cache.writeAuthoritativeThread("host", "c", saved(2)).getOrThrow()
            val repo = wrapper(cache)
            val observer = observe(repo)
            val target = requireNotNull(repo.authoritativeThreads.captureRemoval("host", "c", 2))
            hold.set(true)
            val removal = backgroundScope.launch { repo.removeAuthoritativeFact(target).getOrThrow() }
            started.await()
            repo.authoritativeThreads.reset("host", "c", "e")
            release.complete(Unit)
            removal.join()
            assertTrue(repo.removeAuthoritativeFact(target).isFailure)
            assertEquals(
                AuthoritativeThreadOutcome.Applied,
                repo.authoritativeThreads.applyBatch("host", "c", "e", 2, listOf(ThreadItemDto.decodeReplacement(raw(2))), 1),
            )
            runCurrent()
            observer.cancelAndJoin()
            assertEquals(listOf(raw(2)), open().readAuthoritativeThread("host", "c")?.items)
        }

    @Test fun retirementInvariant_scopeDeletionKeepsOtherHostExact() =
        runTest {
            for (hostDeletion in listOf(false, true)) {
                val cache = open()
                val original = saved(3)
                cache.admitHost("host")
                cache.writeAuthoritativeThread("host", "c", original).getOrThrow()
                cache.writeAuthoritativeThread("other", "c", original).getOrThrow()
                val repo = wrapper(cache)
                val observer = observe(repo)
                val target = requireNotNull(repo.authoritativeThreads.captureRemoval("host", "c", 2))
                repo.removeAuthoritativeFact(target).getOrThrow()
                val late = requireNotNull(repo.authoritativeThreads.captureRemoval("host", "c", 1))
                if (hostDeletion) cache.removeHost("host").getOrThrow() else repo.delete("c")
                assertTrue(repo.removeAuthoritativeFact(late).isFailure)
                observer.cancelAndJoin()
                val fresh = open()
                assertNull(fresh.readAuthoritativeThread("host", "c"))
                assertEquals(original, fresh.readAuthoritativeThread("other", "c"))
            }
        }

    @Test fun retirementInvariant_finalFlushRefreshesRemoval() =
        runTest {
            val hold = AtomicBoolean(false)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val cache =
                open {
                    if (hold.compareAndSet(true, false)) {
                        started.complete(Unit)
                        release.await()
                    }
                }
            cache.writeAuthoritativeThread("host", "c", saved(3)).getOrThrow()
            val repo = wrapper(cache)
            val observer = observe(repo)
            repo.authoritativeThreads.applyBatch("host", "c", "e", 20_001, listOf(ThreadItemDto.decodeReplacement(raw(2, 20_001))), 0)
            runCurrent()
            hold.set(true)
            val cleanup = backgroundScope.launch { observer.cancelAndJoin() }
            started.await()
            try {
                assertEquals(
                    AuthoritativeThreadOutcome.Applied,
                    repo.authoritativeThreads.remove(
                        requireNotNull(repo.authoritativeThreads.captureRemoval("host", "c", 2)),
                    ),
                )
                assertEquals(1, recoveryEntries(repo.authoritativeThreads))
            } finally {
                release.complete(Unit)
            }
            cleanup.join()
            assertEquals(0, recoveryEntries(repo.authoritativeThreads))
            val fresh = requireNotNull(open().readAuthoritativeThread("host", "c"))
            assertEquals(listOf(raw(1), raw(3)), fresh.items)
            assertEquals(mapOf(2L to 20_001L), fresh.retirements)
            assertEquals(0L, fresh.checkpoint)
            assertNull(fresh.coverage)
        }

    @Test fun ownershipInvariant_durableNewerRevisionRejectsRemovalAtomically() =
        runTest {
            assertDurableRejection(open()) { open() }
        }

    @Test fun ownershipInvariant_roomReplacementRejectsStaleRemovalAtomically() =
        runTest {
            // Hide the incremental capability so the production writer exercises full replacement.
            assertDurableRejection(object : ConversationCache by open() {}) { open() }
        }

    @Test fun ownershipInvariant_fileReplacementRejectsStaleRemovalAtomically() =
        runTest {
            val root = File(temporary.root, "replacement")
            assertDurableRejection(FileConversationCache(root, StandardTestDispatcher(testScheduler))) {
                FileConversationCache(root, StandardTestDispatcher(testScheduler))
            }
        }

    @Test fun retirementInvariant_roomReplacementResetAdmitsOnlyNewIncarnation() =
        runTest {
            for (epoch in listOf("e", "new")) {
                val fail = AtomicBoolean(false)
                val room = open { if (fail.get()) throw IOException("private reset failure") }
                room.removeConversation("host", "c").getOrThrow()
                val cache = object : ConversationCache by room {}
                val original = saved(3)
                cache.writeAuthoritativeThread("host", "c", original).getOrThrow()
                cache.writeAuthoritativeThread("other", "c", original).getOrThrow()
                val repo = wrapper(cache)
                observe(repo).cancelAndJoin()
                val target = requireNotNull(repo.authoritativeThreads.captureRemoval("host", "c", 2))
                repo.removeAuthoritativeFact(target).getOrThrow()
                val retired = requireNotNull(open().readAuthoritativeThread("host", "c"))
                fail.set(true)
                assertTrue(repo.resetAuthoritativeThread("c", epoch).isFailure)
                assertEquals(retired, open().readAuthoritativeThread("host", "c"))
                fail.set(false)
                repo.resetAuthoritativeThread("c", epoch).getOrThrow()
                val reset = requireNotNull(open().readAuthoritativeThread("host", "c"))
                assertEquals(epoch, reset.epoch)
                assertTrue(reset.incarnation.isNotEmpty())
                assertTrue(reset.items.isEmpty())
                assertTrue(reset.retirements.isEmpty())
                assertEquals(0L, reset.checkpoint)
                assertNull(reset.coverage)
                assertTrue(repo.removeAuthoritativeFact(target).isFailure)
                assertTrue(open().writeAuthoritativeThread("host", "c", retired).isFailure)
                assertEquals(
                    AuthoritativeThreadOutcome.StaleGeneration,
                    repo.authoritativeThreads.applyBatch("host", "c", "e", 3, original.toSnapshot().items, 0),
                )
                val observer = observe(repo)
                val generation = repo.authoritativeThreads.current("host", "c").generation
                repo.authoritativeThreads.applyBatch("host", "c", epoch, 2, listOf(ThreadItemDto.decodeReplacement(raw(2))), generation)
                runCurrent()
                observer.cancelAndJoin()
                val fresh = open()
                val admitted = requireNotNull(fresh.readAuthoritativeThread("host", "c"))
                assertEquals(listOf(raw(2)), admitted.items)
                assertEquals(reset.incarnation, admitted.incarnation)
                assertEquals(original, fresh.readAuthoritativeThread("other", "c"))
                assertEquals(
                    admitted.items,
                    wrapper(fresh)
                        .observeAuthoritativeThread("c")
                        .first()
                        .items
                        .map { it.raw },
                )
            }
        }

    @Test fun ownershipInvariant_pendingRejectionsRestoreOriginalTieOrder() =
        runTest {
            val fail = AtomicBoolean(false)
            val cache = open { if (fail.get()) throw IOException("private removal failure") }
            val original = saved(3).copy(items = (1..3).map { raw(it, order = 1) })
            cache.writeAuthoritativeThread("host", "c", original).getOrThrow()
            val repo = wrapper(cache)
            observe(repo).cancelAndJoin()
            var observed = emptyList<ThreadItemDto>()
            val storeObserver =
                backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
                    repo.authoritativeThreads.observe("host", "c").collect { observed = it.items }
                }
            val first = requireNotNull(repo.authoritativeThreads.captureRemoval("host", "c", 2))
            fail.set(true)
            assertTrue(repo.removeAuthoritativeFact(first).isFailure)
            assertEquals(original, open().readAuthoritativeThread("host", "c"))
            val newer = original.copy(items = listOf(raw(1, 20_001, 1), raw(2, 20_002, 1), raw(3, order = 1)), appliedVersion = 20_002)
            open().writeAuthoritativeThread("host", "c", newer).getOrThrow()
            val before = ordinals()
            val second = requireNotNull(repo.authoritativeThreads.captureRemoval("host", "c", 1))
            fail.set(false)
            assertTrue(repo.removeAuthoritativeFact(second).isFailure)
            assertEquals(listOf(2L, 3L), observed.map { it.id })
            assertTrue(repo.removeAuthoritativeFact(second).isFailure)
            assertEquals(
                newer.items,
                repo.authoritativeThreads
                    .current("host", "c")
                    .items
                    .map { it.raw },
            )
            assertEquals(newer.items, observed.map { it.raw })
            assertEquals(newer, open().readAuthoritativeThread("host", "c"))
            val observer = observe(repo)
            repo.authoritativeThreads.applyBatch("host", "c", "e", 20_003, listOf(ThreadItemDto.decodeReplacement(raw(3, 20_003, 1))), 0)
            runCurrent()
            observer.cancelAndJoin()
            val retained = requireNotNull(open().readAuthoritativeThread("host", "c"))
            assertEquals(listOf(raw(1, 20_001, 1), raw(2, 20_002, 1), raw(3, 20_003, 1)), retained.items)
            assertEquals(retained.items, observed.map { it.raw })
            assertTrue(retained.retirements.isEmpty())
            assertEquals(before, ordinals())
            storeObserver.cancelAndJoin()
        }

    @Test fun retirementInvariant_successReleasesRecoveryButFailureRetainsIt() =
        runTest {
            val started = System.nanoTime()
            var previous = started
            fun phase(code: String) {
                val now = System.nanoTime()
                println("removal_phase=$code phase_ms=${(now - previous) / 1_000_000} elapsed_ms=${(now - started) / 1_000_000}")
                previous = now
            }
            removalProgress = ::phase
            for (absentOrder in listOf(false, true)) {
                val arm = if (absentOrder) "absent" else "tied"
                phase("${arm}_fixture_begin")
                val fail = AtomicBoolean(false)
                val cache = open { if (fail.get()) throw IOException("private removal failure") }
                cache.removeConversation("host", "c").getOrThrow()
                phase("${arm}_cleared")
                val original =
                    saved(256).copy(
                        items =
                            (1..256).map { id ->
                                val item = raw(id, order = 1)
                                if (absentOrder) JsonObject(item - "order") else item
                            },
                    )
                cache.writeAuthoritativeThread("host", "c", original).getOrThrow()
                phase("${arm}_seeded")
                val repo = wrapper(cache)
                val observer = observe(repo)
                phase("${arm}_observed")
                observer.cancelAndJoin()
                phase("${arm}_observer_joined")
                for (id in 1L..64L) {
                    repo.removeAuthoritativeFact(requireNotNull(repo.authoritativeThreads.captureRemoval("host", "c", id))).getOrThrow()
                    assertEquals(0, recoveryEntries(repo.authoritativeThreads))
                    assertEquals(
                        id.toInt(),
                        repo.authoritativeThreads
                            .current("host", "c")
                            .retirements.size,
                    )
                    phase("${arm}_removed_$id")
                }
                val target = requireNotNull(repo.authoritativeThreads.captureRemoval("host", "c", 65))
                fail.set(true)
                phase("${arm}_failure_begin")
                assertTrue(repo.removeAuthoritativeFact(target).isFailure)
                phase("${arm}_failure_returned")
                assertEquals(192, recoveryEntries(repo.authoritativeThreads))
                assertEquals(original.items.drop(64), open().readAuthoritativeThread("host", "c")?.items)
                phase("${arm}_failure_reopened")
                fail.set(false)
                repo.removeAuthoritativeFact(target).getOrThrow()
                phase("${arm}_retry_committed")
                assertEquals(0, recoveryEntries(repo.authoritativeThreads))
                val retained = requireNotNull(open().readAuthoritativeThread("host", "c"))
                phase("${arm}_retry_reopened")
                assertEquals(original.items.drop(65), retained.items)
                assertEquals((1L..65L).associateWith { it }, retained.retirements)
                phase("${arm}_assertions_done")
            }
            phase("body_done")
        }

    private fun recoveryEntries(store: AuthoritativeThreadStore): Int = store.retainedRemovalPeerCount()

    private suspend fun TestScope.assertDurableRejection(
        cache: ConversationCache,
        reopen: () -> ConversationCache,
    ) {
        val original = saved(2).copy(items = listOf(raw(2, order = 1), raw(1)))
        cache.writeAuthoritativeThread("host", "c", original).getOrThrow()
        val repo = wrapper(cache)
        val observer = observe(repo)
        // Assert the refused transaction before an observer can save the recovered conservative state.
        observer.cancelAndJoin()
        var observed = emptyList<ThreadItemDto>()
        val storeObserver =
            backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
                repo.authoritativeThreads.observe("host", "c").collect { observed = it.items }
            }
        val target = requireNotNull(repo.authoritativeThreads.captureRemoval("host", "c", 2))
        val newer = original.copy(items = listOf(raw(2, 20_001, order = 1), raw(1)), appliedVersion = 20_001)
        if (cache is AuthoritativeUpsertCache) {
            cache
                .upsertAuthoritativeThread(
                    "host",
                    "c",
                    newer.copy(items = listOf(raw(2, 20_001, order = 1))),
                    false,
                    original.copy(items = emptyList()),
                    emptyMap(),
                ).getOrThrow()
        } else {
            reopen().writeAuthoritativeThread("host", "c", newer).getOrThrow()
        }
        val before = if (databases.isEmpty()) null else ordinals()
        assertTrue(repo.removeAuthoritativeFact(target).isFailure)
        assertEquals(newer, reopen().readAuthoritativeThread("host", "c"))
        assertEquals(
            newer.items,
            repo.authoritativeThreads
                .current("host", "c")
                .items
                .map { it.raw },
        )
        assertEquals(newer.items, observed.map { it.raw })
        val writer = observe(repo)
        repo.authoritativeThreads.applyBatch("host", "c", "e", 20_002, listOf(ThreadItemDto.decodeReplacement(raw(1, 20_002))), 0)
        runCurrent()
        writer.cancelAndJoin()
        val retained = requireNotNull(reopen().readAuthoritativeThread("host", "c"))
        assertEquals(listOf(raw(2, 20_001, order = 1), raw(1, 20_002)), retained.items)
        assertEquals(retained.items, observed.map { it.raw })
        if (before != null) assertEquals(before, ordinals())
        assertTrue(retained.retirements.isEmpty())
        storeObserver.cancelAndJoin()
    }

    @Test fun retirementInvariant_storageFencesReplayAndOldIncarnationAcrossReopen() =
        runTest {
            val cache = open()
            val original = saved(2)
            cache.writeAuthoritativeThread("host", "c", original).getOrThrow()
            cache
                .upsertAuthoritativeThread(
                    "host",
                    "c",
                    original.copy(appliedVersion = 30_000),
                    false,
                    original.copy(items = emptyList()),
                    emptyMap(),
                ).getOrThrow()
            val repo = wrapper(cache)
            val observer = observe(repo)
            repo.removeAuthoritativeFact(requireNotNull(repo.authoritativeThreads.captureRemoval("host", "c", 2))).getOrThrow()
            observer.cancelAndJoin()
            val restarted = open()
            restarted.upsertAuthoritativeThread("host", "c", original, false, original.copy(items = emptyList()), emptyMap()).getOrThrow()
            assertEquals(listOf(raw(1)), open().readAuthoritativeThread("host", "c")?.items)
            restarted.writeAuthoritativeThread("host", "c", original).getOrThrow()
            val retained = requireNotNull(open().readAuthoritativeThread("host", "c"))
            assertEquals(listOf(raw(1)), retained.items)
            assertEquals(30_000L, retained.appliedVersion)
            assertEquals(mapOf(2L to 2L), retained.retirements)
            assertEquals(0L, retained.checkpoint)
            assertNull(retained.coverage)
            assertTrue(restarted.writeAuthoritativeThread("host", "c", original.copy(epoch = "other")).isFailure)
            val freshRepo = wrapper(restarted)
            val second = observe(freshRepo)
            freshRepo.resetAuthoritativeThread("c", "e").getOrThrow()
            val reset = requireNotNull(open().readAuthoritativeThread("host", "c"))
            assertTrue(reset.incarnation.isNotEmpty())
            assertTrue(reset.retirements.isEmpty())
            assertTrue(
                restarted.upsertAuthoritativeThread("host", "c", original, false, original.copy(items = emptyList()), emptyMap()).isFailure,
            )
            assertTrue(restarted.writeAuthoritativeThread("host", "c", original.copy(epoch = "older")).isFailure)
            assertEquals(
                AuthoritativeThreadOutcome.Applied,
                freshRepo.authoritativeThreads.applyBatch("host", "c", "e", 2, listOf(ThreadItemDto.decodeReplacement(raw(2))), 1),
            )
            runCurrent()
            second.cancelAndJoin()
            val admitted = requireNotNull(open().readAuthoritativeThread("host", "c"))
            assertEquals(listOf(raw(2)), admitted.items)
            assertEquals(reset.incarnation, admitted.incarnation)
        }

    @Test fun retirementInvariant_fileCompatibilityRetainsFenceAndResetLifetime() =
        runTest {
            val root = File(temporary.root, "compatibility")
            val files = FileConversationCache(root, StandardTestDispatcher(testScheduler))
            val original = saved(3)
            files.writeAuthoritativeThread("host", "c", original).getOrThrow()
            val repo = wrapper(files)
            val observer = observe(repo)
            repo.removeAuthoritativeFact(requireNotNull(repo.authoritativeThreads.captureRemoval("host", "c", 1))).getOrThrow()
            observer.cancelAndJoin()
            val fresh = FileConversationCache(root, StandardTestDispatcher(testScheduler))
            val retained = requireNotNull(fresh.readAuthoritativeThread("host", "c"))
            assertEquals(listOf(raw(2), raw(3)), retained.items)
            assertEquals(mapOf(1L to 1L), retained.retirements)
            files.writeAuthoritativeThread("host", "c", original).getOrThrow()
            fresh.writeAuthoritativeThread("host", "c", original).getOrThrow()
            assertEquals(retained, FileConversationCache(root).readAuthoritativeThread("host", "c"))
            val restarted = wrapper(fresh)
            val second = observe(restarted)
            restarted.authoritativeThreads.applyBatch("host", "c", "e", 30_000, listOf(ThreadItemDto.decodeReplacement(raw(1, 30_000))), 0)
            second.cancelAndJoin()
            assertEquals(retained.items, fresh.readAuthoritativeThread("host", "c")?.items)
            restarted.resetAuthoritativeThread("c", "e").getOrThrow()
            val reset = requireNotNull(fresh.readAuthoritativeThread("host", "c"))
            assertTrue(reset.retirements.isEmpty())
            assertTrue(reset.incarnation.isNotEmpty())
            assertTrue(files.writeAuthoritativeThread("host", "c", original).isFailure)
            assertTrue(fresh.writeAuthoritativeThread("host", "c", retained).isFailure)
            assertEquals(reset, FileConversationCache(root).readAuthoritativeThread("host", "c"))
            val third = observe(restarted)
            restarted.authoritativeThreads.applyBatch("host", "c", "e", 1, listOf(ThreadItemDto.decodeReplacement(raw(1))), 1)
            runCurrent()
            third.cancelAndJoin()
            assertEquals(listOf(raw(1)), FileConversationCache(root).readAuthoritativeThread("host", "c")?.items)
        }

    @Test fun atomicInvariant_cancelledRemovalFinishesCommitBeforeReturning() =
        runTest {
            val hold = AtomicBoolean(false)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val cache =
                open {
                    if (hold.compareAndSet(true, false)) {
                        started.complete(Unit)
                        release.await()
                    }
                }
            cache.writeAuthoritativeThread("host", "c", saved(2)).getOrThrow()
            val repo = wrapper(cache)
            val observer = observe(repo)
            val target = requireNotNull(repo.authoritativeThreads.captureRemoval("host", "c", 2))
            hold.set(true)
            val removal = backgroundScope.launch { repo.removeAuthoritativeFact(target) }
            started.await()
            removal.cancel()
            runCurrent()
            assertFalse(removal.isCompleted)
            release.complete(Unit)
            removal.join()
            observer.cancelAndJoin()
            val fresh = requireNotNull(open().readAuthoritativeThread("host", "c"))
            assertEquals(listOf(raw(1)), fresh.items)
            assertEquals(mapOf(2L to 2L), fresh.retirements)
            assertEquals(0L, fresh.checkpoint)
            assertNull(fresh.coverage)
        }

    @Test fun retirementInvariant_emptyPageStillRetainsItsFence() =
        runTest {
            val cache = open()
            cache.writeAuthoritativeThread("host", "c", saved(1)).getOrThrow()
            val repo = wrapper(cache)
            val observer = observe(repo)
            val target = requireNotNull(repo.authoritativeThreads.captureRemoval("host", "c", 1))
            repo.removeAuthoritativeFact(target).getOrThrow()
            observer.cancelAndJoin()
            val fresh = open()
            val page = requireNotNull(fresh.newestAuthoritativePage("host", "c", 1))
            assertTrue(page.items.isEmpty())
            assertNull(page.continuation)
            assertEquals(mapOf(1L to 1L), page.retirements)
            val restarted = wrapper(fresh)
            val second = observe(restarted)
            restarted.authoritativeThreads.establishComplete(
                "host",
                "c",
                "e",
                30_000,
                listOf(ThreadItemDto.decodeReplacement(raw(1, 30_000))),
                0,
            )
            runCurrent()
            second.cancelAndJoin()
            val retained = requireNotNull(open().readAuthoritativeThread("host", "c"))
            assertTrue(retained.items.isEmpty())
            assertEquals(0L, retained.checkpoint)
            assertNull(retained.coverage)
            assertEquals(
                AuthoritativeThreadOutcome.ResetNeeded,
                restarted.authoritativeThreads.applyBatch("host", "c", "unexpected", 30_001, emptyList(), 0),
            )
            val mismatch = restarted.authoritativeThreads.current("host", "c").toSavedAuthoritativeThread()
            assertEquals(retained.retirements, mismatch.retirements)
            assertEquals(retained.incarnation, mismatch.incarnation)
            assertEquals(retained.appliedVersion, mismatch.appliedVersion)
            mismatch.toSnapshot()
        }
}
