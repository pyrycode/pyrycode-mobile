package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.preferences.Effort
import de.pyryco.mobile.data.preferences.Model
import de.pyryco.mobile.data.preferences.label
import de.pyryco.mobile.data.repository.BoundaryReason
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.components.ConnectionBanner
import de.pyryco.mobile.ui.conversations.components.EmptyThreadState
import de.pyryco.mobile.ui.conversations.components.MessageBubble
import de.pyryco.mobile.ui.conversations.components.RenameDialog
import de.pyryco.mobile.ui.conversations.components.SessionBoundaryDelimiter
import de.pyryco.mobile.ui.conversations.components.StatusSheet
import de.pyryco.mobile.ui.conversations.components.WorkspaceChip
import de.pyryco.mobile.ui.conversations.components.WorkspacePicker
import de.pyryco.mobile.ui.settings.label
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.datetime.Instant

private const val ABOVE_DELIMITER_ALPHA = 0.55f

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
    onOverflowEvent: (ThreadEvent) -> Unit = {},
    onModelSelected: (Model) -> Unit = {},
    onEffortSelected: (Effort) -> Unit = {},
    onYoloToggled: (Boolean) -> Unit = {},
    onWorkspaceChipTapped: () -> Unit = {},
    onWorkspacePicked: (String) -> Unit = {},
    onWorkspacePickerDismissed: () -> Unit = {},
) {
    var sheetVisible by rememberSaveable { mutableStateOf(false) }
    var overflowExpanded by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        topBar = {
            ThreadTopAppBar(
                title = state.displayName,
                onBack = onBack,
                onTitleClick = onTitleClick,
                onOverflowClick = { overflowExpanded = true },
                overflowExpanded = overflowExpanded,
                onOverflowDismiss = { overflowExpanded = false },
                onOverflowEvent = onOverflowEvent,
            )
        },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                ThreadStatusRow(
                    model = state.selectedModel.label(),
                    effort = state.selectedEffort.label(),
                    tokenPercent = state.tokenPercent,
                    onExpandClick = { sheetVisible = true },
                )
                ThreadInputBar(onSend = onSendMessage)
            }
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
            if (!state.hasMessages) {
                EmptyThreadState(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 24.dp),
                )
            } else {
                val reversedItems = state.items.asReversed()
                val cutoffChronologicalIndex =
                    remember(state.items) { mostRecentSessionBoundaryIndex(state.items) }
                val listState = rememberLazyListState()
                val hasStreamingMessage by remember(state.items) {
                    derivedStateOf {
                        state.items.any { it is ThreadItem.MessageItem && it.message.isStreaming }
                    }
                }
                var userScrolledAway by remember { mutableStateOf(false) }
                val autoScrollNestedScroll =
                    remember {
                        object : NestedScrollConnection {
                            override fun onPreScroll(
                                available: Offset,
                                source: NestedScrollSource,
                            ): Offset {
                                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                                    userScrolledAway = true
                                }
                                return Offset.Zero
                            }
                        }
                    }
                LaunchedEffect(listState) {
                    snapshotFlow {
                        listState.firstVisibleItemIndex == 0 &&
                            listState.firstVisibleItemScrollOffset == 0
                    }.collect { atBottom ->
                        if (atBottom) userScrolledAway = false
                    }
                }
                LaunchedEffect(hasStreamingMessage, listState) {
                    if (!hasStreamingMessage) return@LaunchedEffect
                    snapshotFlow {
                        listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.index == 0 }
                            ?.size ?: 0
                    }.distinctUntilChanged()
                        .collect {
                            if (!userScrolledAway) {
                                listState.scrollToItem(0)
                            }
                        }
                }
                LazyColumn(
                    state = listState,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .nestedScroll(autoScrollNestedScroll),
                    reverseLayout = true,
                ) {
                    itemsIndexed(
                        items = reversedItems,
                        key = { _, item ->
                            when (item) {
                                is ThreadItem.MessageItem -> "msg:${item.message.id}"
                                is ThreadItem.SessionBoundary ->
                                    "boundary:${item.previousSessionId}->${item.newSessionId}"
                            }
                        },
                    ) { reversedIndex, item ->
                        val chronologicalIndex = state.items.size - 1 - reversedIndex
                        val rowAlpha =
                            if (chronologicalIndex < cutoffChronologicalIndex) {
                                ABOVE_DELIMITER_ALPHA
                            } else {
                                1f
                            }
                        Box(modifier = Modifier.alpha(rowAlpha)) {
                            when (item) {
                                is ThreadItem.MessageItem -> MessageBubble(message = item.message)
                                is ThreadItem.SessionBoundary ->
                                    SessionBoundaryDelimiter(boundary = item)
                            }
                        }
                    }
                }
            }
        }
    }
    WorkspacePicker(
        visible = state.workspacePickerVisible,
        onPicked = onWorkspacePicked,
        onDismiss = onWorkspacePickerDismissed,
    )
    if (state.showRenameDialog) {
        RenameDialog(
            initialName = state.displayName,
            onSubmit = { onOverflowEvent(ThreadEvent.RenameSubmit(it)) },
            onDismiss = { onOverflowEvent(ThreadEvent.RenameDismiss) },
        )
    }
    if (sheetVisible) {
        StatusSheet(
            selectedModel = state.selectedModel,
            onModelSelected = { model ->
                onModelSelected(model)
                sheetVisible = false
            },
            selectedEffort = state.selectedEffort,
            onEffortSelected = { effort ->
                onEffortSelected(effort)
                sheetVisible = false
            },
            yoloEnabled = state.yoloEnabled,
            onYoloToggled = onYoloToggled,
            onDismiss = { sheetVisible = false },
        )
    }
}

internal fun mostRecentSessionBoundaryIndex(items: List<ThreadItem>): Int = items.indexOfLast { it is ThreadItem.SessionBoundary }

private fun previewItems(): List<ThreadItem> {
    val t0 = Instant.parse("2026-05-17T14:32:00Z")
    val t1 = Instant.parse("2026-05-17T14:32:10Z")
    val t2 = Instant.parse("2026-05-17T14:32:20Z")
    val t3 = Instant.parse("2026-05-17T14:33:00Z")
    return listOf(
        ThreadItem.MessageItem(
            Message(
                id = "u1",
                sessionId = "s1",
                role = Role.User,
                content = "Can you help me think through the schema migration plan?",
                timestamp = t0,
                isStreaming = false,
            ),
        ),
        ThreadItem.MessageItem(
            Message(
                id = "a1",
                sessionId = "s1",
                role = Role.Assistant,
                content = "Sure — let me read the existing schema first.",
                timestamp = t1,
                isStreaming = false,
            ),
        ),
        ThreadItem.MessageItem(
            Message(
                id = "t1",
                sessionId = "s1",
                role = Role.Tool,
                content = "",
                timestamp = t2,
                isStreaming = false,
                toolCall =
                    ToolCall(
                        toolName = "read_file",
                        input = "kitchenclaw/db/schema.ts",
                        output = "184 lines",
                    ),
            ),
        ),
        ThreadItem.SessionBoundary(
            previousSessionId = "s1",
            newSessionId = "s2",
            reason = BoundaryReason.Clear,
            occurredAt = t3,
            workspaceCwd = null,
        ),
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
                    isPromoted = true,
                    items = previewItems(),
                    tokenPercent = 73,
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
                    isPromoted = true,
                    items = previewItems(),
                    tokenPercent = 73,
                ),
            onBack = {},
            onSendMessage = {},
            connectionState = ConnectionState.Connected,
            onRetry = {},
        )
    }
}

private fun previewItemsWithBoundaries(): List<ThreadItem> {
    val t0 = Instant.parse("2026-05-17T13:00:00Z")
    val t1 = Instant.parse("2026-05-17T13:00:10Z")
    val t2 = Instant.parse("2026-05-17T13:30:00Z")
    val t3 = Instant.parse("2026-05-17T13:30:10Z")
    val t4 = Instant.parse("2026-05-17T13:30:20Z")
    val t5 = Instant.parse("2026-05-17T13:30:30Z")
    val t6 = Instant.parse("2026-05-17T14:00:00Z")
    val t7 = Instant.parse("2026-05-17T14:00:10Z")
    val t8 = Instant.parse("2026-05-17T14:00:20Z")
    return listOf(
        ThreadItem.MessageItem(
            Message(
                id = "u0",
                sessionId = "s0",
                role = Role.User,
                content = "Earlier: can we sketch the rough plan?",
                timestamp = t0,
                isStreaming = false,
            ),
        ),
        ThreadItem.MessageItem(
            Message(
                id = "a0",
                sessionId = "s0",
                role = Role.Assistant,
                content = "Sure — let me start with the data model.",
                timestamp = t1,
                isStreaming = false,
            ),
        ),
        ThreadItem.SessionBoundary(
            previousSessionId = "s0",
            newSessionId = "s1",
            reason = BoundaryReason.Clear,
            occurredAt = t2,
            workspaceCwd = null,
        ),
        ThreadItem.MessageItem(
            Message(
                id = "u1",
                sessionId = "s1",
                role = Role.User,
                content = "Can you help me think through the schema migration plan?",
                timestamp = t3,
                isStreaming = false,
            ),
        ),
        ThreadItem.MessageItem(
            Message(
                id = "a1",
                sessionId = "s1",
                role = Role.Assistant,
                content = "Sure — let me read the existing schema first.",
                timestamp = t4,
                isStreaming = false,
            ),
        ),
        ThreadItem.MessageItem(
            Message(
                id = "t1",
                sessionId = "s1",
                role = Role.Tool,
                content = "",
                timestamp = t5,
                isStreaming = false,
                toolCall =
                    ToolCall(
                        toolName = "read_file",
                        input = "kitchenclaw/db/schema.ts",
                        output = "184 lines",
                    ),
            ),
        ),
        ThreadItem.SessionBoundary(
            previousSessionId = "s1",
            newSessionId = "s2",
            reason = BoundaryReason.WorkspaceChange,
            occurredAt = t6,
            workspaceCwd = "~/Workspace/Projects/KitchenClaw",
        ),
        ThreadItem.MessageItem(
            Message(
                id = "u2",
                sessionId = "s2",
                role = Role.User,
                content = "I think the migration script needs to handle the legacy schema first.",
                timestamp = t7,
                isStreaming = false,
            ),
        ),
        ThreadItem.MessageItem(
            Message(
                id = "a2",
                sessionId = "s2",
                role = Role.Assistant,
                content = "Good thinking — let me sketch what the migration shape would look like.",
                timestamp = t8,
                isStreaming = false,
            ),
        ),
    )
}

@Preview(name = "Thread — Above-delimiter dim · Light", showBackground = true, widthDp = 412)
@Composable
private fun ThreadScreenAboveDelimiterDimLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        ThreadScreen(
            state =
                ThreadUiState(
                    conversationId = "seed-channel-personal",
                    displayName = "kitchenclaw refactor",
                    isPromoted = true,
                    items = previewItemsWithBoundaries(),
                    tokenPercent = 73,
                ),
            onBack = {},
            onSendMessage = {},
            connectionState = ConnectionState.Connected,
            onRetry = {},
        )
    }
}

@Preview(name = "Thread — Above-delimiter dim · Dark", showBackground = true, widthDp = 412)
@Composable
private fun ThreadScreenAboveDelimiterDimDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        ThreadScreen(
            state =
                ThreadUiState(
                    conversationId = "seed-channel-personal",
                    displayName = "kitchenclaw refactor",
                    isPromoted = true,
                    items = previewItemsWithBoundaries(),
                    tokenPercent = 73,
                ),
            onBack = {},
            onSendMessage = {},
            connectionState = ConnectionState.Connected,
            onRetry = {},
        )
    }
}
