package de.pyryco.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WarningColorSlotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun lightTheme_resolvesWarningToLightValue() {
        var captured: Color? = null
        composeTestRule.setContent {
            PyrycodeMobileTheme(darkTheme = false, dynamicColor = false) {
                CaptureWarning { captured = it }
            }
        }
        assertEquals(warningLight, captured)
    }

    @Test
    fun darkTheme_resolvesWarningToDarkValue() {
        var captured: Color? = null
        composeTestRule.setContent {
            PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                CaptureWarning { captured = it }
            }
        }
        assertEquals(warningDark, captured)
    }

    @Composable
    private fun CaptureWarning(onColor: (Color) -> Unit) {
        onColor(MaterialTheme.colorScheme.warning)
    }
}
