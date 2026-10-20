package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.cache.ConversationCacheDatabase
import de.pyryco.mobile.data.cache.MigratingConversationCache
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.CachingConversationRepository
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.HistoryCoverage
import de.pyryco.mobile.data.repository.HistoryPage
import de.pyryco.mobile.data.repository.HistoryPosition
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.ThreadSnapshot
import de.pyryco.mobile.data.repository.ThreadSnapshotSource
import de.pyryco.mobile.data.repository.UnsignedHistorySpan
import de.pyryco.mobile.data.repository.historyKeys
import de.pyryco.mobile.data.repository.historyRowProofs
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalLegacyHistoryScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @get:Rule val temporary = TemporaryFolder()

    @Test fun oldestBoundaryPull_revealsOneSavedPageOffline() = boundaryPull(false)

    @Test fun oldestBoundaryPull_revealsOneSavedPageWhileNewestIsHeld() = boundaryPull(true)

    private fun boundaryPull(online: Boolean) {
        val database =
            Room
                .databaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    ConversationCacheDatabase::class.java,
                    File(temporary.root, "history.db").absolutePath,
                ).build()
        val store = ViewModelStore()
        try {
            val rows =
                (0 until 401).map {
                    ThreadItem.MessageItem(Message("row-$it", "s", Role.User, "Row $it.", Instant.fromEpochSeconds(it.toLong()), false))
                }
            val order = rows.mapIndexed { index, row -> row.historyKeys().first() to (index + 1).toULong() }.toMap()
            val coverage =
                HistoryCoverage(
                    unsignedSpans = listOf(UnsignedHistorySpan(1u, 401u)),
                    unsignedUnknown = true,
                    unsignedRowOrder = order,
                    unsignedRowEntries = order.mapValues { setOf(it.value) },
                    proofs = historyRowProofs(rows),
                )
            val cache = MigratingConversationCache(File(temporary.root, "files"), database)
            runBlocking {
                cache.readThread("host", "c")
                cache.writeThread("host", "c", rows).getOrThrow()
                cache.writeHistoryPosition("host", "c", HistoryPosition("saved", false, coverage)).getOrThrow()
            }
            var asks = 0
            val live =
                object : ConversationRepository by FakeConversationRepository(), ThreadSnapshotSource {
                    override fun observeThreadSnapshot(conversationId: String) = MutableStateFlow(ThreadSnapshot(emptyList()))

                    override suspend fun requestHistory(
                        conversationId: String,
                        cursor: String,
                        limit: Int,
                    ): HistoryPage {
                        asks++
                        return awaitCancellation()
                    }
                }
            val repository = CachingConversationRepository(live, cache, "host")
            lateinit var vm: ThreadViewModel
            composeRule.runOnUiThread {
                vm =
                    ThreadViewModel(
                        SavedStateHandle(mapOf("serverId" to "host", "conversationId" to "c")),
                        repository,
                        FakeConnectionStateSource(),
                        ComposerDraftStore(),
                        repositoryAvailable = flowOf(online),
                    )
                store.put("thread", vm)
            }
            val selected = mutableListOf<ULong>()
            composeRule.setContent {
                val state by vm.state.collectAsState()
                PyrycodeMobileTheme {
                    ThreadScreen(
                        state,
                        {},
                        {},
                        ConnectionState.Offline,
                        {},
                        onDemandOlderHistory = {
                            vm.onDemandOlderHistory()
                        },
                        onDemandUnsignedHistoryGap = {
                            selected += it
                            vm.onDemandUnsignedHistoryGap(it)
                        },
                    )
                }
            }
            try {
                composeRule.waitUntil(15000) {
                    composeRule.waitForIdle()
                    vm.state.value.items.size == 200 && vm.state.value.historyMarkers.size == 1
                }
            } finally {
                println(
                    "event=boundary_ready online=$online rows=${vm.state.value.items.size} markers=${vm.state.value.historyMarkers.size} tail=${vm.state.value.historyTail} asks=$asks",
                )
            }
            composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(199)
            composeRule.onNodeWithTag("history-gap:0").assertIsDisplayed()
            composeRule.runOnIdle {
                assertEquals(200, vm.state.value.items.size)
                assertEquals(if (online) ThreadHistoryTail.Loading else ThreadHistoryTail.Offline, vm.state.value.historyTail)
                assertEquals(if (online) 1 else 0, asks)
                assertEquals(emptyList<ULong>(), selected)
            }
            composeRule.onNodeWithTag("thread-message-region").performTouchInput {
                swipeDown(startY = height * 0.3f, endY = height * 0.7f, durationMillis = 400)
            }
            try {
                composeRule.waitUntil(15000) {
                    composeRule.waitForIdle()
                    vm.state.value.items.size == 400
                }
            } finally {
                println(
                    "event=boundary_pull online=$online rows=${vm.state.value.items.size} selected=${selected.size} tail=${vm.state.value.historyTail} asks=$asks",
                )
            }
            composeRule.runOnIdle {
                assertEquals(listOf(0uL), selected)
                assertEquals(rows.takeLast(400), vm.state.value.items)
                assertEquals(if (online) 1 else 0, asks)
            }
        } finally {
            composeRule.runOnUiThread { store.clear() }
            composeRule.waitForIdle()
            database.close()
        }
    }
}
