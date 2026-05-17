package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

@Composable
fun WorkspaceChip(
    workspaceLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AssistChip(
        onClick = onClick,
        label = { Text("Workspace: $workspaceLabel (change)") },
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
