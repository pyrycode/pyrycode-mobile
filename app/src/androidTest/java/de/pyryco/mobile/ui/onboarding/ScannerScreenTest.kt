package de.pyryco.mobile.ui.onboarding

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
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
                    onTap = {},
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
                    onTap = {},
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
                    onTap = {},
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
                    onTap = {},
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
                    onTap = {},
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
                    onTap = {},
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
    fun error_rendersMessageAndClickablePasteFallback() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ScannerScreen(
                    state = ScannerUiState.Error("Camera unavailable"),
                    onTap = {},
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
