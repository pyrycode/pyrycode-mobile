package de.pyryco.mobile.ui.onboarding

import android.content.res.Configuration
import android.graphics.BlurMaskFilter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
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
    cameraPreview: @Composable () -> Unit = {},
) {
    when (state) {
        ScannerUiState.PermissionRequesting,
        ScannerUiState.ReadyToScan,
        is ScannerUiState.Decoded,
        ->
            ScannerViewport(
                onNavigateBack = onNavigateBack,
                onPasteCode = onPasteCode,
                cameraPreview = cameraPreview,
                modifier = modifier,
            )
        ScannerUiState.Denied ->
            ScannerDeniedScreen(
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
        is ScannerUiState.AwaitingConfirm ->
            PairingConfirmContent(
                fingerprint = state.fingerprint,
                onConfirm = onConfirmPairing,
                onDecline = onDeclinePairing,
                modifier = modifier,
            )
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
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onNavigateBack, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
                Text(
                    text = "Pairing",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 24.dp).testTag("scanner_divider"),
                color = MaterialTheme.colorScheme.inversePrimary.copy(alpha = 0.6f),
            )
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest),
            ) {
                // Back-most layer: the live camera feed (route injects it for ReadyToScan; renders
                // nothing otherwise). The atmosphere/reticle/hint overlay below composites over it.
                cameraPreview()
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

// The pairing security checkpoint (#343). A full-screen, stateless M3 surface — a peer of the
// Error/Denied branches above — that renders the server's static-key fingerprint and gates the
// persist behind an explicit confirm. Deliberately clean and non-decorative: the confirm-pairing
// view is not yet drawn in the locked Figma file (design-later), so no atmospheric styling is
// invented here; a visual-fidelity retrofit lands when the design exists. The token never reaches
// this composable — it receives only the public-key [fingerprint] string + two callbacks.
@Composable
private fun PairingConfirmContent(
    fingerprint: String,
    onConfirm: () -> Unit,
    onDecline: () -> Unit,
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
                text = "Confirm the server fingerprint",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text =
                    "Check this matches the Static-key fp: line that pyry pair shows on your " +
                        "other device before you pair.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(24.dp))
            // Rendered verbatim — already the #342 colon-lowercase-hex form; never uppercased,
            // regrouped, or stripped. Selectable + long-press copy via SelectionContainer; the
            // content description lets TalkBack announce it (AC #4, #5).
            SelectionContainer {
                Text(
                    text = fingerprint,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier =
                        Modifier.semantics {
                            contentDescription = "Server fingerprint $fingerprint"
                        },
                )
            }
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = onConfirm,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
            ) {
                Text(text = "Confirm pairing")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onDecline,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
            ) {
                Text(text = "Don't pair")
            }
        }
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

@Preview(name = "Confirm", showBackground = true, widthDp = 412, heightDp = 892)
@Composable
private fun PairingConfirmPreview() {
    PyrycodeMobileTheme {
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
