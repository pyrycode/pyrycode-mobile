package de.pyryco.mobile.ui.conversations.thread

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.repository.AttachmentUploadLimit
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile

/**
 * #1747: an image refused while pasting or inserted from the keyboard says why in the top Error pill,
 * through the composer's real paste receiver, and never in the bottom SnackbarHost.
 */
@RunWith(AndroidJUnit4::class)
class ThreadPastedImageErrorTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun registerProvider() {
        Robolectric.setupContentProvider(RefusingImageProvider::class.java, AUTHORITY)
    }

    @Test
    fun unreadableAndOversizedPastes_queueInertTopPills_inArrivalOrder() {
        rule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = ThreadUiState("c1", "Thread"),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                )
            }
        }
        rule.mainClock.autoAdvance = false
        val clip = ClipData(ClipDescription("images", arrayOf("image/jpeg")), ClipData.Item(Uri.parse("content://$AUTHORITY/unreadable.jpg")))
        clip.addItem(ClipData.Item(Uri.parse("content://$AUTHORITY/huge.jpg")))
        rule.runOnUiThread { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip) }

        rule.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.PasteText)

        val resources = context.resources
        assertOnlyAPill(AttachmentSendFailure.UNREADABLE.text(resources))
        rule.mainClock.advanceTimeBy(4_100)
        assertOnlyAPill(AttachmentSendFailure.TOO_LARGE.text(resources))
        rule.mainClock.advanceTimeBy(4_100)
        rule.waitForIdle()
        rule.onNodeWithTag("transient_error_notice").assertDoesNotExist()
    }

    private fun assertOnlyAPill(text: String) {
        rule.waitUntil(10_000) {
            rule.mainClock.advanceTimeByFrame()
            rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        // One node only: the pill. A snackbar would add a second node with the same copy.
        rule.onAllNodesWithText(text).assertCountEquals(1)
        rule.onAllNodes(hasText(text) and hasTestTag("transient_error_notice")).assertCountEquals(1)
        rule.onNodeWithTag("transient_error_notice").assertIsDisplayed().assertHasNoClickAction()
    }

    /** Types `.jpg` as an image; `unreadable` cannot be opened, `huge` is one byte over the upload limit. */
    class RefusingImageProvider : ContentProvider() {
        override fun onCreate() = true

        override fun getType(uri: Uri): String? = if (uri.path.orEmpty().endsWith(".jpg")) "image/jpeg" else null

        override fun openFile(
            uri: Uri,
            mode: String,
        ): ParcelFileDescriptor {
            if (uri.lastPathSegment != "huge.jpg") throw FileNotFoundException()
            val file = File.createTempFile("paste-1747", ".jpg").apply { deleteOnExit() }
            RandomAccessFile(file, "rw").use { it.setLength(AttachmentUploadLimit.MAX_BYTES + 1L) }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any?>(uri.lastPathSegment, 1L)) }

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

    private companion object {
        const val AUTHORITY = "paste1747.test"
    }
}
