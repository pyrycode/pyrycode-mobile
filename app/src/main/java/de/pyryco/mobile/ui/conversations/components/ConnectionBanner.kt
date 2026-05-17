package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

private val BannerVerticalPadding = 12.dp
private val BannerHorizontalPadding = 16.dp

@Composable
fun ConnectionBanner(
    state: ConnectionState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text: String
    val containerColor: Color
    val contentColor: Color
    val onClick: () -> Unit
    when (state) {
        ConnectionState.Connected -> return
        ConnectionState.Connecting -> {
            text = "Connecting…"
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            contentColor = MaterialTheme.colorScheme.onSurface
            onClick = {}
        }
        is ConnectionState.Reconnecting -> {
            text = "Reconnecting in ${state.secondsRemaining}s"
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            contentColor = MaterialTheme.colorScheme.onSurface
            onClick = {}
        }
        ConnectionState.Offline -> {
            text = "Offline — tap to retry"
            containerColor = MaterialTheme.colorScheme.errorContainer
            contentColor = MaterialTheme.colorScheme.onErrorContainer
            onClick = onRetry
        }
    }
    ConnectionBannerContent(
        text = text,
        containerColor = containerColor,
        contentColor = contentColor,
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun ConnectionBannerContent(
    text: String,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = text,
            modifier =
                Modifier.padding(
                    horizontal = BannerHorizontalPadding,
                    vertical = BannerVerticalPadding,
                ),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ConnectionBannerPreviewMatrix() {
    Column {
        ConnectionBanner(state = ConnectionState.Connected, onRetry = {})
        ConnectionBanner(state = ConnectionState.Connecting, onRetry = {})
        ConnectionBanner(state = ConnectionState.Reconnecting(secondsRemaining = 12), onRetry = {})
        ConnectionBanner(state = ConnectionState.Offline, onRetry = {})
    }
}

@Preview(name = "ConnectionBanner — Light", showBackground = true, widthDp = 412)
@Composable
private fun ConnectionBannerLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface {
            ConnectionBannerPreviewMatrix()
        }
    }
}

@Preview(
    name = "ConnectionBanner — Dark",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ConnectionBannerDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        Surface {
            ConnectionBannerPreviewMatrix()
        }
    }
}
