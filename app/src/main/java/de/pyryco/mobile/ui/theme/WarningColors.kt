package de.pyryco.mobile.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class WarningColors(
    val warning: Color,
)

internal val LocalWarningColors: ProvidableCompositionLocal<WarningColors> =
    staticCompositionLocalOf {
        error("WarningColors not provided. Wrap content in PyrycodeMobileTheme.")
    }

val ColorScheme.warning: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalWarningColors.current.warning
