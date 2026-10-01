package de.pyryco.mobile.ui.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScannerScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun topAppBar_rendersPairingTitle() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ScannerScreen(
                    state = ScannerUiState.ReadyToScan,
                    onNavigateBack = {},
                    onOpenSettings = {},
                    onPasteCode = {},
                )
            }
        }

        composeTestRule
            .onNode(hasText("Pairing"))
            .assertExists()
    }

    @Test
    fun hintCard_rendersPyryPairInstruction() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ScannerScreen(
                    state = ScannerUiState.ReadyToScan,
                    onNavigateBack = {},
                    onOpenSettings = {},
                    onPasteCode = {},
                )
            }
        }

        composeTestRule
            .onNode(hasText("pyry pair", substring = true))
            .assertExists()
    }

    @Test
    fun pasteCodeFallback_hasClickAction() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ScannerScreen(
                    state = ScannerUiState.ReadyToScan,
                    onNavigateBack = {},
                    onOpenSettings = {},
                    onPasteCode = {},
                )
            }
        }

        composeTestRule
            .onNode(hasText("Trouble scanning?", substring = true))
            .assert(hasClickAction())
    }

    @Test
    fun permissionRequesting_rendersViewportShell() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ScannerScreen(
                    state = ScannerUiState.PermissionRequesting,
                    onNavigateBack = {},
                    onOpenSettings = {},
                    onPasteCode = {},
                )
            }
        }

        composeTestRule
            .onNode(hasText("Pairing"))
            .assertExists()
    }

    @Test
    fun denied_rendersScannerDeniedScreen() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ScannerScreen(
                    state = ScannerUiState.Denied,
                    onNavigateBack = {},
                    onOpenSettings = {},
                    onPasteCode = {},
                )
            }
        }

        composeTestRule
            .onNode(hasText("Camera permission required"))
            .assertExists()
    }

    @Test
    fun denied_backReturnsToCaller() {
        val actions = mutableListOf<String>()
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ScannerScreen(
                    state = ScannerUiState.Denied,
                    onNavigateBack = { actions.add("back") },
                    onOpenSettings = { actions.add("settings") },
                    onPasteCode = { actions.add("paste") },
                )
            }
        }
        composeTestRule.onNode(hasText("Pair with pyrycode")).assertExists()
        composeTestRule
            .onNode(hasContentDescription("Back"))
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .performClick()
        composeTestRule.runOnIdle { assertEquals(listOf("back"), actions) }
    }

    @Test
    fun decoded_rendersViewportUnchanged() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ScannerScreen(
                    state = ScannerUiState.Decoded("ignored-payload"),
                    onNavigateBack = {},
                    onOpenSettings = {},
                    onPasteCode = {},
                )
            }
        }

        // Scope guard: the Decoded state renders the existing locked viewport — no new visible
        // surface, the payload is never displayed.
        composeTestRule
            .onNode(hasText("Pairing"))
            .assertExists()
        composeTestRule
            .onNode(hasText("pyry pair", substring = true))
            .assertExists()
    }

    @Test
    fun cameraPreviewSlot_rendersBehindLockedOverlay() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ScannerScreen(
                    state = ScannerUiState.ReadyToScan,
                    onNavigateBack = {},
                    onOpenSettings = {},
                    onPasteCode = {},
                    cameraPreview = {
                        Box(modifier = Modifier.fillMaxSize().testTag("camera"))
                    },
                )
            }
        }

        // AC1: the injected preview slot renders, and the locked overlay is undisturbed over it.
        composeTestRule
            .onNodeWithTag("camera")
            .assertExists()
        composeTestRule
            .onNode(hasText("Pairing"))
            .assertExists()
        composeTestRule
            .onNode(hasText("pyry pair", substring = true))
            .assertExists()
    }

    @Test
    fun error_rendersMessageAndClickablePasteFallback() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ScannerScreen(
                    state = ScannerUiState.Error("Camera unavailable"),
                    onNavigateBack = {},
                    onOpenSettings = {},
                    onPasteCode = {},
                )
            }
        }

        composeTestRule
            .onNode(hasText("Camera unavailable"))
            .assertExists()
        composeTestRule
            .onNodeWithText("Paste the pairing code instead")
            .assertHasClickAction()
    }

    // ---- AwaitingConfirm: the fingerprint confirm gate (#343) ------------------

    @Test
    fun awaitingConfirm_usesMobileModalWithExactFingerprintAndCloseDecline() {
        var declines = 0
        composeTestRule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                ScannerScreen(
                    state = ScannerUiState.AwaitingConfirm(FINGERPRINT, pairedServer()),
                    onNavigateBack = {},
                    onOpenSettings = {},
                    onPasteCode = {},
                    onDeclinePairing = { declines++ },
                )
            }
        }

        composeTestRule.onNodeWithText("Pair").assertExists()
        composeTestRule.onNodeWithText(FINGERPRINT).assertExists()
        composeTestRule.onNodeWithText("Confirm pairing").assertHasClickAction()
        composeTestRule.onNodeWithText("Don't pair").assertHasClickAction()
        composeTestRule.onNodeWithContentDescription("Close").performClick()
        composeTestRule.runOnIdle { assertEquals(1, declines) }
    }

    @Test
    fun awaitingConfirm_rendersFingerprintVerbatimAndCompareCopy() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ScannerScreen(
                    state = ScannerUiState.AwaitingConfirm(FINGERPRINT, pairedServer()),
                    onNavigateBack = {},
                    onOpenSettings = {},
                    onPasteCode = {},
                )
            }
        }

        // Rendered byte-for-byte as the #342 colon-lowercase-hex form (AC #4).
        composeTestRule.onNode(hasText(FINGERPRINT)).assertExists()
        // Compare copy points at the other device's Static-key fp line (AC #4).
        composeTestRule.onNode(hasText("other device", substring = true)).assertExists()
    }

    @Test
    fun awaitingConfirm_fingerprint_exposesContentDescription() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ScannerScreen(
                    state = ScannerUiState.AwaitingConfirm(FINGERPRINT, pairedServer()),
                    onNavigateBack = {},
                    onOpenSettings = {},
                    onPasteCode = {},
                )
            }
        }

        composeTestRule
            .onNode(hasContentDescription("Server fingerprint $FINGERPRINT"))
            .assertExists()
    }

    @Test
    fun awaitingConfirm_confirmButton_invokesOnConfirm() {
        var confirmed = false
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ScannerScreen(
                    state = ScannerUiState.AwaitingConfirm(FINGERPRINT, pairedServer()),
                    onNavigateBack = {},
                    onOpenSettings = {},
                    onPasteCode = {},
                    onConfirmPairing = { confirmed = true },
                )
            }
        }

        composeTestRule.onNodeWithText("Confirm pairing").assertHasClickAction().performClick()
        assertTrue(confirmed)
    }

    @Test
    fun awaitingConfirm_declineButton_invokesOnDecline() {
        var declined = false
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ScannerScreen(
                    state = ScannerUiState.AwaitingConfirm(FINGERPRINT, pairedServer()),
                    onNavigateBack = {},
                    onOpenSettings = {},
                    onPasteCode = {},
                    onDeclinePairing = { declined = true },
                )
            }
        }

        composeTestRule.onNodeWithText("Don't pair").assertHasClickAction().performClick()
        assertTrue(declined)
    }

    @Test
    fun awaitingConfirm_confirmAndDecline_meetMinimumTouchTargetHeight() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ScannerScreen(
                    state = ScannerUiState.AwaitingConfirm(FINGERPRINT, pairedServer()),
                    onNavigateBack = {},
                    onOpenSettings = {},
                    onPasteCode = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Confirm pairing").assertHeightIsAtLeast(48.dp)
        composeTestRule.onNodeWithText("Don't pair").assertHeightIsAtLeast(48.dp)
    }

    // ---- Verification after Confirm (#1386) -----------------------------------

    @Test
    fun verifying_keepsTheModalLoadingWithCancelEnabled() {
        var cancelled = false
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ScannerScreen(
                    state = ScannerUiState.Verifying(FINGERPRINT, pairedServer()),
                    onNavigateBack = {},
                    onOpenSettings = {},
                    onPasteCode = {},
                    onCancelPairing = { cancelled = true },
                )
            }
        }

        composeTestRule.onNodeWithText(FINGERPRINT).assertExists()
        composeTestRule.onNodeWithText("Confirm pairing").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Don't pair").assertDoesNotExist()
        composeTestRule.onNodeWithText("Cancel").assertIsEnabled().performClick()
        assertTrue(cancelled)
    }

    @Test
    fun verificationFailed_offersRetryOnlyWhenTheStepAllowsIt() {
        val events = mutableListOf<String>()
        var state by mutableStateOf<ScannerUiState>(failed(PairingVerification.Failure.Unavailable))
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ScannerScreen(
                    state = state,
                    onNavigateBack = {},
                    onOpenSettings = {},
                    onPasteCode = {},
                    onRetryPairing = { events += "retry" },
                    onCancelPairing = { events += "cancel" },
                )
            }
        }

        composeTestRule.onNodeWithText(PairingVerification.Failure.Unavailable.message).assertExists()
        composeTestRule.onNodeWithText("Confirm pairing").assertDoesNotExist()
        composeTestRule.onNodeWithText("Retry").assertIsEnabled().performClick()
        composeTestRule.onNodeWithText("Cancel").assertIsEnabled().performClick()
        composeTestRule.runOnIdle { assertEquals(listOf("retry", "cancel"), events.toList()) }

        composeTestRule.runOnIdle {
            events.clear()
            state = failed(PairingVerification.Failure.Rejected)
        }
        composeTestRule.onNodeWithText(PairingVerification.Failure.Rejected.message).assertExists()
        composeTestRule.onNodeWithText("Retry").assertDoesNotExist()
        composeTestRule.onNodeWithText("Confirm pairing").assertDoesNotExist()
        composeTestRule.onNodeWithText("Cancel").assertIsEnabled().performClick()
        composeTestRule.runOnIdle { assertEquals(listOf("cancel"), events.toList()) }
    }

    // A rebuilt Dialog window composes fresh layout nodes, so a changed semantics id means the modal closed and reopened.
    @Test
    fun confirmWaitAndFailure_keepTheSameModalWindow() {
        var state by mutableStateOf<ScannerUiState>(ScannerUiState.AwaitingConfirm(FINGERPRINT, pairedServer()))
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ScannerScreen(state = state, onNavigateBack = {}, onOpenSettings = {}, onPasteCode = {})
            }
        }
        val fingerprintId = composeTestRule.onNodeWithText(FINGERPRINT).fetchSemanticsNode().id

        listOf(
            ScannerUiState.Verifying(FINGERPRINT, pairedServer()),
            failed(PairingVerification.Failure.Unavailable),
            ScannerUiState.Verifying(FINGERPRINT, pairedServer()),
            failed(PairingVerification.Failure.Rejected),
        ).forEach { next ->
            composeTestRule.runOnIdle { state = next }
            assertEquals(fingerprintId, composeTestRule.onNodeWithText(FINGERPRINT).fetchSemanticsNode().id)
        }
    }

    private fun failed(failure: PairingVerification.Failure) =
        ScannerUiState.VerificationFailed(FINGERPRINT, pairedServer(), failure.message, failure.retryable)

    private companion object {
        const val FINGERPRINT = "32:0b:5e:a9:9e:65:3b:c2"

        fun pairedServer() =
            PairedServer(
                serverId = "srv-1",
                token = "tok-123",
                relayUrl = "wss://relay.example.com",
                serverStaticPublicKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            )
    }
}
