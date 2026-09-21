package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import de.pyryco.mobile.ui.workspace.MAX_WORKSPACE_LABEL_CHARS

@Composable
fun WorkspaceChip(
    workspaceLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AssistChip(
        onClick = onClick,
        // Plain Text, never MarkdownText and never a WebView: [workspaceLabel] can carry opaque
        // daemon-authored text, which must render as text. maxLines keeps a long label from growing
        // the chip's height; the ellipsis keeps it from being clipped at the chip's trailing edge.
        label = {
            Text(
                text = "Workspace: $workspaceLabel (change)",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Outlined.Folder,
                contentDescription = null,
            )
        },
        modifier = modifier,
    )
}

@Preview(name = "WorkspaceChip — Light", showBackground = true, widthDp = 412)
@Composable
private fun WorkspaceChipLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface {
            WorkspaceChip(
                workspaceLabel = "scratch",
                onClick = {},
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Preview(
    name = "WorkspaceChip — Dark",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun WorkspaceChipDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        Surface {
            WorkspaceChip(
                workspaceLabel = "my-app",
                onClick = {},
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * A label at the daemon's maximum stored length, rendered through the production modifier chain.
 *
 * `ThreadScreen` always passes `fillMaxWidth()`, and only under that constraint does the chip show
 * its bounded behaviour: one line at the ordinary chip height, truncated with an ellipsis rather
 * than growing or clipping. The two previews above wrap their content and would not demonstrate it.
 */
@Preview(name = "WorkspaceChip — Long label", showBackground = true, widthDp = 412)
@Composable
private fun WorkspaceChipLongLabelPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface {
            WorkspaceChip(
                workspaceLabel = "Design system refresh ".repeat(7).take(MAX_WORKSPACE_LABEL_CHARS),
                onClick = {},
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}
