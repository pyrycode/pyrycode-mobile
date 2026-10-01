package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.model.ModalUiState
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.repository.ApiRetryStatus
import de.pyryco.mobile.data.repository.ResetStatus
import de.pyryco.mobile.data.repository.ThinkingProgress
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.UsageLimitReading
import de.pyryco.mobile.ui.conversations.components.ApiRetryIndicator
import de.pyryco.mobile.ui.conversations.components.AttachmentViewState
import de.pyryco.mobile.ui.conversations.components.BannerNoticeRow
import de.pyryco.mobile.ui.conversations.components.ChannelInfoSheet
import de.pyryco.mobile.ui.conversations.components.ChannelInfoUiModel
import de.pyryco.mobile.ui.conversations.components.CompactingIndicator
import de.pyryco.mobile.ui.conversations.components.CompactionBoundaryDivider
import de.pyryco.mobile.ui.conversations.components.ConnectionStatusIndicator
import de.pyryco.mobile.ui.conversations.components.EmptyThreadState
import de.pyryco.mobile.ui.conversations.components.MEMORY_PLUGIN_DOCS_URL
import de.pyryco.mobile.ui.conversations.components.MessageBubble
import de.pyryco.mobile.ui.conversations.components.ModelRefusalRow
import de.pyryco.mobile.ui.conversations.components.NoticePill
import de.pyryco.mobile.ui.conversations.components.OptionsOverlay
import de.pyryco.mobile.ui.conversations.components.QueuedMessageRow
import de.pyryco.mobile.ui.conversations.components.RenameDialog
import de.pyryco.mobile.ui.conversations.components.ResettingIndicator
import de.pyryco.mobile.ui.conversations.components.SaveAsChannelDialog
import de.pyryco.mobile.ui.conversations.components.SessionBoundaryDelimiter
import de.pyryco.mobile.ui.conversations.components.StatusSheet
import de.pyryco.mobile.ui.conversations.components.SwitchBackOffer
import de.pyryco.mobile.ui.conversations.components.ThinkingIndicator
import de.pyryco.mobile.ui.conversations.components.TurnOutcomeIndicator
import de.pyryco.mobile.ui.conversations.components.TurnOutcomeReport
import de.pyryco.mobile.ui.conversations.components.UnrecognizedMessageRow
import de.pyryco.mobile.ui.conversations.components.WorkspacePicker
import de.pyryco.mobile.ui.conversations.components.formatRelativeTime
import de.pyryco.mobile.ui.theme.threadColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
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
private val AttachmentStripTouchOverlap = 5.dp

// The footer's 32dp boxes start below the input gap. Compose expands them to 48dp, using that gap
// without entering the input surface. The visible controls stay in their 20dp design band.
private val FooterTouchBottomOverflow = 12.dp
private val FrameFooterTouchHeight = 32.dp

// The three status indicators each carry their own 16dp horizontal padding, sized for the full-bleed
// foot-of-list mount they had until #643. Inset them by the remainder so their content lands on the
// same 20dp gutter as the input field and the footer, with their own files untouched.
private val ComposerStatusGutter = ComposerGutter - 16.dp

// Figma's top overlay shares the message area's top edge.
private val TopOverlayTopGap = 0.dp
private val FrameGlowRadius = 480.dp
private const val FRAME_GLOW_STOP = 0.76012f
private const val FRAME_SCRIM_ALPHA = 0.30f

// Reserve the design's visible band height while allowing an existing control's touch area to extend
// into the adjacent gap. Neither extension reaches the next visible control.
private fun Modifier.frameHeightWithTouchOverflow(
    top: Dp = 0.dp,
    bottom: Dp = 0.dp,
): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val topPx = top.roundToPx()
        val bottomPx = bottom.roundToPx()
        layout(placeable.width, (placeable.height - topPx - bottomPx).coerceAtLeast(0)) {
            placeable.placeRelative(0, -topPx)
        }
    }

// #778: the ONE oldest-end slot. Loading, retry and dead-end share this key because they share the slot —
// at most one of them is ever emitted.
private const val HISTORY_TAIL_KEY = "history-tail"

// #1306: the inline permission request's lazy items — Cancel, card and title.
private const val PERMISSION_ROW_COUNT = 3

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ThreadScreen(
    state: ThreadUiState,
    onBack: () -> Unit,
    onSendMessage: (String) -> Unit,
    connectionState: ConnectionState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    questionState: QuestionModalState? = null,
    onQuestionEvent: (QuestionModalEvent, Long) -> Unit = { _, _ -> },
    isThinking: Boolean = false,
    apiRetry: ApiRetryStatus = ApiRetryStatus.NotRetrying, // #594: claude's API-retry status, replaces the spinner
    usageLimit: UsageLimitReading? = null, // #804: claude's usage-limit report; #1002 draws it in the Top overlay
    resetting: ResetStatus? = null, // #872: Reset session's phase, below usage limit and above compaction
    isCompacting: Boolean = false, // #597: claude is auto-compacting its context, replaces the spinner
    turnOutcome: TurnOutcomeReport? = null, // #805: how the last turn failed or was interrupted, above thinking
    thinkingProgress: ThinkingProgress? = null, // #803: claude's live token reading, decorates the thinking arm
    isBusy: Boolean = false, // #459: a turn is in flight (thinking OR responding) → show the interrupt affordance
    isStalled: Boolean = false, // #1311: the daemon reported a stall; the band's stall arm
    localSendPending: Boolean = false, // #1311: a send is with the daemon, which has not spoken yet → "Thinking…"
    onInterrupt: () -> Unit = {}, // #459: wired by MainActivity → vm::onInterrupt (the #458 send path)
    onTitleClick: () -> Unit = {},
    onOverflowEvent: (ThreadEvent) -> Unit = {},
    // #807: a published ModelMenuRow.value / effort level, forwarded verbatim — never a device enum.
    onModelSelected: (String) -> Unit = {},
    // #1360: the switch-back offer, drawn on the refusal row that armed it, and its tap.
    switchBackOffer: SwitchBackOffer? = null,
    onSwitchBack: () -> Unit = {},
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
    // #452, #1306: wired by MainActivity → vm.onModalOption / vm.onModalCancel with the rendered request's id,
    // so a tap composed before a replacement cannot reach the replacement.
    onModalOption: (modalId: String, optionId: String) -> Unit = { _, _ -> },
    onModalCancel: (modalId: String) -> Unit = {},
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
    // Top overlay's pairing pill (#1002) and withholds the connection banner, whose retry cannot succeed then.
    // The tap is bound by MainActivity to the code-pair route keyed by the destination's own server id.
    showRePair: Boolean = false,
    onRePair: () -> Unit = {},
    // #1002: the usage readings hidden from the Top overlay (the app-scoped UsageLimitDismissals) and the X's
    // tap, which hides the reading the pill is showing. Defaulted so screens that never dismiss show every one.
    dismissedUsageLimits: Set<UsageLimitDismissals.Key> = emptySet(),
    onDismissUsageLimit: (UsageLimitReading) -> Unit = {},
    // #933: this chat's pending attachments (ThreadViewModel.pendingAttachments) and whether a send carrying
    // them is under way (attachmentsSending); the picker's result, a tile's remove, and the one-shot refusal
    // notice. Bound by MainActivity; defaulted so screens that never attach render no strip.
    attachments: List<PendingAttachment> = emptyList(),
    attachmentsSending: Boolean = false,
    // #1327: the running upload's figure (ThreadViewModel.attachmentUploadProgress), drawn on its tile.
    attachmentUploadProgress: AttachmentUploadProgress? = null,
    onAttachmentsPicked: (List<PickedAttachment>) -> Unit = {},
    onRemoveAttachment: (Long) -> Unit = {},
    attachmentRefusals: Flow<AttachmentRefusal> = emptyFlow(),
    // #1325: the one-shot notice of why a send stopped at a file (ThreadViewModel.attachmentSendFailures).
    attachmentSendFailures: Flow<AttachmentSendFailure> = emptyFlow(),
    // #984: each message attachment's state by id (ThreadViewModel.attachmentStates), the report that one's
    // row is on screen, and a failed one's retry. Bound by MainActivity; defaulted so other screens and tests
    // draw attachments as loading and start nothing.
    attachmentStates: Map<String, AttachmentViewState> = emptyMap(),
    onAttachmentShown: (String) -> Unit = {},
    onRetryAttachment: (String) -> Unit = {},
    // #1027: a ready markdown attachment's tap, and the one-shot signal that it could not be read. Bound by
    // MainActivity → vm::onOpenMarkdownAttachment / vm.markdownOpenFailures.
    onOpenMarkdownAttachment: (String) -> Unit = {},
    markdownOpenFailures: Flow<Unit> = emptyFlow(),
    // #1050: a tapped link to a workspace markdown note in an assistant reply, by its path. Bound by
    // MainActivity → vm::onOpenMarkdownLink; a failed read reuses [markdownOpenFailures].
    onOpenMarkdownLink: (String) -> Unit = {},
) {
    var sheetVisible by rememberSaveable { mutableStateOf(false) }
    var overflowExpanded by rememberSaveable { mutableStateOf(false) }
    val openRequest = modalState as? ModalUiState.Open
    // #1306: one call site for both prompt kinds, so a question → permission hand-over keeps one owner.
    if (questionState != null || openRequest != null) QuestionPromptProtection()
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
    // #1325: a send that stopped at a file says why in one fixed sentence — never a name or the daemon's code.
    LaunchedEffect(attachmentSendFailures, snackbarHostState) {
        attachmentSendFailures.collect { failure -> snackbarHostState.showSnackbar(failure.text(resources)) }
    }
    val openAttachmentPicker = rememberAttachmentPicker(onAttachmentsPicked)
    // #985: a ready message attachment opens in another app or saves to a picked document; each outcome the
    // user should hear about is one static sentence, never a name, URI or path.
    val noticeScope = rememberCoroutineScope()
    val attachmentActions =
        rememberAttachmentActions(attachmentStates, onOpenMarkdownAttachment) { notice ->
            noticeScope.launch { snackbarHostState.showSnackbar(resources.getString(notice.message)) }
        }
    // #1027: a markdown file that cannot be read says what any failed open says.
    LaunchedEffect(markdownOpenFailures, snackbarHostState) {
        markdownOpenFailures.collect { snackbarHostState.showSnackbar(resources.getString(AttachmentNotice.OPEN_FAILED.message)) }
    }
    // #934: a pasted image joins the chat's strip through the same sink as a picked one.
    val onImagesPasted = rememberPastedImageReceiver(onAttachmentsPicked)
    // #808: the footer's open option overlay. Plain `remember`, keyed on the conversation, and never
    // `rememberSaveable`: a back-stack return or another conversation must open with every overlay
    // closed. The open menu is re-derived from the live run configuration on every pass, so the overlay
    // closes when the control stops offering anything (a write goes pending, a reading drops the menu).
    // #1319: Send, Stop, Actions and the run settings wait for the host's handshake, as on desktop.
    // #1321: so do the inline permission and question answers.
    val connected = connectionState == ConnectionState.Connected
    var openControl by remember(state.conversationId) { mutableStateOf<FooterControl?>(null) }
    // #678: the read-only background-task panel the Actions menu opens. Local and keyed like [openControl]:
    // closing it only flips this flag, so nothing is sent and no conversation or task changes.
    var backgroundTasksOpen by remember(state.conversationId) { mutableStateOf(false) }
    val footerAnchors = remember { mutableStateMapOf<FooterControl, Rect>() }
    var layerOrigin by remember { mutableStateOf(Offset.Zero) }
    val openMenu =
        openControl
            ?.takeIf { footerControlEnabled(it, state.runConfig, connected) }
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
    val scheme = MaterialTheme.colorScheme
    val frameColors = scheme.threadColors
    Box(
        modifier =
            modifier
                .drawWithCache {
                    val brush =
                        frameColors.glow?.let { glow ->
                            Brush.radialGradient(
                                0f to glow,
                                (FRAME_GLOW_STOP / 2f) to lerp(glow, scheme.onPrimary, 0.5f).copy(alpha = 0.5f),
                                FRAME_GLOW_STOP to scheme.onPrimary.copy(alpha = 0f),
                                center = Offset(size.width * (196f / 412f), 265.dp.toPx()),
                                radius = FrameGlowRadius.toPx(),
                            )
                        }
                    onDrawBehind {
                        if (brush == null) {
                            drawRect(frameColors.background)
                        } else {
                            drawRect(scheme.surface)
                            drawRect(brush)
                            drawRect(scheme.scrim.copy(alpha = FRAME_SCRIM_ALPHA))
                        }
                    }
                }.onGloballyPositioned { layerOrigin = it.positionInWindow() },
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = if (frameColors.glow == null) frameColors.background else Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
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
                    memorySearch = state.runConfig.memorySearch,
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
                            .background(MaterialTheme.colorScheme.threadColors.surface)
                            .imePadding()
                            .padding(top = ComposerTopGap, bottom = ComposerBottomGap),
                    verticalArrangement = Arrangement.spacedBy(ComposerSectionGap),
                ) {
                    // #897: the open tool call names itself in the thinking arm's slot, only while a turn runs.
                    val openTool = remember(state.items) { openToolCall(state.items) }
                    ThreadStatusArea(
                        apiRetry = apiRetry,
                        resetting = resetting,
                        isCompacting = isCompacting,
                        isStalled = isStalled,
                        turnOutcome = turnOutcome,
                        isThinking = isThinking,
                        isBusy = isBusy,
                        localSendPending = localSendPending,
                        thinkingProgress = thinkingProgress,
                        runningTool = if (isBusy) openTool else null,
                        waitingForAnswers = questionState != null && connectionState == ConnectionState.Connected,
                        connectionState = connectionState,
                        taskCount = state.backgroundTaskCount,
                        onTasksClick = { backgroundTasksOpen = true },
                        agent = state.agent,
                    )
                    // #933: Figma's `Attachment area`, between the status area and the input field, only when
                    // this chat has something pending.
                    if (attachments.isNotEmpty()) {
                        ComposerAttachmentStrip(
                            attachments = attachments,
                            sending = attachmentsSending,
                            onRemove = onRemoveAttachment,
                            uploadProgress = attachmentUploadProgress,
                            modifier =
                                Modifier
                                    .padding(horizontal = ComposerGutter)
                                    .frameHeightWithTouchOverflow(top = AttachmentStripTouchOverlap),
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
                        sending = attachmentsSending,
                        onImagesReceived = onImagesPasted,
                        enabled = connected,
                    )
                    // The design puts the model/effort controls in the footer, below the input field, not
                    // above it. Its own 16dp horizontal padding reproduces the footer frame's further `px-16`
                    // inside the 20dp content gutter applied here.
                    ThreadComposerFooter(
                        runConfig = state.runConfig,
                        onOpen = { openControl = it },
                        onStatusClick = {
                            sheetVisible = true
                            onOverflowEvent(ThreadEvent.RunConfigOpen)
                        },
                        onAnchorChanged = { control, bounds -> footerAnchors[control] = bounds },
                        modifier =
                            Modifier
                                .padding(horizontal = ComposerGutter)
                                .frameHeightWithTouchOverflow(bottom = FooterTouchBottomOverflow),
                        onAttach = openAttachmentPicker,
                        agent = state.agent,
                        touchHeight = FrameFooterTouchHeight,
                        contentBottomPadding = FooterTouchBottomOverflow,
                        connected = connected,
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
                Spacer(Modifier.height(12.dp))
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
                // #1002: the message area, with the Top overlay pinned over its top edge while the messages
                // scroll beneath it.
                Box(modifier = Modifier.fillMaxWidth().weight(1f).testTag("thread-message-region")) {
                    if (!state.hasMessages && state.queuedMessages.isEmpty() && questionState == null && openRequest == null) {
                        EmptyThreadState(
                            modifier =
                                Modifier
                                    .fillMaxSize()
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
                        val promptRowCount =
                            (questionState?.let { it.batch.questions.size + 2 } ?: 0) +
                                (if (openRequest != null) PERMISSION_ROW_COUNT else 0)
                        val historyRowCount by rememberUpdatedState(rows.size + promptRowCount)
                        val hasHistoryRows by rememberUpdatedState(rows.isNotEmpty())
                        val demandOlderHistory by rememberUpdatedState(onDemandOlderHistory)
                        LaunchedEffect(listState) {
                            snapshotFlow {
                                val oldestVisible =
                                    listState.layoutInfo.visibleItemsInfo
                                        .lastOrNull()
                                        ?.index ?: -1
                                hasHistoryRows && oldestVisible >= historyRowCount - 1
                            }.distinctUntilChanged()
                                .collect { atOldestRow -> if (atOldestRow) demandOlderHistory() }
                        }
                        val promptPresent = questionState != null || openRequest != null
                        LaunchedEffect(hasStreamingMessage, promptPresent, listState) {
                            if (!hasStreamingMessage || promptPresent) return@LaunchedEffect
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
                        // #981: the list keeps its first visible row anchored by key, so under reverseLayout a new
                        // newest row lands at index 0 below the viewport. The streaming pin above only covers a
                        // row that is still streaming when it collects; a reply that arrives whole, a tool row or
                        // the operator's own echo needs this one. A streaming row that grows keeps its key and is
                        // left to the pin. drop(1) skips the first value, because userScrolledAway is not saved
                        // and a recreation must not pull a reader who had scrolled away back to the newest end.
                        val newestRowKey by rememberUpdatedState(rows.lastOrNull()?.listKey(rows.lastIndex))
                        LaunchedEffect(listState) {
                            snapshotFlow { newestRowKey }
                                .drop(1)
                                .collect {
                                    if (!userScrolledAway) {
                                        // A finger resting at the newest end holds the list at UserInput priority,
                                        // which refuses this scroll with a CancellationException. Unlike the
                                        // streaming pin, this effect never relaunches, so the refusal costs this one
                                        // scroll only; a real cancellation of the effect still ends it.
                                        try {
                                            listState.scrollToItem(0)
                                        } catch (e: CancellationException) {
                                            ensureActive()
                                        }
                                    }
                                }
                        }
                        // #1305: prompt rows insert at index 0 below the anchored newest row, so a batch arriving
                        // while the reader sits at the newest end would land offscreen. Reveal it from its
                        // actions upward, but only for that reader: userScrolledAway misses a programmatic scroll
                        // into history, so the newest row must also still be the first visible item. drop(1)
                        // keeps a recreation from moving a restored position, as in the #981 effect. #1306: a
                        // permission request inserts at the same end, so either prompt's new identity reveals.
                        val promptIdentity by rememberUpdatedState(questionState?.generation to openRequest?.modalId)
                        LaunchedEffect(listState) {
                            snapshotFlow { promptIdentity }
                                .drop(1)
                                .filter { (generation, modalId) -> generation != null || modalId != null }
                                .collect {
                                    val first = listState.layoutInfo.visibleItemsInfo.firstOrNull()
                                    val atNewestEnd =
                                        listState.firstVisibleItemScrollOffset == 0 &&
                                            (listState.firstVisibleItemIndex == 0 || first?.key == newestRowKey)
                                    if (!userScrolledAway && atNewestEnd) {
                                        try {
                                            listState.scrollToItem(0)
                                        } catch (e: CancellationException) {
                                            ensureActive()
                                        }
                                    }
                                }
                        }
                        LazyColumn(
                            state = listState,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .nestedScroll(autoScrollNestedScroll),
                            reverseLayout = true,
                        ) {
                            openRequest?.let { open ->
                                permissionRequestItems(
                                    open = open,
                                    armedOptionId = armedOptionId,
                                    connected = connected,
                                    onOption = onModalOption,
                                    onCancel = onModalCancel,
                                    alwaysAllowAccepted = alwaysAllowAccepted,
                                    onAlwaysAllowChanged = onAlwaysAllowChanged,
                                    gutter = Modifier.fillMaxWidth().padding(horizontal = ComposerGutter, vertical = 4.dp),
                                )
                            }
                            questionState?.let { pending ->
                                val dispatch: (QuestionModalEvent) -> Unit = { onQuestionEvent(it, pending.generation) }
                                val gutter = Modifier.fillMaxWidth().padding(horizontal = ComposerGutter, vertical = 4.dp)
                                item(key = "question-actions:${pending.generation}") {
                                    Box(gutter) { QuestionBatchActions(pending, connected, dispatch) }
                                }
                                items(pending.batch.questions.size, key = { "question:${pending.generation}:$it" }) { reversedIndex ->
                                    val index = pending.batch.questions.lastIndex - reversedIndex
                                    Box(gutter.testTag("thread-question-row")) {
                                        QuestionBlock(
                                            index,
                                            pending.batch.questions[index],
                                            pending.selections[index],
                                            !pending.locked,
                                            dispatch,
                                        )
                                    }
                                }
                                item(key = "question-title:${pending.generation}") {
                                    Box(gutter) { QuestionBatchTitle(pending) }
                                }
                            }
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
                                                        attachmentStates = attachmentStates,
                                                        onAttachmentShown = onAttachmentShown,
                                                        onRetryAttachment = onRetryAttachment,
                                                        onOpenAttachment = attachmentActions.open,
                                                        onSaveAttachment = attachmentActions.save,
                                                        onOpenMarkdownLink = onOpenMarkdownLink,
                                                    )
                                                is ThreadItem.SessionBoundary ->
                                                    SessionBoundaryDelimiter(
                                                        boundary = item,
                                                        agent = state.agent,
                                                        memorySearch = state.runConfig.memorySearch,
                                                    )
                                                is ThreadItem.UnrecognizedMessage ->
                                                    UnrecognizedMessageRow(item = item)
                                                is ThreadItem.Banner -> BannerNoticeRow(item = item, agent = state.agent)
                                                is ThreadItem.CompactionBoundary -> CompactionBoundaryDivider(item = item)
                                                is ThreadItem.ModelRefusal ->
                                                    ModelRefusalRow(
                                                        item = item,
                                                        agent = state.agent,
                                                        switchBack = switchBackOffer?.takeIf { it.armedBy(item) },
                                                        onSwitchBack = onSwitchBack,
                                                    )
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
                    ThreadTopOverlay(
                        usageLimit = usageLimit,
                        usageLimitDismissed = usageLimit?.dismissalKey() in dismissedUsageLimits,
                        onDismissUsageLimit = { usageLimit?.let(onDismissUsageLimit) },
                        showRePair = showRePair,
                        onRePair = onRePair,
                        connectionState = connectionState,
                        onRetryConnection = onRetry,
                        modifier =
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(start = ComposerGutter, top = TopOverlayTopGap, end = ComposerGutter),
                        agent = state.agent,
                    )
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
            selectedModel = state.runConfig.selectedChoice?.value,
            modelSelectionNote = state.runConfig.modelSelectionNote,
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
            // So does a host that is not connected (#1319).
            enabled = state.runConfig.writable && connected,
            onDismiss = { sheetVisible = false },
            effortNote = state.runConfig.effortNote?.text(state.agent),
            running = state.runConfig.running,
            contextPercent = state.runConfig.contextPercent,
            permissionMode = state.runConfig.permissionMode,
            permissionChoices =
                PermissionModeOption.entries
                    .filter { state.runConfig.offersPermission(it) }
                    .map { it.wire to it.label },
            onPermissionSelected = { value ->
                onPermissionModeSelected(value)
                sheetVisible = false
            },
            permissionPending = state.runConfig.pendingPermission != null,
        )
    }
    if (state.channelInfoOpen) {
        val uriHandler = LocalUriHandler.current
        ChannelInfoSheet(
            model = state.toChannelInfoUiModel(),
            mutationsSupported = state.mutationsSupported,
            onRename = {
                onOverflowEvent(ThreadEvent.Rename)
                onOverflowEvent(ThreadEvent.ChannelInfoDismiss)
            },
            onArchive = { onOverflowEvent(ThreadEvent.Archive) },
            onDelete = { onOverflowEvent(ThreadEvent.Delete) },
            onInstallMemoryPlugin = { uriHandler.openUri(MEMORY_PLUGIN_DOCS_URL) },
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
    // Permission/choice request (#446). Hoisted single source = ThreadViewModel.currentModal (#445), already
    // scoped to this thread's conversation (#816): another conversation's modal arrives as Hidden. Since #1306
    // Open renders inside the message list above; Dismissed surfaces the resolution reason once.
    when (modalState) {
        is ModalUiState.Open -> Unit
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
 * Figma `16:8`'s `Status area` (`111:3525`) — the composer's top band, carrying whichever live turn-status
 * signal is current (#643 moved this block here from the foot of the content `Column`).
 *
 * One status slot, top wins, decided by [statusArm] (#1311): connecting / reconnecting → resetting →
 * api-retry → compaction → stall → turn outcome → thinking / working / running tool. While a turn runs the
 * band always has a reading, as desktop's `workingIndicatorState` keeps one up. While the link is
 * unavailable, turn readings cannot be refreshed; Offline is instead shown in the Top overlay as a retry
 * pill.
 * No two may ever stack. Single-sourcing the mutual exclusion here, in the screen, is deliberate:
 * `isThinking` stays defined as the `turn_state` phase (other tests assert it directly), so suppressing it
 * at its source would make the VM's contract lie.
 *
 * A running Reset session's phase (#872) is the top turn arm, above api-retry since #1311 as on desktop:
 * the wrap-up is itself a claude turn, so without this ordering the reset the user started would read as
 * generic thinking or as a compaction inside it, and it outranks a turn outcome lingering from before the
 * reset. api-retry (#594) is the "something is going wrong" signal, and the benign affordances below must
 * never mask it. A stall (#395, #1311) is client-owned copy in the error colour; it clears on the next live
 * event through `StallProjection`, and outranks every reading of the running turn. A phase
 * change replaces the reading in this one arm; the falling edge and the session transition clear it
 * upstream. Compaction (#597) is mid-turn and outlives the thinking phase. A failed or interrupted turn's
 * outcome (#805) is post-turn and clears when the next turn starts.
 *
 * Notices are not turn status and are not here: claude's usage-limit report and the pairing error draw as
 * pills in the message area's [ThreadTopOverlay] (#1002), so a live reading never hides the running tool,
 * the wrap-up or "interrupted". When no signal is live every arm returns without emitting, so the band
 * contributes no node and the composer column's gap above the input field collapses with it.
 *
 * [thinkingProgress] (#803) adds **no arm**: it decorates the daemon's thinking phase only, so every arm
 * above pre-empts a live reading for free and the local-send window never shows a stale one.
 *
 * [runningTool] (#897) names the open call while the turn is busy; closing it drops the band back to
 * "Working…" or "Thinking…" (#1311), never to nothing while [isBusy] holds.
 *
 * [taskCount] (#1043) is not an arm either: above zero, a pill reading it sits at the band's right end
 * beside whichever reading shows, or alone, and [onTasksClick] opens the background-task panel. At zero
 * the band is exactly the reading, so with nothing live it still contributes no node.
 */
@Composable
private fun ThreadStatusArea(
    apiRetry: ApiRetryStatus,
    resetting: ResetStatus?,
    isCompacting: Boolean,
    isStalled: Boolean,
    turnOutcome: TurnOutcomeReport?,
    isThinking: Boolean,
    isBusy: Boolean,
    localSendPending: Boolean,
    thinkingProgress: ThinkingProgress?,
    runningTool: ToolCall?,
    waitingForAnswers: Boolean,
    connectionState: ConnectionState,
    taskCount: Int,
    onTasksClick: () -> Unit,
    agent: ConversationAgent,
) {
    val reading: @Composable (Modifier) -> Unit = { modifier ->
        if (waitingForAnswers) {
            Row(
                modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painterResource(R.drawable.ic_question_glyph),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.size(14.dp, 16.dp),
                )
                Text(
                    stringResource(R.string.question_waiting_for_answers),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            StatusReading(
                arm =
                    statusArm(
                        connectionState = connectionState,
                        resetting = resetting != null,
                        apiRetrying = apiRetry != ApiRetryStatus.NotRetrying,
                        isCompacting = isCompacting,
                        isStalled = isStalled,
                        hasTurnOutcome = turnOutcome != null,
                        isThinking = isThinking,
                        isBusy = isBusy,
                        localSendPending = localSendPending,
                        hasOpenTool = runningTool != null,
                    ),
                apiRetry = apiRetry,
                resetting = resetting,
                turnOutcome = turnOutcome,
                isThinking = isThinking,
                thinkingProgress = thinkingProgress,
                runningTool = runningTool,
                connectionState = connectionState,
                agent = agent,
                modifier = modifier,
            )
        }
    }
    if (taskCount <= 0) {
        reading(Modifier.fillMaxWidth().padding(horizontal = ComposerStatusGutter))
        return
    }
    // The reading's own 16dp padding lands its content on the 20dp gutter; the pill ends on it. A reading
    // that emits nothing takes its weight with it, and Arrangement.End keeps the pill at the right end.
    // The reading and pill share Figma's 24dp band at normal text scale, and can grow with text scale.
    val bandModifier = Modifier.fillMaxWidth().padding(start = ComposerStatusGutter, end = ComposerGutter)
    Row(
        modifier = bandModifier,
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        reading(Modifier.weight(1f))
        // Figma's band is 24dp, the pill's own height. The clickable Surface would otherwise lay out at the
        // 48dp minimum touch target; the hit test still widens its touch bounds to that minimum without it.
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            NoticePill(
                text = pluralStringResource(R.plurals.thread_task_count, taskCount, taskCount),
                isError = false,
                onClick = onTasksClick,
                modifier = Modifier.sizeIn(minWidth = 104.dp, minHeight = 24.dp),
                // Figma 568:3162 sits in the band, not over the messages, so it has no overlay shadow.
                shadowElevation = 0.dp,
            )
        }
    }
}

/** Which one reading the status band shows (#1311); see [statusArm]. */
internal enum class StatusArm { None, Connection, Resetting, ApiRetry, Compacting, Stalled, TurnOutcome, Thinking, Working, RunningTool }

/**
 * The status band's one arm order (#1311), desktop's `workingIndicatorState` and
 * `workingIndicatorStateWithLocalSend` with Mobile's connection arm at the top and its turn-outcome arm
 * above the turn's own readings. Top wins: connection, Reset session, api-retry, compaction, stall, turn
 * outcome, then the running turn — an open tool while busy, else thinking, else working — and last the
 * local-send window, which reads as thinking. Offline returns [StatusArm.None]: the Top overlay's retry
 * pill owns it.
 *
 * A pending local send hides a turn outcome: that outcome belongs to the turn before the send, and the new
 * turn's first `thinking` / `responding` would clear it anyway. An `idle` answer closes the window and the
 * outcome shows again, since the outcome fold keeps it on `idle`.
 */
internal fun statusArm(
    connectionState: ConnectionState,
    resetting: Boolean,
    apiRetrying: Boolean,
    isCompacting: Boolean,
    isStalled: Boolean,
    hasTurnOutcome: Boolean,
    isThinking: Boolean,
    isBusy: Boolean,
    localSendPending: Boolean,
    hasOpenTool: Boolean,
): StatusArm =
    when {
        connectionState == ConnectionState.Offline -> StatusArm.None
        connectionState != ConnectionState.Connected -> StatusArm.Connection
        resetting -> StatusArm.Resetting
        apiRetrying -> StatusArm.ApiRetry
        isCompacting -> StatusArm.Compacting
        isStalled -> StatusArm.Stalled
        hasTurnOutcome && !localSendPending -> StatusArm.TurnOutcome
        isBusy && hasOpenTool -> StatusArm.RunningTool
        isThinking -> StatusArm.Thinking
        isBusy -> StatusArm.Working
        localSendPending -> StatusArm.Thinking
        else -> StatusArm.None
    }

/** The band's one live reading, [arm], drawn; see [ThreadStatusArea]. Emits nothing for [StatusArm.None]. */
@Composable
private fun StatusReading(
    arm: StatusArm,
    apiRetry: ApiRetryStatus,
    resetting: ResetStatus?,
    turnOutcome: TurnOutcomeReport?,
    isThinking: Boolean,
    thinkingProgress: ThinkingProgress?,
    runningTool: ToolCall?,
    connectionState: ConnectionState,
    agent: ConversationAgent,
    modifier: Modifier = Modifier,
) {
    when (arm) {
        StatusArm.None -> Unit
        StatusArm.Connection -> ConnectionStatusIndicator(state = connectionState, modifier = modifier)
        StatusArm.Resetting -> ResettingIndicator(status = resetting, modifier = modifier, agent = agent)
        StatusArm.ApiRetry -> ApiRetryIndicator(status = apiRetry, modifier = modifier, agent = agent)
        StatusArm.Compacting -> CompactingIndicator(isCompacting = true, modifier = modifier, agent = agent)
        StatusArm.TurnOutcome -> TurnOutcomeIndicator(report = turnOutcome, agent = agent, modifier = modifier)
        // One branch, so the glyph keeps its composition identity, and its pulse, across these readings.
        StatusArm.Stalled, StatusArm.Thinking, StatusArm.Working, StatusArm.RunningTool ->
            ThinkingIndicator(
                isThinking = arm == StatusArm.Thinking,
                modifier = modifier,
                // The token reading belongs to the daemon's thinking phase, never to the local-send window.
                progress = thinkingProgress.takeIf { isThinking },
                runningTool = runningTool.takeIf { arm == StatusArm.RunningTool },
                agent = agent,
                isWorking = arm == StatusArm.Working,
                isStalled = arm == StatusArm.Stalled,
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
        memorySearch = runConfig.memorySearch,
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
