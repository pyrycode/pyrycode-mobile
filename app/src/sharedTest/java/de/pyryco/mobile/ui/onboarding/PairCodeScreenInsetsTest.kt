package de.pyryco.mobile.ui.onboarding

import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairCodeScreenInsetsTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var statusTop = 0
    private var navigationBottom = 0
    private lateinit var view: View

    // Robolectric reports no system bars, so fixed ones are applied to the Compose view. On a device the
    // real bars may replace them; the assertions use whatever insets Compose actually read.
    private fun applyBars() {
        rule.runOnIdle {
            val density = view.resources.displayMetrics.density
            val bars =
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, (40 * density).toInt(), 0, 0))
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, (48 * density).toInt()))
                    .build()
            ViewCompat.dispatchApplyWindowInsets(view, bars)
        }
        rule.waitForIdle()
    }

    private fun backTop() =
        rule
            .onNodeWithContentDescription("Back")
            .fetchSemanticsNode()
            .boundsInRoot.top

    @Test
    fun backStartsAtStatusInsetAndActionsSitAboveNavigationBar() {
        rule.runOnUiThread { rule.activity.enableEdgeToEdge() }
        rule.setContent {
            view = LocalView.current
            val density = LocalDensity.current
            // Reading the insets here also keeps Compose's inset listener attached across the screen switch.
            statusTop = WindowInsets.statusBars.getTop(density)
            navigationBottom = WindowInsets.navigationBars.getBottom(density)
            PyrycodeMobileTheme(darkTheme = true) {
                PairCodeScreen(PairCodeState(), {})
            }
        }
        applyBars()
        val pairBack = backTop()

        assertTrue("status bar inset $statusTop", statusTop > 0)
        assertTrue("navigation bar inset $navigationBottom", navigationBottom > 0)
        assertEquals(statusTop.toFloat(), pairBack, 1f)
        assertTrue("Back top $pairBack under status bar $statusTop", pairBack >= statusTop)

        val footer = "Open source · github.com/pyrycode/pyrycode-mobile"
        rule.onNodeWithText(footer).performScrollTo()
        val limit =
            rule
                .onRoot()
                .fetchSemanticsNode()
                .boundsInRoot.bottom - navigationBottom
        for (label in listOf("Cancel", footer)) {
            val bottom =
                rule
                    .onNodeWithText(label)
                    .fetchSemanticsNode()
                    .boundsInRoot.bottom
            assertTrue("$label bottom $bottom below navigation bar limit $limit", bottom <= limit + 1)
        }
    }
}
