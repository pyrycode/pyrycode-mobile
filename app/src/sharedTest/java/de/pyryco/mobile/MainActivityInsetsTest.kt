package de.pyryco.mobile

import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real activity Scaffold and NavHost, not a copy of their modifier chain. */
@RunWith(AndroidJUnit4::class)
class MainActivityInsetsTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun welcomeConsumesChangingSystemBarsExactlyOnce() {
        rule.onNodeWithText("Pyrycode Mobile").assertIsDisplayed()
        val density = rule.activity.resources.displayMetrics.density
        // Both cases leave room for the existing Welcome content on the 731 dp managed device.
        for ((top, bottom) in listOf(16 to 16, 24 to 24)) {
            val topPx = (top * density).toInt()
            val bottomPx = (bottom * density).toInt()
            rule.runOnIdle {
                val content = rule.activity.findViewById<ViewGroup>(android.R.id.content)
                val bars =
                    WindowInsetsCompat
                        .Builder()
                        .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, topPx, 0, 0))
                        .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, bottomPx))
                        .build()
                ViewCompat.dispatchApplyWindowInsets(content.getChildAt(0), bars)
            }
            rule.waitForIdle()
            val title = rule.onNodeWithText("Pyrycode Mobile").fetchSemanticsNode().boundsInRoot
            // The decorative logo has no semantics: title top = logo top + logo height + hero gap.
            assertEquals("logo starts 168 dp below one status inset", topPx + 300 * density, title.top, 1f)
            val footer =
                rule
                    .onNodeWithText("Open source · github.com/pyrycode/pyrycode-mobile")
                    .fetchSemanticsNode()
                    .boundsInRoot
            val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
            assertEquals("footer reserves one navigation inset", root.bottom - bottomPx - 16 * density, footer.bottom, 1f)
        }
    }
}
