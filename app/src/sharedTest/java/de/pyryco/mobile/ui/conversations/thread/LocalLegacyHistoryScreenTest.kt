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
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.HistoryCoverage
import de.pyryco.mobile.data.repository.HistoryPosition
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.UnsignedHistorySpan
import de.pyryco.mobile.data.repository.historyKeys
import de.pyryco.mobile.data.repository.historyRowProofs
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
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

    @Test fun oldestBoundaryPull_revealsOneSavedPageOffline() {
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
            val repository = CachingConversationRepository(FakeConversationRepository(), cache, "host")
            lateinit var vm: ThreadViewModel
            composeRule.runOnUiThread {
                vm =
                    ThreadViewModel(
                        SavedStateHandle(mapOf("serverId" to "host", "conversationId" to "c")),
                        repository,
                        FakeConnectionStateSource(),
                        ComposerDraftStore(),
                        repositoryAvailable = flowOf(false),
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
                        onDemandOlderHistory = vm::onDemandOlderHistory,
                        onDemandUnsignedHistoryGap = {
                            selected += it
                            vm.onDemandUnsignedHistoryGap(it)
                        },
                    )
                }
            }
            composeRule.waitUntil(15000) { vm.state.value.items.size == 200 && vm.state.value.historyMarkers.size == 1 }
            composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
            composeRule.onNodeWithTag("history-gap:0").assertIsDisplayed()
            composeRule.runOnIdle {
                assertEquals(200, vm.state.value.items.size)
                assertEquals(emptyList<ULong>(), selected)
            }
            composeRule.onNodeWithTag("thread-message-region").performTouchInput {
                swipeDown(startY = height * 0.3f, endY = height * 0.7f, durationMillis = 400)
            }
            composeRule.waitUntil(15000) { vm.state.value.items.size == 400 }
            composeRule.runOnIdle {
                assertEquals(listOf(0uL), selected)
                assertEquals(rows.takeLast(400), vm.state.value.items)
            }
        } finally {
            composeRule.runOnUiThread { store.clear() }
            composeRule.waitForIdle()
            database.close()
        }
    }
}
