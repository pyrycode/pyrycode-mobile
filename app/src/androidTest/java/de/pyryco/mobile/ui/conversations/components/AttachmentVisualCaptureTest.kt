package de.pyryco.mobile.ui.conversations.components

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.thread.ATTACHMENT_STRIP_TEST_TAG
import de.pyryco.mobile.ui.conversations.thread.ComposerAttachmentStrip
import de.pyryco.mobile.ui.conversations.thread.PendingAttachment
import de.pyryco.mobile.ui.conversations.thread.ThreadScreen
import de.pyryco.mobile.ui.conversations.thread.ThreadUiState
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real device pixels for the current dark-theme attachment design. */
@RunWith(AndroidJUnit4::class)
class AttachmentVisualCaptureTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var screenView: View? = null
    private var draft by mutableStateOf("My message")

    @Test
    fun fileArtworkAndLabels_haveReadableContrastOnBothBubblesAndThread() {
        val id = "file-1"
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                Surface {
                    Column {
                        for (role in listOf(Role.Assistant, Role.User)) {
                            MessageBubble(
                                message =
                                    Message(
                                        id = "message-$role",
                                        sessionId = "session-1",
                                        role = role,
                                        content = "Here is the file",
                                        timestamp = Instant.parse("2026-01-13T11:55:00Z"),
                                        isStreaming = false,
                                        attachments = listOf(MessageAttachment(id, "report.pdf", "application/pdf")),
                                    ),
                                attachmentStates =
                                    mapOf(id to AttachmentViewState.Ready(AttachmentSource.Kept(File("/fixture/report.pdf")), null, null)),
                            )
                        }
                        ComposerAttachmentStrip(
                            attachments =
                                listOf(
                                    PendingAttachment(
                                        key = 1,
                                        uri = "content://com.example.docs/report.pdf",
                                        displayName = "report.pdf",
                                        mimeType = "application/pdf",
                                        size = 1,
                                    ),
                                ),
                            sending = false,
                            onRemove = {},
                        )
                    }
                }
            }
        }

        val bubbleColors = listOf(Color.rgb(0x00, 0x1D, 0x34), Color.rgb(0x00, 0x33, 0x55))
        bubbleColors.forEachIndexed { index, background ->
            val row = rule.onAllNodesWithTag(MESSAGE_ATTACHMENT_FILE_TEST_TAG)[index].captureToImage().asAndroidBitmap()
            assertReadablePixels("sent page $index", row, background, 0, 45, 0, 25)
            assertReadablePixels("sent name $index", row, background, 55, row.width, 0, row.height)
        }
        val pending = rule.onNodeWithContentDescription("report.pdf").captureToImage().asAndroidBitmap()
        val threadBackground = Color.rgb(0x0B, 0x0E, 0x11)
        assertReadablePixels("pending page", pending, threadBackground, 0, pending.width, 0, 25)
        assertReadablePixels("pending type label", pending, threadBackground, 8, pending.width - 8, 35, 55)
    }

    private fun assertReadablePixels(
        label: String,
        bitmap: Bitmap,
        background: Int,
        left: Int,
        right: Int,
        top: Int,
        bottom: Int,
    ) {
        val readable =
            (left until right).sumOf { x ->
                (top until bottom).count { y -> contrast(bitmap.getPixel(x, y), background) >= 4.5 }
            }
        assertTrue("$label needs readable pixels, found $readable", readable > 10)
    }

    private fun contrast(
        first: Int,
        second: Int,
    ): Double {
        fun luminance(color: Int): Double {
            fun channel(value: Int): Double {
                val scaled = value / 255.0
                return if (scaled <= 0.04045) scaled / 12.92 else Math.pow((scaled + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(Color.red(color)) + 0.7152 * channel(Color.green(color)) + 0.0722 * channel(Color.blue(color))
        }
        val lighter = maxOf(luminance(first), luminance(second))
        val darker = minOf(luminance(first), luminance(second))
        return (lighter + 0.05) / (darker + 0.05)
    }

    @Test
    fun pendingAndSentAttachments_matchReferenceGeometryAt412By892() {
        val oldSize = overrideOf(shell("wm size"))
        val oldDensity = overrideOf(shell("wm density"))
        val resolver = instrumentation.targetContext.contentResolver
        val imageUri =
            resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "attachment-1290-rock.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                },
            ) ?: error("could not create image fixture")
        try {
            instrumentation.context.assets.open("attachment-1290/figma-rock.png").use { input ->
                resolver.openOutputStream(imageUri)?.use { output -> input.copyTo(output) }
                    ?: error("could not write image fixture")
            }
            val rock =
                instrumentation.context.assets
                    .open("attachment-1290/figma-rock.png")
                    .use(BitmapFactory::decodeStream)
                    .asImageBitmap()
            val decoder = AttachmentThumbnailDecoder { _, _ -> rock }
            val imageId = "image-1"
            val fileId = "file-1"
            val states =
                mapOf(
                    imageId to AttachmentViewState.Ready(AttachmentSource.Kept(File("/fixture/rock.png")), null, null),
                    fileId to AttachmentViewState.Ready(AttachmentSource.Kept(File("/fixture/report.pdf")), null, null),
                )
            val state =
                ThreadUiState(
                    conversationId = "capture",
                    displayName = "pyrycode discord integration",
                    isPromoted = true,
                    hasMessages = true,
                    items =
                        listOf(
                            messageItem(
                                "image",
                                Role.User,
                                "Morbi efficitur scelerisque augue, in pretium erat tempor in.",
                                MessageAttachment(imageId, "rock.png", "image/png"),
                            ),
                            messageItem(
                                "file",
                                Role.Assistant,
                                "Lorem ipsum dolor sit amet, consectetur adipiscing elit.",
                                MessageAttachment(
                                    fileId,
                                    "Filename of the Best file attachment that the assistant generated.pdf",
                                    "application/pdf",
                                ),
                            ),
                        ),
                )
            val pending =
                listOf(
                    PendingAttachment(1, imageUri.toString(), "rock.png", "image/png", 1),
                    PendingAttachment(2, imageUri.toString(), "rock-2.png", "image/png", 1),
                    PendingAttachment(3, imageUri.toString(), "report.pdf", "application/pdf", 1),
                    PendingAttachment(4, imageUri.toString(), "rock-3.png", "image/png", 1),
                )

            shell("wm density 160")
            shell("wm size 412x892")
            instrumentation.waitForIdleSync()
            showThread(state, states, pending, decoder)
            rule.waitForIdle()
            rule.waitUntil(10_000) {
                listOf("rock.png", "rock-2.png", "rock-3.png").all { name ->
                    val tile =
                        rule
                            .onNode(hasContentDescription(name) and hasAnyAncestor(hasTestTag(ATTACHMENT_STRIP_TEST_TAG)))
                            .captureToImage()
                            .asAndroidBitmap()
                    Color.red(tile.getPixel(10, 10)) > 80
                }
            }
            val strip = rule.onNodeWithTag(ATTACHMENT_STRIP_TEST_TAG).getUnclippedBoundsInRoot()
            assertTrue(strip.width >= 45.dp * 4 + 12.dp * 3)
            capture("emulator-412x892.png", 412, 892)

            shell("wm size 320x640")
            instrumentation.waitForIdleSync()
            showThread(state, states, pending, decoder, fontScale = 1.5f)
            val file = rule.onNodeWithTag(MESSAGE_ATTACHMENT_FILE_TEST_TAG).getUnclippedBoundsInRoot()
            assertTrue("file row overflows compact viewport: $file", file.right <= 320.dp)
            capture("emulator-320x640-large-text.png", 320, 640)
        } finally {
            resolver.delete(imageUri, null, null)
            shell("wm size $oldSize")
            shell("wm density $oldDensity")
        }
    }

    private fun messageItem(
        id: String,
        role: Role,
        content: String,
        attachment: MessageAttachment,
    ) = ThreadItem.MessageItem(
        Message(
            id = id,
            sessionId = "s1",
            role = role,
            content = content,
            timestamp = Instant.parse("2026-01-13T11:55:00Z"),
            isStreaming = false,
            attachments = listOf(attachment),
        ),
    )

    private fun showThread(
        state: ThreadUiState,
        states: Map<String, AttachmentViewState>,
        pending: List<PendingAttachment>,
        decoder: AttachmentThumbnailDecoder,
        fontScale: Float = 1f,
    ) {
        rule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
                LocalAttachmentThumbnailDecoder provides decoder,
            ) {
                PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                    screenView = LocalView.current
                    ThreadScreen(
                        state = state,
                        onBack = {},
                        onSendMessage = {},
                        connectionState = ConnectionState.Connected,
                        onRetry = {},
                        isThinking = true,
                        draft = draft,
                        onDraftChange = { draft = it },
                        attachments = pending,
                        attachmentStates = states,
                    )
                }
            }
        }
    }

    private fun capture(
        name: String,
        width: Int,
        height: Int,
    ) {
        rule.waitForIdle()
        val bitmap =
            rule.runOnIdle {
                val root = checkNotNull(screenView).rootView
                Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
            }
        assertEquals(width, bitmap.width)
        assertEquals(height, bitmap.height)
        val directory =
            File(
                checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")),
                "attachment-1290",
            ).apply { mkdirs() }
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(directory, "$name.txt").writeText("size=${width}x$height design=16:8,132:4605,390:7181,390:7159 inspected=2026-09-30\n")
        bitmap.recycle()
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor
            .AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader()
            .use { it.readText() }

    private fun overrideOf(output: String) =
        output.lineSequence().firstOrNull { it.startsWith("Override") }?.substringAfter(": ") ?: "reset"
}
