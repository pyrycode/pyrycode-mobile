package de.pyryco.mobile.ui.settings

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.ui.conversations.components.ConnectionStatusLine
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import de.pyryco.mobile.ui.workspace.MAX_WORKSPACE_LABEL_CHARS

// The Connection section's own status-line inset, carried verbatim so the dots keep the row's left
// edge when the row above them changes shape.
private val StatusLinePadding = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp)

/**
 * One host's identity and its own two-part connection status, in the Connection section's existing
 * row treatment: the host's [name] as the headline over its [serverId] and [relayUrl], with
 * [ConnectionStatusLine] beneath.
 *
 * The four facts are always display text; [onClick] and [trailing] are what the caller adds around
 * them. Both default to absent, which is the inert row #749 shipped when nothing on this screen
 * navigated. Since #750 the caller passes an [onClick] for every host that is not the one whose
 * Settings is open, and the design frame's chevron comes back as that row's [trailing]. The click
 * target deliberately covers the status line as well as the identity, so the affordance is the
 * whole of what it describes.
 *
 * Stateless and per-host rather than per-screen, which is what lets the Connection section repeat
 * it once per saved host instead of re-deriving the treatment.
 *
 * Caller obligations, because this component cannot enforce them from inside:
 * - Pass **display text**. Never a pairing token, a device or server static key, or a raw
 *   fingerprint: this renders what it is handed, so a secret passed in is a secret on screen.
 * - [relayUrl] is display text only. It is never the URL anything opens or dials — the live
 *   endpoint stays the stored record read through the relay supervisor, the same rule
 *   `EditHostModal` states for the identical value.
 * - [name] arrives already resolved ("its server id when unnamed" is `SettingsHostState.Owned.name`),
 *   so every caller of this row agrees on that fallback rather than each deriving its own.
 *
 * All three strings originate in a scanned QR payload or in locally entered metadata, and the
 * pairing parser bounds none of them in length (#752), so each is clamped here before it reaches
 * text layout. `maxLines` bounds only what is painted — Compose still measures the whole string —
 * so the clamp, not the overflow, is what keeps an oversized value a truncation rather than an ANR.
 */
@Composable
fun HostIdentityRow(
    name: String,
    serverId: String,
    relayUrl: String,
    status: ConnectionStatus,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier) {
        ListItem(
            headlineContent = { BoundedLine(name, MaterialTheme.typography.bodyLarge) },
            supportingContent = {
                Column {
                    BoundedLine(serverId, MaterialTheme.typography.bodySmall)
                    BoundedLine(relayUrl, MaterialTheme.typography.bodySmall)
                }
            },
            trailingContent = trailing,
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        )
        ConnectionStatusLine(status = status, modifier = StatusLinePadding)
    }
}

/** The clamp and the paint bound together, so no caller of this file can apply only one of them. */
@Composable
private fun BoundedLine(
    raw: String,
    style: TextStyle,
) {
    Text(
        text = raw.take(MAX_WORKSPACE_LABEL_CHARS),
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun HostIdentityRowPreviewMatrix() {
    Column {
        HostIdentityRow(
            name = "Pyrybox",
            serverId = "pyrybox-2026-0f3a",
            relayUrl = "wss://relay.pyryco.de",
            status = ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected),
        )
        // The unnamed host, where the name line and the id line deliberately read the same.
        HostIdentityRow(
            name = "juhana-mac-2026-8c41",
            serverId = "juhana-mac-2026-8c41",
            relayUrl = "wss://relay.pyryco.de",
            status = ConnectionStatus(RelayLinkStatus.DaemonAbsent, PyrycodeLinkStatus.Down),
        )
    }
}

@Preview(name = "HostIdentityRow — Light", showBackground = true, widthDp = 412)
@Composable
private fun HostIdentityRowLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface { HostIdentityRowPreviewMatrix() }
    }
}

@Preview(
    name = "HostIdentityRow — Dark",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun HostIdentityRowDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        Surface { HostIdentityRowPreviewMatrix() }
    }
}
