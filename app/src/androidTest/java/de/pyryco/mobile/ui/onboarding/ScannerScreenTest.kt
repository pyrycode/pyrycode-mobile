package de.pyryco.mobile.ui.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScannerScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun topAppBar_rendersPairWithPyrycodeTitle() {
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
            .onNode(hasText("Pair with pyrycode"))
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
            .onNode(hasText("Pair with pyrycode"))
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
            .onNode(hasText("Pair with pyrycode"))
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
            .onNode(hasText("Pair with pyrycode"))
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
}
