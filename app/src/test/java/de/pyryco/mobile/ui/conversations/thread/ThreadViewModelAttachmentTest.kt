package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** #932: the chat's pending attachments, added and removed through the thread, and uploaded on send. */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelAttachmentTest {
    @get:Rule val pasteFiles = TemporaryFolder()
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
        private val neverReplies: Boolean = false,
        private val beforeUpload: suspend () -> Unit = {},
        private val duringUpload: suspend (filename: String, onProgress: (Int, Int) -> Unit) -> Unit = { _, _ -> },
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
            onProgress: (sentChunks: Int, totalChunks: Int) -> Unit,
        ): AttachmentUploadResult {
            beforeUpload()
            duringUpload(filename, onProgress)
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
            if (neverReplies) awaitCancellation()
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
        ioDispatcher = UnconfinedTestDispatcher(),
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
            val notices = mutableListOf<AttachmentSendFailure>()
            val collector = launch { vm.attachmentSendFailures.toList(notices) }

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
            // #1325: the failed send said why once; the successful retry says nothing.
            assertEquals(listOf(AttachmentSendFailure.NOT_CONNECTED), notices)
            collector.cancel()
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
    fun aThrownSend_afterTheUploads_restoresNeitherTheTextNorTheEntries() =
        runTest {
            // #1355 AC #3: once the uploads succeed the composer clears, and a refused send does not undo it.
            val store = ComposerDraftStore()
            val repository = RecordingRepository(sendFailure = IllegalStateException("not connected"))
            val vm = vm(repository, store)
            vm.onDraftChange("gone")
            vm.attach("a")

            vm.sendMessage("gone")
            advanceUntilIdle()

            assertEquals("", vm.draft.value)
            assertTrue(vm.pendingAttachments.value.isEmpty())
        }

    @Test
    fun theUploadsSucceeding_clearsTheComposer_andSendsTheTrimmedText_beforeAnyReply() =
        runTest {
            // #1355 AC #4: the send never completes, yet the text and the entries are already gone.
            val store = ComposerDraftStore()
            val repository = RecordingRepository(neverReplies = true)
            val vm = vm(repository, store)
            vm.onDraftChange("  look \n")
            vm.attach("a")

            vm.sendMessage("  look \n")
            advanceUntilIdle()

            assertEquals(listOf("look" to listOf("id-a")), repository.sends)
            assertEquals("", vm.draft.value)
            assertTrue(vm.pendingAttachments.value.isEmpty())
        }

    @Test
    fun textTypedDuringTheUploads_survivesTheClear() =
        runTest {
            // The clear compares the store against the text as typed, untrimmed, so an edit made while
            // the files upload is not swallowed.
            val store = ComposerDraftStore()
            var vm: ThreadViewModel? = null
            val repository = RecordingRepository(beforeUpload = { vm?.onDraftChange(" first \nand more") })
            vm = vm(repository, store)
            vm.onDraftChange(" first ")
            vm.attach("a")

            vm.sendMessage(" first ")
            advanceUntilIdle()

            assertEquals(listOf("first" to listOf("id-a")), repository.sends)
            assertEquals(" first \nand more", vm.draft.value)
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
            // #1325: the daemon's code picks the notice and goes no further.
            assertFalse("no daemon code in logs: $logs", logs.any { "attachment.bad" in it })
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
            // A failed upload keeps the entry; a thrown send has already cleared it with the text (#1355).
            for ((repository, remaining) in listOf(refused to listOf("a"), thrown to emptyList())) {
                val vm = vm(repository, ComposerDraftStore())
                vm.attach("a")

                vm.sendMessage("hi")
                advanceUntilIdle()

                assertFalse(vm.attachmentsSending.value)
                assertEquals(remaining, vm.pendingAttachments.value.map { it.displayName })
            }
        }

    /**
     * #1325: a send that stops on [read] failing for the second file, or on [upload] failing for it, shows
     * exactly one notice, keeps the draft text and every tile, and returns that notice. A second Send, once
     * the file reads and uploads, sends only the missing file and shows no further notice.
     */
    private fun TestScope.failureNoticeFor(
        upload: AttachmentUploadResult = AttachmentUploadResult.Stored("unused"),
        read: AttachmentRead? = null,
    ): AttachmentSendFailure {
        var failing = true
        val repository =
            RecordingRepository(uploadOutcome = { name ->
                if (name == "b" && failing) upload else AttachmentUploadResult.Stored("id-$name")
            })
        val readFailures = read?.let { mutableMapOf("content://docs/b" to it) } ?: mutableMapOf()
        val vm = vm(repository, ComposerDraftStore(), FakeReader(readFailures))
        val notices = mutableListOf<AttachmentSendFailure>()
        val collector = launch { vm.attachmentSendFailures.toList(notices) }
        vm.onDraftChange("text")
        vm.attach("a")
        vm.attach("b")

        vm.sendMessage("text")
        advanceUntilIdle()

        assertTrue(repository.sends.isEmpty())
        assertEquals("text", vm.draft.value)
        assertEquals(listOf("a", "b"), vm.pendingAttachments.value.map { it.displayName })
        assertEquals(listOf("id-a", null), vm.pendingAttachments.value.map { it.attachmentId })
        val notice = notices.single()

        failing = false
        readFailures.clear()
        repository.uploads.clear()
        vm.sendMessage("text")
        advanceUntilIdle()
        collector.cancel()

        assertEquals(listOf("b"), repository.uploads.map { it.first })
        assertEquals(listOf("text" to listOf("id-a", "id-b")), repository.sends)
        assertEquals(listOf(notice), notices)
        return notice
    }

    private fun refused(code: String) = AttachmentUploadResult.Refused(code, retryable = false)

    @Test
    fun failureNotice_unreadableFile() =
        runTest {
            assertEquals(AttachmentSendFailure.UNREADABLE, failureNoticeFor(read = AttachmentRead.Unreadable))
        }

    @Test
    fun failureNotice_fileTooLargeToRead() =
        runTest {
            assertEquals(AttachmentSendFailure.TOO_LARGE, failureNoticeFor(read = AttachmentRead.TooLarge))
        }

    @Test
    fun failureNotice_fileTooLargeToUpload() =
        runTest { assertEquals(AttachmentSendFailure.TOO_LARGE, failureNoticeFor(AttachmentUploadResult.TooLarge)) }

    @Test
    fun failureNotice_notConnected() =
        runTest { assertEquals(AttachmentSendFailure.NOT_CONNECTED, failureNoticeFor(AttachmentUploadResult.ReconnectRequired)) }

    @Test
    fun failureNotice_connectionLost() =
        runTest { assertEquals(AttachmentSendFailure.CONNECTION_LOST, failureNoticeFor(AttachmentUploadResult.ConnectionLost)) }

    @Test
    fun failureNotice_chunkNotSent() =
        runTest {
            assertEquals(AttachmentSendFailure.SEND_FAILED, failureNoticeFor(AttachmentUploadResult.SendFailed))
        }

    @Test
    fun failureNotice_hostRejectedAChunk() =
        runTest { assertEquals(AttachmentSendFailure.HOST_INVALID_CHUNK, failureNoticeFor(refused("attachment.invalid_chunk"))) }

    @Test
    fun failureNotice_hostCouldNotVerify() =
        runTest { assertEquals(AttachmentSendFailure.HOST_INTEGRITY_FAILED, failureNoticeFor(refused("attachment.integrity_failed"))) }

    @Test
    fun failureNotice_hostRefusedAsTooLarge() =
        runTest { assertEquals(AttachmentSendFailure.HOST_TOO_LARGE, failureNoticeFor(refused("attachment.too_large"))) }

    @Test
    fun failureNotice_hostHasTooManyUploads() =
        runTest { assertEquals(AttachmentSendFailure.HOST_TOO_MANY_UPLOADS, failureNoticeFor(refused("attachment.too_many_uploads"))) }

    @Test
    fun failureNotice_hostCouldNotStore() =
        runTest { assertEquals(AttachmentSendFailure.HOST_STORAGE_FAILED, failureNoticeFor(refused("attachment.storage_failed"))) }

    @Test
    fun failureNotice_hostRefusedAsTooLong() =
        runTest { assertEquals(AttachmentSendFailure.HOST_MESSAGE_TOO_LONG, failureNoticeFor(refused("message.too_long"))) }

    @Test
    fun failureNotice_uploadDidNotComplete() =
        runTest {
            assertEquals(AttachmentSendFailure.HOST_INCOMPLETE, failureNoticeFor(refused("attachment.not_found")))
            assertEquals(AttachmentSendFailure.HOST_INCOMPLETE, failureNoticeFor(refused("attachment.stream_aborted")))
        }

    @Test
    fun failureNotice_unknownOrMalformedRefusal() =
        runTest {
            assertEquals(AttachmentSendFailure.UNCLASSIFIED, failureNoticeFor(refused("attachment.something_new")))
            assertEquals(AttachmentSendFailure.UNCLASSIFIED, failureNoticeFor(refused("error.malformed_reply")))
        }

    @Test
    fun uploadProgress_namesTheUploadingEntry_fromEightChunks_andEachFileStartsFromItsOwn() =
        runTest {
            lateinit var vm: ThreadViewModel
            val atStart = mutableListOf<AttachmentUploadProgress?>()
            val reported = mutableListOf<AttachmentUploadProgress?>()
            val repository =
                RecordingRepository(duringUpload = { name, onProgress ->
                    atStart += vm.attachmentUploadProgress.value
                    when (name) {
                        "small" -> onProgress(2, 7)
                        "a" -> onProgress(4, 10)
                        else -> onProgress(1, 8)
                    }
                    reported += vm.attachmentUploadProgress.value
                })
            vm = vm(repository, ComposerDraftStore())
            vm.attach("a")
            vm.attach("small")
            vm.attach("b")
            val keys = vm.pendingAttachments.value.associate { it.displayName to it.key }

            vm.sendMessage("hi")
            advanceUntilIdle()

            assertEquals(listOf(null, null, null), atStart)
            assertEquals(
                listOf(AttachmentUploadProgress(keys.getValue("a"), 40), null, AttachmentUploadProgress(keys.getValue("b"), 12)),
                reported,
            )
            assertEquals(null, vm.attachmentUploadProgress.value)
        }

    @Test
    fun uploadProgress_clearsAfterAFailedUpload_orAThrownOne() =
        runTest {
            val report: suspend (String, (Int, Int) -> Unit) -> Unit = { _, onProgress -> onProgress(5, 10) }
            val refused = RecordingRepository(uploadOutcome = { AttachmentUploadResult.ReconnectRequired }, duringUpload = report)
            val thrown =
                RecordingRepository(duringUpload = { name, onProgress ->
                    report(name, onProgress)
                    throw IllegalStateException("connection lost")
                })
            for (repository in listOf(refused, thrown)) {
                val vm = vm(repository, ComposerDraftStore())
                vm.attach("a")

                vm.sendMessage("hi")
                advanceUntilIdle()

                assertEquals(null, vm.attachmentUploadProgress.value)
                assertFalse(vm.attachmentsSending.value)
            }
        }

    @Test
    fun pasteThenRevokeOriginal_sendsCapturedBytesAndNeverPublishesDeletedOriginal() =
        runTest {
            var readable = true
            val reader =
                AttachmentReader {
                    if (readable) AttachmentRead.Bytes("original PNG bytes".toByteArray()) else AttachmentRead.Unreadable
                }
            val capture = OwnedPasteCopy.capture(pasteFiles.root, reader, PASTED_URI, UnconfinedTestDispatcher(testScheduler))
            val copy = (capture as PasteCopyCapture.Captured).copy
            val store = ComposerDraftStore()
            val repository = RecordingRepository()
            val vm = vm(repository, store, reader)
            vm.addPickedAttachments(listOf(PickedAttachment(PASTED_URI, "paste.png", "image/png", capture.size, copy)))
            readable = false
            vm.onDraftChange("typed after copying something else")

            vm.sendMessage(vm.draft.value)
            advanceUntilIdle()

            assertEquals(listOf("paste.png" to "original PNG bytes"), repository.uploads)
            assertEquals(1, repository.sends.size)
            assertTrue(vm.pendingAttachments.value.isEmpty())
            assertEquals(
                0,
                pasteFiles.root
                    .listFiles()
                    .orEmpty()
                    .size,
            )
            assertEquals(null, store.sentOriginal(HOST, CONV, "id-paste.png"))
        }

    @Test
    fun pasteCopyLivesUntilSendFinishes_andANewEntrySurvivesItsCleanup() =
        runTest {
            val store = ComposerDraftStore()
            val copy = pasteCopy()
            val newCopy = pasteCopy()
            lateinit var vm: ThreadViewModel
            val repository =
                RecordingRepository(whileSending = {
                    assertEquals(
                        2,
                        pasteFiles.root
                            .listFiles()
                            .orEmpty()
                            .size,
                    )
                    vm.addAttachment("content://docs/new", "new", "image/png", 3, newCopy)
                }, neverReplies = true)
            vm = vm(repository, store)
            vm.addAttachment(PASTED_URI, "paste.png", "image/png", 3, copy)
            vm.sendMessage("send")
            advanceUntilIdle()
            assertEquals(listOf("new"), vm.pendingAttachments.value.map { it.displayName })
            assertEquals(
                2,
                pasteFiles.root
                    .listFiles()
                    .orEmpty()
                    .size,
            )
            // Cancelling the send after pre-send consumption releases its lease, not the new draft entry.
            ViewModelStore().apply {
                put("vm", vm)
                clear()
            }
            advanceUntilIdle()
            assertEquals(
                1,
                pasteFiles.root
                    .listFiles()
                    .orEmpty()
                    .size,
            )
            assertEquals(listOf("new"), store.attachmentsFor(HOST, CONV).map { it.displayName })
            val newer = store.attachmentsFor(HOST, CONV).single()
            assertArrayEquals(
                byteArrayOf(1, 2, 3),
                (requireNotNull(newer.ownedPaste).read(UnconfinedTestDispatcher(testScheduler)) as AttachmentRead.Bytes).bytes,
            )
            store.removeAttachment(HOST, CONV, newer.key)
            assertEquals(
                0,
                pasteFiles.root
                    .listFiles()
                    .orEmpty()
                    .size,
            )
        }

    @Test
    fun failedUploadRetainsPasteForRetry_andSuccessfulRetryDeletesIt() =
        runTest {
            var fails = true
            val store = ComposerDraftStore()
            val copy = pasteCopy()
            val repository =
                RecordingRepository(uploadOutcome = {
                    if (fails) AttachmentUploadResult.ConnectionLost else AttachmentUploadResult.Stored("paste-id")
                })
            val vm = vm(repository, store)
            vm.addAttachment(PASTED_URI, "paste.png", "image/png", 3, copy)
            vm.sendMessage("retry me")
            advanceUntilIdle()
            assertEquals(1, store.attachmentsFor(HOST, CONV).size)
            assertEquals(
                1,
                pasteFiles.root
                    .listFiles()
                    .orEmpty()
                    .size,
            )
            fails = false
            vm.sendMessage("retry me")
            advanceUntilIdle()
            assertEquals(1, repository.sends.size)
            assertEquals(
                0,
                pasteFiles.root
                    .listFiles()
                    .orEmpty()
                    .size,
            )
        }

    @Test
    fun evictingDraftDuringUploadDoesNotDeleteTheActiveSendCopy() =
        runTest {
            val store = ComposerDraftStore()
            val copy = pasteCopy()
            val repository =
                RecordingRepository(beforeUpload = {
                    store.clearHost(HOST)
                    assertEquals(
                        1,
                        pasteFiles.root
                            .listFiles()
                            .orEmpty()
                            .size,
                    )
                })
            val vm = vm(repository, store)
            vm.addAttachment(PASTED_URI, "paste.png", "image/png", 3, copy)
            vm.sendMessage("send")
            advanceUntilIdle()
            assertEquals(1, repository.sends.size)
            assertEquals(
                0,
                pasteFiles.root
                    .listFiles()
                    .orEmpty()
                    .size,
            )
        }

    @Test
    fun clearedViewModelDoesNotDeleteAStillPendingPasteCopy() =
        runTest {
            val store = ComposerDraftStore()
            val vm = vm(RecordingRepository(), store)
            vm.addAttachment(PASTED_URI, "paste.png", "image/png", 3, pasteCopy())
            ViewModelStore().apply {
                put("vm", vm)
                clear()
            }
            assertEquals(
                1,
                pasteFiles.root
                    .listFiles()
                    .orEmpty()
                    .size,
            )
            val recreated = vm(RecordingRepository(), store)
            recreated.sendMessage("send after navigation")
            advanceUntilIdle()
            assertEquals(
                0,
                pasteFiles.root
                    .listFiles()
                    .orEmpty()
                    .size,
            )
        }

    @Test
    fun cancelledBeforeSendStarts_releasesOnlyTheSendLease() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val store = ComposerDraftStore()
                val vm = vm(RecordingRepository(), store)
                vm.addAttachment(PASTED_URI, "paste.png", "image/png", 3, pasteCopy())
                vm.sendMessage("send")
                ViewModelStore().apply {
                    put("vm", vm)
                    clear()
                }
                advanceUntilIdle()
                assertEquals(
                    1,
                    pasteFiles.root
                        .listFiles()
                        .orEmpty()
                        .size,
                )
                store.clearHost(HOST)
                assertEquals(
                    0,
                    pasteFiles.root
                        .listFiles()
                        .orEmpty()
                        .size,
                )
            } finally {
                Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            }
        }

    private suspend fun TestScope.pasteCopy(): OwnedPasteCopy =
        (
            OwnedPasteCopy.capture(
                pasteFiles.root,
                AttachmentReader { AttachmentRead.Bytes(byteArrayOf(1, 2, 3)) },
                PASTED_URI,
                UnconfinedTestDispatcher(testScheduler),
            ) as PasteCopyCapture.Captured
        ).copy

    private fun picked(
        name: String,
        size: Long? = 5L,
    ) = PickedAttachment("content://docs/$name", name, "text/plain", size)

    private fun attachmentLogs() = logs.filter { "composer_attachment" in it }

    private companion object {
        const val PASTED_URI = "content://clipboard/image"
        const val HOST = "pyrybox"
        const val CONV = "seed-channel-personal"
    }
}
