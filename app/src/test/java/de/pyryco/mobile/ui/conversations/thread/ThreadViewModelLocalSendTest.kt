package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.RelayErrorException
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.AttachmentUploadResult
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #1311: the local-send window — "Thinking…" from the moment a send is handed to the daemon until the
 * first `turn_state` for this conversation, as desktop's `localSendPending` does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelLocalSendTest {
    private val uncaught = mutableListOf<Throwable>()
    private val oldHandler = Thread.getDefaultUncaughtExceptionHandler()

    // RelayLog's default sink is android.util.Log, which throws on the plain JVM.
    private val logs = mutableListOf<String>()
    private val oldSink = RelayLog.sink
    private val oldEnabled = RelayLog.enabled

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        RelayLog.enabled = true
        RelayLog.sink = { _, _, message -> logs += message }
        // A throw escaping viewModelScope reaches the default handler, not runTest.
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        RelayLog.sink = oldSink
        RelayLog.enabled = oldEnabled
        Thread.setDefaultUncaughtExceptionHandler(oldHandler)
        assertTrue("nothing may escape the send: $uncaught", uncaught.isEmpty())
    }

    /** Both sends suspend on [gate] and then throw [failure] or succeed; uploads return [uploadOutcome]. */
    private class GatedRepository(
        private val gate: CompletableDeferred<Unit> = CompletableDeferred(Unit),
        private val failure: Throwable? = null,
        private val uploadOutcome: AttachmentUploadResult = AttachmentUploadResult.Stored("att-1"),
    ) : ConversationRepository by FakeConversationRepository() {
        var sends = 0
        var uploads = 0

        override suspend fun sendMessage(
            conversationId: String,
            text: String,
        ): Message = send(text)

        override suspend fun sendMessage(
            conversationId: String,
            text: String,
            attachments: List<MessageAttachment>,
        ): Message = send(text)

        override suspend fun uploadAttachment(
            conversationId: String,
            bytes: ByteArray,
            filename: String,
            mimeType: String,
            onProgress: (sentChunks: Int, totalChunks: Int) -> Unit,
        ): AttachmentUploadResult {
            uploads++
            return uploadOutcome
        }

        private suspend fun send(text: String): Message {
            sends++
            gate.await()
            failure?.let { throw it }
            return Message(
                id = "m$sends",
                sessionId = "s1",
                role = Role.User,
                content = text,
                timestamp = Instant.parse("2026-10-01T00:00:00Z"),
                isStreaming = false,
            )
        }
    }

    private object BytesReader : AttachmentReader {
        override suspend fun read(uri: String): AttachmentRead = AttachmentRead.Bytes("bytes".toByteArray())
    }

    private fun vm(
        repository: ConversationRepository,
        events: Flow<LiveSessionEvent> = MutableSharedFlow(),
        repositoryAvailable: Flow<Boolean> = flowOf(true),
        store: ComposerDraftStore = ComposerDraftStore(),
    ) = ThreadViewModel(
        SavedStateHandle(mapOf("serverId" to HOST, "conversationId" to CONV)),
        repository,
        FakeConnectionStateSource(),
        store,
        liveSessionEvents = events,
        repositoryAvailable = repositoryAvailable,
        attachmentReader = BytesReader,
    )

    private fun turnState(
        phase: LiveSessionEvent.TurnState.Phase,
        conversationId: String = CONV,
    ) = LiveSessionEvent.TurnState(conversationId, phase)

    @Test
    fun opensWhenTheSendIsHandedToTheDaemon_andStaysOpenAfterItIsAccepted() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val vm = vm(GatedRepository(gate))
            assertFalse(vm.localSendPending.value)

            vm.sendMessage("hello")
            advanceUntilIdle()
            assertTrue("open while the send is in flight", vm.localSendPending.value)

            gate.complete(Unit)
            advanceUntilIdle()
            assertTrue("still open until the daemon's first turn_state", vm.localSendPending.value)
        }

    @Test
    fun anyTurnStateForThisConversation_closesIt() =
        runTest {
            for (phase in LiveSessionEvent.TurnState.Phase.entries) {
                val events = MutableSharedFlow<LiveSessionEvent>()
                val vm = vm(GatedRepository(), events)
                vm.sendMessage("hello")
                advanceUntilIdle()
                assertTrue(vm.localSendPending.value)

                events.emit(turnState(phase))
                advanceUntilIdle()

                assertFalse("turn_state $phase must close the window", vm.localSendPending.value)
            }
            // Static codes only: the message text never reaches the log.
            assertTrue(logs.contains("event=local_send_window state=open"))
            assertTrue(logs.contains("event=local_send_window state=closed reason=turn_state"))
            assertTrue(logs.none { "hello" in it })
        }

    @Test
    fun anotherConversationsTurnState_leavesItOpen() =
        runTest {
            val events = MutableSharedFlow<LiveSessionEvent>()
            val vm = vm(GatedRepository(), events)
            vm.sendMessage("hello")
            advanceUntilIdle()

            events.emit(turnState(LiveSessionEvent.TurnState.Phase.Thinking, conversationId = "someone-else"))
            events.emit(LiveSessionEvent.AssistantDelta(CONV, "t1", 0, "hi"))
            advanceUntilIdle()

            assertTrue(vm.localSendPending.value)
        }

    @Test
    fun aFailedSend_closesIt() =
        runTest {
            val failures =
                listOf(
                    IllegalStateException("not connected"),
                    RelayErrorException(code = "server.error", retryable = false, message = "no"),
                    UnsupportedOperationException("not wired"),
                )
            for (failure in failures) {
                val gate = CompletableDeferred<Unit>()
                val vm = vm(GatedRepository(gate, failure))
                vm.sendMessage("hello")
                advanceUntilIdle()
                assertTrue(vm.localSendPending.value)

                gate.complete(Unit)
                advanceUntilIdle()

                assertFalse("a send refused by $failure must close the window", vm.localSendPending.value)
            }
        }

    @Test
    fun aReconnect_closesIt() =
        runTest {
            val available = MutableStateFlow(true)
            val vm = vm(GatedRepository(), repositoryAvailable = available)
            vm.sendMessage("hello")
            advanceUntilIdle()
            assertTrue(vm.localSendPending.value)

            available.value = false
            available.value = true
            advanceUntilIdle()

            assertFalse(vm.localSendPending.value)
        }

    @Test
    fun theConnectionTheThreadOpenedOn_doesNotCloseIt() =
        runTest {
            val available = MutableStateFlow(true)
            val vm = vm(GatedRepository(), repositoryAvailable = available)
            advanceUntilIdle()

            vm.sendMessage("hello")
            advanceUntilIdle()

            assertTrue(vm.localSendPending.value)
        }

    @Test
    fun aBlankSend_neverOpensIt() =
        runTest {
            val repo = GatedRepository()
            val vm = vm(repo)

            vm.sendMessage("   ")
            advanceUntilIdle()

            assertEquals(0, repo.sends)
            assertFalse(vm.localSendPending.value)
        }

    @Test
    fun aSendRefusedWhileAttachmentsAreSending_neverOpensIt() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val events = MutableSharedFlow<LiveSessionEvent>()
            val repo = GatedRepository(gate)
            val vm = vm(repo, events)
            vm.addAttachment("content://docs/a", "a.txt", "text/plain", 5L)
            vm.sendMessage("with a file")
            advanceUntilIdle()
            assertTrue(vm.attachmentsSending.value)
            // Close the window the first send opened, so only the refused second send could reopen it.
            events.emit(turnState(LiveSessionEvent.TurnState.Phase.Idle))
            advanceUntilIdle()
            assertFalse(vm.localSendPending.value)

            vm.sendMessage("again")
            advanceUntilIdle()

            assertEquals(1, repo.sends)
            assertFalse(vm.localSendPending.value)
        }

    @Test
    fun anAttachmentSend_opensIt() =
        runTest {
            val vm = vm(GatedRepository())
            vm.addAttachment("content://docs/a", "a.txt", "text/plain", 5L)

            vm.sendMessage("with a file")
            advanceUntilIdle()

            assertTrue(vm.localSendPending.value)
        }

    @Test
    fun aFailedUpload_neverOpensIt() =
        runTest {
            val repo = GatedRepository(uploadOutcome = AttachmentUploadResult.TooLarge)
            val vm = vm(repo)
            vm.addAttachment("content://docs/a", "a.txt", "text/plain", 5L)

            vm.sendMessage("with a file")
            advanceUntilIdle()

            assertEquals(0, repo.sends)
            assertFalse(vm.localSendPending.value)
        }

    /** #1314: [ThreadViewModel.sentMessages] fires once per send the daemon accepted, and for nothing else. */
    private suspend fun TestScope.sentSignals(
        vm: ThreadViewModel,
        act: suspend () -> Unit,
    ): Int {
        val signals = mutableListOf<Unit>()
        val collector = launch { vm.sentMessages.toList(signals) }
        act()
        advanceUntilIdle()
        collector.cancel()
        return signals.size
    }

    @Test
    fun anAcceptedTextSend_signalsOnce_andOnlyAfterTheDaemonAccepts() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val vm = vm(GatedRepository(gate))
            val signals = mutableListOf<Unit>()
            val collector = launch { vm.sentMessages.toList(signals) }

            vm.sendMessage("hello")
            advanceUntilIdle()
            assertEquals("nothing while the send is in flight", 0, signals.size)

            gate.complete(Unit)
            advanceUntilIdle()
            collector.cancel()

            assertEquals(1, signals.size)
            assertTrue(logs.contains("event=thread_send_accepted"))
            assertTrue(logs.none { "hello" in it })
        }

    @Test
    fun anAcceptedAttachmentSend_signals() =
        runTest {
            val repository = GatedRepository()
            val vm = vm(repository)
            vm.addAttachment("content://docs/a", "a.txt", "text/plain", 5L)

            assertEquals(1, sentSignals(vm) { vm.sendMessage("with a file") })
            assertEquals(1, repository.uploads)
        }

    @Test
    fun aFailedSend_aBlankSend_andAFailedUpload_neverSignal() =
        runTest {
            val failed = vm(GatedRepository(failure = RelayErrorException(code = "server.error", retryable = false, message = "no")))
            assertEquals(0, sentSignals(failed) { failed.sendMessage("hello") })

            val blank = vm(GatedRepository())
            assertEquals(0, sentSignals(blank) { blank.sendMessage("   ") })

            val upload = vm(GatedRepository(uploadOutcome = AttachmentUploadResult.TooLarge))
            upload.addAttachment("content://docs/a", "a.txt", "text/plain", 5L)
            assertEquals(0, sentSignals(upload) { upload.sendMessage("with a file") })
        }

    private companion object {
        const val HOST = "pyrybox"
        const val CONV = "chat-1311"
    }
}
