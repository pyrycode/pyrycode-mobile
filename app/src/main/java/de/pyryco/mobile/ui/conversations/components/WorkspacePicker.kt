package de.pyryco.mobile.ui.conversations.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.pyryco.mobile.data.repository.ConversationRepository
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspacePicker(
    visible: Boolean,
    onPicked: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val repository = koinInject<ConversationRepository>()
    WorkspacePickerInternal(
        repository = repository,
        onPicked = onPicked,
        onDismiss = onDismiss,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspacePickerInternal(
    repository: ConversationRepository,
    onPicked: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val recents by repository
        .recentWorkspaces()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    WorkspacePickerSheet(
        recent = recents,
        onPick = onPicked,
        onCreateNew = { showCreateDialog = true },
        onDismiss = onDismiss,
        modifier = modifier,
    )
    if (showCreateDialog) {
        CreateFolderDialog(
            onCreate = { name ->
                showCreateDialog = false
                scope.launch {
                    val path = repository.createWorkspaceFolder(name)
                    onPicked(path)
                }
            },
            onDismiss = { showCreateDialog = false },
        )
    }
}
