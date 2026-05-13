package de.pyryco.mobile.ui.settings

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.BuildConfig
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun versionRow_rendersBuildConfigVersionName() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                SettingsScreen(onBack = {}, onOpenLicense = {})
            }
        }

        composeTestRule
            .onNode(hasText("Version ${BuildConfig.VERSION_NAME}", substring = true))
            .performScrollTo()
            .assertExists()
    }

    @Test
    fun openSourceRow_hasClickAction() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                SettingsScreen(onBack = {}, onOpenLicense = {})
            }
        }

        composeTestRule
            .onNode(hasText("Open source", substring = true))
            .performScrollTo()
            .assert(hasClickAction())
    }
}
