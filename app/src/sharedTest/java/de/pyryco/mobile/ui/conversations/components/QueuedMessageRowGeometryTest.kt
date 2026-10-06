package de.pyryco.mobile.ui.conversations.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class QueuedMessageRowGeometryTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun referenceRows_matchBubblePaddingSpacingAndDropGutter() {
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(412.dp, 892.dp))) {
                PyrycodeMobileTheme {
                    Column {
                        QueuedMessageRow(WRAPPING, {})
                        QueuedMessageRow(SHORT, {})
                    }
                }
            }
        }
        val text = rule.onNodeWithText(WRAPPING, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val bubble = rule.onNodeWithText(WRAPPING, useUnmergedTree = true).onParent().getUnclippedBoundsInRoot()
        val next = rule.onNodeWithText(SHORT, useUnmergedTree = true).onParent().getUnclippedBoundsInRoot()
        assertEquals(200f, bubble.width.value, 2f)
        assertEquals(20f, (text.left - bubble.left).value, 2f)
        assertEquals(20f, (bubble.right - text.right).value, 2f)
        assertEquals(16f, (text.top - bubble.top).value, 2f)
        assertEquals(16f, (bubble.bottom - text.bottom).value, 2f)
        assertEquals(16f, (next.top - bubble.bottom).value, 2f)
        val drops = rule.onAllNodesWithContentDescription(DROP)
        assertEquals(2, drops.fetchSemanticsNodes().size)
        (0..1).forEach { index ->
            val bounds = drops[index].getUnclippedBoundsInRoot()
            assertEquals(48f, bounds.width.value, 2f)
            assertEquals(48f, bounds.height.value, 2f)
            assertEquals(368f, ((bounds.left + bounds.right) / 2).value, 2f)
        }
    }

    @Test
    fun wideViewport_stillCapsWrappingBubbleAt200Dp() {
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(600.dp, 892.dp))) {
                PyrycodeMobileTheme { QueuedMessageRow(WRAPPING, {}) }
            }
        }
        val bubble = rule.onNodeWithText(WRAPPING, useUnmergedTree = true).onParent().getUnclippedBoundsInRoot()
        assertEquals(200f, bubble.width.value, 2f)
    }

    @Test
    fun everyTextLength_keepsDropCenterAndEdgesTappableOnce() {
        var text by mutableStateOf(SHORT)
        var drops = 0
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(412.dp, 892.dp))) {
                PyrycodeMobileTheme { QueuedMessageRow(text, { drops++ }) }
            }
        }
        listOf(SHORT, WRAPPING, "unbroken".repeat(30)).forEach { value ->
            rule.runOnIdle { text = value }
            val drop = rule.onNodeWithContentDescription(DROP).assertIsDisplayed()
            val bounds = drop.getUnclippedBoundsInRoot()
            assertEquals(48f, bounds.width.value, 2f)
            assertEquals(48f, bounds.height.value, 2f)
            assertEquals(368f, ((bounds.left + bounds.right) / 2).value, 2f)
            listOf(0, 1, 2).forEach { position ->
                val before = drops
                drop.performTouchInput {
                    click(
                        when (position) {
                            0 -> center
                            1 -> Offset(1f, center.y)
                            else -> Offset(width - 1f, center.y)
                        },
                    )
                }
                assertEquals(before + 1, drops)
            }
        }
    }

    private companion object {
        const val DROP = "Drop this queued message"
        const val SHORT = "Then push a draft PR."
        const val WRAPPING = "Can you also update the migration tests once you're done?"
    }
}
