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
class SharedDarkColourRolesTest {
    @get:Rule val rule = createComposeRule()

    @Test fun staticDarkUsesFigmaFixedAndSuccessRoles() {
        val colors = capture(dark = true, dynamic = false)
        assertEquals(Color(0xFF001D34), colors.onPrimaryFixed)
        assertEquals(Color(0xFF2FC038), colors.success)
        assertEquals(Color(0xFF003355), colors.onPrimary)
        assertEquals(Color(0xFF134A74), colors.primaryContainer)
        assertEquals(Color(0xFFFFB59F), colors.tertiaryFixedDim)
    }

    @Test fun lightSuccessKeepsItsPreviousValue() {
        assertEquals(Color(0xFF316B2B), capture(dark = false, dynamic = false).success)
    }

    @Test fun dynamicDarkSuccessKeepsItsPreviousValue() {
        assertEquals(Color(0xFFA6D388), capture(dark = true, dynamic = true).success)
    }

    private fun capture(
        dark: Boolean,
        dynamic: Boolean,
    ): Captured {
        var captured: Captured? = null
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = dark, dynamicColor = dynamic) {
                Capture { captured = it }
            }
        }
        return checkNotNull(captured)
    }

    @Composable
    private fun Capture(onColors: (Captured) -> Unit) {
        val colors = MaterialTheme.colorScheme
        onColors(Captured(colors.onPrimaryFixed, colors.success, colors.onPrimary, colors.primaryContainer, colors.tertiaryFixedDim))
    }

    private data class Captured(
        val onPrimaryFixed: Color,
        val success: Color,
        val onPrimary: Color,
        val primaryContainer: Color,
        val tertiaryFixedDim: Color,
    )
}
