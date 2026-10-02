package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.LiveSessionEvent.TurnState.Phase
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.AttachmentUploadResult
import de.pyryco.mobile.data.repository.BoundaryReason
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.ThinkingProgress
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.components.TurnRecoveryNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #1357: [ThreadViewModel.turnOutcome] holds a stopped turn's recovery advice from its `turn_end` until the
 * next sign of activity — one test per clear signal — and keeps it across an `idle` `turn_state`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelTurnRecoveryTest {
    // RelayLog's default sink is android.util.Log, which throws on the plain JVM.
    private val logs = mutableListOf<String>()
    private val oldSink = RelayLog.sink
    private val oldEnabled = RelayLog.enabled

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

    /** Thread rows and the thinking reading are set by the test; sends are counted. */
    private class ControlledRepository : ConversationRepository by FakeConversationRepository() {
        val items = MutableStateFlow<List<ThreadItem>>(emptyList())
        val reading = MutableStateFlow<ThinkingProgress?>(null)
        var sends = 0

        override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> = items

        override fun observeThinkingProgress(conversationId: String): Flow<ThinkingProgress?> = reading

        override suspend fun sendMessage(
            conversationId: String,
            text: String,
        ): Message = sent(text)

        override suspend fun sendMessage(
            conversationId: String,
            text: String,
            attachments: List<MessageAttachment>,
        ): Message = sent(text)

        override suspend fun uploadAttachment(
            conversationId: String,
            bytes: ByteArray,
            filename: String,
            mimeType: String,
            onProgress: (sentChunks: Int, totalChunks: Int) -> Unit,
        ): AttachmentUploadResult = AttachmentUploadResult.Stored("att-1")

        private fun sent(text: String): Message {
            sends++
            return message("sent-$sends", text)
        }
    }

    private class Fixture(
        val vm: ThreadViewModel,
        val repo: ControlledRepository,
        val events: MutableSharedFlow<LiveSessionEvent>,
        val available: MutableStateFlow<Boolean>,
    )

    /** A ViewModel whose `state` is collected, as the screen collects it, so the thread rows flow. */
    private fun TestScope.fixture(): Fixture {
        val repo = ControlledRepository()
        val events = MutableSharedFlow<LiveSessionEvent>()
        val available = MutableStateFlow(true)
        val vm =
            ThreadViewModel(
                SavedStateHandle(mapOf("serverId" to HOST, "conversationId" to CONV)),
                repo,
                FakeConnectionStateSource(),
                ComposerDraftStore(),
                liveSessionEvents = events,
                repositoryAvailable = available,
                attachmentReader = { AttachmentRead.Bytes("bytes".toByteArray()) },
            )
        backgroundScope.launch { vm.state.collect {} }
        return Fixture(vm, repo, events, available)
    }

    /** Raise the context notice and check it holds. */
    private suspend fun TestScope.raise(f: Fixture) {
        f.events.emit(contextOverflow())
        advanceUntilIdle()
        assertEquals(TurnRecoveryNotice.ContextTooLong, f.vm.turnOutcome.value)
    }

    @Test
    fun aStoppedTurnEnd_raisesItsNotice_andACleanOneReplacesIt() =
        runTest {
            val f = fixture()
            assertNull(f.vm.turnOutcome.value)

            f.events.emit(
                LiveSessionEvent.TurnEnd(CONV, "t1", "end_turn", isError = true, errorCategory = "billing_error"),
            )
            advanceUntilIdle()
            assertEquals(TurnRecoveryNotice.BillingError, f.vm.turnOutcome.value)

            f.events.emit(LiveSessionEvent.TurnEnd(CONV, "t2", "end_turn"))
            advanceUntilIdle()
            assertNull(f.vm.turnOutcome.value)
            assertTrue(logs.contains("event=turn_recovery_notice state=shown notice=BillingError"))
            assertTrue(logs.contains("event=turn_recovery_notice state=cleared reason=turn_end"))
        }

    @Test
    fun anIdleTurnState_andAReplayGap_keepIt() =
        runTest {
            val f = fixture()
            raise(f)

            f.events.emit(LiveSessionEvent.TurnState(CONV, Phase.Idle))
            f.events.emit(LiveSessionEvent.ReplayGap(CONV))
            advanceUntilIdle()

            assertEquals(TurnRecoveryNotice.ContextTooLong, f.vm.turnOutcome.value)
        }

    @Test
    fun anotherConversationsEvents_leaveIt() =
        runTest {
            val f = fixture()
            f.events.emit(contextOverflow(conversationId = OTHER))
            advanceUntilIdle()
            assertNull(f.vm.turnOutcome.value)

            raise(f)
            f.events.emit(LiveSessionEvent.TurnState(OTHER, Phase.Thinking))
            f.events.emit(LiveSessionEvent.AssistantDelta(OTHER, "t9", 0, "hi"))
            advanceUntilIdle()

            assertEquals(TurnRecoveryNotice.ContextTooLong, f.vm.turnOutcome.value)
        }

    @Test
    fun aNonIdleTurnState_clearsIt() =
        runTest {
            for (phase in listOf(Phase.Thinking, Phase.Responding)) {
                val f = fixture()
                raise(f)

                f.events.emit(LiveSessionEvent.TurnState(CONV, phase))
                advanceUntilIdle()

                assertNull("turn_state $phase", f.vm.turnOutcome.value)
            }
        }

    @Test
    fun anAssistantDelta_clearsIt() =
        runTest {
            val f = fixture()
            raise(f)

            f.events.emit(LiveSessionEvent.AssistantDelta(CONV, "t2", 0, "hi"))
            advanceUntilIdle()

            assertNull(f.vm.turnOutcome.value)
        }

    @Test
    fun aToolUse_clearsIt() =
        runTest {
            val f = fixture()
            raise(f)

            f.events.emit(LiveSessionEvent.ToolUse(CONV, "t2", "tool-1", "Bash", "ls"))
            advanceUntilIdle()

            assertNull(f.vm.turnOutcome.value)
        }

    @Test
    fun aToolResult_clearsIt() =
        runTest {
            val f = fixture()
            raise(f)

            f.events.emit(LiveSessionEvent.ToolResult(CONV, "t2", "tool-1", isError = false, resultSummary = "ok"))
            advanceUntilIdle()

            assertNull(f.vm.turnOutcome.value)
        }

    @Test
    fun aNewThinkingReading_clearsIt_butNoReadingDoesNot() =
        runTest {
            val f = fixture()
            raise(f)

            f.repo.reading.value = null
            advanceUntilIdle()
            assertEquals(TurnRecoveryNotice.ContextTooLong, f.vm.turnOutcome.value)

            f.repo.reading.value = ThinkingProgress(estimatedTokens = 12, estimatedTokensDelta = 12)
            advanceUntilIdle()

            assertNull(f.vm.turnOutcome.value)
            assertTrue(logs.contains("event=turn_recovery_notice state=cleared reason=thinking"))
        }

    @Test
    fun aNewSessionBoundary_clearsIt() =
        runTest {
            val f = fixture()
            f.repo.items.value = listOf(messageItem("m1"), boundary("s0", "s1", minute = 1), messageItem("m2"))
            advanceUntilIdle()
            raise(f)

            f.repo.items.value = f.repo.items.value + boundary("s1", "s2", minute = 3)
            advanceUntilIdle()

            assertNull(f.vm.turnOutcome.value)
            assertTrue(logs.contains("event=turn_recovery_notice state=cleared reason=session_boundary"))
        }

    @Test
    fun theFirstLoad_anOlderPage_andAnUnrelatedRow_keepIt() =
        runTest {
            val f = fixture()
            raise(f)

            // The first load is a baseline, not a transition, even when it ends on a boundary.
            f.repo.items.value = listOf(messageItem("m1"), boundary("s0", "s1", minute = 1))
            advanceUntilIdle()
            // An older page prepends an older boundary; the newest is unchanged.
            f.repo.items.value = listOf(boundary("a", "s0", minute = 0)) + f.repo.items.value
            // A new message row is not a boundary.
            f.repo.items.value = f.repo.items.value + messageItem("m2")
            advanceUntilIdle()

            assertEquals(TurnRecoveryNotice.ContextTooLong, f.vm.turnOutcome.value)
        }

    @Test
    fun aTextSend_clearsIt() =
        runTest {
            val f = fixture()
            raise(f)

            f.vm.sendMessage("try again")
            advanceUntilIdle()

            assertEquals(1, f.repo.sends)
            assertNull(f.vm.turnOutcome.value)
            assertTrue(logs.contains("event=turn_recovery_notice state=cleared reason=send"))
            assertTrue(logs.none { "try again" in it })
        }

    @Test
    fun anAttachmentSend_clearsIt() =
        runTest {
            val f = fixture()
            raise(f)
            f.vm.addAttachment("content://docs/a", "a.txt", "text/plain", 5L)

            f.vm.sendMessage("with a file")
            advanceUntilIdle()

            assertEquals(1, f.repo.sends)
            assertNull(f.vm.turnOutcome.value)
        }

    @Test
    fun aBareCommand_includingCompact_clearsIt() =
        runTest {
            val f = fixture()
            raise(f)

            f.vm.onComposerCommand(ComposerAction.CompactSession)
            advanceUntilIdle()

            assertEquals(1, f.repo.sends)
            assertNull(f.vm.turnOutcome.value)
        }

    @Test
    fun aReconnect_clearsIt() =
        runTest {
            val f = fixture()
            advanceUntilIdle()
            raise(f)

            f.available.value = false
            advanceUntilIdle()

            assertNull(f.vm.turnOutcome.value)
            assertTrue(logs.contains("event=turn_recovery_notice state=cleared reason=reconnect"))
        }

    private fun contextOverflow(conversationId: String = CONV) =
        LiveSessionEvent.TurnEnd(
            conversationId,
            turnId = "t1",
            stopReason = "end_turn",
            outcome = "success",
            isError = true,
            terminalReason = "prompt_too_long",
        )

    private fun messageItem(id: String) = ThreadItem.MessageItem(message(id, "text $id"))

    private fun boundary(
        previous: String,
        next: String,
        minute: Int,
    ) = ThreadItem.SessionBoundary(previous, next, BoundaryReason.Clear, Instant.parse("2026-10-01T00:0$minute:00Z"))

    private companion object {
        const val HOST = "pyrybox"
        const val CONV = "chat-1357"
        const val OTHER = "someone-else"

        fun message(
            id: String,
            text: String,
        ) = Message(
            id = id,
            sessionId = "s1",
            role = Role.User,
            content = text,
            timestamp = Instant.parse("2026-10-01T00:00:00Z"),
            isStreaming = false,
        )
    }
}
