package de.pyryco.mobile.design

import android.util.Base64
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.ui.onboarding.ScannerEvent
import de.pyryco.mobile.ui.onboarding.ScannerUiState
import de.pyryco.mobile.ui.onboarding.ScannerViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Onboarding states of the assembled app at the Figma frames' 412x892 viewport (design-1220/onboarding). */
@RunWith(AndroidJUnit4::class)
class OnboardingDesignCaptureTest {
    @get:Rule(order = 0)
    val viewport = ViewportRule()

    @get:Rule(order = 1)
    val rule = createEmptyComposeRule()

    @get:Rule(order = 2)
    val design = DesignCapture(rule)

    @Test fun onboardingFramesAt412By892() {
        design.launch()
        rule.onNodeWithText("Pyrycode Mobile").assertIsDisplayed()
        design.capture(FOLDER, "welcome", "6:32")

        rule.onNodeWithText("I already have pyrycode").performClick()
        val scanner = awaitScanner { it is ScannerUiState.ReadyToScan }
        rule.onNodeWithText("Trouble scanning? Paste the pairing code instead").assertIsDisplayed()
        design.capture(FOLDER, "scanner", "13:2")

        // The real denial needs a fresh permission grant state (ScannerDeniedRouteDeviceTest); the screen is the same.
        send(scanner, ScannerEvent.PermissionDenied)
        rule.onNodeWithText("Camera permission required").assertIsDisplayed()
        design.capture(FOLDER, "scanner-denied", "32:2")

        send(scanner, ScannerEvent.QrDecoded(pairingPayload()))
        awaitScanner { it is ScannerUiState.AwaitingConfirm }
        design.capture(FOLDER, "pairing-confirm", "none")

        // pairingStatus never answers, so the confirm wait stays in its connecting state.
        send(scanner, ScannerEvent.ConfirmPairing)
        awaitScanner { it is ScannerUiState.Verifying }
        design.capture(FOLDER, "scanner-connecting", "32:20")

        send(scanner, ScannerEvent.CancelVerification)
        rule.onNodeWithText("I already have pyrycode").assertIsDisplayed().performClick()
        awaitScanner { it is ScannerUiState.ReadyToScan }
        rule.onNodeWithText("Trouble scanning? Paste the pairing code instead").performClick()
        rule.onNodeWithText("Host name").assertIsDisplayed()
        rule.onNodeWithText("Pairing code").assertIsDisplayed()
        design.capture(FOLDER, "pair", "533:2147")

        design.openKeyboard(rule.onNodeWithText("Host name"))
        design.capture(FOLDER, "pair-keyboard", "533:2147")
        design.closeKeyboard()
    }

    private fun awaitScanner(predicate: (ScannerUiState) -> Boolean): ScannerViewModel {
        rule.waitUntil(5_000) {
            design.inputs.scanner.value
                ?.state
                ?.value
                ?.let(predicate) == true
        }
        rule.waitForIdle()
        return checkNotNull(design.inputs.scanner.value)
    }

    private fun send(
        scanner: ScannerViewModel,
        event: ScannerEvent,
    ) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { scanner.onEvent(event) }
        rule.waitForIdle()
    }

    private fun pairingPayload(): String {
        val key = Base64.encodeToString(ByteArray(32) { (it + 1).toByte() }, Base64.NO_WRAP)
        val json = """{"server":"home.lan:7117","relay":"wss://relay.invalid","token":"design-token","server_static_pubkey":"$key"}"""
        return Base64.encodeToString(json.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private companion object {
        const val FOLDER = "onboarding"
    }
}
