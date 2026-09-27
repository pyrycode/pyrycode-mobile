package de.pyryco.mobile.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Figma Schemes/On Primary Fixed. Scoped to bubbles, leaving Material's global roles intact.
internal val assistantBubbleContainerDark = Color(0xFF001D34)

internal val LocalUserBubbleContainer =
    staticCompositionLocalOf<Color> {
        error("User bubble colour not provided. Wrap content in PyrycodeMobileTheme.")
    }

internal val LocalAssistantBubbleContainer =
    staticCompositionLocalOf<Color> {
        error("Assistant bubble colour not provided. Wrap content in PyrycodeMobileTheme.")
    }

val ColorScheme.userBubbleContainer: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalUserBubbleContainer.current

val ColorScheme.assistantBubbleContainer: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalAssistantBubbleContainer.current
