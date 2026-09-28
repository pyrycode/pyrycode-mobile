package de.pyryco.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppTypographyTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun themeSuppliesFigmaBaseTextMetrics() {
        lateinit var typography: Typography
        rule.setContent {
            PyrycodeMobileTheme(dynamicColor = false) {
                typography = MaterialTheme.typography
            }
        }

        assertMetrics("bodySmall", typography.bodySmall, FontWeight.Normal, 12f, 16f, 0.4f)
        assertMetrics("bodyMedium", typography.bodyMedium, FontWeight.Normal, 14f, 20f, 0.25f)
        assertMetrics("titleSmall", typography.titleSmall, FontWeight.Medium, 14f, 20f, 0.1f)
        assertMetrics("titleLarge", typography.titleLarge, FontWeight.Normal, 22f, 28f, 0f)
        assertMetrics("bodyLarge", typography.bodyLarge, FontWeight.Normal, 16f, 24f, 0.5f)
        assertMetrics("labelLarge", typography.labelLarge, FontWeight.Medium, 14f, 20f, 0.1f)
    }

    @Test
    fun callSiteEmphasisInheritsTheFigmaMetrics() {
        val typography = AppTypography
        assertMetrics(
            "bodySmall emphasized",
            typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            FontWeight.Medium,
            12f,
            16f,
            0.4f,
        )
        assertMetrics(
            "bodyLarge emphasized",
            typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            FontWeight.Medium,
            16f,
            24f,
            0.5f,
        )
        assertMetrics(
            "labelLarge emphasized",
            typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            FontWeight.SemiBold,
            14f,
            20f,
            0.1f,
        )
    }

    private fun assertMetrics(
        name: String,
        style: TextStyle,
        weight: FontWeight,
        size: Float,
        lineHeight: Float,
        tracking: Float,
    ) {
        assertEquals("$name family", FontFamily.SansSerif, style.fontFamily)
        assertEquals("$name weight", weight, style.fontWeight)
        assertEquals("$name size", size.sp, style.fontSize)
        assertEquals("$name line height", lineHeight.sp, style.lineHeight)
        assertEquals("$name tracking", tracking.sp, style.letterSpacing)
    }
}
