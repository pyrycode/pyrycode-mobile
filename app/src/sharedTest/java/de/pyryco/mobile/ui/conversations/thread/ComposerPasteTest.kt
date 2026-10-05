package de.pyryco.mobile.ui.conversations.thread

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** #934: the composer's Paste hands image content URIs to the attachment path and text to the draft. */
@RunWith(AndroidJUnit4::class)
class ComposerPasteTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var draft by mutableStateOf("")
    private val received = mutableListOf<List<Uri>>()

    private fun setBar() {
        composeRule.setContent {
            PyrycodeMobileTheme {
                ThreadInputBar(
                    text = draft,
                    onTextChange = { draft = it },
                    onSend = {},
                    onImagesReceived = { images, _ -> received += images },
                )
            }
        }
    }

    private fun putOnClipboard(clip: ClipData) {
        composeRule.runOnUiThread {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        }
    }

    private fun paste() {
        composeRule.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.PasteText)
        composeRule.waitForIdle()
    }

    /**
     * Robolectric only: a device's clipboard service refuses a content URI this app cannot read, and
     * the URI must stay foreign to pass `isForeignContentUri`. `ComposerImagePasteDeviceTest` is the
     * device proof, pasting a real `MediaStore` image.
     */
    @Test
    fun pastingAnImageUri_reachesTheAttachmentPath_andLeavesTheDraftEmpty() {
        assumeTrue(
            "Device clipboard rejects an unreadable URI; ComposerImagePasteDeviceTest covers the device",
            Build.FINGERPRINT == "robolectric",
        )
        setBar()
        val image = Uri.parse("content://media/external/images/media/7")
        putOnClipboard(ClipData(ClipDescription("image", arrayOf("image/png")), ClipData.Item(image)))

        paste()

        assertEquals(listOf(listOf(image)), received)
        assertEquals("", draft)
    }

    @Test
    fun pastingText_goesIntoTheDraft_andAddsNoAttachment() {
        setBar()
        putOnClipboard(ClipData.newPlainText("text", "hello"))

        paste()

        assertEquals("hello", draft)
        assertEquals(emptyList<List<Uri>>(), received)
    }

    @Test
    fun anOutsideDraftChange_replacesTheField_andTypingStillReachesTheDraft() {
        setBar()
        composeRule.runOnIdle { draft = "/model " }
        composeRule.waitForIdle()

        putOnClipboard(ClipData.newPlainText("text", "opus"))
        paste()

        assertEquals("/model opus", draft)
    }
}
