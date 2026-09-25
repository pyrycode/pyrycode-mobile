package de.pyryco.mobile.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class ModalColors(
    val container: Color,
)

internal val LocalModalColors: ProvidableCompositionLocal<ModalColors> =
    staticCompositionLocalOf {
        error("ModalColors not provided. Wrap content in PyrycodeMobileTheme.")
    }

val ColorScheme.modalContainer: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalModalColors.current.container
