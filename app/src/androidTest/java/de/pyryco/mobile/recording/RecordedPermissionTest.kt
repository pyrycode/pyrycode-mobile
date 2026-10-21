package de.pyryco.mobile.recording

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerStore
import de.pyryco.mobile.data.model.ModalUiState
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.CAPABILITY_THREAD
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
import de.pyryco.mobile.data.repository.StableConversationRepository
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.e2e.PeerDeviceKeyStore
import de.pyryco.mobile.ui.conversations.components.MESSAGE_BUBBLE_TEST_TAG
import de.pyryco.mobile.ui.conversations.thread.ComposerDraftStore
import de.pyryco.mobile.ui.conversations.thread.ThreadScreen
import de.pyryco.mobile.ui.conversations.thread.ThreadViewModel
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
        val text =
            if (live) {
                null
            } else if (replayName != null) {
                File(instrumentation.targetContext.filesDir, replayName).readText()
            } else {
                instrumentation.context.assets
                    .open("recorded/permission-allow.json")
                    .bufferedReader()
                    .use { it.readText() }
            }
        val recording = text?.let(ExchangeRecording::parse)
        val capture = ExchangeCapture()
        val replay = recording?.let(::RecordedExchange)
        val scenario = recording?.scenario
        val prompt = scenario?.getValue("prompt")?.jsonPrimitive?.content ?: PROMPT
        val reply = scenario?.getValue("expectedReply")?.jsonPrimitive?.content ?: REPLY
        val transport: RelayTransport
        val pump: ManagedSessionPump
        val hostId: String
        val deviceId: String
        if (live) {
            fun arg(name: String) = requireNotNull(args.getString(name)) { "Missing capture argument" }
            val pairing =
                PairedServer(
                    serverId = arg("serverId"),
                    token = arg("token"),
                    relayUrl = arg("relayUrl"),
                    serverStaticPublicKey = arg("serverStaticPublicKey"),
                )
            hostId = pairing.serverId
            val keys = PeerDeviceKeyStore(pairing)
            deviceId = runBlocking { keys.publicKey(hostId).joinToString("") { "%02x".format(it.toInt() and 0xff) } }
            val info = NoiseClientInfo("mobile-recorded-tests", "1.0.999999")
            transport = OkHttpRelayTransport(pairing, info, OkHttpRelayTransport.defaultClient())
            val store =
                object : PairedServerStore {
                    override suspend fun load() = pairing

                    override suspend fun save(record: PairedServer): Unit = error("Read-only test pairing")
                }
            pump = CapturingPump(NoiseSessionPump(transport, NoiseSessionFactory(keys, store, info)), capture)
            runBlocking {
                withTimeout(20000) {
                    transport.connect()
                    check(transport.events.first() is TransportEvent.Up)
                }
            }
        } else {
            require(
                CAPABILITY_THREAD in checkNotNull(recording).capabilities,
            ) { "Permission recording requires authoritative thread support" }
            hostId =
                recording.scenario
                    .getValue("serverId")
                    .jsonPrimitive.content
            deviceId =
                checkNotNull(replay).reboundIdentity(
                    recording.scenario
                        .getValue("deviceId")
                        .jsonPrimitive.content,
                )
            transport = UnusedTransport()
            pump = ReplayingPump(checkNotNull(recording), checkNotNull(replay))
        }
        val coordinator =
            RelayRepositoryCoordinator(
                MutableStateFlow(transport),
                MutableStateFlow(RelayLinkStatus.Connected),
                { pump },
                authoritativeHostId = hostId,
                authoritativeDeviceId = { host ->
                    check(host == hostId) { "Permission device requested for another host" }
                    deviceId
                },
            )
        val viewModels = ViewModelStore()
        try {
            coordinator.start()
            runBlocking {
                withTimeout(20000) {
                    check(pump.state.first { it !is PumpState.Handshaking } is PumpState.Open)
                    check(CAPABILITY_THREAD in (pump.state.value as PumpState.Open).capabilities)
                    coordinator.currentRepository.filterNotNull().first()
                }
            }
            val repository =
                StableConversationRepository(
                    coordinator.currentRepository,
                    coordinator.hostReadings,
                    negotiatedThread = coordinator.negotiatedThread,
                )
            val conversation = runBlocking { withTimeout(20000) { repository.createDiscussion(null) } }
            val vm =
                ThreadViewModel(
                    SavedStateHandle(mapOf("serverId" to hostId, "conversationId" to conversation.id)),
                    repository,
                    FakeConnectionStateSource(),
                    ComposerDraftStore(),
                    liveSessionEvents = coordinator.liveSessionEvents,
                    hostModal = coordinator.hostModals,
                    answerModal = coordinator::answerModal,
                    cancelModal = coordinator::cancelModal,
                )
            viewModels.put("recorded", vm)
            rule.setContent {
                val state by vm.state.collectAsState()
                val modal by vm.currentModal.collectAsState()
                val armed by vm.armedOptionId.collectAsState()
                val draft by vm.draft.collectAsState()
                val busy by vm.isBusy.collectAsState()
                PyrycodeMobileTheme(darkTheme = true) {
                    ThreadScreen(
                        state = state,
                        onBack = {},
                        onSendMessage = vm::sendMessage,
                        draft = draft,
                        onDraftChange = vm::onDraftChange,
                        connectionState = de.pyryco.mobile.data.model.ConnectionState.Connected,
                        onRetry = {},
                        isBusy = busy,
                        modalState = modal,
                        armedOptionId = armed,
                        onModalOption = { modalId, optionId ->
                            android.util.Log.i("RecordedExchange", "stage=permission-tapped")
                            vm.onModalOption(optionId, modalId)
                        },
                        onModalCancel = vm::onModalCancel,
                    )
                }
            }
            rule.waitForIdle()
            rule.onNode(hasSetTextAction()).performTextInput(prompt)
            rule
                .onNodeWithContentDescription(
                    instrumentation.targetContext.getString(de.pyryco.mobile.R.string.cd_send_message),
                ).performClick()
            android.util.Log.i("RecordedExchange", "stage=prompt-sent")
            rule.waitUntil(120000) { vm.currentModal.value is ModalUiState.Open }
            androidx.test.espresso.Espresso
                .closeSoftKeyboard()
            rule.waitForIdle()
            android.util.Log.i("RecordedExchange", "stage=permission-shown")
            val shown = vm.currentModal.value as ModalUiState.Open
            val allow = shown.options.single { it.id == "allow_once" }.label
            val choice = hasText(allow) and hasClickAction() and hasAnyAncestor(hasTestTag("permission-request-card"))

            fun tapAllow() {
                rule.onNode(hasScrollToNodeAction()).performScrollToNode(choice)
                rule
                    .onNode(choice)
                    .performScrollTo()
                    .assertIsDisplayed()
                    .assertIsEnabled()
                // The list draws behind the composer. Its scroll semantics alone can expose a
                // button geometrically while the composer still intercepts the pointer.
                val composerTop =
                    rule
                        .onNode(hasTestTag("thread-composer"))
                        .fetchSemanticsNode()
                        .boundsInRoot.top
                val overlap =
                    rule
                        .onNode(choice)
                        .fetchSemanticsNode()
                        .boundsInRoot.bottom - composerTop + 24f
                if (overlap > 0f) {
                    rule.onNode(hasScrollToNodeAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -overlap) }
                }
                check(
                    rule
                        .onNode(choice)
                        .fetchSemanticsNode()
                        .boundsInRoot.bottom < composerTop,
                ) {
                    "Permission choice remains behind the composer"
                }
                rule.onNode(choice).performTouchInput { click(center) }
            }
            tapAllow()
            rule.waitUntil(10000) { vm.armedOptionId.value == "allow_once" }
            android.util.Log.i("RecordedExchange", "stage=allow-armed")
            val replyNode = hasText(reply) and hasAnyAncestor(hasTestTag(MESSAGE_BUBBLE_TEST_TAG))
            assertFalse(
                "Reply arrived before permission confirmation",
                rule.onAllNodes(replyNode, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
            )
            if (live) stage(instrumentation.targetContext.filesDir, "armed")
            tapAllow()
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
                val result =
                    buildJsonObject {
                        put("wire", wire)
                        put("capabilities", JsonArray((pump.state.value as PumpState.Open).capabilities.sorted().map(::JsonPrimitive)))
                        put(
                            "scenario",
                            buildJsonObject {
                                put("prompt", prompt)
                                put("expectedReply", reply)
                                put("allowLabel", allow)
                                put("serverId", hostId)
                                put("deviceId", deviceId)
                            },
                        )
                    }
                val file = File(instrumentation.targetContext.filesDir, name)
                java.nio.file.Files
                    .write(file.toPath(), result.toString().toByteArray(), java.nio.file.StandardOpenOption.CREATE_NEW)
                stage(instrumentation.targetContext.filesDir, "completed")
            } else {
                replay!!.assertComplete()
            }
        } finally {
            rule.runOnUiThread { viewModels.clear() }
            coordinator.close()
            pump.close()
            transport.close()
        }
    }

    private fun stage(
        directory: File,
        value: String,
    ) {
        val name = requireNotNull(InstrumentationRegistry.getArguments().getString("recordingName"))
        File(directory, "$name.$value").createNewFile()
        if (value == "armed") {
            val resume = File(directory, "$name.confirm")
            rule.waitUntil(20000) { resume.exists() }
        }
    }

    companion object {
        const val PROMPT =
            "Use the Bash tool to run exactly `touch mobile-recorded-witness.txt`. " +
                "Do not use another tool. After it completes, reply with exactly MOBILE_RECORDED_PERMISSION_COMPLETE."
        const val REPLY = "MOBILE_RECORDED_PERMISSION_COMPLETE"
    }
}

private class CapturingPump(
    private val delegate: ManagedSessionPump,
    private val capture: ExchangeCapture,
) : ManagedSessionPump by delegate {
    override val inbound = delegate.inbound.onEach { capture.observe("daemon", it) }

    override fun send(envelope: Envelope): Boolean {
        capture.observe("client", envelope)
        return delegate.send(envelope)
    }
}

private class ReplayingPump(
    recording: ExchangeRecording,
    private val exchange: RecordedExchange,
) : ManagedSessionPump {
    override val state = MutableStateFlow<PumpState>(PumpState.Open("recorded", recording.capabilities))
    private val frames = Channel<Envelope>(Channel.UNLIMITED)
    override val inbound = frames.receiveAsFlow()

    override fun start() {
        exchange.initial().forEach { check(frames.trySend(it).isSuccess) }
    }

    override fun send(envelope: Envelope): Boolean =
        try {
            exchange.reply(envelope).forEach { check(frames.trySend(it).isSuccess) }
            true
        } catch (_: IllegalStateException) {
            false
        }

    override fun close() {
        frames.close()
        state.value = PumpState.Closed(null)
    }
}

private class UnusedTransport : RelayTransport {
    override val inbound = emptyFlow<InnerFrameV2>()
    override val events = emptyFlow<TransportEvent>()

    override fun connect(): Unit = error("Replay cannot connect")

    override fun send(frame: InnerFrameV2): Boolean = error("Replay cannot send transport frames")

    override fun close() = Unit
}
