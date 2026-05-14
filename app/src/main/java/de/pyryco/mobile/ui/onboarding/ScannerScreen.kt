package de.pyryco.mobile.ui.onboarding

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Phase 1.5: every interactive element fires onTap (AC6)
    val blueStop = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val coralStop = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.06f)
    val stripeColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier =
            modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onTap() })
                },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
        ) {
            TopAppBar(
                title = {
                    Text(
                        text = "Pair with pyrycode",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onTap) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
            )
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                        .drawBehind {
                            val radius = maxOf(size.width, size.height) * 0.7f
                            drawRect(
                                brush =
                                    Brush.radialGradient(
                                        0f to blueStop,
                                        0.6f to blueStop.copy(alpha = 0f),
                                        1f to Color.Transparent,
                                        center = Offset(size.width * 0.30f, size.height * 0.40f),
                                        radius = radius,
                                    ),
                            )
                            drawRect(
                                brush =
                                    Brush.radialGradient(
                                        0f to coralStop,
                                        0.6f to coralStop.copy(alpha = 0f),
                                        1f to Color.Transparent,
                                        center = Offset(size.width * 0.70f, size.height * 0.70f),
                                        radius = radius,
                                    ),
                            )
                        },
            ) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    val spacing = 7.dp.toPx()
                    val thickness = 1.dp.toPx()
                    var y = 0f
                    while (y <= size.height) {
                        drawRect(
                            color = stripeColor,
                            topLeft = Offset(0f, y),
                            size = Size(size.width, thickness),
                        )
                        y += spacing
                    }
                }
                Reticle(modifier = Modifier.align(Alignment.Center))
                HintCard(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp)
                            .fillMaxWidth(),
                )
            }
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                TextButton(onClick = onTap) {
                    Text(
                        text = "Trouble scanning? Paste the pairing code instead",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun Reticle(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val glow = primary.copy(alpha = 0.6f)
    Box(modifier = modifier.size(248.dp)) {
        Corner(modifier = Modifier.align(Alignment.TopStart), alignment = Alignment.TopStart, color = primary)
        Corner(modifier = Modifier.align(Alignment.TopEnd), alignment = Alignment.TopEnd, color = primary)
        Corner(modifier = Modifier.align(Alignment.BottomStart), alignment = Alignment.BottomStart, color = primary)
        Corner(modifier = Modifier.align(Alignment.BottomEnd), alignment = Alignment.BottomEnd, color = primary)
        Box(
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 8.dp)
                    .fillMaxWidth()
                    .height(6.dp)
                    .blur(radius = 12.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                    .background(glow),
        )
        Box(
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 8.dp)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(primary),
        )
    }
}

@Composable
private fun Corner(
    alignment: Alignment,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.size(28.dp)) {
        Box(
            modifier =
                Modifier
                    .align(alignment)
                    .size(width = 28.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
        )
        Box(
            modifier =
                Modifier
                    .align(alignment)
                    .size(width = 4.dp, height = 28.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
        )
    }
}

@Composable
private fun HintCard(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.72f))
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text =
                buildAnnotatedString {
                    append("Run ")
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.tertiary,
                        ),
                    ) {
                        append("pyry pair")
                    }
                    append(" on your pyrycode server to generate a QR code.")
                },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
        )
    }
}

@Preview(name = "Light", showBackground = true, widthDp = 412, heightDp = 892)
@Composable
private fun ScannerScreenLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        ScannerScreen(onTap = {})
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
private fun ScannerScreenDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        ScannerScreen(onTap = {})
    }
}
