package de.pyryco.mobile.ui.conversations.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionState

/** The temporary connection reading in the composer's status row. Offline uses the retry pill instead. */
@Composable
fun ConnectionStatusIndicator(
    state: ConnectionState,
    modifier: Modifier = Modifier,
) {
    val label =
        when (state) {
            ConnectionState.Connecting -> stringResource(R.string.thread_connection_connecting)
            is ConnectionState.Reconnecting -> stringResource(R.string.thread_connection_reconnecting, state.secondsRemaining)
            ConnectionState.Connected, ConnectionState.Offline -> return
        }
    Text(
        text = label,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
