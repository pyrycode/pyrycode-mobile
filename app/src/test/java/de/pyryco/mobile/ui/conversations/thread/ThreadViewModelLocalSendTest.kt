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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #1641: Sending until acknowledgement, Waiting until this conversation's first turn_state.
 * A completion cannot revive a closed or replaced window.
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
        private val gates: List<CompletableDeferred<Unit>> = emptyList(),
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
            val attempt = sends
            gates.getOrElse(attempt - 1) { gate }.await()
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
            assertEquals(LocalSendStage.None, vm.localSendStage.value)

            vm.sendMessage("hello")
            advanceUntilIdle()
            assertEquals("open while the send is in flight", LocalSendStage.Sending, vm.localSendStage.value)

            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals("still open until the daemon's first turn_state", LocalSendStage.Waiting, vm.localSendStage.value)
            advanceTimeBy(300_000)
            advanceUntilIdle()
            assertEquals("no local timeout invents a started turn", LocalSendStage.Waiting, vm.localSendStage.value)
        }

    @Test
    fun anyTurnStateForThisConversation_closesIt() =
        runTest {
            for (phase in LiveSessionEvent.TurnState.Phase.entries) {
                val events = MutableSharedFlow<LiveSessionEvent>()
                val vm = vm(GatedRepository(), events)
                vm.sendMessage("hello")
                advanceUntilIdle()
                assertEquals(LocalSendStage.Waiting, vm.localSendStage.value)

                events.emit(turnState(phase))
                advanceUntilIdle()

                assertEquals("turn_state $phase must close the window", LocalSendStage.None, vm.localSendStage.value)
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

            assertEquals(LocalSendStage.Waiting, vm.localSendStage.value)
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
                assertEquals(LocalSendStage.Sending, vm.localSendStage.value)

                gate.complete(Unit)
                advanceUntilIdle()

                assertEquals("a send refused by $failure must close the window", LocalSendStage.None, vm.localSendStage.value)
            }
        }

    @Test
    fun aReconnect_closesIt() =
        runTest {
            val available = MutableStateFlow(true)
            val vm = vm(GatedRepository(), repositoryAvailable = available)
            vm.sendMessage("hello")
            advanceUntilIdle()
            assertEquals(LocalSendStage.Waiting, vm.localSendStage.value)

            available.value = false
            available.value = true
            advanceUntilIdle()

            assertEquals(LocalSendStage.None, vm.localSendStage.value)
        }

    @Test
    fun theConnectionTheThreadOpenedOn_doesNotCloseIt() =
        runTest {
            val available = MutableStateFlow(true)
            val vm = vm(GatedRepository(), repositoryAvailable = available)
            advanceUntilIdle()

            vm.sendMessage("hello")
            advanceUntilIdle()

            assertEquals(LocalSendStage.Waiting, vm.localSendStage.value)
        }

    @Test
    fun aBlankSend_neverOpensIt() =
        runTest {
            val repo = GatedRepository()
            val vm = vm(repo)

            vm.sendMessage("   ")
            advanceUntilIdle()

            assertEquals(0, repo.sends)
            assertEquals(LocalSendStage.None, vm.localSendStage.value)
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
            assertEquals(LocalSendStage.None, vm.localSendStage.value)

            vm.sendMessage("again")
            advanceUntilIdle()

            assertEquals(1, repo.sends)
            assertEquals(LocalSendStage.None, vm.localSendStage.value)
        }

    @Test
    fun anAttachmentSend_transitionsFromSendingToWaiting() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val vm = vm(GatedRepository(gate))
            vm.addAttachment("content://docs/a", "a.txt", "text/plain", 5L)
            vm.sendMessage("with a file")
            advanceUntilIdle()
            assertEquals(LocalSendStage.Sending, vm.localSendStage.value)

            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(LocalSendStage.Waiting, vm.localSendStage.value)
        }

    @Test
    fun turnStateBeforeAcknowledgement_cannotRestoreWaiting_forAnyPhase() =
        runTest {
            for (phase in LiveSessionEvent.TurnState.Phase.entries) {
                val gate = CompletableDeferred<Unit>()
                val events = MutableSharedFlow<LiveSessionEvent>()
                val vm = vm(GatedRepository(gate), events)
                vm.sendMessage("hello")
                advanceUntilIdle()
                assertEquals(LocalSendStage.Sending, vm.localSendStage.value)

                events.emit(turnState(phase))
                advanceUntilIdle()
                assertEquals(LocalSendStage.None, vm.localSendStage.value)
                gate.complete(Unit)
                advanceUntilIdle()
                assertEquals("late ack after $phase", LocalSendStage.None, vm.localSendStage.value)
            }
        }

    @Test
    fun availabilityChangesBeforeAcknowledgement_cannotRestoreWaiting() =
        runTest {
            for (initial in listOf(true, false)) {
                val gate = CompletableDeferred<Unit>()
                val available = MutableStateFlow(initial)
                val vm = vm(GatedRepository(gate), repositoryAvailable = available)
                advanceUntilIdle()
                vm.sendMessage("hello")
                advanceUntilIdle()
                assertEquals(LocalSendStage.Sending, vm.localSendStage.value)

                available.value = !initial
                advanceUntilIdle()
                assertEquals(LocalSendStage.None, vm.localSendStage.value)
                gate.complete(Unit)
                advanceUntilIdle()
                assertEquals(LocalSendStage.None, vm.localSendStage.value)
            }
        }

    @Test
    fun anotherConversationsTurnState_doesNotCloseSendingOrWaiting() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val events = MutableSharedFlow<LiveSessionEvent>()
            val vm = vm(GatedRepository(gate), events)
            vm.sendMessage("hello")
            advanceUntilIdle()
            events.emit(turnState(LiveSessionEvent.TurnState.Phase.Idle, "other"))
            advanceUntilIdle()
            assertEquals(LocalSendStage.Sending, vm.localSendStage.value)
            gate.complete(Unit)
            advanceUntilIdle()
            events.emit(turnState(LiveSessionEvent.TurnState.Phase.Thinking, "other"))
            advanceUntilIdle()
            assertEquals(LocalSendStage.Waiting, vm.localSendStage.value)
        }

    @Test
    fun olderSendCompletion_cannotMutateAReplacementWindow() =
        runTest {
            for (failure in listOf(null, IllegalStateException("not connected"))) {
                val first = CompletableDeferred<Unit>()
                val second = CompletableDeferred<Unit>()
                val events = MutableSharedFlow<LiveSessionEvent>()
                val vm = vm(GatedRepository(failure = failure, gates = listOf(first, second)), events)
                vm.sendMessage("first")
                advanceUntilIdle()
                events.emit(turnState(LiveSessionEvent.TurnState.Phase.Idle))
                advanceUntilIdle()
                vm.sendMessage("second")
                advanceUntilIdle()
                assertEquals(LocalSendStage.Sending, vm.localSendStage.value)

                first.complete(Unit)
                advanceUntilIdle()
                assertEquals("old completion must leave the new send alone", LocalSendStage.Sending, vm.localSendStage.value)
                second.complete(Unit)
                advanceUntilIdle()
                assertEquals(if (failure == null) LocalSendStage.Waiting else LocalSendStage.None, vm.localSendStage.value)
            }
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
            assertEquals(LocalSendStage.None, vm.localSendStage.value)
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
