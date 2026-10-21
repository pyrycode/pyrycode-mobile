package de.pyryco.mobile.data.cache

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.repository.CachingConversationRepository
import de.pyryco.mobile.data.repository.ThreadItem
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

    private fun trackingWrite(
        disk: MigratingConversationCache,
        raw: JsonObject,
        committed: CompletableDeferred<Unit>,
    ): ConversationCache =
        object : ConversationCache by disk, AuthoritativeWindowCache by disk, AuthoritativeUpsertCache by disk {
            override suspend fun upsertAuthoritativeThread(
                serverId: String,
                conversationId: String,
                saved: SavedAuthoritativeThread,
                reset: Boolean,
                baseline: SavedAuthoritativeThread?,
                orderMoves: Map<Long, Boolean>,
            ): Result<Unit> =
                disk.upsertAuthoritativeThread(serverId, conversationId, saved, reset, baseline, orderMoves).also {
                    if (it.isSuccess && raw in saved.items) committed.complete(Unit)
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
            // 120 saved groups of 150 rows, separated by 250 genuinely absent daemon identities.
            val displayed = (0 until 18_000).map { fact(1 + it + (it / 150) * 250) }
            val hidden = (60_001..61_000).map { fact(it, false) }
            val newestOrder = ThreadItemDto.decodeReplacement(displayed.last()).order ?: error("fixture order")
            val original =
                SavedAuthoritativeThread(
                    "e",
                    displayed + hidden,
                    100_000,
                    99_000,
                    SavedThreadCoverage(newestOrder - 200, newestOrder, newestOrder - 200, true),
                )
            disk().writeAuthoritativeThread("host", "c", original).getOrThrow()
            disk().writeAuthoritativeThread("other", "c", original.copy(items = listOf(fact(1)))).getOrThrow()
            val cache = disk()
            val revised = JsonObject(displayed.last() + mapOf("rev" to JsonPrimitive(110_000), "future" to JsonNull))
            val committed = CompletableDeferred<Unit>()
            val expected = displayed.dropLast(1) + revised
            destination(trackingWrite(cache, revised, committed)) { fixture, vm, repo ->
                vm.state.first { it.authoritativeThread?.rows?.size == 200 }
                repo.authoritativeThreads.applyBatch("host", "c", "e", 110_000, listOf(ThreadItemDto.decodeReplacement(revised)), 0)
                committed.await()
                val requests = fixture.requests.toList()
                repeat(89) { page ->
                    vm.onDemandOlderHistory()
                    vm.state.first { (it.authoritativeThread?.rows?.size ?: 0) >= (page + 2) * 200 }
                    val snapshot = repo.authoritativeThreads.current("host", "c")
                    assertEquals((page + 2) * 200, snapshot.items.size)
                    assertEquals(expected.takeLast(snapshot.items.size), snapshot.items.map { it.raw })
                    assertEquals(snapshot.items.size, snapshot.items.distinctBy { it.id }.size)
                    assertEquals(
                        snapshot.items.map { it.id to "Saved exact ${it.id}" },
                        vm.state.value.authoritativeThread?.items?.filterIsInstance<ThreadItem.MessageItem>()?.map {
                            it.message.id
                                .substringAfterLast(':')
                                .toLong() to it.message.content
                        },
                    )
                    assertEquals(original.checkpoint, snapshot.checkpoint)
                    assertEquals(original.coverage?.toDomain(), snapshot.coverage)
                    assertEquals(0, cache.authoritativeFullReads)
                    val counts = cache.authoritativeReadCounts
                    assertEquals(200, counts.itemReads)
                    assertEquals(200, counts.itemDecodes)
                    assertEquals((if (page == 88) 200 else 400) + (if (page == 0) 1 else 0), counts.ordinalReads)
                    assertTrue(counts.queries in 8..9)
                    assertEquals(1, counts.evidenceReads)
                    assertEquals(1, counts.evidenceDecodes)
                    println("reader_page=${page + 1} counts=${cache.authoritativeReadCounts}")
                    advanceTimeBy(1000)
                    runCurrent()
                    assertEquals(
                        (page + 2) * 200,
                        repo.authoritativeThreads
                            .current("host", "c")
                            .items.size,
                    )
                    assertEquals(requests, fixture.requests)
                }
                assertEquals(0, fixture.requests.count { it.type in setOf("thread_page", "thread_catch_up") })
            }
            assertEquals(original.copy(items = expected + hidden, appliedVersion = 110_000), disk().readAuthoritativeThread("host", "c"))
            val reopened = disk()
            destination(reopened) { fixture, vm, repo ->
                vm.state.first { it.authoritativeThread?.rows?.size == 200 }
                vm.onDemandOlderHistory()
                vm.state.first { it.authoritativeThread?.rows?.size == 400 }
                assertEquals(
                    expected.takeLast(400),
                    repo.authoritativeThreads
                        .current("host", "c")
                        .items
                        .map { it.raw },
                )
                assertEquals(original.coverage?.toDomain(), repo.authoritativeThreads.current("host", "c").coverage)
                assertEquals(original.checkpoint, repo.authoritativeThreads.current("host", "c").checkpoint)
                assertTrue(fixture.requests.none { it.type in setOf("thread_page", "thread_catch_up") })
            }
            assertEquals(listOf(fact(1)), disk().readAuthoritativeThread("other", "c")?.items)
        }

    @Test fun retiredBoundaryRecoversAfterAncestorPlacementRebuild() =
        runTest {
            for (movedOrder in listOf(20_000, 1, 10_000)) {
                databaseName = "retired-boundary-$movedOrder.db"
                val parent = JsonObject(fact(90_000, false) + mapOf("kind" to JsonPrimitive("agent"), "order" to JsonPrimitive(10_000)))
                val active = JsonObject(fact(90_001) + mapOf("order" to JsonPrimitive(1), "active" to JsonPrimitive(true)))
                val children = (1..600).map { JsonObject(fact(it) + ("parent" to JsonPrimitive(90_000))) }
                val original = SavedAuthoritativeThread("e", listOf(active) + children + parent, 100_000, 99_000)
                disk().writeAuthoritativeThread("host", "c", original).getOrThrow()
                val cache = disk()
                val moved = JsonObject(parent + mapOf("rev" to JsonPrimitive(110_000), "order" to JsonPrimitive(movedOrder)))
                val rankInputs =
                    if (movedOrder == 10_000) {
                        (91_001..91_003).map {
                            JsonObject(fact(it, false) + mapOf("parent" to JsonPrimitive(90_000), "order" to JsonPrimitive(1)))
                        }
                    } else {
                        emptyList()
                    }
                val committed = CompletableDeferred<Unit>()
                destination(trackingWrite(cache, moved, committed)) { fixture, vm, repo ->
                    vm.state.first { it.authoritativeThread?.rows?.size == 201 }
                    repo.removeAuthoritativeFact(requireNotNull(repo.authoritativeThreads.captureRemoval("host", "c", 401))).getOrThrow()
                    vm.state.first { it.authoritativeThread?.rows?.size == 200 }
                    repo.authoritativeThreads.applyBatch(
                        "host",
                        "c",
                        "e",
                        110_000,
                        (listOf(moved) + rankInputs).map { ThreadItemDto.decodeReplacement(it) },
                        0,
                    )
                    committed.await()
                    vm.onDemandOlderHistory()
                    // Await settlement as well as delivery: the broken cursor reports exhaustion with 200 rows.
                    vm.state.first { it.historyTail == ThreadHistoryTail.Loading }
                    vm.state.first { it.historyTail != ThreadHistoryTail.Loading }
                    val applied = repo.authoritativeThreads.current("host", "c")
                    val selected = requireNotNull(applied.openingRows)
                    val heldRows = applied.items.count { it.shown && (it.id to false) in selected }
                    println("reader_retired_applied moved_order=$movedOrder rows=$heldRows counts=${cache.authoritativeReadCounts}")
                    assertEquals(400, heldRows)
                    vm.state.first { it.authoritativeThread?.rows?.size == 400 }
                    assertEquals(
                        children.takeLast(400).filterNot { it["id"] == JsonPrimitive(401) },
                        repo.authoritativeThreads
                            .current("host", "c")
                            .items
                            .filter { it.shown && it.parent == 90_000L && (it.id to false) in selected }
                            .map { it.raw },
                    )
                    assertEquals(
                        children.take(1),
                        applied.items.filter { it.shown && it.parent == 90_000L && (it.id to false) !in selected }.map { it.raw },
                    )
                    val counts = cache.authoritativeReadCounts
                    println("reader_retired_boundary moved_order=$movedOrder counts=$counts")
                    assertTrue(counts.itemDecodes <= 203)
                    assertTrue(counts.ordinalReads <= 800)
                    assertTrue(counts.queries <= 14)
                    vm.onDemandOlderHistory()
                    vm.state.first { it.authoritativeThread?.rows?.size == 600 }
                    assertEquals(
                        children.filterNot { it["id"] == JsonPrimitive(401) },
                        repo.authoritativeThreads
                            .current("host", "c")
                            .items
                            .filter { it.shown && it.parent == 90_000L }
                            .map { it.raw },
                    )
                    assertEquals(0, cache.authoritativeFullReads)
                    assertTrue(fixture.requests.none { it.type in setOf("thread_page", "thread_catch_up") })
                }
                val fresh = requireNotNull(disk().readAuthoritativeThread("host", "c"))
                assertEquals(
                    (children.filterNot { it["id"] == JsonPrimitive(401) } + moved + active + rankInputs).toSet(),
                    fresh.items.toSet(),
                )
                assertTrue(401L in fresh.retirements)
                val reopened = disk()
                destination(reopened) { _, vm, repo ->
                    vm.state.first { it.authoritativeThread?.rows?.size == 201 }
                    vm.onDemandOlderHistory()
                    vm.state.first { it.authoritativeThread?.rows?.size == 401 }
                    assertEquals(
                        400,
                        repo.authoritativeThreads
                            .current("host", "c")
                            .items
                            .count { it.shown && it.parent == 90_000L },
                    )
                    assertFalse(
                        repo.authoritativeThreads
                            .current("host", "c")
                            .items
                            .any { it.id == 401L },
                    )
                }
            }
        }

    @Test fun retiredBoundaryKeepsItsOlderComponentAcrossStandaloneSuccessors() =
        runTest {
            val parent = JsonObject(fact(90_000, false) + mapOf("kind" to JsonPrimitive("agent"), "order" to JsonPrimitive(10_000)))
            val children = (1..600).map { JsonObject(fact(it) + ("parent" to JsonPrimitive(90_000))) }
            val standalone = (11_000..11_198).map { JsonObject(fact(it) + ("order" to JsonPrimitive(it))) }
            val original =
                SavedAuthoritativeThread(
                    "e",
                    children + parent + standalone,
                    100_000,
                    99_000,
                    SavedThreadCoverage(11_000, 11_198, 11_000, true),
                )
            disk().writeAuthoritativeThread("host", "c", original).getOrThrow()
            val cache = disk()
            val moved = JsonObject(parent + mapOf("rev" to JsonPrimitive(110_000), "order" to JsonPrimitive(20_000)))
            val committed = CompletableDeferred<Unit>()
            destination(trackingWrite(cache, moved, committed)) { fixture, vm, repo ->
                vm.state.first { it.authoritativeThread?.rows?.size == 200 }
                assertEquals(original.coverage?.toDomain(), repo.authoritativeThreads.current("host", "c").coverage)
                repo.removeAuthoritativeFact(requireNotNull(repo.authoritativeThreads.captureRemoval("host", "c", 600))).getOrThrow()
                vm.state.first { it.authoritativeThread?.rows?.size == 199 }
                repo.authoritativeThreads.applyBatch("host", "c", "e", 110_000, listOf(ThreadItemDto.decodeReplacement(moved)), 0)
                committed.await()
                val requests = fixture.requests.toList()
                repeat(3) { page ->
                    vm.onDemandOlderHistory()
                    vm.state.first { it.historyTail == ThreadHistoryTail.Loading }
                    vm.state.first { it.historyTail != ThreadHistoryTail.Loading }
                    val snapshot = repo.authoritativeThreads.current("host", "c")
                    val selected = requireNotNull(snapshot.openingRows)
                    val expected = children.dropLast(1).takeLast(minOf(599, (page + 1) * 200))
                    assertEquals(expected, snapshot.items.filter { it.parent == 90_000L && (it.id to false) in selected }.map { it.raw })
                    vm.state.first { it.authoritativeThread?.rows?.size == expected.size + standalone.size }
                    assertEquals(
                        (standalone + expected).map { it["id"].toString().toLong() to "Saved exact ${it["id"]}" },
                        vm.state.value.authoritativeThread?.items?.filterIsInstance<ThreadItem.MessageItem>()?.map {
                            it.message.id
                                .substringAfterLast(':')
                                .toLong() to it.message.content
                        },
                    )
                    // Explicit retirement clears certification; hydration must not revive the original one.
                    assertEquals(null, snapshot.coverage)
                    assertEquals(0L, snapshot.checkpoint)
                    val counts = cache.authoritativeReadCounts
                    println("reader_mixed_component page=${page + 1} counts=$counts")
                    assertTrue(counts.itemDecodes <= 202)
                    assertTrue(counts.ordinalReads <= 1000)
                    assertTrue(counts.queries <= 15)
                    assertEquals(0, cache.authoritativeFullReads)
                    advanceTimeBy(1000)
                    runCurrent()
                    assertEquals(requests, fixture.requests)
                    assertEquals(
                        expected.size + standalone.size,
                        vm.state.value.authoritativeThread
                            ?.rows
                            ?.size,
                    )
                }
            }
            val fresh = requireNotNull(disk().readAuthoritativeThread("host", "c"))
            assertEquals((children.dropLast(1) + moved + standalone).toSet(), fresh.items.toSet())
            assertTrue(600L in fresh.retirements)
            assertEquals(null, fresh.coverage)
            assertEquals(0L, fresh.checkpoint)
            destination(disk()) { fixture, vm, _ ->
                vm.state.first { it.authoritativeThread?.rows?.size == 200 }
                for (count in listOf(400, 600, 798)) {
                    vm.onDemandOlderHistory()
                    vm.state.first { it.authoritativeThread?.rows?.size == count }
                }
                assertEquals(
                    (standalone + children.dropLast(1)).map { it["id"].toString().toLong() },
                    vm.state.value.authoritativeThread?.items?.filterIsInstance<ThreadItem.MessageItem>()?.map {
                        it.message.id
                            .substringAfterLast(':')
                            .toLong()
                    },
                )
                assertTrue(fixture.requests.none { it.type in setOf("thread_page", "thread_catch_up") })
            }
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
            assertEquals(original.copy(checkpoint = 1000), disk().readAuthoritativeThread("host", "c"))
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
