package de.pyryco.mobile.ui.conversations.thread

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #934 AC#2: an image content URI pasted into the composer lands in the chat's attachment strip.
 *
 * Device-only: it needs the real clipboard service and a real provider — a `MediaStore` image this test
 * inserts, which the platform types and grants — so the paste crosses the same boundary a user's does.
 * Robolectric's clipboard and resolver are shadows and cannot stand in for that path.
 */
@RunWith(AndroidJUnit4::class)
class ComposerImagePasteDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val displayName = "pyry-paste-${System.nanoTime()}.png"
    private var inserted: Uri? = null

    @After
    fun deleteImage() {
        inserted?.let { context.contentResolver.delete(it, null, null) }
    }

    @Test
    fun pasteAndKeyboardInsert_captureBeforeTheProviderDisappears() {
        val image = insertPng()
        var hostView: View? = null
        val viewModel =
            ThreadViewModel(
                SavedStateHandle(mapOf("serverId" to "host", "conversationId" to "chat")),
                FakeConversationRepository(),
                FakeConnectionStateSource(),
                ComposerDraftStore(),
            )
        composeRule.setContent {
            val attachments by viewModel.pendingAttachments.collectAsStateWithLifecycle()
            val draft by viewModel.draft.collectAsStateWithLifecycle()
            PyrycodeMobileTheme {
                hostView = LocalView.current
                ThreadScreen(
                    state = ThreadUiState(conversationId = "chat", displayName = "Chat"),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    draft = draft,
                    onDraftChange = viewModel::onDraftChange,
                    attachments = attachments,
                    onAttachmentsPicked = viewModel::addPickedAttachments,
                )
            }
        }
        // The managed API 33 image can show a Bluetooth crash dialog above the test host.
        // Clipboard paste needs the host window focused before the action is sent.
        composeRule.waitUntil(10_000) {
            val focused = composeRule.runOnIdle { hostView?.hasWindowFocus() }
            if (focused == false) {
                ParcelFileDescriptor
                    .AutoCloseInputStream(
                        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                            "am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS",
                        ),
                    ).use { it.readBytes() }
            }
            focused == true
        }
        composeRule.runOnUiThread {
            context
                .getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newUri(context.contentResolver, "image", image))
        }

        composeRule.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.PasteText)

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithContentDescription(displayName).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(ATTACHMENT_STRIP_TEST_TAG).assertExists()
        val pending = viewModel.pendingAttachments.value.single()
        assertNotNull(pending.ownedPaste)
        val original = requireNotNull(context.contentResolver.openInputStream(image)).use { it.readBytes() }
        composeRule.runOnUiThread {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("replacement", "something else"))
        }
        // MediaStore grants under the target identity can survive clipboard replacement. Delete the
        // provider item too, making revocation deterministic instead of claiming replacement proves it.
        context.contentResolver.delete(image, null, null)
        inserted = null
        val read = runBlocking { requireNotNull(pending.ownedPaste).read() }
        assertArrayEquals(original, (read as AttachmentRead.Bytes).bytes)
        composeRule.runOnIdle { viewModel.removeAttachment(pending.key) }
        assertEquals("", viewModel.draft.value)

        // Drive the real editor's commitContent path, which shares the receiver with clipboard paste.
        val keyboardImage = insertPng()
        composeRule.onNode(hasSetTextAction()).performClick()
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            val connection = requireNotNull(hostView?.onCreateInputConnection(EditorInfo()))
            val info = InputContentInfo(keyboardImage, ClipDescription("image", arrayOf("image/png")), null)
            assertTrue(connection.commitContent(info, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null))
        }
        composeRule.waitUntil(10_000) { viewModel.pendingAttachments.value.size == 1 }
        val keyboardPending = viewModel.pendingAttachments.value.single()
        assertNotNull(keyboardPending.ownedPaste)
        context.contentResolver.delete(keyboardImage, null, null)
        inserted = null
        val keyboardRead = runBlocking { requireNotNull(keyboardPending.ownedPaste).read() }
        assertArrayEquals(original, (keyboardRead as AttachmentRead.Bytes).bytes)
        composeRule.runOnIdle { viewModel.removeAttachment(keyboardPending.key) }
    }

    private fun insertPng(): Uri {
        val resolver = context.contentResolver
        val values =
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            }
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        inserted = uri
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        requireNotNull(resolver.openOutputStream(uri)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return uri
    }
}
