package de.pyryco.mobile.ui.onboarding

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PairCodeScreenGlowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun glowReachesBothSideEdges_firstPair() = assertGlowAtEdges(PairCodeState())

    @Test fun glowReachesBothSideEdges_rePair() = assertGlowAtEdges(PairCodeState(targetName = "Pyrybox", error = WRONG_HOST_ERROR))

    @Test fun glowReachesBothSideEdges_invalidCode() = assertGlowAtEdges(PairCodeState(code = "nope", error = INVALID_CODE_ERROR))

    @Test fun glowMatchesWelcome() {
        lateinit var view: View
        var welcomeShown by mutableStateOf(false)
        compose.setContent {
            view = LocalView.current
            PyrycodeMobileTheme(darkTheme = true) {
                if (welcomeShown) WelcomeScreen(onPaired = {}, onSetup = {}) else PairCodeScreen(PairCodeState(), {})
            }
        }
        val pair = compose.runOnIdle { draw(view) }
        welcomeShown = true
        val welcome = compose.runOnIdle { draw(view) }
        assertNotEquals("header has its own Top bar treatment", welcome.getPixel(2, pair.height / 40), pair.getPixel(2, pair.height / 40))
        // The body keeps the shared glow; the header now owns the shared Top bar tint.
        for (y in listOf(pair.height * 3 / 10)) {
            for (x in listOf(2, pair.width - 3)) {
                assertEquals("glow at ($x, $y)", welcome.getPixel(x, y), pair.getPixel(x, y))
            }
        }
    }

    @Test fun allPairingHeadersFadeFromTheSharedDarkTopBarColor() {
        lateinit var view: View
        var screen by mutableStateOf(0)
        compose.setContent {
            view = LocalView.current
            PyrycodeMobileTheme(darkTheme = true) {
                when (screen) {
                    0 -> PairCodeScreen(PairCodeState(), {})
                    1 -> ScannerScreen(ScannerUiState.ReadyToScan, {}, {}, {})
                    else -> ScannerDeniedScreen({}, {}, {})
                }
            }
        }
        repeat(3) { index ->
            compose.runOnIdle { screen = index }
            val bitmap = compose.runOnIdle { draw(view) }
            val density = view.resources.displayMetrics.density
            val x = bitmap.width - 3
            val top = Color(bitmap.getPixel(x, (2 * density).toInt()))
            val bottom = Color(bitmap.getPixel(x, (60 * density).toInt()))
            val expected = Color(0xFF09141D)
            assertEquals("screen $index top red", expected.red, top.red, 0.015f)
            assertEquals("screen $index top green", expected.green, top.green, 0.015f)
            assertEquals("screen $index top blue", expected.blue, top.blue, 0.015f)
            assertTrue("screen $index tint fades into atmosphere", bottom.blue > top.blue + 0.01f)
        }
    }

    private fun assertGlowAtEdges(state: PairCodeState) {
        val bitmap = capture { PairCodeScreen(state, {}) }
        val surface = Color(bitmap.getPixel(bitmap.width / 2, bitmap.height - 2))
        val y = bitmap.height * 3 / 10
        for (x in listOf(2, bitmap.width - 3)) {
            val edge = Color(bitmap.getPixel(x, y))
            assertTrue("glow reaches side edge x=$x", edge.blue > surface.blue + 0.03f)
        }
        val header = Color(bitmap.getPixel(bitmap.width - 3, bitmap.height / 40))
        assertTrue("glow covers the header", header.blue > surface.blue + 0.01f)
    }

    private fun capture(content: @Composable () -> Unit): Bitmap {
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            PyrycodeMobileTheme(darkTheme = true) { content() }
        }
        return compose.runOnIdle { draw(view) }
    }

    private fun draw(view: View): Bitmap =
        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
}
