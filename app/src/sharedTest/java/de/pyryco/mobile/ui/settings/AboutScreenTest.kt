package de.pyryco.mobile.ui.settings

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.BuildConfig
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AboutScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun versionRow_rendersBuildConfigVersionName() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                AboutScreen(onBack = {})
            }
        }

        composeTestRule
            .onNode(hasText("Version ${BuildConfig.VERSION_NAME}", substring = true))
            .performScrollTo()
            .assertExists()
    }

    @Test
    fun versionRow_rendersSupportingTextWithGitSha() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                AboutScreen(onBack = {})
            }
        }

        composeTestRule
            .onNode(hasText("build ${BuildConfig.GIT_SHA}", substring = true))
            .performScrollTo()
            .assertExists()
    }

    @Test
    fun openSourceRow_hasClickAction() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                AboutScreen(onBack = {})
            }
        }

        composeTestRule
            .onNode(hasText("Open source", substring = true))
            .performScrollTo()
            .assert(hasClickAction())
    }

    @Test
    fun licenseRow_hasNoClickAction() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                AboutScreen(onBack = {})
            }
        }

        composeTestRule
            .onNodeWithText("License: MIT")
            .performScrollTo()
            .assert(hasClickAction().not())
    }

    @Test
    fun backArrow_invokesOnBack() {
        var backCount = 0
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                AboutScreen(onBack = { backCount++ })
            }
        }

        composeTestRule.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, backCount)
    }
}
