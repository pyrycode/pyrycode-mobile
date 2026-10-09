package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ConversationReadMarks
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.ThreadReadEvidence
import de.pyryco.mobile.data.repository.ThreadSnapshot
import de.pyryco.mobile.data.repository.ThreadSnapshotSource
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exact paced versions still require the production viewport, reveal and destination lifecycle proof. */
@RunWith(AndroidJUnit4::class)
class ThreadPacedReadViewportTest {
    @get:Rule val compose = createComposeRule()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle = registry
    }

    @Test fun readVersionInvariant_pendingAndSkippedContent_waitForFrameLifecycleAndReveal() {
        fun snapshot(
            text: String,
            id: ULong,
        ): ThreadSnapshot {
            val row = ThreadItem.MessageItem(Message("reply", "s", Role.Assistant, text, Instant.fromEpochSeconds(1), isStreaming = true))
            return ThreadSnapshot(
                listOf(row),
                readEvidence =
                    ThreadReadEvidence(
                        versions = mapOf(row to setOf(id)),
                        facts = (1uL..id).associateWith { false },
                    ),
            )
        }
        val first = snapshot("First displayed version.", 1u)
        val skipped = snapshot("Skipped version.", 2u)
        val final = snapshot("Final complete displayed version.", 3u)
        val received = MutableStateFlow(first)
        val ticks = Channel<Long>(Channel.UNLIMITED)
        val checkpoints = mutableListOf<ULong>()
        val presented = mutableListOf<ThreadEvent.NewestContentPresented>()
        var subscriptions = 0
        var asks = 0
        val source =
            object : ConversationRepository by FakeConversationRepository(), ThreadSnapshotSource {
                override fun observeThreadSnapshot(conversationId: String) = received.onStart { subscriptions++ }

                override fun observeReadMarks(conversationId: String) = flowOf(ConversationReadMarks(0u, 3u))

                override suspend fun acknowledgeReadCheckpoint(
                    conversationId: String,
                    checkpoint: ULong,
                ) {
                    checkpoints += checkpoint
                }

                override suspend fun requestHistory(
                    conversationId: String,
                    cursor: String,
                    limit: Int,
                ): de.pyryco.mobile.data.repository.HistoryPage {
                    asks++
                    return de.pyryco.mobile.data.repository
                        .HistoryPage(emptyList(), "", false)
                }
            }
        val owner = Owner().apply { registry.currentState = Lifecycle.State.STARTED }
        val store = ViewModelStore()
        lateinit var vm: ThreadViewModel
        compose.runOnIdle {
            vm =
                ThreadViewModel(
                    SavedStateHandle(mapOf("serverId" to "h", "conversationId" to "c")),
                    source,
                    FakeConnectionStateSource(),
                    ComposerDraftStore(),
                    repositoryAvailable = flowOf(false),
                    contentScheduling = ThreadContentScheduling(Dispatchers.Main.immediate) { ticks.receive() },
                )
            store.put("vm", vm)
        }
        try {
            compose.setContent {
                val state by vm.state.collectAsState()
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    PyrycodeMobileTheme {
                        ThreadScreen(state, {}, {}, ConnectionState.Connected, {}, onOverflowEvent = {
                            if (it is ThreadEvent.NewestContentPresented) presented += it
                            vm.onOverflowEvent(it)
                        })
                    }
                }
            }
            compose.runOnIdle { check(ticks.trySend(1L).isSuccess) }
            compose.waitUntil(5000) { vm.state.value.items == first.rows }
            compose.runOnIdle {
                assertTrue(checkpoints.isEmpty())
                owner.registry.currentState = Lifecycle.State.RESUMED
            }
            compose.waitUntil(5000) { checkpoints == listOf(1uL) }
            compose.runOnIdle { received.value = skipped }
            compose.waitForIdle()
            compose.runOnIdle { received.value = final }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(first.rows, vm.state.value.items)
                assertEquals(listOf(1uL), checkpoints)
                owner.registry.currentState = Lifecycle.State.STARTED
                check(ticks.trySend(2L).isSuccess)
            }
            compose.waitUntil(5000) { vm.state.value.items == final.rows }
            compose.runOnIdle {
                assertEquals(listOf(1uL), checkpoints)
                vm.onOverflowEvent(ThreadEvent.Rename)
                owner.registry.currentState = Lifecycle.State.RESUMED
            }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(listOf(1uL), checkpoints)
                vm.onOverflowEvent(ThreadEvent.RenameDismiss)
            }
            compose.waitUntil(5000) { checkpoints == listOf(1uL, 3uL) }
            compose.runOnIdle {
                assertEquals(listOf(first.rows.single(), final.rows.single()), presented.map { it.row })
                assertEquals(1, subscriptions)
                assertEquals(0, asks)
            }
        } finally {
            compose.runOnIdle {
                store.clear()
                ticks.cancel()
            }
        }
    }
}
