package de.pyryco.mobile.ui.onboarding

import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// The frames' 412x892 size, and exact text measurement: the title line box is what the frames place.
@Config(qualifiers = "w412dp-h892dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(AndroidJUnit4::class)
class PairingHeaderGeometryTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var view: View
    private var statusTop = 0

    private val screens: List<@Composable () -> Unit> =
        listOf(
            { ScannerScreen(ScannerUiState.ReadyToScan, {}, {}, {}) },
            { ScannerDeniedScreen(onNavigateBack = {}, onOpenSettings = {}, onPasteCode = {}) },
            { PairCodeScreen(PairCodeState(), {}) },
        )

    @Test
    fun titleSits24DpBelowTheStatusInsetOnAllThreeAndDividerSits44DpBelowTheTitle() {
        var screen by mutableIntStateOf(0)
        rule.runOnUiThread { rule.activity.enableEdgeToEdge() }
        rule.setContent {
            view = LocalView.current
            // Reading the insets here keeps Compose's inset listener attached across the screen switch.
            statusTop = WindowInsets.statusBars.getTop(LocalDensity.current)
            PyrycodeMobileTheme(darkTheme = true) { screens[screen]() }
        }
        val density = rule.activity.resources.displayMetrics.density
        val titleTops =
            screens.indices.map { index ->
                rule.runOnIdle { screen = index }
                applyBars(density)
                assertTrue("status bar inset $statusTop", statusTop > 0)
                val titleTop = top("pairing_header_title")
                assertEquals("screen $index title top", statusTop + 24 * density, titleTop, 1f)
                if (index == 1) {
                    rule.onNodeWithTag("pairing_header_divider").assertDoesNotExist()
                } else {
                    assertEquals("screen $index divider top", titleTop + 44 * density, top("pairing_header_divider"), 1f)
                }
                titleTop
            }
        assertEquals(titleTops[0], titleTops[1], 0.5f)
        assertEquals(titleTops[0], titleTops[2], 0.5f)
    }

    // Robolectric reports no system bars, so fixed 24 dp ones are applied to the Compose view.
    private fun applyBars(density: Float) {
        rule.runOnIdle {
            val bar = (24 * density).toInt()
            val bars =
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, bar, 0, 0))
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, bar))
                    .build()
            ViewCompat.dispatchApplyWindowInsets(view, bars)
        }
        rule.waitForIdle()
    }

    private fun top(tag: String) =
        rule
            .onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot.top
}
