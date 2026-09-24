package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.ModalUiState
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.repository.ApiRetryStatus
import de.pyryco.mobile.data.repository.ResetStatus
import de.pyryco.mobile.data.repository.ThinkingProgress
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.UsageLimitReading
import de.pyryco.mobile.ui.conversations.components.ApiRetryIndicator
import de.pyryco.mobile.ui.conversations.components.BannerNoticeRow
import de.pyryco.mobile.ui.conversations.components.ChannelInfoSheet
import de.pyryco.mobile.ui.conversations.components.ChannelInfoUiModel
import de.pyryco.mobile.ui.conversations.components.CompactingIndicator
import de.pyryco.mobile.ui.conversations.components.CompactionBoundaryDivider
import de.pyryco.mobile.ui.conversations.components.ConnectionBanner
import de.pyryco.mobile.ui.conversations.components.EmptyThreadState
import de.pyryco.mobile.ui.conversations.components.MessageBubble
import de.pyryco.mobile.ui.conversations.components.ModelRefusalRow
import de.pyryco.mobile.ui.conversations.components.OptionsOverlay
import de.pyryco.mobile.ui.conversations.components.QueuedMessageRow
import de.pyryco.mobile.ui.conversations.components.RenameDialog
import de.pyryco.mobile.ui.conversations.components.ResettingIndicator
import de.pyryco.mobile.ui.conversations.components.SaveAsChannelDialog
import de.pyryco.mobile.ui.conversations.components.SessionBoundaryDelimiter
import de.pyryco.mobile.ui.conversations.components.StatusSheet
import de.pyryco.mobile.ui.conversations.components.ThinkingIndicator
import de.pyryco.mobile.ui.conversations.components.TurnOutcomeIndicator
import de.pyryco.mobile.ui.conversations.components.TurnOutcomeReport
import de.pyryco.mobile.ui.conversations.components.UnrecognizedMessageRow
import de.pyryco.mobile.ui.conversations.components.UsageLimitIndicator
import de.pyryco.mobile.ui.conversations.components.WorkspaceChip
import de.pyryco.mobile.ui.conversations.components.WorkspacePicker
import de.pyryco.mobile.ui.conversations.components.formatRelativeTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

private const val ABOVE_DELIMITER_ALPHA = 0.55f

// Figma 16:8's `Input area` (533:1957) and its offsets inside the 412dp reference frame: a 20dp
// content gutter (372dp of content), 8dp between the area's three bands, 12dp of air above it where
// the message area ends, and 16dp below it at the frame's foot.
internal val ComposerGutter = 20.dp
private val ComposerSectionGap = 8.dp
private val ComposerTopGap = 12.dp
private val ComposerBottomGap = 16.dp

// The three status indicators each carry their own 16dp horizontal padding, sized for the full-bleed
// foot-of-list mount they had until #643. Inset them by the remainder so their content lands on the
// same 20dp gutter as the input field and the footer, with their own files untouched.
private val ComposerStatusGutter = ComposerGutter - 16.dp

// #843: Figma 354:7093's "Button small" and its gap from the status signal it sits beside.
private val StatusActionGap = 8.dp
private val RePairButtonRadius = 6.dp
private val RePairButtonHorizontalPadding = 16.dp
private val RePairButtonVerticalPadding = 8.dp

// #778: the ONE oldest-end slot. Loading, retry and dead-end share this key because they share the slot —
// at most one of them is ever emitted.
private const val HISTORY_TAIL_KEY = "history-tail"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ThreadScreen(
    state: ThreadUiState,
    onBack: () -> Unit,
    onSendMessage: (String) -> Unit,
    connectionState: ConnectionState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    isThinking: Boolean = false,
    apiRetry: ApiRetryStatus = ApiRetryStatus.NotRetrying, // #594: claude's API-retry status, replaces the spinner
    usageLimit: UsageLimitReading? = null, // #804: claude's usage-limit report, below api-retry in the slot
    resetting: ResetStatus? = null, // #872: Reset session's phase, below usage limit and above compaction
    isCompacting: Boolean = false, // #597: claude is auto-compacting its context, replaces the spinner
    turnOutcome: TurnOutcomeReport? = null, // #805: how the last turn failed or was interrupted, above thinking
    thinkingProgress: ThinkingProgress? = null, // #803: claude's live token reading, decorates the thinking arm
    isBusy: Boolean = false, // #459: a turn is in flight (thinking OR responding) → show the interrupt affordance
    onInterrupt: () -> Unit = {}, // #459: wired by MainActivity → vm::onInterrupt (the #458 send path)
    onTitleClick: () -> Unit = {},
    onOverflowEvent: (ThreadEvent) -> Unit = {},
    // #807: a published ModelMenuRow.value / effort level, forwarded verbatim — never a device enum.
    onModelSelected: (String) -> Unit = {},
    onEffortSelected: (String) -> Unit = {},
    // #650: a PermissionModeOption wire value from the footer's permission menu.
    onPermissionModeSelected: (String) -> Unit = {},
    // #884: a command row of the footer's Actions menu, wired by MainActivity → vm::onComposerCommand.
    // Reset session is not a command: it goes through onOverflowEvent(ThreadEvent.NewSession).
    onComposerCommand: (ComposerAction) -> Unit = {},
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
    // #818: whether the open prompt's "don't ask again this session" offer is accepted (VM-scoped to that
    // prompt), and its toggle, wired by MainActivity → vm::onAlwaysAllowChanged with the rendered modalId.
    alwaysAllowAccepted: Boolean = false,
    onAlwaysAllowChanged: (modalId: String, accepted: Boolean) -> Unit = { _, _ -> },
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
    // #843: this thread's host rejected the saved pairing (ThreadViewModel.rePairAvailable). Draws the
    // status area's Re-pair action and withholds the connection banner, whose retry cannot succeed then.
    // The tap is bound by MainActivity to the code-pair route keyed by the destination's own server id.
    showRePair: Boolean = false,
    onRePair: () -> Unit = {},
    // #933: this chat's pending attachments (ThreadViewModel.pendingAttachments) and whether a send carrying
    // them is under way (attachmentsSending); the picker's result, a tile's remove, and the one-shot refusal
    // notice. Bound by MainActivity; defaulted so screens that never attach render no strip.
    attachments: List<PendingAttachment> = emptyList(),
    attachmentsSending: Boolean = false,
    onAttachmentsPicked: (List<PickedAttachment>) -> Unit = {},
    onRemoveAttachment: (Long) -> Unit = {},
    attachmentRefusals: Flow<AttachmentRefusal> = emptyFlow(),
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
    // #544: surface a failed run-configuration change (model / effort / permission mode) as a transient snackbar. Same
    // payload-free (Unit) one-shot idiom; the fixed local string keeps the server-supplied
    // RelayErrorException.message out of the un-secured Activity window the snackbar draws in. The control
    // reverts in the ViewModel, so the sheet never settles on a value the daemon did not confirm.
    val sessionSettingsFailedMessage = stringResource(R.string.session_settings_failed)
    LaunchedEffect(sessionSettingsErrors, snackbarHostState) {
        sessionSettingsErrors.collect { snackbarHostState.showSnackbar(sessionSettingsFailedMessage) }
    }
    // #933: a pick with refused entries names how many, per reason — counts only, never a file name.
    val resources = LocalContext.current.resources
    LaunchedEffect(attachmentRefusals, snackbarHostState) {
        attachmentRefusals.collect { refusal ->
            if (refusal.tooLarge > 0) {
                val text = resources.getQuantityString(R.plurals.thread_attachments_too_large, refusal.tooLarge, refusal.tooLarge)
                snackbarHostState.showSnackbar(text)
            }
            if (refusal.tooMany > 0) {
                val text = resources.getQuantityString(R.plurals.thread_attachments_too_many, refusal.tooMany, refusal.tooMany)
                snackbarHostState.showSnackbar(text)
            }
        }
    }
    val openAttachmentPicker = rememberAttachmentPicker(onAttachmentsPicked)
    // #934: a pasted image joins the chat's strip through the same sink as a picked one.
    val onImagesPasted = rememberPastedImageReceiver(onAttachmentsPicked)
    // #808: the footer's open option overlay. Plain `remember`, keyed on the conversation, and never
    // `rememberSaveable`: a back-stack return or another conversation must open with every overlay
    // closed. The open menu is re-derived from the live run configuration on every pass, so the overlay
    // closes when the control stops offering anything (a write goes pending, a reading drops the menu).
    var openControl by remember(state.conversationId) { mutableStateOf<FooterControl?>(null) }
    // #678: the read-only background-task panel the Actions menu opens. Local and keyed like [openControl]:
    // closing it only flips this flag, so nothing is sent and no conversation or task changes.
    var backgroundTasksOpen by remember(state.conversationId) { mutableStateOf(false) }
    val footerAnchors = remember { mutableStateMapOf<FooterControl, Rect>() }
    var layerOrigin by remember { mutableStateOf(Offset.Zero) }
    val openMenu =
        openControl
            ?.takeIf { footerControlEnabled(it, state.runConfig) }
            ?.let { control ->
                footerMenu(
                    control,
                    state.runConfig,
                    state.mutationsSupported,
                    state.absentActions,
                    state.backgroundTaskCount,
                )?.let { control to it }
            }
    LaunchedEffect(openControl, openMenu == null) {
        if (openMenu == null) openControl = null
    }
    // #885: the input field's text-aligned window bounds, where the slash-command suggestions anchor.
    var inputAnchor by remember { mutableStateOf<Rect?>(null) }
    val imeVisible = WindowInsets.isImeVisible
    Box(
        modifier = modifier.onGloballyPositioned { layerOrigin = it.positionInWindow() },
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
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
                    // #897: the open tool call names itself in the thinking arm's slot, only while a turn runs.
                    val openTool = remember(state.items) { openToolCall(state.items) }
                    ThreadStatusArea(
                        apiRetry = apiRetry,
                        usageLimit = usageLimit,
                        resetting = resetting,
                        isCompacting = isCompacting,
                        turnOutcome = turnOutcome,
                        isThinking = isThinking,
                        thinkingProgress = thinkingProgress,
                        runningTool = if (isBusy) openTool else null,
                        showRePair = showRePair,
                        onRePair = onRePair,
                    )
                    // #933: Figma's `Attachment area`, between the status area and the input field, only when
                    // this chat has something pending.
                    if (attachments.isNotEmpty()) {
                        ComposerAttachmentStrip(
                            attachments = attachments,
                            sending = attachmentsSending,
                            onRemove = onRemoveAttachment,
                            modifier = Modifier.padding(horizontal = ComposerGutter),
                        )
                    }
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
                        onAnchorChanged = { inputAnchor = it },
                        hasAttachments = attachments.isNotEmpty(),
                        sending = attachmentsSending,
                        onImagesReceived = onImagesPasted,
                    )
                    // The design puts the model/effort controls in the footer, below the input field, not
                    // above it. Its own 16dp horizontal padding reproduces the footer frame's further `px-16`
                    // inside the 20dp content gutter applied here.
                    ThreadComposerFooter(
                        runConfig = state.runConfig,
                        onOpen = { openControl = it },
                        onStatusClick = { sheetVisible = true },
                        onAnchorChanged = { control, bounds -> footerAnchors[control] = bounds },
                        modifier = Modifier.padding(horizontal = ComposerGutter),
                        onAttach = openAttachmentPicker,
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
                // #843: a rejected pairing reads as Offline here, and its retry cannot succeed — the status
                // area's Re-pair action replaces it. Network loss still gets the banner and its retry.
                if (!showRePair) ConnectionBanner(state = connectionState, onRetry = onRetry)
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
                    // #896: a subagent's tool rows indent under the Agent/Task call that spawned them.
                    val toolDepths = remember(state.items) { toolNestingDepths(state.items) }
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
                                            is ThreadItem.MessageItem ->
                                                MessageBubble(
                                                    message = item.message,
                                                    toolNestingDepth = toolDepths[item.message.id] ?: 0,
                                                )
                                            is ThreadItem.SessionBoundary ->
                                                SessionBoundaryDelimiter(boundary = item)
                                            is ThreadItem.UnrecognizedMessage ->
                                                UnrecognizedMessageRow(item = item)
                                            is ThreadItem.Banner -> BannerNoticeRow(item = item)
                                            is ThreadItem.CompactionBoundary -> CompactionBoundaryDivider(item = item)
                                            is ThreadItem.ModelRefusal -> ModelRefusalRow(item = item)
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
        // #808: drawn over the Scaffold in this screen's own window, not in a Popup, so the input field
        // keeps its focus and the dismissing tap never reaches the composer. The anchor is read from the
        // footer button's live window bounds, so the overlay follows the IME lift.
        openMenu?.let { (control, menu) ->
            footerAnchors[control]?.let { anchor ->
                OptionsOverlay(
                    options = menu.options,
                    selectedValue = menu.selectedValue,
                    notListed = menu.notListed,
                    anchor = anchor.translate(-layerOrigin),
                    onSelect = { value ->
                        when (control) {
                            FooterControl.Model -> onModelSelected(value)
                            FooterControl.Effort -> onEffortSelected(value)
                            FooterControl.Permission -> onPermissionModeSelected(value)
                            // #884: Reset session is the overflow menu's own path; a command row sends.
                            FooterControl.Actions ->
                                when (val action = ComposerAction.fromValue(value)) {
                                    null -> Unit
                                    ComposerAction.ResetSession -> onOverflowEvent(ThreadEvent.NewSession)
                                    ComposerAction.BackgroundTasks -> backgroundTasksOpen = true
                                    else -> onComposerCommand(action)
                                }
                        }
                        openControl = null
                    },
                    onDismiss = { openControl = null },
                    actions = menu.actions,
                )
            }
        }
        // #885: the slash-command suggestions share the footer overlay's layer. They stand down while a
        // footer menu is open, so two overlays never stack. A pick completes the draft and sends nothing.
        SlashCommandTypeAhead(
            text = draft,
            commands = state.slashCommands,
            anchor = inputAnchor?.takeIf { openMenu == null }?.translate(-layerOrigin),
            imeVisible = imeVisible,
            onComplete = onDraftChange,
            resetKey = state.conversationId,
        )
    }
    if (backgroundTasksOpen) {
        BackgroundTaskPanel(roster = state.backgroundTasks, onDismiss = { backgroundTasksOpen = false })
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
        // #957: the thread stays open behind the modal; the failure strings are generic because the
        // shell announces them aloud, and no daemon message reaches this screen.
        SaveAsChannelDialog(
            conversationId = state.conversationId,
            initialName = dialogState.initialName,
            onSubmit = { name, systemPrompt ->
                onOverflowEvent(
                    ThreadEvent.SaveAsChannelSubmit(name = name, systemPrompt = systemPrompt),
                )
            },
            onDismissRequest = { onOverflowEvent(ThreadEvent.SaveAsChannelDismiss) },
            nameEditable = !dialogState.promoted,
            loading = dialogState.saving,
            error =
                when (dialogState.failure) {
                    SaveAsChannelFailure.Promote -> stringResource(R.string.save_as_channel_failed)
                    SaveAsChannelFailure.SystemPrompt -> stringResource(R.string.save_as_channel_prompt_failed)
                    null -> null
                },
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
            onDismiss = { sheetVisible = false },
            effortNote = state.runConfig.effortNote?.let { stringResource(it.textRes()) },
            running = state.runConfig.running,
            contextPercent = state.runConfig.contextPercent,
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
    // Permission/choice modal overlay (#446). Hoisted single source = ThreadViewModel.currentModal (#445),
    // already scoped to this thread's conversation (#816): another conversation's modal arrives as Hidden.
    // Open → separate-surface overlay; Dismissed → surface the resolution reason once and remove the overlay.
    when (modalState) {
        is ModalUiState.Open ->
            PermissionModalOverlay(
                open = modalState,
                armedOptionId = armedOptionId,
                onOption = onModalOption,
                onCancel = onModalCancel,
                alwaysAllowAccepted = alwaysAllowAccepted,
                onAlwaysAllowChanged = onAlwaysAllowChanged,
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
 * claude's usage-limit report (#804) sits between them — api-retry → usage limit → compaction → thinking —
 * for the same reason: compaction is benign and must never mask a report that something may be going
 * wrong. The arm is raised by a non-`null` reading alone; the expiry and the benign clear are already
 * applied upstream, so nothing here re-reads its fields.
 *
 * A failed or interrupted turn's outcome (#805) sits directly above thinking — api-retry → usage limit →
 * compaction → turn outcome → thinking. Compaction is mid-turn and the outcome is post-turn, so the two
 * co-occurring has not been observed; the outcome clears when the next turn starts.
 *
 * A running Reset session's phase (#872) sits between usage limit and compaction — the full ladder, top
 * wins: api-retry → usage limit → resetting → compaction → turn outcome → thinking. It stays below the two
 * "something may be wrong" signals so it never hides them. It sits above compaction and thinking because
 * the wrap-up is itself a claude turn — without this ordering the reset the user started would read as
 * generic thinking, or as a compaction inside it — and above a turn outcome lingering from before the
 * reset. A phase change replaces the reading in this one arm; the falling edge and the session transition
 * clear it upstream.
 *
 * The design's trailing contextual-action slot holds the Re-pair action (#843) while [showRePair] does,
 * and is otherwise absent, so nothing inert is emitted beside the signal. Without it, when no signal is
 * live every arm returns without emitting, so the band contributes no node and the composer column's gap
 * above the input field collapses with it.
 *
 * [thinkingProgress] (#803) adds **no arm**: it decorates the thinking arm's label and rides the `else`
 * branch, so retry and compaction pre-empt a live reading for free and the mutual exclusion above is
 * unchanged. Visibility stays governed by [isThinking] alone — `turn_state` owns the thinking phase
 * (#406), and letting a reading raise the arm on its own would be a fourth arm wearing the third one's
 * name.
 *
 * [runningTool] (#897) rides the same `else` branch and does raise it: a tool claude is running during
 * the `responding` phase is exactly the signal the band otherwise lacks. The screen passes it only while
 * the turn is busy, so every arm above still pre-empts it and a closed call drops the band back to what
 * it would otherwise show.
 */
@Composable
private fun ThreadStatusArea(
    apiRetry: ApiRetryStatus,
    usageLimit: UsageLimitReading?,
    resetting: ResetStatus?,
    isCompacting: Boolean,
    turnOutcome: TurnOutcomeReport?,
    isThinking: Boolean,
    thinkingProgress: ThinkingProgress?,
    runningTool: ToolCall?,
    showRePair: Boolean = false,
    onRePair: () -> Unit = {},
) {
    val gutter = Modifier.fillMaxWidth().padding(horizontal = ComposerStatusGutter)
    val signal: @Composable (Modifier) -> Unit = { slot ->
        when {
            apiRetry != ApiRetryStatus.NotRetrying -> ApiRetryIndicator(status = apiRetry, modifier = slot)
            usageLimit != null -> UsageLimitIndicator(reading = usageLimit, modifier = slot)
            resetting != null -> ResettingIndicator(status = resetting, modifier = slot)
            isCompacting -> CompactingIndicator(isCompacting = true, modifier = slot)
            turnOutcome != null -> TurnOutcomeIndicator(report = turnOutcome, modifier = slot)
            else ->
                ThinkingIndicator(
                    isThinking = isThinking,
                    modifier = slot,
                    progress = thinkingProgress,
                    runningTool = runningTool,
                )
        }
    }
    if (!showRePair) {
        signal(gutter)
        return
    }
    // Figma 111:3525: the signal leading, the action trailing on the same row.
    Row(
        modifier = gutter,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StatusActionGap),
    ) {
        Box(modifier = Modifier.weight(1f)) { signal(Modifier.fillMaxWidth()) }
        RePairButton(onClick = onRePair)
    }
}

/**
 * Figma `354:7093`'s "Pairing error - Re-pair" small button (#843): a 6dp-radius container, 16/8 padding,
 * a `bodySmall` label at medium weight (`M3/body/small-emphasized`).
 *
 * Deliberate deviation: the frame paints it `on-error` / `error`; this uses the theme's `errorContainer` /
 * `onErrorContainer` pair instead, the family [ConnectionBanner]'s Offline arm and [HistoryRetryRow] already
 * use for an error-plus-action affordance, as the ticket directs. The label is a local resource, never
 * daemon text.
 */
@Composable
private fun RePairButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(RePairButtonRadius),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Text(
            text = stringResource(R.string.thread_re_pair),
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            modifier = Modifier.padding(horizontal = RePairButtonHorizontalPadding, vertical = RePairButtonVerticalPadding),
        )
    }
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
        is ThreadItem.Banner -> occurredAt
        is ThreadItem.CompactionBoundary -> occurredAt
        is ThreadItem.ModelRefusal -> occurredAt
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

/**
 * The thread's open tool call (#897): the latest row in [items] whose call is still
 * [ToolCallStatus.Running], or `null`. Denied, done and failed rows are not open. One call supplies both
 * the name and the elapsed reading the status area shows, so the two can never come from different calls,
 * and a newer open call replaces an older one because it sits later in the chronological list.
 */
internal fun openToolCall(items: List<ThreadItem>): ToolCall? =
    items
        .lastOrNull { item ->
            item is ThreadItem.MessageItem && item.message.toolCall?.status == ToolCallStatus.Running
        }.let { (it as? ThreadItem.MessageItem)?.message?.toolCall }
