package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.cache.FileConversationCache
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.InnerFrameV2
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.PumpState
import de.pyryco.mobile.data.network.RelayTransport
import de.pyryco.mobile.data.network.TransportEvent
import de.pyryco.mobile.data.repository.CachingConversationRepository
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.ManagedSessionPump
import de.pyryco.mobile.data.repository.RelayRepositoryCoordinator
import de.pyryco.mobile.data.repository.StableConversationRepository
import de.pyryco.mobile.ui.conversations.components.TOOL_ELAPSED_TAG
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Exercises the screen's real selected producer without replacing its presentation in the test. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NegotiatedThreadScreenTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val temporary = TemporaryFolder()
    private val connections = MutableStateFlow<RelayTransport?>(null)
    private val pumps = mutableListOf<Pump>()
    private val owner = ViewModelStore()
    private val coordinator =
        RelayRepositoryCoordinator(connections, MutableStateFlow(RelayLinkStatus.Connected), {
            Pump().also {
                pumps +=
                    it
            }
        }, dispatcher = Dispatchers.Main.immediate, authoritativeHostId = "host")

    @After fun close() {
        owner.clear()
        coordinator.close()
    }

    @Test fun negotiatedScreenShowsAdmittedProgressAndSuppressesOfflineFactsForMissingPeer() {
        lateinit var vm: ThreadViewModel
        compose.runOnIdle {
            coordinator.start()
            val stable = StableConversationRepository(coordinator.currentRepository, negotiatedThread = coordinator.negotiatedThread)
            val repository =
                CachingConversationRepository(
                    stable,
                    FileConversationCache(temporary.root, Dispatchers.Main.immediate),
                    "host",
                    processingDispatcher = Dispatchers.Main.immediate,
                )
            vm =
                ThreadViewModel(
                    SavedStateHandle(mapOf("serverId" to "host", "conversationId" to "c")),
                    repository,
                    FakeConnectionStateSource(),
                    ComposerDraftStore(),
                )
            owner.put("thread", vm)
            connections.value = Transport()
        }
        compose.runOnIdle { pumps.last().state.value = PumpState.Open("capable", setOf("interactive", "thread")) }
        compose.setContent {
            val state by vm.state.collectAsState()
            PyrycodeMobileTheme {
                ThreadScreen(state, onBack = {
                }, onSendMessage = {}, connectionState = ConnectionState.Connected, onRetry = {}, onOverflowEvent = vm::onOverflowEvent)
            }
        }
        compose.runOnIdle {
            pumps.last().push(
                """{"id":1,"type":"thread_item_added","ts":"2026-10-17T00:00:00Z","payload":{"conversation_id":"c","epoch":"e","version":2,"item":{"id":1,"rev":2,"order":1,"kind":"tool_call","session":"s","status":"running","active":true,"shown":true,"summary":"","content":{"name":"Read","tool_use_id":"tool","input_summary":"file"}}}}""",
            )
            pumps.last().push(
                """{"id":2,"type":"tool_progress","ts":"2026-10-17T00:00:00Z","conversation_id":"c","session_id":"s","session_state_cleared":true,"payload":{}}""",
            )
            pumps.last().push(
                """{"id":3,"type":"tool_progress","ts":"2026-10-17T00:00:00Z","conversation_id":"c","session_id":"s","payload":{"conversation_id":"c","turn_id":"turn","tool_use_id":"tool","elapsed_seconds":7}}""",
            )
        }
        compose.onNodeWithTag(TOOL_ELAPSED_TAG, useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { connections.value = null }
        compose.onNodeWithTag(TOOL_ELAPSED_TAG, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Read", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { connections.value = Transport() }
        compose.runOnIdle { pumps.last().state.value = PumpState.Open("missing", setOf("interactive")) }
        compose.onNodeWithText("Read", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(pumps.last().sent.none { it.type in setOf("thread_catch_up", "thread_page", "request_history", "backfill_since") })
        }
    }

    private class Pump : ManagedSessionPump {
        private val frames = Channel<Envelope>(Channel.UNLIMITED)
        override val inbound = frames.receiveAsFlow()
        override val state = MutableStateFlow<PumpState>(PumpState.Handshaking)
        val sent = mutableListOf<Envelope>()

        override fun start() = Unit

        override fun close() {
            frames.close()
        }

        override fun send(envelope: Envelope): Boolean {
            sent += envelope
            return true
        }

        fun push(raw: String) {
            check(frames.trySend(MobileJson.decodeFromString<Envelope>(raw)).isSuccess)
        }
    }

    private class Transport : RelayTransport {
        override val inbound: Flow<InnerFrameV2> = emptyFlow()
        override val events: Flow<TransportEvent> = emptyFlow()

        override fun connect() = Unit

        override fun send(frame: InnerFrameV2) = true

        override fun close() = Unit
    }
}
