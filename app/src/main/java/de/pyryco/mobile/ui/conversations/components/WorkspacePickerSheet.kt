package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspacePickerSheet(
    recent: List<String>,
    onPick: (String) -> Unit,
    onCreateNew: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
    ) {
        WorkspacePickerSheetContent(
            recent = recent,
            onPick = onPick,
            onCreateNew = onCreateNew,
            onDismiss = onDismiss,
        )
    }
}

@Composable
internal fun WorkspacePickerSheetContent(
    recent: List<String>,
    onPick: (String) -> Unit,
    onCreateNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        TitleRow(onClose = onDismiss)
        if (recent.isNotEmpty()) {
            SectionHeader(text = "Recent")
            recent.forEach { path ->
                RecentRow(path = path, onClick = { onPick(path) })
            }
        }
        SectionHeader(text = "Other")
        CreateNewRow(onClick = onCreateNew)
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun TitleRow(onClose: () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Choose workspace",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Close",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RecentRow(
    path: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Folder,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = path,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CreateNewRow(onClick: () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.CreateNewFolder,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Create new folder under pyry-workspace…",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private val SAMPLE_RECENTS =
    listOf(
        "~/Workspace/Projects/KitchenClaw",
        "~/Workspace/Projects/pyrycode",
        "~/Workspace/Projects/pyrycode-mobile",
        "~/Workspace/personal",
    )

@Preview(name = "WorkspacePickerSheet — Light", showBackground = true, widthDp = 412)
@Composable
private fun WorkspacePickerSheetPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(modifier = Modifier.padding(PaddingValues(top = 12.dp))) {
                WorkspacePickerSheetContent(
                    recent = SAMPLE_RECENTS,
                    onPick = {},
                    onCreateNew = {},
                    onDismiss = {},
                )
            }
        }
    }
}

@Preview(
    name = "WorkspacePickerSheet — Dark",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun WorkspacePickerSheetDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(modifier = Modifier.padding(PaddingValues(top = 12.dp))) {
                WorkspacePickerSheetContent(
                    recent = SAMPLE_RECENTS,
                    onPick = {},
                    onCreateNew = {},
                    onDismiss = {},
                )
            }
        }
    }
}
