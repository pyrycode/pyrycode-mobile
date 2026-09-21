package de.pyryco.mobile.ui.conversations.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.pyryco.mobile.data.repository.ConversationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * The generic, fixed failure message the picker shows when [ConversationRepository.createWorkspaceFolder]
 * fails (#564). Deliberately a **static literal** — it never interpolates the server's error message
 * or the attempted name/path, so no untrusted path bytes reach the UI (§ Security). The failure state
 * has no Figma design (design-owed); this is an idiomatic generic M3 error affordance.
 */
private const val CREATE_FOLDER_ERROR_MESSAGE =
    "Something went wrong. Check your connection and try again."

/**
 * The host whose folders this picker reads and creates in, bound by whichever route opened it.
 *
 * Every production host binds it since #714 took Settings off the compatibility binding: thread and
 * literal routes bind their route host, the flat channel screen and Settings bind their picker's own
 * captured target. The `null` default below is therefore reached only from previews and from
 * component tests that compose the picker with no provider.
 */
internal val LocalWorkspacePickerRepository = staticCompositionLocalOf<ConversationRepository?> { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspacePicker(
    visible: Boolean,
    onPicked: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val repository = LocalWorkspacePickerRepository.current ?: koinInject<ConversationRepository>()
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
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
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
                errorMessage = null
                scope.launch {
                    try {
                        // On success the returned path flows straight to onPicked and becomes the
                        // selected workspace (AC #2). On any failure — not-connected session, server
                        // error, or malformed reply — surface a generic message instead of crashing
                        // (AC #3). CancellationException is caught first and rethrown so a dismissed
                        // sheet cancels cleanly (it extends IllegalStateException on the JVM, so a
                        // broad catch would otherwise swallow it).
                        onPicked(repository.createWorkspaceFolder(name))
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (e: Exception) {
                        errorMessage = CREATE_FOLDER_ERROR_MESSAGE
                    }
                }
            },
            onDismiss = { showCreateDialog = false },
        )
    }
    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text("Couldn't create folder") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { errorMessage = null }) {
                    Text("OK")
                }
            },
        )
    }
}
