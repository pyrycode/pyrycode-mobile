package de.pyryco.mobile.ui.conversations.components

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * #984: a thread message's attachments, drawn in its bubble in every retrieval state. Native graphics:
 * the long-name case measures single-line truncation, and the fake thumbnail is a real bitmap.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MessageAttachmentsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val decodes = AttachmentThumbnailDecoder { _, _ -> ImageBitmap(4, 4) }
    private val failedDecodes = AttachmentThumbnailDecoder { _, _ -> null }

    private fun message(
        vararg attachments: MessageAttachment,
        role: Role = Role.Assistant,
        content: String = "Here you go",
    ) = Message(
        id = "m1",
        sessionId = "s1",
        role = role,
        content = content,
        timestamp = Instant.parse("2026-01-13T12:55:00Z"),
        isStreaming = false,
        attachments = attachments.toList(),
    )

    private fun render(
        message: Message,
        decoder: AttachmentThumbnailDecoder = decodes,
        onShown: (String) -> Unit = {},
        onRetry: (String) -> Unit = {},
        onOpen: (AttachmentTarget) -> Unit = {},
        onSave: (AttachmentTarget) -> Unit = {},
        fontScale: Float = 1f,
        states: () -> Map<String, AttachmentViewState>,
    ) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                PyrycodeMobileTheme {
                    CompositionLocalProvider(LocalAttachmentThumbnailDecoder provides decoder) {
                        Surface {
                            MessageBubble(
                                message = message,
                                attachmentStates = states(),
                                onAttachmentShown = onShown,
                                onRetryAttachment = onRetry,
                                onOpenAttachment = onOpen,
                                onSaveAttachment = onSave,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun ready(
        name: String?,
        mime: String?,
    ) = AttachmentViewState.Ready(AttachmentSource.Kept(File("/kept/$A1")), name, mime)

    @Test
    fun decodedImage_showsTheThumbnail_describedByItsName() {
        render(message(MessageAttachment(A1, "photo.png", "image/png"))) { mapOf(A1 to ready("photo.png", "image/png")) }

        composeTestRule.onNodeWithContentDescription("photo.png").assertExists()
        composeTestRule.onAllNodesWithTag(MESSAGE_ATTACHMENT_FILE_TEST_TAG).assertCountEquals(0)
    }

    @Test
    fun imageSlot_holdsItsSize_fromLoadingToLoaded_soALateThumbnailMovesNothing() {
        var states by mutableStateOf<Map<String, AttachmentViewState>>(emptyMap())
        render(message(MessageAttachment(A1, "photo.png", "image/png"))) { states }

        val loading = composeTestRule.onNodeWithTag(MESSAGE_ATTACHMENT_IMAGE_TEST_TAG).getUnclippedBoundsInRoot()
        states = mapOf(A1 to ready("photo.png", "image/png"))
        composeTestRule.waitForIdle()
        val loaded = composeTestRule.onNodeWithTag(MESSAGE_ATTACHMENT_IMAGE_TEST_TAG).getUnclippedBoundsInRoot()

        // 160dp square, or as wide as the bubble's content allows on a narrower screen (this one is 320dp).
        val root = composeTestRule.onRoot().getUnclippedBoundsInRoot()
        val content = root.width - MessageContentGutter * 2 - MessageRoleInset - BubbleHorizontalPadding * 2
        assertEquals(minOf(160.dp, content).value, loading.width.value, 0.5f)
        assertEquals(loading.width.value, loading.height.value, 0.5f)
        assertEquals(loading, loaded)
        composeTestRule.onNodeWithContentDescription("photo.png").assertExists()
    }

    @Test
    fun imageThatDoesNotDecode_fallsBackToTheFileRow() {
        render(
            message(MessageAttachment(A1, "photo.png", "image/png")),
            states = { mapOf(A1 to ready("photo.png", "image/png")) },
            decoder = failedDecodes,
        )

        composeTestRule.onNodeWithTag(MESSAGE_ATTACHMENT_FILE_TEST_TAG).assertExists()
        composeTestRule.onNodeWithText("photo.png").assertExists()
        composeTestRule.onAllNodesWithTag(MESSAGE_ATTACHMENT_IMAGE_TEST_TAG).assertCountEquals(0)
    }

    @Test
    fun file_rendersARowLabelledWithItsName_andItsType() {
        render(message(MessageAttachment(A1, "report.pdf", "application/pdf"))) {
            mapOf(A1 to ready("report.pdf", "application/pdf"))
        }

        composeTestRule.onNodeWithText("report.pdf").assertExists()
        composeTestRule.onNodeWithText("PDF").assertExists()
        composeTestRule.onAllNodesWithText("Retry").assertCountEquals(0)
    }

    @Test
    fun longName_isShortenedWithinTheBubble_whichStaysInsideItsLane() {
        val name = "Filename of the Best file attachment that the assistant generated ".repeat(6) + "final.pdf"
        render(message(MessageAttachment(A1, name, "application/pdf"))) { mapOf(A1 to ready(name, "application/pdf")) }

        val root = composeTestRule.onRoot().getUnclippedBoundsInRoot()
        val bubble = composeTestRule.onNodeWithTag(MESSAGE_BUBBLE_TEST_TAG).getUnclippedBoundsInRoot()
        val lane = root.width - MessageContentGutter * 2 - MessageRoleInset
        assertTrue("bubble ${bubble.width} wider than its lane $lane", bubble.width <= lane + 0.5.dp)
        assertTrue(bubble.right <= root.right - MessageContentGutter - MessageRoleInset + 0.5.dp)
        // The semantics keep the whole name; what is drawn stays on one line inside the bubble.
        val label = composeTestRule.onNodeWithText(name, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(label.right <= bubble.right)
        assertTrue("name wraps: ${label.height}", label.height < 20.dp)
    }

    @Test
    fun enlargedText_longFileNameAndRetryStayInsideTheCompactBubble() {
        val name = "Filename of the Best file attachment that the assistant generated.pdf"
        render(
            message(MessageAttachment(A1, name, "application/pdf")),
            fontScale = 1.5f,
            states = { mapOf(A1 to AttachmentViewState.Failed) },
        )

        val bubble = composeTestRule.onNodeWithTag(MESSAGE_BUBBLE_TEST_TAG).getUnclippedBoundsInRoot()
        val row = composeTestRule.onNodeWithTag(MESSAGE_ATTACHMENT_FILE_TEST_TAG).getUnclippedBoundsInRoot()
        val nameBounds = composeTestRule.onNodeWithText(name, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val retry = composeTestRule.onNodeWithText("Retry").getUnclippedBoundsInRoot()
        assertTrue(row.left >= bubble.left && row.right <= bubble.right)
        assertTrue(nameBounds.right <= bubble.right)
        assertTrue(retry.right <= bubble.right && retry.bottom <= bubble.bottom)
    }

    @Test
    fun unnamedReference_usesTheGenericLabel_untilRetrievalSuppliesAName() {
        var states by mutableStateOf<Map<String, AttachmentViewState>>(mapOf(A1 to AttachmentViewState.Loading))
        render(message(MessageAttachment(A1))) { states }

        composeTestRule.onNodeWithText("Attachment").assertExists()
        states = mapOf(A1 to ready("fetched.txt", "text/plain"))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("fetched.txt").assertExists()
        composeTestRule.onAllNodesWithText("Attachment").assertCountEquals(0)
    }

    @Test
    fun loading_saysSo() {
        render(message(MessageAttachment(A1, "notes.txt", "text/plain"))) { emptyMap() }

        composeTestRule.onNodeWithText("notes.txt").assertExists()
        composeTestRule.onNodeWithText("Loading…").assertExists()
    }

    @Test
    fun failed_offersRetry_andATapRetriesThatAttachment() {
        val retried = mutableListOf<String>()
        render(
            message(MessageAttachment(A1, "notes.txt", "text/plain"), MessageAttachment(A2, "ok.txt", "text/plain")),
            states = { mapOf(A1 to AttachmentViewState.Failed, A2 to ready("ok.txt", "text/plain")) },
            onRetry = { retried += it },
        )

        composeTestRule.onNodeWithText("Couldn't load file").assertExists()
        composeTestRule.onNodeWithText("Retry").performClick()

        assertEquals(listOf(A1), retried)
    }

    @Test
    fun notFound_saysSo_withNoRetry() {
        render(message(MessageAttachment(A1, "gone.png", "image/png"))) { mapOf(A1 to AttachmentViewState.NotFound) }

        composeTestRule.onNodeWithText("File not found").assertExists()
        composeTestRule.onNodeWithText("gone.png").assertExists()
        composeTestRule.onAllNodesWithText("Retry").assertCountEquals(0)
    }

    @Test
    fun attachments_renderInReferenceOrder() {
        render(message(MessageAttachment(A2, "second.txt", "text/plain"), MessageAttachment(A1, "first.txt", "text/plain"))) {
            emptyMap()
        }

        val second = composeTestRule.onNodeWithText("second.txt").getUnclippedBoundsInRoot()
        val first = composeTestRule.onNodeWithText("first.txt").getUnclippedBoundsInRoot()
        assertTrue(second.bottom <= first.top)
    }

    @Test
    fun attachmentOnlyMessage_drawsNoEmptyTextBlock() {
        render(message(MessageAttachment(A1, "photo.png", "image/png"), role = Role.User, content = "")) { emptyMap() }

        composeTestRule
            .onAllNodes(hasText("", substring = false))
            .assertCountEquals(0)
        composeTestRule.onNodeWithTag(MESSAGE_ATTACHMENT_IMAGE_TEST_TAG).assertExists()
    }

    @Test
    fun eachComposedAttachment_isReportedShownByItsId() {
        val shown = mutableListOf<String>()
        render(
            message(MessageAttachment(A1, "a.txt", "text/plain"), MessageAttachment(A2, "b.txt", "text/plain")),
            states = { emptyMap() },
            onShown = { shown += it },
        )
        composeTestRule.waitForIdle()

        assertEquals(listOf(A1, A2), shown)
    }

    @Test
    fun readyFile_tapOpensIt_andLongPressSavesIt_withItsNameAndType() {
        val opened = mutableListOf<AttachmentTarget>()
        val saved = mutableListOf<AttachmentTarget>()
        render(
            message(MessageAttachment(A1, "report.pdf", null)),
            states = { mapOf(A1 to ready("fetched.pdf", "application/pdf")) },
            onOpen = { opened += it },
            onSave = { saved += it },
        )

        val row = composeTestRule.onNodeWithTag(MESSAGE_ATTACHMENT_FILE_TEST_TAG)
        row.assertHasClickAction()
        row.performClick()
        row.performTouchInput { longClick() }

        // The reference's own name wins; retrieval fills the MIME type it left unknown.
        val expected = AttachmentTarget(A1, "report.pdf", "application/pdf")
        assertEquals(listOf(expected), opened)
        assertEquals(listOf(expected), saved)
    }

    @Test
    fun readyImage_tapOpensIt() {
        val opened = mutableListOf<AttachmentTarget>()
        render(
            message(MessageAttachment(A1, "photo.png", "image/png")),
            states = { mapOf(A1 to ready("photo.png", "image/png")) },
            onOpen = { opened += it },
        )

        composeTestRule.onNodeWithTag(MESSAGE_ATTACHMENT_IMAGE_TEST_TAG).performClick()

        assertEquals(listOf(AttachmentTarget(A1, "photo.png", "image/png")), opened)
    }

    @Test
    fun loadingFailedAndNotFound_offerNeitherOpenNorSave() {
        val acted = mutableListOf<AttachmentTarget>()
        render(
            message(
                MessageAttachment(A1, "photo.png", "image/png"),
                MessageAttachment(A2, "notes.txt", "text/plain"),
                MessageAttachment(A3, "gone.zip", "application/zip"),
                MessageAttachment(A4, "later.txt", "text/plain"),
            ),
            states = { mapOf(A2 to AttachmentViewState.Failed, A3 to AttachmentViewState.NotFound) },
            onOpen = { acted += it },
            onSave = { acted += it },
        )

        composeTestRule.onNodeWithTag(MESSAGE_ATTACHMENT_IMAGE_TEST_TAG).assertHasNoClickAction()
        val rows = composeTestRule.onAllNodesWithTag(MESSAGE_ATTACHMENT_FILE_TEST_TAG)
        rows.assertCountEquals(3)
        for (i in 0 until 3) {
            rows[i].assertHasNoClickAction()
            rows[i].performTouchInput { longClick() }
        }
        composeTestRule.onNodeWithTag(MESSAGE_ATTACHMENT_IMAGE_TEST_TAG).performClick()

        assertEquals(emptyList<AttachmentTarget>(), acted)
    }

    private companion object {
        const val A1 = "0f8fad5b-d9cb-469f-a165-70867728950e"
        const val A2 = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
        const val A3 = "16fd2706-8baf-433b-82eb-8c7fada847da"
        const val A4 = "886313e1-3b8a-5372-9b90-0c9aee199e5d"
    }
}
