package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.network.MessageAttachmentIds
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.AttachmentUploadResult
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** #932: the chat's pending attachments, added and removed through the thread, and uploaded on send. */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelAttachmentTest {
    private val logs = mutableListOf<String>()
    private val oldSink = RelayLog.sink
    private val oldEnabled = RelayLog.enabled
    private val uncaught = mutableListOf<Throwable>()
    private val oldHandler = Thread.getDefaultUncaughtExceptionHandler()

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

    /** Records uploads and sends; each upload's outcome comes from [uploadOutcome], by display name. */
    private class RecordingRepository(
        private val uploadOutcome: (filename: String) -> AttachmentUploadResult = { AttachmentUploadResult.Stored("id-$it") },
        private val sendFailure: Throwable? = null,
        private val whileSending: () -> Unit = {},
        private val beforeUpload: suspend () -> Unit = {},
        private val delegate: FakeConversationRepository = FakeConversationRepository(),
    ) : ConversationRepository by delegate {
        val uploads = mutableListOf<Pair<String, String>>() // filename to content
        val sends = mutableListOf<Pair<String, List<String>?>>() // text to ids; null ids = two-argument send
        val sentAttachments = mutableListOf<List<MessageAttachment>>()

        override suspend fun uploadAttachment(
            conversationId: String,
            bytes: ByteArray,
            filename: String,
            mimeType: String,
        ): AttachmentUploadResult {
            beforeUpload()
            uploads += filename to bytes.decodeToString()
            return uploadOutcome(filename)
        }

        override suspend fun sendMessage(
            conversationId: String,
            text: String,
        ): Message {
            sends += text to null
            return delegate.sendMessage(conversationId, text)
        }

        override suspend fun sendMessage(
            conversationId: String,
            text: String,
            attachments: List<MessageAttachment>,
        ): Message {
            whileSending()
            sendFailure?.let { throw it }
            sends += text to attachments.map { it.attachmentId }
            sentAttachments += attachments
            return delegate.sendMessage(conversationId, text)
        }
    }

    /** Serves `bytes-of-<uri>` for every URI, except the ones listed as failing. */
    private class FakeReader(
        private val failures: Map<String, AttachmentRead> = emptyMap(),
    ) : AttachmentReader {
        val reads = mutableListOf<String>()

        override suspend fun read(uri: String): AttachmentRead {
            reads += uri
            return failures[uri] ?: AttachmentRead.Bytes("bytes-of-$uri".toByteArray())
        }
    }

    private fun vm(
        repository: ConversationRepository,
        store: ComposerDraftStore,
        reader: AttachmentReader = FakeReader(),
        serverId: String = HOST,
    ) = ThreadViewModel(
        SavedStateHandle(mapOf("serverId" to serverId, "conversationId" to CONV)),
        repository,
        FakeConnectionStateSource(),
        store,
        attachmentReader = reader,
    )

    private fun ThreadViewModel.attach(name: String): AttachmentAddOutcome = addAttachment("content://docs/$name", name, "text/plain", 5L)

    @Test
    fun pendingAttachments_showsOnlyThisChatsEntries() =
        runTest {
            val store = ComposerDraftStore()
            val here = vm(RecordingRepository(), store)
            val otherHost = vm(RecordingRepository(), store, serverId = "laptop")

            here.attach("mine")
            otherHost.attach("theirs")
            advanceUntilIdle()

            assertEquals(listOf("mine"), here.pendingAttachments.value.map { it.displayName })
            assertEquals(listOf("theirs"), otherHost.pendingAttachments.value.map { it.displayName })
        }

    @Test
    fun addAttachment_reportsARefusal_andRemoveKeepsTheRestInOrder() =
        runTest {
            val store = ComposerDraftStore()
            val vm = vm(RecordingRepository(), store)

            val refused = vm.addAttachment("content://docs/huge", "huge", "video/mp4", Long.MAX_VALUE)
            assertEquals(AttachmentAddOutcome.TOO_LARGE, refused)
            assertEquals(listOf("event=composer_attachment_add outcome=too_large"), attachmentLogs())

            listOf("a", "b", "c").forEach { assertEquals(AttachmentAddOutcome.ADDED, vm.attach(it)) }
            vm.removeAttachment(vm.pendingAttachments.value[1].key)
            advanceUntilIdle()

            assertEquals(listOf("a", "c"), vm.pendingAttachments.value.map { it.displayName })
        }

    @Test
    fun send_uploadsEachEntryInOrder_thenNamesTheirIds_andClearsTheDraft() =
        runTest {
            val store = ComposerDraftStore()
            val repository = RecordingRepository()
            val vm = vm(repository, store)
            vm.onDraftChange("look at these")
            vm.attach("a")
            vm.attach("b")

            vm.sendMessage("look at these")
            advanceUntilIdle()

            assertEquals(listOf("a" to "bytes-of-content://docs/a", "b" to "bytes-of-content://docs/b"), repository.uploads)
            assertEquals(listOf("look at these" to listOf("id-a", "id-b")), repository.sends)
            assertEquals("", vm.draft.value)
            assertTrue(vm.pendingAttachments.value.isEmpty())
            assertTrue(store.attachments.value.isEmpty())
        }

    @Test
    fun send_namesEachAttachmentWithTheDisplayNameAndMimeTypeUsedForTheUpload() =
        runTest {
            val repository = RecordingRepository()
            val vm = vm(repository, ComposerDraftStore())
            vm.addAttachment("content://docs/photo", "photo.jpg", "image/jpeg", 5L)
            vm.attach("notes")

            vm.sendMessage("two files")
            advanceUntilIdle()

            assertEquals(
                listOf(
                    listOf(
                        MessageAttachment("id-photo.jpg", "photo.jpg", "image/jpeg"),
                        MessageAttachment("id-notes", "notes", "text/plain"),
                    ),
                ),
                repository.sentAttachments,
            )
        }

    @Test
    fun send_withBlankTextAndAttachments_sendsNothing_andKeepsTheFilesForTheNextSend() =
        runTest {
            val store = ComposerDraftStore()
            val repository = RecordingRepository()
            val vm = vm(repository, store)
            vm.attach("a")

            vm.sendMessage("")
            vm.sendMessage("  ")
            advanceUntilIdle()

            assertTrue(repository.sends.isEmpty())
            assertTrue(repository.uploads.isEmpty())
            assertFalse(vm.attachmentsSending.value)
            assertEquals(listOf("a"), vm.pendingAttachments.value.map { it.displayName })

            vm.sendMessage("with text")
            advanceUntilIdle()

            assertEquals(listOf("with text" to listOf("id-a")), repository.sends)
            assertTrue(vm.pendingAttachments.value.isEmpty())
        }

    @Test
    fun send_withoutAttachments_staysTheTextOnlySend() =
        runTest {
            val repository = RecordingRepository()
            val vm = vm(repository, ComposerDraftStore())

            vm.sendMessage("")
            vm.sendMessage("plain")
            advanceUntilIdle()

            assertEquals(listOf("plain" to null), repository.sends)
            assertTrue(repository.uploads.isEmpty())
        }

    @Test
    fun aFailedUpload_keepsEverything_andARetryUploadsOnlyWhatIsMissing() =
        runTest {
            val store = ComposerDraftStore()
            var refuseB = true
            val repository =
                RecordingRepository(uploadOutcome = { name ->
                    if (name == "b" && refuseB) {
                        AttachmentUploadResult.ReconnectRequired
                    } else {
                        AttachmentUploadResult.Stored("id-$name")
                    }
                })
            val vm = vm(repository, store)
            vm.onDraftChange("both")
            vm.attach("a")
            vm.attach("b")
            vm.attach("c")

            vm.sendMessage("both")
            advanceUntilIdle()

            // Stopped at b: c was never read, nothing was sent, the draft is whole and a keeps its id.
            assertEquals(listOf("a", "b"), repository.uploads.map { it.first })
            assertTrue(repository.sends.isEmpty())
            assertEquals("both", vm.draft.value)
            assertEquals(listOf("id-a", null, null), vm.pendingAttachments.value.map { it.attachmentId })
            assertEquals(listOf("event=composer_attachment_send outcome=upload_failed"), attachmentLogs())

            refuseB = false
            repository.uploads.clear()
            vm.sendMessage("both")
            advanceUntilIdle()

            assertEquals(listOf("b", "c"), repository.uploads.map { it.first })
            assertEquals(listOf("both" to listOf("id-a", "id-b", "id-c")), repository.sends)
            assertEquals("", vm.draft.value)
            assertTrue(vm.pendingAttachments.value.isEmpty())
        }

    @Test
    fun aFailedRead_keepsEverything_andUploadsNothingAfterIt() =
        runTest {
            for ((failure, outcome) in listOf(AttachmentRead.TooLarge to "read_too_large", AttachmentRead.Unreadable to "read_failed")) {
                logs.clear()
                val store = ComposerDraftStore()
                val repository = RecordingRepository()
                val vm = vm(repository, store, FakeReader(mapOf("content://docs/a" to failure)))
                vm.onDraftChange("text")
                vm.attach("a")
                vm.attach("b")

                vm.sendMessage("text")
                advanceUntilIdle()

                assertTrue(repository.uploads.isEmpty())
                assertTrue(repository.sends.isEmpty())
                assertEquals("text", vm.draft.value)
                assertEquals(listOf("a", "b"), vm.pendingAttachments.value.map { it.displayName })
                assertEquals(listOf("event=composer_attachment_send outcome=$outcome"), attachmentLogs())
            }
        }

    @Test
    fun aThrownSend_keepsTheTextAndTheAcknowledgedIds() =
        runTest {
            val store = ComposerDraftStore()
            val reader = FakeReader()
            val repository = RecordingRepository(sendFailure = IllegalStateException("not connected"))
            val vm = vm(repository, store, reader)
            vm.onDraftChange("keep me")
            vm.attach("a")

            vm.sendMessage("keep me")
            advanceUntilIdle()

            assertEquals("keep me", vm.draft.value)
            assertEquals(listOf("id-a"), vm.pendingAttachments.value.map { it.attachmentId })

            // A retry reads nothing again: the id is already held.
            reader.reads.clear()
            vm.sendMessage("keep me")
            advanceUntilIdle()
            assertTrue(reader.reads.isEmpty())
        }

    @Test
    fun attachmentsAndTextAddedDuringASend_surviveIt() =
        runTest {
            val store = ComposerDraftStore()
            var vm: ThreadViewModel? = null
            val repository =
                RecordingRepository(whileSending = {
                    vm?.onDraftChange("next message")
                    vm?.attach("late")
                })
            vm = vm(repository, store)
            vm.onDraftChange("first")
            vm.attach("early")

            vm.sendMessage("first")
            advanceUntilIdle()

            assertEquals(listOf("first" to listOf("id-early")), repository.sends)
            assertEquals("next message", vm.draft.value)
            assertEquals(listOf("late"), vm.pendingAttachments.value.map { it.displayName })
        }

    @Test
    fun logs_neverCarryAUriOrAName() =
        runTest {
            val repository = RecordingRepository(uploadOutcome = { AttachmentUploadResult.Refused("attachment.bad", false) })
            val vm = vm(repository, ComposerDraftStore())
            vm.addAttachment("content://private.provider/secret-doc", "secret-name.pdf", "application/pdf", Long.MAX_VALUE)
            vm.addAttachment("content://private.provider/secret-doc", "secret-name.pdf", "application/pdf", 1L)

            vm.sendMessage("hi")
            advanceUntilIdle()

            assertTrue(logs.isNotEmpty())
            assertFalse("no provider text in logs: $logs", logs.any { "secret" in it || "application/pdf" in it })
        }

    @Test
    fun addPickedAttachments_addsInOrder_andReportsEachRefusalKindOnce() =
        runTest {
            val store = ComposerDraftStore()
            val vm = vm(RecordingRepository(), store)
            repeat(MessageAttachmentIds.MAX - 2) { vm.attach("f$it") }
            val refusals = mutableListOf<AttachmentRefusal>()
            val collector = launch { vm.attachmentRefusals.toList(refusals) }

            vm.addPickedAttachments(
                listOf(
                    picked("a"),
                    picked("huge", size = Long.MAX_VALUE),
                    picked("b"),
                    picked("c"),
                    picked("d"),
                ),
            )
            advanceUntilIdle()

            assertEquals(
                listOf("a", "b"),
                vm.pendingAttachments.value
                    .takeLast(2)
                    .map { it.displayName },
            )
            assertEquals(listOf(AttachmentRefusal(tooLarge = 1, tooMany = 2)), refusals)
            collector.cancel()
        }

    @Test
    fun addPickedAttachments_reportsNothing_whenEverythingIsAdded() =
        runTest {
            val vm = vm(RecordingRepository(), ComposerDraftStore())
            val refusals = mutableListOf<AttachmentRefusal>()
            val collector = launch { vm.attachmentRefusals.toList(refusals) }

            vm.addPickedAttachments(listOf(picked("a"), picked("b")))
            advanceUntilIdle()

            assertEquals(listOf("a", "b"), vm.pendingAttachments.value.map { it.displayName })
            assertTrue(refusals.isEmpty())
            collector.cancel()
        }

    @Test
    fun attachmentsSending_coversTheUploadsAndTheSend_andASecondTapSendsNothingMore() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val repository = RecordingRepository(beforeUpload = { gate.await() })
            val vm = vm(repository, ComposerDraftStore())
            vm.attach("a")
            assertFalse(vm.attachmentsSending.value)

            vm.sendMessage("hi")
            advanceUntilIdle()
            assertTrue(vm.attachmentsSending.value)

            vm.sendMessage("hi")
            gate.complete(Unit)
            advanceUntilIdle()

            assertFalse(vm.attachmentsSending.value)
            assertEquals(listOf("a"), repository.uploads.map { it.first })
            assertEquals(listOf("hi" to listOf("id-a")), repository.sends)
        }

    @Test
    fun attachmentsSending_clearsAfterAFailedUpload_orAThrownSend() =
        runTest {
            val refused = RecordingRepository(uploadOutcome = { AttachmentUploadResult.ReconnectRequired })
            val thrown = RecordingRepository(sendFailure = IllegalStateException("not connected"))
            for (repository in listOf(refused, thrown)) {
                val vm = vm(repository, ComposerDraftStore())
                vm.attach("a")

                vm.sendMessage("hi")
                advanceUntilIdle()

                assertFalse(vm.attachmentsSending.value)
                assertEquals(listOf("a"), vm.pendingAttachments.value.map { it.displayName })
            }
        }

    private fun picked(
        name: String,
        size: Long? = 5L,
    ) = PickedAttachment("content://docs/$name", name, "text/plain", size)

    private fun attachmentLogs() = logs.filter { "composer_attachment" in it }

    private companion object {
        const val HOST = "pyrybox"
        const val CONV = "seed-channel-personal"
    }
}
