package de.pyryco.mobile.ui.conversations.components

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        onShown: (MessageAttachment) -> Unit = {},
        onRetry: (String) -> Unit = {},
        onOpen: (AttachmentTarget) -> Unit = {},
        onSave: (AttachmentTarget) -> Unit = {},
        onRequest: (MessageAttachment, AttachmentAction) -> Unit = { _, _ -> },
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
                                onRequestAttachment = onRequest,
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

    // #1624: Figma 696:4913 draws the type label at regular weight, and the file's name at the bubble's
    // full content colour — only the state line beneath it is dimmed. The theme drew the type label at
    // medium weight and dimmed the name along with the state line.
    @Test
    fun fileRow_keepsTheNameAtFullStrength_andTheTypeLabelAtRegularWeight() {
        render(message(MessageAttachment(A1, "notes.txt", "text/plain"))) { mapOf(A1 to AttachmentViewState.Failed) }

        val typeResults = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        composeTestRule
            .onNodeWithText("TXT")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(typeResults) }
        assertEquals(
            androidx.compose.ui.text.font.FontWeight.Normal,
            typeResults
                .single()
                .layoutInput.style.fontWeight,
        )

        val nameResults = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        composeTestRule
            .onNodeWithText("notes.txt")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(nameResults) }
        val statusResults = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        composeTestRule
            .onAllNodesWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.thread_attachment_failed))
            .onFirst()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(statusResults) }

        val nameAlpha =
            nameResults
                .single()
                .layoutInput.style.color.alpha
        val statusAlpha =
            statusResults
                .single()
                .layoutInput.style.color.alpha
        assertTrue("the name must be less dimmed than the state line below it", nameAlpha > statusAlpha)
    }

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
        assertEnlargedRetryIsUnclipped(fontScale = 1.5f)
    }

    @Test
    fun doubledText_longFileNameAndRetryStayInsideTheCompactBubble() {
        assertEnlargedRetryIsUnclipped(fontScale = 2f)
    }

    private fun assertEnlargedRetryIsUnclipped(fontScale: Float) {
        val name = "Filename of the Best file attachment that the assistant generated.pdf"
        render(
            message(MessageAttachment(A1, name, "application/pdf")),
            fontScale = fontScale,
            states = { mapOf(A1 to AttachmentViewState.Failed) },
        )

        val bubble = composeTestRule.onNodeWithTag(MESSAGE_BUBBLE_TEST_TAG).getUnclippedBoundsInRoot()
        val row = composeTestRule.onNodeWithTag(MESSAGE_ATTACHMENT_FILE_TEST_TAG).getUnclippedBoundsInRoot()
        val nameBounds = composeTestRule.onNodeWithText(name, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val retry = composeTestRule.onNodeWithText("Retry").getUnclippedBoundsInRoot()
        assertTrue(row.left >= bubble.left && row.right <= bubble.right)
        assertTrue(nameBounds.right <= bubble.right)
        assertTrue(retry.right <= bubble.right && retry.bottom <= bubble.bottom)
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        composeTestRule
            .onNodeWithText("Retry", useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertFalse(
            "Retry must paint its entire paragraph at fontScale $fontScale: size=${layout.size}, " +
                "paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, " +
                "widthOverflow=${layout.didOverflowWidth}, heightOverflow=${layout.didOverflowHeight}",
            layout.hasVisualOverflow,
        )
        assertTrue("Retry paragraph exceeds its allocated height", layout.multiParagraph.height <= layout.size.height)
        composeTestRule.onNodeWithText("Retry").assertTouchHeightIsEqualTo(maxOf(48.dp, retry.height))
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
        // #1329: with no state, only a reference that is fetched on show is loading; this one has no type or name.
        render(message(MessageAttachment(A1))) { emptyMap() }

        composeTestRule.onNodeWithText("Attachment").assertExists()
        composeTestRule.onNodeWithText("Loading…").assertExists()
        composeTestRule.onNodeWithTag(MESSAGE_ATTACHMENT_FILE_TEST_TAG).assertHasNoClickAction()
    }

    @Test
    fun aFileNotFetchedYet_drawsAsItsReadyRow_andItsTapAndLongPressAskForIt() {
        val pdf = MessageAttachment(A1, "report.pdf", "application/pdf")
        val offer = MessageAttachment(A2, "offer.txt")
        val requested = mutableListOf<Pair<MessageAttachment, AttachmentAction>>()
        val acted = mutableListOf<AttachmentTarget>()
        render(
            message(pdf, offer),
            states = { emptyMap() },
            onRequest = { attachment, action -> requested += attachment to action },
            onOpen = { acted += it },
            onSave = { acted += it },
        )

        composeTestRule.onNodeWithText("report.pdf").assertExists()
        composeTestRule.onNodeWithText("offer.txt").assertExists()
        composeTestRule.onAllNodesWithText("Loading…").assertCountEquals(0)
        val rows = composeTestRule.onAllNodesWithTag(MESSAGE_ATTACHMENT_FILE_TEST_TAG)
        rows.assertCountEquals(2)
        rows[0].assertHasClickAction()
        rows[0].performClick()
        rows[1].performTouchInput { longClick() }

        assertEquals(listOf(pdf to AttachmentAction.OPEN, offer to AttachmentAction.SAVE), requested)
        assertEquals(emptyList<AttachmentTarget>(), acted)
    }

    @Test
    fun anImageByTypeOrName_isLoadingUntilItArrives_andAsksForNothing() {
        val requested = mutableListOf<AttachmentAction>()
        render(
            message(MessageAttachment(A1, "photo.png", "image/png"), MessageAttachment(A2, "offer.PNG")),
            states = { emptyMap() },
            onRequest = { _, action -> requested += action },
        )

        composeTestRule.onNodeWithTag(MESSAGE_ATTACHMENT_IMAGE_TEST_TAG).assertHasNoClickAction()
        // A name-only image has no type to draw a slot from until it is retrieved; it is a loading file row.
        composeTestRule.onNodeWithTag(MESSAGE_ATTACHMENT_FILE_TEST_TAG).assertHasNoClickAction()
        composeTestRule.onNodeWithText("Loading…").assertExists()
        composeTestRule.onNodeWithTag(MESSAGE_ATTACHMENT_IMAGE_TEST_TAG).performClick()
        assertEquals(emptyList<AttachmentAction>(), requested)
    }

    @Test
    fun aRequestedFileThatLoads_thenFails_offersRetry_andNeitherOpensNorSaves() {
        var states by mutableStateOf<Map<String, AttachmentViewState>>(mapOf(A1 to AttachmentViewState.Loading))
        val acted = mutableListOf<Any>()
        render(
            message(MessageAttachment(A1, "report.pdf", "application/pdf")),
            states = { states },
            onRequest = { attachment, _ -> acted += attachment },
            onOpen = { acted += it },
            onSave = { acted += it },
        )

        composeTestRule.onNodeWithText("Loading…").assertExists()
        composeTestRule.onNodeWithTag(MESSAGE_ATTACHMENT_FILE_TEST_TAG).assertHasNoClickAction()
        states = mapOf(A1 to AttachmentViewState.Failed)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Couldn't load file").assertExists()
        composeTestRule.onNodeWithText("Retry").assertExists()
        composeTestRule.onNodeWithTag(MESSAGE_ATTACHMENT_FILE_TEST_TAG).assertHasNoClickAction()
        assertEquals(emptyList<Any>(), acted)
    }

    @Test
    fun failedRetry_keepsFortyDpLayoutHeight_andFortyEightDpTouchTarget() {
        val retried = mutableListOf<String>()
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                Surface {
                    MessageAttachments(
                        attachments =
                            listOf(
                                MessageAttachment(A1, "notes.txt", "text/plain"),
                                MessageAttachment(A2, "gone.yaml", "application/yaml"),
                            ),
                        states = mapOf(A1 to AttachmentViewState.Failed, A2 to AttachmentViewState.NotFound),
                        onShown = {},
                        onRetry = { retried += it },
                    )
                }
            }
        }

        val retry = composeTestRule.onNodeWithText("Retry")
        val retryBounds = retry.getUnclippedBoundsInRoot()
        assertEquals(40f, retryBounds.height.value, 0.5f)
        val rows = composeTestRule.onAllNodesWithTag(MESSAGE_ATTACHMENT_FILE_TEST_TAG)
        val failed = rows[0].getUnclippedBoundsInRoot()
        val next = rows[1].getUnclippedBoundsInRoot()
        assertEquals(72f, failed.height.value, 0.5f)
        assertEquals(84f, (next.top - failed.top).value, 0.5f)
        retry.assertTouchHeightIsEqualTo(48.dp)

        // Real pointer taps in the expanded target, 2dp beyond each visible edge.
        retry.performTouchInput { click(Offset(center.x, -height / 20f)) }
        retry.performTouchInput { click(Offset(center.x, height + height / 20f)) }
        assertEquals(listOf(A1, A1), retried)
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

    // #1638: the selectable body wrapper must not emit an empty child, or the bubble column spaces both
    // sides of it and the attachments sit two gaps above the meta row.
    @Test
    fun attachmentOnlyMessage_keepsOneContentGapAboveTheMetaRow() {
        render(message(MessageAttachment(A1, "photo.png", "image/png"), role = Role.User, content = "")) { emptyMap() }

        val image = composeTestRule.onNodeWithTag(MESSAGE_ATTACHMENT_IMAGE_TEST_TAG).getUnclippedBoundsInRoot()
        val copyDescription =
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.cd_thread_copy_message)
        val metaCopy = composeTestRule.onNodeWithContentDescription(copyDescription).getUnclippedBoundsInRoot()
        assertEquals(BubbleContentSpacing.value, (metaCopy.top - image.bottom).value, 1.5f)
    }

    @Test
    fun eachComposedAttachment_isReportedShownByItsId() {
        val shown = mutableListOf<MessageAttachment>()
        render(
            message(MessageAttachment(A1, "a.txt", "text/plain"), MessageAttachment(A2, "b.txt", "text/plain")),
            states = { emptyMap() },
            onShown = { shown += it },
        )
        composeTestRule.waitForIdle()

        // Every row reports itself; the thread decides which are fetched on show (#1329).
        assertEquals(listOf(A1, A2), shown.map { it.attachmentId })
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
            states = { mapOf(A2 to AttachmentViewState.Failed, A3 to AttachmentViewState.NotFound, A4 to AttachmentViewState.Loading) },
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

    @Test
    fun userMessage_drawsItsAttachmentsAboveItsText_andItsMetaRowLast() = assertAttachmentsTextMetaOrder(Role.User)

    @Test
    fun assistantMessage_drawsItsAttachmentsAboveItsText_andItsMetaRowLast() = assertAttachmentsTextMetaOrder(Role.Assistant)

    private fun assertAttachmentsTextMetaOrder(role: Role) {
        render(message(MessageAttachment(A1, "photo.png", "image/png"), role = role)) {
            mapOf(A1 to ready("photo.png", "image/png"))
        }

        val image = composeTestRule.onNodeWithTag(MESSAGE_ATTACHMENT_IMAGE_TEST_TAG).getUnclippedBoundsInRoot()
        val text = composeTestRule.onNodeWithText("Here you go", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val meta = composeTestRule.onNodeWithContentDescription("Copy this message").getUnclippedBoundsInRoot()
        assertTrue("image ${image.bottom} not above text ${text.top}", image.bottom <= text.top)
        assertTrue("text ${text.bottom} not above meta row ${meta.top}", text.bottom <= meta.top)
    }

    private companion object {
        const val A1 = "0f8fad5b-d9cb-469f-a165-70867728950e"
        const val A2 = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
        const val A3 = "16fd2706-8baf-433b-82eb-8c7fada847da"
        const val A4 = "886313e1-3b8a-5372-9b90-0c9aee199e5d"
    }
}
