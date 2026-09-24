package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import de.pyryco.mobile.ui.theme.success
import de.pyryco.mobile.ui.theme.warning

/**
 * The semantic colour category a connection leg resolves to. The category is the load-bearing
 * product behaviour; the concrete M3 token is resolved separately in [color] so the mapping stays
 * pure (JVM-unit-testable, no Compose import).
 */
internal enum class ConnectionLegCategory { Up, InProgress, Down }

/**
 * The full presentation triple for one connection leg: a colour [category], a visible textual
 * [label] (so status is legible without colour perception), and a [contentDescription] combining
 * leg + state for TalkBack.
 */
internal data class ConnectionLegVisual(
    val category: ConnectionLegCategory,
    val label: String,
    val contentDescription: String,
)

/**
 * Maps the relay leg ([RelayLinkStatus]) to its presentation triple. [RelayLinkStatus.DaemonAbsent]
 * resolves to [ConnectionLegCategory.Up] (AC#2): the relay is reachable — the missing daemon is the
 * pyrycode leg's story, not a relay failure. A distinct "Reachable" label keeps the text/a11y
 * channel honest while staying green.
 */
internal fun RelayLinkStatus.toLegVisual(): ConnectionLegVisual =
    when (this) {
        RelayLinkStatus.Connected ->
            ConnectionLegVisual(ConnectionLegCategory.Up, "Connected", "Relay: connected")
        RelayLinkStatus.Connecting ->
            ConnectionLegVisual(ConnectionLegCategory.InProgress, "Connecting…", "Relay: connecting")
        is RelayLinkStatus.Reconnecting ->
            ConnectionLegVisual(ConnectionLegCategory.InProgress, "Reconnecting", "Relay: reconnecting")
        RelayLinkStatus.DaemonAbsent ->
            ConnectionLegVisual(ConnectionLegCategory.Up, "Reachable", "Relay: reachable, no daemon")
        RelayLinkStatus.PairingRejected ->
            ConnectionLegVisual(ConnectionLegCategory.Down, "Pairing rejected", "Relay: pairing rejected")
        is RelayLinkStatus.UpdateRequired ->
            ConnectionLegVisual(ConnectionLegCategory.Down, "Update required", "Relay: update required")
        RelayLinkStatus.Offline ->
            ConnectionLegVisual(ConnectionLegCategory.Down, "Offline", "Relay: offline")
        RelayLinkStatus.Idle ->
            ConnectionLegVisual(ConnectionLegCategory.Down, "Not connected", "Relay: not connected")
    }

/** Maps the pyrycode leg ([PyrycodeLinkStatus]) to its presentation triple. */
internal fun PyrycodeLinkStatus.toLegVisual(): ConnectionLegVisual =
    when (this) {
        PyrycodeLinkStatus.Handshaking ->
            ConnectionLegVisual(ConnectionLegCategory.InProgress, "Handshaking…", "Pyrycode: handshaking")
        PyrycodeLinkStatus.Connected ->
            ConnectionLegVisual(ConnectionLegCategory.Up, "Connected", "Pyrycode: connected")
        PyrycodeLinkStatus.Down ->
            ConnectionLegVisual(ConnectionLegCategory.Down, "Down", "Pyrycode: down")
    }

/** Resolves a category to its M3 semantic colour token — the only colour-resolution site (AC#3). */
@Composable
@ReadOnlyComposable
internal fun ConnectionLegCategory.color(): Color =
    when (this) {
        ConnectionLegCategory.Up -> MaterialTheme.colorScheme.success
        ConnectionLegCategory.InProgress -> MaterialTheme.colorScheme.warning
        ConnectionLegCategory.Down -> MaterialTheme.colorScheme.error
    }

private val DotSize = 8.dp
private val LegSpacing = 24.dp
private val IntraLegSpacing = 6.dp

/**
 * A stateless two-part connection-status line — `● Relay   ● Pyrycode` — whose dots colour by leg
 * state. Each leg shows its name and a textual state label, so status is conveyed without relying on
 * colour perception. The live [ConnectionStatus] flow is sourced by the consumer (#398); this
 * component is pure presentation.
 */
@Composable
fun ConnectionStatusLine(
    status: ConnectionStatus,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(LegSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusLeg(name = "Relay", visual = status.relay.toLegVisual())
        StatusLeg(name = "Pyrycode", visual = status.pyrycode.toLegVisual())
    }
}

@Composable
private fun StatusLeg(
    name: String,
    visual: ConnectionLegVisual,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier.clearAndSetSemantics { contentDescription = visual.contentDescription },
        horizontalArrangement = Arrangement.spacedBy(IntraLegSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(DotSize)
                    .background(visual.category.color(), CircleShape),
        )
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = visual.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConnectionStatusLinePreviewMatrix() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(16.dp)) {
        ConnectionStatusLine(
            ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected),
        )
        ConnectionStatusLine(
            ConnectionStatus(RelayLinkStatus.DaemonAbsent, PyrycodeLinkStatus.Down),
        )
        ConnectionStatusLine(
            ConnectionStatus(RelayLinkStatus.Offline, PyrycodeLinkStatus.Down),
        )
        ConnectionStatusLine(
            ConnectionStatus(
                RelayLinkStatus.Reconnecting(secondsRemaining = 12),
                PyrycodeLinkStatus.Handshaking,
            ),
        )
    }
}

@Preview(name = "ConnectionStatusLine — Light", showBackground = true, widthDp = 412)
@Composable
private fun ConnectionStatusLineLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface {
            ConnectionStatusLinePreviewMatrix()
        }
    }
}

@Preview(
    name = "ConnectionStatusLine — Dark",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ConnectionStatusLineDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        Surface {
            ConnectionStatusLinePreviewMatrix()
        }
    }
}
