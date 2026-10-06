package de.pyryco.mobile.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

// Robolectric draws real pixels here, so the sheet's fill can be sampled; the device ignores this annotation.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class MobileModalFillTest {
    @get:Rule
    val rule = createComposeRule()

    private val title = "Edit host"

    private var dialogView: View? = null
    private var contentColor = Color.Unspecified
    private var expectedContentColor = Color.Unspecified

    private fun show(darkTheme: Boolean) {
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = darkTheme) {
                MobileModal(title = title, onDismissRequest = {}, onSubmit = {}) {
                    dialogView = LocalView.current
                    contentColor = LocalContentColor.current
                    expectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                }
            }
        }
    }

    /**
     * The sheet's centre, which the empty content leaves unpainted by anything but the fill. Robolectric
     * never redraws a dialog window for `captureToImage`, so the dialog's own view is drawn by hand.
     */
    private fun sheetCentre(): Color =
        rule.runOnIdle {
            val view = checkNotNull(dialogView)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            Color(bitmap.getPixel(view.width / 2, view.height / 2))
        }

    @Test
    fun darkShellUsesFigmaHeaderAndFortyDpVisiblePrimaryAction() {
        show(darkTheme = true)
        val titleTop =
            rule
                .onNodeWithText(title)
                .fetchSemanticsNode()
                .boundsInRoot.top
        val bitmap =
            rule.runOnIdle {
                val view = checkNotNull(dialogView)
                Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
            }
        val density = checkNotNull(dialogView).resources.displayMetrics.density
        val dividerX = bitmap.width / 2
        val navy = Color(0xFF001D34).toArgb()
        val dividerY = (titleTop.toInt() until (titleTop + 100 * density).toInt()).first { bitmap.getPixel(dividerX, it) != navy }
        // The divider follows the 28 dp header row by 12 dp. The close glyph fills that row, so its top is the
        // row's top; the title text's own top depends on the font, sitting lower in Robolectric's than on a device.
        val rowTop =
            rule
                .onNodeWithContentDescription("Close", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot.top
        assertEquals(40f, (dividerY - rowTop) / density, 1f)

        val primary = Color(0xFF9DCBFC).toArgb()
        val actionRows =
            ((bitmap.height - 100 * density).toInt() until bitmap.height).filter { y ->
                (0 until bitmap.width).count { x -> bitmap.getPixel(x, y) == primary } > 40 * density
            }
        assertTrue("primary action must be visible", actionRows.isNotEmpty())
        assertEquals(40f, (actionRows.last() - actionRows.first() + 1) / density, 1f)
        assertEquals(24f, (bitmap.height - actionRows.last() - 1) / density, 1f)
    }

    @Test
    fun compactWidthAndLargeTextKeepFooterActionsReachable() {
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 640.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.5f)) {
                    PyrycodeMobileTheme(darkTheme = true) {
                        MobileModal(title = title, onDismissRequest = {}, onSubmit = {}) {
                            repeat(24) { Text("Content item $it", style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            }
        }
        rule.onNodeWithText("Content item 23").performScrollTo().assertIsDisplayed()
        val cancel =
            rule
                .onNodeWithText("Cancel")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val submit =
            rule
                .onNodeWithText("OK")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue("actions overlap", cancel.right < submit.left)
    }

    @Test
    fun darkHoverUsesReferencedButtonRoleFills() {
        val cancelSource = MutableInteractionSource()
        val submitSource = MutableInteractionSource()
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                Column {
                    dialogView = LocalView.current
                    ModalCancelButton("Cancel", onClick = {}, interactionSource = cancelSource)
                    ModalSubmitButton("OK", onClick = {}, enabled = true, loading = false, interactionSource = submitSource)
                }
            }
        }
        rule.runOnIdle { cancelSource.tryEmit(HoverInteraction.Enter()) }
        assertEquals(Color(0xFF003355), actionInterior("Cancel"))
        rule.runOnIdle { submitSource.tryEmit(HoverInteraction.Enter()) }
        assertEquals(Color(0xFFD6E4F7), actionInterior("OK"))
    }

    private fun actionInterior(label: String): Color {
        val bounds = rule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
        return rule.runOnIdle {
            val view = checkNotNull(dialogView)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val inset = (5 * view.resources.displayMetrics.density).toInt()
            Color(bitmap.getPixel(bounds.left.toInt() + inset, bounds.center.y.toInt()))
        }
    }

    @Test
    fun darkThemePaintsTheFramesDeepNavy() {
        show(darkTheme = true)

        assertEquals(Color(0xFF001D34), sheetCentre())
        assertEquals(expectedContentColor, contentColor)
        assertEquals(Color(0xFFCFE4FF), contentColor)
    }

    @Test
    fun lightThemeKeepsThePrimaryContainerFill() {
        show(darkTheme = false)

        assertEquals(Color(0xFFCFE4FF), sheetCentre())
        assertEquals(expectedContentColor, contentColor)
        assertEquals(Color(0xFF134A74), contentColor)
    }

    @Test
    fun darkCancelUsesPrimaryAndKeepsNativeDisabledColour() {
        assertCancelColours(darkTheme = true)
    }

    @Test
    fun lightCancelUsesPrimaryAndKeepsNativeDisabledColour() {
        assertCancelColours(darkTheme = false)
    }

    private fun assertCancelColours(darkTheme: Boolean) {
        val sending = mutableStateOf(false)
        var primary = Color.Unspecified
        var disabled = Color.Unspecified
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = darkTheme) {
                primary = MaterialTheme.colorScheme.primary
                disabled = ButtonDefaults.outlinedButtonColors().disabledContentColor
                MobileGateModal(
                    title = title,
                    cancelLabel = "Cancel",
                    onCancel = {},
                    sending = sending.value,
                ) {
                    dialogView = LocalView.current
                }
            }
        }
        val cancel = rule.onNodeWithText("Cancel")
        cancel.assertIsEnabled().assertHeightIsEqualTo(40.dp)
        assertEquals(primary, cancelTextColour())

        rule.runOnIdle { sending.value = true }
        cancel.assertIsNotEnabled().assertHeightIsEqualTo(40.dp)
        assertEquals(disabled, cancelTextColour())
    }

    private fun cancelTextColour(): Color {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("Cancel").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results
            .single()
            .layoutInput.style.color
    }
}
