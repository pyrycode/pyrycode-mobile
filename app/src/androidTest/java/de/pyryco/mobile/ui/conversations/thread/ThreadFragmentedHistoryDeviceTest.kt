package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.HistoryPage
import de.pyryco.mobile.data.repository.HistoryPosition
import de.pyryco.mobile.data.repository.QueuedMessage
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.historyKeys
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real worker/main and IME progress while opening restored fragmented coverage. */
@RunWith(AndroidJUnit4::class)
class ThreadFragmentedHistoryDeviceTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun sparseFragmentedRestore_opensEditsAndScrolls_onePagePerPull() {
        val (base, rows) = fragmentedHistoryFixture(listOf(9000, 9001, 17000, 17999))
        val queuedShadows =
            rows.take(2).mapIndexed { index, row ->
                ThreadItem.MessageItem((row as ThreadItem.MessageItem).message.copy(id = "queued-$index"))
            }
        val coverage =
            base.copy(
                unsignedRowOrder =
                    base.unsignedRowOrder +
                        queuedShadows.zip(rows).associate { (queued, delivered) ->
                            queued.historyKeys().first() to base.unsignedRowOrder.getValue(delivered.historyKeys().first())
                        },
            )
        val held =
            listOf(ThreadItem.BackgroundTaskLifecycle("task", Instant.fromEpochSeconds(0)), queuedShadows[0], rows[0], queuedShadows[1]) +
                rows.drop(1)
        val queue =
            queuedShadows.mapIndexed { index, row ->
                QueuedMessage(index.toLong(), row.message.content, row.message.timestamp, row.message.id)
            }
        val asks = mutableListOf<Pair<String, Int>>()
        val repo =
            object : ConversationRepository by FakeConversationRepository() {
                override fun observeMessages(conversationId: String) = flowOf(held)

                override fun observeQueue(conversationId: String) = flowOf(queue)

                override suspend fun readHistoryPosition(conversationId: String) = HistoryPosition("oldest", true, coverage)

                override suspend fun requestHistory(
                    conversationId: String,
                    cursor: String,
                    limit: Int,
                ): HistoryPage {
                    asks += cursor to limit
                    return HistoryPage(emptyList(), "next-page", false)
                }
            }
        val store = ViewModelStore()
        lateinit var vm: ThreadViewModel
        composeRule.runOnUiThread {
            vm =
                ThreadViewModel(
                    SavedStateHandle(mapOf("serverId" to "fixture", "conversationId" to "c")),
                    repo,
                    FakeConnectionStateSource(),
                    ComposerDraftStore(),
                )
            store.put("thread", vm)
        }
        try {
            composeRule.setContent {
                val state by vm.state.collectAsState()
                val draft by vm.draft.collectAsState()
                PyrycodeMobileTheme {
                    ThreadScreen(
                        state,
                        {},
                        vm::sendMessage,
                        ConnectionState.Connected,
                        {},
                        draft = draft,
                        onDraftChange = vm::onDraftChange,
                        onDemandOlderHistory = vm::onDemandOlderHistory,
                        onDemandUnsignedHistoryGap = vm::onDemandUnsignedHistoryGap,
                    )
                }
            }
            composeRule.waitUntil(15000) { vm.state.value.items.size == held.size && vm.state.value.historyMarkers.size == 2 }
            composeRule.runOnIdle {
                assertEquals(
                    listOf(35998uL, 36002uL),
                    vm.state.value.historyMarkers
                        .map { it.unsignedAnchor },
                )
                assertEquals(
                    rows.take(2),
                    vm.state.value.historyMarkers
                        .map { it.displayRow },
                )
                assertEquals(listOf("" to 200), asks)
            }
            composeRule.onNodeWithTag("history-gap:35998").assertIsDisplayed()
            composeRule.onNodeWithTag("history-gap:36002").assertIsDisplayed()
            composeRule.onNode(hasSetTextAction()).performTextInput("composer remains responsive")
            composeRule.runOnIdle { assertEquals("composer remains responsive", vm.draft.value) }
            val region = composeRule.onNodeWithTag("thread-message-region")
            region.performTouchInput {
                down(Offset(center.x, height * 0.2f))
                moveBy(Offset(0f, 40f), delayMillis = 400)
            }
            composeRule.waitForIdle()
            // Settlement and continuing the same touch must not redirect to another page.
            region.performTouchInput {
                moveBy(Offset(0f, 50f), delayMillis = 400)
                up()
            }
            composeRule.runOnIdle {
                assertEquals(2, asks.size)
                assertEquals("internal-gap" to 200, asks.last())
            }
        } finally {
            composeRule.runOnUiThread { store.clear() }
        }
    }
}
