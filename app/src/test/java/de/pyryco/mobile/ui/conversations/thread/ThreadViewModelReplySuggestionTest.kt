package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.AttachmentUploadResult
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.ReplySuggestion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelReplySuggestionTest {
    private val oldSink = RelayLog.sink
    private val models = ViewModelStore()

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun close() {
        models.clear()
        RelayLog.sink = oldSink
        Dispatchers.resetMain()
    }

    private class Repository : ConversationRepository by FakeConversationRepository() {
        val sessions = MutableStateFlow("s1")
        val phase = MutableStateFlow(LiveSessionEvent.TurnState.Phase.Idle)
        val readings = MutableStateFlow<Map<String, ReplySuggestion>>(emptyMap())
        val sends = mutableListOf<String>()
        val attachmentSends = mutableListOf<List<MessageAttachment>>()
        var hold: CompletableDeferred<Unit>? = null

        override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> =
            sessions.map { session ->
                listOf(Conversation("c1", "test", "/p", session, emptyList(), true, Instant.parse("2026-10-07T00:00:00Z")))
            }

        override fun observeTurnPhase(conversationId: String) = phase

        override fun observeReplySuggestion(
            conversationId: String,
            sessionId: String,
        ) = readings.map {
            it["$conversationId/$sessionId"]
        }

        fun set(
            revision: ULong,
            text: String?,
            session: String = "s1",
            conversation: String = "c1",
        ) {
            readings.value = readings.value + ("$conversation/$session" to ReplySuggestion(conversation, session, revision, text))
        }

        override suspend fun sendMessage(
            conversationId: String,
            text: String,
        ): Message {
            sends += text
            return Message("m${sends.size}", "s1", Role.User, text, Instant.parse("2026-10-07T00:00:00Z"), false)
        }

        override suspend fun uploadAttachment(
            conversationId: String,
            bytes: ByteArray,
            filename: String,
            mimeType: String,
            onProgress: (Int, Int) -> Unit,
        ): AttachmentUploadResult {
            hold?.await()
            return AttachmentUploadResult.Stored("attachment-id")
        }

        override suspend fun sendMessage(
            conversationId: String,
            text: String,
            attachments: List<MessageAttachment>,
        ): Message {
            hold?.await()
            attachmentSends += attachments
            return sendMessage(conversationId, text)
        }
    }

    private fun vm(
        repo: Repository,
        connection: FakeConnectionStateSource = FakeConnectionStateSource(),
        host: String = "host",
    ) = ThreadViewModel(
        SavedStateHandle(mapOf("serverId" to host, "conversationId" to "c1")),
        repo,
        connection,
        ComposerDraftStore(),
        attachmentReader = AttachmentReader { AttachmentRead.Bytes(byteArrayOf(1)) },
        ioDispatcher = UnconfinedTestDispatcher(),
    ).also { models.put("$host-${System.identityHashCode(it)}", it) }

    @Test fun emptyDraftShowsInertVerbatimSuggestion_typingAndWhitespaceHide_erasingRevealsNewToken() =
        runTest {
            val repo = Repository()
            val vm = vm(repo)
            repo.set(1u, "  next reply  ")
            runCurrent()
            val initial = requireNotNull(vm.suggestedReply.value)
            assertEquals("  next reply  ", initial.text)
            assertEquals("", vm.draft.value)
            vm.onDraftChange(" ")
            assertNull(vm.suggestedReply.value)
            assertFalse(vm.sendSuggestedReply(initial))
            vm.onDraftChange("")
            val revealed = requireNotNull(vm.suggestedReply.value)
            assertNotSame(initial, revealed)
            assertFalse(vm.sendSuggestedReply(initial))
            assertTrue(repo.sends.isEmpty())
        }

    @Test fun explicitSubmission_preservesSpaces_consumesBeforeAsyncSend_andTypedTextStillTrims() =
        runTest {
            val repo = Repository()
            val vm = vm(repo)
            repo.set(1u, "  next reply  ")
            runCurrent()
            val offer = requireNotNull(vm.suggestedReply.value)
            assertTrue(vm.sendSuggestedReply(offer))
            assertFalse(vm.sendSuggestedReply(offer))
            assertNull(vm.suggestedReply.value)
            assertEquals(listOf("  next reply  "), repo.sends)
            repo.phase.value = LiveSessionEvent.TurnState.Phase.Thinking
            repo.phase.value = LiveSessionEvent.TurnState.Phase.Idle
            assertNull(vm.suggestedReply.value)
            vm.onDraftChange("  typed  ")
            vm.sendMessage("  typed  ")
            assertEquals(listOf("  next reply  ", "typed"), repo.sends)
            repo.set(2u, "newer")
            assertEquals("newer", vm.suggestedReply.value?.text)
        }

    @Test fun changeClearAndSessionReplacement_rejectOldTokens_andOtherPairsNeverSupplyText() =
        runTest {
            val repo = Repository()
            val vm = vm(repo)
            repo.set(1u, "first")
            val first = requireNotNull(vm.suggestedReply.value)
            repo.set(2u, "first")
            assertFalse(vm.sendSuggestedReply(first))
            val revised = requireNotNull(vm.suggestedReply.value)
            repo.set(3u, null)
            assertNull(vm.suggestedReply.value)
            assertFalse(vm.sendSuggestedReply(revised))
            repo.set(1u, "other session", session = "s2")
            repo.set(1u, "other conversation", conversation = "c2")
            assertNull(vm.suggestedReply.value)
            repo.set(4u, "old session")
            val old = requireNotNull(vm.suggestedReply.value)
            repo.sessions.value = "s2"
            assertEquals("other session", vm.suggestedReply.value?.text)
            assertFalse(vm.sendSuggestedReply(old))
            repo.sessions.value = "s1"
            assertNull(vm.suggestedReply.value)
            assertTrue(repo.sends.isEmpty())
        }

    @Test fun busyAndDisconnect_invalidateRevision_evenAfterReturningToIdleOrReconnecting() =
        runTest {
            val repo = Repository()
            val connection = FakeConnectionStateSource()
            val vm = vm(repo, connection)
            repo.set(1u, "before turn")
            val old = requireNotNull(vm.suggestedReply.value)
            repo.phase.value = LiveSessionEvent.TurnState.Phase.Thinking
            assertNull(vm.suggestedReply.value)
            repo.phase.value = LiveSessionEvent.TurnState.Phase.Idle
            assertNull(vm.suggestedReply.value)
            assertFalse(vm.sendSuggestedReply(old))
            repo.set(2u, "before disconnect")
            val disconnected = requireNotNull(vm.suggestedReply.value)
            connection.emit(ConnectionState.Offline)
            assertNull(vm.suggestedReply.value)
            connection.emit(ConnectionState.Connected)
            assertNull(vm.suggestedReply.value)
            assertFalse(vm.sendSuggestedReply(disconnected))
            repo.set(3u, "fresh")
            assertEquals("fresh", vm.suggestedReply.value?.text)
            assertTrue(repo.sends.isEmpty())
        }

    @Test fun freshDaemonLifetimeMayRestartRevisions_butSameRevisionReconciliationStaysInvalidated() =
        runTest {
            val repo = Repository()
            val connection = FakeConnectionStateSource()
            val vm = vm(repo, connection)
            repo.set(50u, "old lifetime")
            val old = requireNotNull(vm.suggestedReply.value)
            connection.emit(ConnectionState.Offline)
            repo.readings.value = emptyMap()
            connection.emit(ConnectionState.Connected)
            repo.set(50u, "old lifetime")
            assertNull(vm.suggestedReply.value)
            // #1865 allows decreasing revisions only after replacing the connection repository.
            repo.readings.value = emptyMap()
            repo.set(1u, "fresh lifetime")
            assertEquals("fresh lifetime", vm.suggestedReply.value?.text)
            assertFalse(vm.sendSuggestedReply(old))
        }

    @Test fun repositoryAbsenceBeforeOffline_keepsSameRevisionInvalidated_andAllowsFreshLifetime() =
        runTest {
            val repo = Repository()
            val connection = FakeConnectionStateSource()
            val vm = vm(repo, connection)
            repo.set(50u, "old lifetime")
            val old = requireNotNull(vm.suggestedReply.value)
            repo.readings.value = emptyMap()
            assertNull(vm.suggestedReply.value)
            assertFalse(vm.sendSuggestedReply(old))
            connection.emit(ConnectionState.Offline)
            connection.emit(ConnectionState.Connected)
            repo.set(50u, "old lifetime")
            assertNull(vm.suggestedReply.value)
            assertFalse(vm.sendSuggestedReply(old))
            assertTrue(repo.sends.isEmpty())
            repo.readings.value = emptyMap()
            repo.set(1u, "fresh lifetime")
            val fresh = requireNotNull(vm.suggestedReply.value)
            assertEquals("fresh lifetime", fresh.text)
            assertFalse(vm.sendSuggestedReply(old))
            assertTrue(vm.sendSuggestedReply(fresh))
            assertFalse(vm.sendSuggestedReply(fresh))
            assertEquals(listOf("fresh lifetime"), repo.sends)
        }

    @Test fun repositoryAbsenceBeforeSessionReplacement_keepsPreviousSessionRevisionInvalidated() =
        runTest {
            val repo = Repository()
            val vm = vm(repo)
            repo.set(50u, "first session")
            val first = requireNotNull(vm.suggestedReply.value)
            repo.readings.value = emptyMap()
            repo.sessions.value = "s2"
            repo.set(8u, "second session", session = "s2")
            val second = requireNotNull(vm.suggestedReply.value)
            repo.readings.value = emptyMap()
            repo.sessions.value = "s1"
            repo.set(50u, "first session")
            assertNull(vm.suggestedReply.value)
            assertFalse(vm.sendSuggestedReply(first))
            assertFalse(vm.sendSuggestedReply(second))
            assertTrue(repo.sends.isEmpty())
            repo.set(51u, "newer first session")
            assertEquals("newer first session", vm.suggestedReply.value?.text)
        }

    @Test fun hostAndDestinationTokensCannotAuthorizeAnotherViewModel() =
        runTest {
            val a = Repository()
            val b = Repository()
            val first = vm(a, host = "a")
            val second = vm(b, host = "b")
            a.set(1u, "same")
            b.set(1u, "same")
            val wrongHost = requireNotNull(first.suggestedReply.value)
            assertFalse(second.sendSuggestedReply(wrongHost))
            val reopened = vm(a, host = "a")
            assertFalse(reopened.sendSuggestedReply(wrongHost))
            assertTrue(a.sends.isEmpty())
            assertTrue(b.sends.isEmpty())
        }

    @Test fun attachmentSendBlocksSuggestion_andSuggestionUsesOrdinaryAttachmentSnapshot() =
        runTest {
            val repo = Repository()
            val vm = vm(repo)
            vm.addAttachment("content://docs/file", "file", "text/plain", 1)
            repo.hold = CompletableDeferred()
            repo.set(1u, "suggestion")
            val offer = requireNotNull(vm.suggestedReply.value)
            assertTrue(vm.sendSuggestedReply(offer))
            assertTrue(vm.attachmentsSending.value)
            repo.set(2u, "next")
            val duringUpload = requireNotNull(vm.suggestedReply.value)
            assertFalse(vm.sendSuggestedReply(duringUpload))
            assertTrue(repo.sends.isEmpty())
            repo.hold?.complete(Unit)
            runCurrent()
            assertEquals(listOf("suggestion"), repo.sends)
            assertEquals(listOf("attachment-id"), repo.attachmentSends.single().map { it.attachmentId })
            assertFalse(vm.sendSuggestedReply(duringUpload))
            assertFalse(vm.sendSuggestedReply(offer))
            assertTrue(vm.pendingAttachments.value.isEmpty())
        }
}
