package de.pyryco.mobile.ui.onboarding

import android.content.res.Configuration
import android.graphics.BlurMaskFilter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.ui.components.MobileModal
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

@Composable
fun ScannerScreen(
    state: ScannerUiState,
    onNavigateBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onPasteCode: () -> Unit,
    modifier: Modifier = Modifier,
    onConfirmPairing: () -> Unit = {},
    onDeclinePairing: () -> Unit = {},
    onRetryPairing: () -> Unit = {},
    onCancelPairing: () -> Unit = {},
    cameraPreview: @Composable () -> Unit = {},
) {
    when (state) {
        ScannerUiState.PermissionRequesting,
        ScannerUiState.ReadyToScan,
        is ScannerUiState.Decoded,
        // Shown only for the frame before the route navigates; the camera stays gated on ReadyToScan.
        ScannerUiState.Paired,
        ScannerUiState.Cancelled,
        ->
            ScannerViewport(
                onNavigateBack = onNavigateBack,
                onPasteCode = onPasteCode,
                cameraPreview = cameraPreview,
                modifier = modifier,
            )
        ScannerUiState.Denied ->
            ScannerDeniedScreen(
                onNavigateBack = onNavigateBack,
                onOpenSettings = onOpenSettings,
                onPasteCode = onPasteCode,
                modifier = modifier,
            )
        is ScannerUiState.Error ->
            ScannerErrorContent(
                message = state.message,
                onPasteCode = onPasteCode,
                modifier = modifier,
            )
        // #1386: one branch, so the same modal window stays up through confirm, the wait and a failure.
        is ScannerUiState.AwaitingConfirm,
        is ScannerUiState.Verifying,
        is ScannerUiState.VerificationFailed,
        -> {
            val failed = state as? ScannerUiState.VerificationFailed
            PairingConfirmContent(
                fingerprint =
                    when (state) {
                        is ScannerUiState.AwaitingConfirm -> state.fingerprint
                        is ScannerUiState.Verifying -> state.fingerprint
                        else -> failed?.fingerprint.orEmpty()
                    },
                onConfirm =
                    when (state) {
                        is ScannerUiState.AwaitingConfirm -> onConfirmPairing
                        is ScannerUiState.VerificationFailed -> onRetryPairing
                        else -> ({})
                    },
                onDecline = if (state is ScannerUiState.AwaitingConfirm) onDeclinePairing else onCancelPairing,
                modifier = modifier,
                cancelLabel = if (state is ScannerUiState.AwaitingConfirm) "Don't pair" else "Cancel",
                submitLabel =
                    when {
                        failed == null -> "Confirm pairing"
                        failed.retryable -> "Retry"
                        else -> null
                    },
                loading = state is ScannerUiState.Verifying,
                error = failed?.message,
            )
        }
    }
}

@Composable
private fun ScannerViewport(
    onNavigateBack: () -> Unit,
    onPasteCode: () -> Unit,
    cameraPreview: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val blueStop = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val coralStop = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.06f)
    val stripeColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
    val colors = MaterialTheme.colorScheme
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .scannerAtmosphere(colors.primaryContainer, colors.surfaceContainerLowest, colors.surface)
                    .systemBarsPadding(),
        ) {
            PairingHeader(
                title = "Pairing",
                titleColor = colors.onPrimaryContainer,
                onBack = onNavigateBack,
                backIcon = rememberVectorPainter(Icons.AutoMirrored.Filled.ArrowBack),
            )
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 24.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest),
            ) {
                // Back-most layer: the live camera feed (route injects it for ReadyToScan; renders
                // nothing otherwise). The atmosphere/reticle/hint overlay below composites over it.
                cameraPreview()
                Box(Modifier.matchParentSize().background(colors.scrim.copy(alpha = 0.6f)))
                // Figma uses elliptical gradients; retain the existing circular atmospheric approximation.
                // Atmosphere gradients, moved off the Box's own drawBehind (which paints behind ALL
                // children incl. the camera) into a matchParentSize child so they layer over the feed.
                Box(
                    modifier =
                        Modifier
                            .matchParentSize()
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
                )
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
                ScannerGuides(Modifier.matchParentSize())
            }
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                TextButton(onClick = onPasteCode, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(
                        text = "Trouble scanning? Paste the pairing code instead",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

internal fun Modifier.scannerAtmosphere(
    center: Color,
    shadow: Color,
    edge: Color,
): Modifier =
    drawBehind {
        drawRect(edge)
        drawRect(
            brush =
                Brush.radialGradient(
                    0f to center,
                    0.7f to lerp(center, shadow, 0.9f),
                    0.76f to edge,
                    1f to edge,
                    center = Offset(size.width * 0.476f, size.height * 0.297f),
                    radius = maxOf(size.width, size.height) * 0.56f,
                ),
        )
    }

// Measure the helper first: preserve Figma's centered reticle when it fits, then move it
// upward (and only shrink if necessary) to keep a 16 dp gap on compact windows.
@Composable
private fun ScannerGuides(modifier: Modifier = Modifier) {
    Layout(
        modifier = modifier,
        content = {
            HintCard(Modifier.padding(16.dp).fillMaxWidth())
            Reticle(Modifier.testTag("scanner_reticle"))
        },
    ) { measurables, constraints ->
        val hint = measurables[0].measure(constraints.copy(minWidth = 0, minHeight = 0))
        val side = minOf(248.dp.roundToPx(), constraints.maxWidth, (constraints.maxHeight - hint.height).coerceAtLeast(0))
        val reticle = measurables[1].measure(Constraints.fixed(side, side))
        layout(constraints.maxWidth, constraints.maxHeight) {
            hint.placeRelative(0, constraints.maxHeight - hint.height)
            reticle.placeRelative(
                (constraints.maxWidth - side) / 2,
                minOf((constraints.maxHeight - side) / 2, constraints.maxHeight - hint.height - side),
            )
        }
    }
}

@Composable
private fun Reticle(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val shadowArgb = primary.copy(alpha = 0.6f).toArgb()
    Box(modifier = modifier.size(248.dp)) {
        Corner(modifier = Modifier.align(Alignment.TopStart), alignment = Alignment.TopStart, color = primary)
        Corner(modifier = Modifier.align(Alignment.TopEnd), alignment = Alignment.TopEnd, color = primary)
        Corner(modifier = Modifier.align(Alignment.BottomStart), alignment = Alignment.BottomStart, color = primary)
        Corner(modifier = Modifier.align(Alignment.BottomEnd), alignment = Alignment.BottomEnd, color = primary)
        Canvas(
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 8.dp)
                    .fillMaxWidth()
                    .height(26.dp),
        ) {
            val lineHeightPx = 2.dp.toPx()
            val lineTop = (size.height - lineHeightPx) / 2f
            val shadowPaint =
                Paint().apply {
                    isAntiAlias = true
                    asFrameworkPaint().apply {
                        color = shadowArgb
                        maskFilter = BlurMaskFilter(12.dp.toPx(), BlurMaskFilter.Blur.NORMAL)
                    }
                }
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawRect(
                    0f,
                    lineTop,
                    size.width,
                    lineTop + lineHeightPx,
                    shadowPaint.asFrameworkPaint(),
                )
            }
            drawRect(
                color = primary,
                topLeft = Offset(0f, lineTop),
                size = Size(size.width, lineHeightPx),
            )
        }
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
                .testTag("scanner_hint")
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
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

// Minimal recovery surface for the Error state (camera-engine slice drives it; no Figma exists for
// this state). The "paste" affordance keeps onboarding completable.
@Composable
private fun ScannerErrorContent(
    message: String,
    onPasteCode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(onClick = onPasteCode) {
                Text(text = "Paste the pairing code instead")
            }
        }
    }
}

// The pairing security checkpoint receives only the public fingerprint and decision callbacks.
// The pending server stays in ScannerViewModel state so the displayed fingerprint and saved record stay bound.
@Composable
private fun PairingConfirmContent(
    fingerprint: String,
    onConfirm: () -> Unit,
    onDecline: () -> Unit,
    cancelLabel: String,
    submitLabel: String?,
    loading: Boolean,
    error: String?,
    modifier: Modifier = Modifier,
) {
    MobileModal(
        title = "Pair",
        onDismissRequest = onDecline,
        onSubmit = onConfirm,
        modifier = modifier,
        loading = loading,
        error = error,
        cancelLabel = cancelLabel,
        submitLabel = submitLabel,
    ) {
        // The desktop's fingerprint is colon-hex, not the older Figma mock code. Keep it verbatim.
        SelectionContainer {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = fingerprint,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Server fingerprint $fingerprint" },
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Text(
            text = "Verify that this fingerprint matches the Static-key fp: line shown by pyry pair on your other device before you pair.",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview(name = "Light", showBackground = true, widthDp = 412, heightDp = 892)
@Composable
private fun ScannerScreenLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        ScannerScreen(
            state = ScannerUiState.ReadyToScan,
            onNavigateBack = {},
            onOpenSettings = {},
            onPasteCode = {},
        )
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
        ScannerScreen(
            state = ScannerUiState.ReadyToScan,
            onNavigateBack = {},
            onOpenSettings = {},
            onPasteCode = {},
        )
    }
}

@Preview(
    name = "Confirm dark",
    showBackground = true,
    widthDp = 412,
    heightDp = 892,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun PairingConfirmPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        ScannerScreen(
            state =
                ScannerUiState.AwaitingConfirm(
                    fingerprint = "32:0b:5e:a9:9e:65:3b:c2",
                    server =
                        PairedServer(
                            serverId = "srv-1",
                            token = "tok-123",
                            relayUrl = "wss://relay.example.com",
                            serverStaticPublicKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                        ),
                ),
            onNavigateBack = {},
            onOpenSettings = {},
            onPasteCode = {},
            onConfirmPairing = {},
            onDeclinePairing = {},
        )
    }
}
