package de.pyryco.mobile.ui.onboarding

import android.content.res.Configuration
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

@Composable
fun WelcomeScreen(
    onPaired: () -> Unit,
    onSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val glowColor = MaterialTheme.colorScheme.primaryContainer
    val glowEdge = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0f)
    val logo = ImageVector.vectorResource(R.drawable.ic_pyry_logo)
    val logoShape =
        remember(logo) {
            // The existing asset is one filled path; derive its silhouette without copying geometry.
            val path = PathParser().addPathNodes((logo.root[0] as VectorPath).pathData).toPath()
            GenericShape { size, _ ->
                addPath(path)
                transform(Matrix().apply { scale(size.width / logo.viewportWidth, size.height / logo.viewportHeight) })
            }
        }
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .drawWithCache {
                    val xScale = size.width / 412f
                    val yScale = size.height / 892f
                    // Welcome's Figma radial transform retains both the ellipse and its tilt.
                    val shader =
                        RadialGradient(
                            0f,
                            0f,
                            10f,
                            intArrayOf(glowColor.toArgb(), glowEdge.toArgb()),
                            floatArrayOf(0f, 0.7f),
                            Shader.TileMode.CLAMP,
                        ).apply {
                            setLocalMatrix(
                                android.graphics.Matrix().apply {
                                    setValues(
                                        floatArrayOf(
                                            28.4f * xScale,
                                            4.3038f * xScale,
                                            196f * xScale,
                                            -2.6f * yScale,
                                            47.011f * yScale,
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
                },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.Start,
        ) {
            Column(
                modifier = Modifier.padding(top = 168.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_pyry_logo),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier =
                        Modifier
                            .dropShadow(
                                shape = logoShape,
                                shadow =
                                    Shadow(
                                        radius = 8.dp,
                                        spread = 3.dp,
                                        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.15f),
                                        offset = DpOffset(0.dp, 4.dp),
                                    ),
                            ).dropShadow(
                                shape = logoShape,
                                shadow =
                                    Shadow(
                                        radius = 3.dp,
                                        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.30f),
                                        offset = DpOffset(0.dp, 1.dp),
                                    ),
                            ).size(width = 92.dp, height = 104.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Pyrycode Mobile",
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Control Claude sessions on your phone.",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text =
                        "Pyrycode runs Claude on your computer or home server. " +
                            "Channels and conversation history live on your machine, " +
                            "accessible from any device.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(320.dp),
                )
            }

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onPaired,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_qr_scan_frame),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "I already have pyrycode")
                }
                TextButton(
                    onClick = onSetup,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                ) {
                    Text(text = "Set up pyrycode first")
                }
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Open source · github.com/pyrycode/pyrycode-mobile",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    )
                }
            }
        }
    }
}

@Preview(name = "Light", showBackground = true, widthDp = 412, heightDp = 892)
@Composable
private fun WelcomeScreenLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        WelcomeScreen(onPaired = {}, onSetup = {})
    }
}

@Preview(
    name = "Dark",
    showBackground = true,
    widthDp = 412,
    heightDp = 892,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun WelcomeScreenDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        WelcomeScreen(onPaired = {}, onSetup = {})
    }
}
