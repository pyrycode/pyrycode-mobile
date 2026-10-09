package de.pyryco.mobile.ui.conversations.thread

import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.cache.ConversationCache
import de.pyryco.mobile.data.cache.FileConversationCache
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.CachingConversationRepository
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.HistoryCoverage
import de.pyryco.mobile.data.repository.HistoryPage
import de.pyryco.mobile.data.repository.HistoryPosition
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.ThreadSnapshotSource
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Real disk, workers, Android frames and drawn message text; no Compose virtual-time measurement. */
@RunWith(AndroidJUnit4::class)
class SavedThreadFirstDrawDeviceTest {
    @get:Rule val composeRule = createComposeRule()

    private data class Opening(
        val vm: ThreadViewModel,
        val probe: Probe,
        val online: Boolean,
    )

    private val selected = mutableStateOf<Opening?>(null)
    private var activeProbe: Probe? = null

    private class Probe(
        val name: String,
        val newestText: String,
    ) {
        val start = SystemClock.elapsedRealtimeNanos()
        val restored = AtomicLong()
        val snapshot = AtomicLong()
        val complete = AtomicLong()
        val drawn = AtomicLong()

        fun elapsed(nanos: Long) = (nanos - start) / 1_000_000

        fun assertBound() {
            assertTrue(
                "$name first drawn newest row: ${elapsed(drawn.get())} ms (bound 1000 ms)",
                drawn.get() > 0 && elapsed(drawn.get()) <= 1000,
            )
        }
    }

    @Test fun savedThreads_firstNewestDrawWithinOneSecond_offlineAndHeldNewest_firstOpenAndReopen() {
        installHost()
        val ordinary =
            (0 until 20).map {
                ThreadItem.MessageItem(Message("ordinary-$it", "s", Role.User, "Saved $it.", Instant.fromEpochSeconds(it.toLong()), false))
            }
        val fragmented = fragmentedHistoryFixture((0 until 18000).toList())
        for ((name, coverage, rows) in listOf(
            Triple("ordinary", null, ordinary),
            Triple("fragmented", fragmented.first, fragmented.second),
        )) {
            for (online in listOf(false, true)) {
                withFixture(name, rows, coverage) { root ->
                    val delegate = HeldNewest()
                    var repo: CachingConversationRepository? = null
                    repeat(2) { opening ->
                        val probe = Probe("$name online=$online opening=$opening", (rows.last() as ThreadItem.MessageItem).message.content)
                        activeProbe = probe
                        if (repo == null) repo = repository(root, delegate)
                        openAndMeasure(requireNotNull(repo), delegate, online, probe, rows, coverage)
                        probe.assertBound()
                    }
                }
            }
        }
    }

    @Test fun slowRestore_negativeControlRejectsTheSameFirstDrawBound() {
        installHost()
        val rows = listOf(ThreadItem.MessageItem(Message("slow", "s", Role.User, "Slow saved row.", Instant.fromEpochSeconds(1), false)))
        withFixture("slow", rows, null) { root ->
            val delegate = HeldNewest()
            val probe = Probe("negative-control", (rows.last() as ThreadItem.MessageItem).message.content)
            activeProbe = probe
            openAndMeasure(repository(root, delegate, 3000), delegate, false, probe, rows, null)
            assertTrue(probe.elapsed(probe.drawn.get()) >= 3000)
            assertThrows(AssertionError::class.java) { probe.assertBound() }
        }
    }

    private class HeldNewest : ConversationRepository by FakeConversationRepository() {
        override fun observeConversations(filter: ConversationFilter) =
            flowOf(listOf(Conversation("c", "Saved channel", "", "s", emptyList(), true, Instant.fromEpochSeconds(1))))

        val asks = mutableListOf<Pair<String, Int>>()
        val response = CompletableDeferred<HistoryPage>()

        override fun observeMessages(conversationId: String) = flowOf(emptyList<ThreadItem>())

        override suspend fun requestHistory(
            conversationId: String,
            cursor: String,
            limit: Int,
        ): HistoryPage {
            asks += cursor to limit
            return response.await()
        }
    }

    private fun repository(
        root: File,
        delegate: HeldNewest,
        delayMs: Long = 0,
    ): CachingConversationRepository {
        val disk = FileConversationCache(root)
        val measured =
            object : ConversationCache by disk {
                override suspend fun readThread(
                    serverId: String,
                    conversationId: String,
                ): List<ThreadItem> {
                    delay(delayMs)
                    return disk
                        .readThread(
                            serverId,
                            conversationId,
                        ).also { activeProbe?.restored?.compareAndSet(0, SystemClock.elapsedRealtimeNanos()) }
                }
            }
        return CachingConversationRepository(delegate, measured, "host")
    }

    private fun installHost() {
        composeRule.setContent {
            val opening = selected.value
            val vm = opening?.vm
            val view = LocalView.current
            if (vm != null) {
                val probe = opening.probe
                val online = opening.online
                val state by vm.state.collectAsState()
                DisposableEffect(vm) {
                    val observer = view.viewTreeObserver
                    val listener =
                        ViewTreeObserver.OnDrawListener {
                            val nodes =
                                view
                                    .composeRoot()
                                    ?.semanticsOwner
                                    ?.getAllSemanticsNodes(mergingEnabled = false)
                                    .orEmpty()
                            val viewport =
                                nodes
                                    .firstOrNull {
                                        it.config.getOrNull(
                                            SemanticsProperties.TestTag,
                                        ) == "thread-message-region"
                                    }?.boundsInRoot
                            val newest =
                                nodes.firstOrNull { node ->
                                    node.config.getOrNull(SemanticsProperties.Text)?.any {
                                        it.text ==
                                            probe.newestText
                                    } ==
                                        true
                                }
                            if (viewport != null &&
                                newest != null &&
                                newest.layoutInfo.isPlaced &&
                                newest.boundsInRoot.width > 0 &&
                                newest.boundsInRoot.height > 0 &&
                                newest.boundsInRoot.top >= viewport.top &&
                                newest.boundsInRoot.bottom <= viewport.bottom
                            ) {
                                view.viewTreeObserver.registerFrameCommitCallback {
                                    probe.drawn.compareAndSet(
                                        0,
                                        SystemClock.elapsedRealtimeNanos(),
                                    )
                                }
                            }
                        }
                    observer.addOnDrawListener(listener)
                    onDispose { observer.removeOnDrawListener(listener) }
                }
                Box {
                    if (state.items.isNotEmpty()) probe.complete.compareAndSet(0, SystemClock.elapsedRealtimeNanos())
                    PyrycodeMobileTheme {
                        ThreadScreen(state, {
                        }, {
                        }, if (online) ConnectionState.Connected else ConnectionState.Offline, {
                        }, onDemandOlderHistory = vm::onDemandOlderHistory, onDemandUnsignedHistoryGap = vm::onDemandUnsignedHistoryGap)
                    }
                }
            }
        }
    }

    private fun openAndMeasure(
        repo: CachingConversationRepository,
        delegate: HeldNewest,
        online: Boolean,
        probe: Probe,
        rows: List<ThreadItem>,
        coverage: HistoryCoverage?,
    ) {
        val store = ViewModelStore()
        val beforeAsks = delegate.asks.size
        val measuredRepo =
            object : ConversationRepository by repo, ThreadSnapshotSource {
                override fun observeThreadSnapshot(conversationId: String) =
                    repo.observeThreadSnapshot(conversationId).onEach {
                        if (it.rows.isNotEmpty()) probe.snapshot.compareAndSet(0, SystemClock.elapsedRealtimeNanos())
                    }
            }
        composeRule.runOnUiThread {
            val vm =
                ThreadViewModel(
                    SavedStateHandle(mapOf("serverId" to "host", "conversationId" to "c")),
                    measuredRepo,
                    FakeConnectionStateSource(),
                    ComposerDraftStore(),
                    repositoryAvailable = flowOf(online),
                    contentScheduling = ThreadContentScheduling(),
                )
            store.put("thread", vm)
            selected.value = Opening(vm, probe, online)
        }
        try {
            composeRule.waitUntil(15000) { probe.drawn.get() > 0 }
            composeRule.runOnIdle {
                val state = requireNotNull(selected.value).vm.state.value
                assertEquals(rows, state.items)
                assertEquals(if (online) beforeAsks + 1 else beforeAsks, delegate.asks.size)
                assertTrue(delegate.asks.all { it == "" to 200 })
                assertFalse(delegate.response.isCompleted)
                assertEquals(
                    coverage
                        ?.unsignedGaps
                        ?.map { it.anchor }
                        ?.let { listOf(0uL) + it }
                        .orEmpty(),
                    state.historyMarkers.map { it.unsignedAnchor },
                )
            }
            Log.i(
                "SavedThreadFirstDraw",
                "case=${probe.name} rows=${rows.size} durable=${coverage?.unsignedSpans?.sumOf {
                    (it.last - it.first + 1u).toLong()
                } ?: rows.size.toLong()} spans=${coverage?.unsignedSpans?.size ?: 0} restored_ms=${probe.elapsed(
                    probe.restored.get(),
                )} snapshot_ms=${probe.elapsed(
                    probe.snapshot.get(),
                )} complete_ms=${probe.elapsed(probe.complete.get())} drawn_ms=${probe.elapsed(probe.drawn.get())}",
            )
        } finally {
            composeRule.runOnUiThread {
                selected.value = null
                store.clear()
            }
            composeRule.waitForIdle()
        }
    }

    private fun View.composeRoot(): ViewRootForTest? {
        if (this is ViewRootForTest) return this
        if (this is ViewGroup) for (index in 0 until childCount) getChildAt(index).composeRoot()?.let { return it }
        return null
    }

    private fun withFixture(
        name: String,
        rows: List<ThreadItem>,
        coverage: HistoryCoverage?,
        block: (File) -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.noBackupFilesDir, "first-draw-$name-${SystemClock.elapsedRealtimeNanos()}")
        try {
            runBlocking {
                val cache = FileConversationCache(root)
                cache.writeThread("host", "c", rows).getOrThrow()
                if (coverage != null) cache.writeHistoryPosition("host", "c", HistoryPosition("saved", false, coverage)).getOrThrow()
            }
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
