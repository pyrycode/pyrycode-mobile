package de.pyryco.mobile.ui.components

import android.content.res.Configuration
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.BuildConfig
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.conversations.components.CreateFolderDialog
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

/**
 * Upper bound on a displayed folder path, in UTF-16 units. The daemon authors the paths and the
 * protocol sets them no display bound; the raw path still travels back unchanged through `onSelect`.
 */
internal const val MAX_PATH_DISPLAY_CHARS: Int = 512

// The frame's 8dp label/field gap, reused between a section label and its rows.
private val SectionLabelGap = 8.dp

// The shell's touch floor for its own actions, applied to every row and the create action.
private val ActionMinHeight = 48.dp

/**
 * The Add workspace frame (#904), drawn through [MobileModal] and driven entirely by its caller —
 * desktop's host-row Add workspace dialog on the phone, a folder list in place of a typed path.
 *
 * Presentation only, like [EditChatModal]: it reports a tapped folder through [onSelect], a new folder's
 * trimmed name through [onCreateFolder], OK through [onSubmit] and every dismissal route through
 * [onDismissRequest], and none of them closes it. The only state it holds is whether the new-folder
 * dialog is up; the selection is the caller's, so a failed write cannot lose it.
 *
 * [recent] and [selected] are daemon-authored paths. They render as clamped text only, and [onSelect]
 * receives the raw path from [recent], never the displayed text. A [selected] path absent from [recent]
 * (a folder just created) is drawn in its own section so the selection is always visible. OK needs a
 * selection and an available host; [error] stays generic because the shell announces it aloud.
 */
@Composable
internal fun AddWorkspaceModal(
    recent: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
    onCreateFolder: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    hostAvailable: Boolean = true,
    loading: Boolean = false,
    error: String? = null,
) {
    var creatingFolder by rememberSaveable { mutableStateOf(false) }

    MobileModal(
        title = stringResource(R.string.add_workspace_title),
        onDismissRequest = onDismissRequest,
        onSubmit = onSubmit,
        modifier = modifier,
        submissionEnabled = selected != null && hostAvailable,
        loading = loading,
        error = error,
    ) {
        if (selected != null && selected !in recent) {
            FolderSection(stringResource(R.string.add_workspace_new_folder), listOf(selected), selected, !loading, onSelect)
        }
        if (recent.isNotEmpty()) {
            FolderSection(stringResource(R.string.add_workspace_recent), recent, selected, !loading, onSelect)
        } else if (selected == null) {
            Text(text = stringResource(R.string.add_workspace_empty), style = MaterialTheme.typography.bodyMedium)
        }
        CreateFolderAction(
            enabled = !loading,
            onClick = {
                logAddWorkspaceEvent("create_folder_opened")
                creatingFolder = true
            },
        )
    }
    // A second window over the shell: the existing creation path, which returns to this modal with
    // its selection unchanged whether it creates or is dismissed.
    if (creatingFolder) {
        CreateFolderDialog(
            onCreate = { name ->
                creatingFolder = false
                onCreateFolder(name)
            },
            onDismiss = { creatingFolder = false },
        )
    }
}

/** One labelled group of selectable folder rows. */
@Composable
private fun FolderSection(
    label: String,
    paths: List<String>,
    selected: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SectionLabelGap),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        paths.forEach { path ->
            FolderRow(path = path, selected = path == selected, enabled = enabled, onClick = { onSelect(path) })
        }
    }
}

@Composable
private fun FolderRow(
    path: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = ActionMinHeight)
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            // The row is the control; a second click target inside it would split its semantics.
            onClick = null,
            enabled = enabled,
            colors =
                RadioButtonDefaults.colors(
                    selectedColor = MaterialTheme.colorScheme.primary,
                    unselectedColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
        )
        Text(
            text = boundedPath(path),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** The frame's outlined action styling, as `EditChatModal`'s Archive chat draws it. */
@Composable
private fun CreateFolderAction(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
        onClick = onClick,
        modifier = Modifier.padding(top = SectionLabelGap).heightIn(min = ActionMinHeight),
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        border =
            BorderStroke(
                1.dp,
                if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Icon(imageVector = Icons.Outlined.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(
            text = stringResource(R.string.add_workspace_create_folder),
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Clamps a daemon-authored path before layout without ending on half a surrogate pair. */
private fun boundedPath(path: String): String =
    path.take(MAX_PATH_DISPLAY_CHARS).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }

/** Content-free and debug-gated, in the shell's shape: the event name and nothing it was given. */
private fun logAddWorkspaceEvent(event: String) {
    if (BuildConfig.DEBUG) Log.d("AddWorkspaceModal", "event=$event")
}

@Preview(name = "Add workspace — Light", widthDp = 412, heightDp = 892, showBackground = true)
@Preview(name = "Add workspace — Dark", widthDp = 412, heightDp = 892, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AddWorkspaceModalPreview() {
    PyrycodeMobileTheme {
        AddWorkspaceModal(
            recent = listOf("~/Workspace/Projects/pyrycode", "~/Workspace/Projects/pyrycode-mobile", "~/Workspace/personal"),
            selected = "~/Workspace/Projects/pyrycode-mobile",
            onSelect = {},
            onCreateFolder = {},
            onDismissRequest = {},
            onSubmit = {},
        )
    }
}
