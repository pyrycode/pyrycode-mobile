package de.pyryco.mobile.ui.conversations.thread

import android.content.ClipDescription
import android.content.ClipboardManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.conversations.components.AttachmentSource
import de.pyryco.mobile.ui.conversations.components.AttachmentTarget
import de.pyryco.mobile.ui.conversations.components.AttachmentViewState
import de.pyryco.mobile.ui.conversations.components.MAX_CLIPBOARD_CHARS
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** #1027: the in-app markdown reader — its name bar, its rendered body, its way back, and the tap that opens it. */
@RunWith(AndroidJUnit4::class)
class MarkdownReaderScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val back = context.getString(R.string.cd_back)
    private val more = context.getString(R.string.cd_more_actions)
    private val copyMarkdown = context.getString(R.string.markdown_reader_copy_markdown)
    private val copyPlain = context.getString(R.string.markdown_reader_copy_plain_text)
    private val copyHtml = context.getString(R.string.markdown_reader_copy_html)
    private val refresh = context.getString(R.string.markdown_reader_refresh)
    private val openFailed = context.getString(AttachmentNotice.OPEN_FAILED.message)
    private val clipboard = context.getSystemService(ClipboardManager::class.java)

    private fun show(
        document: MarkdownDocument,
        onBack: () -> Unit = {},
    ) = composeRule.setContent {
        PyrycodeMobileTheme {
            MarkdownReaderScreen(document = document, onBack = onBack)
        }
    }

    @Test
    fun showsTheNameInTheBar_andTheRenderedMarkdownBelow() {
        show(MarkdownDocument("Plan.md", "# Builder Plan\n\n- Pin the dispatcher\n\nSome **bold** prose."))

        composeRule.onNodeWithText("Plan.md").assertIsDisplayed()
        composeRule.onNodeWithText("Builder Plan").assertIsDisplayed()
        composeRule.onNodeWithText("Pin the dispatcher").assertIsDisplayed()
        // Rendered, not shown as source: the heading's `#` and the bold markers are gone.
        composeRule.onNodeWithText("# Builder Plan").assertDoesNotExist()
        composeRule.onNodeWithText("Some bold prose.").assertIsDisplayed()
    }

    @Test
    fun theBackArrow_goesBack() {
        var backs = 0
        show(MarkdownDocument("Plan.md", "text"), onBack = { backs++ })

        composeRule.onNodeWithContentDescription(back).performClick()

        assertEquals(1, backs)
    }

    @Test
    fun longContent_scrollsUnderAFixedBar() {
        val body = (1..80).joinToString("\n\n") { "Paragraph $it" }
        show(MarkdownDocument("Long.md", body))

        composeRule.onNodeWithText("Paragraph 80").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText("Long.md").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(back).assertIsDisplayed()
        composeRule.onNodeWithText("Paragraph 1").assertIsNotDisplayed()
    }

    @Test
    fun aReadyMarkdownTap_goesToTheReader_whateverItsSource_andOtherFilesDoNot() {
        val kept = AttachmentViewState.Ready(AttachmentSource.Kept(File("/kept/a")), null, null)
        val original = AttachmentViewState.Ready(AttachmentSource.Original("content://docs/b"), null, null)
        val states = mapOf("a" to kept, "b" to original, "c" to kept)
        val opened = mutableListOf<String>()
        val notices = mutableListOf<AttachmentNotice>()
        lateinit var actions: AttachmentActions
        composeRule.setContent {
            actions = rememberAttachmentActions(states, onOpenMarkdown = { opened += it }, onNotice = { notices += it })
        }

        composeRule.runOnIdle {
            actions.open(AttachmentTarget("a", "Plan.MD", "text/plain"))
            actions.open(AttachmentTarget("b", "notes.markdown", null))
            actions.open(AttachmentTarget("c", "notes.txt", "text/plain"))
            actions.open(AttachmentTarget("loading", "later.md", null))
        }

        assertEquals(listOf("a", "b"), opened)
        // The text file went the view-intent way: a kept file outside the store has no content URI.
        assertEquals(listOf(AttachmentNotice.OPEN_FAILED), notices)
    }

    @Test
    fun aLinkedNote_opensInTheSameReader_andItsBackArrowGoesBack() {
        var backs = 0
        composeRule.setContent {
            PyrycodeMobileTheme {
                LinkedMarkdownReaderDestination(
                    note = LinkedMarkdown("notes/Plan.md", MarkdownDocument("Plan.md", "# Live note")),
                    reread = { null },
                    onBack = { backs++ },
                )
            }
        }

        composeRule.onNodeWithText("Plan.md").assertIsDisplayed()
        composeRule.onNodeWithText("Live note").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(back).performClick()
        assertEquals(1, backs)
    }

    @Test
    fun noLinkedNote_goesBackOnce_andDrawsNoReader() {
        var backs = 0
        composeRule.setContent {
            PyrycodeMobileTheme {
                LinkedMarkdownReaderDestination(note = null, reread = { null }, onBack = { backs++ })
            }
        }
        composeRule.waitForIdle()

        assertEquals(1, backs)
        composeRule.onNodeWithContentDescription(back).assertDoesNotExist()
    }

    private fun choose(item: String) {
        composeRule.onNodeWithContentDescription(more).performClick()
        composeRule.onNodeWithText(item).performClick()
        composeRule.waitForIdle()
    }

    private fun showRefreshable(
        initial: MarkdownDocument,
        reread: suspend () -> MarkdownDocument?,
    ) = composeRule.setContent {
        PyrycodeMobileTheme {
            RefreshableMarkdownReader(initial = initial, reread = reread, onBack = {})
        }
    }

    @Test
    fun theOverflow_opensTheFourItems_inOrder() {
        show(MarkdownDocument("Plan.md", "text"))

        composeRule.onNodeWithContentDescription(more).performClick()

        val tops =
            listOf(copyMarkdown, copyPlain, copyHtml, refresh).map { item ->
                composeRule
                    .onNodeWithText(item)
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInRoot.top
            }
        assertEquals(tops.sorted(), tops)
        assertEquals(4, tops.distinct().size)
    }

    @Test
    fun copyAsMarkdown_putsTheRawText_andShowsNoNotice() {
        show(MarkdownDocument("Plan.md", "# Plan\n\nSome **bold**."))

        choose(copyMarkdown)

        assertEquals(
            "# Plan\n\nSome **bold**.",
            clipboard.primaryClip
                ?.getItemAt(0)
                ?.text
                ?.toString(),
        )
        composeRule.onNodeWithText(openFailed).assertDoesNotExist()
    }

    @Test
    fun copyAsPlainText_putsTheRenderedText() {
        show(MarkdownDocument("Plan.md", "# Plan\n\nSome **bold** [link](https://example.com).\n\n- item"))

        choose(copyPlain)

        assertEquals(
            "Plan\n\nSome bold link.\n\nitem",
            clipboard.primaryClip
                ?.getItemAt(0)
                ?.text
                ?.toString(),
        )
    }

    @Test
    fun copyAsHtml_putsOneClip_withTheHtmlAndThePlainFallback() {
        show(MarkdownDocument("Plan.md", "# Plan\n\n<script>x</script>"))

        choose(copyHtml)

        val clip = clipboard.primaryClip
        assertEquals(1, clip?.itemCount)
        assertTrue(clip?.description?.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) == true)
        val item = clip?.getItemAt(0)
        val html = item?.htmlText.orEmpty()
        assertTrue(html, html.contains("<h1>Plan</h1>"))
        assertFalse(html, html.contains("<script"))
        assertEquals("Plan\n\n<script>x</script>", item?.text?.toString())
    }

    @Test
    fun copiesOfANoteAtTheReadersBound_areBounded() {
        show(MarkdownDocument("Big.md", "a".repeat(MAX_MARKDOWN_READER_BYTES)))

        choose(copyMarkdown)
        assertEquals(
            MAX_CLIPBOARD_CHARS,
            clipboard.primaryClip
                ?.getItemAt(0)
                ?.text
                ?.length,
        )

        choose(copyHtml)
        val item = clipboard.primaryClip?.getItemAt(0)
        assertTrue((item?.htmlText?.length ?: Int.MAX_VALUE) <= MAX_CLIPBOARD_CHARS)
        assertEquals(MAX_CLIPBOARD_CHARS, item?.text?.length)
    }

    @Test
    fun refresh_replacesTheContent_withANewRead() {
        var reads = 0
        showRefreshable(MarkdownDocument("Plan.md", "# Old note")) {
            reads++
            MarkdownDocument("Plan.md", "# New note")
        }

        choose(refresh)

        composeRule.onNodeWithText("New note").assertIsDisplayed()
        composeRule.onNodeWithText("Old note").assertDoesNotExist()
        assertEquals(1, reads)
    }

    @Test
    fun aSecondRefresh_whileOneIsReading_readsNothing() {
        var reads = 0
        val pending = CompletableDeferred<MarkdownDocument?>()
        showRefreshable(MarkdownDocument("Plan.md", "# Old note")) {
            reads++
            pending.await()
        }

        choose(refresh)
        choose(refresh)
        assertEquals(1, reads)

        pending.complete(MarkdownDocument("Plan.md", "# New note"))
        composeRule.onNodeWithText("New note").assertIsDisplayed()
        assertEquals(1, reads)
    }

    @Test
    fun aFailedRefresh_keepsTheOldContent_andSaysItCouldNotOpen() {
        showRefreshable(MarkdownDocument("Plan.md", "# Old note")) { null }

        choose(refresh)

        composeRule.onNodeWithText("Old note").assertIsDisplayed()
        composeRule.onNodeWithText(openFailed).assertIsDisplayed()
    }

    @Test
    fun aRetry_whileTheFailureNoticeShows_readsAgain() {
        var reads = 0
        showRefreshable(MarkdownDocument("Plan.md", "# Old note")) {
            reads++
            if (reads == 1) null else MarkdownDocument("Plan.md", "# New note")
        }

        choose(refresh)
        composeRule.onNodeWithText(openFailed).assertIsDisplayed()
        choose(refresh)

        assertEquals(2, reads)
        composeRule.onNodeWithText("New note").assertIsDisplayed()
    }
}
