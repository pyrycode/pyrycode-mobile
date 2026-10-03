package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.SessionCapabilities
import de.pyryco.mobile.data.repository.SessionSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelSendNowTest {
    private val oldSink = RelayLog.sink

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun teardown() {
        Dispatchers.resetMain()
        RelayLog.sink = oldSink
    }

    @Test fun conversationAndSessionChanges_doNotReuseSupport() =
        runTest {
            val fake = FakeConversationRepository()
            val selected = MutableStateFlow("s1")
            val sends = mutableListOf<Pair<String, Long>>()
            val repo =
                object : ConversationRepository by fake {
                    override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> =
                        combine(fake.observeConversations(filter), selected) { rows, session ->
                            rows.map { if (it.id == "seed-channel-personal") it.copy(currentSessionId = session) else it }
                        }

                    override suspend fun sendQueuedNow(
                        conversationId: String,
                        queuedMessageId: Long,
                    ) {
                        sends += conversationId to queuedMessageId
                    }
                }
            val settings =
                SessionSettings(
                    effectiveEffort = de.pyryco.mobile.data.repository.EffectiveEffort.Unavailable,
                    sessionId = "s1",
                    model = "opus",
                    effort = "high",
                    permissionMode = "default",
                    yolo = false,
                    usedTokens = 0,
                    windowTokens = 0,
                    capabilities = SessionCapabilities(emptyList(), emptyList(), midTurnInput = true),
                )
            fake.setSessionSettingsReading("seed-channel-personal", settings)
            val first =
                ThreadViewModel(
                    SavedStateHandle(mapOf("serverId" to "host-a", "conversationId" to "seed-channel-personal")),
                    repo,
                    FakeConnectionStateSource(),
                    ComposerDraftStore(),
                )
            val other =
                ThreadViewModel(
                    SavedStateHandle(mapOf("serverId" to "host-a", "conversationId" to "seed-discussion-b")),
                    repo,
                    FakeConnectionStateSource(),
                    ComposerDraftStore(),
                )
            backgroundScope.launch { first.state.collect {} }
            backgroundScope.launch { other.state.collect {} }
            runCurrent()
            assertTrue(first.state.value.runConfig.midTurnInputSupported)
            assertFalse(other.state.value.runConfig.midTurnInputSupported)
            selected.value = "s2"
            runCurrent()
            assertFalse(first.state.value.runConfig.midTurnInputSupported)
            selected.value = ""
            runCurrent()
            assertFalse(first.state.value.runConfig.midTurnInputSupported)
            first.onSendQueuedNow(42L)
            runCurrent()
            assertTrue(sends.isEmpty())
            fake.setSessionSettingsReading("seed-channel-personal", settings.copy(sessionId = "s2"))
            runCurrent()
            assertTrue(first.state.value.runConfig.midTurnInputSupported)
            first.onSendQueuedNow(43L)
            runCurrent()
            assertEquals(listOf("seed-channel-personal" to 43L), sends)
            fake.setSessionSettingsReading("seed-channel-personal", settings.copy(sessionId = "s2", held = true))
            runCurrent()
            assertFalse(first.state.value.runConfig.midTurnInputSupported)
        }

    @Test fun replacementReadings_gateActionAndKeepOwnerOnFailedSend() =
        runTest {
            val readings = MutableStateFlow<SessionSettings?>(null)
            val calls = mutableListOf<Pair<String, Long>>()
            val fake = FakeConversationRepository()
            val repo =
                object : ConversationRepository by fake {
                    override fun observeSessionSettings(conversationId: String) = readings

                    override suspend fun sendQueuedNow(
                        conversationId: String,
                        queuedMessageId: Long,
                    ) {
                        calls += conversationId to queuedMessageId
                        throw IllegalStateException("offline")
                    }
                }
            val vm =
                ThreadViewModel(
                    SavedStateHandle(mapOf("serverId" to "host-a", "conversationId" to "owned")),
                    repo,
                    FakeConnectionStateSource(),
                    ComposerDraftStore(),
                )
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            vm.onSendQueuedNow(42)
            assertEquals(emptyList<Pair<String, Long>>(), calls)
            readings.value =
                SessionSettings(
                    effectiveEffort = de.pyryco.mobile.data.repository.EffectiveEffort.Unavailable,
                    sessionId = "s1",
                    model = "opus",
                    effort = "high",
                    permissionMode = "default",
                    yolo = false,
                    usedTokens = 0,
                    windowTokens = 0,
                    capabilities = SessionCapabilities(emptyList(), emptyList(), midTurnInput = true),
                )
            runCurrent()
            assertTrue(vm.state.value.runConfig.midTurnInputSupported)
            val before = vm.state.value.items
            vm.onSendQueuedNow(42)
            runCurrent()
            assertEquals(listOf("owned" to 42L), calls)
            assertEquals(before, vm.state.value.items)
            readings.value = readings.value?.copy(sessionId = "s2", capabilities = null)
            runCurrent()
            assertFalse(vm.state.value.runConfig.midTurnInputSupported)
            vm.onSendQueuedNow(43)
            assertEquals(1, calls.size)
            readings.value = null
            runCurrent()
            assertFalse(vm.state.value.runConfig.midTurnInputSupported)
        }
}
