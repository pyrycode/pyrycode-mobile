package de.pyryco.mobile.ui.conversations.thread

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.pixelDp
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h892dp")
@RunWith(AndroidJUnit4::class)
class MarkdownReaderDesignTest {
    @get:Rule val rule = createComposeRule()

    private var variant = Color.Unspecified

    private fun show(
        name: String = "Builder Pipeline - Plan.md",
        markdown: String = REFERENCE_MARKDOWN,
        onBack: () -> Unit = {},
        fontScale: Float = 1f,
        onUri: (String) -> Unit = {},
    ) {
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                variant = MaterialTheme.colorScheme.onSurfaceVariant
                CompositionLocalProvider(
                    LocalDensity provides Density(LocalDensity.current.density, fontScale),
                    LocalUriHandler provides
                        object : UriHandler {
                            override fun openUri(uri: String) = onUri(uri)
                        },
                ) {
                    MarkdownReaderScreen(MarkdownDocument(name, markdown), onBack = onBack)
                }
            }
        }
    }

    private fun layout(
        text: String,
        substring: Boolean = false,
    ): TextLayoutResult {
        val result = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(text, substring = substring).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
        return result.single()
    }

    @Test fun referencePlacesBarRuleAndBodyAtFigmaCoordinates() {
        show()
        val title = rule.onNodeWithText("Builder Pipeline - Plan.md").getUnclippedBoundsInRoot()
        val heading = rule.onNodeWithText("Builder Pipeline Plan").getUnclippedBoundsInRoot()
        val code = rule.onNodeWithTag("reader-code-panel").getUnclippedBoundsInRoot()
        val quote = rule.onNodeWithText("Tokens first, running time second.").getUnclippedBoundsInRoot()
        val paragraph = rule.onNodeWithText("The main board runs four roles", substring = true).getUnclippedBoundsInRoot()
        val subheading = rule.onNodeWithText("Next steps").getUnclippedBoundsInRoot()
        val firstItem = rule.onNodeWithText("Pin all five agents repos", substring = true).getUnclippedBoundsInRoot()

        assertEquals(56f, title.left.value, 1f)
        assertEquals(24f, title.top.value, 1f)
        assertEquals(20f, heading.left.value, 1f)
        assertEquals(97f, heading.top.value, 1f)
        assertEquals(
            69f,
            rule
                .onNodeWithTag("markdown-reader-top-bar")
                .getUnclippedBoundsInRoot()
                .bottom.value,
            1f,
        )
        // Each text block rounds its height up to a whole pixel at the emulator's density, so a block lower in
        // the body may sit a few pixels below its Figma position. Robolectric's density 1 adds nothing.
        val stacked = 1f + 4 * pixelDp()
        assertEquals(141f, paragraph.top.value, stacked)
        assertEquals(225f, subheading.top.value, stacked)
        assertEquals(265f, firstItem.top.value, stacked)
        assertEquals(20f, code.left.value, 1f)
        assertEquals(385f, code.top.value, stacked)
        assertEquals(372f, code.width.value, 1f)
        assertEquals(64f, code.height.value, 1f)
        assertEquals(35f, quote.left.value, 1f)
        assertEquals(461f, quote.top.value, 2f + 4 * pixelDp())
    }

    private fun body() = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))

    @Test fun scrollViewportStartsBehindTheFixedBar() {
        show(markdown = "# Underlap heading\n\n" + (1..80).joinToString("\n\n") { "Paragraph $it" })
        val titleBefore = rule.onNodeWithText("Builder Pipeline - Plan.md").getUnclippedBoundsInRoot()
        assertEquals(0f, body().getUnclippedBoundsInRoot().top.value, 0.5f)
        body().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 72f * rule.density.density) }
        val heading = rule.onNodeWithText("Underlap heading").getUnclippedBoundsInRoot()
        assertTrue("heading crosses beneath title row", heading.top < titleBefore.bottom && heading.bottom > titleBefore.top)
        assertEquals(titleBefore, rule.onNodeWithText("Builder Pipeline - Plan.md").getUnclippedBoundsInRoot())
        rule.onNodeWithText("Paragraph 80").performScrollTo().assertIsDisplayed()
        body().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        val last = rule.onNodeWithText("Paragraph 80").getUnclippedBoundsInRoot()
        assertEquals(16f, (body().getUnclippedBoundsInRoot().bottom - last.bottom).value, 1f)
    }

    @Test fun largeTextReservesMeasuredBarPlus28dp() {
        show(fontScale = 1.5f, markdown = "# Large heading\n\n" + (1..80).joinToString("\n\n") { "Paragraph $it" })
        val bar = rule.onNodeWithTag("markdown-reader-top-bar").getUnclippedBoundsInRoot()
        val heading = rule.onNodeWithText("Large heading").getUnclippedBoundsInRoot()
        assertEquals(28f, (heading.top - bar.bottom).value, 1f)
        assertEquals(20f, heading.left.value, 0.5f)
        for (label in listOf("Back", "More actions")) {
            val target = rule.onNodeWithContentDescription(label).getUnclippedBoundsInRoot()
            assertEquals(48f, target.width.value, 0.5f)
            assertEquals(48f, target.height.value, 0.5f)
        }
        rule.onNodeWithText("Paragraph 80").performScrollTo().assertIsDisplayed()
    }

    @Test fun headerBlocksUnderlyingLinkWithPositiveClearAreaControl() {
        var opened = 0
        show(
            markdown = "[Underneath link](https://example.com)\n\n" + (1..80).joinToString("\n\n") { "Paragraph $it" },
            onUri = { opened++ },
        )
        val link = rule.onNodeWithText("Underneath link")
        link.performTouchInput { click(Offset(70f * rule.density.density, center.y)) }
        assertEquals(1, opened)
        body().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 65f * rule.density.density) }
        val bounds = link.getUnclippedBoundsInRoot()
        assertTrue("link is under bar", bounds.top.value < 69f && bounds.bottom.value > 24f)
        link.performTouchInput { click(Offset(70f * rule.density.density, center.y)) }
        rule.onNodeWithText("Builder Pipeline - Plan.md").performTouchInput { click() }
        assertEquals(1, opened)
        rule.onNodeWithTag("markdown-reader-menu").assertDoesNotExist()
        rule.onNodeWithContentDescription("More actions").performTouchInput { click() }
        rule.onNodeWithText("Copy as markdown").assertIsDisplayed()
        assertEquals(1, opened)
    }

    @Test fun headerBlocksUnderlyingCodePanelWithPositiveClearAreaControl() {
        val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("sentinel", "unchanged"))
        var backs = 0
        show(
            markdown = "```\ncopy this code\nand this line\n```\n\n" + (1..80).joinToString("\n\n") { "Paragraph $it" },
            onBack = { backs++ },
        )
        val panel = rule.onNodeWithTag("reader-code-panel")
        panel.performTouchInput { click(Offset(10f, 10f)) }
        assertEquals(
            "copy this code\nand this line",
            clipboard.primaryClip
                ?.getItemAt(0)
                ?.text
                .toString(),
        )
        clipboard.setPrimaryClip(ClipData.newPlainText("sentinel", "unchanged"))
        body().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 80f * rule.density.density) }
        val bounds = panel.getUnclippedBoundsInRoot()
        assertTrue("panel intersects bar", bounds.top.value < 69f && bounds.bottom.value > 24f)
        // The blank strip above the rule, title and both control edges belong to chrome.
        body().performTouchInput { click(Offset(200f * rule.density.density, 66f * rule.density.density)) }
        rule.onNodeWithText("Builder Pipeline - Plan.md").performTouchInput { click() }
        rule.onNodeWithContentDescription("Back").performTouchInput { click(Offset(width - 1f, center.y)) }
        rule.onNodeWithContentDescription("More actions").performTouchInput { click(Offset(1f, center.y)) }
        assertEquals(1, backs)
        rule.onNodeWithText("Copy as markdown").assertIsDisplayed()
        assertEquals(
            "unchanged",
            clipboard.primaryClip
                ?.getItemAt(0)
                ?.text
                .toString(),
        )
    }

    // #1533: `553:2574` writes each item as one `•  text` paragraph, so its wrapped line returns to the gutter.
    @Test fun wrappedListItemContinuesAtTheGutter() {
        show()
        val item = rule.onNodeWithText("Pin all five agents repos", substring = true)
        val text = layout("Pin all five agents repos", substring = true)

        assertTrue(
            text.layoutInput.text.text
                .startsWith("•"),
        )
        assertTrue("item did not wrap", text.lineCount > 1)
        assertEquals(20f, item.getUnclippedBoundsInRoot().left.value, 1f)
        assertEquals(0f, text.getLineLeft(1), 0.5f)
    }

    @Test fun readerCodeAndQuoteUseReferenceRoles() {
        show()
        val code = layout("pyry release --both\npyry status").layoutInput.style
        val quote = layout("Tokens first, running time second.").layoutInput.style

        assertEquals(13.sp, code.fontSize)
        assertEquals(20.sp, code.lineHeight)
        assertEquals(FontFamily.Monospace, code.fontFamily)
        assertEquals(variant, code.color)
        assertEquals(16.sp, quote.fontSize)
        assertEquals(24.sp, quote.lineHeight)
        assertNotEquals(FontStyle.Italic, quote.fontStyle)
        assertEquals(variant, quote.color)
    }

    @Test fun longNameAndBodyLeaveControlsAndMenuReachable() {
        show(
            name = "a-very-long-markdown-file-name-that-must-not-overlap-either-control.md",
            markdown = REFERENCE_MARKDOWN + "\n\n" + (1..80).joinToString("\n\n") { "Paragraph $it" },
        )
        val back = rule.onNodeWithContentDescription("Back")
        val more = rule.onNodeWithContentDescription("More actions")
        back.assertIsDisplayed()
        more.assertIsDisplayed()
        val title = rule.onNodeWithText("a-very-long-markdown-file-name", substring = true).getUnclippedBoundsInRoot()
        assertTrue(title.left >= back.getUnclippedBoundsInRoot().right)
        assertTrue(title.right <= more.getUnclippedBoundsInRoot().left)
        more.performClick()
        rule.onNodeWithText("Save to device").assertIsDisplayed()
        rule.onNodeWithContentDescription("Close options").performClick()
        rule.onNodeWithText("Paragraph 80").performScrollTo().assertIsDisplayed()
    }

    @Test fun headerPointerAreasRouteOnlyTheirOwnActions() {
        var backs = 0
        show(onBack = { backs++ })
        rule.onNodeWithText("Builder Pipeline - Plan.md").performTouchInput { click() }
        assertEquals(0, backs)
        rule.onNodeWithTag("markdown-reader-menu").assertDoesNotExist()
        rule.onNodeWithContentDescription("More actions").performTouchInput { click() }
        rule.onNodeWithText("Copy as markdown").assertIsDisplayed()
        rule.onNodeWithText("Copy as markdown").performClick()
        rule.onNodeWithContentDescription("Back").performTouchInput { click() }
        assertEquals(1, backs)
    }

    @Test fun plainReaderCodeScrollsHorizontallyInsideItsPanel() {
        val line = "release " + (1..60).joinToString(" ") { "argument$it" }
        show(markdown = "```\n$line\n```")
        rule.onNodeWithText(line).assertIsDisplayed()
        val scroll = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
        val before = scroll.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()
        scroll.performTouchInput { swipeLeft() }
        val after = scroll.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()
        assertTrue("plain code did not scroll: $before -> $after", after > before)
        assertEquals(
            372f,
            rule
                .onNodeWithTag("reader-code-panel")
                .getUnclippedBoundsInRoot()
                .width.value,
            1f,
        )
    }

    @Test fun readerMenuOpensFourDpBelowItsButtonWithSharedEdgeClamp() {
        show()
        val button = rule.onNodeWithContentDescription("More actions")
        button.performClick()
        val anchor = button.getUnclippedBoundsInRoot()
        val menu = rule.onNodeWithTag("markdown-reader-menu").getUnclippedBoundsInRoot()
        assertEquals(4f, (menu.top - anchor.bottom).value, 0.5f)
        assertEquals(8f, (rule.onNodeWithTag("markdown-reader-top-bar").getUnclippedBoundsInRoot().right - menu.right).value, 0.5f)
    }

    @Test fun readerMenuRowsAreActionsWithoutSelectionOrCaption() {
        show()
        rule.onNodeWithContentDescription("More actions").performClick()
        for (label in listOf(
            "Copy as markdown",
            "Copy as plain text",
            "Copy as HTML",
            "Refresh",
            "Open in another app",
            "Save to device",
        )) {
            val row = rule.onNodeWithText(label)
            row.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            assertTrue(!row.fetchSemanticsNode().config.contains(SemanticsProperties.Selected))
            val style = layout(label).layoutInput.style
            assertEquals(12.sp, style.fontSize)
            assertEquals(16.sp, style.lineHeight)
        }
        rule.onNodeWithText("not listed", substring = true).assertDoesNotExist()
    }

    @Test fun outsideTapsDismissWithoutActivatingBackOrUnderlyingLinks() {
        var backs = 0
        var links = 0
        show(markdown = "[Underlying link](https://example.com)", onBack = { backs++ }, onUri = { links++ })
        val back = rule.onNodeWithContentDescription("Back")
        val link = rule.onNodeWithText("Underlying link")
        rule.onNodeWithContentDescription("More actions").performTouchInput { click() }
        back.performTouchInput { click() }
        rule.onNodeWithTag("markdown-reader-menu").assertDoesNotExist()
        assertEquals(0, backs)
        rule.onNodeWithContentDescription("More actions").performTouchInput { click() }
        // Link's left edge is outside the right-aligned menu; the next tap is a positive control.
        val menu = rule.onNodeWithTag("markdown-reader-menu").fetchSemanticsNode().boundsInRoot
        val target = link.fetchSemanticsNode().boundsInRoot.topLeft + Offset(20f, 10f)
        assertTrue(target.x < menu.left)
        rule.onRoot().performTouchInput { click(target) }
        rule.onNodeWithTag("markdown-reader-menu").assertDoesNotExist()
        assertEquals(0, links)
        rule.onRoot().performTouchInput { click(target) }
        assertEquals(1, links)
        back.performTouchInput { click() }
        assertEquals(1, backs)
    }

    @Test fun openMenuSurvivesRecompositionAndFollowsTheLiveAnchorInAnOffsetHost() {
        val top = mutableStateOf(30.dp)
        val document = mutableStateOf(MarkdownDocument("Before.md", "# Before"))
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                Box(Modifier.fillMaxSize().padding(start = 16.dp, top = top.value, end = 16.dp)) {
                    MarkdownReaderScreen(document.value, onBack = {})
                }
            }
        }
        rule.onNodeWithContentDescription("More actions").performClick()
        val before = rule.onNodeWithTag("markdown-reader-menu").getUnclippedBoundsInRoot()
        rule.runOnIdle {
            top.value = 70.dp
            document.value = MarkdownDocument("After.md", "# After")
        }
        val after = rule.onNodeWithTag("markdown-reader-menu").getUnclippedBoundsInRoot()
        val anchor = rule.onNodeWithContentDescription("More actions").getUnclippedBoundsInRoot()
        assertEquals(40f, (after.top - before.top).value, 0.5f)
        assertEquals(4f, (after.top - anchor.bottom).value, 0.5f)
        assertEquals(8f, (rule.onNodeWithTag("markdown-reader-top-bar").getUnclippedBoundsInRoot().right - after.right).value, 0.5f)
        rule.onNodeWithText("Copy as markdown").performTouchInput { click() }
        rule.onNodeWithTag("markdown-reader-menu").assertDoesNotExist()
        val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
        assertEquals(
            "# After",
            clipboard.primaryClip
                ?.getItemAt(0)
                ?.text
                .toString(),
        )
    }

    @Test fun compactEnlargedTextScrollsToLastActionWithinTheSpaceBelow() {
        var refreshes = 0
        var saves = 0
        val registry =
            object : androidx.activity.result.ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: androidx.activity.result.contract.ActivityResultContract<I, O>,
                    input: I,
                    options: androidx.core.app.ActivityOptionsCompat?,
                ) {
                    saves++
                }
            }
        val pickerOwner =
            object : androidx.activity.result.ActivityResultRegistryOwner {
                override val activityResultRegistry = registry
            }
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(240.dp, 220.dp))) {
                PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                    CompositionLocalProvider(
                        LocalDensity provides Density(LocalDensity.current.density, 1.6f),
                        androidx.activity.compose.LocalActivityResultRegistryOwner provides pickerOwner,
                    ) {
                        MarkdownReaderScreen(MarkdownDocument("Compact.md", "# Note"), onBack = {}, onRefresh = { refreshes++ })
                    }
                }
            }
        }
        rule.onNodeWithContentDescription("More actions").performClick()
        val menu = rule.onNodeWithTag("markdown-reader-menu").getUnclippedBoundsInRoot()
        val anchor = rule.onNodeWithContentDescription("More actions").getUnclippedBoundsInRoot()
        assertEquals(4f, (menu.top - anchor.bottom).value, 0.5f)
        // Each edge may round to the next whole pixel at the emulator's density.
        val pixel = pixelDp()
        assertTrue("menu $menu", menu.left.value >= 8f - pixel)
        assertTrue("menu $menu", menu.right.value <= 232f + pixel)
        assertTrue("menu $menu", menu.bottom.value <= 212f + pixel)
        rule.onNodeWithText("Save to device").performScrollTo().assertIsDisplayed()
        val last = rule.onNodeWithText("Save to device").getUnclippedBoundsInRoot()
        assertTrue(last.top >= menu.top && last.bottom <= menu.bottom)
        // Physical activation proves scrolling leaves the last action reachable and closes the column.
        rule.onNodeWithText("Save to device").performTouchInput { click() }
        rule.onNodeWithTag("markdown-reader-menu").assertDoesNotExist()
        assertEquals(0, refreshes)
        assertEquals(1, saves)
    }

    @Test fun readerActionsUseComposerLightAppearance() = assertReaderActionPalette(dark = false)

    @Test fun readerActionsUseComposerDarkAppearance() = assertReaderActionPalette(dark = true)

    private fun assertReaderActionPalette(dark: Boolean) {
        var view: android.view.View? = null
        var background = Color.Unspecified
        var foreground = Color.Unspecified
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = dark, dynamicColor = false) {
                view = LocalView.current
                background = if (dark) MaterialTheme.colorScheme.onPrimaryFixed else MaterialTheme.colorScheme.surfaceContainerLowest
                foreground = MaterialTheme.colorScheme.primary
                MarkdownReaderScreen(MarkdownDocument("Plan.md", "# Plan"), onBack = {})
            }
        }
        rule.onNodeWithContentDescription("More actions").performClick()
        val bitmap =
            rule.runOnIdle {
                val root = checkNotNull(view)
                Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
            }
        for (label in listOf(
            "Copy as markdown",
            "Copy as plain text",
            "Copy as HTML",
            "Refresh",
            "Open in another app",
            "Save to device",
        )) {
            val bounds = rule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
            assertEquals(background.toArgb(), bitmap.getPixel(bounds.left.roundToInt() + 6, bounds.center.y.roundToInt()))
            val style = layout(label).layoutInput.style
            assertEquals(foreground, style.color)
            assertEquals(12.sp, style.fontSize)
            assertEquals(16.sp, style.lineHeight)
        }
        bitmap.recycle()
    }

    private companion object {
        const val REFERENCE_MARKDOWN =
            "# Builder Pipeline Plan\n\nThe main board runs four roles since 2026-09-01. Each ticket moves from " +
                "[refiner](https://example.com) to builder, then through review and the merge gate.\n\n## Next steps\n\n" +
                "- Pin all five agents repos to one dispatcher version\n- Measure token spend per role\n" +
                "- Retire the old single-role runner\n\n```\npyry release --both\npyry status\n```\n\n" +
                "> Tokens first, running time second."
    }
}
