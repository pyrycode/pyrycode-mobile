package de.pyryco.mobile.design

import android.util.Base64
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerEntry
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.ui.onboarding.PairCodeEvent
import de.pyryco.mobile.ui.onboarding.PairCodePhase
import de.pyryco.mobile.ui.onboarding.PairCodeState
import de.pyryco.mobile.ui.onboarding.PairCodeViewModel
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

    private val inputs get() = design.inputs

    @Test fun scannerFramesAt412By892() {
        design.launch()
        rule.onNodeWithText("Pyrycode Mobile").assertIsDisplayed()
        design.capture(FOLDER, "welcome", "6:32")

        val scanner = openScanner()
        design.capture(FOLDER, "scanner", "13:2")

        // The real denial needs a fresh permission grant state (ScannerDeniedRouteDeviceTest); the screen is the same.
        send(scanner, ScannerEvent.PermissionDenied)
        rule.onNodeWithText("Camera permission required").assertIsDisplayed()
        design.capture(FOLDER, "scanner-denied", "32:2")

        send(scanner, ScannerEvent.QrDecoded(payload("home.lan:7117")))
        awaitScanner { it is ScannerUiState.AwaitingConfirm }
        design.capture(FOLDER, "pairing-confirm", "654:4834")

        // A null status holds the wait in its connecting state until the 30 s deadline.
        send(scanner, ScannerEvent.ConfirmPairing)
        awaitScanner { it is ScannerUiState.Verifying }
        design.capture(FOLDER, "pairing-connecting", "654:4882")

        inputs.pairingStatus.value = ConnectionStatus(RelayLinkStatus.DaemonAbsent, PyrycodeLinkStatus.Down)
        awaitScanner { it is ScannerUiState.VerificationFailed && it.retryable }
        design.capture(FOLDER, "pairing-failed-retry", "654:4932")

        send(scanner, ScannerEvent.RetryVerification)
        inputs.pairingStatus.value = ConnectionStatus(RelayLinkStatus.PairingRejected, PyrycodeLinkStatus.Down)
        awaitScanner { it is ScannerUiState.VerificationFailed && !it.retryable }
        design.capture(FOLDER, "pairing-failed-rejected", "654:4982")

        send(scanner, ScannerEvent.CancelVerification)
        val again = openScanner()
        send(again, ScannerEvent.CameraError("Couldn't start the camera. Paste the pairing code instead."))
        awaitScanner { it is ScannerUiState.Error }
        design.capture(FOLDER, "scanner-camera-error", "654:5032")
    }

    @Test fun pairCodeFramesAt412By892() {
        design.launch()
        openScanner()
        rule.onNodeWithText("Trouble scanning? Paste the pairing code instead").performClick()
        val pair = awaitPairCode { it.phase == PairCodePhase.Editing }
        rule.onNodeWithText("Host name").assertIsDisplayed()
        send(pair, PairCodeEvent.Name("Pyrybox"))
        send(pair, PairCodeEvent.Code(payload("home.lan:7117")))
        design.capture(FOLDER, "pair", "533:2147")

        design.openKeyboard(rule.onNodeWithText("Host name"))
        design.capture(FOLDER, "pair-keyboard", "533:2147")
        design.closeKeyboard()

        send(pair, PairCodeEvent.Code("not-a-pairing-code"))
        send(pair, PairCodeEvent.Pair)
        awaitPairCode { it.error != null }
        design.capture(FOLDER, "pair-invalid-code", "533:2147")

        send(pair, PairCodeEvent.Code(payload("home.lan:7117")))
        send(pair, PairCodeEvent.Pair)
        awaitPairCode { it.phase == PairCodePhase.Confirming }
        design.capture(FOLDER, "pair-confirm", "654:4834")

        inputs.holdSaves.value = true
        send(pair, PairCodeEvent.Confirm)
        awaitPairCode { it.phase == PairCodePhase.Saving }
        design.capture(FOLDER, "pair-saving", "none")

        inputs.holdSaves.value = false
        awaitPairCode { it.phase == PairCodePhase.Connecting }
        design.capture(FOLDER, "pair-connecting", "654:4882")

        inputs.pairingStatus.value = ConnectionStatus(RelayLinkStatus.DaemonAbsent, PyrycodeLinkStatus.Down)
        awaitPairCode { it.failure?.retryable == true }
        design.capture(FOLDER, "pair-failed-retry", "654:4932")

        send(pair, PairCodeEvent.Pair)
        inputs.pairingStatus.value = ConnectionStatus(RelayLinkStatus.PairingRejected, PyrycodeLinkStatus.Down)
        awaitPairCode { it.failure?.retryable == false }
        design.capture(FOLDER, "pair-failed-rejected", "654:4982")
    }

    @Test fun rePairFramesAt412By892() {
        design.paired = true
        inputs.pairingRejected.value = true
        inputs.pairedHosts += PairedServerEntry(PairedServer("demo", "unused", "wss://demo.invalid", "unused"), "Pyrybox")
        design.launch()
        rule.onNodeWithText("Pyrycode Mobile").performScrollTo().performClick()
        rule.onNodeWithText("Pairing error - Re-pair").performClick()
        val pair = awaitPairCode { it.targetName == "Pyrybox" }
        design.capture(FOLDER, "repair", "533:2147")

        send(pair, PairCodeEvent.Code(payload("home.lan:7117")))
        send(pair, PairCodeEvent.Pair)
        awaitPairCode { it.error != null }
        design.capture(FOLDER, "repair-wrong-host", "533:2147")
    }

    private fun openScanner(): ScannerViewModel {
        rule.onNodeWithText("I already have pyrycode").assertIsDisplayed().performClick()
        val scanner = awaitScanner { it is ScannerUiState.ReadyToScan }
        rule.onNodeWithText("Trouble scanning? Paste the pairing code instead").assertIsDisplayed()
        return scanner
    }

    private fun awaitScanner(predicate: (ScannerUiState) -> Boolean): ScannerViewModel {
        rule.waitUntil(5_000) {
            inputs.scanner.value
                ?.state
                ?.value
                ?.let(predicate) == true
        }
        rule.waitForIdle()
        return checkNotNull(inputs.scanner.value)
    }

    private fun awaitPairCode(predicate: (PairCodeState) -> Boolean): PairCodeViewModel {
        rule.waitUntil(5_000) {
            inputs.pairCode.value
                ?.state
                ?.value
                ?.let(predicate) == true
        }
        rule.waitForIdle()
        return checkNotNull(inputs.pairCode.value)
    }

    private fun send(
        scanner: ScannerViewModel,
        event: ScannerEvent,
    ) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { scanner.onEvent(event) }
        rule.waitForIdle()
    }

    private fun send(
        pair: PairCodeViewModel,
        event: PairCodeEvent,
    ) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { pair.onEvent(event) }
        rule.waitForIdle()
    }

    private fun payload(server: String): String {
        val key = Base64.encodeToString(ByteArray(32) { (it + 1).toByte() }, Base64.NO_WRAP)
        val json = """{"server":"$server","relay":"wss://relay.invalid","token":"design-token","server_static_pubkey":"$key"}"""
        return Base64.encodeToString(json.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private companion object {
        const val FOLDER = "onboarding"
    }
}
