package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.AttachmentRetrievalResult
import de.pyryco.mobile.data.repository.AttachmentUploadResult
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.ui.conversations.components.AttachmentAction
import de.pyryco.mobile.ui.conversations.components.AttachmentSource
import de.pyryco.mobile.ui.conversations.components.AttachmentTarget
import de.pyryco.mobile.ui.conversations.components.AttachmentViewState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.io.File

/** #984: a thread message's attachment, shown from the phone's own original or retrieved from its host. */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelAttachmentRetrievalTest {
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

    /** Answers each retrieval with the next queued outcome, recording which ids were asked for. */
    private class RetrievingRepository(
        vararg outcomes: AttachmentRetrievalResult,
        private val throwOnRetrieve: Throwable? = null,
        private val delegate: FakeConversationRepository = FakeConversationRepository(),
    ) : ConversationRepository by delegate {
        private val queue = ArrayDeque(outcomes.toList())
        val retrievals = mutableListOf<Pair<String, String>>()
        val events = mutableListOf<String>()

        override suspend fun retrieveAttachment(
            conversationId: String,
            attachmentId: String,
        ): AttachmentRetrievalResult {
            retrievals += conversationId to attachmentId
            throwOnRetrieve?.let { throw it }
            return queue.removeFirst()
        }

        override suspend fun uploadAttachment(
            conversationId: String,
            bytes: ByteArray,
            filename: String,
            mimeType: String,
            onProgress: (sentChunks: Int, totalChunks: Int) -> Unit,
        ): AttachmentUploadResult = AttachmentUploadResult.Stored("id-$filename")

        override suspend fun sendMessage(
            conversationId: String,
            text: String,
            attachments: List<MessageAttachment>,
        ): Message {
            events += "send"
            return Message("m1", "s1", Role.User, text, Instant.parse("2026-09-24T12:00:00Z"), false, attachments = attachments)
        }
    }

    private class ProbingReader(
        private val readable: Set<String>,
    ) : AttachmentReader {
        val probes = mutableListOf<String>()

        override suspend fun read(uri: String): AttachmentRead = AttachmentRead.Bytes(byteArrayOf(1))

        override suspend fun canRead(uri: String): Boolean {
            probes += uri
            return uri in readable
        }
    }

    private fun vm(
        repository: ConversationRepository,
        store: ComposerDraftStore = ComposerDraftStore(),
        reader: AttachmentReader = ProbingReader(emptySet()),
    ) = ThreadViewModel(
        SavedStateHandle(mapOf("serverId" to HOST, "conversationId" to CONV)),
        repository,
        FakeConnectionStateSource(),
        store,
        attachmentReader = reader,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    private val kept = File("/kept/$ATTACHMENT")

    @Test
    fun shown_retrievesOnce_andIsReadyWithTheRetrievedHints() =
        runTest {
            val repository = RetrievingRepository(AttachmentRetrievalResult.Retrieved(kept, "photo.png", "image/png"))
            val vm = vm(repository)

            vm.onAttachmentShown(MessageAttachment(ATTACHMENT))
            vm.onAttachmentShown(MessageAttachment(ATTACHMENT))
            advanceUntilIdle()

            assertEquals(listOf(CONV to ATTACHMENT), repository.retrievals)
            assertEquals(
                AttachmentViewState.Ready(AttachmentSource.Kept(kept), "photo.png", "image/png"),
                vm.attachmentStates.value[ATTACHMENT],
            )
        }

    @Test
    fun notFound_isFinal_andRetryDoesNothing() =
        runTest {
            val repository = RetrievingRepository(AttachmentRetrievalResult.NotFound)
            val vm = vm(repository)

            vm.onAttachmentShown(MessageAttachment(ATTACHMENT))
            advanceUntilIdle()
            vm.onRetryAttachment(ATTACHMENT)
            advanceUntilIdle()

            assertEquals(AttachmentViewState.NotFound, vm.attachmentStates.value[ATTACHMENT])
            assertEquals(1, repository.retrievals.size)
        }

    @Test
    fun failure_isRetryable_andRetryStartsANewRetrieval() =
        runTest {
            val repository =
                RetrievingRepository(
                    AttachmentRetrievalResult.Unavailable,
                    AttachmentRetrievalResult.Retrieved(kept, "notes.txt", "text/plain"),
                )
            val vm = vm(repository)

            vm.onAttachmentShown(MessageAttachment(ATTACHMENT))
            advanceUntilIdle()
            assertEquals(AttachmentViewState.Failed, vm.attachmentStates.value[ATTACHMENT])
            // Being shown again is not a retry: only the control starts one.
            vm.onAttachmentShown(MessageAttachment(ATTACHMENT))
            advanceUntilIdle()
            assertEquals(1, repository.retrievals.size)

            vm.onRetryAttachment(ATTACHMENT)
            advanceUntilIdle()

            assertEquals(2, repository.retrievals.size)
            assertEquals(
                AttachmentViewState.Ready(AttachmentSource.Kept(kept), "notes.txt", "text/plain"),
                vm.attachmentStates.value[ATTACHMENT],
            )
        }

    @Test
    fun tooLargeAndInvalid_areFailures() =
        runTest {
            val repository = RetrievingRepository(AttachmentRetrievalResult.TooLarge, AttachmentRetrievalResult.Invalid)
            val vm = vm(repository)

            vm.onAttachmentShown(MessageAttachment(ATTACHMENT))
            vm.onAttachmentShown(MessageAttachment(OTHER))
            advanceUntilIdle()

            assertEquals(AttachmentViewState.Failed, vm.attachmentStates.value[ATTACHMENT])
            assertEquals(AttachmentViewState.Failed, vm.attachmentStates.value[OTHER])
        }

    @Test
    fun aRepositoryThatThrows_isAFailure_notACrash() =
        runTest {
            val vm = vm(RetrievingRepository(throwOnRetrieve = IllegalStateException("no store")))

            vm.onAttachmentShown(MessageAttachment(ATTACHMENT))
            advanceUntilIdle()

            assertEquals(AttachmentViewState.Failed, vm.attachmentStates.value[ATTACHMENT])
        }

    @Test
    fun readableSentOriginal_isShown_andNothingIsRetrieved() =
        runTest {
            val store = ComposerDraftStore()
            store.recordSentOriginals(HOST, CONV, mapOf(ATTACHMENT to ORIGINAL))
            val repository = RetrievingRepository()
            val vm = vm(repository, store, ProbingReader(setOf(ORIGINAL)))

            vm.onAttachmentShown(MessageAttachment(ATTACHMENT))
            advanceUntilIdle()

            assertTrue(repository.retrievals.isEmpty())
            assertEquals(
                AttachmentViewState.Ready(AttachmentSource.Original(ORIGINAL), null, null),
                vm.attachmentStates.value[ATTACHMENT],
            )
        }

    @Test
    fun unreadableSentOriginal_fallsBackToRetrieval() =
        runTest {
            val store = ComposerDraftStore()
            store.recordSentOriginals(HOST, CONV, mapOf(ATTACHMENT to ORIGINAL))
            val reader = ProbingReader(emptySet())
            val repository = RetrievingRepository(AttachmentRetrievalResult.Retrieved(kept, "a.pdf", "application/pdf"))
            val vm = vm(repository, store, reader)

            vm.onAttachmentShown(MessageAttachment(ATTACHMENT))
            advanceUntilIdle()

            assertEquals(listOf(ORIGINAL), reader.probes)
            assertEquals(listOf(CONV to ATTACHMENT), repository.retrievals)
        }

    @Test
    fun send_recordsEachOriginal_beforeTheSendReachesTheRepository() =
        runTest {
            val store = ComposerDraftStore()
            val repository = RetrievingRepository()
            var recordedAtSend: String? = null
            val probing =
                object : ConversationRepository by repository {
                    override suspend fun sendMessage(
                        conversationId: String,
                        text: String,
                        attachments: List<MessageAttachment>,
                    ): Message {
                        recordedAtSend = store.sentOriginal(HOST, CONV, "id-a")
                        return repository.sendMessage(conversationId, text, attachments)
                    }
                }
            val vm = vm(probing, store)
            vm.addAttachment("content://docs/a", "a", "text/plain", 1L)

            vm.sendMessage("hi")
            advanceUntilIdle()

            assertEquals("content://docs/a", recordedAtSend)
            assertEquals(listOf("send"), repository.events)
            // Recording an original starts nothing: a load waits for the row to be shown.
            assertFalse(vm.attachmentStates.value.containsKey("id-a"))
        }

    @Test
    fun logs_carryTheIdAndAStaticOutcome_neverANameOrUri() =
        runTest {
            val store = ComposerDraftStore()
            store.recordSentOriginals(HOST, CONV, mapOf(OTHER to ORIGINAL))
            val repository = RetrievingRepository(AttachmentRetrievalResult.Retrieved(kept, "secret-name.png", "image/png"))
            val vm = vm(repository, store, ProbingReader(setOf(ORIGINAL)))

            vm.onAttachmentShown(MessageAttachment(ATTACHMENT))
            vm.onAttachmentShown(MessageAttachment(OTHER))
            advanceUntilIdle()

            val loads = logs.filter { it.startsWith("event=thread_attachment_load") }
            assertEquals(
                listOf(
                    "event=thread_attachment_load id=$ATTACHMENT outcome=retrieved",
                    "event=thread_attachment_load id=$OTHER outcome=original",
                ),
                loads,
            )
            assertTrue(logs.none { "secret-name" in it || "content://" in it || "/kept" in it })
        }

    /** Collects [ThreadViewModel.attachmentLoads] for the rest of the test. */
    private fun TestScope.loadsOf(vm: ThreadViewModel): List<AttachmentLoaded> {
        val loads = mutableListOf<AttachmentLoaded>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.attachmentLoads.collect { loads += it } }
        return loads
    }

    @Test
    fun shown_retrievesTheImageOnly_andNothingForAKnownNonImageFile() =
        runTest {
            val repository = RetrievingRepository(AttachmentRetrievalResult.Retrieved(kept, "photo.png", "image/png"))
            val vm = vm(repository)

            vm.onAttachmentShown(MessageAttachment(ATTACHMENT, "photo.png", "image/png"))
            vm.onAttachmentShown(MessageAttachment(OTHER, "report.pdf", "application/pdf"))
            advanceUntilIdle()

            assertEquals(listOf(CONV to ATTACHMENT), repository.retrievals)
            assertFalse(vm.attachmentStates.value.containsKey(OTHER))
        }

    @Test
    fun shownNameOnly_retrievesAnImageExtension_andDefersAnyOther() =
        runTest {
            val repository =
                RetrievingRepository(
                    AttachmentRetrievalResult.Retrieved(kept, "fetched.bin", "application/octet-stream"),
                    AttachmentRetrievalResult.Retrieved(kept, "fetched.bin", "application/octet-stream"),
                )
            val vm = vm(repository)

            vm.onAttachmentShown(MessageAttachment(ATTACHMENT, "offer.png"))
            vm.onAttachmentShown(MessageAttachment(OTHER, "offer.PNG"))
            vm.onAttachmentShown(MessageAttachment(THIRD, "offer.pdf"))
            vm.onAttachmentShown(MessageAttachment(FOURTH, "README"))
            advanceUntilIdle()

            assertEquals(listOf(CONV to ATTACHMENT, CONV to OTHER), repository.retrievals)
        }

    @Test
    fun tappingADeferredFile_loadsItOnce_andDeliversOneOpen() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val repository =
                object : ConversationRepository by FakeConversationRepository() {
                    var calls = 0

                    override suspend fun retrieveAttachment(
                        conversationId: String,
                        attachmentId: String,
                    ): AttachmentRetrievalResult {
                        calls++
                        gate.await()
                        return AttachmentRetrievalResult.Retrieved(kept, "fetched.pdf", "application/pdf")
                    }
                }
            val vm = vm(repository)
            val loads = loadsOf(vm)
            val reference = MessageAttachment(ATTACHMENT, "report.pdf")

            vm.onAttachmentShown(reference)
            vm.onAttachmentRequested(reference, AttachmentAction.OPEN)
            assertEquals(AttachmentViewState.Loading, vm.attachmentStates.value[ATTACHMENT])
            vm.onAttachmentRequested(reference, AttachmentAction.SAVE)
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, repository.calls)
            // The reference's own name wins; retrieval fills the type it left unknown.
            assertEquals(
                listOf(
                    AttachmentLoaded(
                        AttachmentTarget(ATTACHMENT, "report.pdf", "application/pdf"),
                        AttachmentSource.Kept(kept),
                        AttachmentAction.OPEN,
                    ),
                ),
                loads,
            )
        }

    @Test
    fun longPressingADeferredFile_deliversASave() =
        runTest {
            val repository = RetrievingRepository(AttachmentRetrievalResult.Retrieved(kept, "fetched.bin", "application/octet-stream"))
            val vm = vm(repository)
            val loads = loadsOf(vm)

            vm.onAttachmentRequested(MessageAttachment(ATTACHMENT, "report.pdf", "application/pdf"), AttachmentAction.SAVE)
            advanceUntilIdle()

            assertEquals(listOf(AttachmentAction.SAVE), loads.map { it.action })
            assertEquals(AttachmentTarget(ATTACHMENT, "report.pdf", "application/pdf"), loads.single().target)
        }

    @Test
    fun aTappedLoadThatFails_deliversNothing_andItsRetryOpensNothingByItself() =
        runTest {
            val repository =
                RetrievingRepository(
                    AttachmentRetrievalResult.Unavailable,
                    AttachmentRetrievalResult.Retrieved(kept, "fetched.bin", "application/octet-stream"),
                    AttachmentRetrievalResult.NotFound,
                )
            val vm = vm(repository)
            val loads = loadsOf(vm)

            vm.onAttachmentRequested(MessageAttachment(ATTACHMENT, "report.pdf"), AttachmentAction.OPEN)
            advanceUntilIdle()
            assertEquals(AttachmentViewState.Failed, vm.attachmentStates.value[ATTACHMENT])
            // A tap on a failed row is not a retry: only the control starts one.
            vm.onAttachmentRequested(MessageAttachment(ATTACHMENT, "report.pdf"), AttachmentAction.OPEN)
            vm.onRetryAttachment(ATTACHMENT)
            advanceUntilIdle()
            vm.onAttachmentRequested(MessageAttachment(OTHER, "gone.zip"), AttachmentAction.SAVE)
            advanceUntilIdle()

            assertEquals(3, repository.retrievals.size)
            assertEquals(
                AttachmentViewState.Ready(AttachmentSource.Kept(kept), "fetched.bin", "application/octet-stream"),
                vm.attachmentStates.value[ATTACHMENT],
            )
            assertEquals(AttachmentViewState.NotFound, vm.attachmentStates.value[OTHER])
            assertTrue(loads.isEmpty())
        }

    @Test
    fun tappingAFileThisPhoneSent_readsItsOriginal_withNoRetrieval() =
        runTest {
            val store = ComposerDraftStore()
            store.recordSentOriginals(HOST, CONV, mapOf(ATTACHMENT to ORIGINAL))
            val repository = RetrievingRepository()
            val vm = vm(repository, store, ProbingReader(setOf(ORIGINAL)))
            val loads = loadsOf(vm)
            val reference = MessageAttachment(ATTACHMENT, "notes.txt", "text/plain")

            vm.onAttachmentShown(reference)
            advanceUntilIdle()
            assertFalse(vm.attachmentStates.value.containsKey(ATTACHMENT))
            vm.onAttachmentRequested(reference, AttachmentAction.OPEN)
            advanceUntilIdle()

            assertTrue(repository.retrievals.isEmpty())
            assertEquals(
                listOf(
                    AttachmentLoaded(
                        AttachmentTarget(ATTACHMENT, "notes.txt", "text/plain"),
                        AttachmentSource.Original(ORIGINAL),
                        AttachmentAction.OPEN,
                    ),
                ),
                loads,
            )
        }

    @Test
    fun requestLogs_carryTheIdAndStaticCodes_neverANameOrUri() =
        runTest {
            val repository =
                RetrievingRepository(
                    AttachmentRetrievalResult.Retrieved(kept, "fetched.bin", "application/octet-stream"),
                    AttachmentRetrievalResult.Unavailable,
                )
            val vm = vm(repository)
            loadsOf(vm)

            vm.onAttachmentRequested(MessageAttachment(ATTACHMENT, "secret-name.pdf"), AttachmentAction.OPEN)
            vm.onAttachmentRequested(MessageAttachment(OTHER, "secret-name.zip"), AttachmentAction.SAVE)
            advanceUntilIdle()

            assertEquals(
                listOf(
                    "event=thread_attachment_request id=$ATTACHMENT action=open",
                    "event=thread_attachment_request id=$ATTACHMENT outcome=delivered",
                    "event=thread_attachment_request id=$OTHER action=save",
                    "event=thread_attachment_request id=$OTHER outcome=dropped",
                ),
                logs.filter { it.startsWith("event=thread_attachment_request") },
            )
            assertTrue(logs.none { "secret-name" in it || "/kept" in it })
        }

    private fun keptMarkdown(bytes: ByteArray): File =
        File.createTempFile("kept", null).apply { deleteOnExit() }.also { it.writeBytes(bytes) }

    @Test
    fun openMarkdown_readsTheKeptFile_thenNavigatesByIdOnly() =
        runTest {
            val file = keptMarkdown("# Plan".toByteArray())
            val repository = RetrievingRepository(AttachmentRetrievalResult.Retrieved(file, "Plan.md", "text/markdown"))
            val vm = vm(repository)
            val navigation = mutableListOf<ThreadNavigation>()
            val failures = mutableListOf<Unit>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.navigationEvents.collect { navigation += it } }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.markdownOpenFailures.collect { failures += it } }

            vm.onOpenMarkdownAttachment(ATTACHMENT)
            advanceUntilIdle()

            assertEquals(listOf(CONV to ATTACHMENT), repository.retrievals)
            assertEquals(listOf<ThreadNavigation>(ThreadNavigation.OpenMarkdown(ATTACHMENT)), navigation)
            assertTrue(failures.isEmpty())
        }

    @Test
    fun openMarkdown_withBadUtf8OrAFailedRetrieval_staysAndSaysOpenFailed() =
        runTest {
            val bad = keptMarkdown(byteArrayOf(0x41, 0x80.toByte()))
            val repository =
                RetrievingRepository(
                    AttachmentRetrievalResult.Retrieved(bad, "bad.md", "text/markdown"),
                    AttachmentRetrievalResult.Unavailable,
                )
            val vm = vm(repository)
            val navigation = mutableListOf<ThreadNavigation>()
            val failures = mutableListOf<Unit>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.navigationEvents.collect { navigation += it } }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.markdownOpenFailures.collect { failures += it } }

            vm.onOpenMarkdownAttachment(ATTACHMENT)
            advanceUntilIdle()
            vm.onOpenMarkdownAttachment(ATTACHMENT)
            advanceUntilIdle()

            assertTrue(navigation.isEmpty())
            assertEquals(2, failures.size)
        }

    @Test
    fun openMarkdown_ignoresATapWhileAnOpenIsInFlight() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val file = keptMarkdown("text".toByteArray())
            val repository =
                object : ConversationRepository by FakeConversationRepository() {
                    var calls = 0

                    override suspend fun retrieveAttachment(
                        conversationId: String,
                        attachmentId: String,
                    ): AttachmentRetrievalResult {
                        calls++
                        gate.await()
                        return AttachmentRetrievalResult.Retrieved(file, "a.md", "text/markdown")
                    }
                }
            val vm = vm(repository)
            val navigation = mutableListOf<ThreadNavigation>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.navigationEvents.collect { navigation += it } }

            vm.onOpenMarkdownAttachment(ATTACHMENT)
            vm.onOpenMarkdownAttachment(ATTACHMENT)
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, repository.calls)
            assertEquals(1, navigation.size)
        }

    @Test
    fun openMarkdown_logsTheIdAndAStaticOutcome_neverTheNameOrText() =
        runTest {
            val file = keptMarkdown("secret body".toByteArray())
            val repository =
                RetrievingRepository(
                    AttachmentRetrievalResult.Retrieved(file, "secret-name.md", "text/markdown"),
                    AttachmentRetrievalResult.NotFound,
                )
            val vm = vm(repository)

            vm.onOpenMarkdownAttachment(ATTACHMENT)
            advanceUntilIdle()
            vm.onOpenMarkdownAttachment(OTHER)
            advanceUntilIdle()

            assertEquals(
                listOf(
                    "event=thread_attachment_open id=$ATTACHMENT outcome=reader",
                    "event=thread_attachment_open id=$OTHER outcome=failed",
                ),
                logs.filter { it.startsWith("event=thread_attachment_open") },
            )
            assertTrue(logs.none { "secret" in it || file.path in it })
        }

    private companion object {
        const val HOST = "pyrybox"
        const val CONV = "c1"
        const val ATTACHMENT = "0f8fad5b-d9cb-469f-a165-70867728950e"
        const val OTHER = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
        const val THIRD = "16fd2706-8baf-433b-82eb-8c7fada847da"
        const val FOURTH = "886313e1-3b8a-5372-9b90-0c9aee199e5d"
        const val ORIGINAL = "content://docs/original"
    }
}
