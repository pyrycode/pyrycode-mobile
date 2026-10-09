package de.pyryco.mobile.ui.conversations.thread

import android.view.Choreographer
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.crypto.DeviceStaticKeyPair
import de.pyryco.mobile.data.crypto.DeviceStaticKeyStore
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.data.crypto.PairedServerEntry
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.NoiseClientInfo
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.RelayTransportFactory
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.ThreadSnapshot
import de.pyryco.mobile.data.repository.ThreadSnapshotSource
import de.pyryco.mobile.di.ObservablePairedServerStore
import de.pyryco.mobile.di.RelayConnectionFactory
import de.pyryco.mobile.di.RelayConnectionRegistry
import de.pyryco.mobile.di.ThreadDestinationFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Real Android frame scheduling and worker/main execution through the production destination factory. */
@RunWith(AndroidJUnit4::class)
class ThreadFramePacingDeviceTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun productionDestination_burstPublishesOncePerFrame_finalDelivers_andRecollectionCleansUp() {
        val timestamp = Instant.fromEpochSeconds(1)

        fun reading(
            text: String,
            streaming: Boolean = true,
        ) = ThreadSnapshot(
            listOf(ThreadItem.MessageItem(Message("reply", "s", Role.Assistant, text, timestamp, isStreaming = streaming))),
        )
        val snapshots = MutableStateFlow(reading("initial"))
        val subscriptions = AtomicInteger()
        val active = AtomicInteger()
        val source: ConversationRepository =
            object : ConversationRepository by FakeConversationRepository(), ThreadSnapshotSource {
                override fun observeThreadSnapshot(conversationId: String) =
                    snapshots
                        .onStart {
                            subscriptions.incrementAndGet()
                            active.incrementAndGet()
                        }.onCompletion { active.decrementAndGet() }
            }
        val rawStore =
            object : PairedServerCollectionStore {
                override suspend fun load(): PairedServer? = null

                override suspend fun save(record: PairedServer) = error("unused")

                override suspend fun list(): List<PairedServerEntry> = emptyList()

                override suspend fun loadById(serverId: String): PairedServerEntry? = null

                override suspend fun setDisplayName(
                    serverId: String,
                    displayName: String?,
                ) = Unit

                override suspend fun remove(serverId: String) = Unit
            }
        val store = ObservablePairedServerStore(rawStore) { }
        val keys =
            object : DeviceStaticKeyStore {
                override suspend fun loadOrCreate(serverId: String): DeviceStaticKeyPair = error("unused")

                override suspend fun publicKey(serverId: String): ByteArray? = null
            }
        val registry =
            RelayConnectionRegistry(
                store,
                RelayConnectionFactory(
                    keys,
                    RelayTransportFactory {
                        error("must not dial")
                    },
                    NoiseClientInfo("fixture", "fixture"),
                ),
            )
        val destinations =
            ThreadDestinationFactory(
                useRelay = true,
                registry = registry,
                fake = FakeConversationRepository(),
                store = store,
                decorateRepository = { source },
                attachmentReader = lazy { AttachmentReader { AttachmentRead.Unreadable } },
            )
        val preferences =
            AppPreferences(
                object : DataStore<Preferences> {
                    override val data = flowOf(emptyPreferences())

                    override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = transform(emptyPreferences())
                },
            )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val viewModels = ViewModelStore()
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val oldSink = RelayLog.sink
        RelayLog.sink = { _, _, event -> logs += event }
        lateinit var vm: ThreadViewModel
        lateinit var choreographer: Choreographer
        lateinit var monitor: Choreographer.FrameCallback
        var frame = 0L
        var reader: Job? = null
        val publications = mutableListOf<Pair<Long, List<ThreadItem>>>()
        var prior = emptyList<ThreadItem>()

        fun collect() {
            reader =
                scope.launch {
                    vm.state.collect { state ->
                        if (state.items != prior) {
                            prior = state.items
                            publications += frame to state.items
                        }
                    }
                }
        }
        composeRule.setContent { Text("Frame scheduling fixture") }
        composeRule.runOnUiThread {
            choreographer = Choreographer.getInstance()
            monitor =
                Choreographer.FrameCallback { nanos ->
                    frame = nanos
                    choreographer.postFrameCallback(monitor)
                }
            choreographer.postFrameCallback(monitor)
            vm =
                destinations.thread(
                    SavedStateHandle(mapOf("serverId" to "frame-fixture", "conversationId" to "c")),
                    ComposerDraftStore(),
                    preferences,
                )
            viewModels.put("thread", vm)
            collect()
        }

        fun content() =
            (
                vm.state.value.items
                    .lastOrNull() as? ThreadItem.MessageItem
            )?.message?.content
        try {
            composeRule.waitUntil(10_000) { content() == "initial" }
            composeRule.runOnUiThread {
                scope.launch {
                    for (count in 1..200) {
                        snapshots.value = reading("x".repeat(count))
                        yield()
                    }
                    snapshots.value = reading("x".repeat(200), streaming = false)
                }
                vm.onDraftChange("typing progresses")
                vm.onOverflowEvent(ThreadEvent.Rename)
            }
            composeRule.waitUntil(10_000) {
                content() == "x".repeat(200) &&
                    !(
                        vm.state.value.items
                            .last() as ThreadItem.MessageItem
                    ).message.isStreaming
            }
            composeRule.runOnUiThread {
                assertEquals("typing progresses", vm.draft.value)
                assertTrue(vm.state.value.showRenameDialog)
                assertTrue("real frame timestamps must be present", publications.all { it.first > 0L })
                assertTrue("no frame may publish two changed item lists", publications.groupBy { it.first }.values.all { it.size == 1 })
                assertTrue(publications.size >= 2)
                val versions = publications.drop(1).map { (it.second.last() as ThreadItem.MessageItem).message.content.length }
                assertTrue(versions.zipWithNext().all { (a, b) -> a <= b })
                reader?.cancel()
            }
            composeRule.waitUntil(10_000) { active.get() == 0 && synchronized(logs) { logs.contains("event=thread_frame_pacing_stopped") } }
            val retained = vm.state.value.items
            composeRule.runOnUiThread { snapshots.value = reading("uncollected") }
            composeRule.waitForIdle()
            assertEquals(retained, vm.state.value.items)
            repeat(2) {
                composeRule.runOnUiThread { collect() }
                composeRule.waitUntil(10_000) { active.get() == 1 && content() == "uncollected" }
                composeRule.runOnUiThread { reader?.cancel() }
                composeRule.waitUntil(10_000) { active.get() == 0 }
            }
            composeRule.runOnUiThread {
                snapshots.value = reading("reopened final", streaming = false)
                collect()
            }
            composeRule.waitUntil(10_000) { content() == "reopened final" }
            assertEquals(4, subscriptions.get())
            composeRule.runOnUiThread {
                viewModels.clear()
                reader?.cancel()
            }
            composeRule.waitUntil(10_000) { active.get() == 0 }
            val delivered = vm.state.value.items
            composeRule.runOnUiThread { snapshots.value = reading("late") }
            composeRule.waitForIdle()
            assertEquals(delivered, vm.state.value.items)
            synchronized(logs) {
                assertEquals(
                    logs.count { it == "event=thread_frame_pacing_started" },
                    logs.count {
                        it ==
                            "event=thread_frame_pacing_stopped"
                    },
                )
            }
        } finally {
            composeRule.runOnUiThread {
                choreographer.removeFrameCallback(monitor)
                viewModels.clear()
                scope.cancel()
            }
            registry.dispose()
            RelayLog.sink = oldSink
        }
    }
}
