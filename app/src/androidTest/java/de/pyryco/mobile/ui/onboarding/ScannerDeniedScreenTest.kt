package de.pyryco.mobile.ui.onboarding

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScannerDeniedScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun heading_rendersCameraPermissionRequired() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ScannerDeniedScreen(onOpenSettings = {}, onPasteCode = {})
            }
        }

        composeTestRule
            .onNode(hasText("Camera permission required"))
            .assertExists()
    }

    @Test
    fun openSettingsButton_hasClickAction() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ScannerDeniedScreen(onOpenSettings = {}, onPasteCode = {})
            }
        }

        composeTestRule
            .onNode(hasText("Open settings"))
            .assert(hasClickAction())
    }

    @Test
    fun pasteCodeButton_hasClickAction() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ScannerDeniedScreen(onOpenSettings = {}, onPasteCode = {})
            }
        }

        composeTestRule
            .onNode(hasText("Paste code instead"))
            .assert(hasClickAction())
    }
}
