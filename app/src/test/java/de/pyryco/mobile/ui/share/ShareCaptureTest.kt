package de.pyryco.mobile.ui.share

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.AttachmentUploadLimit
import de.pyryco.mobile.data.repository.AttachmentUploadResult
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.ui.conversations.list.HostConversationTarget
import de.pyryco.mobile.ui.conversations.thread.AttachmentPasteTest
import de.pyryco.mobile.ui.conversations.thread.AttachmentRead
import de.pyryco.mobile.ui.conversations.thread.AttachmentReader
import de.pyryco.mobile.ui.conversations.thread.AttachmentSendFailure
import de.pyryco.mobile.ui.conversations.thread.ComposerDraftStore
import de.pyryco.mobile.ui.conversations.thread.ThreadViewModel
import de.pyryco.mobile.ui.conversations.thread.captureSharedAttachment
import de.pyryco.mobile.ui.conversations.thread.describePastedImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ShareCaptureTest {
    @get:Rule val copies = TemporaryFolder()

    @After fun resetDispatcher() = Dispatchers.resetMain()

    @Test fun genericFilesUseActualBytesDespiteUnknownAndDishonestMetadataAndPasteStillRejectsThem() =
        runTest {
            Robolectric.setupContentProvider(AttachmentPasteTest.TypedProvider::class.java, "share.test")
            val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
            val failures = mutableListOf<AttachmentSendFailure>()
            val bytes = byteArrayOf(1, 2, 3)
            for (name in listOf("unknown.txt", "huge.txt", "small.txt")) {
                val uri = Uri.parse("content://share.test/$name")
                assertNull(describePastedImage(resolver, uri, "de.pyryco.mobile"))
                val captured =
                    requireNotNull(
                        captureSharedAttachment(
                            resolver,
                            uri,
                            "de.pyryco.mobile",
                            copies.root,
                            failures::add,
                            AttachmentReader {
                                AttachmentRead.Bytes(bytes)
                            },
                        ),
                    )
                assertEquals(3L, captured.size)
                val copy = requireNotNull(captured.ownedPaste)
                assertArrayEquals(bytes, (copy.read(UnconfinedTestDispatcher(testScheduler)) as AttachmentRead.Bytes).bytes)
                copy.release()
            }
            val tooLarge =
                captureSharedAttachment(
                    resolver,
                    Uri.parse("content://share.test/small.txt"),
                    "de.pyryco.mobile",
                    copies.root,
                    failures::add,
                    AttachmentReader { AttachmentRead.Bytes(ByteArray(AttachmentUploadLimit.MAX_BYTES + 1)) },
                )
            assertNull(tooLarge)
            assertEquals(listOf(AttachmentSendFailure.TOO_LARGE), failures)
            assertEquals(0, copies.root.listFiles()?.size)
        }

    @Test fun forbiddenUrisNeverQueryOrReadPrivateFilesAndFailuresAreContentFree() =
        runTest {
            val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
            var reads = 0
            val failures = mutableListOf<AttachmentSendFailure>()
            val logs = mutableListOf<String>()
            val oldSink = RelayLog.sink
            val oldEnabled = RelayLog.enabled
            RelayLog.enabled = true
            RelayLog.sink = { _, _, line -> logs += line }
            try {
                for (uri in listOf(
                    "file:///private/secret",
                    "android.resource://other/id",
                    "content://de.pyryco.mobile.attachments/secret",
                    "content://0@de.pyryco.mobile.attachments/secret",
                )) {
                    assertNull(
                        captureSharedAttachment(
                            resolver,
                            Uri.parse(uri),
                            "de.pyryco.mobile",
                            copies.root,
                            failures::add,
                            AttachmentReader {
                                reads++
                                AttachmentRead.Bytes(byteArrayOf(1))
                            },
                        ),
                    )
                }
                assertNull(
                    captureSharedAttachment(
                        resolver,
                        Uri.parse(
                            "content://foreign/unreadable-secret",
                        ),
                        "de.pyryco.mobile",
                        copies.root,
                        failures::add,
                        AttachmentReader {
                            AttachmentRead.Unreadable
                        },
                    ),
                )
                assertEquals(0, reads)
                assertEquals(List(5) { AttachmentSendFailure.UNREADABLE }, failures)
                assertTrue(logs.contains("event=share_capture outcome=unreadable"))
                assertTrue(logs.none { it.contains("secret") || it.contains("content:") || it.contains("private") })
                assertEquals(0, copies.root.listFiles()?.size)
            } finally {
                RelayLog.sink = oldSink
                RelayLog.enabled = oldEnabled
            }
        }

    @Test fun revokeSourcesAfterCaptureThenSendReadsBothOwnedCopiesAndOnlySelectedConversation() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            Robolectric.setupContentProvider(AttachmentPasteTest.TypedProvider::class.java, "share.test")
            val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
            var readable = true
            val reads = mutableListOf<String>()
            val originals =
                mapOf(
                    "content://share.test/image.jpg" to byteArrayOf(1, 2, 3),
                    "content://share.test/doc.txt" to byteArrayOf(4, 5, 6),
                )
            val reader =
                AttachmentReader { uri ->
                    reads += uri
                    if (readable) AttachmentRead.Bytes(requireNotNull(originals[uri])) else AttachmentRead.Unreadable
                }
            val store = ComposerDraftStore()
            val intake =
                ShareIntakeViewModel(
                    store,
                    {
                        uri,
                        failure,
                        ->
                        captureSharedAttachment(resolver, uri, "de.pyryco.mobile", copies.root, failure, reader, dispatcher)
                    },
                    dispatcher,
                )
            val uploads = mutableListOf<Pair<String, ByteArray>>()
            val sends = mutableListOf<Pair<String, String>>()
            val delegate = FakeConversationRepository()
            val repository =
                object : ConversationRepository by delegate {
                    override suspend fun uploadAttachment(
                        conversationId: String,
                        bytes: ByteArray,
                        filename: String,
                        mimeType: String,
                        onProgress: (Int, Int) -> Unit,
                    ): AttachmentUploadResult {
                        uploads += conversationId to bytes
                        return AttachmentUploadResult.Stored("id-${uploads.size}")
                    }

                    override suspend fun sendMessage(
                        conversationId: String,
                        text: String,
                        attachments: List<MessageAttachment>,
                    ): Message {
                        sends += conversationId to text
                        return delegate.sendMessage(conversationId, text, attachments)
                    }
                }
            val thread =
                ThreadViewModel(
                    SavedStateHandle(mapOf("serverId" to "host", "conversationId" to "x")),
                    repository,
                    FakeConnectionStateSource(),
                    store,
                    attachmentReader = reader,
                    ioDispatcher = dispatcher,
                )
            intake.accept(SharePayload("shared caption", originals.keys.map(Uri::parse)))
            advanceUntilIdle()
            readable = false
            val readsAfterCapture = reads.size
            assertTrue(intake.select(HostConversationTarget("host", "x")))
            assertTrue(uploads.isEmpty())
            assertTrue(sends.isEmpty())
            thread.sendMessage(store.draftFor("host", "x"))
            advanceUntilIdle()
            assertEquals(readsAfterCapture, reads.size)
            assertEquals(listOf("x", "x"), uploads.map { it.first })
            originals.values.zip(uploads).forEach { (expected, uploaded) -> assertArrayEquals(expected, uploaded.second) }
            assertEquals(listOf("x" to "shared caption"), sends)
            assertTrue(store.attachmentsFor("host", "y").isEmpty())
            assertEquals(0, copies.root.listFiles()?.size)
        }
}
