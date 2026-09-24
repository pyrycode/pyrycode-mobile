package de.pyryco.mobile.ui.conversations.thread

import android.content.ClipData
import android.content.ClipDescription
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric

/** #934: which pasted clip items become attachments, and the provider's type check behind them. */
@RunWith(AndroidJUnit4::class)
class AttachmentPasteTest {
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
        ): Cursor? = null

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
