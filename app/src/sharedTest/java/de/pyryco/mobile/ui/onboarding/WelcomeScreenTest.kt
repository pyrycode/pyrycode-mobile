package de.pyryco.mobile.ui.onboarding

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WelcomeScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun bodyAtReferenceWidth_isCappedAt320Dp() = assertBodyWidth(412, 320)

    @Test
    fun bodyAtNarrowWidth_shrinksToAvailableWidth() = assertBodyWidth(360, 296)

    private fun assertBodyWidth(
        width: Int,
        expected: Int,
    ) {
        var density = 1f
        composeTestRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, 892.dp))) {
                density = LocalDensity.current.density
                PyrycodeMobileTheme(darkTheme = true) {
                    WelcomeScreen(onPaired = {}, onSetup = {})
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        composeTestRule
            .onNodeWithText("Pyrycode runs Claude", substring = true)
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(expected.toFloat(), layouts.single().size.width / density, 1f)
    }

    @Test
    fun setupText_usesOnSurface() {
        var onSurface = Color.Unspecified
        composeTestRule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                onSurface = MaterialTheme.colorScheme.onSurface
                WelcomeScreen(onPaired = {}, onSetup = {})
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        composeTestRule
            .onNodeWithText("Set up pyrycode first", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(
            onSurface,
            layouts
                .single()
                .layoutInput.style.color,
        )
    }

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
