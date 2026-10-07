package de.pyryco.mobile.data.repository

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import de.pyryco.mobile.data.cache.ConversationCache
import de.pyryco.mobile.data.cache.FileConversationCache
import de.pyryco.mobile.data.cache.MAX_CACHED_THREAD_ROWS
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.ui.conversations.thread.ComposerDraftStore
import de.pyryco.mobile.ui.conversations.thread.ThreadHistoryDemand
import de.pyryco.mobile.ui.conversations.thread.ThreadViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
import kotlin.time.Duration.Companion.minutes

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HistoryCacheReworkTest {
    @get:Rule val tmp = TemporaryFolder()
    private val oldSink = RelayLog.sink

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun teardown() {
        Dispatchers.resetMain()
        RelayLog.sink = oldSink
    }

    private fun disk() = FileConversationCache(tmp.root, UnconfinedTestDispatcher())

    private fun page(
        vararg ids: Long,
        cursor: String = "older",
    ) = HistoryPage(
        ids.reversed().map { id ->
            HistoryEntry(
                id,
                "send_message",
                MobileJson.parseToJsonElement(
                    """{"conversation_id":"c","message_id":"m$id","text":"$id"}""",
                ),
                Instant.fromEpochSeconds(1),
            )
        },
        cursor,
        false,
    )

    private fun rows(page: HistoryPage) = reduceHistoryPage(page.entries, true)

    private class Live :
        ConversationRepository by FakeConversationRepository(),
        ThreadSnapshotSource {
        var projection = ThreadProjection()
        val asks = mutableListOf<String>()
        var answer: (String) -> HistoryPage = { error("unexpected ask") }

        override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> = projection.observe(conversationId)

        override fun observeThreadSnapshot(conversationId: String): Flow<ThreadSnapshot> = projection.observeSnapshot(conversationId)

        override suspend fun delete(conversationId: String) {}

        override suspend fun requestHistory(
            conversationId: String,
            cursor: String,
            limit: Int,
        ): HistoryPage {
            asks += cursor
            return answer(cursor).also { projection.mergeHistoryPage(conversationId, it, true) }
        }
    }

    @Test fun restoredOlderGapWithoutSharedRows_staysChronologicalAfterReconnectAndFreshRestore() =
        runTest {
            val old = page(1, 2, cursor = "oldest")
            val upper = page(10, 11, cursor = "nine")
            val held = rows(old) + rows(upper)
            val coverage = HistoryCoverage().received(old).received(upper).boundTo(held)
            disk().writeThread("h", "c", held)
            disk().writeHistoryPosition("h", "c", HistoryPosition("oldest", true, coverage))
            for (collectRows in listOf(true, false)) {
                disk().writeThread("h", "c", held)
                disk().writeHistoryPosition("h", "c", HistoryPosition("oldest", true, coverage))
                val live =
                    Live().apply {
                        answer =
                            { cursor -> if (cursor.isEmpty()) page(20, 21, cursor = "nineteen") else page(6, 7, 8, 9, cursor = "five") }
                    }
                val repository = CachingConversationRepository(live, disk(), "h")
                if (!collectRows) {
                    val seed = repository.readHistoryPosition("c") ?: error("missing seed")
                    val newest = live.requestHistory("c", "", 0)
                    repository.writeHistoryPosition("c", seed.copy(coverage = seed.coverage?.received(newest, newest = true)))
                    val middle = live.requestHistory("c", "nine", 0)
                    repository.writeHistoryPosition("c", seed.copy(coverage = coverage.received(newest).received(middle, target = 2)))
                } else {
                    val available = MutableStateFlow(true)
                    val vm =
                        ThreadViewModel(
                            SavedStateHandle(mapOf("conversationId" to "c")),
                            repository,
                            FakeConnectionStateSource(),
                            ComposerDraftStore(),
                            repositoryAvailable = available,
                        )
                    val store = ViewModelStore().apply { put("vm", vm) }
                    val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
                    advanceUntilIdle()
                    available.value = false
                    live.projection.remove("c")
                    advanceUntilIdle()
                    available.value = true
                    advanceUntilIdle()
                    vm.onDemandHistoryGap(2)
                    advanceUntilIdle()
                    assertEquals(listOf("", "", "nine"), live.asks)
                    assertEquals(
                        listOf(1, 2, 6, 7, 8, 9, 10, 11, 20, 21).map {
                            "m$it"
                        },
                        vm.state.value.items
                            .filterIsInstance<ThreadItem.MessageItem>()
                            .map { it.message.id },
                    )
                    assertEquals(
                        listOf(2L, 11L),
                        vm.state.value.historyMarkers
                            .map { it.anchor },
                    )
                    assertEquals(
                        listOf("m6", "m20").map { historyIdentity(listOf("message", it)) },
                        vm.state.value.historyMarkers
                            .map { it.beforeRow },
                    )
                    store.clear()
                    collector.cancel()
                }
                val restored = CachingConversationRepository(Live(), disk(), "h")
                assertEquals(
                    listOf(1, 2, 6, 7, 8, 9, 10, 11, 20, 21).map {
                        "m$it"
                    },
                    restored
                        .observeMessages("c")
                        .first()
                        .filterIsInstance<ThreadItem.MessageItem>()
                        .map { it.message.id },
                )
                assertEquals(listOf(HistoryGap(2, 6), HistoryGap(11, 20)), restored.readHistoryPosition("c")?.coverage?.gaps)
                val reopened =
                    ThreadViewModel(
                        SavedStateHandle(mapOf("conversationId" to "c")),
                        restored,
                        FakeConnectionStateSource(),
                        ComposerDraftStore(),
                        repositoryAvailable = flowOf(false),
                    )
                val store = ViewModelStore().apply { put("reopened", reopened) }
                val reader = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { reopened.state.collect {} }
                advanceUntilIdle()
                assertEquals(
                    listOf(2L, 11L),
                    reopened.state.value.historyMarkers
                        .map { it.anchor },
                )
                assertEquals(
                    listOf("m6", "m20").map { historyIdentity(listOf("message", it)) },
                    reopened.state.value.historyMarkers
                        .map { it.beforeRow },
                )
                store.clear()
                reader.cancel()
            }
        }

    @Test fun stateWriteAfterTrimmingCannotRestoreSavedStop() = trimmingResetsWalk(HistoryPosition("", true))

    @Test fun stateWriteAfterTrimmingCannotRestoreSavedCursor() = trimmingResetsWalk(HistoryPosition("past-discarded", false))

    private fun trimmingResetsWalk(saved: HistoryPosition) =
        // Crossing the real 100,000-row cap rewrites and reloads disk records; the full-suite
        // verifier exceeded runTest's one-minute default before these assertions could finish.
        runTest(timeout = 3.minutes) {
            val old = page(1)
            val coverage = HistoryCoverage().received(old).boundTo(rows(old))
            disk().writeThread("h", "c", rows(old))
            disk().writeHistoryPosition("h", "c", saved.copy(coverage = coverage))
            val template = rows(page(2)).single() as ThreadItem.MessageItem
            val many = List(MAX_CACHED_THREAD_ROWS) { template.copy(message = template.message.copy(id = "new-$it")) }
            val live =
                object : ConversationRepository by FakeConversationRepository() {
                    override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> = flowOf(many)
                }
            val repository = CachingConversationRepository(live, disk(), "h")
            repository.writeHistoryPosition("c", saved.copy(coverage = coverage.received(page(2), newest = true)))
            val restored = CachingConversationRepository(Live(), disk(), "h").readHistoryPosition("c") ?: error("missing state")
            assertEquals("", restored.cursor)
            assertFalse(restored.atStart)
            assertTrue(restored.coverage?.unknown == true)
            val demand = ThreadHistoryDemand().restored(restored.cursor, restored.atStart)
            assertTrue(demand.canAsk)
            assertEquals("", demand.asking().cursor)
        }

    @Test fun deleteDuringFallbackReadCannotRecreateRowsOrState() = deletionWins("read")

    @Test fun deleteBetweenRowAndStateWritesCannotRecreateDocument() = deletionWins("rows")

    @Test fun deleteDuringCoverageNullWriteCannotRecreateDocument() = deletionWins("state")

    @Test fun deleteDuringObserverWriteCannotRecreateRows() = deletionWins("observer")

    private fun deletionWins(point: String) =
        runTest {
            val disk = disk()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val gated =
                object : ConversationCache by disk {
                    override suspend fun readThread(
                        serverId: String,
                        conversationId: String,
                    ): List<ThreadItem> {
                        if (point == "read") {
                            entered.complete(Unit)
                            release.await()
                        }
                        return disk.readThread(serverId, conversationId)
                    }

                    override suspend fun writeThread(
                        serverId: String,
                        conversationId: String,
                        rows: List<ThreadItem>,
                    ): Result<Unit> {
                        if (point == "observer") {
                            entered.complete(Unit)
                            release.await()
                        }
                        return disk.writeThread(serverId, conversationId, rows).also {
                            if (point == "rows") {
                                entered.complete(Unit)
                                release.await()
                            }
                        }
                    }

                    override suspend fun writeHistoryPosition(
                        serverId: String,
                        conversationId: String,
                        position: HistoryPosition?,
                    ): Result<Unit> {
                        if (point == "state") {
                            entered.complete(Unit)
                            release.await()
                        }
                        return disk.writeHistoryPosition(serverId, conversationId, position)
                    }
                }
            val live = Live().apply { projection.mergeHistoryPage("c", page(1), true) }
            val repository = CachingConversationRepository(live, gated, "h")
            val writer =
                launch {
                    if (point == "observer") {
                        repository.observeMessages("c").collect {}
                    } else {
                        repository.writeHistoryPosition(
                            "c",
                            HistoryPosition(
                                "older",
                                false,
                                if (point ==
                                    "state"
                                ) {
                                    null
                                } else {
                                    HistoryCoverage().received(page(1))
                                },
                            ),
                        )
                    }
                }
            entered.await()
            val removal = launch { repository.delete("c") }
            runCurrent()
            release.complete(Unit)
            runCurrent()
            if (point == "observer") writer.cancel() else writer.join()
            removal.join()
            assertTrue(disk().readThread("h", "c").isEmpty())
            assertNull(disk().readHistoryPosition("h", "c"))
            assertTrue(tmp.root.walkTopDown().none { it.isFile })
            repository.writeHistoryPosition("c", HistoryPosition("late", true))
            assertNull(disk().readHistoryPosition("h", "c"))
        }
}
