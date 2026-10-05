package de.pyryco.mobile.ui.conversations.thread

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.conversations.components.AttachmentSource
import de.pyryco.mobile.ui.conversations.components.AttachmentTarget
import de.pyryco.mobile.ui.conversations.components.AttachmentViewState
import de.pyryco.mobile.ui.conversations.components.MAX_CLIPBOARD_CHARS
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
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
    private val openInApp = context.getString(R.string.markdown_reader_open_in_app)
    private val saveToDevice = context.getString(R.string.markdown_reader_save_to_device)
    private val saved = context.getString(AttachmentNotice.SAVED.message)
    private val saveFailed = context.getString(AttachmentNotice.SAVE_FAILED.message)
    private val noApp = context.getString(AttachmentNotice.NO_APP.message)
    private val openFailed = context.getString(AttachmentNotice.OPEN_FAILED.message)
    private val clipboard = context.getSystemService(ClipboardManager::class.java)

    /**
     * Open in another app (#1068) serves its file through FileProvider, which caches each authority's roots
     * for the life of the process. Robolectric gives every test a fresh data directory, so a root cached by an
     * earlier test in this JVM would refuse the file; see `AttachmentActionsTest`.
     */
    @Before
    fun forgetCachedProviderRoots() {
        val cache =
            FileProvider::class.java
                .getDeclaredField("sCache")
                .apply { isAccessible = true }
                .get(null) as MutableMap<*, *>
        synchronized(cache) { cache.clear() }
    }

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
        // The item's marker is part of its paragraph in the reader (#1533).
        composeRule.onNodeWithText("Pin the dispatcher", substring = true).assertIsDisplayed()
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
        composeRule.onNodeWithTag("markdown-reader-menu").assertDoesNotExist()
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
    fun theOverflow_opensTheSixItems_inOrder() {
        show(MarkdownDocument("Plan.md", "text"))

        composeRule.onNodeWithContentDescription(more).performClick()

        val tops =
            listOf(copyMarkdown, copyPlain, copyHtml, refresh, openInApp, saveToDevice).map { item ->
                composeRule
                    .onNodeWithText(item)
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .boundsInRoot.top
            }
        assertEquals(tops.sorted(), tops)
        assertEquals(6, tops.distinct().size)
    }

    @Test fun systemBackDismissesMenuWithoutLeavingReader() {
        var backs = 0
        var dispatcher: androidx.activity.OnBackPressedDispatcher? = null
        composeRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            PyrycodeMobileTheme {
                MarkdownReaderScreen(MarkdownDocument("Plan.md", "# Plan"), onBack = { backs++ })
            }
        }
        composeRule.onNodeWithContentDescription(more).performClick()
        composeRule.onNodeWithTag("markdown-reader-menu").assertIsDisplayed()
        composeRule.runOnIdle { checkNotNull(dispatcher).onBackPressed() }
        composeRule.onNodeWithTag("markdown-reader-menu").assertDoesNotExist()
        assertEquals(0, backs)
        composeRule.onNodeWithText("Plan").assertIsDisplayed()
    }

    /** Stands in for the system picker (#1069): records each launch, and answers when the test says so. */
    private class PickerRegistry : ActivityResultRegistry() {
        val launches = mutableListOf<Pair<Int, Intent>>()

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            launches += requestCode to contract.createIntent(InstrumentationRegistry.getInstrumentation().targetContext, input)
        }
    }

    private fun showWithPicker(
        document: MarkdownDocument,
        registry: PickerRegistry,
    ) = composeRule.setContent {
        val owner =
            object : ActivityResultRegistryOwner {
                override val activityResultRegistry: ActivityResultRegistry = registry
            }
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
            PyrycodeMobileTheme {
                MarkdownReaderScreen(document = document, onBack = {})
            }
        }
    }

    private fun answer(
        registry: PickerRegistry,
        destination: Uri?,
    ) {
        composeRule.runOnIdle { registry.dispatchResult(registry.launches.single().first, destination) }
    }

    private fun awaitText(text: String) {
        // The document is written on the IO dispatcher, off the test's main clock.
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun saveToDevice_opensTheCreateDocumentPicker_withTheNotesNameAndMarkdownType() {
        val registry = PickerRegistry()
        showWithPicker(MarkdownDocument("notes/Plan.md", "# Plan"), registry)

        choose(saveToDevice)

        val intent = registry.launches.single().second
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("text/markdown", intent.type)
        assertEquals("Plan.md", intent.getStringExtra(Intent.EXTRA_TITLE))
    }

    @Test
    fun aPickedDocument_receivesTheTextOnScreen_andTheReaderSaysSaved() {
        val registry = PickerRegistry()
        val text = "# Plän ✓\n\nNaïve café 🙂"
        val destination = File(context.cacheDir, "saved-note.md").apply { writeText("older, longer content to truncate") }
        showWithPicker(MarkdownDocument("Plan.md", text), registry)

        choose(saveToDevice)
        answer(registry, Uri.fromFile(destination))

        awaitText(saved)
        assertArrayEquals(text.toByteArray(Charsets.UTF_8), destination.readBytes())
        composeRule.onNodeWithText(saveFailed).assertDoesNotExist()
    }

    @Test
    fun aFailedWrite_saysTheSaveFailed() {
        val registry = PickerRegistry()
        val destination = File(context.cacheDir, "missing-directory/Plan.md")
        showWithPicker(MarkdownDocument("Plan.md", "# Plan"), registry)

        choose(saveToDevice)
        answer(registry, Uri.fromFile(destination))

        awaitText(saveFailed)
        assertFalse(destination.exists())
        composeRule.onNodeWithText(saved).assertDoesNotExist()
    }

    @Test
    fun aCancelledPicker_writesNothing_andSaysNothing() {
        val registry = PickerRegistry()
        showWithPicker(MarkdownDocument("Plan.md", "# Plan"), registry)

        choose(saveToDevice)
        answer(registry, null)
        composeRule.waitForIdle()

        composeRule.onNodeWithText(saved).assertDoesNotExist()
        composeRule.onNodeWithText(saveFailed).assertDoesNotExist()
    }

    @Test
    fun openInAnotherApp_withNoAppForMarkdown_saysSo() {
        show(MarkdownDocument("Plan.md", "# Plan"))

        choose(openInApp)

        // The file is written on the IO dispatcher, off the test's main clock.
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText(noApp).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText(noApp).assertIsDisplayed()
        composeRule.onNodeWithText("Plan").assertIsDisplayed()
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
        choose(copyMarkdown)
        assertEquals(
            "# New note",
            clipboard.primaryClip
                ?.getItemAt(0)
                ?.text
                .toString(),
        )
        choose(copyPlain)
        assertEquals(
            "New note",
            clipboard.primaryClip
                ?.getItemAt(0)
                ?.text
                .toString(),
        )
        choose(copyHtml)
        assertTrue(
            clipboard.primaryClip
                ?.getItemAt(0)
                ?.htmlText
                .orEmpty()
                .contains("<h1>New note</h1>"),
        )
        choose(openInApp)
        awaitText(noApp)
        assertEquals("# New note", File(sharedNoteDirectory(context.noBackupFilesDir), "Plan.md").readText())
    }

    @Test fun savingAfterRefreshWritesTheDisplayedDocument() {
        val registry = PickerRegistry()
        val owner =
            object : ActivityResultRegistryOwner {
                override val activityResultRegistry: ActivityResultRegistry = registry
            }
        composeRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                PyrycodeMobileTheme {
                    RefreshableMarkdownReader(
                        initial = MarkdownDocument("Old.md", "# Old"),
                        reread = { MarkdownDocument("New.md", "# Refreshed") },
                        onBack = {},
                    )
                }
            }
        }
        choose(refresh)
        composeRule.onNodeWithText("Refreshed").assertIsDisplayed()
        choose(saveToDevice)
        assertEquals(
            "New.md",
            registry.launches
                .single()
                .second
                .getStringExtra(Intent.EXTRA_TITLE),
        )
        val destination = File(context.cacheDir, "refreshed-reader-note.md")
        answer(registry, Uri.fromFile(destination))
        awaitText(saved)
        assertEquals("# Refreshed", destination.readText())
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
