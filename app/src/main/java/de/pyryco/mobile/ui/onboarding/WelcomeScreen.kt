package de.pyryco.mobile.ui.onboarding

import android.content.res.Configuration
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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

@Composable
fun WelcomeScreen(
    onPaired: () -> Unit,
    onSetup: () -> Unit,
) {
    val glowColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .drawBehind {
                    drawRect(
                        brush =
                            Brush.radialGradient(
                                colors = listOf(glowColor, Color.Transparent),
                                center = Offset(size.width * 0.48f, size.height * 0.30f),
                                radius = size.height * 0.53f,
                            ),
                    )
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
                modifier = Modifier.padding(top = 136.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_pyry_logo),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(104.dp),
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
