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

    @Test fun unsignedTerminalHistory_staysIncompleteAcrossWalkCacheAndReopen() =
        unsignedHistoryRemainsIncomplete(mixed = false, previouslyComplete = false)

    @Test fun mixedTerminalHistory_staysIncompleteAcrossWalkCacheAndReopen() =
        unsignedHistoryRemainsIncomplete(mixed = true, previouslyComplete = false)

    @Test fun unsignedNewestHistory_reopensPreviouslyCompleteWalkAndSurvivesSignedTerminalPage() =
        unsignedHistoryRemainsIncomplete(mixed = true, previouslyComplete = true)

    @Test fun mixedNonterminalHistory_preservesCursorAndUnknownCoverageAfterSignedTerminalPage() =
        unsignedHistoryRemainsIncomplete(mixed = true, previouslyComplete = false, terminal = false)

    @Test fun unsignedStateOnlyHistory_keepsUncertaintyAndCursorThroughEmptyCacheReopen() =
        emptyUnsignedCacheRemainsIncomplete("turn_state", """{"conversation_id":"c","state":"idle"}""")

    @Test fun unsignedUncacheableHistory_keepsUncertaintyAndCursorThroughEmptyCacheReopen() =
        emptyUnsignedCacheRemainsIncomplete(
            "unrecognized_message",
            """{"conversation_id":"c","site":"undecodable","message_type":"","raw":"inert","truncated":false}""",
        )

    private fun emptyUnsignedCacheRemainsIncomplete(
        type: String,
        payload: String,
    ) = runTest {
        val unsupported =
            HistoryPage(
                listOf(
                    HistoryEntry(
                        type = type,
                        payload = MobileJson.parseToJsonElement(payload),
                        timestamp = Instant.fromEpochSeconds(1),
                        unsignedId = ULong.MAX_VALUE,
                    ),
                ),
                "older-upper",
                false,
            )
        val live = Live().apply { answer = { unsupported } }
        val vm =
            ThreadViewModel(
                SavedStateHandle(mapOf("conversationId" to "c")),
                CachingConversationRepository(live, disk(), "h"),
                FakeConnectionStateSource(),
                ComposerDraftStore(),
                projectionDispatcher = UnconfinedTestDispatcher(testScheduler),
                repositoryAvailable = flowOf(true),
            )
        val store = ViewModelStore().apply { put("vm", vm) }
        val reader = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        advanceUntilIdle()
        assertEquals(listOf(""), live.asks)
        assertEquals(
            if (type == "turn_state") 0 else 1,
            live.projection
                .observe("c")
                .first()
                .size,
        )
        assertTrue("cache policy must retain no renderer rows", disk().readThread("h", "c").isEmpty())
        val saved = disk().readHistoryPosition("h", "c") ?: error("missing saved metadata")
        assertEquals("older-upper", saved.cursor)
        assertTrue(
            saved.coverage
                ?.spans
                .orEmpty()
                .isEmpty(),
        )
        assertTrue(saved.coverage?.unsignedIncomplete == true)
        assertTrue(saved.coverage?.unknown == true)
        assertFalse(saved.atStart)
        store.clear()
        reader.cancel()

        val lower = page(1).copy(cursor = "", atStart = true)
        val reopenedLive = Live().apply { answer = { lower } }
        val repository = CachingConversationRepository(reopenedLive, disk(), "h")
        assertEquals("empty cache must preserve its coverage and cursor", saved, repository.readHistoryPosition("c"))
        val reopened =
            ThreadViewModel(
                SavedStateHandle(mapOf("conversationId" to "c")),
                repository,
                FakeConnectionStateSource(),
                ComposerDraftStore(),
                projectionDispatcher = UnconfinedTestDispatcher(testScheduler),
                repositoryAvailable = flowOf(true),
            )
        val reopenedStore = ViewModelStore().apply { put("vm", reopened) }
        val reopenedReader = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { reopened.state.collect {} }
        advanceUntilIdle()
        repeat(2) {
            val afterLower = disk().readHistoryPosition("h", "c") ?: error("missing reopened metadata")
            assertTrue("signed terminal page must retain omitted-content uncertainty", afterLower.coverage?.unsignedIncomplete == true)
            assertTrue(afterLower.coverage?.unknown == true)
            assertFalse("signed terminal page cannot certify completeness", afterLower.atStart)
            assertEquals(saved.cursor, afterLower.cursor)
            reopened.onDemandOlderHistory()
            advanceUntilIdle()
        }
        assertEquals(
            "older demand must remain available with the saved cursor",
            listOf("", "older-upper", "older-upper"),
            reopenedLive.asks,
        )
        reopenedStore.clear()
        reopenedReader.cancel()
    }

    private fun unsignedHistoryRemainsIncomplete(
        mixed: Boolean,
        previouslyComplete: Boolean,
        terminal: Boolean = true,
    ) = runTest {
        val lower = page(1).copy(cursor = "", atStart = true)
        if (previouslyComplete) {
            disk().writeThread("h", "c", rows(lower))
            disk().writeHistoryPosition("h", "c", HistoryPosition("", true, HistoryCoverage().received(lower)))
        }
        val upper =
            HistoryEntry(
                type = "send_message",
                payload = MobileJson.parseToJsonElement("""{"conversation_id":"c","message_id":"upper","text":"upper"}"""),
                timestamp = Instant.fromEpochSeconds(1),
                unsignedId = Long.MAX_VALUE.toULong() + 1u,
            )
        val unsupported =
            HistoryPage(listOf(upper) + if (mixed) lower.entries else emptyList(), if (terminal) "" else "older", terminal)
        val live = Live().apply { answer = { unsupported } }
        val repository = CachingConversationRepository(live, disk(), "h")
        val vm =
            ThreadViewModel(
                SavedStateHandle(mapOf("conversationId" to "c")),
                repository,
                FakeConnectionStateSource(),
                ComposerDraftStore(),
                projectionDispatcher = UnconfinedTestDispatcher(testScheduler),
                repositoryAvailable = flowOf(true),
            )
        val store = ViewModelStore().apply { put("vm", vm) }
        val reader = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        advanceUntilIdle()
        assertEquals(listOf(""), live.asks)
        val saved = CachingConversationRepository(Live(), disk(), "h").readHistoryPosition("c") ?: error("missing position")
        assertTrue("omitted ids must mark fresh and previously complete coverage unknown", saved.coverage?.unknown == true)
        assertFalse("unsupported terminal pages cannot persist completeness", saved.atStart)
        assertEquals(if (terminal) "" else "older", saved.cursor)
        assertEquals(if (previouslyComplete || mixed) listOf(HistorySpan(1, 1)) else emptyList<HistorySpan>(), saved.coverage?.spans)
        live.answer = { lower }
        vm.onDemandOlderHistory()
        advanceUntilIdle()
        assertEquals("ordinary older demand must remain available", 2, live.asks.size)
        val afterLower = disk().readHistoryPosition("h", "c") ?: error("missing position")
        assertTrue("a signed terminal page cannot certify omitted upper content", afterLower.coverage?.unknown == true)
        assertFalse(afterLower.atStart)
        assertEquals(saved.cursor, afterLower.cursor)
        store.clear()
        reader.cancel()

        val reopenedLive = Live().apply { answer = { lower } }
        val reopened =
            ThreadViewModel(
                SavedStateHandle(mapOf("conversationId" to "c")),
                CachingConversationRepository(reopenedLive, disk(), "h"),
                FakeConnectionStateSource(),
                ComposerDraftStore(),
                projectionDispatcher = UnconfinedTestDispatcher(testScheduler),
                repositoryAvailable = flowOf(true),
            )
        val reopenedStore = ViewModelStore().apply { put("vm", reopened) }
        val reopenedReader = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { reopened.state.collect {} }
        advanceUntilIdle()
        reopened.onDemandOlderHistory()
        advanceUntilIdle()
        assertEquals("restore cannot disable older demand", 2, reopenedLive.asks.size)
        assertTrue(disk().readHistoryPosition("h", "c")?.coverage?.unknown == true)
        assertFalse(disk().readHistoryPosition("h", "c")?.atStart ?: true)
        reopenedStore.clear()
        reopenedReader.cancel()
    }

    @Test fun incompleteSignedPosition_cannotCertifyCompleteCacheOnWriteOrRead() =
        runTest {
            val received = page(1)
            val coverage = HistoryCoverage(unknown = true, unsignedIncomplete = true).received(received)
            val unsafe = HistoryPosition("", true, coverage)
            val live = Live().apply { projection.mergeHistoryPage("c", received, true) }
            val repository = CachingConversationRepository(live, disk(), "h")
            repository.writeHistoryPosition("c", unsafe)
            assertFalse(disk().readHistoryPosition("h", "c")?.atStart ?: true)
            // Read independently saved contradictory metadata through a fresh cache instance.
            disk().writeHistoryPosition("h", "c", unsafe)
            val restored = CachingConversationRepository(Live(), disk(), "h").readHistoryPosition("c") ?: error("missing state")
            assertFalse(restored.atStart)
            assertTrue(restored.coverage?.unknown == true)
        }

    @Test fun incompleteSignedPosition_cannotStopViewModelSeedFromAnotherRepository() =
        runTest {
            val live = Live().apply { answer = { page(1).copy(cursor = "", atStart = true) } }
            val repository =
                object : ConversationRepository by live {
                    override suspend fun readHistoryPosition(conversationId: String) =
                        HistoryPosition("", true, HistoryCoverage(unknown = true, unsignedIncomplete = true))
                }
            val vm =
                ThreadViewModel(
                    SavedStateHandle(mapOf("conversationId" to "c")),
                    repository,
                    FakeConnectionStateSource(),
                    ComposerDraftStore(),
                    projectionDispatcher = UnconfinedTestDispatcher(testScheduler),
                    repositoryAvailable = flowOf(true),
                )
            val store = ViewModelStore().apply { put("vm", vm) }
            val reader = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()
            vm.onDemandOlderHistory()
            advanceUntilIdle()
            assertEquals(listOf("", ""), live.asks)
            store.clear()
            reader.cancel()
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
                            projectionDispatcher = UnconfinedTestDispatcher(testScheduler),
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
                        projectionDispatcher = UnconfinedTestDispatcher(testScheduler),
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
