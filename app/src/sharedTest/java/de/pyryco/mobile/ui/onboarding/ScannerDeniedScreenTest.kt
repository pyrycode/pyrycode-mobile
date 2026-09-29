package de.pyryco.mobile.ui.onboarding

import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScannerDeniedScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun heading_rendersCameraPermissionRequired() {
        showScreen()
        rule.onNodeWithText("Camera permission required").assertExists()
    }

    @Test
    fun openSettings_dispatchesOnlySettings() {
        val actions = showScreen()
        rule.onNodeWithText("Open settings").assertHeightIsEqualTo(48.dp).performClick()
        rule.runOnIdle { assertEquals(listOf("settings"), actions) }
    }

    @Test
    fun pasteCode_hasAccessibleTargetAndDispatchesOnlyPaste() {
        val actions = showScreen()
        rule
            .onNodeWithText("Paste code instead")
            .assertHeightIsEqualTo(40.dp)
            .assertTouchHeightIsEqualTo(48.dp)
            .performClick()
        rule.runOnIdle { assertEquals(listOf("paste"), actions) }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun compactEnlargedText_keepsCopyAndActionsSeparate() {
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 640.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.5f)) {
                    PyrycodeMobileTheme(darkTheme = true) {
                        ScannerDeniedScreen(onNavigateBack = {}, onOpenSettings = {}, onPasteCode = {})
                    }
                }
            }
        }
        val heading = rule.onNodeWithText("Camera permission required").getUnclippedBoundsInRoot()
        val copy =
            rule
                .onNodeWithText(
                    "Pyrycode needs the camera to read the QR code from your server. You can also paste the pairing code instead.",
                ).getUnclippedBoundsInRoot()
        val settings = rule.onNodeWithText("Open settings").getUnclippedBoundsInRoot()
        val paste = rule.onNodeWithText("Paste code instead").getUnclippedBoundsInRoot()
        val screen = rule.onRoot().getUnclippedBoundsInRoot()
        assertTrue(heading.bottom < copy.top)
        assertTrue(copy.bottom < settings.top)
        assertTrue(settings.bottom <= paste.top)
        assertTrue(paste.bottom <= screen.bottom)
    }

    private fun showScreen(): List<String> {
        val actions = mutableListOf<String>()
        rule.setContent {
            PyrycodeMobileTheme {
                ScannerDeniedScreen(
                    onNavigateBack = { actions.add("back") },
                    onOpenSettings = { actions.add("settings") },
                    onPasteCode = { actions.add("paste") },
                )
            }
        }
        return actions
    }
}
