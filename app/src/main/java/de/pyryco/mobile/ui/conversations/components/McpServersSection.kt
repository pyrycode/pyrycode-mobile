package de.pyryco.mobile.ui.conversations.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.data.repository.McpServerStatus
import de.pyryco.mobile.data.repository.McpStatus

// The daemon's own servers. Client-owned constants: a Claude-authored name is compared against them for the
// display filter only, never for any behaviour (desktop's MCP_BUILT_IN_SERVER_NAMES).
private val BUILT_IN_SERVER_NAMES = setOf("pyry_approve", "pyry_files")

private const val DISPLAY_BOUND = 256

/**
 * Bound Claude-authored MCP text (#1344) to its first 256 Unicode code points, appending "…" only when it was
 * longer. Counts code points, so a surrogate pair is never split. Desktop's `boundMcpText`.
 */
internal fun boundMcpText(text: String): String {
    if (text.codePointCount(0, text.length) <= DISPLAY_BOUND) return text
    return text.substring(0, text.offsetByCodePoints(0, DISPLAY_BOUND)) + "…"
}

/**
 * The body of Channel info's MCP servers section (#1344), desktop's `McpServersSectionView`. A pure function
 * of [status] plus the Show built-in tick, which is local and starts off each time the sheet composes it.
 *
 * Every server string is Claude-authored and unsanitized: it is rendered only as bounded inert [Text] (and as
 * the bounded accessibility label of its row's switch), never used as a key, a tag or a log value. While a
 * reconnect or a toggle is outstanding, every Reconnect button and switch is disabled.
 */
@Composable
internal fun McpServersSection(
    status: McpStatus,
    onReconnect: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
) {
    var showBuiltIn by rememberSaveable { mutableStateOf(false) }
    val busy = status.reconnecting || status.toggling
    Column(modifier = Modifier.fillMaxWidth()) {
        val report = status.report
        if (report == null) {
            McpNotice("No MCP report has arrived yet.")
        } else {
            ShowBuiltInRow(checked = showBuiltIn, onCheckedChange = { showBuiltIn = it })
            val shown = if (showBuiltIn) report.servers else report.servers.filterNot { it.name in BUILT_IN_SERVER_NAMES }
            shown.forEach { server ->
                McpServerRow(server = server, enabled = !busy, onReconnect = onReconnect, onToggle = onToggle)
            }
            if (shown.isEmpty()) {
                McpNotice(if (report.servers.isEmpty()) "Claude reported no MCP servers." else "Only built-in servers are reported.")
            }
            if (report.droppedServers > 0) {
                McpNotice("Partial list: ${report.droppedServers} more servers were left out by the daemon.")
            }
        }
        // Client-owned copy: a refusal carries no daemon text, so none can reach these lines.
        if (status.unavailable) McpNotice("The daemon could not report MCP status right now.")
        if (status.reconnectRefused) McpNotice("The daemon refused to reconnect the MCP server.")
        if (status.toggleRefused) McpNotice("The daemon refused to change the MCP server.")
    }
}

@Composable
private fun ShowBuiltInRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Show built-in",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = "Show built-in" },
        )
    }
}

@Composable
private fun McpServerRow(
    server: McpServerStatus,
    enabled: Boolean,
    onReconnect: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
) {
    val name = boundMcpText(server.name)
    // Off exactly when Claude reports `disabled`; any other word is on. The switch reads only the report, never
    // a requested state, so it cannot show a state the daemon never entered.
    val on = server.status != "disabled"
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = boundMcpText(server.status),
                modifier = Modifier.weight(1f).padding(start = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
        }
        if (server.error.isNotEmpty()) {
            Text(
                text = boundMcpText(server.error),
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (server.status != "connected") {
                FilledTonalButton(onClick = { onReconnect(server.name) }, enabled = enabled) {
                    Text(text = "Reconnect", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
            Switch(
                checked = on,
                onCheckedChange = { onToggle(server.name, !on) },
                enabled = enabled,
                // The switch is labelled by the row's rendered name, as desktop's aria-labelledby.
                modifier = Modifier.semantics { contentDescription = name },
            )
        }
    }
}

@Composable
private fun McpNotice(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
