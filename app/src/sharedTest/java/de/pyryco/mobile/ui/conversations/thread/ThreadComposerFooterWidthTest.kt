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
        assertEquals(footer.left + 16.dp + 15.dp + 16.dp, actions.left)
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
    fun compactWidth_circleAndActionsShareOneRow() {
        assertCompactGeometryAndRouting(fontScale = 1f)
    }

    @Test
    fun compactWidthAndEnlargedText_circleAndActionsShareOneRow() {
        assertCompactGeometryAndRouting(fontScale = 1.5f)
    }

    private fun assertCompactGeometryAndRouting(fontScale: Float) {
        var actions = 0
        var attaches = 0
        var statusClicks = 0
        composeTestRule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 640.dp))) {
                    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                        ThreadComposerFooter(
                            runConfig = fullConfig.copy(contextPercent = 100),
                            onOpen = { actions++ },
                            onStatusClick = { statusClicks++ },
                            onAnchorChanged = { _, _ -> },
                            onAttach = { attaches++ },
                            touchHeight = 28.dp,
                            contentBottomPadding = 12.dp,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = ComposerGutter).testTag(FOOTER),
                        )
                    }
                }
            }
        }
        val circle = composeTestRule.onNodeWithTag(CONTEXT_USAGE_TEST_TAG)
        circle
            .assertIsDisplayed()
            .assertWidthIsEqualTo(15.dp)
            .assertHeightIsEqualTo(15.dp)
            .assertContentDescriptionEquals("Context usage high, 100%")
        val c = circle.getUnclippedBoundsInRoot()
        val footer = composeTestRule.onNodeWithTag(FOOTER).getUnclippedBoundsInRoot()
        val action = composeTestRule.onNode(hasText("Actions") and hasClickAction())
        val actionBounds = action.getUnclippedBoundsInRoot()
        val actionsText = composeTestRule.onNodeWithText("Actions", useUnmergedTree = true)
        val text = actionsText.getUnclippedBoundsInRoot()
        assertEquals("4dp group inset after footer padding", footer.left + 16.dp, c.left)
        assertEquals("16dp visual gap", 16.dp, text.left - c.right)
        val slotTop = actionBounds.top + (actionBounds.bottom - actionBounds.top - 12.dp - 16.dp) / 2
        assertEquals("Context slot centred in left group", slotTop.value, c.top.value, 0.5f)
        assertEquals("circle top aligned in 16dp slot", slotTop.value + 15f, c.bottom.value, 0.5f)
        val layouts = mutableListOf<TextLayoutResult>()
        actionsText.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse("Actions ellipsizes", layouts.single().isLineEllipsized(0))
        val attach = composeTestRule.onNodeWithTag("footer_attach_visual", useUnmergedTree = true)
        val status = composeTestRule.onNodeWithTag("footer_status_visual", useUnmergedTree = true)
        attach.assertIsDisplayed()
        status.assertIsDisplayed()
        val a = attach.getUnclippedBoundsInRoot()
        val b = status.getUnclippedBoundsInRoot()
        assertTrue(actionBounds.right < a.left)
        assertTrue(a.right < b.left)
        assertTrue(b.right <= footer.right)
        assertEquals(text.bottom, a.bottom)
        assertEquals(text.bottom, b.bottom)
        // Real pointer taps prove the moved Actions anchor and neighbouring controls still route.
        action.performTouchInput { click(center) }
        attach.performTouchInput { click(center) }
        status.performTouchInput { click(center) }
        assertEquals(1, actions)
        assertEquals(1, attaches)
        assertEquals(1, statusClicks)
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
