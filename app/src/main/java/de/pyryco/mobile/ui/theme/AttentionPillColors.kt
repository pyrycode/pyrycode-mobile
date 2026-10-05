package de.pyryco.mobile.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/** Static dark Figma containers; other palettes tint the theme surface with their semantic colour. */
internal val ColorScheme.attentionWaitingContainer: Color
    @Composable @ReadOnlyComposable
    get() = if (LocalStaticDarkPalette.current) attentionWaitingContainerDark else warning.copy(alpha = .18f).compositeOver(surface)

internal val ColorScheme.attentionFinishedContainer: Color
    @Composable @ReadOnlyComposable
    get() = if (LocalStaticDarkPalette.current) attentionFinishedContainerDark else success.copy(alpha = .18f).compositeOver(surface)
