package de.pyryco.mobile.ui.components

import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.compose.animation.core.LinearEasing
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect

/** Samples a sibling hazeSource; foreground content is drawn sharp after the effect and tint. */
@Composable
internal fun Modifier.chromeBackdrop(
    source: HazeState,
    color: Color,
    top: Boolean,
): Modifier {
    var height by remember { mutableFloatStateOf(0f) }
    return this
        .onSizeChanged { height = it.height.toFloat() }
        .hazeEffect(source) {
            backgroundColor = Color.Transparent
            blurRadius = 10.dp
            noiseFactor = 0f
            tints = emptyList()
            progressive =
                HazeProgressive.verticalGradient(
                    easing = LinearEasing,
                    startIntensity = if (top) 1f else 0f,
                    endIntensity = if (top) 0f else 1f,
                    endY = if (top) Float.POSITIVE_INFINITY else height * 0.2f,
                )
        }.drawWithCache {
            val tint =
                if (top) {
                    Brush.verticalGradient(0f to color, 1f to color.copy(alpha = 0.09f))
                } else {
                    Brush.verticalGradient(0f to color.copy(alpha = 0f), 0.25f to color.copy(alpha = 0.6f), 1f to color.copy(alpha = 0.6f))
                }
            onDrawBehind { drawRect(tint) }
        }.blockTouchesBehindChrome()
}

/** Owns blank chrome pixels too, without adding a meaningless TalkBack action. */
private fun Modifier.blockTouchesBehindChrome(): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                // Children see Main first, so their controls and horizontal attachment scrolling win.
                awaitPointerEvent(PointerEventPass.Main).changes.forEach { it.consume() }
            }
        }
    }

/** Figma Default shadow follows rendered alpha, including text and glyphs, rather than a box outline. */
@Composable
internal fun Modifier.defaultChromeShadow(): Modifier {
    val layer = rememberGraphicsLayer()
    val shadow = MaterialTheme.colorScheme.scrim.copy(alpha = 0.2f)
    return drawWithCache {
        val margin = 10.dp.roundToPx()
        val recordedSize = IntSize(size.width.toInt() + 2 * margin, size.height.toInt() + 2 * margin)
        layer.renderEffect =
            RenderEffect
                .createBlurEffect(
                    5.dp.toPx(),
                    5.dp.toPx(),
                    RenderEffect.createColorFilterEffect(BlendModeColorFilter(shadow.toArgb(), BlendMode.SRC_IN)),
                    Shader.TileMode.DECAL,
                ).asComposeRenderEffect()
        onDrawWithContent {
            // Transparent margins keep the 24dp status band's shadow from clipping at its layer edge.
            layer.record(size = recordedSize) {
                translate(left = margin.toFloat(), top = margin.toFloat()) { this@onDrawWithContent.drawContent() }
            }
            translate(left = -margin.toFloat(), top = 4.dp.toPx() - margin) { drawLayer(layer) }
            drawContent()
        }
    }
}
