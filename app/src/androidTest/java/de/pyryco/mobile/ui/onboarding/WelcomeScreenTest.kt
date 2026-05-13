package de.pyryco.mobile.ui.onboarding

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WelcomeScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun pairedCta_isDisplayed() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                WelcomeScreen(onPaired = {}, onSetup = {})
            }
        }

        composeTestRule
            .onNodeWithText("I already have pyrycode")
            .assertIsDisplayed()
    }

    @Test
    fun setupCta_isDisplayed() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                WelcomeScreen(onPaired = {}, onSetup = {})
            }
        }

        composeTestRule
            .onNodeWithText("Set up pyrycode first")
            .assertIsDisplayed()
    }

    @Test
    fun tappingPairedCta_invokesOnPairedOnly() {
        var pairedCount = 0
        var setupCount = 0

        composeTestRule.setContent {
            PyrycodeMobileTheme {
                WelcomeScreen(
                    onPaired = { pairedCount++ },
                    onSetup = { setupCount++ },
                )
            }
        }

        composeTestRule
            .onNodeWithText("I already have pyrycode")
            .performClick()

        assertEquals(1, pairedCount)
        assertEquals(0, setupCount)
    }

    @Test
    fun tappingSetupCta_invokesOnSetupOnly() {
        var pairedCount = 0
        var setupCount = 0

        composeTestRule.setContent {
            PyrycodeMobileTheme {
                WelcomeScreen(
                    onPaired = { pairedCount++ },
                    onSetup = { setupCount++ },
                )
            }
        }

        composeTestRule
            .onNodeWithText("Set up pyrycode first")
            .performClick()

        assertEquals(1, setupCount)
        assertEquals(0, pairedCount)
    }
}
