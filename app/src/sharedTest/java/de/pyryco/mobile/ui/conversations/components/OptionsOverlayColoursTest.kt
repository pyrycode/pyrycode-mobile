package de.pyryco.mobile.ui.conversations.components

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(AndroidJUnit4::class)
class OptionsOverlayColoursTest {
    @get:Rule val rule = createComposeRule()

    @Test fun lightMenuKeepsReadableIdleRows() = assertReadableLightMenu(dynamic = false)

    @Test fun dynamicLightMenuKeepsReadableIdleRows() = assertReadableLightMenu(dynamic = true)

    private fun assertReadableLightMenu(dynamic: Boolean) {
        var label = Color.Unspecified
        var detail = Color.Unspecified
        var idle = Color.Unspecified
        var view: android.view.View? = null
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = false, dynamicColor = dynamic) {
                val colors = MaterialTheme.colorScheme
                label = colors.primary
                detail = colors.onSurfaceVariant
                idle = colors.surfaceContainerLowest
                view = LocalView.current
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    OptionsOverlay(
                        options = listOf(OptionsOverlayOption("idle", "Idle", detail = "Detail")),
                        selectedValue = "none",
                        notListed = 0,
                        anchor = Rect(40f, 300f, 100f, 340f),
                        onSelect = {},
                        onDismiss = {},
                    )
                }
            }
        }
        val bitmap =
            rule.runOnIdle {
                val root = checkNotNull(view)
                Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
            }
        val bounds = rule.onNodeWithText("Idle").fetchSemanticsNode().boundsInRoot
        val actualIdle = Color(bitmap.getPixel(bounds.left.roundToInt() + 6, bounds.center.y.roundToInt()))
        rule.runOnIdle {
            assertEquals(idle.toArgb(), actualIdle.toArgb())
            assertEquals(true, contrast(label, idle) >= 4.5)
            assertEquals(true, contrast(detail, idle) >= 4.5)
        }
    }

    private fun contrast(
        foreground: Color,
        background: Color,
    ): Double {
        val a = android.graphics.Color.luminance(foreground.toArgb()) + 0.05
        val b = android.graphics.Color.luminance(background.toArgb()) + 0.05
        return maxOf(a, b) / minOf(a, b)
    }

    @Test fun compactViewportWithLargeTextCanReachLastOption() {
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(280.dp, 400.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.6f)) {
                    PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                        val density = LocalDensity.current
                        OptionsOverlay(
                            options =
                                (1..12).map { index ->
                                    OptionsOverlayOption(index.toString(), "Option $index", detail = "Description for option $index")
                                },
                            selectedValue = "1",
                            notListed = 0,
                            anchor =
                                with(density) {
                                    Rect(16.dp.toPx(), 300.dp.toPx(), 100.dp.toPx(), 340.dp.toPx())
                                },
                            onSelect = {},
                            onDismiss = {},
                        )
                    }
                }
            }
        }
        rule.onNodeWithText("Option 12").performScrollTo().assertIsDisplayed()
        val last = rule.onNodeWithText("Option 12").getUnclippedBoundsInRoot()
        assertEquals(true, last.bottom <= 296.dp)
        assertEquals(true, last.left >= 8.dp)
    }

    @Test fun rowsMatchFigmaHeightAndStayAboveAnchor() {
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                val density = LocalDensity.current
                OptionsOverlay(
                    options = listOf(OptionsOverlayOption("selected", "Selected"), OptionsOverlayOption("other", "Other")),
                    selectedValue = "selected",
                    notListed = 0,
                    anchor =
                        with(density) {
                            Rect(40.dp.toPx(), 300.dp.toPx(), 100.dp.toPx(), 340.dp.toPx())
                        },
                    onSelect = {},
                    onDismiss = {},
                )
            }
        }
        val selected = rule.onNodeWithText("Selected").getUnclippedBoundsInRoot()
        val other = rule.onNodeWithText("Other").getUnclippedBoundsInRoot()
        assertEquals(28.dp, selected.bottom - selected.top)
        assertEquals(28.dp, other.bottom - other.top)
        assertEquals(4.dp, 300.dp - other.bottom - 2.dp)
    }

    @Test fun belowMenuFollowsLiveAnchorAndClampsBothHorizontalEdges() {
        var anchorLeft by mutableStateOf(40.dp)
        var anchorBottom by mutableStateOf(100.dp)
        rule.setContent {
            PyrycodeMobileTheme {
                val density = LocalDensity.current
                OptionsOverlay(
                    options = listOf(OptionsOverlayOption("settings", "Settings"), OptionsOverlayOption("archive", "Archive")),
                    selectedValue = "",
                    notListed = 0,
                    anchor = with(density) { Rect(anchorLeft.toPx(), 60.dp.toPx(), (anchorLeft + 44.dp).toPx(), anchorBottom.toPx()) },
                    onSelect = {},
                    onDismiss = {},
                    actions = true,
                    placement = OptionsOverlayPlacement.Below,
                )
            }
        }
        val first = rule.onNodeWithText("Settings").getUnclippedBoundsInRoot()
        assertEquals(106.dp, first.top)
        assertEquals(28.dp, first.left)
        rule.runOnIdle {
            anchorLeft = 0.dp
            anchorBottom = 150.dp
        }
        val moved = rule.onNodeWithText("Settings").getUnclippedBoundsInRoot()
        assertEquals(156.dp, moved.top)
        assertEquals(8.dp, moved.left)
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        rule.runOnIdle { anchorLeft = root.right }
        assertEquals(root.right - 8.dp, rule.onNodeWithText("Settings").getUnclippedBoundsInRoot().right)
    }

    @Test fun belowMenuScrollsWithinTheSpaceBelowItsAnchor() {
        rule.setContent {
            PyrycodeMobileTheme {
                val density = LocalDensity.current
                OptionsOverlay(
                    options = (1..60).map { OptionsOverlayOption(it.toString(), "Option $it") },
                    selectedValue = "",
                    notListed = 0,
                    anchor = with(density) { Rect(40.dp.toPx(), 400.dp.toPx(), 84.dp.toPx(), 444.dp.toPx()) },
                    onSelect = {},
                    onDismiss = {},
                    actions = true,
                    placement = OptionsOverlayPlacement.Below,
                )
            }
        }
        val layerBottom = rule.onRoot().getUnclippedBoundsInRoot().bottom
        val column = rule.onNode(hasScrollAction()).getUnclippedBoundsInRoot()
        assertEquals(448.dp, column.top)
        assertEquals(layerBottom - 8.dp, column.bottom)
        rule.onNodeWithText("Option 60").performScrollTo().assertIsDisplayed()
        assertEquals(true, rule.onNodeWithText("Option 60").getUnclippedBoundsInRoot().bottom <= layerBottom - 8.dp)
    }

    @Test fun belowActionsUseTheExistingLightPalette() = assertActionPalette(dark = false)

    @Test fun belowActionsUseTheExistingDarkPalette() = assertActionPalette(dark = true)

    private fun assertActionPalette(dark: Boolean) {
        var view: android.view.View? = null
        var expected = Color.Unspecified
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = dark, dynamicColor = false) {
                view = LocalView.current
                expected = if (dark) MaterialTheme.colorScheme.onPrimaryFixed else MaterialTheme.colorScheme.surfaceContainerLowest
                OptionsOverlay(
                    options = listOf(OptionsOverlayOption("settings", "Settings"), OptionsOverlayOption("archive", "Archive")),
                    selectedValue = "settings",
                    notListed = 0,
                    anchor = Rect(40f, 60f, 84f, 104f),
                    onSelect = {},
                    onDismiss = {},
                    actions = true,
                    placement = OptionsOverlayPlacement.Below,
                )
            }
        }
        val bitmap =
            rule.runOnIdle {
                val root = checkNotNull(view)
                Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
            }
        for (label in listOf("Settings", "Archive")) {
            val bounds = rule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
            assertEquals(expected.toArgb(), bitmap.getPixel(bounds.left.roundToInt() + 6, bounds.center.y.roundToInt()))
        }
    }

    @Test fun staticDarkMenuUsesBoundFixedAndOnPrimaryRoles() {
        var view: android.view.View? = null
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                view = LocalView.current
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    OptionsOverlay(
                        options = listOf(OptionsOverlayOption("selected", "Selected"), OptionsOverlayOption("other", "Other")),
                        selectedValue = "selected",
                        notListed = 0,
                        anchor = Rect(40f, 300f, 100f, 340f),
                        onSelect = {},
                        onDismiss = {},
                    )
                }
            }
        }
        val bitmap =
            rule.runOnIdle {
                val root = checkNotNull(view)
                Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
            }

        fun rowPixel(label: String): Int {
            val bounds = rule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
            return bitmap.getPixel(bounds.left.roundToInt() + 6, bounds.center.y.roundToInt())
        }
        assertEquals("Selected row shows On Primary surface", android.graphics.Color.rgb(0, 51, 85), rowPixel("Selected"))
        assertEquals("Unselected row uses On Primary Fixed", android.graphics.Color.rgb(0, 29, 52), rowPixel("Other"))
    }
}
