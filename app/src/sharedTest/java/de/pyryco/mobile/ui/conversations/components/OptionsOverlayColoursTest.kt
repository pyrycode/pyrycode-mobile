package de.pyryco.mobile.ui.conversations.components

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
