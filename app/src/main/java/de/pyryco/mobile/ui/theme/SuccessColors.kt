package de.pyryco.mobile.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class SuccessColors(
    val success: Color,
)

internal val LocalSuccessColors: ProvidableCompositionLocal<SuccessColors> =
    staticCompositionLocalOf {
        error("SuccessColors not provided. Wrap content in PyrycodeMobileTheme.")
    }

val ColorScheme.success: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalSuccessColors.current.success
