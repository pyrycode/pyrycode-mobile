package de.pyryco.mobile.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

// Robolectric draws real pixels here, so the sheet's fill can be sampled; the device ignores this annotation.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(AndroidJUnit4::class)
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
}
