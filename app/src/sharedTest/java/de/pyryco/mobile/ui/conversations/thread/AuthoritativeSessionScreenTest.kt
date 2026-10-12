package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.HostModalState
import de.pyryco.mobile.data.model.reduce
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.repository.ConnectionStateSource
import de.pyryco.mobile.data.repository.RemoteConversationRepository
import de.pyryco.mobile.data.repository.SessionPump
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuthoritativeSessionScreenTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pump = Pump()
    private val repo =
        RemoteConversationRepository(
            pump,
            scope,
            negotiatedCapabilities = { setOf("thread", "interactive") },
            authoritativeHostId = "host",
        )
    private val host = MutableStateFlow(HostModalState())
    private val connection =
        object : ConnectionStateSource {
            override fun observe() = flowOf(ConnectionState.Connected)

            override suspend fun retry() = Unit
        }
    private lateinit var vm: ThreadViewModel

    @After fun close() {
        scope.cancel()
    }

    @Test fun suppliedSessionReplacementUpdatesActivityAndRetiresRenderedPromptCallbacks() {
        // The current state survives arriving before the screen/coordinator collector.
        compose.runOnIdle {
            push("turn_state", "old", clear = true)
            push("turn_state", "old", fields = "\"state\":\"thinking\"")
            push("modal_shown", "old", clear = true)
            prompt("old", "old-request", "Old request")
        }
        compose.runOnIdle {
            scope.launch { repo.authoritativeHostModals.collect { host.value = it } }
            vm =
                ThreadViewModel(
                    SavedStateHandle(mapOf("serverId" to "host", "conversationId" to "c")),
                    repo,
                    connection,
                    ComposerDraftStore(),
                    hostModal = host,
                    answerModal = { id, option, grant -> repo.answerModal(id, option, grant) },
                    cancelModal = repo::cancelModal,
                    recordModalAction = { action ->
                        repo.recordAuthoritativeModalAction(action)
                        host.value = host.value.reduce(action)
                    },
                )
        }
        val durable = authoritativeSnapshot(authoritativeItem(1, content = "{\"text\":\"Historical answered permission\"}"))
        var mounted by mutableStateOf(true)
        compose.setContent {
            val state by vm.state.collectAsState()
            val thinking by vm.isThinking.collectAsState()
            val modal by vm.currentModal.collectAsState()
            PyrycodeMobileTheme(darkTheme = true) {
                if (!mounted) return@PyrycodeMobileTheme
                ThreadScreen(
                    state.copy(authoritativeThread = projectAuthoritativeThread("host", "c", durable)),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    isThinking = thinking,
                    modalState = modal,
                    onModalOption = { id, option -> vm.onModalOption(option, id) },
                    onModalCancel = vm::onModalCancel,
                )
            }
        }
        compose.onNodeWithText("Old request").assertIsDisplayed()
        compose.runOnIdle { assertEquals(true, vm.isThinking.value) }
        compose.runOnIdle {
            push("turn_state", "new", clear = true)
            push("modal_shown", "new", clear = true)
            prompt("new", "new-request", "Current request")
            push("turn_state", "old", fields = "\"state\":\"thinking\"")
            prompt("old", "old-request", "Old request")
        }
        compose.onNodeWithText("Old request").assertDoesNotExist()
        compose.onNodeWithText("Current request").assertIsDisplayed()
        compose.runOnIdle { mounted = false }
        compose.runOnIdle { mounted = true }
        compose.onNodeWithText("Current request").assertIsDisplayed()
        compose.runOnIdle {
            vm.onModalOption("deny", "old-request")
            vm.onModalCancel("old-request")
            assertTrue(pump.sent.none { it.type == "modal_answer" || it.type == "modal_cancel" })
            assertEquals(false, vm.isThinking.value)
        }
        compose.onNodeWithText("Deny").performClick()
        compose.runOnIdle {
            val answer = pump.sent.single { it.type == "modal_answer" }
            assertTrue(answer.payload.toString().contains("new-request"))
            pump.push(Envelope(99, "ack", "2026-10-12T00:00:00Z", inReplyTo = answer.id))
        }
        compose.waitForIdle()
        compose.onNodeWithText("Historical answered permission").assertIsDisplayed()
        compose.onNodeWithText("Thinking…").assertDoesNotExist()
        compose.onNodeWithText("Current request").assertDoesNotExist()
        compose.runOnIdle {
            // Recollection and same-session repeated clear cannot replay an answered request.
            push("modal_shown", "new", clear = true)
            prompt("new", "new-request", "Current request")
        }
        compose.onNodeWithText("Current request").assertDoesNotExist()
    }

    private fun prompt(
        session: String,
        id: String,
        title: String,
    ) = push(
        "modal_shown",
        session,
        fields = (
            "\"modal_id\":\"$id\",\"class\":\"permission\",\"title\":\"$title\",\"prompt\":\"Proceed?\"," +
                "\"options\":[{\"id\":\"deny\",\"label\":\"Deny\"}],\"default_option_id\":\"deny\""
        ),
    )

    private fun push(
        type: String,
        session: String,
        clear: Boolean = false,
        fields: String = "",
    ) {
        val payload = if (clear) "{}" else "{\"conversation_id\":\"c\"${if (fields.isEmpty()) "" else ",$fields"}}"
        pump.push(
            MobileJson.decodeFromString(
                """
            {"id":1,"type":"$type","ts":"2026-10-12T00:00:00Z","conversation_id":"c",
            "session_id":"$session","session_state_cleared":$clear,
            "payload":$payload}
            """,
            ),
        )
    }

    private class Pump : SessionPump {
        private val channel = Channel<Envelope>(Channel.UNLIMITED)
        override val inbound = channel.receiveAsFlow()
        val sent = mutableListOf<Envelope>()

        override fun send(envelope: Envelope): Boolean {
            sent += envelope
            return true
        }

        fun push(envelope: Envelope) {
            channel.trySend(envelope)
        }
    }
}
