package de.pyryco.mobile.ui.onboarding

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScannerConnectingScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun connectingMessage_renders() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ScannerConnectingScreen(serverAddress = "home.lan:7117")
            }
        }

        composeTestRule
            .onNode(hasText("Connecting to your pyrycode server", substring = true))
            .assertExists()
    }

    @Test
    fun serverAddress_rendersVerbatim() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ScannerConnectingScreen(serverAddress = "home.lan:7117")
            }
        }

        composeTestRule
            .onNode(hasText("home.lan:7117"))
            .assertExists()
    }
}
