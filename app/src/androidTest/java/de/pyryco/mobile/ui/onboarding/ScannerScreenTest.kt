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
class ScannerScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun topAppBar_rendersPairWithPyrycodeTitle() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ScannerScreen(onTap = {})
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
                ScannerScreen(onTap = {})
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
                ScannerScreen(onTap = {})
            }
        }

        composeTestRule
            .onNode(hasText("Trouble scanning?", substring = true))
            .assert(hasClickAction())
    }
}
