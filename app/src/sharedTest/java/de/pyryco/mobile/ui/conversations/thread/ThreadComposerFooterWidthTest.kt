package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

// Robolectric measures text with real fonts here, so the labels take their device widths; the device
// ignores this annotation.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class ThreadComposerFooterWidthTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun string(resId: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)

    // A 1080 px Pixel 8 at its default density is about 411dp wide.
    private val pixel8 = DpSize(411.dp, 892.dp)

    // A full run configuration still leaves the footer controls room on a Pixel 8.
    private val fullConfig =
        ThreadRunConfig(
            choices =
                listOf(
                    ThreadModelChoice(
                        value = "default",
                        label = "default",
                        detail = "",
                        effortChoices = listOf(ThreadEffortChoice("medium", "medium"), ThreadEffortChoice("high", "high")),
                    ),
                ),
            menuAvailable = true,
            settingsAvailable = true,
            savedModel = "default",
            savedEffort = "medium",
            permissionMode = "default",
            sessionId = "s1",
        )

    @Test
    fun trailingControls_matchRevisedVisualGeometry_andPointerRouting() {
        var attaches = 0
        var statusClicks = 0
        composeTestRule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(412.dp, 892.dp))) {
                    CompositionLocalProvider(LocalDensity provides Density(1f)) {
                        ThreadComposerFooter(
                            runConfig = fullConfig,
                            onOpen = {},
                            onStatusClick = { statusClicks++ },
                            onAnchorChanged = { _, _ -> },
                            onAttach = { attaches++ },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = ComposerGutter)
                                    .wrapContentHeight()
                                    .testTag(FOOTER),
                        )
                    }
                }
            }
        }
        val footer = composeTestRule.onNodeWithTag(FOOTER).getUnclippedBoundsInRoot()
        val attach = composeTestRule.onNodeWithTag("footer_attach_visual", useUnmergedTree = true)
        val status = composeTestRule.onNodeWithTag("footer_status_visual", useUnmergedTree = true)
        for (node in listOf(attach, status)) {
            node.assertWidthIsEqualTo(24.dp).assertHeightIsEqualTo(16.dp)
        }
        val a = attach.getUnclippedBoundsInRoot()
        val b = status.getUnclippedBoundsInRoot()
        assertEquals(12.dp, b.left - a.right)
        assertEquals(60.dp, b.right - a.left)
        assertEquals(16.dp, footer.right - b.right)
        assertEquals(4.dp, a.top - footer.top)
        assertEquals(a.top, b.top)
        assertEquals(footer.bottom, b.bottom)
        val actions = composeTestRule.onNodeWithText("Actions", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(footer.left + 12.dp, actions.left)
        assertEquals(actions.bottom, b.bottom)
        composeTestRule
            .onNodeWithTag("footer_attach_icon", useUnmergedTree = true)
            .assertWidthIsEqualTo(11.dp)
            .assertHeightIsEqualTo(12.dp)
        val paperclip = composeTestRule.onNodeWithTag("footer_attach_icon", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals((a.left + a.right).value, (paperclip.left + paperclip.right).value, 1f)
        assertEquals((a.top + a.bottom).value, (paperclip.top + paperclip.bottom).value, 1f)
        val tune = composeTestRule.onNodeWithTag("footer_status_icon", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(b.left + b.right, tune.left + tune.right)
        assertEquals(b.top + b.bottom, tune.top + tune.bottom)
        assertEquals(16.dp, tune.right - tune.left)
        assertEquals(16.dp, tune.bottom - tune.top)
        for (description in listOf(R.string.cd_attach_files, R.string.cd_thread_status_expand)) {
            val touch = composeTestRule.onNodeWithContentDescription(string(description)).fetchSemanticsNode().touchBoundsInRoot
            assertTrue("expanded horizontal touch area", touch.width >= 48f)
            assertTrue("expanded vertical touch area", touch.height >= 48f)
        }
        attach.performTouchInput { click(center) }
        assertEquals(1, attaches)
        assertEquals(0, statusClicks)
        status.performTouchInput { click(center) }
        assertEquals(1, attaches)
        assertEquals(1, statusClicks)
    }

    @Test
    fun actionsChevron_matchesTheInputAreaAssetBounds() {
        composeTestRule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(pixel8)) {
                    CompositionLocalProvider(LocalDensity provides Density(1f)) {
                        ThreadComposerFooter(
                            runConfig = fullConfig,
                            onOpen = {},
                            onStatusClick = {},
                            onAnchorChanged = { _, _ -> },
                        )
                    }
                }
            }
        }

        composeTestRule
            .onNodeWithTag("footer_actions_chevron", useUnmergedTree = true)
            .assertWidthIsEqualTo(8.dp)
            .assertHeightIsEqualTo(4.dp)
    }

    @Test
    fun contextText_sitsAtBottomOfFooterTapRow() {
        composeTestRule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(pixel8)) {
                    CompositionLocalProvider(LocalDensity provides Density(1f)) {
                        ThreadComposerFooter(
                            runConfig = fullConfig.copy(contextPercent = 84),
                            onOpen = {},
                            onStatusClick = {},
                            onAnchorChanged = { _, _ -> },
                            modifier = Modifier.fillMaxWidth().testTag(FOOTER),
                        )
                    }
                }
            }
        }
        val footer = composeTestRule.onNodeWithTag(FOOTER).getUnclippedBoundsInRoot()
        val context = composeTestRule.onNodeWithTag(CONTEXT_USAGE_TEST_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(context.bottom <= footer.bottom)
        assertFalse("text is centred too high in its target", footer.bottom - context.bottom > 1.dp)
    }

    @Test
    fun compactWidthAndEnlargedText_keepThreeActionsSeparate() {
        var actions = 0
        var attaches = 0
        var statusClicks = 0
        composeTestRule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 640.dp))) {
                    CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                        // An ordinary reading (#1412 gives 70 and above the longer "Cxt high:" text).
                        ThreadComposerFooter(
                            runConfig = fullConfig.copy(contextPercent = 37),
                            onOpen = { actions++ },
                            onStatusClick = { statusClicks++ },
                            onAnchorChanged = { _, _ -> },
                            onAttach = { attaches++ },
                            modifier = Modifier.fillMaxWidth().testTag(FOOTER),
                        )
                    }
                }
            }
        }
        val action = composeTestRule.onNode(hasText("Actions") and hasClickAction())
        val attach = composeTestRule.onNodeWithContentDescription(string(R.string.cd_attach_files))
        val status = composeTestRule.onNodeWithContentDescription(string(R.string.cd_thread_status_expand))
        val footer = composeTestRule.onNodeWithTag(FOOTER).getUnclippedBoundsInRoot()
        val actionsText = composeTestRule.onNodeWithText("Actions", useUnmergedTree = true)
        val contextText = composeTestRule.onNodeWithTag(CONTEXT_USAGE_TEST_TAG, useUnmergedTree = true)
        val actionsBounds = actionsText.getUnclippedBoundsInRoot()
        val contextBounds = contextText.getUnclippedBoundsInRoot()
        for ((label, node) in listOf("Actions" to actionsText, "Cxt: 37%" to contextText)) {
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertEquals("$label wraps at enlarged text", 1, layout.lineCount)
            assertFalse("$label is clipped vertically at enlarged text", layout.didOverflowHeight)
            assertTrue("$label clips on the left at enlarged text", layout.getLineLeft(0) >= 0f)
            assertTrue("$label clips on the right at enlarged text", layout.getLineRight(0) <= layout.size.width)
            assertFalse("$label ellipsizes at enlarged text", layout.isLineEllipsized(0))
        }
        assertTrue("context text must remain visible", contextBounds.right > contextBounds.left)
        assertTrue("Actions and context text overlap", actionsBounds.right < contextBounds.left)
        assertTrue("context text overlaps Attach", contextBounds.right < attach.getUnclippedBoundsInRoot().left)
        assertTrue(action.getUnclippedBoundsInRoot().right < attach.getUnclippedBoundsInRoot().left)
        assertTrue(attach.getUnclippedBoundsInRoot().right <= status.getUnclippedBoundsInRoot().left)
        assertTrue(status.getUnclippedBoundsInRoot().right <= footer.right)
        action.performClick()
        attach.performClick()
        status.performClick()
        assertEquals(1, actions)
        assertEquals(1, attaches)
        assertEquals(1, statusClicks)
    }

    // The high reading (#1412) is the longest context text; at 320dp and default font it still fits whole.
    @Test
    fun compactWidth_highReadingFitsOnOneLine() {
        renderCompactFooter(contextPercent = 100, fontScale = 1f)
        val layout = contextLayout()
        assertEquals("Cxt high: 100%", layout.layoutInput.text.text)
        assertEquals("high reading wraps at 320dp", 1, layout.lineCount)
        assertFalse("high reading ellipsizes at 320dp", layout.isLineEllipsized(0))
        assertTrue("high reading clips on the right at 320dp", layout.getLineRight(0) <= layout.size.width)
    }

    // #1549, Figma 639:3308: at 320dp and 1.5× font the "Cxt high:" text does not fit beside Actions, so it
    // moves whole to a line of its own under Actions, and the paperclip and tune stay level with Actions.
    @Test
    fun compactWidthAndEnlargedText_highReadingWrapsUnderActions() {
        renderCompactFooter(contextPercent = 84, fontScale = 1.5f, contentBottomPadding = 12.dp)
        val layout = contextLayout()
        assertEquals("Cxt high: 84%", layout.layoutInput.text.text)
        assertFalse("high reading ellipsizes at enlarged text", layout.isLineEllipsized(0))
        assertTrue("high reading clips on the right", layout.getLineRight(0) <= layout.size.width)
        composeTestRule
            .onNodeWithTag(CONTEXT_USAGE_TEST_TAG, useUnmergedTree = true)
            .assertContentDescriptionEquals("Context usage high, 84%")

        val actions = composeTestRule.onNode(hasText("Actions") and hasClickAction()).getUnclippedBoundsInRoot()
        val actionsText = composeTestRule.onNodeWithText("Actions", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val context = composeTestRule.onNodeWithTag(CONTEXT_USAGE_TEST_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val attach = composeTestRule.onNodeWithTag("footer_attach_icon", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val status = composeTestRule.onNodeWithTag("footer_status_icon", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals("label starts under Actions", actions.left, context.left)
        // Measured from Actions' visible text, not from the invisible touch padding under it.
        assertEquals("label sits 4dp under Actions", actionsText.bottom + 4.dp, context.top)
        assertEquals("paperclip leaves Actions' row", actionsText.bottom - 2.dp, attach.bottom)
        assertEquals("tune leaves Actions' row", actionsText.bottom, status.bottom)
    }

    // #1549, Figma 16:8: on a Pixel 8 at default font the label stays on Actions' row.
    @Test
    fun pixel8_highReadingStaysOnActionsRow() {
        renderCompactFooter(contextPercent = 84, fontScale = 1f, size = pixel8)
        val actions = composeTestRule.onNodeWithText("Actions", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val context = composeTestRule.onNodeWithTag(CONTEXT_USAGE_TEST_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val attach = composeTestRule.onNodeWithContentDescription(string(R.string.cd_attach_files)).getUnclippedBoundsInRoot()
        assertEquals("label leaves Actions' row", actions.bottom, context.bottom)
        assertTrue("label is not beside Actions", context.left > actions.right)
        assertTrue("label overlaps the paperclip", context.right < attach.left)
        assertFalse(contextLayout().isLineEllipsized(0))
    }

    private fun renderCompactFooter(
        contextPercent: Int,
        fontScale: Float,
        size: DpSize = DpSize(320.dp, 640.dp),
        contentBottomPadding: Dp = 0.dp,
    ) {
        composeTestRule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
                    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                        ThreadComposerFooter(
                            runConfig = fullConfig.copy(contextPercent = contextPercent),
                            onOpen = {},
                            onStatusClick = {},
                            onAnchorChanged = { _, _ -> },
                            onAttach = {},
                            contentBottomPadding = contentBottomPadding,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = ComposerGutter),
                        )
                    }
                }
            }
        }
    }

    private fun contextLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        composeTestRule
            .onNodeWithTag(CONTEXT_USAGE_TEST_TAG, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    // The paperclip and run configuration opener keep their full tap targets inside the footer.
    @Test
    fun fullFooter_keepsThePaperclipAndStatusOpenerVisibleAndTappable() {
        var attaches = 0
        var statusClicks = 0
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(pixel8)) {
                    CompositionLocalProvider(LocalDensity provides Density(1f)) {
                        ThreadComposerFooter(
                            runConfig = fullConfig,
                            onOpen = {},
                            onStatusClick = { statusClicks++ },
                            onAnchorChanged = { _, _ -> },
                            onAttach = { attaches++ },
                            modifier = Modifier.fillMaxWidth().testTag(FOOTER),
                        )
                    }
                }
            }
        }
        val footerRight = composeTestRule.onNodeWithTag(FOOTER).getUnclippedBoundsInRoot().right

        listOf(R.string.cd_attach_files, R.string.cd_thread_status_expand).forEach {
            val icon = composeTestRule.onNodeWithContentDescription(string(it))
            icon.assertIsDisplayed().assertWidthIsEqualTo(24.dp)
            assertTrue(icon.getUnclippedBoundsInRoot().right <= footerRight)
        }
        composeTestRule.onNode(hasText("Actions") and hasClickAction()).assertIsDisplayed()
        listOf("Manual approval", "default", "medium").forEach {
            composeTestRule.onNode(hasText(it) and hasClickAction()).assertDoesNotExist()
        }

        composeTestRule.onNodeWithContentDescription(string(R.string.cd_attach_files)).performClick()
        composeTestRule.onNodeWithContentDescription(string(R.string.cd_thread_status_expand)).performClick()
        assertEquals(1, attaches)
        assertEquals(1, statusClicks)
    }

    private companion object {
        const val FOOTER = "footer"
    }
}
