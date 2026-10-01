package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.SetSessionSettingsPayloadDto
import de.pyryco.mobile.data.repository.AttachmentUploadResult
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.EffectiveEffort
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.SessionSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #1319: a tap that races a disconnect sends nothing. Nothing here collects [ThreadViewModel.connectionState],
 * so each case proves the tap-time check reads the live source, not a `WhileSubscribed` initial value.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelConnectionGateTest {
    private val logs = mutableListOf<String>()
    private val oldSink = RelayLog.sink
    private val oldEnabled = RelayLog.enabled
    private val source = FakeConnectionStateSource()
    private val draftStore = ComposerDraftStore()
    private val repo = RecordingRepo()
    private var interrupts = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        RelayLog.enabled = true
        RelayLog.sink = { _, _, message -> logs += message }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        RelayLog.sink = oldSink
        RelayLog.enabled = oldEnabled
    }

    @Test
    fun send_whileOffline_sendsNothing_andKeepsTheDraft() =
        runTest {
            val vm = collectedVm()
            vm.onDraftChange("hello")

            source.emit(ConnectionState.Offline)
            vm.sendMessage("hello")

            assertTrue(repo.sent.isEmpty())
            assertEquals("hello", draftStore.draftFor(SERVER, CONV))
            assertTrue("event=thread_action_skipped action=send reason=not_connected" in logs)
        }

    @Test
    fun send_withAttachments_whileConnecting_uploadsNothing_andKeepsThem() =
        runTest {
            val vm = collectedVm()
            vm.onDraftChange("look")
            draftStore.addAttachment(SERVER, CONV, "content://a", "a.png", "image/png", 10)

            source.emit(ConnectionState.Connecting)
            vm.sendMessage("look")

            assertEquals(0, repo.uploads)
            assertTrue(repo.sent.isEmpty())
            assertFalse(vm.attachmentsSending.value)
            assertEquals(1, draftStore.attachmentsFor(SERVER, CONV).size)
            assertEquals("look", draftStore.draftFor(SERVER, CONV))
        }

    @Test
    fun send_afterReconnect_sends() =
        runTest {
            val vm = collectedVm()
            vm.onDraftChange("hello")
            source.emit(ConnectionState.Offline)
            source.emit(ConnectionState.Connected)

            vm.sendMessage("hello")

            assertEquals(listOf("hello"), repo.sent)
            assertEquals("", draftStore.draftFor(SERVER, CONV))
        }

    @Test
    fun interrupt_whileOffline_sendsNothing() =
        runTest {
            val vm = collectedVm()

            source.emit(ConnectionState.Offline)
            vm.onInterrupt()

            assertEquals(0, interrupts)
        }

    @Test
    fun interrupt_whileConnected_sends() =
        runTest {
            val vm = collectedVm()

            vm.onInterrupt()

            assertEquals(1, interrupts)
        }

    @Test
    fun composerCommand_whileOffline_sendsNothing() =
        runTest {
            val vm = collectedVm()

            source.emit(ConnectionState.Reconnecting(secondsRemaining = 3))
            vm.onComposerCommand(ComposerAction.CompactSession)

            assertTrue(repo.sent.isEmpty())
        }

    @Test
    fun runSettings_whileOffline_writeNothing_andLeaveTheRunConfigurationUnchanged() =
        runTest {
            val vm = collectedVm()
            val before = vm.state.value.runConfig
            assertTrue("the fixture must be writable", before.writable)

            source.emit(ConnectionState.Offline)
            vm.onModelSelected("opus")
            vm.onEffortSelected("high")
            vm.onPermissionModeSelected("acceptEdits")
            runCurrent()

            assertTrue(repo.settings.isEmpty())
            assertEquals(before, vm.state.value.runConfig)
            assertFalse(vm.state.value.runConfig.pending)
            assertNull(vm.state.value.runConfig.pendingPermission)
        }

    @Test
    fun runSettings_whileConnected_write() =
        runTest {
            val vm = collectedVm()

            vm.onModelSelected("opus")
            runCurrent()

            assertEquals(listOf(SetSessionSettingsPayloadDto(SESSION, model = "opus")), repo.settings)
        }

    // ---- fixtures -------------------------------------------------------------------------------

    private fun TestScope.collectedVm(): ThreadViewModel {
        val vm =
            ThreadViewModel(
                SavedStateHandle(mapOf("serverId" to SERVER, "conversationId" to CONV)),
                repo,
                source,
                draftStore,
                interrupt = { interrupts++ },
                attachmentReader = AttachmentReader { AttachmentRead.Bytes(ByteArray(1)) },
            )
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        return vm
    }

    private class RecordingRepo(
        val fake: FakeConversationRepository = FakeConversationRepository(),
    ) : ConversationRepository by fake {
        val sent = mutableListOf<String>()
        val settings = mutableListOf<SetSessionSettingsPayloadDto>()
        var uploads = 0
        private val readings =
            MutableStateFlow<SessionSettings?>(
                SessionSettings(
                    sessionId = SESSION,
                    model = "",
                    effort = "",
                    effectiveEffort = EffectiveEffort.Unavailable,
                    permissionMode = "plan",
                    yolo = false,
                    usedTokens = 0,
                    windowTokens = 0,
                ),
            )

        override fun observeSessionSettings(conversationId: String): Flow<SessionSettings?> = readings

        override suspend fun sendMessage(
            conversationId: String,
            text: String,
        ): Message {
            sent += text
            return fake.sendMessage(conversationId, text)
        }

        override suspend fun sendMessage(
            conversationId: String,
            text: String,
            attachments: List<MessageAttachment>,
        ): Message {
            sent += text
            return fake.sendMessage(conversationId, text)
        }

        override suspend fun uploadAttachment(
            conversationId: String,
            bytes: ByteArray,
            filename: String,
            mimeType: String,
            onProgress: (sentChunks: Int, totalChunks: Int) -> Unit,
        ): AttachmentUploadResult {
            uploads++
            return AttachmentUploadResult.Stored("att-1")
        }

        override suspend fun setSessionSettings(
            sessionId: String,
            model: String?,
            effort: String?,
            yolo: Boolean?,
            permissionMode: String?,
        ) {
            settings += SetSessionSettingsPayloadDto(sessionId, model, effort, yolo, permissionMode)
        }
    }

    private companion object {
        const val SERVER = "host-a"
        const val CONV = "seed-channel-personal"
        const val SESSION = "sess-a"
    }
}
