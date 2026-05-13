package de.pyryco.mobile.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LicenseScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun backArrow_isPresent() {
        val cdBack = InstrumentationRegistry.getInstrumentation()
            .targetContext.getString(R.string.cd_back)

        composeTestRule.setContent {
            PyrycodeMobileTheme {
                LicenseScreen(onBack = {})
            }
        }

        composeTestRule
            .onNode(hasContentDescription(cdBack))
            .assertIsDisplayed()
    }

    @Test
    fun licenseBody_rendersKnownFragment() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                LicenseScreen(onBack = {})
            }
        }

        composeTestRule
            .onNode(hasText("Permission is hereby granted", substring = true))
            .assertExists()
    }
}
