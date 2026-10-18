package de.pyryco.mobile.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.pyryco.mobile.data.cache.AuthoritativeUpsertCache
import de.pyryco.mobile.data.cache.ConversationCache
import de.pyryco.mobile.data.cache.ConversationCacheDatabase
import de.pyryco.mobile.data.cache.MAX_CACHED_THREAD_ROWS
import de.pyryco.mobile.data.cache.MigratingConversationCache
import de.pyryco.mobile.data.cache.SavedAuthoritativeThread
import de.pyryco.mobile.data.cache.SavedThreadCoverage
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.network.ThreadQueryReply
import de.pyryco.mobile.data.network.ThreadQueryRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class AuthoritativeOwnedUpsertTest {
    @get:Rule val temporary = TemporaryFolder()
    private var database: ConversationCacheDatabase? = null

    private fun TestScope.open(beforeCommit: suspend () -> Unit = {}): MigratingConversationCache {
        database?.close()
        val db =
            Room
                .databaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    ConversationCacheDatabase::class.java,
                    File(temporary.root, "cache.db").absolutePath,
                ).build()
        database = db
        return MigratingConversationCache(File(temporary.root, "files"), db, StandardTestDispatcher(testScheduler), beforeCommit)
    }

    @After fun close() {
        database?.close()
    }

    private fun raw(
        id: Int,
        rev: Int = id,
        order: Int = id,
    ): JsonObject =
        MobileJson
            .parseToJsonElement(
                """{"id":$id,"rev":$rev,"order":$order,"kind":"future","status":"done","active":false,"shown":true,"summary":"private","content":null,"unknown":{"x":null}}""",
            ).jsonObject

    private fun saved(
        items: List<JsonObject>,
        version: Long = 20_000,
    ) = SavedAuthoritativeThread("e", items, version, 19_000, SavedThreadCoverage(1, 18_001, 1, false))

    private fun TestScope.wrapper(cache: ConversationCache) =
        CachingConversationRepository(
            FakeConversationRepository(),
            cache,
            "host",
            processingDispatcher = StandardTestDispatcher(testScheduler),
        )

    private class PartialCache(
        private val actual: MigratingConversationCache,
    ) : ConversationCache by actual,
        AuthoritativeUpsertCache by actual {
        override suspend fun readAuthoritativeThread(
            serverId: String,
            conversationId: String,
        ): SavedAuthoritativeThread? =
            actual.newestAuthoritativePage(serverId, conversationId, 200)?.let {
                SavedAuthoritativeThread(it.epoch, it.items, it.appliedVersion, it.checkpoint, it.coverage)
            }
    }

    private suspend fun TestScope.observe(repo: CachingConversationRepository): kotlinx.coroutines.Job {
        val ready = CompletableDeferred<Unit>()
        val observer = backgroundScope.launch { repo.observeAuthoritativeThread("c").collect { ready.complete(Unit) } }
        ready.await()
        runCurrent()
        return observer
    }

    private fun assertWork(
        cache: MigratingConversationCache,
        reads: Int,
        writes: Int,
        deletes: Int = 0,
    ) {
        val work = cache.authoritativeWriteCounts
        println("owned_upsert_work=$work fullReads=${cache.authoritativeFullReads} replacements=${cache.authoritativeReplacements}")
        assertEquals(reads, work.itemReads)
        assertEquals(reads, work.itemDecodes)
        assertEquals(writes, work.inputDecodes)
        assertEquals(writes, work.itemSerializations)
        assertEquals(writes, work.itemWrites)
        assertEquals(deletes, work.itemDeletes)
        assertEquals(1, work.headerWrites)
    }

    private fun ordinals(): Map<Long, Int> =
        requireNotNull(database)
            .openHelper.readableDatabase
            .query(
                "SELECT id,ordinal FROM authoritative_items WHERE host='host' AND conversation='c' ORDER BY id",
            ).use { cursor -> buildMap { while (cursor.moveToNext()) put(cursor.getLong(0), cursor.getInt(1)) } }

    private suspend fun TestScope.fresh(host: String = "host"): SavedAuthoritativeThread? {
        val db =
            Room
                .databaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    ConversationCacheDatabase::class.java,
                    File(temporary.root, "cache.db").absolutePath,
                ).build()
        return try {
            MigratingConversationCache(File(temporary.root, "files"), db, StandardTestDispatcher(testScheduler))
                .readAuthoritativeThread(host, "c")
        } finally {
            db.close()
        }
    }

    private fun apply(
        repo: CachingConversationRepository,
        version: Int,
        vararg facts: JsonObject,
    ) {
        val generation = repo.authoritativeThreads.current("host", "c").generation
        assertEquals(
            AuthoritativeThreadOutcome.Applied,
            repo.authoritativeThreads.applyBatch(
                "host",
                "c",
                "e",
                version.toLong(),
                facts.map(ThreadItemDto::decodeReplacement),
                generation,
            ),
        )
    }

    @Test fun boundedInvariant_ownedRevisionAndCertificationPreserveUnloadedFacts() =
        runTest {
            val cache = open()
            val original = saved((1..18_000).map { raw(it) })
            cache.writeAuthoritativeThread("host", "c", original).getOrThrow()
            val originalOrdinals = ordinals()
            val token = requireNotNull(cache.newestAuthoritativePage("host", "c", 200)?.continuation)
            val repo = wrapper(PartialCache(cache))
            var observer = observe(repo)
            var fullReads = cache.authoritativeFullReads
            val replacements = cache.authoritativeReplacements
            assertEquals(
                200,
                repo.authoritativeThreads
                    .current("host", "c")
                    .items.size,
            )
            val revised = raw(18_000, 21_000)
            apply(repo, 21_000, revised)
            runCurrent()
            advanceTimeBy(101)
            runCurrent()
            observer.cancelAndJoin()
            assertWork(cache, 1, 1)
            assertEquals(originalOrdinals, ordinals())
            assertEquals(fullReads, cache.authoritativeFullReads)
            assertEquals(replacements, cache.authoritativeReplacements)
            assertNull(cache.beforeAuthoritativePage("host", "c", token, 200))
            var expected = original.copy(items = original.items.dropLast(1) + revised, appliedVersion = 21_000)
            assertEquals(expected, fresh())
            observer = observe(repo)
            fullReads = cache.authoritativeFullReads
            val added = JsonObject(raw(18_001, 21_001, 19_000) + mapOf("parent" to JsonPrimitive(2), "active" to JsonPrimitive(true)))
            apply(repo, 21_001, added)
            runCurrent()
            observer.cancelAndJoin()
            assertWork(cache, 0, 1)
            assertEquals(originalOrdinals, ordinals().filterKeys { it <= 18_000 })
            assertEquals(fullReads, cache.authoritativeFullReads)
            expected = expected.copy(items = expected.items + added, appliedVersion = 21_001)
            assertEquals(expected, fresh())
            observer = observe(repo)
            fullReads = cache.authoritativeFullReads
            assertEquals(
                AuthoritativeThreadOutcome.Applied,
                repo.authoritativeThreads.establishComplete("host", "c", "e", 21_001, emptyList(), 0),
            )
            runCurrent()
            observer.cancelAndJoin()
            assertWork(cache, 0, 0)
            assertEquals(fullReads, cache.authoritativeFullReads)
            assertEquals(replacements, cache.authoritativeReplacements)
            expected = expected.copy(checkpoint = 21_001)
            val reopened = open()
            assertEquals(18_001, reopened.readAuthoritativeThread("host", "c")?.items?.size)
            assertEquals(expected, reopened.readAuthoritativeThread("host", "c"))
            val restored = wrapper(reopened).observeAuthoritativeThread("c").first()
            assertEquals(expected.items, restored.items.map { it.raw })
            assertEquals(21_001L, restored.checkpoint)
            assertFalse(wrapper(reopened).openAuthoritativeThread("c"))
        }

    @Test fun atomicInvariant_heldFailureAndFinalFlushKeepNewestFacts() =
        runTest {
            var hook: suspend () -> Unit = {}
            val cache = open { hook() }
            val prior = saved(listOf(raw(1), raw(2)))
            cache.writeAuthoritativeThread("host", "c", prior).getOrThrow()
            val repo = wrapper(cache)
            val ready = CompletableDeferred<Unit>()
            val observer = backgroundScope.launch { repo.observeAuthoritativeThread("c").collect { ready.complete(Unit) } }
            ready.await()
            runCurrent()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val allowRetry = CompletableDeferred<Unit>()
            var firstAttempt = true
            hook = {
                if (firstAttempt) {
                    firstAttempt = false
                    entered.complete(Unit)
                    release.await()
                    throw IOException("private failure")
                } else {
                    allowRetry.await()
                }
            }
            apply(repo, 21_000, raw(1, 21_000))
            runCurrent()
            advanceTimeBy(101)
            runCurrent()
            entered.await()
            apply(repo, 22_000, raw(1, 22_000), raw(3, 22_000))
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertEquals(prior, fresh())
            allowRetry.complete(Unit)
            observer.cancelAndJoin()
            val expected = prior.copy(items = listOf(raw(1, 22_000), raw(2), raw(3, 22_000)), appliedVersion = 22_000)
            assertEquals(expected, fresh())
        }

    @Test fun identityInvariant_revisionsAndTiesSurviveRestart() =
        runTest {
            val cache = open()
            val original = saved(listOf(raw(3, order = 1), raw(1, order = 1), raw(2, order = 2)))
            cache.writeAuthoritativeThread("host", "c", original).getOrThrow()
            val repo = wrapper(cache)
            val observer = observe(repo)
            val revised = JsonObject(raw(1, 21_000, 1) + mapOf("parent" to JsonNull, "content" to JsonNull))
            apply(repo, 21_000, raw(4, 21_000, 1), revised, raw(3, order = 1))
            runCurrent()
            // A replay and an older overlapping replacement cannot undo the explicit clears.
            assertEquals(
                AuthoritativeThreadOutcome.Ignored,
                repo.authoritativeThreads.applyBatch(
                    "host",
                    "c",
                    "e",
                    21_000,
                    listOf(raw(1, 20_000, 1), revised).map(ThreadItemDto::decodeReplacement),
                    0,
                ),
            )
            observer.cancelAndJoin()
            val expected =
                original.copy(
                    items = listOf(raw(3, order = 1), revised, raw(4, 21_000, 1), raw(2, order = 2)),
                    appliedVersion = 21_000,
                )
            assertEquals(expected, fresh())
            val restarted = open()
            var page = requireNotNull(restarted.newestAuthoritativePage("host", "c", 1))
            var walked = page.items
            while (page.continuation != null) {
                page = requireNotNull(restarted.beforeAuthoritativePage("host", "c", requireNotNull(page.continuation), 1))
                walked = page.items + walked
            }
            assertEquals(expected.items, walked)
            assertEquals(expected.coverage, page.coverage)
        }

    @Test fun retentionInvariant_trimClearsCertification() =
        runTest {
            val cache = open()
            val original =
                saved((1..MAX_CACHED_THREAD_ROWS).map { raw(it) }, 200_000).copy(
                    checkpoint = 190_000,
                    coverage = SavedThreadCoverage(1, 100_001, 1, false),
                )
            cache.writeAuthoritativeThread("host", "c", original).getOrThrow()
            val repo = wrapper(PartialCache(cache))
            var observer = observe(repo)
            apply(repo, 210_000, raw(100_001, 210_000))
            runCurrent()
            observer.cancelAndJoin()
            assertWork(cache, 0, 1, 1)
            // The held store still carries the old certificate. A later revision must not reinstall it.
            observer = observe(repo)
            apply(repo, 210_001, raw(100_001, 210_001))
            runCurrent()
            observer.cancelAndJoin()
            assertWork(cache, 1, 1)
            val restored = requireNotNull(fresh())
            assertEquals(MAX_CACHED_THREAD_ROWS, restored.items.size)
            assertEquals(original.items.drop(1) + raw(100_001, 210_001), restored.items)
            assertEquals(210_001L, restored.appliedVersion)
            assertEquals(0L, restored.checkpoint)
            assertNull(restored.coverage)

            // Completion inherits coverage for the trimmed fact; metadata changes cannot repair it.
            val checkpoint =
                ThreadQueryReply.Checkpoint(
                    MobileJson.parseToJsonElement("""{"conversation_id":"c","epoch":"e","version":210001}""").jsonObject,
                )
            for ((request, coverage) in listOf(
                ThreadQueryRequest.CatchUp("c", "e", 190_000uL) to ThreadQueryReply.Coverage.CatchUp(190_000uL),
                ThreadQueryRequest.Page("c", 1uL) to ThreadQueryReply.Coverage.Window(0uL, 1uL, 0uL, false),
            )) {
                observer = observe(repo)
                assertEquals(
                    AuthoritativeThreadOutcome.Applied,
                    repo.authoritativeThreads.certifyQuery(
                        "host",
                        request,
                        ThreadQueryReply.Success(JsonObject(emptyMap()), checkpoint, coverage),
                        0,
                    ),
                )
                runCurrent()
                observer.cancelAndJoin()
                assertWork(cache, 0, 0)
                assertEquals(210_001L, repo.authoritativeThreads.current("host", "c").checkpoint)
                if (request is ThreadQueryRequest.Page) {
                    assertEquals(
                        0uL,
                        repo.authoritativeThreads
                            .current("host", "c")
                            .coverage
                            ?.lowerOrder,
                    )
                }
                assertEquals(restored, fresh())
                val reopened = wrapper(PartialCache(cache)).observeAuthoritativeThread("c").first()
                assertEquals(0L, reopened.checkpoint)
                assertNull(reopened.coverage)
            }

            // A fresh conservative baseline can certify a newly supplied, fully durable window.
            val recovery = wrapper(PartialCache(cache))
            observer = observe(recovery)
            assertEquals(
                AuthoritativeThreadOutcome.Applied,
                recovery.authoritativeThreads.certifyQuery(
                    "host",
                    ThreadQueryRequest.CatchUp("c", "e", 0uL),
                    ThreadQueryReply.Success(
                        JsonObject(emptyMap()),
                        checkpoint,
                        ThreadQueryReply.Coverage.Window(99_802uL, 100_001uL, 99_802uL, true),
                    ),
                    0,
                ),
            )
            runCurrent()
            observer.cancelAndJoin()
            assertWork(cache, 0, 0)
            assertEquals(restored.copy(checkpoint = 210_001, coverage = SavedThreadCoverage(99_802, 100_001, 99_802, true)), fresh())
        }

    @Test fun retentionInvariant_coverageCompletionCannotReinstateTrimmedRange() =
        runTest {
            val cache = open()
            val original = saved((1..MAX_CACHED_THREAD_ROWS).map { raw(it) }, 200_000).copy(checkpoint = 0)
            cache.writeAuthoritativeThread("host", "c", original).getOrThrow()
            val repo = wrapper(PartialCache(cache))
            var observer = observe(repo)
            apply(repo, 210_000, raw(100_001, 210_000))
            runCurrent()
            observer.cancelAndJoin()
            assertWork(cache, 0, 1, 1)
            observer = observe(repo)
            val checkpoint =
                ThreadQueryReply.Checkpoint(
                    MobileJson.parseToJsonElement("""{"conversation_id":"c","epoch":"e","version":210000}""").jsonObject,
                )
            assertEquals(
                AuthoritativeThreadOutcome.Applied,
                repo.authoritativeThreads.certifyQuery(
                    "host",
                    ThreadQueryRequest.Page("c", 1uL),
                    ThreadQueryReply.Success(JsonObject(emptyMap()), checkpoint, ThreadQueryReply.Coverage.Window(0uL, 1uL, 0uL, false)),
                    0,
                ),
            )
            runCurrent()
            observer.cancelAndJoin()
            assertWork(cache, 0, 0)
            assertEquals(0L, repo.authoritativeThreads.current("host", "c").checkpoint)
            assertEquals(
                0uL,
                repo.authoritativeThreads
                    .current("host", "c")
                    .coverage
                    ?.lowerOrder,
            )
            assertEquals(
                original.copy(items = original.items.drop(1) + raw(100_001, 210_000), appliedVersion = 210_000, coverage = null),
                fresh(),
            )
            val reopened = wrapper(open()).observeAuthoritativeThread("c").first()
            assertEquals(0L, reopened.checkpoint)
            assertNull(reopened.coverage)
        }

    @Test fun orderingInvariant_partialRestoreChangedOrderPreservesFinalTie() = runTest { assertPartialRestoreTie(initialOrder = 10) }

    @Test fun orderingInvariant_partialRestoreUnchangedOrderPreservesFinalTie() = runTest { assertPartialRestoreTie(initialOrder = 20) }

    @Test fun orderingInvariant_liveBeforeRestorePreservesAcceptedTie() =
        runTest {
            val cache = open()
            val peer = raw(1, order = 20)
            val later = (3..201).map { raw(it, order = it + 100) }
            cache.writeAuthoritativeThread("host", "c", saved(listOf(peer, raw(2, order = 20)) + later)).getOrThrow()
            val before = ordinals()
            val captured = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val heldRead =
                object : ConversationCache by cache, AuthoritativeUpsertCache by cache {
                    override suspend fun readAuthoritativeThread(
                        serverId: String,
                        conversationId: String,
                    ): SavedAuthoritativeThread? {
                        val saved = cache.readAuthoritativeThread(serverId, conversationId)
                        captured.complete(Unit)
                        release.await()
                        return saved
                    }
                }
            val repo = wrapper(heldRead)
            val ready = CompletableDeferred<Unit>()
            val observer = backgroundScope.launch { repo.observeAuthoritativeThread("c").collect { ready.complete(Unit) } }
            captured.await()
            assertTrue(
                repo.authoritativeThreads
                    .current("host", "c")
                    .items
                    .isEmpty(),
            )
            apply(repo, 21_000, raw(2, 21_000, 20))
            release.complete(Unit)
            ready.await()
            runCurrent()
            val fullReads = cache.authoritativeFullReads
            val replacements = cache.authoritativeReplacements
            var placed: Map<Long, Int>? = null
            for (revision in 21_000..21_001) {
                if (revision > 21_000) {
                    // Re-reading the complete family adds no peers and must retain the accepted placement.
                    assertFalse(repo.openAuthoritativeThread("c"))
                    apply(repo, revision, raw(2, revision, 20))
                    runCurrent()
                }
                val expected = listOf(raw(2, revision, 20), peer) + later
                assertEquals(
                    expected,
                    repo.authoritativeThreads
                        .current("host", "c")
                        .items
                        .map { it.raw },
                )
                advanceTimeBy(101)
                runCurrent()
                requireNotNull(cache.newestAuthoritativePage("host", "c", 1))
                assertWork(cache, 1, 1)
                assertEquals(fullReads + (revision - 21_000), cache.authoritativeFullReads)
                assertEquals(replacements, cache.authoritativeReplacements)
                val currentOrdinals = ordinals()
                assertEquals(before.filterKeys { it != 2L }, currentOrdinals.filterKeys { it != 2L })
                placed?.let { assertEquals(it, currentOrdinals) }
                placed = currentOrdinals
                val reopened = requireNotNull(fresh())
                assertEquals(expected.take(2).map { it["id"] }, reopened.items.take(2).map { it["id"] })
                assertEquals(saved(expected).copy(appliedVersion = revision.toLong()), reopened)
                var page = requireNotNull(cache.newestAuthoritativePage("host", "c", 1))
                var walked = page.items
                while (page.continuation != null) {
                    page = requireNotNull(cache.beforeAuthoritativePage("host", "c", requireNotNull(page.continuation), 1))
                    walked = page.items + walked
                }
                assertEquals(expected, walked)
            }
            observer.cancelAndJoin()
            assertEquals(
                listOf(raw(2, 21_001, 20), peer) + later,
                wrapper(open())
                    .observeAuthoritativeThread("c")
                    .first()
                    .items
                    .map { it.raw },
            )
        }

    private suspend fun TestScope.assertPartialRestoreTie(initialOrder: Int) {
        val cache = open()
        val later = (3..201).map { raw(it, order = it + 100) }
        val peer = raw(2, order = 20)
        cache.writeAuthoritativeThread("host", "c", saved(listOf(raw(1, order = initialOrder), peer) + later)).getOrThrow()
        val before = ordinals()
        val repo = wrapper(PartialCache(cache))
        val observer = observe(repo)
        val restored = repo.authoritativeThreads.current("host", "c")
        assertEquals(200, restored.items.size)
        assertFalse(restored.items.any { it.id == 1L })
        val fullReads = cache.authoritativeFullReads
        val replacements = cache.authoritativeReplacements
        var placed: Map<Long, Int>? = null
        for (revision in 21_000..21_001) {
            apply(repo, revision, raw(1, revision, 20))
            runCurrent()
            val expected = listOf(peer, raw(1, revision, 20)) + later
            assertEquals(
                expected,
                repo.authoritativeThreads
                    .current("host", "c")
                    .items
                    .map { it.raw },
            )
            advanceTimeBy(101)
            runCurrent()
            // A bounded read joins the cache mutex after the writer's asynchronous Room commit.
            requireNotNull(cache.newestAuthoritativePage("host", "c", 1))
            assertWork(cache, 1, 1)
            assertEquals(fullReads, cache.authoritativeFullReads)
            assertEquals(replacements, cache.authoritativeReplacements)
            val currentOrdinals = ordinals()
            assertEquals(before.filterKeys { it != 1L }, currentOrdinals.filterKeys { it != 1L })
            placed?.let { assertEquals(it, currentOrdinals) }
            placed = currentOrdinals
            val reopened = requireNotNull(fresh())
            assertEquals(expected.take(2).map { it["id"] }, reopened.items.take(2).map { it["id"] })
            assertEquals(saved(expected).copy(appliedVersion = revision.toLong()), reopened)
        }
        observer.cancelAndJoin()
        val expected = listOf(peer, raw(1, 21_001, 20)) + later
        var page = requireNotNull(cache.newestAuthoritativePage("host", "c", 1))
        var walked = page.items
        while (page.continuation != null) {
            page = requireNotNull(cache.beforeAuthoritativePage("host", "c", requireNotNull(page.continuation), 1))
            walked = page.items + walked
        }
        assertEquals(expected, walked)
        assertEquals(
            expected,
            wrapper(open())
                .observeAuthoritativeThread("c")
                .first()
                .items
                .map { it.raw },
        )
    }

    @Test fun orderingInvariant_coalescedMovesPreserveFinalTies() =
        runTest {
            for ((initialOrder, deliverIntermediate) in listOf(10 to true, 20 to true, 10 to false, 20 to false)) {
                val cache = open()
                cache
                    .writeAuthoritativeThread(
                        "host",
                        "c",
                        saved(listOf(raw(1, order = initialOrder), raw(2, order = 20), raw(3, order = 20), raw(4, order = 40))),
                    ).getOrThrow()
                val before = ordinals()
                val repo = wrapper(cache)
                val observer = observe(repo)
                val fullReads = cache.authoritativeFullReads
                apply(repo, 21_000, raw(1, 21_000, 30))
                if (deliverIntermediate) runCurrent()
                apply(repo, 21_001, raw(1, 21_001, 20))
                runCurrent()
                val expected =
                    repo.authoritativeThreads
                        .current("host", "c")
                        .items
                        .map { it.raw }
                assertEquals(listOf(raw(2, order = 20), raw(3, order = 20), raw(1, 21_001, 20), raw(4, order = 40)), expected)
                advanceTimeBy(101)
                runCurrent()
                observer.cancelAndJoin()
                assertWork(cache, 1, 1)
                assertEquals(fullReads, cache.authoritativeFullReads)
                assertEquals(before.filterKeys { it != 1L }, ordinals().filterKeys { it != 1L })
                assertEquals(expected, fresh()?.items)
                var page = requireNotNull(cache.newestAuthoritativePage("host", "c", 1))
                var walked = page.items
                while (page.continuation != null) {
                    page = requireNotNull(cache.beforeAuthoritativePage("host", "c", requireNotNull(page.continuation), 1))
                    walked = page.items + walked
                }
                assertEquals(expected, walked)
                assertEquals(
                    expected,
                    wrapper(open())
                        .observeAuthoritativeThread("c")
                        .first()
                        .items
                        .map { it.raw },
                )
            }
        }

    @Test fun orderingInvariant_repeatedMovesIntoTiesOnlyRewriteMovedFacts() =
        runTest {
            val cache = open()
            cache
                .writeAuthoritativeThread(
                    "host",
                    "c",
                    saved(listOf(raw(2, order = 10), raw(1, order = 20), raw(3, order = 30), raw(4, order = 40))),
                ).getOrThrow()
            val repo = wrapper(cache)
            val batches =
                listOf(
                    listOf(raw(1, 21_000, 5)),
                    listOf(raw(1, 21_001, 10)),
                    listOf(raw(2, 21_002, 30), raw(1, 21_002, 30)),
                    listOf(raw(4, 21_003, 30)),
                    listOf(raw(1, 21_004, 0)),
                    listOf(raw(1, 21_005, 30)),
                )
            batches.forEachIndexed { index, facts ->
                val observer = observe(repo)
                val before = ordinals()
                apply(repo, 21_000 + index, *facts.toTypedArray())
                runCurrent()
                observer.cancelAndJoin()
                val ids = facts.map { it.getValue("id").toString().toLong() }.toSet()
                assertEquals(before.filterKeys { it !in ids }, ordinals().filterKeys { it !in ids })
                val current = repo.authoritativeThreads.current("host", "c")
                assertEquals(current.items.map { it.raw }, fresh()?.items)
            }
        }

    @Test fun atomicInvariant_postCommitCancellationPublishesBaselineBeforeFinalFlush() =
        runTest {
            val cache = open()
            cache.writeAuthoritativeThread("host", "c", saved(listOf(raw(1), raw(2)))).getOrThrow()
            val committed = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var hold = true
            val held =
                object : ConversationCache by cache, AuthoritativeUpsertCache {
                    override suspend fun upsertAuthoritativeThread(
                        serverId: String,
                        conversationId: String,
                        saved: SavedAuthoritativeThread,
                        reset: Boolean,
                        baseline: SavedAuthoritativeThread?,
                        orderMoves: Map<Long, Boolean>,
                    ): Result<Unit> {
                        val result = cache.upsertAuthoritativeThread(serverId, conversationId, saved, reset, baseline, orderMoves)
                        if (hold) {
                            committed.complete(Unit)
                            release.await()
                            hold = false
                        }
                        return result
                    }
                }
            val repo = wrapper(held)
            val observer = observe(repo)
            apply(repo, 21_000, raw(1, 21_000))
            runCurrent()
            advanceTimeBy(101)
            runCurrent()
            committed.await()
            apply(repo, 22_000, raw(2, 22_000))
            runCurrent()
            observer.cancel()
            release.complete(Unit)
            observer.join()
            assertWork(cache, 1, 1)
            assertEquals(
                repo.authoritativeThreads
                    .current("host", "c")
                    .items
                    .map { it.raw },
                fresh()?.items,
            )
            assertEquals(22_000L, fresh()?.appliedVersion)
        }

    @Test fun retirementInvariant_resetWhileFinalFlushWaitsForWriteLock() =
        runTest {
            var hook: suspend () -> Unit = {}
            val cache = open { hook() }
            cache.writeAuthoritativeThread("host", "c", saved(listOf(raw(1)))).getOrThrow()
            val repo = wrapper(cache)
            val first = observe(repo)
            val second = observe(repo)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            hook = {
                entered.complete(Unit)
                release.await()
            }
            apply(repo, 21_000, raw(1, 21_000))
            runCurrent()
            advanceTimeBy(101)
            runCurrent()
            entered.await()
            first.cancel()
            second.cancel()
            runCurrent()
            repo.authoritativeThreads.reset("host", "c", "e")
            release.complete(Unit)
            first.join()
            second.join()
            assertEquals(SavedAuthoritativeThread("e", emptyList(), 0, 0), fresh())
        }

    @Test fun admissionInvariant_deferredImportCannotBeOverwrittenByOwnedSaves() =
        runTest {
            val original = saved(listOf(raw(1), raw(2)))
            de.pyryco.mobile.data.cache
                .FileConversationCache(File(temporary.root, "files"), StandardTestDispatcher(testScheduler))
                .writeAuthoritativeThread("host", "c", original)
                .getOrThrow()
            var fail = true
            val cache = open { if (fail) throw IOException("private") }
            assertNull(cache.readAuthoritativeThread("host", "c"))
            fail = false
            val rejected = cache.upsertAuthoritativeThread("host", "c", original.copy(items = listOf(raw(3))), false, original, emptyMap())
            assertTrue(rejected.isFailure)
            assertTrue(rejected.exceptionOrNull()?.message?.endsWith("import_unobserved") == true)
            assertNull(rejected.exceptionOrNull()?.cause)
            assertEquals(original, fresh())
            val restarted = open()
            restarted.removeHost("host").getOrThrow()
            val retired = restarted.upsertAuthoritativeThread("host", "c", original, false, original, emptyMap())
            assertTrue(retired.exceptionOrNull()?.message?.endsWith("host_retired") == true)
            assertNull(fresh())
        }

    @Test fun retirementInvariant_resetDuringFinalIoRetiresReusedEpoch() =
        runTest {
            for (epoch in listOf("e", "new")) {
                var hook: suspend () -> Unit = {}
                val cache = open { hook() }
                cache.writeAuthoritativeThread("host", "c", saved(listOf(raw(1)))).getOrThrow()
                val repo = wrapper(cache)
                val ready = CompletableDeferred<Unit>()
                val observer = backgroundScope.launch { repo.observeAuthoritativeThread("c").collect { ready.complete(Unit) } }
                ready.await()
                runCurrent()
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val retirement = CompletableDeferred<Unit>()
                val releaseRetirement = CompletableDeferred<Unit>()
                var calls = 0
                hook = {
                    if (++calls == 1) {
                        entered.complete(Unit)
                        release.await()
                    } else {
                        retirement.complete(Unit)
                        releaseRetirement.await()
                    }
                }
                apply(repo, 21_000, raw(1, 21_000))
                runCurrent()
                observer.cancel()
                runCurrent()
                entered.await()
                assertEquals(AuthoritativeThreadOutcome.Applied, repo.authoritativeThreads.reset("host", "c", epoch))
                release.complete(Unit)
                runCurrent()
                retirement.await()
                assertFalse(observer.isCompleted)
                releaseRetirement.complete(Unit)
                runCurrent()
                observer.join()
                assertEquals(SavedAuthoritativeThread(epoch, emptyList(), 0, 0), fresh())
            }
        }

    @Test fun retirementInvariant_deletionFencesHeldSavesAndOtherHost() =
        runTest {
            for (hostRemoval in listOf(false, true)) {
                var hook: suspend () -> Unit = {}
                val cache = open { hook() }
                val prior = saved(listOf(raw(1), raw(2)))
                cache.writeAuthoritativeThread("host", "c", prior).getOrThrow()
                cache.writeAuthoritativeThread("other", "c", prior).getOrThrow()
                val repo = wrapper(cache)
                val ready = CompletableDeferred<Unit>()
                val observer = backgroundScope.launch { repo.observeAuthoritativeThread("c").collect { ready.complete(Unit) } }
                ready.await()
                runCurrent()
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                hook = {
                    entered.complete(Unit)
                    release.await()
                }
                apply(repo, 21_000, raw(1, 21_000))
                runCurrent()
                advanceTimeBy(101)
                runCurrent()
                entered.await()
                val deletion =
                    backgroundScope.launch {
                        if (hostRemoval) cache.removeHost("host").getOrThrow() else repo.delete("c")
                    }
                runCurrent()
                apply(repo, 22_000, raw(1, 22_000))
                runCurrent()
                release.complete(Unit)
                runCurrent()
                deletion.join()
                observer.cancelAndJoin()
                assertNull(fresh())
                assertEquals(prior, fresh("other"))
            }
        }
}
