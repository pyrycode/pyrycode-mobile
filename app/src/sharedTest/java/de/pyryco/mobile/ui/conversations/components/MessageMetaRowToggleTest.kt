package de.pyryco.mobile.ui.conversations.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.thread.ThreadScreen
import de.pyryco.mobile.ui.conversations.thread.ThreadUiState
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * #1817: only the timestamp hides until a finished bubble is tapped, with at most one shown.
 * Side copy remains a labelled button during streaming; nested targets keep their own taps.
 */
@RunWith(AndroidJUnit4::class)
class MessageMetaRowToggleTest {
    @get:Rule
    val composeRule = createComposeRule()

    private class RecordingClipboard : ClipboardManager {
        val writes = mutableListOf<String>()

        override fun setText(annotatedString: AnnotatedString) {
            writes += annotatedString.text
        }

        override fun getText(): AnnotatedString? = writes.lastOrNull()?.let { AnnotatedString(it) }

        override fun hasText(): Boolean = writes.isNotEmpty()
    }

    private val clipboard = RecordingClipboard()
    private val opened = mutableListOf<String>()
    private val uriHandler =
        object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }

    private fun string(id: Int): String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getString(id)

    private var expectedCopies = 0

    private val copyDescription = string(R.string.cd_thread_copy_message)

    private fun message(
        id: String,
        role: Role,
        content: String,
        isStreaming: Boolean = false,
        attachments: List<MessageAttachment> = emptyList(),
    ) = Message(id, "s1", role, content, TIMESTAMP, isStreaming, attachments = attachments)

    private fun setThread(
        attachmentStates: Map<String, AttachmentViewState> = emptyMap(),
        items: () -> List<Message>,
    ) {
        expectedCopies = items().count { it.role != Role.Tool }
        composeRule.setContent {
            PyrycodeMobileTheme {
                CompositionLocalProvider(
                    LocalClipboardManager provides clipboard,
                    LocalUriHandler provides uriHandler,
                ) {
                    ThreadScreen(
                        state =
                            ThreadUiState(
                                conversationId = "conversation",
                                displayName = "Meta rows",
                                isPromoted = true,
                                hasMessages = true,
                                items = items().map { ThreadItem.MessageItem(it) },
                            ),
                        onBack = {},
                        onSendMessage = {},
                        connectionState = ConnectionState.Connected,
                        onRetry = {},
                        attachmentStates = attachmentStates,
                    )
                }
            }
        }
    }

    private fun visibleCopyControls(): Int =
        composeRule
            .onAllNodesWithContentDescription(copyDescription)
            .fetchSemanticsNodes()
            .size

    private fun visibleTimestamps(): Int =
        composeRule
            .onAllNodesWithText(" - ", substring = true, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .size

    private fun assertRowsShown(expected: Int) {
        composeRule.waitForIdle()
        assertEquals("copy controls always shown", expectedCopies, visibleCopyControls())
        assertEquals("timestamps shown", expected, visibleTimestamps())
        // Single bubble taps must be spaced beyond text selection's double-tap timeout (#1638).
        composeRule.mainClock.advanceTimeBy(500)
    }

    private fun copyFor(body: String) =
        composeRule.onNode(
            hasContentDescription(copyDescription) and
                hasAnyAncestor(
                    hasTestTag("message-row") and hasAnyDescendant(hasText(body)),
                ),
        )

    @Test
    fun aTapShowsThatMessagesRow_aSecondTapHidesIt_andAnotherMessageTakesItOver() {
        setThread { listOf(message("u", Role.User, USER_BODY), message("a", Role.Assistant, ASSISTANT_BODY)) }
        assertRowsShown(0)
        copyFor(USER_BODY).performClick()
        assertEquals(listOf(USER_BODY), clipboard.writes)
        assertRowsShown(0)

        composeRule.onNodeWithText(USER_BODY).performClick()
        assertRowsShown(1)
        copyFor(USER_BODY).performClick()
        assertEquals(listOf(USER_BODY, USER_BODY), clipboard.writes)

        composeRule.onNodeWithText(USER_BODY).performClick()
        assertRowsShown(0)

        composeRule.onNodeWithText(USER_BODY).performClick()
        composeRule.onNodeWithText(ASSISTANT_BODY).performClick()
        assertRowsShown(1)
        copyFor(ASSISTANT_BODY).performClick()
        assertEquals(listOf(USER_BODY, USER_BODY, ASSISTANT_BODY), clipboard.writes)
    }

    @Test
    fun aStreamingReply_ignoresTaps_andTheFirstTapAfterItFinishesShowsTheRow() {
        var streaming by mutableStateOf(true)
        setThread { listOf(message("a", Role.Assistant, ASSISTANT_BODY, isStreaming = streaming)) }
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule
                .onAllNodesWithText(ASSISTANT_BODY, substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        val bubble = composeRule.onAllNodesWithTag(MESSAGE_BUBBLE_TEST_TAG)[0]
        assertEquals(null, bubble.fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick))
        assertEquals(
            androidx.compose.ui.semantics.Role.Button,
            composeRule.onNodeWithContentDescription(copyDescription).fetchSemanticsNode().config[SemanticsProperties.Role],
        )
        bubble.performClick()
        assertRowsShown(0)
        composeRule.onNodeWithContentDescription(copyDescription).performClick()
        assertEquals(listOf(ASSISTANT_BODY), clipboard.writes)
        assertRowsShown(0)

        composeRule.runOnIdle { streaming = false }
        composeRule.onNodeWithText(ASSISTANT_BODY).performClick()
        assertRowsShown(1)
    }

    @Test
    fun aLinkTap_andACodeBlockCopy_doNotToggleTheRow_andTheCodeCopyStaysVisible() {
        setThread { listOf(message("a", Role.Assistant, NESTED_BODY)) }
        val codeCopy = string(R.string.cd_thread_copy_code)
        composeRule.onNodeWithContentDescription(codeCopy).assertExists()

        composeRule.onNodeWithText("Plan", substring = true).performFirstLinkClick()
        assertRowsShown(0)
        assertEquals(listOf("https://example.com/plan"), opened)

        composeRule.onNodeWithContentDescription(codeCopy).performClick()
        assertRowsShown(0)
        assertEquals(listOf(CODE), clipboard.writes)
    }

    @Test
    fun aTapOnALoadingNotFoundOrFailedAttachment_doesNotToggleTheRow() {
        setThread(
            attachmentStates = mapOf("nf" to AttachmentViewState.NotFound, "f" to AttachmentViewState.Failed),
        ) {
            listOf(
                message(
                    "a",
                    Role.Assistant,
                    ASSISTANT_BODY,
                    attachments =
                        listOf(
                            MessageAttachment("l", "loading.png", "image/png"),
                            MessageAttachment("nf", "gone.zip", "application/zip"),
                            MessageAttachment("f", "broken.txt", "text/plain"),
                        ),
                ),
            )
        }
        val image = composeRule.onAllNodesWithTag(MESSAGE_ATTACHMENT_IMAGE_TEST_TAG, useUnmergedTree = true)
        val files = composeRule.onAllNodesWithTag(MESSAGE_ATTACHMENT_FILE_TEST_TAG, useUnmergedTree = true)
        assertEquals(1, image.fetchSemanticsNodes().size)
        assertEquals(2, files.fetchSemanticsNodes().size)

        image[0].performClick()
        assertRowsShown(0)
        files[0].performClick()
        assertRowsShown(0)
        files[1].performClick()
        assertRowsShown(0)

        composeRule.onNodeWithText(ASSISTANT_BODY).performClick()
        assertRowsShown(1)
    }

    @Test
    fun theBubblesScreenReaderClick_showsAndHidesTheRow() {
        setThread { listOf(message("a", Role.Assistant, ASSISTANT_BODY)) }
        val bubble = composeRule.onAllNodesWithTag(MESSAGE_BUBBLE_TEST_TAG)[0]
        assertEquals(
            string(R.string.thread_message_show_details),
            bubble.fetchSemanticsNode().config[SemanticsActions.OnClick].label,
        )

        bubble.performSemanticsAction(SemanticsActions.OnClick)
        assertRowsShown(1)
        assertEquals(
            string(R.string.thread_message_hide_details),
            bubble.fetchSemanticsNode().config[SemanticsActions.OnClick].label,
        )

        bubble.performSemanticsAction(SemanticsActions.OnClick)
        assertRowsShown(0)
    }

    @Test
    fun aHiddenRow_leavesTheTimestampDescription_andAnIndependentCopyButton() {
        setThread { listOf(message("a", Role.Assistant, ASSISTANT_BODY)) }
        assertRowsShown(0)

        val bubble = composeRule.onAllNodesWithTag(MESSAGE_BUBBLE_TEST_TAG)[0].fetchSemanticsNode()
        val description =
            bubble.config
                .getOrNull(SemanticsProperties.ContentDescription)
                .orEmpty()
                .joinToString()
        val formatted = formatShortDateTime(TIMESTAMP, TimeZone.currentSystemDefault(), Locale.getDefault())
        assertTrue("bubble must announce $formatted, was '$description'", description.contains(formatted))

        assertTrue(
            bubble.config
                .getOrNull(SemanticsActions.CustomActions)
                .orEmpty()
                .isEmpty(),
        )
        copyFor(ASSISTANT_BODY).performClick()
        assertRowsShown(0)
        assertEquals(listOf(ASSISTANT_BODY), clipboard.writes)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        val TIMESTAMP: Instant = Instant.parse("2026-01-13T12:55:00Z")
        const val USER_BODY = "omega user line"
        const val ASSISTANT_BODY = "alpha assistant line"
        const val CODE = "./gradlew check"
        const val NESTED_BODY = "See [Plan](https://example.com/plan) first.\n\n```bash\n$CODE\n```\n"
    }
}
