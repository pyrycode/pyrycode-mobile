package de.pyryco.mobile.ui.onboarding

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

@Composable
fun ScannerDeniedScreen(
    onOpenSettings: () -> Unit,
    onPasteCode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 32.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(32.dp))
            DeniedCameraIllustration(modifier = Modifier.size(120.dp))
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = "Camera permission required",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Pyrycode needs the camera to read the QR code from your server. " +
                    "You can also paste the pairing code instead.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 300.dp),
            )
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = "Open settings")
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = onPasteCode,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = "Paste code instead")
            }
        }
    }
}

@Composable
private fun DeniedCameraIllustration(modifier: Modifier = Modifier) {
    val outlineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val strikeColor = MaterialTheme.colorScheme.error
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokePx = 2.dp.toPx()
        val stroke = Stroke(width = strokePx, cap = StrokeCap.Round)

        // Camera body — rounded rect, centered vertically.
        val bodyLeft = w * 0.10f
        val bodyTop = h * 0.30f
        val bodyWidth = w * 0.80f
        val bodyHeight = h * 0.45f
        drawRoundRect(
            color = outlineColor,
            topLeft = Offset(bodyLeft, bodyTop),
            size = Size(bodyWidth, bodyHeight),
            cornerRadius = CornerRadius(8.dp.toPx()),
            style = stroke,
        )

        // Viewfinder bridge — small trapezoid (narrower top, wider base) on top of camera body.
        val bridgeTopY = h * 0.22f
        val bridgeBottomY = bodyTop
        val bridgeTopHalfWidth = w * 0.10f
        val bridgeBottomHalfWidth = bridgeTopHalfWidth + (bridgeBottomY - bridgeTopY)
        val cx = w / 2f
        val bridge = Path().apply {
            moveTo(cx - bridgeBottomHalfWidth, bridgeBottomY)
            lineTo(cx - bridgeTopHalfWidth, bridgeTopY)
            lineTo(cx + bridgeTopHalfWidth, bridgeTopY)
            lineTo(cx + bridgeBottomHalfWidth, bridgeBottomY)
        }
        drawPath(path = bridge, color = outlineColor, style = stroke)

        // Lens — outer stroked circle + inner filled dot, centered on the camera body.
        val lensCenter = Offset(cx, bodyTop + bodyHeight / 2f)
        drawCircle(
            color = outlineColor,
            radius = w * 0.13f,
            center = lensCenter,
            style = stroke,
        )
        drawCircle(
            color = outlineColor,
            radius = w * 0.045f,
            center = lensCenter,
        )

        // Strike line — diagonal in error color, top-right to bottom-left, crossing the lens.
        drawLine(
            color = strikeColor,
            start = Offset(w * 0.82f, h * 0.18f),
            end = Offset(w * 0.18f, h * 0.86f),
            strokeWidth = strokePx,
            cap = StrokeCap.Round,
        )
    }
}

@Preview(name = "Light", showBackground = true, widthDp = 412, heightDp = 892)
@Composable
private fun ScannerDeniedScreenLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        ScannerDeniedScreen(onOpenSettings = {}, onPasteCode = {})
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
private fun ScannerDeniedScreenDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        ScannerDeniedScreen(onOpenSettings = {}, onPasteCode = {})
    }
}
