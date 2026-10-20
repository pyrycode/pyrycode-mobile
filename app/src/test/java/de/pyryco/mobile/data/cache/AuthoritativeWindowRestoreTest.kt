package de.pyryco.mobile.data.cache

import android.database.sqlite.SQLiteDatabase
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayTransport
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.repository.CachingConversationRepository
import de.pyryco.mobile.data.repository.RelayRepositoryCoordinator
import de.pyryco.mobile.data.repository.StableConversationRepository
import de.pyryco.mobile.di.SavedAuthoritativeDestination
import de.pyryco.mobile.ui.conversations.thread.ThreadViewModel
import de.pyryco.mobile.ui.conversations.thread.listKey
import de.pyryco.mobile.ui.conversations.thread.projectAuthoritativeThread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AuthoritativeWindowRestoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private var database: ConversationCacheDatabase? = null

    private fun open(): AuthoritativeRoomStorage {
        database?.close()
        val db =
            Room
                .databaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    ConversationCacheDatabase::class.java,
                    File(temporary.root, "window.db").absolutePath,
                ).build()
        database = db
        return AuthoritativeRoomStorage(db)
    }

    @After fun close() {
        database?.close()
    }

    private fun fact(
        id: Int,
        shown: Boolean = true,
        active: Boolean = false,
        parent: Int? = null,
    ): JsonObject =
        MobileJson
            .parseToJsonElement(
                """{
                "id":$id,"rev":$id,"order":$id,"kind":"assistant_message",
                "status":"done","active":$active,"shown":$shown,"parent":$parent,"summary":"",
                "content":{"text":"Saved $id."},"future":{"clear":null}
            }""",
            ).jsonObject

    private suspend fun TestScope.destination(
        cache: ConversationCache,
        online: Boolean = false,
        block: suspend (SavedAuthoritativeDestination, ThreadViewModel, CachingConversationRepository) -> Unit,
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

    private fun migrating() = MigratingConversationCache(File(temporary.root, "files"), requireNotNull(database))

    private fun finishNewest(
        fixture: SavedAuthoritativeDestination,
        version: Int = 1000,
    ) {
        val request = fixture.requests.last { it.type in setOf("thread_page", "thread_catch_up") }
        val versionField = request.payload.jsonObject["version"]
        val coverage =
            if (versionField != null && versionField != JsonPrimitive(0)) {
                "\"from_version\":$versionField"
            } else {
                "\"lower_order\":250,\"upper_order\":301,\"continuation\":250,\"older_exists\":true"
            }
        fixture.reply(
            request,
            """{"conversation_id":"c","epoch":"e","version":$version,"items":[],"final":true,
            $coverage}""",
        )
    }

    private fun agent(
        id: Int,
        order: Int,
        ended: Int,
        parent: Int? = null,
        shown: Boolean = false,
    ): JsonObject =
        MobileJson
            .parseToJsonElement(
                """{"id":$id,"rev":$id,"order":$order,"ended_order":$ended,
            "kind":"agent","status":"done","active":false,"shown":$shown,"parent":$parent,
            "summary":"agent","content":{"name":"Agent","task_id":"task-$id","task_link":{}}}""",
            ).jsonObject

    @Test fun placementInvariant_settledNestedAgentsAndLaunchesUseRenderedRows() =
        runTest {
            val ordinary = (10..18_009).map { fact(it) }
            val facts =
                listOf(
                    agent(1, 1, 20_000),
                    agent(2, 2, 19_000, 1, true),
                    fact(3, parent = 2),
                    fact(4, parent = 1),
                    agent(5, 5, 21_000, shown = true),
                    fact(6, parent = 5),
                ) + ordinary
            val original = SavedAuthoritativeThread("e", facts, 30_000, 30_000, null).retained()
            open().writeAuthoritativeThread("host", "c", original).getOrThrow()
            val cold = open()
            val window = requireNotNull(cold.newestDisplayWindow("host", "c", 200))
            val saved = window.saved

            fun rows(
                thread: SavedAuthoritativeThread,
                selected: Set<Pair<Long, Boolean>>? = null,
            ): List<String> {
                val snapshot = thread.toSnapshot().copy(openingRows = selected)
                return projectAuthoritativeThread("host", "c", snapshot).rows.map { it.listKey(0) }
            }
            assertEquals(rows(original).takeLast(200), rows(saved, window.rows))
            assertTrue(saved.items.any { it["id"] == JsonPrimitive(4) })
            assertTrue(cold.lastReadCounts.itemDecodes <= 206)
            assertEquals(0, cold.fullReads)
            val moved = JsonObject(agent(1, 1, 22_000) + ("rev" to JsonPrimitive(30_001)))
            cold
                .upsertAuthoritativeThread(
                    "host",
                    "c",
                    original.copy(items = listOf(moved), appliedVersion = 30_001, checkpoint = 30_001),
                    false,
                    original,
                    emptyMap(),
                ).getOrThrow()
            val updated =
                original.copy(
                    items = original.items.map { if (it["id"] == JsonPrimitive(1)) moved else it },
                    appliedVersion = 30_001,
                    checkpoint = 30_001,
                )
            val reopened = requireNotNull(open().newestDisplayWindow("host", "c", 200))
            assertEquals(rows(updated).takeLast(200), rows(reopened.saved, reopened.rows))
        }

    @Test fun placementInvariant_launchAndContentEachConsumeOneRow() =
        runTest {
            val coincident = SavedAuthoritativeThread("e", (1..300).map { agent(it, it, it, shown = true) }, 1000, 1000, null)
            open().writeAuthoritativeThread("host", "coincident", coincident).getOrThrow()
            val coincidentDisk = open()
            val coincidentWindow = requireNotNull(coincidentDisk.newestDisplayWindow("host", "coincident", 200))
            val coincidentSnapshot = coincidentWindow.saved.toSnapshot().copy(openingRows = coincidentWindow.rows)
            val coincidentRows = projectAuthoritativeThread("host", "coincident", coincidentSnapshot).rows.map { it.listKey(0) }
            val allCoincidentRows = projectAuthoritativeThread("host", "coincident", coincident.toSnapshot()).rows.map { it.listKey(0) }
            assertEquals(allCoincidentRows.takeLast(200), coincidentRows)
            assertEquals(100, coincidentWindow.saved.items.size)
            assertEquals(400, coincidentDisk.lastReadCounts.itemReads)
            assertEquals(100, coincidentDisk.lastReadCounts.itemDecodes)
            val original = SavedAuthoritativeThread("e", (1..300).map { agent(it, it, 1000 + it, shown = true) }, 5000, 5000, null)
            open().writeAuthoritativeThread("host", "c", original).getOrThrow()
            val cold = open()
            val window = requireNotNull(cold.newestDisplayWindow("host", "c", 200))
            val saved = window.saved
            val expected = projectAuthoritativeThread("host", "c", original.toSnapshot()).rows.takeLast(200).map { it.listKey(0) }
            val snapshot = saved.toSnapshot().copy(openingRows = window.rows)
            val rows = projectAuthoritativeThread("host", "c", snapshot).rows.map { it.listKey(0) }
            assertEquals(expected, rows)
            assertEquals(200, saved.items.size)
            assertEquals(400, cold.lastReadCounts.itemReads)
            assertEquals(200, cold.lastReadCounts.itemDecodes)
            destination(migrating()) { _, vm, repo ->
                val opened = vm.state.first { it.authoritativeThread?.rows?.size == 200 }
                val presentation = requireNotNull(opened.authoritativeThread)
                assertEquals(expected, presentation.rows.map { it.listKey(0) })
                assertEquals(200, presentation.shownRowRevisions.size)
                val revised = JsonObject(agent(300, 300, 1300, shown = true) + ("rev" to JsonPrimitive(6000)))
                repo.authoritativeThreads.applyBatch("host", "c", "e", 6000, listOf(ThreadItemDto.decodeReplacement(revised)), 0)
                vm.state.first { it.authoritativeThread?.rows?.size == 201 }
                requireNotNull(repo.negotiatedThread).older("c")
                val hydrated = vm.state.first { it.authoritativeThread?.rows?.size == 600 }
                assertNull(requireNotNull(hydrated.authoritativeThread).sourceSnapshot.openingRows)
            }
        }

    @Test fun placementInvariant_unorderedParentsKeepHeldChildTies() =
        runTest {
            val original =
                SavedAuthoritativeThread(
                    "e",
                    listOf(
                        agent(1, 0, 0),
                        agent(2, 0, 0),
                        JsonObject(fact(4, parent = 2) + ("order" to JsonPrimitive(10))),
                        JsonObject(fact(3, parent = 1) + ("order" to JsonPrimitive(10))),
                        fact(10),
                    ),
                    1000,
                    1000,
                    null,
                ).retained()
            open().writeAuthoritativeThread("host", "c", original).getOrThrow()
            val expected = projectAuthoritativeThread("host", "c", original.toSnapshot()).rows.map { it.listKey(0) }
            for (size in 1..3) {
                val selected = requireNotNull(open().newestDisplayWindow("host", "c", size))
                val snapshot = selected.saved.toSnapshot().copy(openingRows = selected.rows)
                val rows = projectAuthoritativeThread("host", "c", snapshot).rows.map { it.listKey(0) }
                assertEquals(expected.takeLast(size), rows)
            }
        }

    @Test fun retirementInvariant_openingReadsOnlyRelevantEvidence() =
        runTest {
            val original =
                SavedAuthoritativeThread(
                    "e",
                    (1..18_000).map { fact(it) },
                    50_000,
                    0,
                    null,
                    retirements = (20_000L..39_999L).associateWith { it },
                )
            open().writeAuthoritativeThread("host", "c", original).getOrThrow()
            val cold = open()
            val saved = requireNotNull(cold.newestDisplayWindow("host", "c", 200)).saved
            assertEquals(original.items.takeLast(200), saved.items)
            assertTrue(saved.retirements.isEmpty())
            assertEquals(1, cold.lastReadCounts.evidenceReads)
            assertEquals(200, cold.lastReadCounts.itemDecodes)
            assertEquals(original, open().readAuthoritativeThread("host", "c"))
        }

    @Test fun retirementInvariant_liveOverlapLoadsItsFenceWithoutHistoricalEvidence() =
        runTest {
            val original =
                SavedAuthoritativeThread(
                    "e",
                    (1..18_000).map { fact(it) },
                    50_000,
                    0,
                    null,
                    retirements = (20_000L..39_999L).associateWith { it },
                )
            open().writeAuthoritativeThread("host", "c", original).getOrThrow()
            open()
            val disk = migrating()
            val captured = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val held =
                object : ConversationCache by disk, AuthoritativeWindowCache by disk, AuthoritativeUpsertCache by disk {
                    override suspend fun newestAuthoritativeWindow(
                        serverId: String,
                        conversationId: String,
                        size: Int,
                    ) = disk.newestAuthoritativeWindow(serverId, conversationId, size).also {
                        captured.complete(Unit)
                        release.await()
                    }
                }
            destination(held) { fixture, vm, repo ->
                captured.await()
                fixture.coordinator.authoritativeThreads.applyBatch(
                    "host",
                    "c",
                    "e",
                    50_000,
                    listOf(ThreadItemDto.decodeReplacement(fact(20_000))),
                    0,
                )
                release.complete(Unit)
                vm.state.first { it.authoritativeThread?.items?.size == 200 }
                val snapshot = repo.authoritativeThreads.current("host", "c")
                assertEquals(original.items.takeLast(200), snapshot.items.map { it.raw })
                assertEquals(mapOf(20_000L to 20_000L), snapshot.retirements)
                assertEquals(3, disk.authoritativeReadCounts.evidenceReads)
                assertEquals(200, disk.authoritativeReadCounts.itemDecodes)
                repo.authoritativeThreads.applyBatch(
                    "host",
                    "c",
                    "e",
                    50_000,
                    listOf(ThreadItemDto.decodeReplacement(fact(20_001))),
                    0,
                )
                vm.state.first {
                    it.authoritativeThread
                        ?.sourceSnapshot
                        ?.retirements
                        ?.containsKey(20_001L) == true
                }
                assertEquals(
                    original.items.takeLast(200),
                    repo.authoritativeThreads
                        .current("host", "c")
                        .items
                        .map { it.raw },
                )
                val current = repo.authoritativeThreads.current("host", "c")
                assertEquals(mapOf(20_000L to 20_000L, 20_001L to 20_001L), current.retirements)
                assertEquals(0, disk.authoritativeFullReads)
            }
            assertEquals(original, open().readAuthoritativeThread("host", "c"))
        }

    @Test fun demandInvariant_failedOrNullHydrationForwardsToDaemon() =
        runTest {
            for (failed in listOf(false, true)) {
                open()
                    .writeAuthoritativeThread(
                        "host",
                        "c",
                        SavedAuthoritativeThread(
                            "e",
                            (1..300).map { fact(it) },
                            1000,
                            900,
                            SavedThreadCoverage(250, 300, 250, true),
                        ),
                    ).getOrThrow()
                open()
                val disk = migrating()
                val unavailable =
                    object : ConversationCache by disk, AuthoritativeWindowCache by disk, AuthoritativeUpsertCache by disk {
                        override suspend fun readAuthoritativeThread(
                            serverId: String,
                            conversationId: String,
                        ): SavedAuthoritativeThread? {
                            if (failed) throw java.io.IOException("unavailable")
                            return null
                        }
                    }
                destination(unavailable, online = true) { fixture, vm, repo ->
                    vm.state.first { it.authoritativeThread?.items?.size == 200 }
                    finishNewest(fixture)
                    runCurrent()
                    val before = fixture.requests.size
                    requireNotNull(repo.negotiatedThread).older("c")
                    runCurrent()
                    assertTrue(fixture.requests.drop(before).any { it.type in setOf("thread_page", "thread_catch_up") })
                }
            }
        }

    @Test fun demandInvariant_resetThenOlderForwardsToDaemon() =
        runTest {
            val original = SavedAuthoritativeThread("e", (1..300).map { fact(it) }, 1000, 900, null)
            open().writeAuthoritativeThread("host", "c", original).getOrThrow()
            open()
            destination(migrating(), online = true) { fixture, vm, repo ->
                vm.state.first { it.authoritativeThread?.items?.size == 200 }
                fixture.resetNewest()
                runCurrent()
                assertTrue(repo.authoritativeThreads.current("host", "c").generation > 0)
                assertNull(repo.authoritativeThreads.current("host", "c").openingRows)
                finishNewest(fixture)
                runCurrent()
                val before = fixture.requests.size
                requireNotNull(repo.negotiatedThread).older("c")
                runCurrent()
                assertTrue(fixture.requests.drop(before).any { it.type in setOf("thread_page", "thread_catch_up") })
            }
        }

    @Test fun openingInvariant_coldWindowIsBoundedAndExact() =
        runTest {
            val original =
                SavedAuthoritativeThread("e", (1..18_000).map { fact(it) }, 20_000, 19_000, SavedThreadCoverage(1, 18_000, 1, true))
            open().writeAuthoritativeThread("host", "c", original).getOrThrow()
            val cold = open()
            val window = requireNotNull(cold.newestDisplayWindow("host", "c", 200))
            assertEquals(original.items.takeLast(200), window.saved.items)
            assertEquals(original.checkpoint, window.saved.checkpoint)
            assertEquals(original.coverage, window.saved.coverage)
            assertEquals(200, cold.lastReadCounts.itemReads)
            assertEquals(200, cold.lastReadCounts.itemDecodes)
            assertEquals(0, cold.fullReads)
            assertEquals(original, open().readAuthoritativeThread("host", "c"))
        }

    @Test fun openingInvariant_emptyAndSmallWindowsStayExact() =
        runTest {
            for (size in listOf(0, 1, 17)) {
                val original = SavedAuthoritativeThread("e", (1..size).map { fact(it) }, 1000, 1000, null)
                open().writeAuthoritativeThread("host", "c", original).getOrThrow()
                val cold = open()
                assertEquals(original, cold.newestDisplayWindow("host", "c", 200)?.saved)
                assertEquals(size, cold.lastReadCounts.itemReads)
                assertEquals(size, cold.lastReadCounts.itemDecodes)
                assertNull(cold.newestDisplayWindow("Host", "c", 200))
                assertNull(cold.newestDisplayWindow("host", "C", 200))
            }
        }

    @Test fun closureInvariant_hiddenFactsDoNotConsumeDisplayWindow() =
        runTest {
            val items =
                (1..18_000).map { fact(it, parent = if (it == 18_000) 2 else null) } +
                    (18_001..19_000).map { fact(it, shown = false) } + fact(19_001, shown = false, active = true, parent = 1)
            val original = SavedAuthoritativeThread("e", items, 20_000, 20_000, null)
            open().writeAuthoritativeThread("host", "c", original).getOrThrow()
            val cold = open()
            val window = requireNotNull(cold.newestDisplayWindow("host", "c", 200))
            assertEquals(listOf(fact(1), fact(2)) + items.subList(17_800, 18_000) + items.last(), window.saved.items)
            assertEquals(203, cold.lastReadCounts.itemReads)
            assertEquals(203, cold.lastReadCounts.itemDecodes)
            assertTrue(cold.lastReadCounts.ordinalReads <= 1)
            assertNotNull(window)
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun demandInvariant_onlyReaderHydratesOlderSavedFacts() =
        runTest {
            val original =
                SavedAuthoritativeThread("e", (1..18_000).map { fact(it) }, 20_000, 19_000, SavedThreadCoverage(1, 18_000, 1, true))
            open().writeAuthoritativeThread("host", "c", original).getOrThrow()
            open()
            val disk = MigratingConversationCache(File(temporary.root, "files"), requireNotNull(database))
            val dispatcher = StandardTestDispatcher(testScheduler)
            val coordinator =
                RelayRepositoryCoordinator(
                    MutableStateFlow<RelayTransport?>(null),
                    MutableStateFlow(RelayLinkStatus.Offline),
                    { error("offline must not construct a pump") },
                    dispatcher = dispatcher,
                    authoritativeHostId = "host",
                )
            val stable = StableConversationRepository(coordinator.currentRepository, negotiatedThread = coordinator.negotiatedThread)
            val repo = CachingConversationRepository(stable, disk, "host", processingDispatcher = dispatcher)
            val route = requireNotNull(repo.negotiatedThread)
            val opened = CompletableDeferred<Unit>()
            val hydrated = CompletableDeferred<Unit>()
            val collection =
                backgroundScope.launch {
                    route.observe("c").collect {
                        if (it.items.isNotEmpty()) opened.complete(Unit)
                        if (it.items.size == 18_000) hydrated.complete(Unit)
                    }
                }
            try {
                opened.await()
                runCurrent()
                assertEquals(
                    200,
                    repo.authoritativeThreads
                        .current("host", "c")
                        .items.size,
                )
                assertEquals(0, disk.authoritativeFullReads)
                assertTrue(route.older("c"))
                hydrated.await()
                collection.cancelAndJoin()
                assertEquals(original, open().readAuthoritativeThread("host", "c"))
            } finally {
                collection.cancelAndJoin()
                coordinator.close()
            }
        }

    @Test fun demandInvariant_networkCannotBlockOrExpandSavedOpening() =
        runTest {
            val displayed =
                (1..18_000).map { id ->
                    JsonObject(fact(id, parent = if (id == 18_000) 2 else null) + ("order" to JsonPrimitive(id * 2)))
                }
            val hidden =
                (18_001..19_000).map { id ->
                    JsonObject(fact(id, shown = false) + ("order" to JsonPrimitive(36_000 + id - 18_000)))
                }
            val active = JsonObject(fact(19_001, shown = false, active = true, parent = 1) + ("order" to JsonPrimitive(1)))
            val expected = listOf(active, displayed[0], displayed[1]) + displayed.takeLast(200)
            val original =
                SavedAuthoritativeThread(
                    "e",
                    displayed + hidden + active,
                    50_000,
                    49_000,
                    SavedThreadCoverage(35_602, 36_000, 35_602, true),
                ).retained()
            open().writeAuthoritativeThread("host", "c", original).getOrThrow()
            for (online in listOf(false, true)) {
                repeat(2) {
                    open()
                    val disk = migrating()
                    destination(disk, online) { fixture, vm, _ ->
                        val shown = vm.state.first { it.authoritativeThread?.items?.isNotEmpty() == true }
                        assertEquals(
                            expected,
                            requireNotNull(shown.authoritativeThread).sourceSnapshot.items.map { it.raw },
                        )
                        assertEquals(200, shown.authoritativeThread?.items?.size)
                        assertEquals(0, disk.authoritativeFullReads)
                        assertEquals(203, disk.authoritativeReadCounts.itemReads)
                        assertEquals(203, disk.authoritativeReadCounts.itemDecodes)
                        assertEquals(7, disk.authoritativeReadCounts.queries)
                        assertEquals(if (online) 1 else 0, fixture.requests.count { it.type in setOf("thread_catch_up", "thread_page") })
                    }
                    assertEquals(original, disk.readAuthoritativeThread("host", "c"))
                }
            }
        }

    @Test fun identityInvariant_overlapKeepsNewestRawFactsAndOrder() =
        runTest {
            val parent = agent(1, 1, 1)
            val outside = agent(2, 2, 2)
            val original = SavedAuthoritativeThread("e", listOf(parent, outside) + (3..18_000).map { fact(it) }, 20_000, 19_000, null)
            open().writeAuthoritativeThread("host", "c", original).getOrThrow()
            open()
            val disk = migrating()
            val captured = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val held =
                object : ConversationCache by disk, AuthoritativeWindowCache by disk, AuthoritativeUpsertCache by disk {
                    override suspend fun newestAuthoritativeWindow(
                        serverId: String,
                        conversationId: String,
                        size: Int,
                    ): AuthoritativeThreadWindow? =
                        disk.newestAuthoritativeWindow(serverId, conversationId, size).also {
                            captured.complete(Unit)
                            release.await()
                        }
                }
            val revised =
                JsonObject(
                    fact(18_000) +
                        mapOf(
                            "rev" to JsonPrimitive(30_000),
                            "parent" to JsonPrimitive(1),
                            "future" to JsonNull,
                        ),
                )
            destination(held) { fixture, vm, repo ->
                captured.await()
                fixture.connect()
                runCurrent()
                fixture.disconnect()
                runCurrent()
                fixture.connect()
                runCurrent()
                val store = fixture.coordinator.authoritativeThreads
                store.applyBatch("host", "c", "e", 30_000, listOf(ThreadItemDto.decodeReplacement(revised)), 0)
                release.complete(Unit)
                vm.state.first { it.authoritativeThread?.items?.size == 200 }
                val snapshot = repo.authoritativeThreads.current("host", "c")
                assertEquals(listOf(parent) + original.items.takeLast(200).dropLast(1) + revised, snapshot.items.map { it.raw })
                assertEquals(listOf(1), projectAuthoritativeThread("host", "c", snapshot).depths.values.toList())
                store.applyBatch("host", "c", "e", 30_000, listOf(ThreadItemDto.decodeReplacement(fact(18_000))), 0)
                assertEquals(
                    revised,
                    store
                        .current("host", "c")
                        .items
                        .last()
                        .raw,
                )
                val reparented = JsonObject(revised + mapOf("rev" to JsonPrimitive(40_000), "parent" to JsonPrimitive(2)))
                store.applyBatch("host", "c", "e", 40_000, listOf(ThreadItemDto.decodeReplacement(reparented)), 0)
                vm.state.first {
                    it.authoritativeThread
                        ?.sourceSnapshot
                        ?.items
                        ?.any { item -> item.id == 2L } == true
                }
                assertEquals(
                    reparented,
                    store
                        .current("host", "c")
                        .items
                        .last()
                        .raw,
                )
                val projected = projectAuthoritativeThread("host", "c", store.current("host", "c"))
                assertEquals(listOf(1), projected.depths.values.toList())
            }
            val finalRevision = JsonObject(revised + mapOf("rev" to JsonPrimitive(40_000), "parent" to JsonPrimitive(2)))
            assertEquals(
                original.copy(items = original.items.dropLast(1) + finalRevision, appliedVersion = 40_000),
                open().readAuthoritativeThread("host", "c"),
            )
        }

    @Test fun generationInvariant_resetFencesHeldRestoration() =
        runTest {
            val original = SavedAuthoritativeThread("e", (1..300).map { fact(it) }, 1000, 1000, null)
            open().writeAuthoritativeThread("host", "c", original).getOrThrow()
            open()
            val disk = migrating()
            val captured = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val held =
                object : ConversationCache by disk, AuthoritativeWindowCache by disk, AuthoritativeUpsertCache by disk {
                    override suspend fun newestAuthoritativeWindow(
                        serverId: String,
                        conversationId: String,
                        size: Int,
                    ) = disk.newestAuthoritativeWindow(serverId, conversationId, size).also {
                        captured.complete(Unit)
                        release.await()
                    }
                }
            destination(held) { fixture, vm, _ ->
                captured.await()
                val store = fixture.coordinator.authoritativeThreads
                store.reset("host", "c", "e")
                store.reset("host", "c", "e")
                store.applyBatch(
                    "host",
                    "c",
                    "e",
                    1,
                    listOf(ThreadItemDto.decodeReplacement(fact(1))),
                    store.current("host", "c").generation,
                )
                release.complete(Unit)
                vm.state.first { it.authoritativeThread?.items?.size == 1 }
                assertEquals(listOf(fact(1)), store.current("host", "c").items.map { it.raw })
            }
            assertEquals(listOf(fact(1)), open().readAuthoritativeThread("host", "c")?.items)
        }

    @Test fun lifetimeInvariant_exitCancelsPendingHydration() =
        runTest {
            val original = SavedAuthoritativeThread("e", (1..300).map { fact(it) }, 1000, 1000, null)
            open().writeAuthoritativeThread("host", "c", original).getOrThrow()
            open()
            val disk = migrating()
            val captured = CompletableDeferred<Unit>()
            val held =
                object : ConversationCache by disk, AuthoritativeWindowCache by disk, AuthoritativeUpsertCache by disk {
                    override suspend fun newestAuthoritativeWindow(
                        serverId: String,
                        conversationId: String,
                        size: Int,
                    ) = disk.newestAuthoritativeWindow(serverId, conversationId, size).also {
                        captured.complete(Unit)
                        CompletableDeferred<Unit>().await()
                    }
                }
            destination(held) { _, _, repo ->
                captured.await()
                assertTrue(
                    repo.authoritativeThreads
                        .current("host", "c")
                        .items
                        .isEmpty(),
                )
            }
            assertEquals(original, open().readAuthoritativeThread("host", "c"))
        }

    @Test fun certificationInvariant_hydrationNeverChangesCommittedCertificate() =
        runTest {
            val original = SavedAuthoritativeThread("e", (1..300).map { fact(it) }, 1000, 900, SavedThreadCoverage(250, 300, 250, true))
            open().writeAuthoritativeThread("host", "c", original).getOrThrow()
            open()
            val disk = migrating()
            destination(disk) { _, vm, repo ->
                vm.state.first { it.authoritativeThread?.items?.size == 200 }
                repo.removeAuthoritativeFact(requireNotNull(repo.authoritativeThreads.captureRemoval("host", "c", 300))).getOrThrow()
                vm.onDemandOlderHistory()
                vm.state.first { it.authoritativeThread?.items?.size == 299 }
                assertFalse(
                    repo.authoritativeThreads
                        .current("host", "c")
                        .items
                        .any { it.id == 300L },
                )
                assertEquals(0L, disk.readAuthoritativeThread("host", "c")?.checkpoint)
            }
            val reopened = requireNotNull(open().readAuthoritativeThread("host", "c"))
            assertEquals(original.items.dropLast(1), reopened.items)
            assertEquals(0L, reopened.checkpoint)
            assertNull(reopened.coverage)
        }

    @Test fun retirementInvariant_freshReopenCannotResurrectOrLoseOtherHost() =
        runTest {
            for (hostDeletion in listOf(false, true)) {
                val original = SavedAuthoritativeThread("e", (1..300).map { fact(it) }, 1000, 1000, null)
                val storage = open()
                storage.writeAuthoritativeThread("host", "c", original).getOrThrow()
                storage.writeAuthoritativeThread("other", "c", original).getOrThrow()
                val hold = AtomicBoolean(false)
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val disk =
                    MigratingConversationCache(File(temporary.root, "files"), requireNotNull(database), Dispatchers.IO) {
                        if (hold.compareAndSet(true, false)) {
                            entered.complete(Unit)
                            release.await()
                        }
                    }
                destination(disk) { _, vm, repo ->
                    vm.state.first { it.authoritativeThread?.items?.size == 200 }
                    hold.set(true)
                    val changed = JsonObject(fact(300) + ("rev" to JsonPrimitive(2000)))
                    repo.authoritativeThreads.applyBatch("host", "c", "e", 2000, listOf(ThreadItemDto.decodeReplacement(changed)), 0)
                    runCurrent()
                    advanceTimeBy(101)
                    entered.await()
                    val lifetime = disk.authoritativeLifetime("host", "c")
                    val deletion =
                        backgroundScope.launch {
                            if (hostDeletion) disk.removeHost("host").getOrThrow() else disk.removeConversation("host", "c").getOrThrow()
                        }
                    runCurrent()
                    assertFalse(deletion.isCompleted)
                    assertFalse(lifetime == disk.authoritativeLifetime("host", "c"))
                    release.complete(Unit)
                    deletion.join()
                }
                val fresh = open()
                assertNull(fresh.readAuthoritativeThread("host", "c"))
                assertEquals(original, fresh.readAuthoritativeThread("other", "c"))
            }
        }

    @Test fun migrationInvariant_existingFactsAndIndexesSurviveUpgrade() =
        runTest {
            val fixtures = listOf(agent(1, 1, 10), fact(2, active = true, parent = 1))
            val schema =
                MobileJson
                    .parseToJsonElement(
                        File("src/main/roomSchemas/de.pyryco.mobile.data.cache.ConversationCacheDatabase/5.json").readText(),
                    ).jsonObject
                    .getValue("database")
                    .jsonObject
            SQLiteDatabase.openOrCreateDatabase(File(temporary.root, "window.db"), null).use { db ->
                schema.getValue("entities").jsonArray.forEach { entry ->
                    val entity = entry.jsonObject
                    val table = entity.getValue("tableName").jsonPrimitive.content
                    db.execSQL(
                        entity
                            .getValue("createSql")
                            .jsonPrimitive.content
                            .replace("\${TABLE_NAME}", table),
                    )
                    entity["indices"]?.jsonArray?.forEach {
                        db.execSQL(
                            it.jsonObject
                                .getValue("createSql")
                                .jsonPrimitive.content
                                .replace("\${TABLE_NAME}", table),
                        )
                    }
                }
                schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                db.execSQL("INSERT INTO authoritative_headers VALUES ('host','c','e','g',10,10,NULL,2,'')")
                for ((index, item) in fixtures.withIndex()) {
                    db.execSQL(
                        "INSERT INTO authoritative_items VALUES ('host','c','e',?,?,?,?)",
                        arrayOf(index + 1, index, index + 1, item.toString()),
                    )
                }
                db.version = 5
            }
            val cold = open()
            assertEquals(
                fixtures,
                cold.newestDisplayWindow("host", "c", 200)?.saved?.items,
            )
            for (column in listOf("displayKey", "launchKey")) {
                val plans =
                    requireNotNull(database).openHelper.readableDatabase.query(
                        "EXPLAIN QUERY PLAN SELECT * FROM authoritative_items " +
                            "WHERE host='host' AND conversation='c' AND epoch='e' AND $column>'' " +
                            "ORDER BY $column DESC LIMIT 200",
                    )
                plans.use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertTrue(cursor.getString(3).contains("$column>?"))
                }
            }
        }
}
