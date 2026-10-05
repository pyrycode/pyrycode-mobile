package de.pyryco.mobile.ui.conversations.thread

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.view.View
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** #933: the composer's attachment picker, strip, removal, send enablement and per-chat strips. */
@RunWith(AndroidJUnit4::class)
class ComposerAttachmentStripTest {
    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule val pasteFiles = TemporaryFolder()

    /** Answers every launch at once with [result], recording what the picker was asked for. */
    private class FakePickerRegistry(
        private val result: List<Uri>,
    ) : ActivityResultRegistry() {
        val launches = mutableListOf<Any?>()

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            launches += input
            dispatchResult(requestCode, result)
        }
    }

    @Test
    fun noAttachments_rendersNoStrip() {
        setScreen(attachments = emptyList())
        composeRule.onNodeWithTag(ATTACHMENT_STRIP_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun tiles_renderInTheGivenOrder_andAFailedThumbnailFallsBackToTheFileTile() {
        setScreen(attachments = listOf(entry(1, "b-report.pdf"), entry(2, "a-photo.jpg", "image/jpeg"), entry(3, "notes")))
        composeRule.waitForIdle()

        val lefts =
            listOf("b-report.pdf", "a-photo.jpg", "notes").map {
                composeRule
                    .onNodeWithContentDescription(it)
                    .fetchSemanticsNode()
                    .boundsInRoot.left
            }
        assertEquals(lefts.sorted(), lefts)
        // Robolectric has no provider behind the image's URI, so its thumbnail fails to the file tile's label.
        composeRule.onNodeWithText("PDF").assertIsDisplayed()
        composeRule.onNodeWithText("JPG").assertIsDisplayed()
        composeRule.onNodeWithText("File").assertIsDisplayed()
    }

    @Test
    fun pendingFileTiles_haveTheFigmaSizeGapAndRemoveOverlap() {
        setScreen(attachments = listOf(entry(1, "one.pdf"), entry(2, "two.pdf")))

        val first = composeRule.onNodeWithContentDescription("one.pdf").getUnclippedBoundsInRoot()
        val second = composeRule.onNodeWithContentDescription("two.pdf").getUnclippedBoundsInRoot()
        val remove = composeRule.onNodeWithContentDescription("Remove one.pdf").getUnclippedBoundsInRoot()
        assertEquals(45.dp.value, first.width.value, 0.5f)
        assertEquals(60.dp.value, first.height.value, 0.5f)
        assertEquals(12.dp.value, (second.left - first.right).value, 0.5f)
        assertEquals((first.right - 15.dp).value, remove.left.value, 0.5f)
        assertEquals((first.top - 5.dp).value, remove.top.value, 0.5f)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun ownedPasteThumbnailLoadsWithoutTheOriginalProvider() {
        val bytes =
            ByteArrayOutputStream().use { output ->
                Bitmap.createBitmap(1024, 512, Bitmap.Config.ARGB_8888).apply {
                    compress(Bitmap.CompressFormat.PNG, 100, output)
                    recycle()
                }
                output.toByteArray()
            }
        val copy =
            runBlocking {
                (
                    OwnedPasteCopy.capture(pasteFiles.root, AttachmentReader { AttachmentRead.Bytes(bytes) }, "content://missing/image")
                        as PasteCopyCapture.Captured
                ).copy
            }
        try {
            setScreen(attachments = listOf(entry(1, "paste.png", "image/png").copy(ownedPaste = copy)))
            composeRule.onNodeWithContentDescription("paste.png").assertIsDisplayed()
            composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("PNG").fetchSemanticsNodes().isEmpty() }
            val tile = composeRule.onNodeWithContentDescription("paste.png").getUnclippedBoundsInRoot()
            assertEquals(45.dp.value, tile.width.value, 0.5f)
            assertEquals(60.dp.value, tile.height.value, 0.5f)
        } finally {
            copy.release()
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun ownedJpegThumbnail_appliesExifRotationWithoutTheOriginalProvider() {
        assertOrientedThumbnail(6, listOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun ownedJpegThumbnail_appliesExifMirroringWithoutTheOriginalProvider() {
        assertOrientedThumbnail(2, listOf(Color.GREEN, Color.RED, Color.YELLOW, Color.BLUE))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun ownedJpegThumbnail_appliesExifRotationAndMirroringWithoutTheOriginalProvider() {
        assertOrientedThumbnail(7, listOf(Color.YELLOW, Color.GREEN, Color.BLUE, Color.RED))
    }

    private fun assertOrientedThumbnail(
        orientation: Int,
        expected: List<Int>,
    ) {
        val bytes = orientedJpeg(orientation)
        val copy =
            runBlocking {
                (
                    OwnedPasteCopy.capture(pasteFiles.root, AttachmentReader { AttachmentRead.Bytes(bytes) }, "content://missing/image")
                        as PasteCopyCapture.Captured
                ).copy
            }
        var view: View? = null
        try {
            composeRule.setContent {
                view = LocalView.current
                PyrycodeMobileTheme {
                    ComposerAttachmentStrip(listOf(entry(1, "paste.jpg", "image/jpeg").copy(ownedPaste = copy)), false, {})
                }
            }
            composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("JPG").fetchSemanticsNodes().isEmpty() }
            val tile = composeRule.onNodeWithContentDescription("paste.jpg").fetchSemanticsNode().boundsInRoot
            assertEquals(45.dp.value, tile.width / composeRule.density.density, 0.5f)
            assertEquals(60.dp.value, tile.height / composeRule.density.density, 0.5f)
            composeRule.runOnIdle {
                val root = checkNotNull(view)
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                try {
                    // Draw the real composition; captureToImage cannot settle its redraw on Robolectric.
                    root.draw(Canvas(bitmap))
                    val positions = listOf(0.25f to 0.3f, 0.75f to 0.3f, 0.25f to 0.75f, 0.75f to 0.75f)
                    positions.zip(expected).forEach { (position, want) ->
                        val x = (tile.left + tile.width * position.first).toInt()
                        val y = (tile.top + tile.height * position.second).toInt()
                        val pixel = bitmap.getPixel(x, y)
                        assertTrue(
                            "EXIF $orientation at $position: expected $want, got $pixel",
                            abs(Color.red(pixel) - Color.red(want)) < 15 &&
                                abs(Color.green(pixel) - Color.green(want)) < 15 &&
                                abs(Color.blue(pixel) - Color.blue(want)) < 15,
                        )
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            copy.release()
        }
    }

    /** Four distinct quadrants, followed by a minimal EXIF APP1 segment with the requested orientation. */
    private fun orientedJpeg(orientation: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(1024, 512, Bitmap.Config.ARGB_8888)
        val jpeg =
            try {
                val canvas = Canvas(bitmap)
                listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).forEachIndexed { index, color ->
                    val left = (index % 2) * 512f
                    val top = (index / 2) * 256f
                    canvas.drawRect(left, top, left + 512f, top + 256f, Paint().apply { this.color = color })
                }
                ByteArrayOutputStream().use { output ->
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output))
                    output.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        val tiff =
            ByteBuffer
                .allocate(26)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(0x4949)
                .putShort(42)
                .putInt(8)
                .putShort(1)
                .putShort(0x0112)
                .putShort(3)
                .putInt(1)
                .putShort(orientation.toShort())
                .putShort(0)
                .putInt(0)
                .array()
        val app1 =
            ByteBuffer
                .allocate(36)
                .putShort(0xffe1.toShort())
                .putShort(34)
                .put("Exif\u0000\u0000".toByteArray())
                .put(tiff)
                .array()
        return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }

    @Test
    fun aTilesRemoveControl_removesOnlyThatAttachment() {
        val removed = mutableListOf<Long>()
        setScreen(attachments = listOf(entry(7, "one.txt"), entry(8, "two.txt")), onRemove = { removed += it })

        composeRule.onNodeWithContentDescription("Remove two.txt").performClick()

        composeRule.runOnIdle { assertEquals(listOf(8L), removed) }
    }

    @Test
    fun whileSending_theStripSaysSo_andOffersNoRemove() {
        setScreen(attachments = listOf(entry(1, "one.txt")), sending = true)

        composeRule
            .onNodeWithTag(ATTACHMENT_STRIP_TEST_TAG)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Sending"))
        composeRule.onNodeWithContentDescription("Remove one.txt").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(SEND).assertIsNotEnabled()
    }

    @Test
    fun whileALargeFileUploads_itsTileFillsToTheFigure_andTheStripSaysUploading() {
        setScreen(
            attachments = listOf(entry(1, "big.bin"), entry(2, "next.bin")),
            sending = true,
            uploadProgress = AttachmentUploadProgress(key = 1, percent = 40),
        )

        composeRule
            .onNodeWithTag(ATTACHMENT_STRIP_TEST_TAG)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Uploading… 40%"))
        val indicators =
            composeRule
                .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
                .fetchSemanticsNodes()
                .map { it.config[SemanticsProperties.ProgressBarRangeInfo] }
        assertEquals(listOf(ProgressBarRangeInfo(0.4f, 0f..1f), ProgressBarRangeInfo.Indeterminate), indicators)
    }

    @Test
    fun attachmentsWithBlankText_keepSendDisabled_untilTextIsTyped() {
        val sent = mutableListOf<String>()
        setScreen(attachments = listOf(entry(1, "one.txt")), onSend = { sent += it })

        composeRule.onNodeWithContentDescription(SEND).assertIsNotEnabled()

        composeRule.onNode(hasSetTextAction()).performTextInput("with text")
        composeRule.onNodeWithContentDescription(SEND).assertIsEnabled().performClick()

        composeRule.runOnIdle { assertEquals(listOf("with text"), sent) }
    }

    @Test
    fun blankTextAndNoAttachments_keepSendDisabled() {
        setScreen(attachments = emptyList())
        composeRule.onNodeWithContentDescription(SEND).assertIsNotEnabled()
    }

    @Test
    fun thePaperclip_opensTheMultiFilePicker_andHandsBackEachPickInOrder() {
        val registry = FakePickerRegistry(listOf(docUri("b.pdf"), docUri("a.png")))
        val picked = mutableListOf<List<PickedAttachment>>()
        setScreen(attachments = emptyList(), registry = registry, onPicked = { picked += it })

        composeRule.onNodeWithContentDescription(ATTACH).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { picked.isNotEmpty() }

        composeRule.runOnIdle {
            assertEquals(1, registry.launches.size)
            assertTrue((registry.launches.single() as Array<*>).contentEquals(arrayOf("*/*")))
            assertEquals(listOf(listOf("b.pdf", "a.png")), picked.map { batch -> batch.map { it.displayName } })
            assertEquals(listOf(docUri("b.pdf").toString(), docUri("a.png").toString()), picked.single().map { it.uri })
        }
    }

    @Test
    fun aCancelledPick_handsBackNothing() {
        val registry = FakePickerRegistry(emptyList())
        val picked = mutableListOf<List<PickedAttachment>>()
        setScreen(attachments = emptyList(), registry = registry, onPicked = { picked += it })

        composeRule.onNodeWithContentDescription(ATTACH).performClick()
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals(1, registry.launches.size)
            assertTrue(picked.isEmpty())
        }
    }

    @Test
    fun aRefusedPick_showsTheCountOfEachKind() {
        val refusals = Channel<AttachmentRefusal>(Channel.BUFFERED)
        setScreen(attachments = emptyList(), refusals = refusals.receiveAsFlow())

        refusals.trySend(AttachmentRefusal(tooLarge = 2, tooMany = 0))

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("2 files are over the 8 MB limit and were not added").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun aFailedSend_saysWhyInOneSnackbar_withTheLimitDerivedFromTheConstant() {
        val failures = Channel<AttachmentSendFailure>(Channel.BUFFERED)
        setScreen(attachments = emptyList(), sendFailures = failures.receiveAsFlow())

        failures.trySend(AttachmentSendFailure.TOO_LARGE)

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodesWithText("Too large to attach — this app sends files up to 8 MB.")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @Test
    fun switchingChats_showsEachChatsOwnStrip() {
        val store = ComposerDraftStore()
        store.addAttachment(HOST, "chat-a", docUri("only-in-a.txt").toString(), "only-in-a.txt", "text/plain", 1L)
        store.addAttachment(HOST, "chat-b", docUri("only-in-b.txt").toString(), "only-in-b.txt", "text/plain", 1L)
        val chatA = threadViewModel(store, "chat-a")
        val chatB = threadViewModel(store, "chat-b")
        var current by mutableStateOf(chatA)
        composeRule.setContent {
            val attachments by current.pendingAttachments.collectAsState()
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = ThreadUiState(conversationId = "chat", displayName = "Chat"),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    attachments = attachments,
                )
            }
        }
        composeRule.onNodeWithContentDescription("only-in-a.txt").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("only-in-b.txt").assertDoesNotExist()

        composeRule.runOnIdle { current = chatB }

        composeRule.onNodeWithContentDescription("only-in-b.txt").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("only-in-a.txt").assertDoesNotExist()
        composeRule.onAllNodesWithContentDescription("Remove only-in-b.txt").fetchSemanticsNodes().single()
    }

    private fun threadViewModel(
        store: ComposerDraftStore,
        conversationId: String,
    ) = ThreadViewModel(
        SavedStateHandle(mapOf("serverId" to HOST, "conversationId" to conversationId)),
        FakeConversationRepository(),
        FakeConnectionStateSource(),
        store,
    )

    private fun setScreen(
        attachments: List<PendingAttachment>,
        sending: Boolean = false,
        uploadProgress: AttachmentUploadProgress? = null,
        onRemove: (Long) -> Unit = {},
        onSend: (String) -> Unit = {},
        registry: ActivityResultRegistry? = null,
        onPicked: (List<PickedAttachment>) -> Unit = {},
        refusals: Flow<AttachmentRefusal> = emptyFlow(),
        sendFailures: Flow<AttachmentSendFailure> = emptyFlow(),
    ) {
        composeRule.setContent {
            PyrycodeMobileTheme {
                if (registry != null) {
                    val owner =
                        object : ActivityResultRegistryOwner {
                            override val activityResultRegistry: ActivityResultRegistry = registry
                        }
                    CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                        Screen(attachments, sending, onRemove, onSend, onPicked, refusals, uploadProgress, sendFailures)
                    }
                } else {
                    Screen(attachments, sending, onRemove, onSend, onPicked, refusals, uploadProgress, sendFailures)
                }
            }
        }
    }

    @Composable
    private fun Screen(
        attachments: List<PendingAttachment>,
        sending: Boolean,
        onRemove: (Long) -> Unit,
        onSend: (String) -> Unit,
        onPicked: (List<PickedAttachment>) -> Unit,
        refusals: Flow<AttachmentRefusal>,
        uploadProgress: AttachmentUploadProgress? = null,
        sendFailures: Flow<AttachmentSendFailure> = emptyFlow(),
    ) {
        var draft by remember { mutableStateOf("") }
        ThreadScreen(
            state = ThreadUiState(conversationId = "chat", displayName = "Chat"),
            onBack = {},
            onSendMessage = onSend,
            draft = draft,
            onDraftChange = { draft = it },
            connectionState = ConnectionState.Connected,
            onRetry = {},
            attachments = attachments,
            attachmentsSending = sending,
            attachmentUploadProgress = uploadProgress,
            onAttachmentsPicked = onPicked,
            onRemoveAttachment = onRemove,
            attachmentRefusals = refusals,
            attachmentSendFailures = sendFailures,
        )
    }

    private fun entry(
        key: Long,
        name: String,
        mimeType: String = "application/octet-stream",
    ) = PendingAttachment(key = key, uri = docUri(name).toString(), displayName = name, mimeType = mimeType, size = 1L)

    private fun docUri(name: String): Uri = Uri.parse("content://com.example.docs/document/$name")

    private companion object {
        const val HOST = "pyrybox"
        const val SEND = "Send message"
        const val ATTACH = "Attach files"
    }
}
