package de.pyryco.mobile.ui.conversations.thread

import android.content.ClipData
import android.content.ClipDescription
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.repository.AttachmentUploadLimit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric

/** #934: which pasted clip items become attachments, and the provider's type check behind them. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AttachmentPasteTest {
    @get:Rule val copies = TemporaryFolder()
    private val own = "de.pyryco.mobile"

    private fun description(vararg types: String) = ClipDescription("paste", arrayOf(*types))

    private fun uriItem(uri: String) = ClipData.Item(Uri.parse(uri))

    @Test
    fun aForeignContentUriUnderAnImageType_isAPastedImage() {
        assertTrue(isPastedImageItem(uriItem("content://media/external/images/1"), description("image/png"), own))
    }

    @Test
    fun textAFileUriOurOwnProviderAndANonImageType_areNot() {
        assertFalse(isPastedImageItem(ClipData.Item("hello"), description(ClipDescription.MIMETYPE_TEXT_PLAIN), own))
        assertFalse(isPastedImageItem(uriItem("file:///sdcard/a.png"), description("image/png"), own))
        assertFalse(isPastedImageItem(uriItem("content://$own.fileprovider/a.png"), description("image/png"), own))
        assertFalse(isPastedImageItem(uriItem("content://0@$own.fileprovider/a.png"), description("image/png"), own))
        assertFalse(isPastedImageItem(uriItem("content://media/external/file/2"), description("application/pdf"), own))
    }

    @Test
    fun theProvidersOwnType_decides_notTheClipsClaim() {
        Robolectric.setupContentProvider(TypedProvider::class.java, "paste.test")
        val resolver = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver

        val image = describePastedImage(resolver, Uri.parse("content://paste.test/photo.jpg"), own)
        assertEquals("image/jpeg", image?.mimeType)
        assertEquals("content://paste.test/photo.jpg", image?.uri)

        assertNull(describePastedImage(resolver, Uri.parse("content://paste.test/notes.txt"), own))
        assertNull(describePastedImage(resolver, Uri.parse("content://paste.test/untyped"), own))
    }

    @Test
    fun unknownAndDishonestProviderSizesUseActualBytes_andOversizeShowsPasteNotice() =
        runTest {
            Robolectric.setupContentProvider(TypedProvider::class.java, "paste.test")
            val resolver = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver
            val failures = mutableListOf<AttachmentSendFailure>()
            for (name in listOf("unknown.jpg", "huge.jpg", "small.jpg")) {
                val picked =
                    capturePastedImage(
                        resolver,
                        Uri.parse("content://paste.test/$name"),
                        own,
                        copies.root,
                        failures::add,
                        AttachmentReader { AttachmentRead.Bytes(byteArrayOf(1, 2, 3)) },
                    )
                assertEquals(3L, picked?.size)
                val copy = requireNotNull(picked?.ownedPaste)
                assertArrayEquals(byteArrayOf(1, 2, 3), (copy.read(UnconfinedTestDispatcher(testScheduler)) as AttachmentRead.Bytes).bytes)
                copy.release()
            }
            val oversized =
                capturePastedImage(
                    resolver,
                    Uri.parse("content://paste.test/small.jpg"),
                    own,
                    copies.root,
                    failures::add,
                    AttachmentReader { AttachmentRead.Bytes(ByteArray(AttachmentUploadLimit.MAX_BYTES + 1)) },
                )
            assertNull(oversized)
            assertEquals(listOf(AttachmentSendFailure.TOO_LARGE), failures)
            assertEquals(
                0,
                copies.root
                    .listFiles()
                    .orEmpty()
                    .size,
            )
        }

    @Test
    fun unreadableShowsExistingNotice_andExternalPrivateSourcesNeverReachReader() =
        runTest {
            Robolectric.setupContentProvider(TypedProvider::class.java, "paste.test")
            val resolver = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver
            val failures = mutableListOf<AttachmentSendFailure>()
            var reads = 0
            val reader =
                AttachmentReader {
                    reads++
                    AttachmentRead.Unreadable
                }
            assertNull(capturePastedImage(resolver, Uri.parse("content://paste.test/small.jpg"), own, copies.root, failures::add, reader))
            assertEquals(listOf(AttachmentSendFailure.UNREADABLE), failures)
            for (uri in listOf(
                "file:///private/image.jpg",
                "content://$own.fileprovider/image.jpg",
                "content://0@$own.fileprovider/image.jpg",
                "content://paste.test/notes.txt",
            )) {
                assertNull(capturePastedImage(resolver, Uri.parse(uri), own, copies.root, failures::add, reader))
            }
            assertEquals(1, reads)
            assertEquals(
                0,
                copies.root
                    .listFiles()
                    .orEmpty()
                    .size,
            )
        }

    @Test
    fun abandonedCaptureAtIoReturnDeletesItsUnpublishedCopy() =
        runTest {
            var ioWork: Runnable? = null
            val io =
                object : kotlinx.coroutines.CoroutineDispatcher() {
                    override fun dispatch(
                        context: kotlin.coroutines.CoroutineContext,
                        block: Runnable,
                    ) {
                        ioWork = block
                    }
                }
            var published = false
            val job =
                launch {
                    captureAndPublishPastedImages(listOf(Uri.parse("content://paste.test/image.jpg")), capture = {
                        val copy =
                            (
                                OwnedPasteCopy.capture(
                                    copies.root,
                                    AttachmentReader { AttachmentRead.Bytes(byteArrayOf(1)) },
                                    it.toString(),
                                    kotlinx.coroutines.Dispatchers.Unconfined,
                                ) as PasteCopyCapture.Captured
                            ).copy
                        PickedAttachment(it.toString(), "image.jpg", "image/jpeg", 1, copy)
                    }, publish = { published = true }, io = io)
                }
            runCurrent()
            requireNotNull(ioWork).run()
            assertEquals(
                1,
                copies.root
                    .listFiles()
                    .orEmpty()
                    .size,
            )
            job.cancel()
            advanceUntilIdle()
            assertFalse(published)
            assertEquals(
                0,
                copies.root
                    .listFiles()
                    .orEmpty()
                    .size,
            )
        }

    @Test
    fun countRefusedPasteIsDeletedBeforeCapturingTheNextItem() =
        runTest {
            val store = ComposerDraftStore()
            repeat(de.pyryco.mobile.data.network.MessageAttachmentIds.MAX) {
                store.addAttachment("host", "chat", "content://foreign/$it", "image.jpg", "image/jpeg", 1)
            }
            captureAndPublishPastedImages(List(2) { Uri.parse("content://paste.test/$it.jpg") }, capture = {
                assertEquals(
                    0,
                    copies.root
                        .listFiles()
                        .orEmpty()
                        .size,
                )
                val capture =
                    OwnedPasteCopy.capture(
                        copies.root,
                        AttachmentReader { AttachmentRead.Bytes(byteArrayOf(1)) },
                        it.toString(),
                        UnconfinedTestDispatcher(testScheduler),
                    ) as PasteCopyCapture.Captured
                PickedAttachment(it.toString(), "image.jpg", "image/jpeg", capture.size, capture.copy)
            }, publish = { items ->
                items.forEach {
                    assertEquals(
                        AttachmentAddOutcome.TOO_MANY,
                        store.addAttachment("host", "chat", it.uri, it.displayName, it.mimeType, it.size, it.ownedPaste),
                    )
                }
            }, io = UnconfinedTestDispatcher(testScheduler))
            assertEquals(
                0,
                copies.root
                    .listFiles()
                    .orEmpty()
                    .size,
            )
        }

    /** Types by extension, answers no metadata rows. */
    class TypedProvider : ContentProvider() {
        override fun onCreate() = true

        override fun getType(uri: Uri): String? =
            when {
                uri.path.orEmpty().endsWith(".jpg") -> "image/jpeg"
                uri.path.orEmpty().endsWith(".txt") -> "text/plain"
                else -> null
            }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? =
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
                addRow(
                    arrayOf<Any?>(
                        uri.lastPathSegment,
                        when {
                            uri.path.orEmpty().contains("huge") -> Long.MAX_VALUE
                            uri.path.orEmpty().contains("small") -> 1L
                            else -> null
                        },
                    ),
                )
            }

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0
    }
}
