package de.pyryco.mobile.ui.conversations.components

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.ui.conversations.thread.MarkdownDocument
import de.pyryco.mobile.ui.conversations.thread.MarkdownReaderScreen
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * #1050: a tapped link to a workspace markdown note in an assistant reply hands its path to the thread,
 * streaming or finished, and every other link and every other caller keeps today's handling.
 */
@RunWith(AndroidJUnit4::class)
class MarkdownLinkTapTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val paths = mutableListOf<String>()
    private val opened = mutableListOf<String>()
    private var linkColor = Color.Unspecified
    private var launchFailure: Exception? = null

    private val uriHandler =
        object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }

    private fun show(content: @Composable () -> Unit) =
        composeTestRule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                linkColor = MaterialTheme.colorScheme.primary
                val baseContext = LocalContext.current
                val context =
                    object : ContextWrapper(baseContext) {
                        override fun startActivity(intent: Intent) {
                            launchFailure?.let { throw it }
                            assertEquals(Intent.ACTION_VIEW, intent.action)
                            opened += requireNotNull(intent.dataString)
                        }
                    }
                CompositionLocalProvider(LocalUriHandler provides uriHandler, LocalContext provides context) {
                    Surface { Column { content() } }
                }
            }
        }

    private fun message(
        role: Role,
        content: String,
        isStreaming: Boolean = false,
    ) = Message("m-${role.name}", "s1", role, content, Instant.parse("2026-09-25T12:00:00Z"), isStreaming)

    /** Tap the first link in the node showing [text], waiting until it is one: a streaming reply reveals its source gradually. */
    private fun tapLink(text: String) {
        composeTestRule.waitUntil(TIMEOUT_MS) {
            runCatching { composeTestRule.onNodeWithText(text, substring = true).performFirstLinkClick() }.isSuccess
        }
        composeTestRule.waitForIdle()
    }

    private fun annotated(text: String): AnnotatedString =
        composeTestRule
            .onNodeWithText(text)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .single()

    private fun assertInert(
        text: String,
        beforeTapMillis: Long = 0,
    ) {
        val rendered = annotated(text)
        assertTrue("$text must have no active link annotation", rendered.getLinkAnnotations(0, rendered.length).isEmpty())
        assertTrue(rendered.spanStyles.none { it.item.color == linkColor || it.item.textDecoration == TextDecoration.Underline })
        composeTestRule.onNodeWithText(text).performTouchInput {
            advanceEventTime(beforeTapMillis)
            click(center)
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun finishedExplicitLinks_agreeWithRoutingAndHtml() = assertExplicitLinks(isStreaming = false)

    @Test
    fun bareObsidianLinks_preserveTargetsStylesAndAndroidRouting() {
        val targets =
            listOf(
                "obsidian://open?vault=V&file=Note.md",
                "ObSiDiAn://open?vault=Second%20brain&file=A%2fPlan.MD&paneType=tab",
                "obsidian://open?vault=www.example.com&file=Note",
            )
        val text = "Open ${targets.joinToString(" and ")} now"
        show { MarkdownText(text, onOpenMarkdownPath = { paths += it }) }
        val rendered = annotated(text)
        val ranges = rendered.getLinkAnnotations(0, rendered.length)
        assertEquals(targets.size, ranges.size)
        ranges.zip(targets).forEach { (range, target) ->
            assertEquals(text.indexOf(target), range.start)
            assertEquals(text.indexOf(target) + target.length, range.end)
            val link = range.item as LinkAnnotation.Url
            assertEquals(target, link.url)
            assertEquals(linkColor, link.styles?.style?.color)
            assertEquals(TextDecoration.Underline, link.styles?.style?.textDecoration)
            composeTestRule.onNodeWithText(text).performFirstLinkClick { (it.item as? LinkAnnotation.Url)?.url == target }
        }
        assertEquals(targets, opened)
        assertEquals(emptyList<String>(), paths)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun refusedBareCandidates_haveNoStylesAnnotationsOrActions() {
        val source = mutableStateOf("")
        val streaming = mutableStateOf(false)
        show {
            if (streaming.value) {
                StreamingMarkdownText(source.value, caretVisible = false, onOpenMarkdownPath = { paths += it })
            } else {
                MarkdownText(source.value, onOpenMarkdownPath = { paths += it })
            }
        }
        val targets =
            listOf(
                "obsidian://new?vault=V&file=Note",
                "obsidian://open?file=Note&append=x",
                "obsidian://open?file=Note&unknown=x",
                "obsidian://open?file=x.hta.",
                "obsidian://open?file=%GG",
                "obsidian://open?file=%ED%A0%80",
                "obsidian://open?file=Note%26append=x",
                "obsidian://open?file=Note%23fragment",
                "obsidian://open?file=Note#fragment",
                "obsidian://open?file=Note&unknown=*value*",
                "javascript:alert(1)",
                "file:///tmp/a.md",
                "http://example.com",
                "https://example.com",
                "www.example.com",
                "a@example.com",
                "https://example.com/(obsidian://open?file=Note)",
                "www.example.com/(obsidian://open?file=Note)",
                "<https://example.com/(obsidian://open?file=Note)>",
            )
        listOf(false, true).forEach { isStreaming ->
            targets.forEach { target ->
                val text = "Before $target after"
                composeTestRule.runOnIdle {
                    streaming.value = isStreaming
                    source.value = text
                }
                assertInert(text.replace("*value*", "value"), beforeTapMillis = 1_000)
            }
        }
        assertEquals(emptyList<String>(), opened)
        assertEquals(emptyList<String>(), paths)
    }

    @Test
    fun bareRecognition_excludesCodeAndAuthoredLabels() {
        val url = "obsidian://open?vault=V&file=Note"
        val code = "code $url words"
        show {
            MarkdownText(
                "`$code`\n\n```\nfenced $url\n```\n\n    indented $url\n\n[See $url now](https://example.com)\n\n[See $url now](javascript:bad)",
            )
        }
        listOf(code, "fenced $url", "indented $url").forEach { text ->
            val rendered = annotated(text)
            assertTrue(rendered.getLinkAnnotations(0, rendered.length).isEmpty())
        }
        val labels =
            composeTestRule
                .onAllNodesWithText(
                    "See $url now",
                ).fetchSemanticsNodes()
                .map { it.config[SemanticsProperties.Text].single() }
        assertEquals(2, labels.size)
        assertEquals(
            listOf("https://example.com"),
            labels
                .flatMap {
                    it.getLinkAnnotations(0, it.length)
                }.map { (it.item as LinkAnnotation.Url).url },
        )
        assertEquals(emptyList<String>(), opened)
    }

    @Test
    fun barePunctuation_preservesAdmittedCharactersAndExactRanges() {
        val source = mutableStateOf("")
        show { MarkdownText(source.value) }
        val url = "obsidian://open?file=Note.md"
        val cases = listOf("See $url." to url, "See (\"$url).\"" to url, "See obsidian://open?file=Note!" to "obsidian://open?file=Note!")
        cases.forEach { (text, target) ->
            composeTestRule.runOnIdle { source.value = text }
            val rendered = annotated(text)
            val range = rendered.getLinkAnnotations(0, rendered.length).single()
            assertEquals(text.indexOf(target), range.start)
            assertEquals(text.indexOf(target) + target.length, range.end)
            assertEquals(target, (range.item as LinkAnnotation.Url).url)
        }
    }

    @Test
    fun bareRanges_crossParserTokensAndRetainSurroundingMarkdown() {
        val source = mutableStateOf("")
        show { MarkdownText(source.value) }
        listOf("obsidian://open?file=A_name.MD", "obsidian://open?file=A*B*C").forEach { url ->
            listOf("~See $url now~", "**See $url now**").forEach { markdown ->
                composeTestRule.runOnIdle { source.value = markdown }
                val rendered = annotated("See $url now")
                val range = rendered.getLinkAnnotations(0, rendered.length).single()
                assertEquals(url, (range.item as LinkAnnotation.Url).url)
                assertEquals(url, rendered.text.substring(range.start, range.end))
                if (markdown.startsWith('~')) {
                    assertTrue(rendered.spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough })
                } else {
                    assertTrue(rendered.spanStyles.any { it.item.fontWeight == androidx.compose.ui.text.font.FontWeight.Bold })
                }
            }
        }
    }

    @Test
    fun streamingExplicitLinks_agreeWithRoutingAndHtml() = assertExplicitLinks(isStreaming = true)

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun finishedMalformedUris_stayInertWithWorkspaceOptIn() = assertMalformedUris(isStreaming = false)

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun streamingMalformedUris_stayInertWithWorkspaceOptIn() = assertMalformedUris(isStreaming = true)

    private fun assertMalformedUris(isStreaming: Boolean) {
        val source = mutableStateOf("")
        show { MessageBubble(message(Role.Assistant, source.value, isStreaming), onOpenMarkdownLink = { paths += it }) }
        val targets =
            listOf(" ", "\t", " \t", "\u00a0", "\u2003").flatMap { prefix ->
                listOf(
                    "obsidian:open?file=Plan.md",
                    "obsidian:/open?file=Plan.md",
                    "obsidian://open?file=Plan.md",
                    "obsidian:///open?file=Plan.md",
                    "ObSiDiAn:open?file=Plan.MARKDOWN:12#top",
                    "file:Plan.md",
                    "content:/Plan.markdown",
                    "unknown:Plan.md",
                ).map { prefix + it }
            }
        targets.forEachIndexed { index, target ->
            composeTestRule.runOnIdle { source.value = "Before [note](<$target>) after\n\nEnd$index." }
            composeTestRule.waitUntil(TIMEOUT_MS) {
                composeTestRule.onAllNodesWithText("End$index.", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertInert("Before note after", beforeTapMillis = 1_000)
            assertEquals(emptyList<String>(), opened)
            assertEquals(emptyList<String>(), paths)
            val html = markdownHtml("[note](<$target>)")
            assertTrue(html, html.contains("note") && !html.contains("href="))
        }
    }

    @Test
    fun retainedObsidianLink_usesTheCurrentFailureSink() {
        val first = mutableListOf<MarkdownLinkNotice>()
        val second = mutableListOf<MarkdownLinkNotice>()
        val sink = mutableStateOf<(MarkdownLinkNotice) -> Unit>({ first += it })
        launchFailure = ActivityNotFoundException()
        show {
            CompositionLocalProvider(LocalMarkdownLinkFailure provides sink.value) {
                MarkdownText("[note](obsidian://open)")
            }
        }
        val retained = annotated("note").getLinkAnnotations(0, 4).single().item as LinkAnnotation.Url
        tapLink("note")
        composeTestRule.runOnIdle { sink.value = { second += it } }
        composeTestRule.runOnIdle { retained.linkInteractionListener?.onClick(retained) }
        assertEquals(listOf(MarkdownLinkNotice.OBSIDIAN_NOT_INSTALLED), first)
        assertEquals(listOf(MarkdownLinkNotice.OBSIDIAN_NOT_INSTALLED), second)
    }

    @Test
    fun readerObsidianLaunchFailure_usesItsExistingErrorPill() {
        launchFailure = ActivityNotFoundException()
        show { MarkdownReaderScreen(MarkdownDocument("Links.md", "[note](obsidian://open)"), onBack = {}) }
        tapLink("note")
        composeTestRule.onNodeWithText("Obsidian is not installed").assertIsDisplayed()
    }

    private fun assertExplicitLinks(isStreaming: Boolean) {
        val admitted =
            listOf(
                "http://example.com/a",
                "HTTPS://example.com/A%20B?q=1#Top",
                "mailto:a@example.com",
                "ObSiDiAn://open?vault=Second%20brain&file=A%2fPlan.MD&paneType=tab",
            )
        val rejected =
            listOf(
                "obsidian://open?vault=x&append=text",
                "obsidian://open?file=x.hta",
                "obsidian://open?file=%ED%A0%80",
                " obsidian://open?file=Plan.md",
                "JaVaScRiPt:alert(1)",
                "data:text/html,x",
                "file:///tmp/a.md",
                "content://x/a.md",
                "unknown:x",
            )
        val targets = admitted + rejected
        val codeLink = "[code](https://example.com)"
        val source =
            targets
                .mapIndexed { index, target ->
                    val destination = if (target.startsWith(' ')) "<$target>" else target
                    "Before [label$index]($destination) after"
                }.joinToString("\n\n") + "\n\n`$codeLink`\n\nEnd."
        show { MessageBubble(message(Role.Assistant, source, isStreaming), onOpenMarkdownLink = { paths += it }) }
        composeTestRule.waitUntil(TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText("End.", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        admitted.forEachIndexed { index, target ->
            val text = "Before label$index after"
            val rendered = annotated(text)
            val range = rendered.getLinkAnnotations(0, rendered.length).single()
            assertEquals(7, range.start)
            assertEquals(7 + "label$index".length, range.end)
            val link = range.item as LinkAnnotation.Url
            assertEquals(target, link.url)
            assertEquals(linkColor, link.styles?.style?.color)
            assertEquals(TextDecoration.Underline, link.styles?.style?.textDecoration)
            tapLink(text)
            assertEquals(admitted.take(index + 1), opened)
            assertTrue(markdownHtml("[label$index]($target)").contains("<a href=\"${target.replace("&", "&amp;")}\">label$index</a>"))
        }
        rejected.forEachIndexed { offset, target ->
            val index = offset + admitted.size
            assertInert("Before label$index after")
            val destination = if (target.startsWith(' ')) "<$target>" else target
            val html = markdownHtml("[label$index]($destination)")
            assertTrue(html, html.contains("label$index") && !html.contains("href="))
        }
        val code = annotated(codeLink)
        assertTrue(code.getLinkAnnotations(0, code.length).isEmpty())
        assertTrue(code.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
        assertEquals(admitted, opened)
        assertEquals(emptyList<String>(), paths)
    }

    @Test
    fun workspaceOptInChanges_refreshAnnotationsAndUseTheCurrentCallback() = assertWorkspaceOptInChanges(isStreaming = false)

    @Test
    fun streamingWorkspaceOptInChanges_refreshAnnotationsAndUseTheCurrentCallback() = assertWorkspaceOptInChanges(isStreaming = true)

    private fun assertWorkspaceOptInChanges(isStreaming: Boolean) {
        val callback = mutableStateOf<((String) -> Unit)?>(null)
        val replacementPaths = mutableListOf<String>()
        show {
            if (isStreaming) {
                StreamingMarkdownText("[Plan](notes/Plan.md:12#top)\n\nTail", caretVisible = false, onOpenMarkdownPath = callback.value)
            } else {
                MarkdownText("[Plan](notes/Plan.md:12#top)", onOpenMarkdownPath = callback.value)
            }
        }
        assertInert("Plan")

        composeTestRule.runOnIdle { callback.value = { paths += it } }
        val retained = annotated("Plan").getLinkAnnotations(0, 4).single().item as LinkAnnotation.Url
        assertEquals("notes/Plan.md:12#top", retained.url)
        tapLink("Plan")
        assertEquals(listOf("notes/Plan.md"), paths)
        composeTestRule.runOnIdle { callback.value = { replacementPaths += it } }
        tapLink("Plan")
        assertEquals(listOf("notes/Plan.md"), replacementPaths)
        assertEquals(listOf("notes/Plan.md"), paths)

        composeTestRule.runOnIdle { callback.value = null }
        assertInert("Plan")
        composeTestRule.runOnIdle { retained.linkInteractionListener?.onClick(retained) }
        assertEquals(listOf("notes/Plan.md"), replacementPaths)
        assertEquals(listOf("notes/Plan.md"), paths)
        assertEquals(emptyList<String>(), opened)
    }

    @Test
    fun aStreamingDestination_becomesInertWhenRejected_andStaysInertOnSettlement() {
        val reply = mutableStateOf(message(Role.Assistant, "[label](javascript:aler", isStreaming = true))
        show { MessageBubble(reply.value, onOpenMarkdownLink = { paths += it }) }
        composeTestRule.waitUntil(TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText("label", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        val pending =
            composeTestRule
                .onNodeWithText(
                    "label",
                    substring = true,
                ).fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .single()
        assertTrue(pending.getLinkAnnotations(0, pending.length).isEmpty())
        composeTestRule.runOnIdle { reply.value = reply.value.copy(content = "[label](javascript:alert(1))\n\nEnd.") }
        composeTestRule.waitUntil(TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText("End.", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertInert("label")
        composeTestRule.runOnIdle { reply.value = reply.value.copy(isStreaming = false) }
        assertInert("label")
        assertEquals(emptyList<String>(), opened)
        assertEquals(emptyList<String>(), paths)
    }

    @Test
    fun aFinishedReply_handsTheNotesPathToTheThread_andNothingToAnotherApp() {
        show { MessageBubble(message(Role.Assistant, "See [Plan](notes/Plan.md:12#top) now."), onOpenMarkdownLink = { paths += it }) }

        tapLink("Plan")

        assertEquals(listOf("notes/Plan.md"), paths)
        assertEquals(emptyList<String>(), opened)
    }

    @Test
    fun aStreamingReply_handsTheNotesPathToTheThread() {
        show { MessageBubble(message(Role.Assistant, "[Plan](Plan.MARKDOWN)", isStreaming = true), onOpenMarkdownLink = { paths += it }) }

        tapLink("Plan")

        assertEquals(listOf("Plan.MARKDOWN"), paths)
        assertEquals(emptyList<String>(), opened)
    }

    @Test
    fun aWebLinkInAReply_stillOpensThroughTheUriHandler() {
        show { MessageBubble(message(Role.Assistant, "[site](https://example.com/a.md)"), onOpenMarkdownLink = { paths += it }) }

        tapLink("site")

        assertEquals(emptyList<String>(), paths)
        assertEquals(listOf("https://example.com/a.md"), opened)
    }

    @Test
    fun withoutTheOptIn_aNotesLinkStaysInert() {
        show { MarkdownText("[Plan](notes/Plan.md)") }

        assertInert("Plan")

        assertEquals(emptyList<String>(), opened)
    }

    @Test
    fun reader_links_keep_web_routing_and_inert_workspace_paths() {
        show {
            MarkdownReaderScreen(
                MarkdownDocument("Links.md", "[Plan](notes/Plan.md)\n\n[site](https://example.com/a.md)"),
                onBack = {},
            )
        }
        assertInert("Plan")
        assertEquals(emptyList<String>(), opened)
        tapLink("site")
        assertEquals(listOf("https://example.com/a.md"), opened)
        assertEquals(emptyList<String>(), paths)
    }

    @Test
    fun theOperatorsOwnMessage_isNotALink() {
        show { MessageBubble(message(Role.User, "[Plan](notes/Plan.md)"), onOpenMarkdownLink = { paths += it }) }

        composeTestRule.onNodeWithText("[Plan](notes/Plan.md)").performClick()

        assertEquals(emptyList<String>(), paths)
        assertEquals(emptyList<String>(), opened)
    }

    @Test
    fun allHeadingLevels_routeWorkspaceAndWebLinks() = assertHeadingLinks(streaming = false, reader = false)

    @Test
    fun allStreamingHeadingLevels_routeWorkspaceAndWebLinks() = assertHeadingLinks(streaming = true, reader = false)

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun allReaderHeadingLevels_keepWorkspaceLinksInertAndRouteWebLinks() = assertHeadingLinks(streaming = false, reader = true)

    private fun assertHeadingLinks(
        streaming: Boolean,
        reader: Boolean,
    ) {
        val source =
            (1..6).joinToString("\n\n") { level ->
                "${"#".repeat(level)} [Note$level](notes/Plan$level.md) [Web$level](https://example.com/$level)"
            }
        show {
            val style =
                MarkdownTextStyle(
                    body = MaterialTheme.typography.bodyMedium,
                    presentation = if (reader) MarkdownPresentation.Reader else MarkdownPresentation.Thread,
                )
            if (streaming) {
                StreamingMarkdownText(source, caretVisible = true, onOpenMarkdownPath = { paths += it }, style = style)
            } else {
                MarkdownText(source, onOpenMarkdownPath = if (reader) null else { path -> paths += path }, style = style)
            }
        }
        for (level in 1..6) {
            val workspaceLabel = "Note$level"
            val webLabel = "Web$level"
            val heading = composeTestRule.onNodeWithText("$workspaceLabel $webLabel", substring = streaming)
            val rendered = heading.fetchSemanticsNode().config[SemanticsProperties.Text].single()
            assertEquals(if (reader) 1 else 2, rendered.getLinkAnnotations(0, rendered.length).size)
            if (reader) {
                val workspaceText = rendered.subSequence(0, workspaceLabel.length)
                assertTrue("H$level workspace label must be inert", workspaceText.getLinkAnnotations(0, workspaceText.length).isEmpty())
                assertTrue(
                    workspaceText.spanStyles.none { it.item.color == linkColor || it.item.textDecoration == TextDecoration.Underline },
                )
                val layouts = mutableListOf<TextLayoutResult>()
                heading.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                heading.performTouchInput { click(layouts.single().getBoundingBox(0).center) }
                composeTestRule.waitForIdle()
                assertEquals(emptyList<String>(), paths)
                assertEquals((1 until level).map { "https://example.com/$it" }, opened)
            } else {
                heading.performFirstLinkClick { (it.item as? LinkAnnotation.Url)?.url == "notes/Plan$level.md" }
            }
            val webStart = workspaceLabel.length + 1
            val webRange = rendered.getLinkAnnotations(webStart, webStart + webLabel.length).single()
            assertEquals(webStart, webRange.start)
            assertEquals(webStart + webLabel.length, webRange.end)
            val webLink = webRange.item as LinkAnnotation.Url
            assertEquals("https://example.com/$level", webLink.url)
            assertEquals(linkColor, webLink.styles?.style?.color)
            assertEquals(TextDecoration.Underline, webLink.styles?.style?.textDecoration)
            heading.performFirstLinkClick { (it.item as? LinkAnnotation.Url)?.url == webLink.url }
        }
        assertEquals(if (reader) emptyList<String>() else (1..6).map { "notes/Plan$it.md" }, paths)
        assertEquals((1..6).map { "https://example.com/$it" }, opened)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
