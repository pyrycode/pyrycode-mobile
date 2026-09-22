package de.pyryco.mobile.ui.conversations.thread

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.ModalOption
import de.pyryco.mobile.data.model.ModalUiState
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.repository.ApiRetryStatus
import de.pyryco.mobile.data.repository.BoundaryReason
import de.pyryco.mobile.data.repository.QueuedMessage
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.components.MobileGateModal
import de.pyryco.mobile.ui.conversations.components.ApiRetryIndicator
import de.pyryco.mobile.ui.conversations.components.ChannelInfoSheet
import de.pyryco.mobile.ui.conversations.components.ChannelInfoUiModel
import de.pyryco.mobile.ui.conversations.components.CompactingIndicator
import de.pyryco.mobile.ui.conversations.components.ConnectionBanner
import de.pyryco.mobile.ui.conversations.components.EmptyThreadState
import de.pyryco.mobile.ui.conversations.components.MessageBubble
import de.pyryco.mobile.ui.conversations.components.QueuedMessageRow
import de.pyryco.mobile.ui.conversations.components.RenameDialog
import de.pyryco.mobile.ui.conversations.components.SaveAsChannelDialog
import de.pyryco.mobile.ui.conversations.components.SessionBoundaryDelimiter
import de.pyryco.mobile.ui.conversations.components.StallPromotionBanner
import de.pyryco.mobile.ui.conversations.components.StatusSheet
import de.pyryco.mobile.ui.conversations.components.ThinkingIndicator
import de.pyryco.mobile.ui.conversations.components.UnrecognizedMessageRow
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

// Figma 16:8's `Input area` (533:1957) and its offsets inside the 412dp reference frame: a 20dp
// content gutter (372dp of content), 8dp between the area's three bands, 12dp of air above it where
// the message area ends, and 16dp below it at the frame's foot.
private val ComposerGutter = 20.dp
private val ComposerSectionGap = 8.dp
private val ComposerTopGap = 12.dp
private val ComposerBottomGap = 16.dp

// The three status indicators each carry their own 16dp horizontal padding, sized for the full-bleed
// foot-of-list mount they had until #643. Inset them by the remainder so their content lands on the
// same 20dp gutter as the input field and the footer, with their own files untouched.
private val ComposerStatusGutter = ComposerGutter - 16.dp

// #777: the oldest-end loading row, sized to ThinkingIndicator's shipped spinner-and-label idiom and
// inset on the same 20dp content gutter as the rest of the thread.
private val HistoryLoadingGutter = ComposerGutter
private val HistoryLoadingVerticalPadding = 12.dp
private val HistoryLoadingSpinnerSize = 16.dp
private val HistoryLoadingSpinnerStroke = 2.dp
private val HistoryLoadingLabelGap = 8.dp

// #778: the ONE oldest-end slot. Loading, retry and dead-end share this key because they share the slot —
// at most one of them is ever emitted.
private const val HISTORY_TAIL_KEY = "history-tail"

// #778: the failure rows' own inset, one step tighter than the loading row's so the tinted surface does
// not read as a message bubble.
private val HistoryTailRowPadding = 12.dp

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
    apiRetry: ApiRetryStatus = ApiRetryStatus.NotRetrying, // #594: claude's API-retry status, replaces the spinner
    isCompacting: Boolean = false, // #597: claude is auto-compacting its context, replaces the spinner
    isBusy: Boolean = false, // #459: a turn is in flight (thinking OR responding) → show the interrupt affordance
    onInterrupt: () -> Unit = {}, // #459: wired by MainActivity → vm::onInterrupt (the #458 send path)
    onTitleClick: () -> Unit = {},
    onOverflowEvent: (ThreadEvent) -> Unit = {},
    onShowLiteralScreen: () -> Unit = {},
    // #807: a published ModelMenuRow.value / effort level, forwarded verbatim — never a device enum.
    onModelSelected: (String) -> Unit = {},
    onEffortSelected: (String) -> Unit = {},
    onYoloToggled: (Boolean) -> Unit = {},
    onWorkspaceChipTapped: () -> Unit = {},
    onWorkspacePicked: (String) -> Unit = {},
    onWorkspacePickerDismissed: () -> Unit = {},
    modalState: ModalUiState = ModalUiState.Hidden,
    armedOptionId: String? = null, // #452: the open modal's armed non-default option, or null (VM-scoped, #451)
    modalSendErrors: Flow<Unit> = emptyFlow(), // #452: payload-free one-shot modal send-failure signal (#451)
    newSessionErrors: Flow<Unit> = emptyFlow(), // #540: payload-free one-shot new-session send-failure signal
    archiveErrors: Flow<Unit> = emptyFlow(), // #556: payload-free one-shot archive send-failure signal
    changeWorkspaceErrors: Flow<Unit> = emptyFlow(), // #561: payload-free one-shot change-workspace failure signal
    sessionSettingsErrors: Flow<Unit> = emptyFlow(), // #544: payload-free one-shot run-config failure signal
    onModalOption: (String) -> Unit = {}, // #452: wired by MainActivity → vm::onModalOption (passes ModalOption.id)
    onModalCancel: () -> Unit = {}, // #452: wired by MainActivity → vm::onModalCancel
    // #467: wired by MainActivity → vm::onDropQueued (passes QueuedMessage.id). Since #782 it is bound
    // per row by the fold rather than handed to a foot-of-list section.
    onDropQueued: (Long) -> Unit = {},
    // #777: the reader has reached the oldest loaded row — ask for the next page back. Wired by
    // MainActivity → vm::onDemandOlderHistory. Safe to fire repeatedly: the ViewModel's demand drops an
    // ask that arrives while a request is outstanding or after the walk has stopped.
    onDemandOlderHistory: () -> Unit = {},
    // #778: the reader pressed the oldest-end retry affordance. Wired by MainActivity →
    // vm::onRetryOlderHistory, and inert unless the walk stopped on a retryable failure.
    onRetryOlderHistory: () -> Unit = {},
    // #789: this chat's unsent composer text and its edit sink, owned by the app-scoped
    // ComposerDraftStore and bound by MainActivity → vm.draft / vm::onDraftChange. Defaulted so the
    // screen tests that never type keep rendering an empty composer, exactly as they did when the input
    // bar owned its own text.
    draft: String = "",
    onDraftChange: (String) -> Unit = {},
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
    // #561: surface a failed workspace change as a transient snackbar. Same payload-free (Unit) one-shot
    // idiom; the fixed local string keeps the server-supplied RelayErrorException.message out of the
    // un-secured Activity window the snackbar draws in.
    val changeWorkspaceFailedMessage = stringResource(R.string.change_workspace_failed)
    LaunchedEffect(changeWorkspaceErrors, snackbarHostState) {
        changeWorkspaceErrors.collect { snackbarHostState.showSnackbar(changeWorkspaceFailedMessage) }
    }
    // #544: surface a failed run-configuration change (model / effort / YOLO) as a transient snackbar. Same
    // payload-free (Unit) one-shot idiom; the fixed local string keeps the server-supplied
    // RelayErrorException.message out of the un-secured Activity window the snackbar draws in. The control
    // reverts in the ViewModel, so the sheet never settles on a value the daemon did not confirm.
    val sessionSettingsFailedMessage = stringResource(R.string.session_settings_failed)
    LaunchedEffect(sessionSettingsErrors, snackbarHostState) {
        sessionSettingsErrors.collect { snackbarHostState.showSnackbar(sessionSettingsFailedMessage) }
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
        // Figma 16:8's `Input area` (533:1957): a gap-8 column of the status area, the input field and
        // the model/effort footer, opening 12dp below the message area and closing 16dp above the
        // frame's foot. It owns the composer's surface and the IME lift, so the whole input area rises
        // above the keyboard as one unit while the header and the list stay put — and, sitting in the
        // bottomBar slot over an opaque surface, it leaves the message list the only scrolling region.
        bottomBar = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .imePadding()
                        .padding(top = ComposerTopGap, bottom = ComposerBottomGap),
                verticalArrangement = Arrangement.spacedBy(ComposerSectionGap),
            ) {
                ThreadStatusArea(
                    apiRetry = apiRetry,
                    isCompacting = isCompacting,
                    isThinking = isThinking,
                )
                ThreadInputBar(
                    text = draft,
                    onTextChange = onDraftChange,
                    // The composer no longer clears itself here (#789): sendMessage clears the draft
                    // once the daemon has accepted it, so a refused send leaves the text to resend.
                    // Blank sends are still refused — the button disables, and the IME Send action that
                    // can still fire on an empty field hits the ViewModel's own blank guard.
                    onSend = { onSendMessage(draft) },
                    modifier = Modifier.padding(horizontal = ComposerGutter),
                    isBusy = isBusy,
                    onInterrupt = onInterrupt,
                )
                // The design puts the model/effort line in the footer, below the input field, not above
                // it. Its own 16dp horizontal padding reproduces the footer frame's further `px-16`
                // inside the 20dp content gutter applied here.
                ThreadStatusRow(
                    model = state.runConfig.modelLabel,
                    effort = state.runConfig.effortLabel,
                    onExpandClick = { sheetVisible = true },
                    modifier = Modifier.padding(horizontal = ComposerGutter),
                    pending = state.runConfig.pending,
                )
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
            // #782: the thread's rows are the join of its items with the daemon's queued backlog, so a
            // message the daemon parked draws once — in place, carrying the queue treatment — instead
            // of once as an optimistic echo and again in a foot-of-list section. Pure and cached on
            // both inputs; the backlog stays replacement truth on ThreadUiState and never folds into
            // the message reducer.
            val rows =
                remember(state.items, state.queuedMessages) {
                    foldQueuedRows(state.items, state.queuedMessages)
                }
            // A backlog item this device minted no echo for is a row of its own, so the empty state
            // must yield to it (#782 AC #3). When an item *is* matched its echo is a MessageItem, so
            // hasMessages already covers that case.
            if (!state.hasMessages && state.queuedMessages.isEmpty()) {
                EmptyThreadState(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 24.dp),
                )
            } else {
                val reversedRows = rows.asReversed()
                // Still read off state.items, and still comparing against an index into `rows`: the two
                // spaces agree wherever a boundary can land, because `rows` shares its prefix with
                // `items` index-for-index and only ever appends unmatched queued rows after them.
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
                // #777: the oldest-end demand predicate. Under reverseLayout the oldest row is the LAST
                // visible index, not the first.
                //
                // The row count is read through rememberUpdatedState over the THREAD ITEMS, never through
                // layoutInfo.totalItemsCount: the latter counts the oldest-end loading row itself, so a
                // page answering atStart = false with zero entries would self-drive with no further user
                // input — ask, the indicator mounts, the count rises, the page settles, the indicator
                // unmounts, the count falls, the predicate re-fires. Reading the thread's own count makes
                // the indicator's presence unable to move the predicate: at the oldest end the last
                // visible index is rowCount - 1 without it and rowCount with it, and `>=` holds for both,
                // so distinctUntilChanged sees no edge and no second demand is issued.
                val historyRowCount by rememberUpdatedState(rows.size)
                val demandOlderHistory by rememberUpdatedState(onDemandOlderHistory)
                LaunchedEffect(listState) {
                    snapshotFlow {
                        val oldestVisible =
                            listState.layoutInfo.visibleItemsInfo
                                .lastOrNull()
                                ?.index ?: -1
                        historyRowCount > 0 && oldestVisible >= historyRowCount - 1
                    }.distinctUntilChanged()
                        .collect { atOldestRow -> if (atOldestRow) demandOlderHistory() }
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
                        items = reversedRows,
                        // The key derivation and its uniqueness argument live beside the fold, in
                        // ThreadRows.kt — a matched queued row deliberately takes the key its
                        // delivered form carries, which is what leaves it in place across delivery.
                        key = { reversedIndex, row -> row.listKey(rows.size - 1 - reversedIndex) },
                    ) { reversedIndex, row ->
                        val chronologicalIndex = rows.size - 1 - reversedIndex
                        val rowAlpha =
                            if (chronologicalIndex < cutoffChronologicalIndex) {
                                ABOVE_DELIMITER_ALPHA
                            } else {
                                1f
                            }
                        Box(modifier = Modifier.alpha(rowAlpha)) {
                            when (row) {
                                is ThreadRow.Delivered ->
                                    when (val item = row.item) {
                                        is ThreadItem.MessageItem -> MessageBubble(message = item.message)
                                        is ThreadItem.SessionBoundary ->
                                            SessionBoundaryDelimiter(boundary = item)
                                        is ThreadItem.UnrecognizedMessage ->
                                            UnrecognizedMessageRow(item = item)
                                    }
                                // One render path for both kinds of queued row — the one the echo
                                // correlated to and the one this device minted no echo for — so the
                                // two cannot drift apart. The id is bound here, so the row never
                                // holds one.
                                is ThreadRow.Queued ->
                                    QueuedMessageRow(
                                        text = row.text,
                                        onDrop = { onDropQueued(row.queuedMessageId) },
                                    )
                            }
                        }
                    }
                    // #777: under reverseLayout a later item takes a higher index and draws further up,
                    // so appending here puts the affordance at the oldest end for free. #778 widened it
                    // from one row to four states, but it is still ONE slot and one key — loading, a
                    // retry or a dead end, never two of them at once.
                    when (state.historyTail) {
                        ThreadHistoryTail.None -> Unit
                        ThreadHistoryTail.Loading -> item(key = HISTORY_TAIL_KEY) { HistoryLoadingRow() }
                        ThreadHistoryTail.Retry ->
                            item(key = HISTORY_TAIL_KEY) { HistoryRetryRow(onRetry = onRetryOlderHistory) }
                        ThreadHistoryTail.DeadEnd -> item(key = HISTORY_TAIL_KEY) { HistoryDeadEndRow() }
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
            choices = state.runConfig.choices,
            menuAvailable = state.runConfig.menuAvailable,
            // The producer's own cut plus this client's render cap, summed for display only — each keeps
            // its own field on the state so neither is ever recomputed from the other.
            notListedModels = state.runConfig.droppedModels + state.runConfig.hiddenChoices,
            selectedModel = state.runConfig.selectedModel,
            onModelSelected = { value ->
                onModelSelected(value)
                sheetVisible = false
            },
            effortChoices = state.runConfig.effortChoices,
            selectedEffort = state.runConfig.selectedEffort,
            onEffortSelected = { level ->
                onEffortSelected(level)
                sheetVisible = false
            },
            pending = state.runConfig.pending,
            // An empty session id means the daemon has no session to address, so the controls read only.
            enabled = state.runConfig.writable,
            yoloEnabled = state.yoloEnabled,
            onYoloToggled = onYoloToggled,
            onDismiss = { sheetVisible = false },
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
 * Figma `16:8`'s `Status area` (`111:3525`) — the composer's top band, carrying whichever of the three
 * conversation-level waiting signals is live (#643 moved this block here from the foot of the content
 * `Column`; the arms, their flags and their precedence are unchanged).
 *
 * One status slot; the retry status wins whenever it is active (#594), then compaction (#597). Both
 * signals are conversation-level and outlive the thinking phase, so each must show regardless of what
 * `turn_state` says — and no two may ever stack. Single-sourcing the mutual exclusion here, in the
 * screen, is deliberate: `isThinking` stays defined as the `turn_state` phase (other tests assert it
 * directly), so suppressing it at its source would make the VM's contract lie. api-retry keeps the top
 * arm because it is the "something is going wrong" signal while compaction is benign progress, so the
 * benign affordance must never mask the alarming one; the two overlapping has never been observed.
 *
 * The design's trailing contextual-action slot stays empty until #675 fills it, so nothing inert is
 * emitted beside the signal. When no signal is live every arm returns without emitting, so the band
 * contributes no node and the composer column's gap above the input field collapses with it.
 */
@Composable
private fun ThreadStatusArea(
    apiRetry: ApiRetryStatus,
    isCompacting: Boolean,
    isThinking: Boolean,
) {
    val slot = Modifier.fillMaxWidth().padding(horizontal = ComposerStatusGutter)
    when {
        apiRetry != ApiRetryStatus.NotRetrying -> ApiRetryIndicator(status = apiRetry, modifier = slot)
        isCompacting -> CompactingIndicator(isCompacting = true, modifier = slot)
        else -> ThinkingIndicator(isThinking = isThinking, modifier = slot)
    }
}

/**
 * The oldest-end "a history page is in flight" affordance (#777) — the one row in the thread
 * [LazyColumn] that is not a [ThreadItem].
 *
 * The Figma thread frame (16:8) carries no history-loading element, so this follows the app's shipped
 * Material 3 progress idiom instead: [de.pyryco.mobile.ui.conversations.components.ThinkingIndicator]'s
 * small indeterminate spinner beside a `bodySmall` / `onSurfaceVariant` label, itself the stand-in for a
 * frame the design has not yet drawn. Both strings are local resources with no interpolation — nothing
 * daemon-authored reaches the screen through this row.
 */
@Composable
private fun HistoryLoadingRow() {
    val description = stringResource(R.string.cd_thread_history_loading)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = HistoryLoadingGutter, vertical = HistoryLoadingVerticalPadding)
                .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HistoryLoadingLabelGap, Alignment.CenterHorizontally),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(HistoryLoadingSpinnerSize),
            strokeWidth = HistoryLoadingSpinnerStroke,
        )
        Text(
            text = stringResource(R.string.thread_history_loading_label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The oldest-end "that page failed, ask again" affordance (#778) — the retry state of the same single
 * slot [HistoryLoadingRow] occupies.
 *
 * The Figma thread frame (16:8) draws no history element, but it does draw one error-plus-action
 * affordance: the status-area "Pairing error - Re-pair" chip, an error-toned container with an emphasized
 * small label on a 6dp radius. This is that shape through its shipped Compose equivalent,
 * [de.pyryco.mobile.ui.conversations.components.ConnectionBanner]'s `errorContainer` /
 * `onErrorContainer` clickable surface, so the new state reads as the same family as the error
 * affordance the design already drew.
 *
 * Both strings are local resources with no interpolation. In particular the server-authored
 * `RelayErrorException.message` is never surfaced here — the reader is told the page failed, not what the
 * daemon called the failure.
 */
@Composable
private fun HistoryRetryRow(onRetry: () -> Unit) {
    val description = stringResource(R.string.cd_thread_history_retry)
    HistoryTailSurface {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    // Fully qualified: a bare `Role` here is the message-author Role already imported.
                    .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onRetry)
                    .padding(horizontal = HistoryLoadingGutter, vertical = HistoryTailRowPadding)
                    .semantics(mergeDescendants = true) { contentDescription = description },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HistoryLoadingLabelGap),
        ) {
            Text(
                text = stringResource(R.string.thread_history_retry_label),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.thread_history_retry_action),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * The oldest-end "earlier messages are out of reach" affordance (#778) — the same slot and the same
 * surface as [HistoryRetryRow] with nothing to press.
 *
 * A permanent failure is the one the contract marks non-retryable: the remaining `history.*` codes, an
 * unknown conversation id, a closed session, a malformed page. The reader sees that the log ends here
 * rather than silently believing they have reached the start of it, which is why this state is visible at
 * all; a button would be an affordance that cannot work.
 */
@Composable
private fun HistoryDeadEndRow() {
    val description = stringResource(R.string.cd_thread_history_dead_end)
    HistoryTailSurface {
        Text(
            text = stringResource(R.string.thread_history_dead_end_label),
            style = MaterialTheme.typography.bodySmall,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HistoryLoadingGutter, vertical = HistoryTailRowPadding)
                    .semantics(mergeDescendants = true) { contentDescription = description },
        )
    }
}

/** The shared error-toned surface behind both oldest-end failure rows (#778). */
@Composable
private fun HistoryTailSurface(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.small,
        content = content,
    )
}

/**
 * The permission/choice modal overlay (#446) — a separate-surface M3 dialog floating over the active
 * thread, **not** a row in the thread [LazyColumn]. Renders the verbatim [title][ModalUiState.Open.title],
 * [prompt][ModalUiState.Open.prompt], and [options][ModalUiState.Open.options] (in wire array order) and
 * highlights the producer's fail-safe-deny [defaultOptionId][ModalUiState.Open.defaultOptionId].
 *
 * Since #815 it is drawn in the shared mobile modal container ([MobileGateModal]): the server title fills
 * the header, the prompt and options fill the scroll area, and the footer carries only Cancel.
 *
 * Security (this slice owns the render-time obligations #445 deferred):
 * - **Inert output-encoding** — every server string renders through plain [Text] (literal, no
 *   markup/HTML/active content; never [de.pyryco.mobile.ui.conversations.components.MarkdownText], no
 *   `SelectionContainer` clipboard path) — the values may name a sensitive command or path.
 * - **Screen-capture hardening** and **tapjacking** (#452) — [MobileGateModal] sets `FLAG_SECURE` on the
 *   dialog's **own** window (the host carries none) and `filterTouchesWhenObscured` on it, a deterministic
 *   View-level net that is *different fabric* from the second-confirm UX belt (#451).
 * - **No persistence** — no modal-derived text reaches `rememberSaveable` / saved-instance state.
 *
 * Live in #452: [onOption] forwards every tapped option id verbatim — the VM decides arm-vs-send; the UI
 * never re-derives the arm. [armedOptionId] reflects the VM's armed non-default option (#451), drawing the
 * second-confirm affordance on that one option. [onCancel] is reached only via the explicit Cancel button;
 * the gate ignores back-press and outside taps and draws no close glyph (#446), so a permission gate never
 * reads a stray gesture as an implicit answer.
 */
@Composable
private fun PermissionModalOverlay(
    open: ModalUiState.Open,
    armedOptionId: String?,
    onOption: (String) -> Unit,
    onCancel: () -> Unit,
) {
    MobileGateModal(
        title = open.title,
        cancelLabel = stringResource(R.string.modal_cancel),
        onCancel = onCancel,
    ) {
        Text(text = open.prompt, style = MaterialTheme.typography.bodyLarge)
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
    // The shell's action geometry (#815): small shape and a 48 dp minimum target.
    val base = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    val modifier =
        when {
            isArmed -> base.semantics { stateDescription = armedDesc }
            isDefault -> base.semantics { stateDescription = defaultDesc }
            else -> base
        }
    val shape = MaterialTheme.shapes.small
    when {
        isArmed -> FilledTonalButton(onClick = onClick, modifier = modifier, shape = shape) { Text(label) }
        isDefault -> Button(onClick = onClick, modifier = modifier, shape = shape) { Text(label) }
        else ->
            OutlinedButton(
                onClick = onClick,
                modifier = modifier,
                shape = shape,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            ) { Text(label) }
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
        is ThreadItem.UnrecognizedMessage -> occurredAt
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
                ),
            armedOptionId = "allow_once",
            onOption = {},
            onCancel = {},
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
