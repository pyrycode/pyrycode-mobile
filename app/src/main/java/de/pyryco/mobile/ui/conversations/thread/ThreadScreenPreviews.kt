package de.pyryco.mobile.ui.conversations.thread

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.ModalContext
import de.pyryco.mobile.data.model.ModalOption
import de.pyryco.mobile.data.model.ModalUiState
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.repository.BoundaryReason
import de.pyryco.mobile.data.repository.QueuedMessage
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.settings.label
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant

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

/** #777: the oldest-end loading affordance, in both palettes, with the thread otherwise unchanged. */
@Preview(name = "Thread — history loading, light", showBackground = true, widthDp = 412)
@Preview(
    name = "Thread — history loading, dark",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ThreadScreenHistoryLoadingPreview() {
    HistoryTailPreview(ThreadHistoryTail.Loading)
}

/** #778: the same slot's retry state — the design's error-plus-action chip at the oldest end. */
@Preview(name = "Thread — history retry, light", showBackground = true, widthDp = 412)
@Preview(
    name = "Thread — history retry, dark",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ThreadScreenHistoryRetryPreview() {
    HistoryTailPreview(ThreadHistoryTail.Retry)
}

/** #778: the same slot's dead end — visible, with nothing to press. */
@Preview(name = "Thread — history dead end, light", showBackground = true, widthDp = 412)
@Preview(
    name = "Thread — history dead end, dark",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ThreadScreenHistoryDeadEndPreview() {
    HistoryTailPreview(ThreadHistoryTail.DeadEnd)
}

/** #815: the permission prompt in the shared mobile modal container, with a non-default option armed. */
@Preview(name = "Permission prompt — light", showBackground = true, widthDp = 412, heightDp = 892)
@Preview(
    name = "Permission prompt — dark",
    showBackground = true,
    widthDp = 412,
    heightDp = 892,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun PermissionModalOverlayPreview() {
    PyrycodeMobileTheme {
        PermissionModalOverlay(
            open =
                ModalUiState.Open(
                    modalId = "preview",
                    modalClass = "permission",
                    title = "Permission required",
                    prompt = "claude wants to run ls -la",
                    options =
                        listOf(
                            ModalOption(id = "allow_once", label = "Allow once"),
                            ModalOption(id = "allow_always", label = "Allow always"),
                            ModalOption(id = "reject_once", label = "Reject once"),
                            ModalOption(id = "reject_always", label = "Reject always"),
                        ),
                    defaultOptionId = "reject_once",
                    context =
                        ModalContext(
                            reason = "Bash(ls:*) is on the ask list",
                            reasonType = "rule",
                            blockedPath = "/home/pyry/project",
                            description = "List the project directory",
                        ),
                    alwaysAllowRules = listOf("Bash(ls:*)", "Read(/home/pyry/project/**)"),
                ),
            armedOptionId = "allow_once",
            onOption = {},
            onCancel = {},
            alwaysAllowAccepted = true,
        )
    }
}

@Composable
private fun HistoryTailPreview(tail: ThreadHistoryTail) {
    PyrycodeMobileTheme {
        ThreadScreen(
            state =
                ThreadUiState(
                    conversationId = "seed-channel-personal",
                    displayName = "kitchenclaw refactor",
                    isPromoted = true,
                    hasMessages = true,
                    items = previewItems(),
                    historyTail = tail,
                ),
            onBack = {},
            onSendMessage = {},
            connectionState = ConnectionState.Connected,
            onRetry = {},
        )
    }
}

@Composable
private fun ThreadScreenRePairPreview(darkTheme: Boolean) {
    PyrycodeMobileTheme(darkTheme = darkTheme) {
        ThreadScreen(
            state =
                ThreadUiState(
                    conversationId = "seed-channel-personal",
                    displayName = "kitchenclaw refactor",
                    isPromoted = true,
                    items = previewItems(),
                ),
            onBack = {},
            onSendMessage = {},
            connectionState = ConnectionState.Offline,
            onRetry = {},
            isThinking = true,
            showRePair = true,
        )
    }
}

@Preview(name = "Thread — Re-pair, Light", showBackground = true, widthDp = 412)
@Composable
private fun ThreadScreenRePairLightPreview() = ThreadScreenRePairPreview(darkTheme = false)

@Preview(name = "Thread — Re-pair, Dark", showBackground = true, widthDp = 412)
@Composable
private fun ThreadScreenRePairDarkPreview() = ThreadScreenRePairPreview(darkTheme = true)

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
                ),
            onBack = {},
            onSendMessage = {},
            connectionState = ConnectionState.Connected,
            onRetry = {},
        )
    }
}

/**
 * #782: the folded backlog, in both of its forms at once. `q1` correlates with the echo `previewItems`
 * appends, so it draws **in place** as that message's queued form; `q2` carries an id this device never
 * minted (a send from the desktop), so it draws as its own row after the thread rows.
 */
private fun previewQueuedItems(): List<ThreadItem> =
    previewItems() +
        ThreadItem.MessageItem(
            Message(
                id = "u-queued",
                sessionId = "s2",
                role = Role.User,
                content = "Also update the migration tests once you're done.",
                timestamp = Instant.parse("2026-05-17T14:34:00Z"),
                isStreaming = false,
            ),
        )

@Preview(name = "Thread — Queued rows · Dark", showBackground = true, widthDp = 412)
@Composable
private fun ThreadScreenQueuedRowsDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        ThreadScreen(
            state =
                ThreadUiState(
                    conversationId = "seed-channel-personal",
                    displayName = "kitchenclaw refactor",
                    isPromoted = true,
                    items = previewQueuedItems(),
                    queuedMessages =
                        listOf(
                            QueuedMessage(
                                id = 1L,
                                text = "Also update the migration tests once you're done.",
                                timestamp = Instant.parse("2026-05-17T14:34:00Z"),
                                messageId = "u-queued",
                            ),
                            QueuedMessage(
                                id = 2L,
                                text = "Then push a draft PR.",
                                timestamp = Instant.parse("2026-05-17T14:34:10Z"),
                                messageId = "minted-on-another-device",
                            ),
                        ),
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
                ),
            onBack = {},
            onSendMessage = {},
            connectionState = ConnectionState.Connected,
            onRetry = {},
        )
    }
}
