package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.EffectiveEffort
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.MemorySearchAvailability
import de.pyryco.mobile.data.repository.MemorySearchReport
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
class VerifierReconnectSessionTest {
    private val oldSink = RelayLog.sink

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun teardown() {
        Dispatchers.resetMain()
        RelayLog.sink = oldSink
    }

    @Test fun freshNewSessionAfterReconnect_restoresSendNowAndCurrentReadings() =
        runTest {
            val fake = FakeConversationRepository()
            val selected = MutableStateFlow("s1")
            val available = MutableStateFlow(true)
            val connection = FakeConnectionStateSource()
            val sends = mutableListOf<Pair<String, Long>>()
            val repo =
                object : ConversationRepository by fake {
                    override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> =
                        combine(fake.observeConversations(filter), selected) { rows, session ->
                            rows.map { if (it.id == CONV) it.copy(currentSessionId = session) else it }
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
                    sessionId = "s1",
                    model = "opus",
                    effort = "high",
                    effectiveEffort = EffectiveEffort.Applied("high"),
                    permissionMode = "plan",
                    yolo = false,
                    usedTokens = 0,
                    windowTokens = 0,
                    capabilities = SessionCapabilities(emptyList(), emptyList(), midTurnInput = true),
                    memorySearch = MemorySearchReport(MemorySearchAvailability.Available, emptyList()),
                )
            fake.setSessionSettingsReading(CONV, settings)
            val vm =
                ThreadViewModel(
                    SavedStateHandle(mapOf("serverId" to "host-a", "conversationId" to CONV)),
                    repo,
                    connection,
                    ComposerDraftStore(),
                    repositoryAvailable = available,
                )
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            assertTrue(vm.state.value.runConfig.midTurnInputSupported)
            // A new connection gets summary placeholders, not the transition that happened offline.
            available.value = false
            connection.emit(ConnectionState.Offline)
            fake.setSessionSettingsReading(CONV, null)
            selected.value = ""
            runCurrent()
            assertFalse(vm.state.value.runConfig.midTurnInputSupported)
            // Cached selections and held support in the gap do not belong to the next connection.
            selected.value = "s1"
            fake.setSessionSettingsReading(CONV, settings.copy(held = true))
            runCurrent()
            assertTrue(vm.state.value.runConfig.settingsHeld)
            assertFalse(vm.state.value.runConfig.midTurnInputSupported)
            vm.onSendQueuedNow(41L)
            runCurrent()
            assertTrue(sends.isEmpty())
            selected.value = ""
            runCurrent()
            available.value = true
            connection.emit(ConnectionState.Connected)
            runCurrent()
            assertFalse(vm.state.value.runConfig.midTurnInputSupported)
            fake.setSessionSettingsReading(CONV, settings.copy(sessionId = "s2"))
            runCurrent()
            val config = vm.state.value.runConfig
            assertEquals("s2", config.sessionId)
            assertFalse(config.settingsHeld)
            vm.onSendQueuedNow(42L)
            runCurrent()
            assertTrue(
                "Fresh s2: supported=${config.midTurnInputSupported}, permission=${config.permissionMode}, " +
                    "effort=${config.appliedEffort}, memory=${config.memorySearch}, sends=$sends",
                config.midTurnInputSupported &&
                    config.permissionMode == "plan" &&
                    config.appliedEffort == EffectiveEffort.Applied("high") &&
                    config.memorySearch.availability == MemorySearchAvailability.Available &&
                    sends == listOf(CONV to 42L),
            )
        }

    private companion object {
        const val CONV = "seed-channel-personal"
    }
}
