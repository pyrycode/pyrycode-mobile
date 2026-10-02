package de.pyryco.mobile.ui.onboarding

import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb

/** The onboarding frames' radial glow (Welcome `6:32`, Pair code `533:2147`), scaled from 412×892 to the drawn size. */
@Composable
internal fun Modifier.onboardingGlow(): Modifier {
    val glowColor = MaterialTheme.colorScheme.primaryContainer
    val glowEdge = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0f)
    return drawWithCache {
        val xScale = size.width / 412f
        val yScale = size.height / 892f
        // The Figma radial transform retains both the ellipse and its tilt.
        val shader =
            RadialGradient(
                0f,
                0f,
                10f,
                intArrayOf(glowColor.toArgb(), glowEdge.toArgb()),
                floatArrayOf(0f, 0.76012f),
                Shader.TileMode.CLAMP,
            ).apply {
                setLocalMatrix(
                    android.graphics.Matrix().apply {
                        setValues(
                            floatArrayOf(
                                43.8f * xScale,
                                13.523f * xScale,
                                196f * xScale,
                                -11.95f * yScale,
                                49.567f * yScale,
                                265f * yScale,
                                0f,
                                0f,
                                1f,
                            ),
                        )
                    },
                )
            }
        val brush = ShaderBrush(shader)
        onDrawBehind { drawRect(brush) }
    }
}
