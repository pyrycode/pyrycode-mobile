package de.pyryco.mobile.design

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.ui.onboarding.ScannerEvent
import de.pyryco.mobile.ui.onboarding.ScannerUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Hardware framebuffer proof of shared backdrop blur and sharp foreground chrome with real bars. */
@RunWith(AndroidJUnit4::class)
class PairingHeaderCaptureTest {
    @get:Rule(order = 0)
    val viewport = ViewportRule()

    @get:Rule(order = 1)
    val rule = createEmptyComposeRule()

    @get:Rule(order = 2)
    val design = DesignCapture(rule)

    @Test fun threePairingHeadersKeepTheirGeometryAndBackActions() {
        design.launch()
        rule.onNodeWithText("I already have pyrycode").performTouchInput { click() }
        rule.waitUntil(5_000) {
            design.inputs.scanner.value
                ?.state
                ?.value is ScannerUiState.ReadyToScan
        }
        capture("scanner", "13:2", divider = true)
        rule.onNodeWithText("Trouble scanning? Paste the pairing code instead").performTouchInput { click() }
        rule.onNodeWithText("Host name").assertIsDisplayed()
        capture("pair-code", "533:2147", divider = true)
        back()
        rule.onNodeWithText("Trouble scanning? Paste the pairing code instead").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            checkNotNull(design.inputs.scanner.value).onEvent(ScannerEvent.PermissionDenied)
        }
        rule.onNodeWithText("Camera permission required").assertIsDisplayed()
        capture("denied", "32:2", divider = false)
        back()
        rule.onNodeWithText("I already have pyrycode").assertIsDisplayed()
    }

    private fun capture(
        name: String,
        node: String,
        divider: Boolean,
    ) {
        val back =
            rule
                .onNodeWithContentDescription("Back")
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
                .assertWidthIsAtLeast(48.dp)
        val title = rule.onNodeWithTag("pairing_header_title").fetchSemanticsNode().boundsInRoot
        val density = design.view.resources.displayMetrics.density
        val target = back.fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertEquals("title offset within target", 10 * density, title.top - target.top, 1f)
        if (divider) {
            val line = rule.onNodeWithTag("pairing_header_divider").fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertEquals("title to rule", 44 * density, line.top - title.top, 1f)
        } else {
            rule.onNodeWithTag("pairing_header_divider").assertDoesNotExist()
        }
        design.capture("pairing-chrome-1648", name, node)
    }

    private fun back() = rule.onNodeWithContentDescription("Back").performTouchInput { click() }
}
