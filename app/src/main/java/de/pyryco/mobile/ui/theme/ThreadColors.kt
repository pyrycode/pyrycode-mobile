package de.pyryco.mobile.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Screen-local roles shared by the conversation thread and markdown reader. */
@Immutable
internal data class ThreadColors(
    val background: Color,
    val surface: Color,
    val headerRule: Color,
    val glow: Color?,
)

internal val LocalThreadColors =
    staticCompositionLocalOf<ThreadColors> {
        error("Thread colours not provided. Wrap content in PyrycodeMobileTheme.")
    }

internal val ColorScheme.threadColors: ThreadColors
    @Composable
    @ReadOnlyComposable
    get() = LocalThreadColors.current
