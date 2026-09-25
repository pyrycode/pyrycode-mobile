package de.pyryco.mobile.ui.conversations.thread

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.conversations.components.AttachmentSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.random.Random

/** #985: the view intent, the provider's one root, and the save copy. */
@RunWith(AndroidJUnit4::class)
class AttachmentActionsTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val authority = "${context.packageName}.attachments"
    private val contentUri = Uri.parse("content://$authority/attachments/h/c/a")

    /**
     * FileProvider caches each authority's canonical roots in a static map for the life of the process. On a
     * device the data directory never moves; Robolectric gives every test a fresh one, so a root cached by an
     * earlier test would point at a directory that is gone and refuse every file here.
     */
    @Before
    fun forgetCachedProviderRoots() {
        val cache =
            FileProvider::class.java
                .getDeclaredField("sCache")
                .apply { isAccessible = true }
                .get(null) as MutableMap<*, *>
        synchronized(cache) { cache.clear() }
    }

    private fun storedFile(): File =
        File(context.noBackupFilesDir, "attachments/$HOST/$CONVERSATION/$ATTACHMENT").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }

    @Test
    fun viewIntent_isAViewOfThatUri_withTheHintAsType_andOnlyAReadGrant() {
        val intent = attachmentViewIntent(contentUri, "application/pdf")

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(contentUri, intent.data)
        assertEquals("application/pdf", intent.type)
        // Exactly the one read grant: never write, never persistable, never a whole prefix of the tree.
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
    }

    @Test
    fun intentType_keepsAConcreteHint_lowercased() {
        assertEquals("image/png", attachmentIntentType("image/png"))
        assertEquals("text/plain", attachmentIntentType(" Text/Plain "))
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            attachmentIntentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        )
    }

    @Test
    fun intentType_fallsBackToOctetStream_forNoHintOrAnUnsafeOne() {
        for (hint in listOf(
            null,
            "",
            "  ",
            "image",
            "image/",
            "/png",
            "*/*",
            "image/*",
            "text/plain; charset=utf-8",
            "text/<b>",
            "a".repeat(120) + "/" + "b".repeat(20),
            "application/vnd.android.package-archive",
            "Application/VND.Android.Package-Archive",
        )) {
            assertEquals("hint $hint", "application/octet-stream", attachmentIntentType(hint))
        }
    }

    @Test
    fun keptFile_underTheStoreRoot_isServedByTheAttachmentProvider() {
        val uri = attachmentContentUri(context, AttachmentSource.Kept(storedFile()))

        assertNotNull(uri)
        assertEquals("content", uri?.scheme)
        assertEquals(authority, uri?.authority)
        // Built from the stored file's own name, the attachment id — never a display name.
        assertEquals(ATTACHMENT, uri?.lastPathSegment)
    }

    @Test
    fun filesOutsideTheStoreRoot_areNeverServed() {
        val outside =
            listOf(
                File(context.noBackupFilesDir, "secret"),
                File(context.filesDir, "secret"),
                File(context.filesDir, "attachments/secret"),
                File(context.cacheDir, "secret"),
                File(context.noBackupFilesDir, "attachments-other/secret"),
            )
        for (file in outside) {
            file.parentFile?.mkdirs()
            file.writeBytes(byteArrayOf(9))
            assertNull("served $file", attachmentContentUri(context, AttachmentSource.Kept(file)))
        }
    }

    @Test
    fun original_isHandedOnOnlyWhenItIsAnotherAppsContentUri() {
        val foreign = "content://com.android.providers.media.documents/document/image%3A42"
        assertEquals(Uri.parse(foreign), attachmentContentUri(context, AttachmentSource.Original(foreign)))

        for (uri in listOf(
            "file:///data/data/${context.packageName}/files/x",
            "content://$authority/attachments/h/c/a",
            "content://${context.packageName}/x",
            "https://example.com/x",
        )) {
            assertNull(uri, attachmentContentUri(context, AttachmentSource.Original(uri)))
        }
    }

    @Test
    fun open_withNoAppToViewIt_reportsNoApp() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        shadowOf(activity.application).checkActivities(true)

        val notice = openAttachment(activity, AttachmentSource.Kept(storedFile()), "application/x-nothing-opens-this")

        assertEquals(AttachmentNotice.NO_APP, notice)
    }

    @Test
    fun open_startsTheViewer_withTheContentUriTypeAndReadGrant() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val viewer = ComponentName("com.example.viewer", "com.example.viewer.View")
        shadowOf(context.packageManager).apply {
            addActivityIfNotPresent(viewer)
            addIntentFilterForActivity(
                viewer,
                IntentFilter(Intent.ACTION_VIEW).apply {
                    addDataType("application/pdf")
                    addCategory(Intent.CATEGORY_DEFAULT)
                },
            )
        }
        shadowOf(activity.application).checkActivities(true)

        val notice = openAttachment(activity, AttachmentSource.Kept(storedFile()), "application/pdf")

        assertNull(notice)
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(authority, started.data?.authority)
        assertEquals("application/pdf", started.type)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, started.flags)
    }

    @Test
    fun open_ofAFileOutsideTheRoot_failsWithoutStartingAnything() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val stray = File(context.filesDir, "stray").apply { writeBytes(byteArrayOf(1)) }

        assertEquals(AttachmentNotice.OPEN_FAILED, openAttachment(activity, AttachmentSource.Kept(stray), null))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun copy_writesTheExactBytes_andDiscardsNothing() {
        val bytes = Random(985).nextBytes(3 * 8192 + 17)
        val output = ByteArrayOutputStream()
        var discarded = false

        val saved = copyAttachment({ ByteArrayInputStream(bytes) }, { output }, { discarded = true })

        assertTrue(saved)
        assertArrayEquals(bytes, output.toByteArray())
        assertFalse(discarded)
    }

    @Test
    fun copy_whoseSourceCannotBeRead_discardsTheDocument() {
        var discarded = false
        var outputOpened = false

        val saved =
            copyAttachment({ throw IOException("gone") }, {
                outputOpened = true
                ByteArrayOutputStream()
            }, { discarded = true })

        assertFalse(saved)
        assertFalse(outputOpened)
        assertTrue(discarded)
    }

    @Test
    fun copy_whoseWriteFails_discardsTheDocument() {
        var discarded = false
        val failing =
            object : OutputStream() {
                override fun write(b: Int) = throw IOException("disk full")
            }
        val input: InputStream = ByteArrayInputStream(byteArrayOf(1, 2, 3))

        val saved = copyAttachment({ input }, { failing }, { discarded = true })

        assertFalse(saved)
        assertTrue(discarded)
    }

    @Test
    fun copy_whoseDiscardAlsoFails_stillReportsAFailure() {
        val saved = copyAttachment({ throw IOException() }, { ByteArrayOutputStream() }, { throw SecurityException() })

        assertFalse(saved)
    }

    private fun registerMarkdownViewer() {
        val viewer = ComponentName("com.example.editor", "com.example.editor.Edit")
        shadowOf(context.packageManager).apply {
            addActivityIfNotPresent(viewer)
            addIntentFilterForActivity(
                viewer,
                IntentFilter(Intent.ACTION_VIEW).apply {
                    addDataType("text/*")
                    addCategory(Intent.CATEGORY_DEFAULT)
                },
            )
        }
    }

    private val note = MarkdownDocument("notes/Plan.md", "# Plan\n\nThe text on screen.")

    @Test
    fun sharedNote_isServedByTheAttachmentProvider_underTheNotesName() {
        val file = writeSharedNote(sharedNoteDirectory(context.noBackupFilesDir), note.name, note.text)

        val uri = attachmentContentUri(context, AttachmentSource.Kept(requireNotNull(file)))

        assertEquals("content", uri?.scheme)
        assertEquals(authority, uri?.authority)
        assertEquals("Plan.md", uri?.lastPathSegment)
    }

    @Test
    fun openNote_startsTheChooser_withAReadOnlyMarkdownView_ofTheTextOnScreen() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        registerMarkdownViewer()

        val notice = runBlocking { openNoteInAnotherApp(activity, note, "Open in another app", Dispatchers.Unconfined) }

        assertNull(notice)
        val chooser = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val grants =
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        assertEquals(0, chooser.flags and grants)
        val target = requireNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(Intent.ACTION_VIEW, target.action)
        assertEquals("text/markdown", target.type)
        assertEquals("content", target.data?.scheme)
        assertEquals(authority, target.data?.authority)
        assertEquals("Plan.md", target.data?.lastPathSegment)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, target.flags)
        val shared = File(sharedNoteDirectory(context.noBackupFilesDir), "Plan.md")
        assertEquals(note.text, shared.readText())
    }

    @Test
    fun openNote_withNoAppForMarkdown_reportsNoApp_andStartsNothing() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        val notice = runBlocking { openNoteInAnotherApp(activity, note, "Open in another app", Dispatchers.Unconfined) }

        assertEquals(AttachmentNotice.NO_APP, notice)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun openNote_thatCannotBeWritten_failsWithoutStartingAnything() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        registerMarkdownViewer()
        sharedNoteDirectory(context.noBackupFilesDir).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1))
        }

        val notice = runBlocking { openNoteInAnotherApp(activity, note, "Open in another app", Dispatchers.Unconfined) }

        assertEquals(AttachmentNotice.OPEN_FAILED, notice)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    private companion object {
        const val HOST = "5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8"
        const val CONVERSATION = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
        const val ATTACHMENT = "0f8fad5b-d9cb-469f-a165-70867728950e"
    }
}
