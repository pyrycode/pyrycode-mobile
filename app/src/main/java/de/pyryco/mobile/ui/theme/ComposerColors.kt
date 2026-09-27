package de.pyryco.mobile.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

internal val LocalComposerFieldContainer =
    staticCompositionLocalOf<Color> {
        error("Composer field colour not provided. Wrap content in PyrycodeMobileTheme.")
    }

val ColorScheme.composerFieldContainer: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalComposerFieldContainer.current
