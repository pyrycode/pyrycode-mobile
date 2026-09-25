package de.pyryco.mobile.ui.onboarding

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class ScannerFrameTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun fullSizeLightAndDarkFrames() = checkFrame(DpSize(412.dp, 892.dp))

    @Test fun compactLightAndDarkFrames() = checkFrame(DpSize(360.dp, 640.dp))

    private fun checkFrame(size: DpSize) {
        var dark by mutableStateOf(true)
        var backs = 0
        var pastes = 0
        rule.runOnUiThread { rule.activity.enableEdgeToEdge() }
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
                PyrycodeMobileTheme(darkTheme = dark) {
                    Scaffold(
                        modifier = Modifier.fillMaxSize().testTag("scanner_frame"),
                        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets(top = 24.dp, bottom = 24.dp)),
                    ) { padding ->
                        ScannerScreen(
                            ScannerUiState.ReadyToScan,
                            { backs++ },
                            {},
                            { pastes++ },
                            Modifier.padding(padding),
                            cameraPreview = { Box(Modifier.fillMaxSize().testTag("camera")) },
                        )
                    }
                }
            }
        }
        repeat(2) { mode ->
            rule.runOnIdle { dark = mode == 0 }
            rule.onNodeWithText("Pairing").assertIsDisplayed()
            rule.onNodeWithTag("scanner_divider").assertIsDisplayed()
            rule.onNodeWithTag("camera").assertIsDisplayed()
            val reticle = rule.onNodeWithTag("scanner_reticle").assertIsDisplayed().getUnclippedBoundsInRoot()
            rule.onNodeWithText("Run pyry pair on your pyrycode server to generate a QR code.").assertIsDisplayed()
            val hint = rule.onNodeWithTag("scanner_hint").assertIsDisplayed().getUnclippedBoundsInRoot()
            val paste =
                rule
                    .onNodeWithText("Trouble scanning? Paste the pairing code instead")
                    .assertIsDisplayed()
                    .assertHeightIsAtLeast(48.dp)
            val action = paste.getUnclippedBoundsInRoot()
            val camera = rule.onNodeWithTag("camera").getUnclippedBoundsInRoot()
            val frame = rule.onNodeWithTag("scanner_frame").getUnclippedBoundsInRoot()
            assertTrue(action.bottom <= frame.bottom - 24.dp)
            assertTrue(reticle.top >= camera.top)
            if (size.width == 412.dp) {
                assertEquals(248f, (reticle.right - reticle.left).value, 0.5f)
                assertEquals((camera.top + camera.bottom).value / 2, (reticle.top + reticle.bottom).value / 2, 0.5f)
            }
            assertTrue(reticle.bottom + 16.dp <= hint.top)
            assertTrue(hint.bottom < camera.bottom)
            assertTrue(camera.bottom <= action.top)
            assertEquals((reticle.right - reticle.left).value, (reticle.bottom - reticle.top).value, 0.5f)
            val back = rule.onNodeWithContentDescription("Back").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            assertTrue(back.getUnclippedBoundsInRoot().bottom < camera.top)
            assertTrue(back.getUnclippedBoundsInRoot().top >= frame.top + 24.dp)
            capture("scanner-${size.width.value.toInt()}-${if (dark) "dark" else "light"}")
            paste.performClick()
            back.performClick()
        }
        assertEquals(2, backs)
        assertEquals(2, pastes)
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir =
            InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                ?: instrumentation.targetContext.getExternalFilesDir(null)?.path ?: error("Missing screenshot directory")
        rule.saveScreenshot(dir, name) { rule.onNodeWithTag("scanner_frame") }
    }
}
