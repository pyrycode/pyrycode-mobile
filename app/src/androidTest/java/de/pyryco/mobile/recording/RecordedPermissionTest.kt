package de.pyryco.mobile.recording

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerStore
import de.pyryco.mobile.data.model.ModalUiState
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.InnerFrameV2
import de.pyryco.mobile.data.network.NoiseClientInfo
import de.pyryco.mobile.data.network.NoiseSessionFactory
import de.pyryco.mobile.data.network.NoiseSessionPump
import de.pyryco.mobile.data.network.OkHttpRelayTransport
import de.pyryco.mobile.data.network.PumpState
import de.pyryco.mobile.data.network.RelayTransport
import de.pyryco.mobile.data.network.TransportEvent
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.ManagedSessionPump
import de.pyryco.mobile.data.repository.RelayRepositoryCoordinator
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.e2e.PeerDeviceKeyStore
import de.pyryco.mobile.ui.conversations.thread.ComposerDraftStore
import de.pyryco.mobile.ui.conversations.thread.ThreadScreen
import de.pyryco.mobile.ui.conversations.thread.ThreadViewModel
import de.pyryco.mobile.ui.conversations.components.MESSAGE_BUBBLE_TEST_TAG
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The same production repository/coordinator/ViewModel/screen graph drives live capture and replay. */
@RunWith(AndroidJUnit4::class)
class RecordedPermissionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun capturedPermissionReachesTheClientAndCompletes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val live = args.getString("recordExchange") == "true"
        val replayName = args.getString("replayName")
        require(replayName == null || Regex("recorded-[a-f0-9]+\\.json").matches(replayName))
        val text = if (live) null else if (replayName != null) File(instrumentation.targetContext.filesDir, replayName).readText()
            else instrumentation.context.assets.open("recorded/permission-allow.json").bufferedReader().use { it.readText() }
        val recording = text?.let(ExchangeRecording::parse)
        val capture = ExchangeCapture()
        val replay = recording?.let(::RecordedExchange)
        val scenario = recording?.scenario
        val prompt = scenario?.getValue("prompt")?.jsonPrimitive?.content ?: PROMPT
        val reply = scenario?.getValue("expectedReply")?.jsonPrimitive?.content ?: REPLY
        val transport: RelayTransport
        val pump: ManagedSessionPump
        if (live) {
            fun arg(name: String) = requireNotNull(args.getString(name)) { "Missing capture argument" }
            val pairing = PairedServer(serverId = arg("serverId"), token = arg("token"), relayUrl = arg("relayUrl"), serverStaticPublicKey = arg("serverStaticPublicKey"))
            val info = NoiseClientInfo("mobile-recorded-tests", "1.0.999999")
            transport = OkHttpRelayTransport(pairing, info, OkHttpRelayTransport.defaultClient())
            val store = object : PairedServerStore {
                override suspend fun load() = pairing
                override suspend fun save(record: PairedServer): Unit = error("Read-only test pairing")
            }
            pump = CapturingPump(NoiseSessionPump(transport, NoiseSessionFactory(PeerDeviceKeyStore(pairing), store, info)), capture)
            runBlocking { withTimeout(20000) { transport.connect(); check(transport.events.first() is TransportEvent.Up) } }
        } else {
            transport = UnusedTransport()
            pump = ReplayingPump(checkNotNull(recording), checkNotNull(replay))
        }
        val coordinator = RelayRepositoryCoordinator(MutableStateFlow(transport), MutableStateFlow(RelayLinkStatus.Connected), { pump })
        val viewModels = ViewModelStore()
        try {
            coordinator.start()
            val repository = runBlocking {
                withTimeout(20000) {
                    check(pump.state.first { it !is PumpState.Handshaking } is PumpState.Open)
                    coordinator.currentRepository.filterNotNull().first()
                }
            }
            val conversation = runBlocking { withTimeout(20000) { repository.createDiscussion(null) } }
            val vm = ThreadViewModel(
                SavedStateHandle(mapOf("conversationId" to conversation.id)), repository, FakeConnectionStateSource(), ComposerDraftStore(),
                liveSessionEvents = coordinator.liveSessionEvents, hostModal = coordinator.hostModals,
                answerModal = coordinator::answerModal, cancelModal = coordinator::cancelModal,
            )
            viewModels.put("recorded", vm)
            rule.setContent {
                val state by vm.state.collectAsState()
                val modal by vm.currentModal.collectAsState()
                val armed by vm.armedOptionId.collectAsState()
                val draft by vm.draft.collectAsState()
                PyrycodeMobileTheme(darkTheme = true) {
                    ThreadScreen(
                        state = state, onBack = {}, onSendMessage = vm::sendMessage,
                        draft = draft, onDraftChange = vm::onDraftChange,
                        connectionState = de.pyryco.mobile.data.model.ConnectionState.Connected, onRetry = {},
                        modalState = modal, armedOptionId = armed, onModalOption = vm::onModalOption, onModalCancel = vm::onModalCancel,
                    )
                }
            }
            rule.waitForIdle()
            rule.onNode(hasSetTextAction()).performTextInput(prompt)
            rule.onNodeWithContentDescription("Send").performClick()
            rule.waitUntil(120000) { vm.currentModal.value is ModalUiState.Open }
            val shown = vm.currentModal.value as ModalUiState.Open
            val allow = shown.options.single { it.id == "allow_once" }.label
            rule.onNodeWithText(allow).performScrollTo().performClick()
            rule.waitUntil(10000) { vm.armedOptionId.value == "allow_once" }
            val replyNode = hasText(reply) and hasAnyAncestor(hasTestTag(MESSAGE_BUBBLE_TEST_TAG))
            assertFalse("Reply arrived before permission confirmation", rule.onAllNodes(replyNode, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
            if (live) stage(instrumentation.targetContext.filesDir, "armed")
            rule.onNodeWithText(allow).performScrollTo().performClick()
            rule.waitUntil(10000) { vm.currentModal.value !is ModalUiState.Open }
            rule.waitUntil(120000) { rule.onNode(replyNode, useUnmergedTree = true).isDisplayed() }
            rule.onNode(replyNode, useUnmergedTree = true).assertIsDisplayed()
            rule.waitUntil(20000) {
                vm.state.value.items.filterIsInstance<ThreadItem.MessageItem>().any {
                    it.message.role == Role.Assistant && it.message.content.trim() == reply && !it.message.isStreaming
                }
            }
            rule.waitUntil(20000) { !vm.isBusy.value }
            rule.waitForIdle()
            if (live) {
                val name = requireNotNull(args.getString("recordingName"))
                require(Regex("recorded-[a-f0-9]+\\.json").matches(name))
                val wire = capture.take()
                val result = buildJsonObject {
                    put("wire", wire)
                    put("capabilities", JsonArray((pump.state.value as PumpState.Open).capabilities.sorted().map(::JsonPrimitive)))
                    put("scenario", buildJsonObject { put("prompt", prompt); put("expectedReply", reply); put("allowLabel", allow) })
                }
                val file = File(instrumentation.targetContext.filesDir, name)
                java.nio.file.Files.write(file.toPath(), result.toString().toByteArray(), java.nio.file.StandardOpenOption.CREATE_NEW)
                stage(instrumentation.targetContext.filesDir, "completed")
            } else replay!!.assertComplete()
        } finally {
            rule.runOnUiThread { viewModels.clear() }
            coordinator.close()
            pump.close()
            transport.close()
        }
    }

    private fun stage(directory: File, value: String) {
        val name = requireNotNull(InstrumentationRegistry.getArguments().getString("recordingName"))
        File(directory, "$name.$value").createNewFile()
        if (value == "armed") {
            val resume = File(directory, "$name.confirm")
            rule.waitUntil(20000) { resume.exists() }
        }
    }

    companion object {
        const val PROMPT = "Use the Bash tool to run exactly `touch mobile-recorded-witness.txt`. Do not use another tool. After it completes, reply with exactly MOBILE_RECORDED_PERMISSION_COMPLETE."
        const val REPLY = "MOBILE_RECORDED_PERMISSION_COMPLETE"
    }
}

private class CapturingPump(private val delegate: ManagedSessionPump, private val capture: ExchangeCapture) : ManagedSessionPump by delegate {
    override val inbound = delegate.inbound.onEach { capture.observe("daemon", it) }
    override fun send(envelope: Envelope): Boolean {
        capture.observe("client", envelope)
        return delegate.send(envelope)
    }
}

private class ReplayingPump(recording: ExchangeRecording, private val exchange: RecordedExchange) : ManagedSessionPump {
    override val state = MutableStateFlow<PumpState>(PumpState.Open("recorded", recording.capabilities))
    private val frames = Channel<Envelope>(Channel.UNLIMITED)
    override val inbound = frames.receiveAsFlow()
    override fun start() { exchange.initial().forEach { check(frames.trySend(it).isSuccess) } }
    override fun send(envelope: Envelope): Boolean =
        try {
            exchange.reply(envelope).forEach { check(frames.trySend(it).isSuccess) }
            true
        } catch (_: IllegalStateException) {
            false
        }
    override fun close() { frames.close(); state.value = PumpState.Closed(null) }
}

private class UnusedTransport : RelayTransport {
    override val inbound = emptyFlow<InnerFrameV2>()
    override val events = emptyFlow<TransportEvent>()
    override fun connect(): Unit = error("Replay cannot connect")
    override fun send(frame: InnerFrameV2): Boolean = error("Replay cannot send transport frames")
    override fun close() = Unit
}
