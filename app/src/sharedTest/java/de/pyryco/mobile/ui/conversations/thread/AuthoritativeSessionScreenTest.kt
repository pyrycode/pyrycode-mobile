package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.HostModalState
import de.pyryco.mobile.data.model.reduce
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.repository.AuthoritativeLiveProgress
import de.pyryco.mobile.data.repository.ConnectionStateSource
import de.pyryco.mobile.data.repository.RemoteConversationRepository
import de.pyryco.mobile.data.repository.SessionPump
import de.pyryco.mobile.ui.conversations.components.TOOL_ELAPSED_TAG
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
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pump = Pump()
    private var repo =
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
    private val viewModels = androidx.lifecycle.ViewModelStore()

    @After fun close() {
        viewModels.clear()
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
            mountViewModel()
        }
        val answered =
            listOf(authoritativeAnsweredPrompt(1, "answered-permission", false), authoritativeAnsweredPrompt(2, "answered-question", true))
        answered.forEach(::item)
        var mounted by mutableStateOf(true)
        var readingVm by mutableStateOf(vm)
        compose.setContent {
            val durable by repo.authoritativeThreads.observe("host", "c").collectAsState(initial = authoritativeSnapshot())
            val state by readingVm.state.collectAsState()
            val thinking by readingVm.isThinking.collectAsState()
            val modal by readingVm.currentModal.collectAsState()
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
        compose.onNodeWithText("Prompt answered: allow").assertExists()
        compose.onNodeWithText("Prompt answered: answer").assertExists()
        compose.onNodeWithText("Thinking…").assertDoesNotExist()
        compose.onNodeWithText("Current request").assertDoesNotExist()
        compose.runOnIdle {
            // Recollection and same-session repeated clear cannot replay an answered request.
            push("modal_shown", "new", clear = true)
            prompt("new", "new-request", "Current request")
        }
        compose.onNodeWithText("Current request").assertDoesNotExist()
        compose.runOnIdle {
            mounted = false
            viewModels.clear()
            scope.cancel()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            pump = Pump()
            repo =
                RemoteConversationRepository(
                    pump,
                    scope,
                    negotiatedCapabilities = { setOf("thread", "interactive") },
                    authoritativeHostId = "host",
                )
            host.value = HostModalState()
            mountViewModel()
            readingVm = vm
            repeat(2) { answered.forEach(::item) }
            mounted = true
        }
        compose.onNodeWithText("Current request").assertDoesNotExist()
        compose.onNodeWithText("Prompt answered: allow").assertExists()
        compose.onNodeWithText("Prompt answered: answer").assertExists()
        compose.runOnIdle {
            assertTrue(host.value.outstanding.isEmpty())
            assertTrue(pump.sent.none { it.type in setOf("modal_answer", "question_answer", "question_refused") })
            push("modal_shown", "new", clear = true)
            prompt("new", "reconnected-request", "Outstanding after reconnect")
        }
        compose.onNodeWithText("Outstanding after reconnect").assertIsDisplayed()
        compose.runOnIdle {
            vm.onModalOption("deny", "new-request")
            assertTrue(pump.sent.none { it.type == "modal_answer" })
        }
        compose.onNodeWithText("Deny").performClick()
        compose.runOnIdle {
            val answer = pump.sent.single { it.type == "modal_answer" }
            assertTrue(answer.payload.toString().contains("reconnected-request"))
            pump.push(Envelope(100, "ack", "2026-10-12T00:00:00Z", inReplyTo = answer.id))
        }
    }

    private fun mountViewModel() {
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
        viewModels.put("thread", vm)
    }

    @Test fun suppliedItemProgressIsVisibleWithoutLegacyRosterAndRetiresByFamilyAndSession() {
        val items =
            listOf(
                authoritativeItem(
                    1,
                    "tool_call",
                    active = true,
                    status = "running",
                    session = "old",
                    content = """{"name":"Read","tool_use_id":"tool","input_summary":"file"}""",
                ),
                authoritativeAgent(2, task = "task", session = "old"),
            )
        items.forEach(::item)
        compose.setContent {
            val snapshot by repo.authoritativeThreads.observe("host", "c").collectAsState(initial = authoritativeSnapshot())
            val progress by repo.observeAuthoritativeProgress("c").collectAsState(initial = AuthoritativeLiveProgress())
            PyrycodeMobileTheme(darkTheme = true) {
                ThreadScreen(
                    ThreadUiState("c", "Channel", authoritativeThread = projectAuthoritativeThread("host", "c", snapshot, progress)),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                )
            }
        }
        compose.onNodeWithTag(TOOL_ELAPSED_TAG, useUnmergedTree = true).assertDoesNotExist()

        fun fresh(session: String) {
            push("tool_progress", session, fields = "\"turn_id\":\"turn\",\"tool_use_id\":\"tool\",\"elapsed_seconds\":7")
            push(
                "background_task_progress",
                session,
                fields = """"task_id":"task","description":"Working now","subagent_type":"general",
                "last_tool_name":"Read","total_tokens":100,"tool_uses":1,"duration_ms":200,"truncated_fields":null""",
            )
        }
        compose.runOnIdle {
            push("tool_progress", "old", clear = true)
            push("background_task_progress", "old", clear = true)
            fresh("old")
        }
        compose.onNodeWithTag(TOOL_ELAPSED_TAG, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("1 task running").performClick()
        compose.onNodeWithText("Working now").assertIsDisplayed()
        compose.runOnIdle {
            push("tool_progress", "old", clear = true)
            push("background_task_progress", "old", clear = true)
        }
        compose.onNodeWithText("Working now").assertIsDisplayed()
        compose.runOnIdle {
            push("tool_progress", "new", clear = true)
            fresh("old")
        }
        compose.onNodeWithText("Working now").assertDoesNotExist()
        compose.runOnIdle { fresh("new") }
        compose.onNodeWithText("Working now").assertDoesNotExist()
        compose.runOnIdle {
            val held = repo.authoritativeThreads.current("host", "c")
            assertEquals(items.map { it.raw }, held.items.map { it.raw })
            assertEquals(0L, held.checkpoint)
            assertTrue(pump.sent.isEmpty())
        }
    }

    private fun item(item: ThreadItemDto) {
        pump.push(
            MobileJson.decodeFromString(
                """{"id":1,"type":"thread_item_added","ts":"2026-10-12T00:00:00Z",
            "payload":{"conversation_id":"c","epoch":"epoch","version":${item.rev},"item":${item.raw}}}""",
            ),
        )
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
