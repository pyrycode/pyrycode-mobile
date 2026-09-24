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
import de.pyryco.mobile.ui.conversations.components.AttachmentSource
import de.pyryco.mobile.ui.conversations.components.AttachmentViewState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    )

    private val kept = File("/kept/$ATTACHMENT")

    @Test
    fun shown_retrievesOnce_andIsReadyWithTheRetrievedHints() =
        runTest {
            val repository = RetrievingRepository(AttachmentRetrievalResult.Retrieved(kept, "photo.png", "image/png"))
            val vm = vm(repository)

            vm.onAttachmentShown(ATTACHMENT)
            vm.onAttachmentShown(ATTACHMENT)
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

            vm.onAttachmentShown(ATTACHMENT)
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

            vm.onAttachmentShown(ATTACHMENT)
            advanceUntilIdle()
            assertEquals(AttachmentViewState.Failed, vm.attachmentStates.value[ATTACHMENT])
            // Being shown again is not a retry: only the control starts one.
            vm.onAttachmentShown(ATTACHMENT)
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

            vm.onAttachmentShown(ATTACHMENT)
            vm.onAttachmentShown(OTHER)
            advanceUntilIdle()

            assertEquals(AttachmentViewState.Failed, vm.attachmentStates.value[ATTACHMENT])
            assertEquals(AttachmentViewState.Failed, vm.attachmentStates.value[OTHER])
        }

    @Test
    fun aRepositoryThatThrows_isAFailure_notACrash() =
        runTest {
            val vm = vm(RetrievingRepository(throwOnRetrieve = IllegalStateException("no store")))

            vm.onAttachmentShown(ATTACHMENT)
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

            vm.onAttachmentShown(ATTACHMENT)
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

            vm.onAttachmentShown(ATTACHMENT)
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

            vm.sendMessage("")
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

            vm.onAttachmentShown(ATTACHMENT)
            vm.onAttachmentShown(OTHER)
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

    private companion object {
        const val HOST = "pyrybox"
        const val CONV = "c1"
        const val ATTACHMENT = "0f8fad5b-d9cb-469f-a165-70867728950e"
        const val OTHER = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
        const val ORIGINAL = "content://docs/original"
    }
}
