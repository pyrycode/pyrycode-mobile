package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.model.ModalUiState
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.repository.ApiRetryStatus
import de.pyryco.mobile.data.repository.BannerLevel
import de.pyryco.mobile.data.repository.ResetStatus
import de.pyryco.mobile.data.repository.ThinkingProgress
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.UsageLimitReading
import de.pyryco.mobile.ui.components.EditChannelModal
import de.pyryco.mobile.ui.components.chromeBackdrop
import de.pyryco.mobile.ui.components.defaultChromeShadow
import de.pyryco.mobile.ui.conversations.components.ApiRetryIndicator
import de.pyryco.mobile.ui.conversations.components.AttachmentAction
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
import de.pyryco.mobile.ui.conversations.components.MessageContentGutter
import de.pyryco.mobile.ui.conversations.components.ModelRefusalRow
import de.pyryco.mobile.ui.conversations.components.NoticePill
import de.pyryco.mobile.ui.conversations.components.OptionsOverlay
import de.pyryco.mobile.ui.conversations.components.QueuedMessageRow
import de.pyryco.mobile.ui.conversations.components.RenameDialog
import de.pyryco.mobile.ui.conversations.components.ResettingIndicator
import de.pyryco.mobile.ui.conversations.components.SaveAsChannelDialog
import de.pyryco.mobile.ui.conversations.components.SessionBoundaryDelimiter
import de.pyryco.mobile.ui.conversations.components.StatusSheet
import de.pyryco.mobile.ui.conversations.components.StoppedTurnRow
import de.pyryco.mobile.ui.conversations.components.SwitchBackOffer
import de.pyryco.mobile.ui.conversations.components.SystemPromptEditorState
import de.pyryco.mobile.ui.conversations.components.ThinkingIndicator
import de.pyryco.mobile.ui.conversations.components.ThreadStatusGlyph
import de.pyryco.mobile.ui.conversations.components.ToolRunRow
import de.pyryco.mobile.ui.conversations.components.TurnOutcomeIndicator
import de.pyryco.mobile.ui.conversations.components.TurnRecoveryNotice
import de.pyryco.mobile.ui.conversations.components.UnrecognizedMessageRow
import de.pyryco.mobile.ui.conversations.components.WorkspacePicker
import de.pyryco.mobile.ui.conversations.components.formatRelativeTime
import de.pyryco.mobile.ui.theme.threadColors
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/** The status band's reading box (#1312), so a test can tell a band reading from the same words in a message. */
internal const val STATUS_READING_TEST_TAG = "thread-status-reading"

// Figma 16:8's `Input area` (533:1957) and its offsets inside the 412dp reference frame: a 20dp
// content gutter (372dp of content), 8dp between the bands, and 16dp above and below its content.
internal val ComposerGutter = 20.dp
private val ComposerSectionGap = 8.dp
private val ComposerTopGap = 16.dp
private val ComposerBottomGap = 16.dp
private val AttachmentStripTouchOverlap = 5.dp

// The footer reserves 4dp top padding and a 16dp visual band. Its 28dp touch boxes include
// 12dp bottom overflow; Compose expands them to 48dp without entering the input surface.
private val FooterTouchBottomOverflow = 12.dp
private val FrameFooterTouchHeight = 28.dp

// Resting rows and top pills begin 28dp below the measured header rule; scrolled rows draw behind it.
private val MessageAreaTopInset = 28.dp
private val TopOverlayTopGap = MessageAreaTopInset
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

// #1306: the inline permission request's lazy items — Cancel and the card, which holds the title since #1483.
private const val PERMISSION_ROW_COUNT = 2

/** The refused-answer notice's slot in the thread (#1340). */
internal const val PERMISSION_REJECTION_TEST_TAG = "thread-permission-rejection"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
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
    turnOutcome: TurnRecoveryNotice? = null, // #1357: recovery advice after a stopped turn, above thinking
    thinkingProgress: ThinkingProgress? = null, // #803: claude's live token reading, decorates the thinking arm
    isBusy: Boolean = false, // #459: a turn is in flight (thinking OR responding) → show the interrupt affordance
    isStalled: Boolean = false, // #1311: the daemon reported a stall; the band's stall arm
    localSendStage: LocalSendStage = LocalSendStage.None,
    sessionError: String? = null,
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
    // #1340: the daemon refused an answer this chat sent (ThreadViewModel.answerRejected), and the notice's X,
    // wired by MainActivity → vm::onAnswerRejectionDismissed.
    answerRejected: Boolean = false,
    onDismissAnswerRejection: () -> Unit = {},
    // #467: wired by MainActivity → vm::onDropQueued (passes QueuedMessage.id). Since #782 it is bound
    // per row by the fold rather than handed to a foot-of-list section.
    onDropQueued: (Long) -> Unit = {},
    // #1352: the reader pulled toward older messages at the thread's oldest end — ask for the next page
    // back. Wired by MainActivity → vm::onDemandOlderHistory, which decides whether the ask is sent.
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
    // #1342: the open Channel info sheet's System prompt state (ThreadViewModel.systemPrompt); its edits,
    // Save and Clear go through onOverflowEvent.
    systemPrompt: SystemPromptEditorState? = null,
    // #843: this thread's host rejected the saved pairing (ThreadViewModel.rePairAvailable). Draws the
    // Top overlay's pairing pill (#1002) and withholds the connection banner, whose retry cannot succeed then.
    // The tap is bound by MainActivity to the code-pair route keyed by the destination's own server id.
    showRePair: Boolean = false,
    onRePair: () -> Unit = {},
    // #1002: the usage readings hidden from the Top overlay (the app-scoped UsageLimitDismissals) and the X's
    // tap, which hides the reading the pill is showing. Defaulted so screens that never dismiss show every one.
    dismissedUsageLimits: Set<UsageLimitDismissals.Key> = emptySet(),
    onDismissUsageLimit: (UsageLimitReading) -> Unit = {},
    // #1345: the failed MCP server the Top overlay names (ThreadViewModel.mcpFailure) and its tap, which
    // acknowledges the report's failures and opens Channel info.
    mcpFailure: String? = null,
    onOpenMcpFailure: () -> Unit = {},
    // #1635: the stored "Collapse assistant tool uses" setting (AppPreferences.collapseToolUses), bound by
    // MainActivity. Defaulted off so screens and tests that never set it draw every tool row as before.
    collapseToolUses: Boolean = false,
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
    // #1314: one signal per send the daemon accepted (ThreadViewModel.sentMessages); the list follows again.
    sentMessages: Flow<Unit> = emptyFlow(),
    // #1325: the one-shot notice of why a send stopped at a file (ThreadViewModel.attachmentSendFailures).
    attachmentSendFailures: Flow<AttachmentSendFailure> = emptyFlow(),
    // #984: each message attachment's state by id (ThreadViewModel.attachmentStates), the report that one's
    // row is on screen, and a failed one's retry. Bound by MainActivity; defaulted so other screens and tests
    // draw attachments as loading, or a file not fetched on sight (#1329) as its ready row, and start nothing.
    attachmentStates: Map<String, AttachmentViewState> = emptyMap(),
    onAttachmentShown: (MessageAttachment) -> Unit = {},
    onRetryAttachment: (String) -> Unit = {},
    // #1329: a file not fetched yet, tapped or long-pressed, and the one-shot open or save once it loaded.
    // Bound by MainActivity → vm::onAttachmentRequested / vm.attachmentLoads.
    onRequestAttachment: (MessageAttachment, AttachmentAction) -> Unit = { _, _ -> },
    attachmentLoads: Flow<AttachmentLoaded> = emptyFlow(),
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
    // #1341: an open permission request goes first, as desktop's ComposerSlot hides QuestionPanelSlot. The
    // question's picks live in the hoisted state (#1305's draft store), so it returns intact on resolution.
    val shownQuestion = questionState.takeIf { openRequest == null }
    // #1306: one call site for both prompt kinds, so a question → permission hand-over keeps one owner.
    if (questionState != null || openRequest != null) QuestionPromptProtection()
    val snackbarHostState = remember { SnackbarHostState() }
    // #540: surface a failed "New session" send as a transient snackbar. Payload-free (Unit) one-shot
    // idiom — the fixed local string keeps anything exception-derived out of the
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
    // #1329: a tapped file that loaded ready opens or saves once, through the same actions as a ready row.
    LaunchedEffect(attachmentLoads, attachmentActions) { attachmentLoads.collect(attachmentActions.loaded) }
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
    // #678: the read-only background-task panel the top menu and task pill open. Local and keyed like [openControl]:
    // closing it only flips this flag, so nothing is sent and no conversation or task changes.
    var backgroundTasksOpen by remember(state.conversationId) { mutableStateOf(false) }
    var overflowAnchor by remember { mutableStateOf<Rect?>(null) }
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
    val chromeSource = remember { HazeState() }
    val density = LocalDensity.current
    var composerHeight by remember { mutableStateOf(0.dp) }
    val frameBackground =
        Modifier.drawWithCache {
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
        }
    Box(modifier = modifier.then(frameBackground).onGloballyPositioned { layerOrigin = it.positionInWindow() }) {
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
                    onOverflowClick = {
                        openControl = null
                        overflowExpanded = true
                    },
                    onOverflowAnchorChanged = { overflowAnchor = it },
                    modifier =
                        Modifier
                            .chromeBackdrop(chromeSource, frameColors.headerBackdrop, top = true)
                            .testTag("thread-top-bar"),
                )
            },
            // The full-width composer owns its chrome; IME padding stays outside its measured height.
            bottomBar = {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .imePadding()
                            .chromeBackdrop(chromeSource, frameColors.composerBackdrop, top = false)
                            .onSizeChanged { composerHeight = with(density) { it.height.toDp() } }
                            .testTag("thread-composer")
                            .padding(start = ComposerGutter, top = ComposerTopGap, end = ComposerGutter, bottom = ComposerBottomGap),
                    verticalArrangement = Arrangement.spacedBy(ComposerSectionGap),
                ) {
                    // #897: the open tool call names itself in the thinking arm's slot, only while a turn runs.
                    val openTool = remember(state.items) { openToolCall(state.items) }
                    // #1357: the context notice's Compact pill takes the Actions menu's path, and no tap while
                    // the published menu proves the command absent.
                    val onCompact =
                        remember(state.absentActions, onComposerCommand) {
                            if (ComposerAction.CompactSession in state.absentActions) {
                                null
                            } else {
                                { onComposerCommand(ComposerAction.CompactSession) }
                            }
                        }
                    ThreadStatusArea(
                        apiRetry = apiRetry,
                        resetting = resetting,
                        isCompacting = isCompacting,
                        isStalled = isStalled,
                        turnOutcome = turnOutcome,
                        onCompact = onCompact,
                        isThinking = isThinking,
                        isBusy = isBusy,
                        localSendStage = localSendStage,
                        thinkingProgress = thinkingProgress,
                        runningTool = if (isBusy) openTool else null,
                        waitingForAnswers = shownQuestion != null && connectionState == ConnectionState.Connected,
                        waitingForPermission = openRequest != null && connectionState == ConnectionState.Connected,
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
                            connected = connected,
                            modifier =
                                Modifier
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
                        modifier = Modifier,
                        isBusy = isBusy,
                        onInterrupt = onInterrupt,
                        onAnchorChanged = { inputAnchor = it },
                        sending = attachmentsSending,
                        onImagesReceived = onImagesPasted,
                        enabled = connected,
                    )
                    // The footer owns its asymmetric padding inside the same 20dp composer gutter.
                    ThreadComposerFooter(
                        runConfig = state.runConfig,
                        onOpen = {
                            overflowExpanded = false
                            openControl = it
                        },
                        onStatusClick = {
                            sheetVisible = true
                            onOverflowEvent(ThreadEvent.RunConfigOpen)
                        },
                        onAnchorChanged = { control, bounds -> footerAnchors[control] = bounds },
                        modifier =
                            Modifier
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
            val headerHeight = inner.calculateTopPadding()
            Column(
                modifier =
                    Modifier
                        .imePadding()
                        .fillMaxSize()
                        .hazeSource(chromeSource)
                        .then(frameBackground),
            ) {
                // #782: the thread's rows are the join of its items with the daemon's queued backlog, so a
                // message the daemon parked draws once — in place, carrying the queue treatment — instead
                // of once as an optimistic echo and again in a foot-of-list section. Pure and cached on
                // both inputs; the backlog stays replacement truth on ThreadUiState and never folds into
                // the message reducer.
                val queuedRows =
                    remember(state.items, state.queuedMessages) {
                        foldQueuedRows(state.items, state.queuedMessages)
                    }
                // #1621: the one message whose meta row (timestamp + copy) shows; every other bubble hides it
                // until tapped. UI-local, keyed by message id so it follows the message as rows arrive.
                var metaRowMessageId by rememberSaveable { mutableStateOf<String?>(null) }
                // #1635: with the setting on, each run of adjacent tool rows draws as one header the reader
                // can open. Which runs are open is UI-local, keyed by each run's first row, and saveable so a
                // rotation or back-stack return keeps them open, as the tool rows inside keep theirs.
                var expandedRuns by rememberSaveable { mutableStateOf(emptySet<String>()) }
                val rows =
                    remember(queuedRows, collapseToolUses, expandedRuns) {
                        if (collapseToolUses) foldToolRuns(queuedRows, expandedRuns) else queuedRows
                    }
                // A backlog item this device minted no echo for is a row of its own, so the empty state
                // must yield to it (#782 AC #3). When an item *is* matched its echo is a MessageItem, so
                // hasMessages already covers that case.
                // #1002: the message area, with the Top overlay pinned over its top edge while the messages
                // scroll beneath it.
                Box(modifier = Modifier.fillMaxWidth().weight(1f).testTag("thread-message-region")) {
                    // #1352: a pull toward older messages is the only history ask. Inert while a page is
                    // loading, so a second pull sends nothing; the ViewModel still decides the rest.
                    val demandOlderHistory by rememberUpdatedState(onDemandOlderHistory)
                    val historyLoading by rememberUpdatedState(state.historyTail == ThreadHistoryTail.Loading)
                    val pullForOlderHistory = { if (!historyLoading) demandOlderHistory() }
                    if (!state.hasMessages &&
                        state.queuedMessages.isEmpty() &&
                        shownQuestion == null &&
                        openRequest == null &&
                        !answerRejected
                    ) {
                        // An empty thread is at its oldest end. The scrollable consumes nothing; it only lets
                        // a drag reach the pull.
                        val emptyThreadPull = remember { OlderHistoryGesture(nearOldestEnd = { true }, onDemand = pullForOlderHistory) }
                        EmptyThreadState(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .olderHistoryPull(emptyThreadPull)
                                    .scrollable(rememberScrollableState { 0f }, Orientation.Vertical)
                                    .padding(top = headerHeight, bottom = composerHeight)
                                    .padding(start = 24.dp, top = MessageAreaTopInset, end = 24.dp),
                        )
                    } else {
                        val reversedRows = rows.asReversed()
                        // #896: a subagent's tool rows indent under the Agent/Task call that spawned them.
                        val toolDepths = remember(state.items) { toolNestingDepths(state.items) }
                        val listState = rememberLazyListState()
                        val promptRowCount =
                            (shownQuestion?.let { it.batch.questions.size + 2 } ?: 0) +
                                (if (openRequest != null) PERMISSION_ROW_COUNT else 0) +
                                (if (answerRejected) 1 else 0)
                        // Info banners retain their keys but render nothing; spacing follows the visible row.
                        val newestRenderedRow =
                            rows.lastOrNull { row ->
                                ((row as? ThreadRow.Delivered)?.item as? ThreadItem.Banner)?.level != BannerLevel.Info
                            }
                        val restAdjustment = ordinaryMessageRestAdjustment(newestRenderedRow, promptRowCount)
                        var previousRestAdjustment by remember(listState) { mutableStateOf(restAdjustment) }
                        SideEffect {
                            val delta = with(density) { (previousRestAdjustment - restAdjustment).roundToPx() }
                            // End spacing must not move a keyed history reader when a prompt or row kind changes.
                            if (delta != 0 && listState.firstVisibleItemIndex > 0 && !listState.isScrollInProgress) {
                                val anchor =
                                    listState.layoutInfo.visibleItemsInfo.firstOrNull {
                                        it.index == listState.firstVisibleItemIndex
                                    }
                                reversedRows.indices
                                    .firstOrNull { index ->
                                        reversedRows[index].listKey(rows.lastIndex - index) == anchor?.key
                                    }?.let { index ->
                                        listState.requestScrollToItem(
                                            index + promptRowCount,
                                            listState.firstVisibleItemScrollOffset + delta,
                                        )
                                    }
                            }
                            previousRestAdjustment = restAdjustment
                        }
                        // #1352: prompt rows take the lowest indices of the reversed list and are never
                        // history, so the oldest thread row sits after them.
                        val oldestRowIndex by rememberUpdatedState(if (rows.isEmpty()) -1 else rows.size + promptRowCount - 1)
                        val askBandPx by rememberUpdatedState(with(LocalDensity.current) { HistoryAskBand.toPx() })
                        val listPull =
                            remember(listState) {
                                OlderHistoryGesture(
                                    nearOldestEnd = { listState.layoutInfo.isNearOldestEnd(oldestRowIndex, askBandPx) },
                                    onDemand = pullForOlderHistory,
                                )
                            }
                        // #1314: one following state, derived from position on every scroll as desktop's
                        // useThreadScrollPin does, replaces the #185 streaming pin, the #981 newest-row pin and
                        // the #1305/#1306 prompt reveal. New rows, streamed growth and a new prompt pin a reader
                        // who is following; an accepted send follows again.
                        val promptIdentity by rememberUpdatedState(shownQuestion?.generation to openRequest?.modalId)
                        FollowNewestEnd(
                            listState = listState,
                            newestRowKey = rows.lastOrNull()?.listKey(rows.lastIndex),
                            newestRow = rows.lastOrNull(),
                            promptIdentity = promptIdentity,
                            promptPresent = questionState != null || openRequest != null,
                            promptRows = promptRowCount,
                            sentMessages = sentMessages,
                        )
                        // #1484: scrolls the question's actions item to the stream's bottom edge unless it is fully
                        // shown already; only the refused-answer notice can precede it.
                        val actionsIndex = if (answerRejected) 1 else 0
                        val revealActions: suspend () -> Unit =
                            remember(listState, actionsIndex) {
                                {
                                    val info = listState.layoutInfo
                                    val actions = info.visibleItemsInfo.firstOrNull { it.index == actionsIndex }
                                    val shown =
                                        actions != null &&
                                            actions.offset >= 0 &&
                                            actions.offset + actions.size <= info.viewportEndOffset - info.afterContentPadding
                                    if (!shown) listState.scrollToItem(actionsIndex)
                                }
                            }
                        val rowRelocationSpec = LocalBringIntoViewSpec.current
                        ThreadMessageList(
                            state = listState,
                            modifier = Modifier.fillMaxSize().olderHistoryPull(listPull),
                            headerHeight = headerHeight,
                            composerHeight = composerHeight,
                            // Padding follows measured chrome; the drawing viewport continues underneath both bars.
                            contentPadding =
                                PaddingValues(
                                    top = headerHeight + MessageAreaTopInset,
                                    bottom = (composerHeight - ComposerTopGap - restAdjustment).coerceAtLeast(0.dp),
                                ),
                            // #1509: a reversed list defaults to bottom-anchored; Figma `640:2646` starts a
                            // short stream under the header. An overflowing stream is unaffected.
                            verticalArrangement = Arrangement.Top,
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
                            // #1340: a refused answer stays in the slot its card held, above any newer card, until
                            // its X. No frame draws it: Figma 347:6617's Default pill, laid in the page unshadowed.
                            if (answerRejected) {
                                item(key = "permission-rejection") {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = ComposerGutter, vertical = 4.dp)
                                            .testTag(PERMISSION_REJECTION_TEST_TAG),
                                    ) {
                                        NoticePill(
                                            text = stringResource(R.string.permission_answer_rejected),
                                            isError = false,
                                            onDismiss = onDismissAnswerRejection,
                                            shadowElevation = 0.dp,
                                        )
                                    }
                                }
                            }
                            shownQuestion?.let { pending ->
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
                                            // Only the last field sits right above the actions, so only it can
                                            // reveal them without leaving the viewport (Figma 636:3803).
                                            if (index == pending.batch.questions.lastIndex) revealActions else NoReveal,
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
                                ThreadRowContent(rowRelocationSpec) {
                                    when (row) {
                                        is ThreadRow.Delivered ->
                                            when (val item = row.item) {
                                                is ThreadItem.MessageItem ->
                                                    MessageBubble(
                                                        message = item.message,
                                                        toolNestingDepth = toolDepths[item.message.id] ?: 0,
                                                        joinsNextToolRow = rows.getOrNull(chronologicalIndex + 1).isToolRow(),
                                                        attachmentStates = attachmentStates,
                                                        onAttachmentShown = onAttachmentShown,
                                                        onRetryAttachment = onRetryAttachment,
                                                        onOpenAttachment = attachmentActions.open,
                                                        onSaveAttachment = attachmentActions.save,
                                                        onRequestAttachment = onRequestAttachment,
                                                        onOpenMarkdownLink = onOpenMarkdownLink,
                                                        // A streaming reply keeps its row hidden and takes no tap, so
                                                        // the first tap after it finishes is the one that shows it.
                                                        metaRowVisible =
                                                            !item.message.isStreaming && metaRowMessageId == item.message.id,
                                                        onToggleMetaRow =
                                                            if (item.message.isStreaming) {
                                                                null
                                                            } else {
                                                                {
                                                                    val id = item.message.id
                                                                    metaRowMessageId = if (metaRowMessageId == id) null else id
                                                                }
                                                            },
                                                    )
                                                is ThreadItem.SessionBoundary ->
                                                    SessionBoundaryDelimiter(boundary = item)
                                                is ThreadItem.UnrecognizedMessage ->
                                                    UnrecognizedMessageRow(item = item)
                                                // #1359: an info banner keeps its row and key but draws
                                                // nothing, as desktop's TimelineRow does.
                                                is ThreadItem.Banner ->
                                                    if (item.level != BannerLevel.Info) {
                                                        BannerNoticeRow(item = item, agent = state.agent)
                                                    }
                                                is ThreadItem.CompactionBoundary -> CompactionBoundaryDivider(item = item)
                                                is ThreadItem.ModelRefusal ->
                                                    ModelRefusalRow(
                                                        item = item,
                                                        agent = state.agent,
                                                        switchBack = switchBackOffer?.takeIf { it.armedBy(item) },
                                                        onSwitchBack = onSwitchBack,
                                                        // #1494: a model the menu knows reads as its menu label.
                                                        knownModelLabel = state.runConfig::knownModelLabel,
                                                    )
                                                is ThreadItem.StoppedTurn -> StoppedTurnRow(item = item, agent = state.agent)
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
                                        is ThreadRow.ToolRun ->
                                            ToolRunRow(
                                                toolCalls = remember(row.tools) { row.tools.mapNotNull { it.toolCall } },
                                                expanded = row.expanded,
                                                onToggle = {
                                                    expandedRuns =
                                                        if (row.expanded) expandedRuns - row.runId else expandedRuns + row.runId
                                                },
                                                modifier = Modifier.padding(horizontal = MessageContentGutter),
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
                                ThreadHistoryTail.Offline -> item(key = HISTORY_TAIL_KEY) { HistoryOfflineRow() }
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
                                .padding(start = ComposerGutter, top = headerHeight + TopOverlayTopGap, end = ComposerGutter),
                        mcpFailure = mcpFailure,
                        onOpenMcpFailure = onOpenMcpFailure,
                        sessionError = sessionError,
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
        overflowAnchor?.let { anchor ->
            ThreadOverflowMenu(
                expanded = overflowExpanded,
                isPromoted = state.isPromoted,
                mutationsSupported = state.mutationsSupported,
                memorySearch = state.runConfig.memorySearch,
                anchor = anchor.translate(-layerOrigin),
                // The header stays fixed while the column scrolls in the room above the visible IME.
                modifier = Modifier.imePadding(),
                onDismiss = { overflowExpanded = false },
                onEvent = onOverflowEvent,
                onBackgroundTasks = { backgroundTasksOpen = true },
            )
        }
        // #885: the slash-command suggestions share the footer overlay's layer. They stand down while a
        // header or footer menu is open, so two overlays never stack. A pick completes the draft and sends nothing.
        SlashCommandTypeAhead(
            text = draft,
            commands = state.slashCommands,
            anchor = inputAnchor?.takeIf { openMenu == null && !overflowExpanded }?.translate(-layerOrigin),
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
    state.channelEditor?.let { editor ->
        // #1561: the list's Edit channel binding, on this thread's host. Both failure strings are static: the
        // shell announces them aloud, and the daemon's message never reaches this screen.
        EditChannelModal(
            conversationId = editor.conversationId,
            initialName = editor.savedName,
            prompt = editor.prompt,
            initialMuted = editor.savedMuted,
            onSubmit = { name, systemPrompt, muted ->
                onOverflowEvent(ThreadEvent.ChannelEditSubmit(name, systemPrompt, muted))
            },
            onArchiveRequested = { onOverflowEvent(ThreadEvent.ChannelEditArchive) },
            onDismissRequest = { onOverflowEvent(ThreadEvent.ChannelEditDismiss) },
            hostAvailable = state.hostAvailable,
            loading = editor.saving,
            error =
                when {
                    editor.archiveFailed -> stringResource(R.string.archive_failed)
                    editor.failed -> stringResource(R.string.edit_channel_save_failed)
                    else -> null
                },
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
            systemPrompt = systemPrompt ?: SystemPromptEditorState.Loading,
            onSystemPromptChange = { onOverflowEvent(ThreadEvent.SystemPromptEdit(it)) },
            onSystemPromptSave = { onOverflowEvent(ThreadEvent.SystemPromptSave) },
            onSystemPromptClear = { onOverflowEvent(ThreadEvent.SystemPromptClear) },
            onMcpReconnect = { name -> onOverflowEvent(ThreadEvent.McpReconnect(name)) },
            onMcpToggle = { name, enabled -> onOverflowEvent(ThreadEvent.McpToggle(name, enabled)) },
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
    // Open renders inside the message list above; Dismissed surfaces the resolution reason.
    when (modalState) {
        is ModalUiState.Open -> Unit
        is ModalUiState.Dismissed -> {
            val reason = dismissReasonText(modalState.source)
            // Keyed on modalId, so it never re-fires on unrelated recomposition. The host fold keeps this
            // conversation's latest dismissal until the next reconnect (#1337), so reopening the chat shows it again.
            LaunchedEffect(modalState.modalId) {
                snackbarHostState.showSnackbar(reason)
            }
        }
        ModalUiState.Hidden -> Unit
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThreadRowContent(
    relocationSpec: BringIntoViewSpec,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides relocationSpec) { Box { content() } }
}

/** Keep the drawing viewport full size while relocating focus between the measured chrome surfaces. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThreadMessageList(
    state: LazyListState,
    headerHeight: Dp,
    composerHeight: Dp,
    contentPadding: PaddingValues,
    verticalArrangement: Arrangement.Vertical,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    val density = LocalDensity.current
    val relocationSpec =
        remember(headerHeight, composerHeight, density) {
            val headerPx = with(density) { headerHeight.toPx() }
            val composerPx = with(density) { composerHeight.toPx() }
            object : BringIntoViewSpec {
                override fun calculateScrollDistance(
                    offset: Float,
                    size: Float,
                    containerSize: Float,
                ): Float =
                    super.calculateScrollDistance(
                        offset - headerPx,
                        size,
                        (containerSize - headerPx - composerPx).coerceAtLeast(0f),
                    )
            }
        }
    CompositionLocalProvider(LocalBringIntoViewSpec provides relocationSpec) {
        LazyColumn(
            state = state,
            modifier = modifier,
            reverseLayout = true,
            contentPadding = contentPadding,
            verticalArrangement = verticalArrangement,
            content = content,
        )
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
 * the wrap-up or "interrupted". When no signal is live every arm returns without emitting, but the band
 * stays composed at its 24dp height with the snowflake alone (#1312), so the input field never moves.
 *
 * **The snowflake (#1312).** The band, not an arm, draws one [ThreadStatusGlyph] at its leading edge in
 * every state, as desktop's `ComposerStatusArea` draws `PyryMark`; only waiting for answers puts its own
 * question glyph there instead. Waiting for permission (#1483) keeps the snowflake and reads "Waiting for
 * permission" in place of the arms, as Figma `639:2242` draws it. It turns while [isBusy] or [localSendStage] is open, desktop's
 * `isStatusIconTurning`, and is still otherwise, including an api-retry, compaction or stall while idle.
 *
 * [thinkingProgress] (#803) adds **no arm**: it decorates the daemon's thinking phase only, so every arm
 * above pre-empts a live reading for free and the local-send window never shows a stale one.
 *
 * [runningTool] (#897) names the open call while the turn is busy; closing it drops the band back to
 * "Working…" or "Thinking…" (#1311), never to nothing while [isBusy] holds.
 *
 * [taskCount] (#1043) is not an arm either: above zero, a pill reading it sits at the band's right end
 * beside whichever reading shows, or alone, and [onTasksClick] opens the background-task panel.
 */
@Composable
private fun ThreadStatusArea(
    apiRetry: ApiRetryStatus,
    resetting: ResetStatus?,
    isCompacting: Boolean,
    isStalled: Boolean,
    turnOutcome: TurnRecoveryNotice?,
    onCompact: (() -> Unit)?,
    isThinking: Boolean,
    isBusy: Boolean,
    localSendStage: LocalSendStage,
    thinkingProgress: ThinkingProgress?,
    runningTool: ToolCall?,
    waitingForAnswers: Boolean,
    waitingForPermission: Boolean,
    connectionState: ConnectionState,
    taskCount: Int,
    onTasksClick: () -> Unit,
    agent: ConversationAgent,
) {
    // #1312: one always-composed band. The glyph is its first child in every state, so a reading change or
    // the task pill never gives the snowflake a new composition node and its turn never restarts.
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 24.dp)
                .defaultChromeShadow()
                .testTag("thread-status-band"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (waitingForAnswers) {
            Image(
                painterResource(R.drawable.ic_question_glyph),
                contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                modifier = Modifier.size(14.dp, 16.dp),
            )
        } else {
            ThreadStatusGlyph(turning = isBusy || localSendStage != LocalSendStage.None)
        }
        // Always present, so the pill keeps the band's right end while no reading shows.
        Box(Modifier.weight(1f).testTag(STATUS_READING_TEST_TAG)) {
            if (waitingForAnswers || waitingForPermission) {
                Text(
                    stringResource(
                        if (waitingForAnswers) R.string.question_waiting_for_answers else R.string.thread_status_waiting_for_permission,
                    ),
                    modifier = Modifier.padding(vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
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
                            localSendStage = localSendStage,
                            hasOpenTool = runningTool != null,
                        ),
                    apiRetry = apiRetry,
                    resetting = resetting,
                    turnOutcome = turnOutcome,
                    onCompact = onCompact,
                    isThinking = isThinking,
                    thinkingProgress = thinkingProgress,
                    runningTool = runningTool,
                    connectionState = connectionState,
                    agent = agent,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (taskCount > 0) {
            // Figma's band is 24dp, the pill's own height. The clickable Surface would otherwise lay out at the
            // 48dp minimum touch target; the hit test still widens its touch bounds to that minimum without it.
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                NoticePill(
                    text = pluralStringResource(R.plurals.thread_task_count, taskCount, taskCount),
                    isError = false,
                    onClick = onTasksClick,
                    // No minimum width: the pill hugs its label (#1628); Figma's 104dp is "2 tasks running"'s hug.
                    modifier = Modifier.sizeIn(minHeight = 24.dp),
                    // Figma 568:3162 sits in the band, not over the messages, so it has no overlay shadow.
                    shadowElevation = 0.dp,
                )
            }
        }
    }
}

/** Account only for ordinary BubbleFrame trailing space; other row kinds own their resting gap (#1630). */
private fun ordinaryMessageRestAdjustment(
    row: ThreadRow?,
    promptRows: Int,
): Dp {
    val message = ((row as? ThreadRow.Delivered)?.item as? ThreadItem.MessageItem)?.message
    return if (promptRows == 0 && message != null && message.toolCall == null && message.attachments.isEmpty()) 4.dp else 0.dp
}

/** Which one reading the status band shows (#1311); see [statusArm]. */
internal enum class StatusArm {
    None,
    Connection,
    Resetting,
    ApiRetry,
    Compacting,
    Stalled,
    TurnOutcome,
    Thinking,
    Working,
    RunningTool,
    Sending,
    Waiting,
}

/**
 * The status band's one arm order (#1311), desktop's `workingIndicatorState` and
 * `workingIndicatorStateWithLocalSend` with Mobile's connection arm at the top and its turn-outcome arm
 * above the turn's own readings. Top wins: connection, Reset session, api-retry, compaction, stall, turn
 * outcome, then the running turn — an open tool while busy, else thinking, else working — and last the
 * local-send window, which reads Sending or Waiting for the conversation's agent. Offline
 * returns [StatusArm.None]: the Top overlay's retry pill owns it.
 *
 * A pending local send hides a turn outcome: that outcome belongs to the turn before the send, and the new
 * turn's first `thinking` / `responding` would clear it anyway. Since #1357 the send itself clears it too.
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
    localSendStage: LocalSendStage,
    hasOpenTool: Boolean,
): StatusArm =
    when {
        connectionState == ConnectionState.Offline -> StatusArm.None
        connectionState != ConnectionState.Connected -> StatusArm.Connection
        resetting -> StatusArm.Resetting
        apiRetrying -> StatusArm.ApiRetry
        isCompacting -> StatusArm.Compacting
        isStalled -> StatusArm.Stalled
        hasTurnOutcome && localSendStage == LocalSendStage.None -> StatusArm.TurnOutcome
        isBusy && hasOpenTool -> StatusArm.RunningTool
        isThinking -> StatusArm.Thinking
        isBusy -> StatusArm.Working
        localSendStage == LocalSendStage.Sending -> StatusArm.Sending
        localSendStage == LocalSendStage.Waiting -> StatusArm.Waiting
        else -> StatusArm.None
    }

/** The band's one live reading, [arm], as text or pill; see [ThreadStatusArea]. Emits nothing for [StatusArm.None]. */
@Composable
private fun StatusReading(
    arm: StatusArm,
    apiRetry: ApiRetryStatus,
    resetting: ResetStatus?,
    turnOutcome: TurnRecoveryNotice?,
    onCompact: (() -> Unit)?,
    isThinking: Boolean,
    thinkingProgress: ThinkingProgress?,
    runningTool: ToolCall?,
    connectionState: ConnectionState,
    agent: ConversationAgent,
    modifier: Modifier = Modifier,
) {
    when (arm) {
        StatusArm.None -> Unit
        StatusArm.Sending, StatusArm.Waiting ->
            Text(
                stringResource(
                    when {
                        arm == StatusArm.Sending -> R.string.thread_sending_label
                        agent == ConversationAgent.Codex -> R.string.thread_waiting_label_codex
                        else -> R.string.thread_waiting_label
                    },
                ),
                modifier = modifier.padding(vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        StatusArm.Connection -> ConnectionStatusIndicator(state = connectionState, modifier = modifier)
        StatusArm.Resetting -> ResettingIndicator(status = resetting, modifier = modifier, agent = agent)
        StatusArm.ApiRetry -> ApiRetryIndicator(status = apiRetry, modifier = modifier, agent = agent)
        StatusArm.Compacting -> CompactingIndicator(isCompacting = true, modifier = modifier, agent = agent)
        StatusArm.TurnOutcome -> TurnOutcomeIndicator(notice = turnOutcome, agent = agent, onCompact = onCompact, modifier = modifier)
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

private fun ThreadItem.timestamp(): Instant =
    when (this) {
        is ThreadItem.MessageItem -> message.timestamp
        is ThreadItem.SessionBoundary -> occurredAt
        is ThreadItem.UnrecognizedMessage -> occurredAt
        is ThreadItem.Banner -> occurredAt
        is ThreadItem.CompactionBoundary -> occurredAt
        is ThreadItem.ModelRefusal -> occurredAt
        is ThreadItem.StoppedTurn -> occurredAt
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
        agent = agent,
        sessionFacts = reportedSessionFacts,
        sessionCostUsd = sessionCostUsd,
        mcpServers = mcpStatus.takeIf { runConfig.mcpServersSupported },
    )

/**
 * The thread's open tool call (#897): the latest main-thread row in [items] whose call is still
 * [ToolCallStatus.Running], or `null`. Parented, denied, done and failed calls are not open here. One call supplies both
 * the name and the elapsed reading the status area shows, so the two can never come from different calls,
 * and a newer open call replaces an older one because it sits later in the chronological list.
 */
internal fun openToolCall(items: List<ThreadItem>): ToolCall? =
    items
        .lastOrNull { item ->
            item is ThreadItem.MessageItem &&
                item.message.toolCall?.status == ToolCallStatus.Running &&
                item.message.toolCall.parentToolUseId
                    .isEmpty()
        }.let { (it as? ThreadItem.MessageItem)?.message?.toolCall }
