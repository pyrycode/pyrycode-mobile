package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.ui.conversations.components.ConnectionBanner
import de.pyryco.mobile.ui.conversations.components.WorkspaceChip
import de.pyryco.mobile.ui.conversations.components.WorkspacePicker
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    state: ThreadUiState,
    onBack: () -> Unit,
    onSendMessage: (String) -> Unit,
    connectionState: ConnectionState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onTitleClick: () -> Unit = {},
    onOverflowClick: () -> Unit = {},
    onWorkspaceChipTapped: () -> Unit = {},
    onWorkspacePicked: (String) -> Unit = {},
    onWorkspacePickerDismissed: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            ThreadTopAppBar(
                title = state.displayName,
                onBack = onBack,
                onTitleClick = onTitleClick,
                onOverflowClick = onOverflowClick,
            )
        },
        bottomBar = {
            ThreadInputBar(onSend = onSendMessage)
        },
    ) { inner ->
        Column(
            modifier =
                Modifier
                    .padding(inner)
                    .fillMaxSize(),
        ) {
            ConnectionBanner(state = connectionState, onRetry = onRetry)
            if (!state.isPromoted && !state.hasMessages) {
                WorkspaceChip(
                    workspaceLabel = state.workspaceLabel,
                    onClick = onWorkspaceChipTapped,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                reverseLayout = true,
            ) {
                items(items = emptyList<Unit>()) { }
            }
        }
    }
    WorkspacePicker(
        visible = state.workspacePickerVisible,
        onPicked = onWorkspacePicked,
        onDismiss = onWorkspacePickerDismissed,
    )
}

@Preview(name = "Thread — Light", showBackground = true, widthDp = 412)
@Composable
private fun ThreadScreenLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        ThreadScreen(
            state =
                ThreadUiState(
                    conversationId = "seed-channel-personal",
                    displayName = "kitchenclaw refactor",
                ),
            onBack = {},
            onSendMessage = {},
            connectionState = ConnectionState.Connected,
            onRetry = {},
        )
    }
}

@Preview(name = "Thread — Dark", showBackground = true, widthDp = 412)
@Composable
private fun ThreadScreenDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        ThreadScreen(
            state =
                ThreadUiState(
                    conversationId = "seed-channel-personal",
                    displayName = "kitchenclaw refactor",
                ),
            onBack = {},
            onSendMessage = {},
            connectionState = ConnectionState.Connected,
            onRetry = {},
        )
    }
}
