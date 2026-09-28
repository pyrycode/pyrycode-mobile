package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
    private val pixel8 = DpSize(411.dp, 800.dp)

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
    fun actionsChevron_matchesTheInputAreaAssetBounds() {
        composeTestRule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(pixel8)) {
                    ThreadComposerFooter(
                        runConfig = fullConfig,
                        onOpen = {},
                        onStatusClick = {},
                        onAnchorChanged = { _, _ -> },
                    )
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
                        ThreadComposerFooter(
                            runConfig = fullConfig.copy(contextPercent = 84),
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

    // The paperclip and run configuration opener keep their full tap targets inside the footer.
    @Test
    fun fullFooter_keepsThePaperclipAndStatusOpenerVisibleAndTappable() {
        var attaches = 0
        var statusClicks = 0
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(pixel8)) {
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
        val footerRight = composeTestRule.onNodeWithTag(FOOTER).getUnclippedBoundsInRoot().right

        listOf(R.string.cd_attach_files, R.string.cd_thread_status_expand).forEach {
            val icon = composeTestRule.onNodeWithContentDescription(string(it))
            icon.assertIsDisplayed().assertWidthIsEqualTo(32.dp)
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
