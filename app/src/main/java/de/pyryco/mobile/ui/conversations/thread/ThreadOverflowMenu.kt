package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import de.pyryco.mobile.R

@Composable
fun ThreadOverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onEvent: (ThreadEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier,
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.thread_overflow_new_session)) },
            onClick = {
                onDismiss()
                onEvent(ThreadEvent.NewSession)
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.thread_overflow_rename)) },
            onClick = {
                onDismiss()
                onEvent(ThreadEvent.Rename)
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.thread_overflow_change_workspace)) },
            onClick = {
                onDismiss()
                onEvent(ThreadEvent.ChangeWorkspace)
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.thread_overflow_archive)) },
            onClick = {
                onDismiss()
                onEvent(ThreadEvent.Archive)
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.thread_overflow_channel_info)) },
            onClick = {
                onDismiss()
                onEvent(ThreadEvent.ChannelInfo)
            },
        )
    }
}
