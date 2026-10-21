package de.pyryco.mobile.data.cache

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.repository.CachingConversationRepository
import de.pyryco.mobile.di.SavedAuthoritativeDestination
import de.pyryco.mobile.ui.conversations.thread.ThreadHistoryTail
import de.pyryco.mobile.ui.conversations.thread.ThreadViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AuthoritativeReaderPagingTest {
    @get:Rule val temporary = TemporaryFolder()
    private var database: ConversationCacheDatabase? = null
    private var databaseName = "pages.db"

    private fun disk(): MigratingConversationCache {
        database?.close()
        val db =
            Room
                .databaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    ConversationCacheDatabase::class.java,
                    File(temporary.root, databaseName).absolutePath,
                ).build()
        database = db
        return MigratingConversationCache(File(temporary.root, "files"), db)
    }

    @After fun close() {
        database?.close()
    }

    private fun fact(
        id: Int,
        shown: Boolean = true,
    ): JsonObject =
        buildJsonObject {
            put("id", id)
            put("rev", id)
            put("kind", "assistant_message")
            put("order", id * 2)
            put("shown", shown)
            put("active", false)
            put("status", "done")
            put("summary", "")
            put("content", buildJsonObject { put("text", "Saved exact $id") })
        }

    private suspend fun TestScope.destination(
        cache: ConversationCache,
        online: Boolean = false,
        block: suspend TestScope.(SavedAuthoritativeDestination, ThreadViewModel, CachingConversationRepository) -> Unit,
    ) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        lateinit var repository: CachingConversationRepository
        val fixture =
            SavedAuthoritativeDestination(cache, dispatcher, dispatcher, awaitFrame = { 1L }, decorate = {
                (it as CachingConversationRepository).also { repository = it }
            })
        runCurrent()
        if (online) fixture.connect()
        runCurrent()
        val vm = fixture.open()
        val owner = ViewModelStore().also { it.put("thread", vm) }
        val collector = backgroundScope.launch { vm.state.collect {} }
        try {
            block(fixture, vm, repository)
        } finally {
            owner.clear()
            vm.viewModelScope.coroutineContext[Job]?.join()
            collector.cancelAndJoin()
            fixture.close()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun demandIsIndependentAndNeverWalksAtRest() =
        runTest {
            val original =
                SavedAuthoritativeThread(
                    "e",
                    (1..18_000).map { fact(it) } + (18_001..19_000).map { fact(it, false) },
                    30_000,
                    29_000,
                    SavedThreadCoverage(35_800, 36_000, 35_800, true),
                )
            disk().writeAuthoritativeThread("host", "c", original).getOrThrow()
            for (online in listOf(false, true)) {
                val cache = disk()
                destination(cache, online) { fixture, vm, repo ->
                    vm.state.first { it.authoritativeThread?.rows?.size == 200 }
                    val requests = fixture.requests.size
                    vm.onDemandOlderHistory()
                    vm.state.first { (it.authoritativeThread?.rows?.size ?: 0) > 200 }
                    val snapshot = repo.authoritativeThreads.current("host", "c")
                    assertEquals(400, snapshot.openingRows?.size)
                    assertEquals(original.items.take(18_000).takeLast(400), snapshot.items.map { it.raw })
                    assertEquals(original.checkpoint, snapshot.checkpoint)
                    assertEquals(original.coverage?.toDomain(), snapshot.coverage)
                    assertEquals(0, cache.authoritativeFullReads)
                    assertTrue(cache.authoritativeReadCounts.itemDecodes <= 200)
                    advanceTimeBy(1000)
                    runCurrent()
                    assertEquals(
                        400,
                        repo.authoritativeThreads
                            .current("host", "c")
                            .items.size,
                    )
                    assertEquals(requests, fixture.requests.size)
                }
            }
        }

    @Test fun localWalkKeepsExactFactsAndCertificate() =
        runTest {
            val original =
                SavedAuthoritativeThread(
                    "e",
                    (1..18_000).map { fact(it) },
                    20_000,
                    19_000,
                    SavedThreadCoverage(35_800, 36_000, 35_800, true),
                )
            disk().writeAuthoritativeThread("host", "c", original).getOrThrow()
            disk().writeAuthoritativeThread("other", "c", original.copy(items = listOf(fact(1)))).getOrThrow()
            val cache = disk()
            destination(cache) { fixture, vm, repo ->
                vm.state.first { it.authoritativeThread?.rows?.size == 200 }
                repeat(89) { page ->
                    vm.onDemandOlderHistory()
                    vm.state.first { (it.authoritativeThread?.rows?.size ?: 0) >= (page + 2) * 200 }
                    val snapshot = repo.authoritativeThreads.current("host", "c")
                    assertEquals((page + 2) * 200, snapshot.items.size)
                    assertEquals(original.items.takeLast(snapshot.items.size), snapshot.items.map { it.raw })
                    assertEquals(original.checkpoint, snapshot.checkpoint)
                    assertEquals(original.coverage?.toDomain(), snapshot.coverage)
                    assertEquals(0, cache.authoritativeFullReads)
                    assertTrue(cache.authoritativeReadCounts.itemDecodes <= 200)
                    println("reader_page=${page + 1} counts=${cache.authoritativeReadCounts}")
                }
                assertEquals(0, fixture.requests.count { it.type in setOf("thread_page", "thread_catch_up") })
            }
            assertEquals(original, disk().readAuthoritativeThread("host", "c"))
            assertEquals(listOf(fact(1)), disk().readAuthoritativeThread("other", "c")?.items)
        }

    @Test fun retryKeepsTheReaderBoundary() =
        runTest {
            disk()
                .writeAuthoritativeThread(
                    "host",
                    "c",
                    SavedAuthoritativeThread("e", (1..18_000).map { fact(it) }, 30_000, 29_000),
                ).getOrThrow()
            val disk = disk()
            var fail = true
            var attempts = 0
            val cache =
                object : ConversationCache by disk, AuthoritativeWindowCache by disk, AuthoritativeUpsertCache by disk {
                    override suspend fun beforeAuthoritativeWindow(
                        serverId: String,
                        conversationId: String,
                        cursor: AuthoritativeDisplayCursor,
                        admitted: Set<Pair<Long, Boolean>>,
                        size: Int,
                    ): AuthoritativeThreadWindow? {
                        attempts++
                        return if (fail) null else disk.beforeAuthoritativeWindow(serverId, conversationId, cursor, admitted, size)
                    }
                }
            destination(cache) { fixture, vm, repo ->
                vm.state.first { it.authoritativeThread?.rows?.size == 200 }
                vm.onDemandOlderHistory()
                vm.state.first { it.historyTail == ThreadHistoryTail.Retry }
                assertEquals(
                    200,
                    repo.authoritativeThreads
                        .current("host", "c")
                        .items.size,
                )
                assertEquals(1, attempts)
                advanceTimeBy(1000)
                runCurrent()
                assertEquals(1, attempts)
                fail = false
                vm.onRetryOlderHistory()
                vm.state.first { it.authoritativeThread?.rows?.size == 400 }
                assertEquals(
                    (17_601..18_000).map { fact(it) },
                    repo.authoritativeThreads
                        .current("host", "c")
                        .items
                        .map { it.raw },
                )
                assertEquals(2, attempts)
                assertTrue(fixture.requests.none { it.type in setOf("thread_page", "thread_catch_up") })
            }
        }

    @Test fun localExhaustionPreservesDaemonCoverage() =
        runTest {
            val original = SavedAuthoritativeThread("e", (1..250).map { fact(it) }, 1000, 900, SavedThreadCoverage(450, 500, 450, true))
            disk().writeAuthoritativeThread("host", "c", original).getOrThrow()
            val cache = disk()
            destination(cache, online = true) { fixture, vm, repo ->
                vm.state.first { it.authoritativeThread?.rows?.size == 200 }
                val before = fixture.requests.count { it.type in setOf("thread_page", "thread_catch_up") }
                vm.onDemandOlderHistory()
                vm.state.first { it.authoritativeThread?.rows?.size == 250 }
                assertEquals(before, fixture.requests.count { it.type in setOf("thread_page", "thread_catch_up") })
                vm.onDemandOlderHistory()
                vm.state.first { it.historyTail == ThreadHistoryTail.Loading }
                vm.state.first { it.historyTail != ThreadHistoryTail.Loading }
                runCurrent()
                val snapshot = repo.authoritativeThreads.current("host", "c")
                assertEquals(original.coverage?.toDomain(), snapshot.coverage)
                assertEquals(original.checkpoint, snapshot.checkpoint)
                assertEquals(0, cache.authoritativeFullReads)
                val newest = fixture.requests.last { it.type in setOf("thread_page", "thread_catch_up") }
                fixture.reply(newest, """{"conversation_id":"c","epoch":"e","version":1000,"items":[],"final":true,"from_version":900}""")
                vm.state.first { it.authoritativeThread?.sourceSnapshot?.checkpoint == 1000L }
                runCurrent()
                assertEquals(before + 1, fixture.requests.count { it.type in setOf("thread_page", "thread_catch_up") })
                assertEquals(
                    JsonPrimitive(450),
                    fixture.requests
                        .last { it.type == "thread_page" }
                        .payload.jsonObject["before_order"],
                )
            }
            assertEquals(original, disk().readAuthoritativeThread("host", "c"))
        }

    @Test fun ownedWritesReselectAtTheReaderBoundary() =
        runTest {
            val original =
                SavedAuthoritativeThread(
                    "e",
                    (1..18_000).map { fact(it) },
                    30_000,
                    29_000,
                    SavedThreadCoverage(35_800, 36_000, 35_800, true),
                )
            disk().writeAuthoritativeThread("host", "c", original).getOrThrow()
            val cache = disk()
            val revised = JsonObject(fact(18_000) + ("rev" to JsonPrimitive(40_000)) + ("future" to JsonNull))
            destination(cache) { _, vm, repo ->
                vm.state.first { it.authoritativeThread?.rows?.size == 200 }
                vm.onDemandOlderHistory()
                vm.state.first { it.authoritativeThread?.rows?.size == 400 }
                repo.authoritativeThreads.applyBatch("host", "c", "e", 40_000, listOf(ThreadItemDto.decodeReplacement(revised)), 0)
                vm.state.first {
                    it.authoritativeThread
                        ?.sourceSnapshot
                        ?.items
                        ?.any { item -> item.raw == revised } == true
                }
                val removal = requireNotNull(repo.authoritativeThreads.captureRemoval("host", "c", 17_601))
                repo.removeAuthoritativeFact(removal).getOrThrow()
                vm.state.first { it.authoritativeThread?.rows?.size == 399 }
                vm.onDemandOlderHistory()
                vm.state.first { it.authoritativeThread?.rows?.size == 599 }
                val expected =
                    original.items
                        .takeLast(600)
                        .filterNot { it["id"] == JsonPrimitive(17_601) }
                        .map { if (it["id"] == JsonPrimitive(18_000)) revised else it }
                assertEquals(
                    expected,
                    repo.authoritativeThreads
                        .current("host", "c")
                        .items
                        .map { it.raw },
                )
                assertEquals(0, cache.authoritativeFullReads)
            }
            val fresh = requireNotNull(disk().readAuthoritativeThread("host", "c"))
            assertEquals(
                original.items
                    .filterNot { it["id"] == JsonPrimitive(17_601) }
                    .map { if (it["id"] == JsonPrimitive(18_000)) revised else it },
                fresh.items,
            )
            assertTrue(17_601L in fresh.retirements)
        }

    @Test fun overlapKeepsWinningFactsAndOrder() =
        runTest {
            val original = SavedAuthoritativeThread("e", (1..18_000).map { fact(it) }, 30_000, 29_000)
            disk().writeAuthoritativeThread("host", "c", original).getOrThrow()
            val cache = disk()
            val revised = JsonObject(fact(17_750) + mapOf("rev" to JsonPrimitive(40_000), "parent" to JsonNull, "future" to JsonNull))
            destination(cache) { _, vm, repo ->
                vm.state.first { it.authoritativeThread?.rows?.size == 200 }
                repo.authoritativeThreads.applyBatch("host", "c", "e", 40_000, listOf(ThreadItemDto.decodeReplacement(revised)), 0)
                vm.state.first { it.authoritativeThread?.rows?.size == 201 }
                vm.onDemandOlderHistory()
                vm.state.first { it.authoritativeThread?.rows?.size == 401 }
                val snapshot = repo.authoritativeThreads.current("host", "c")
                assertEquals((17_600..18_000).map { if (it == 17_750) revised else fact(it) }, snapshot.items.map { it.raw })
                assertEquals(snapshot.items.size, snapshot.items.distinctBy { it.id }.size)
                assertEquals(29_000L, snapshot.checkpoint)
                assertEquals(0, cache.authoritativeFullReads)
            }
        }

    @Test fun staleAndCancelledReadsCannotApply() =
        runTest {
            for (event in listOf("reconnect", "reset", "exit", "delete")) {
                databaseName = "event-$event.db"
                val original = SavedAuthoritativeThread("e", (1..18_000).map { fact(it) }, 30_000, 29_000)
                disk().writeAuthoritativeThread("host", "c", original).getOrThrow()
                val disk = disk()
                val captured = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val cancelled = CompletableDeferred<Unit>()
                val cache =
                    object : ConversationCache by disk, AuthoritativeWindowCache by disk, AuthoritativeUpsertCache by disk {
                        override suspend fun beforeAuthoritativeWindow(
                            serverId: String,
                            conversationId: String,
                            cursor: AuthoritativeDisplayCursor,
                            admitted: Set<Pair<Long, Boolean>>,
                            size: Int,
                        ): AuthoritativeThreadWindow? {
                            val page = disk.beforeAuthoritativeWindow(serverId, conversationId, cursor, admitted, size)
                            captured.complete(Unit)
                            try {
                                release.await()
                            } finally {
                                cancelled.complete(Unit)
                            }
                            return page
                        }
                    }
                destination(cache, online = true) { fixture, vm, repo ->
                    vm.state.first { it.authoritativeThread?.rows?.size == 200 }
                    vm.onDemandOlderHistory()
                    captured.await()
                    vm.onDemandOlderHistory()
                    when (event) {
                        "exit" -> return@destination
                        "reconnect" -> {
                            fixture.disconnect()
                            runCurrent()
                            fixture.connect()
                            runCurrent()
                        }
                        "reset" -> {
                            fixture.resetNewest()
                            runCurrent()
                        }
                        "delete" -> disk.removeHost("host").getOrThrow()
                    }
                    release.complete(Unit)
                    if (event == "reconnect") {
                        cancelled.await()
                        runCurrent()
                        assertEquals(
                            200,
                            repo.authoritativeThreads
                                .current("host", "c")
                                .items.size,
                        )
                        vm.state.first { it.authoritativeThread?.rows?.size == 200 }
                        vm.onDemandOlderHistory()
                        vm.state.first { it.authoritativeThread?.rows?.size == 400 }
                        advanceTimeBy(1000)
                        runCurrent()
                        assertEquals(
                            400,
                            repo.authoritativeThreads
                                .current("host", "c")
                                .items.size,
                        )
                    } else {
                        runCurrent()
                        assertTrue(
                            repo.authoritativeThreads
                                .current("host", "c")
                                .items.size <= 200,
                        )
                    }
                    assertEquals(0, disk.authoritativeFullReads)
                }
                assertTrue(cancelled.isCompleted)
                println("reader_lifetime event=$event cancelled=true")
                if (event == "delete") assertEquals(null, disk().readAuthoritativeThread("host", "c"))
            }
        }

    @Test fun savedTieOrderSurvivesPageOverlap() =
        runTest {
            val facts = (1..600).map { JsonObject(fact(it) + ("order" to JsonPrimitive(10))) }
            disk().writeAuthoritativeThread("host", "c", SavedAuthoritativeThread("e", facts, 1000, 1000)).getOrThrow()
            val cache = disk()
            destination(cache) { _, vm, repo ->
                vm.state.first { it.authoritativeThread?.rows?.size == 200 }
                vm.onDemandOlderHistory()
                vm.state.first { it.authoritativeThread?.rows?.size == 400 }
                assertEquals(
                    facts.takeLast(400),
                    repo.authoritativeThreads
                        .current("host", "c")
                        .items
                        .map { it.raw },
                )
            }
            assertEquals(facts, disk().readAuthoritativeThread("host", "c")?.items)
        }

    @Test fun displayBudgetCountsContributionsAndClosesParents() =
        runTest {
            val parent = JsonObject(fact(25_000, false) + mapOf("kind" to JsonPrimitive("agent"), "order" to JsonNull))
            val active = JsonObject(fact(26_000, false) + mapOf("active" to JsonPrimitive(true), "parent" to JsonPrimitive(25_000)))
            val displayed =
                (1..18_000).map { id ->
                    if (id == 17_790) JsonObject(fact(id) + ("parent" to JsonPrimitive(25_000))) else fact(id)
                }
            val original =
                SavedAuthoritativeThread(
                    "e",
                    displayed + (18_001..20_000).map { fact(it, false) } + parent + active,
                    30_000,
                    29_000,
                    SavedThreadCoverage(35_800, 36_000, 35_800, true),
                )
            disk().writeAuthoritativeThread("host", "c", original).getOrThrow()
            val cache = disk()
            destination(cache) { _, vm, repo ->
                vm.state.first { it.authoritativeThread?.rows?.size == 200 }
                vm.onDemandOlderHistory()
                vm.state.first { it.authoritativeThread?.rows?.size == 400 }
                val snapshot = repo.authoritativeThreads.current("host", "c")
                assertEquals((displayed.takeLast(400) + parent + active).toSet(), snapshot.items.map { it.raw }.toSet())
                assertTrue(cache.authoritativeReadCounts.itemDecodes <= 204)
                assertEquals(original.coverage?.toDomain(), snapshot.coverage)
                assertFalse(snapshot.items.any { it.id in 18_001L..20_000L })
                println("reader_extras counts=${cache.authoritativeReadCounts}")
            }
        }
}
