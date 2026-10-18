package de.pyryco.mobile.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Markdown-specific roles from Figma `892:13442`, independent of the shared Material type ramp. */
internal object MarkdownTypography {
    val h1 =
        TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 20.sp,
            lineHeight = 28.sp,
            letterSpacing = 0.sp,
        )
    val h2 =
        TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 18.sp,
            lineHeight = 26.sp,
            letterSpacing = 0.sp,
        )

    // Compose's titleMedium defaults to 0.2sp tracking; Figma's title/medium specifies 0.15sp.
    fun h3(titleMedium: TextStyle): TextStyle = titleMedium.copy(fontFamily = FontFamily.SansSerif, letterSpacing = 0.15.sp)

    val h4ToH6 =
        TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.25.sp,
        )
}
