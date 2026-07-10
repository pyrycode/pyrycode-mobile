package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.SecureFlagPolicy
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.ModalUiState
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.preferences.Effort
import de.pyryco.mobile.data.preferences.Model
import de.pyryco.mobile.data.preferences.label
import de.pyryco.mobile.data.repository.BoundaryReason
import de.pyryco.mobile.data.repository.QueuedMessage
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.components.ChannelInfoSheet
import de.pyryco.mobile.ui.conversations.components.ChannelInfoUiModel
import de.pyryco.mobile.ui.conversations.components.ConnectionBanner
import de.pyryco.mobile.ui.conversations.components.EmptyThreadState
import de.pyryco.mobile.ui.conversations.components.InterruptAffordance
import de.pyryco.mobile.ui.conversations.components.MessageBubble
import de.pyryco.mobile.ui.conversations.components.QueuedBacklog
import de.pyryco.mobile.ui.conversations.components.RenameDialog
import de.pyryco.mobile.ui.conversations.components.SaveAsChannelDialog
import de.pyryco.mobile.ui.conversations.components.SessionBoundaryDelimiter
import de.pyryco.mobile.ui.conversations.components.StallPromotionBanner
import de.pyryco.mobile.ui.conversations.components.StatusSheet
import de.pyryco.mobile.ui.conversations.components.ThinkingIndicator
import de.pyryco.mobile.ui.conversations.components.WorkspaceChip
import de.pyryco.mobile.ui.conversations.components.WorkspacePicker
import de.pyryco.mobile.ui.conversations.components.formatRelativeTime
import de.pyryco.mobile.ui.settings.label
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.datetime.Clock
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
    isThinking: Boolean = false,
    isStalled: Boolean = false,
    isBusy: Boolean = false, // #459: a turn is in flight (thinking OR responding) → show the interrupt affordance
    onInterrupt: () -> Unit = {}, // #459: wired by MainActivity → vm::onInterrupt (the #458 send path)
    onTitleClick: () -> Unit = {},
    onOverflowEvent: (ThreadEvent) -> Unit = {},
    onShowLiteralScreen: () -> Unit = {},
    onModelSelected: (Model) -> Unit = {},
    onEffortSelected: (Effort) -> Unit = {},
    onYoloToggled: (Boolean) -> Unit = {},
    onWorkspaceChipTapped: () -> Unit = {},
    onWorkspacePicked: (String) -> Unit = {},
    onWorkspacePickerDismissed: () -> Unit = {},
    modalState: ModalUiState = ModalUiState.Hidden,
    armedOptionId: String? = null, // #452: the open modal's armed non-default option, or null (VM-scoped, #451)
    modalSendErrors: Flow<Unit> = emptyFlow(), // #452: payload-free one-shot modal send-failure signal (#451)
    newSessionErrors: Flow<Unit> = emptyFlow(), // #540: payload-free one-shot new-session send-failure signal
    archiveErrors: Flow<Unit> = emptyFlow(), // #556: payload-free one-shot archive send-failure signal
    onModalOption: (String) -> Unit = {}, // #452: wired by MainActivity → vm::onModalOption (passes ModalOption.id)
    onModalCancel: () -> Unit = {}, // #452: wired by MainActivity → vm::onModalCancel
    onDropQueued: (Long) -> Unit = {}, // #467: wired by MainActivity → vm::onDropQueued (passes QueuedMessage.id)
) {
    var sheetVisible by rememberSaveable { mutableStateOf(false) }
    var overflowExpanded by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    // #452: surface a failed modal send as a transient snackbar. The event is payload-free (Unit, #451) and
    // the message is a fixed local string, so nothing modal-derived (command / path) can reach the
    // un-secured Activity window the snackbar draws in. Same VM-event→snackbar idiom as ArchivedDiscussionsScreen.
    val modalSendFailedMessage = stringResource(R.string.modal_send_failed)
    LaunchedEffect(modalSendErrors, snackbarHostState) {
        modalSendErrors.collect { snackbarHostState.showSnackbar(modalSendFailedMessage) }
    }
    // #540: surface a failed "New session" send as a transient snackbar. Same payload-free (Unit) one-shot
    // idiom as the modal path — the fixed local string keeps anything exception-derived out of the
    // un-secured Activity window the snackbar draws in.
    val newSessionFailedMessage = stringResource(R.string.new_session_failed)
    LaunchedEffect(newSessionErrors, snackbarHostState) {
        newSessionErrors.collect { snackbarHostState.showSnackbar(newSessionFailedMessage) }
    }
    // #556: surface a failed "Archive" as a transient snackbar. Same payload-free (Unit) one-shot idiom;
    // the fixed local string keeps the server-supplied RelayErrorException.message out of the un-secured
    // Activity window the snackbar draws in.
    val archiveFailedMessage = stringResource(R.string.archive_failed)
    LaunchedEffect(archiveErrors, snackbarHostState) {
        archiveErrors.collect { snackbarHostState.showSnackbar(archiveFailedMessage) }
    }
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            ThreadTopAppBar(
                title = state.displayName,
                onBack = onBack,
                onTitleClick = onTitleClick,
                onOverflowClick = { overflowExpanded = true },
                overflowExpanded = overflowExpanded,
                onOverflowDismiss = { overflowExpanded = false },
                onOverflowEvent = onOverflowEvent,
                onShowLiteralScreen = onShowLiteralScreen,
                isPromoted = state.isPromoted,
                mutationsSupported = state.mutationsSupported,
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
            StallPromotionBanner(isStalled = isStalled, onShowLiteralScreen = onShowLiteralScreen)
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
            // Content continuation below the thread: the ordered queued-message backlog (#461). Sits
            // directly under the list (it extends the user's side of the conversation) and above the
            // foot-most ThinkingIndicator. A separate wrap-content section, not a LazyColumn row, so the
            // list's keying / alpha-dimming / auto-scroll logic stays untouched.
            QueuedBacklog(
                queued = state.queuedMessages,
                onDrop = onDropQueued,
                modifier = Modifier.fillMaxWidth(),
            )
            ThinkingIndicator(isThinking = isThinking, modifier = Modifier.fillMaxWidth())
            // The interrupt affordance (#459): shown across the whole in-flight turn (thinking OR
            // responding), so it sits at the very foot of the list, below the thinking spinner. Interim
            // placement — design-owed, same status as ThinkingIndicator until the Figma frame draws it.
            InterruptAffordance(isBusy = isBusy, onInterrupt = onInterrupt, modifier = Modifier.fillMaxWidth())
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
    state.saveAsChannelDialog?.let { dialogState ->
        SaveAsChannelDialog(
            initialName = dialogState.initialName,
            onSubmit = { name, workspace ->
                onOverflowEvent(
                    ThreadEvent.SaveAsChannelSubmit(name = name, workspace = workspace),
                )
            },
            onDismiss = { onOverflowEvent(ThreadEvent.SaveAsChannelDismiss) },
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
            tokenPercent = state.tokenPercent,
            tokensUsed = state.tokensUsed,
            tokensTotal = state.tokensTotal,
        )
    }
    if (state.channelInfoOpen) {
        ChannelInfoSheet(
            model = state.toChannelInfoUiModel(),
            mutationsSupported = state.mutationsSupported,
            onRename = {
                onOverflowEvent(ThreadEvent.Rename)
                onOverflowEvent(ThreadEvent.ChannelInfoDismiss)
            },
            onChangeWorkspace = {
                onOverflowEvent(ThreadEvent.ChangeWorkspace)
                onOverflowEvent(ThreadEvent.ChannelInfoDismiss)
            },
            onArchive = { onOverflowEvent(ThreadEvent.Archive) },
            onDelete = { onOverflowEvent(ThreadEvent.Delete) },
            onInstallMemoryPlugin = { /* TODO: Phase 3+ */ },
            onDismiss = { onOverflowEvent(ThreadEvent.ChannelInfoDismiss) },
        )
    }
    if (state.deleteConfirmVisible) {
        DeleteConfirmationDialog(
            displayName = state.displayName,
            onConfirm = { onOverflowEvent(ThreadEvent.DeleteConfirm) },
            onDismiss = { onOverflowEvent(ThreadEvent.DeleteDismiss) },
        )
    }
    // App-level permission/choice modal overlay (#446). Hoisted single source = ThreadViewModel.currentModal
    // (#445), forwarded verbatim. Open → separate-surface overlay; Dismissed → surface the resolution reason
    // once and remove the overlay. Modal events carry no conversation_id, so this is not scoped per thread.
    when (modalState) {
        is ModalUiState.Open ->
            PermissionModalOverlay(
                open = modalState,
                armedOptionId = armedOptionId,
                onOption = onModalOption,
                onCancel = onModalCancel,
            )
        is ModalUiState.Dismissed -> {
            val reason = dismissReasonText(modalState.source)
            // Keyed on modalId: Dismissed is a sticky terminal state (#445's fold), so this fires exactly
            // once per resolution and never re-fires on unrelated recomposition.
            LaunchedEffect(modalState.modalId) {
                snackbarHostState.showSnackbar(reason)
            }
        }
        ModalUiState.Hidden -> Unit
    }
}

/**
 * The permission/choice modal overlay (#446) — a separate-surface M3 dialog floating over the active
 * thread, **not** a row in the thread [LazyColumn]. Renders the verbatim [title][ModalUiState.Open.title],
 * [prompt][ModalUiState.Open.prompt], and [options][ModalUiState.Open.options] (in wire array order) and
 * highlights the producer's fail-safe-deny [defaultOptionId][ModalUiState.Open.defaultOptionId].
 *
 * Security (this slice owns the render-time obligations #445 deferred):
 * - **Inert output-encoding** — every server string renders through plain [Text] (literal, no
 *   markup/HTML/active content; never [de.pyryco.mobile.ui.conversations.components.MarkdownText], no
 *   `SelectionContainer` clipboard path) — the values may name a sensitive command or path.
 * - **Screen-capture hardening** — [SecureFlagPolicy.SecureOn] sets `FLAG_SECURE` on the dialog's **own**
 *   window (a host-Activity flag would not cover it; the host carries no `FLAG_SECURE`). `SecureOn`, not
 *   the default `Inherit`, is load-bearing.
 * - **No persistence** — no modal-derived text reaches `rememberSaveable` / saved-instance state.
 * - **Tapjacking** (#452, now that the taps are live) — `filterTouchesWhenObscured` on the dialog's **own**
 *   window drops touches delivered while another window obscures it. A deterministic View-level net (min
 *   SDK 33), *different fabric* from the second-confirm UX belt (#451).
 *
 * Live in #452: [onOption] forwards every tapped option id verbatim — the VM decides arm-vs-send; the UI
 * never re-derives the arm. [armedOptionId] reflects the VM's armed non-default option (#451), drawing the
 * second-confirm affordance on that one option. [onCancel] is reached only via the explicit low-emphasis
 * Cancel button; back-press / outside-tap dismissal stay disabled (#446) so a permission gate never reads a
 * stray gesture as an implicit answer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermissionModalOverlay(
    open: ModalUiState.Open,
    armedOptionId: String?,
    onOption: (String) -> Unit,
    onCancel: () -> Unit,
) {
    BasicAlertDialog(
        onDismissRequest = onCancel,
        properties =
            DialogProperties(
                securePolicy = SecureFlagPolicy.SecureOn,
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
    ) {
        // The taps now answer a high-consequence permission gate, so harden the dialog's own window against
        // tapjacking: drop touches delivered while another window obscures it. No Compose-test semantics
        // node ⇒ verified by inspection (matches #446's treatment of SecureOn).
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { dialogWindow?.decorView?.filterTouchesWhenObscured = true }
        Surface(
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(text = open.title, style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = open.prompt, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(16.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Iterate in array order (the canonical display/selection order). isDefault drives the
                    // fail-safe-deny highlight; isArmed reflects the VM's armed non-default option — no
                    // option-id semantics are interpreted, every tap forwards verbatim.
                    open.options.forEach { option ->
                        ModalOptionButton(
                            label = option.label,
                            isDefault = option.id == open.defaultOptionId,
                            isArmed = option.id == armedOptionId,
                            onClick = { onOption(option.id) },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.modal_cancel))
                }
            }
        }
    }
}

/**
 * One modal option — a **stateless** pure function of [label] / [isDefault] / [isArmed]; it holds no
 * `remember`-based arm state (the arm lives on the VM, #451; this slice only renders [armedOptionId]).
 * Three disjoint renders, [isArmed] taking precedence:
 * - [isArmed] (an armed non-default awaiting its second confirm, #452) → a [FilledTonalButton], kept
 *   **below** the default's filled emphasis so the safe default stays visually dominant, plus the
 *   `modal_armed_option_desc` `stateDescription` ("Tap again to confirm").
 * - [isDefault] (the fail-safe-deny default) → a high-emphasis filled [Button] + the
 *   `modal_default_option_desc` marker, so the visually prominent button is always the producer's
 *   deny/safe option (it answers on a single tap).
 * - neither → an [OutlinedButton], no marker (a first tap arms it via the VM).
 *
 * The `stateDescription` markers are accessible + test-observable (a screen reader announces them; the AC#4
 * test locates the armed / default option by these, not by colour). Only the local markers are added — the
 * verbatim server [label] stays the sole server text, rendered through plain [Text].
 */
@Composable
private fun ModalOptionButton(
    label: String,
    isDefault: Boolean,
    isArmed: Boolean,
    onClick: () -> Unit,
) {
    val defaultDesc = stringResource(R.string.modal_default_option_desc)
    val armedDesc = stringResource(R.string.modal_armed_option_desc)
    val modifier =
        when {
            isArmed ->
                Modifier
                    .fillMaxWidth()
                    .semantics { stateDescription = armedDesc }
            isDefault ->
                Modifier
                    .fillMaxWidth()
                    .semantics { stateDescription = defaultDesc }
            else -> Modifier.fillMaxWidth()
        }
    when {
        isArmed -> FilledTonalButton(onClick = onClick, modifier = modifier) { Text(label) }
        isDefault -> Button(onClick = onClick, modifier = modifier) { Text(label) }
        else -> OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
    }
}

/**
 * Maps the verbatim [Dismissed.source][ModalUiState.Dismissed.source] to a **local** string resource —
 * never echoing the raw wire token (the snackbar draws in the un-secured Activity window, so the mapping
 * is a confidentiality requirement, not only UX). Unknown forward-compat values fall back to a generic
 * "resolved" message (AC #3).
 */
@Composable
private fun dismissReasonText(source: String): String =
    when (source) {
        "remote" -> stringResource(R.string.modal_dismissed_remote)
        "local" -> stringResource(R.string.modal_dismissed_local)
        "timeout" -> stringResource(R.string.modal_dismissed_timeout)
        else -> stringResource(R.string.modal_dismissed_resolved)
    }

@Composable
private fun DeleteConfirmationDialog(
    displayName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_dialog_title)) },
        text = { Text(stringResource(R.string.delete_dialog_body, displayName)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.delete_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.delete_dialog_cancel))
            }
        },
    )
}

internal fun mostRecentSessionBoundaryIndex(items: List<ThreadItem>): Int = items.indexOfLast { it is ThreadItem.SessionBoundary }

private fun ThreadItem.timestamp(): Instant =
    when (this) {
        is ThreadItem.MessageItem -> message.timestamp
        is ThreadItem.SessionBoundary -> occurredAt
    }

internal fun ThreadUiState.toChannelInfoUiModel(now: Instant = Clock.System.now()): ChannelInfoUiModel =
    ChannelInfoUiModel(
        conversationName = displayName,
        workspacePath = workspacePath,
        createdLabel = items.firstOrNull()?.let { formatRelativeTime(it.timestamp(), now) } ?: "—",
        lastActivityLabel = lastUsedAt?.let { formatRelativeTime(it, now) } ?: "—",
        sessionCount = sessionCount,
        messageCount = items.count { it is ThreadItem.MessageItem },
        memoryPlugins = emptyList(),
        channelId = conversationId,
    )

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
                    tokensUsed = 146_000,
                    tokensTotal = 200_000,
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
                    tokensUsed = 146_000,
                    tokensTotal = 200_000,
                ),
            onBack = {},
            onSendMessage = {},
            connectionState = ConnectionState.Connected,
            onRetry = {},
        )
    }
}

@Preview(name = "Thread — Queued backlog · Dark", showBackground = true, widthDp = 412)
@Composable
private fun ThreadScreenQueuedBacklogDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        ThreadScreen(
            state =
                ThreadUiState(
                    conversationId = "seed-channel-personal",
                    displayName = "kitchenclaw refactor",
                    isPromoted = true,
                    items = previewItems(),
                    queuedMessages =
                        listOf(
                            QueuedMessage(
                                id = 1L,
                                text = "Also update the migration tests once you're done.",
                                timestamp = Instant.parse("2026-05-17T14:34:00Z"),
                            ),
                            QueuedMessage(
                                id = 2L,
                                text = "Then push a draft PR.",
                                timestamp = Instant.parse("2026-05-17T14:34:10Z"),
                            ),
                        ),
                    tokenPercent = 73,
                    tokensUsed = 146_000,
                    tokensTotal = 200_000,
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
                    tokensUsed = 146_000,
                    tokensTotal = 200_000,
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
                    tokensUsed = 146_000,
                    tokensTotal = 200_000,
                ),
            onBack = {},
            onSendMessage = {},
            connectionState = ConnectionState.Connected,
            onRetry = {},
        )
    }
}
