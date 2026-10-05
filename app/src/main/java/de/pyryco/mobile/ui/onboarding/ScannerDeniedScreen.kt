package de.pyryco.mobile.ui.onboarding

import android.content.res.Configuration
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.BuildConfig
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

@Composable
fun ScannerDeniedScreen(
    onNavigateBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onPasteCode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxSize(),
    ) {
        val backdropSource = remember { HazeState() }
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.matchParentSize().hazeSource(backdropSource).scannerAtmosphere(
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.surfaceContainerLowest,
                    MaterialTheme.colorScheme.surface,
                ),
            )
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .systemBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PairingHeader(
                    title = "Pair with pyrycode",
                    titleColor = MaterialTheme.colorScheme.onSurface,
                    onBack = { deniedAction("back", onNavigateBack) },
                    backIcon = rememberVectorPainter(Icons.AutoMirrored.Filled.ArrowBack),
                    backdropSource = backdropSource,
                    startPadding = 4.dp,
                    divider = false,
                )
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    // Scrolls once large text no longer fits a small screen, rather than drawing the copy over the
                    // buttons; at the reference size it fits, and the weighted spacer keeps the buttons at the bottom.
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .heightIn(min = maxHeight)
                                .padding(start = 32.dp, end = 32.dp, bottom = 80.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // The frame's illustration sits 132 dp below the bar; the header is 62 dp tall.
                        Spacer(modifier = Modifier.height(70.dp))
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
                            text =
                                "Pyrycode needs the camera to read the QR code from your server. " +
                                    "You can also paste the pairing code instead.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.widthIn(max = 300.dp),
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        // The least space left between the copy and the buttons when the screen has to scroll.
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { deniedAction("settings", onOpenSettings) },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) {
                            Text(text = "Open settings")
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                            TextButton(
                                onClick = { deniedAction("paste", onPasteCode) },
                                modifier = Modifier.fillMaxWidth().height(40.dp),
                            ) {
                                Text(text = "Paste code instead")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeniedCameraIllustration(modifier: Modifier = Modifier) {
    // Node 32:8 exported geometry, split only to bind its two colors to the current theme.
    Box(modifier) {
        Icon(
            painter = painterResource(R.drawable.scanner_denied_outline),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxSize(),
        )
        Icon(
            painter = painterResource(R.drawable.scanner_denied_strike),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun deniedAction(
    action: String,
    callback: () -> Unit,
) {
    if (BuildConfig.DEBUG) Log.d("ScannerDenied", "event=denied_action action=$action")
    callback()
}

@Preview(name = "Light", showBackground = true, widthDp = 412, heightDp = 892)
@Composable
private fun ScannerDeniedScreenLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        ScannerDeniedScreen(onNavigateBack = {}, onOpenSettings = {}, onPasteCode = {})
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
        ScannerDeniedScreen(onNavigateBack = {}, onOpenSettings = {}, onPasteCode = {})
    }
}
