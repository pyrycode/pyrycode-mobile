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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.SubcomposeLayout
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
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.model.ModalUiState
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.ApiRetryStatus
import de.pyryco.mobile.data.repository.BannerLevel
import de.pyryco.mobile.data.repository.ResetStatus
import de.pyryco.mobile.data.repository.ThinkingProgress
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.UsageLimitReading
import de.pyryco.mobile.ui.components.EditChannelModal
import de.pyryco.mobile.ui.components.MobileModal
import de.pyryco.mobile.ui.components.chromeBackdrop
import de.pyryco.mobile.ui.components.defaultChromeShadow
import de.pyryco.mobile.ui.conversations.components.AgentStartMarker
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
import de.pyryco.mobile.ui.conversations.components.LocalMarkdownLinkFailure
import de.pyryco.mobile.ui.conversations.components.LocalToolCallExpansion
import de.pyryco.mobile.ui.conversations.components.MEMORY_PLUGIN_DOCS_URL
import de.pyryco.mobile.ui.conversations.components.MarkdownLinkNotice
import de.pyryco.mobile.ui.conversations.components.MessageAreaRowSpacing
import de.pyryco.mobile.ui.conversations.components.MessageBubble
import de.pyryco.mobile.ui.conversations.components.MessageContentGutter
import de.pyryco.mobile.ui.conversations.components.ModelRefusalRow
import de.pyryco.mobile.ui.conversations.components.NoticePill
import de.pyryco.mobile.ui.conversations.components.OptionsOverlay
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
import de.pyryco.mobile.ui.conversations.components.ToolCallExpansion
import de.pyryco.mobile.ui.conversations.components.ToolRunRow
import de.pyryco.mobile.ui.conversations.components.TurnRecoveryNotice
import de.pyryco.mobile.ui.conversations.components.UnrecognizedMessageRow
import de.pyryco.mobile.ui.conversations.components.WorkspacePicker
import de.pyryco.mobile.ui.conversations.components.agentName
import de.pyryco.mobile.ui.conversations.components.formatRelativeTime
import de.pyryco.mobile.ui.theme.threadColors
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

// Observes the production list-host composition without adding snapshot dependencies.
internal val LocalThreadListCompositionObserver =
    staticCompositionLocalOf<
        (
            (
                androidx.compose.foundation.lazy.LazyListState,
            ) -> Unit
        )?,
    > { null }

/** Lets the cold-boundary regression distinguish unmeasured rows from merely offscreen rows. */
internal val LocalThreadRowMeasurementObserver = staticCompositionLocalOf<((Any) -> Unit)?> { null }

/** Counts mounted authoritative rows without observing frame-updated layout state. */
internal val LocalAuthoritativeRowCompositionObserver = staticCompositionLocalOf<((String, Boolean) -> Unit)?> { null }

/** The status band's reading box (#1312), so a test can tell a band reading from the same words in a message. */
internal const val STATUS_READING_TEST_TAG = "thread-status-reading"

// Figma 16:8's `Input area` (533:1957) and its offsets inside the 412dp reference frame: a 20dp
// content gutter (372dp of content), 8dp between the bands, and 16dp above and below its content.
internal val ComposerGutter = 20.dp
private val ComposerSectionGap = 8.dp
private val ComposerTopGap = 16.dp
private val ComposerBottomGap = 16.dp
private val AttachmentStripTouchOverlap = 5.dp

// Trailing controls retain their 28dp boxes and 12dp bottom overflow. Context reserves a full
// 48dp target above the visible icon band so its bottom edge remains inside the window.
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

/** Retain the published receiver through recomposition; callback identity does not identify its owner. */
private class AuthoritativeThreadActionPublisher {
    private class Binding(
        val presentation: AuthoritativeThreadPresentation,
        val receiver: (ThreadEvent) -> Unit,
        val token: Any = Any(),
    )

    private var published: Binding? = null
    private var bodyKeys: Set<String> = emptySet()
    private var childKeys: Set<String> = emptySet()

    fun demandChildren(
        presentation: AuthoritativeThreadPresentation,
        keys: Set<String>,
        retry: String? = null,
        more: String? = null,
    ) {
        val binding = published?.takeIf { it.presentation === presentation } ?: return
        childKeys = keys
        binding.receiver(ThreadEvent.AgentChildrenDemand(presentation, keys, binding.token, retry, more))
    }

    fun childrenAction(
        presentation: AuthoritativeThreadPresentation,
        key: String,
        retry: Boolean,
    ) {
        if (key in childKeys) demandChildren(presentation, childKeys, retry = key.takeIf { retry }, more = key.takeUnless { retry })
    }

    fun demandBodies(
        presentation: AuthoritativeThreadPresentation,
        keys: Set<String>,
        retry: String? = null,
    ) {
        val binding = published?.takeIf { it.presentation === presentation } ?: return
        bodyKeys = keys
        binding.receiver(ThreadEvent.ToolBodyDemand(presentation, keys, binding.token, retry))
    }

    fun retryBody(
        presentation: AuthoritativeThreadPresentation,
        key: String,
    ) {
        if (key in bodyKeys) demandBodies(presentation, bodyKeys, key)
    }

    fun present(
        presentation: AuthoritativeThreadPresentation?,
        receiver: (ThreadEvent) -> Unit,
    ) {
        val previous = published
        if (presentation == null) {
            retire()
            return
        }
        if (previous?.presentation === presentation && previous.receiver == receiver) return
        val next = Binding(presentation, receiver)
        // Publish first: on the same ViewModel, retirement of the old binding is then a no-op.
        receiver(ThreadEvent.AuthoritativeThreadPresented(presentation, next.token))
        published = next
        previous?.receiver?.invoke(ThreadEvent.AuthoritativeThreadRetired(previous.token))
    }

    fun dispatchRead(
        event: ThreadEvent,
        receiver: (ThreadEvent) -> Unit,
    ) {
        if (event !is ThreadEvent.NewestContentPresented || event.presentation == null) {
            receiver(event)
            return
        }
        val binding = published ?: return
        if (binding.presentation !== event.presentation) return
        receiver(event.copy(binding = binding.token))
    }

    fun retire() {
        val previous = published ?: return
        published = null
        previous.receiver(ThreadEvent.AuthoritativeThreadRetired(previous.token))
    }
}

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
    turnOutcome: TurnRecoveryNotice? = null, // #1603: recovery advice after a stopped turn in the top overlay
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
    onSendQueuedNow: (Long) -> Unit = {},
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
    onReplyToMessage: (Message) -> String? = { null },
    suggestedReply: SuggestedReply? = null,
    onSendSuggestedReply: (SuggestedReply) -> Boolean = { false },
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
    attentionPill: (@Composable () -> Unit)? = null,
) {
    val authoritative = state.authoritativeThread
    val threadItems = authoritative?.items ?: state.items
    val taskRoster = authoritative?.tasks ?: state.backgroundTasks
    val taskCount = authoritative?.tasks?.liveCount ?: state.backgroundTaskCount
    val interactionScope = authoritative?.scope ?: state.conversationId
    var authoritativeExpandedTasks by rememberSaveable(interactionScope) { mutableStateOf(emptySet<String>()) }
    val actionPublisher = remember { AuthoritativeThreadActionPublisher() }
    SideEffect { actionPublisher.present(authoritative, onOverflowEvent) }
    DisposableEffect(actionPublisher) {
        onDispose { actionPublisher.retire() }
    }
    LaunchedEffect(authoritative) {
        if (authoritative != null) {
            RelayLog.d {
                "event=thread_presentation code=projected rows=${authoritative.rows.size} tasks=${authoritative.tasks.tasks.size}"
            }
            val fallbacks = authoritative.rows.filterIsInstance<ThreadRow.Delivered>().count { it.summaryText != null }
            if (fallbacks > 0) RelayLog.d { "event=thread_presentation code=summary_fallback rows=$fallbacks" }
        }
    }
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
    var pendingReplyDraft by remember(state.conversationId) { mutableStateOf<String?>(null) }
    val threadOpenedAt = remember(state.conversationId) { Clock.System.now() }
    var sheetVisible by rememberSaveable { mutableStateOf(false) }
    var overflowExpanded by rememberSaveable { mutableStateOf(false) }
    val openRequest = modalState as? ModalUiState.Open
    // #1341: an open permission request goes first, as desktop's ComposerSlot hides QuestionPanelSlot. The
    // question's picks live in the hoisted state (#1305's draft store), so it returns intact on resolution.
    val shownQuestion = questionState.takeIf { openRequest == null }
    // #1306: one call site for both prompt kinds, so a question → permission hand-over keeps one owner.
    if (questionState != null || openRequest != null) QuestionPromptProtection()
    val confirmationNotices = rememberTransientConfirmationNoticeState(state.conversationId)
    val confirmationScope = key(state.conversationId) { rememberCoroutineScope() }
    val errorNotices = rememberTransientErrorNoticeState(state.conversationId)
    // Payload-free signals and local resources keep exception and daemon text out of notices. Each collector
    // queues its notice and returns, so signals keep their arrival order across routes.
    val newSessionFailedMessage = stringResource(R.string.new_session_failed)
    LaunchedEffect(newSessionErrors, errorNotices) {
        newSessionErrors.collect { errorNotices.enqueue(this, newSessionFailedMessage) }
    }
    val archiveFailedMessage = stringResource(R.string.archive_failed)
    LaunchedEffect(archiveErrors, errorNotices) {
        archiveErrors.collect { errorNotices.enqueue(this, archiveFailedMessage) }
    }
    val changeWorkspaceFailedMessage = stringResource(R.string.change_workspace_failed)
    LaunchedEffect(changeWorkspaceErrors, errorNotices) {
        changeWorkspaceErrors.collect { errorNotices.enqueue(this, changeWorkspaceFailedMessage) }
    }
    val sessionSettingsFailedMessage = stringResource(R.string.session_settings_failed)
    val agentSwitchFailedMessage = stringResource(R.string.thread_agent_switch_failed)
    LaunchedEffect(state.runConfig.agentSwitchFailed, errorNotices) {
        if (state.runConfig.agentSwitchFailed) errorNotices.enqueue(this, agentSwitchFailedMessage)
    }
    LaunchedEffect(sessionSettingsErrors, errorNotices) {
        sessionSettingsErrors.collect { errorNotices.enqueue(this, sessionSettingsFailedMessage) }
    }
    // #933: a pick with refused entries names how many, per reason — counts only, never a file name.
    val resources = LocalContext.current.resources
    LaunchedEffect(attachmentRefusals, errorNotices) {
        attachmentRefusals.collect { refusal ->
            if (refusal.tooLarge > 0) {
                val text = resources.getQuantityString(R.plurals.thread_attachments_too_large, refusal.tooLarge, refusal.tooLarge)
                errorNotices.enqueue(this, text)
            }
            if (refusal.tooMany > 0) {
                val text = resources.getQuantityString(R.plurals.thread_attachments_too_many, refusal.tooMany, refusal.tooMany)
                errorNotices.enqueue(this, text)
            }
        }
    }
    // #1325: a send that stopped at a file says why in one fixed sentence — never a name or the daemon's code.
    LaunchedEffect(attachmentSendFailures, errorNotices) {
        attachmentSendFailures.collect { failure -> errorNotices.enqueue(this, failure.text(resources)) }
    }
    val openAttachmentPicker = rememberAttachmentPicker(onAttachmentsPicked)
    // #985: a ready message attachment opens in another app or saves to a picked document; each outcome the
    // user should hear about is one static sentence, never a name, URI or path.
    val noticeScope = rememberCoroutineScope()
    val linkNotices = MarkdownLinkNotice.entries.associateWith { stringResource(it.message) }
    val onMarkdownLinkFailure: (MarkdownLinkNotice) -> Unit =
        remember(errorNotices, noticeScope, linkNotices) {
            { notice ->
                errorNotices.enqueue(noticeScope, linkNotices.getValue(notice))
                Unit
            }
        }
    val attachmentActions =
        rememberAttachmentActions(attachmentStates, onOpenMarkdownAttachment) { notice ->
            val text = resources.getString(notice.message)
            if (notice == AttachmentNotice.SAVED) {
                confirmationNotices.enqueue(confirmationScope, text)
            } else {
                noticeScope.launch { errorNotices.show(text) }
            }
        }
    // #1329: a tapped file that loaded ready opens or saves once, through the same actions as a ready row.
    LaunchedEffect(attachmentLoads, attachmentActions) { attachmentLoads.collect(attachmentActions.loaded) }
    // #1027: a markdown file that cannot be read says what any failed open says.
    LaunchedEffect(markdownOpenFailures, errorNotices) {
        markdownOpenFailures.collect { errorNotices.enqueue(this, resources.getString(AttachmentNotice.OPEN_FAILED.message)) }
    }
    // #934: a pasted image joins the chat's strip through the same sink as a picked one. A refused
    // paste or keyboard insertion queues its Error pill like any other attachment failure (#1747).
    val onImagesPasted =
        key(state.conversationId) {
            rememberPastedImageReceiver(onAttachmentsPicked) { failure ->
                errorNotices.enqueue(noticeScope, failure.text(resources))
            }
        }
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
    var typeAheadVisible by remember(state.conversationId) { mutableStateOf(false) }
    var inputAnchor by remember { mutableStateOf<Rect?>(null) }
    val imeVisible = WindowInsets.isImeVisible
    val scheme = MaterialTheme.colorScheme
    val frameColors = scheme.threadColors
    val chromeSource = remember { HazeState() }
    val density = LocalDensity.current
    var topOverlayHeight by remember { mutableStateOf(0.dp) }
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
                    val openTool =
                        remember(authoritative, threadItems) {
                            if (authoritative != null) authoritative.foregroundTool else openToolCall(threadItems)
                        }
                    ThreadStatusArea(
                        apiRetry = apiRetry,
                        resetting = resetting,
                        switching = state.runConfig.agentSwitch?.takeIf { it.sending },
                        isCompacting = isCompacting,
                        isStalled = isStalled,
                        isThinking = isThinking,
                        isBusy = isBusy,
                        localSendStage = localSendStage,
                        thinkingProgress = thinkingProgress,
                        runningTool = if (isBusy) openTool else null,
                        waitingForAnswers = shownQuestion != null && connectionState == ConnectionState.Connected,
                        waitingForPermission = openRequest != null && connectionState == ConnectionState.Connected,
                        connectionState = connectionState,
                        taskCount = taskCount,
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
                        replyDraft = pendingReplyDraft,
                        onReplyConsumed = { pendingReplyDraft = null },
                        suggestedReply = suggestedReply,
                        onSendSuggestedReply = onSendSuggestedReply,
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
                        onContextClick = {
                            openControl = null
                            onOverflowEvent(ThreadEvent.ContextOpen)
                        },
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
                val agentRows =
                    remember(authoritative, state.displayRows, state.items) {
                        authoritative?.rows ?: state.displayRows ?: state.items.map { ThreadRow.Delivered(it) }
                    }
                val agentNavigation = remember(interactionScope) { ThreadAgentNavigation() }
                val destinationLifecycle = LocalLifecycleOwner.current.lifecycle
                DisposableEffect(agentNavigation, destinationLifecycle) {
                    val observer =
                        LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_PAUSE) agentNavigation.cancel("departure")
                        }
                    destinationLifecycle.addObserver(observer)
                    onDispose {
                        destinationLifecycle.removeObserver(observer)
                        agentNavigation.cancel("departure")
                    }
                }
                // #1621: the one message whose meta row (timestamp + copy) shows; every other bubble hides it
                // until tapped. UI-local, keyed by message id so it follows the message as rows arrive.
                var metaRowMessageId by rememberSaveable { mutableStateOf<String?>(null) }
                // #1635: with the setting on, each run of adjacent tool rows draws as one header the reader
                // can open. Which runs are open is UI-local, keyed by each run's first row, and saveable so a
                // rotation or back-stack return keeps them open, as the tool rows inside keep theirs.
                var expandedTools by rememberSaveable(interactionScope) { mutableStateOf(emptySet<String>()) }
                var expandedRuns by rememberSaveable(interactionScope) { mutableStateOf(emptySet<String>()) }
                var previousAgentRows by remember(interactionScope) { mutableStateOf(agentRows) }
                var pendingOpenTools by rememberSaveable(interactionScope) { mutableStateOf(emptySet<String>()) }
                // A late join or parent backfill moves tools between runs. Carry an open run's expansion with them.
                val carried =
                    remember(agentRows, previousAgentRows, expandedRuns, pendingOpenTools) {
                        carryRunExpansion(previousAgentRows, agentRows, expandedRuns, pendingOpenTools)
                    }
                val retainedExpandedRuns = carried.expandedRuns
                SideEffect {
                    expandedRuns = retainedExpandedRuns
                    pendingOpenTools = carried.pending
                    previousAgentRows = agentRows
                }
                val rows =
                    remember(authoritative, agentRows, collapseToolUses, retainedExpandedRuns, expandedTools) {
                        val folded = if (collapseToolUses) foldToolRuns(agentRows, retainedExpandedRuns) else agentRows
                        authoritative?.demandRows(folded, expandedTools) ?: folded
                    }
                val childControls =
                    remember(authoritative, rows, expandedTools) { authoritative?.childControls(rows, expandedTools).orEmpty() }
                // A backlog item this device minted no echo for is a row of its own, so the empty state
                // must yield to it (#782 AC #3). When an item *is* matched its echo is a MessageItem, so
                // hasMessages already covers that case.
                // #1002: the message area, with the Top overlay pinned over its top edge while the messages
                // scroll beneath it.
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .nestedScroll(agentNavigation)
                            .testTag("thread-message-region"),
                ) {
                    // Movement can prefetch after a page settles; arrival alone never asks.
                    // The ViewModel remains the authoritative single-flight and termination gate.
                    val demandOlderHistory by rememberUpdatedState(onDemandOlderHistory)
                    val historyLoading by rememberUpdatedState(state.historyTail == ThreadHistoryTail.Loading)
                    val pullForOlderHistory = { if (!historyLoading) demandOlderHistory() }
                    if ((authoritative?.rows?.isEmpty() ?: !state.hasMessages) &&
                        state.displayRows.isNullOrEmpty() &&
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
                        val toolDepths = remember(authoritative, state.items) { authoritative?.depths ?: toolNestingDepths(state.items) }
                        val listState = key(interactionScope) { rememberLazyListState() }
                        val bodyPresentation by rememberUpdatedState(authoritative)
                        val bodyRows by rememberUpdatedState(rows)
                        val bodyConnected by rememberUpdatedState(connected)
                        val visibleChildControls by rememberUpdatedState(childControls)
                        LaunchedEffect(listState, destinationLifecycle) {
                            destinationLifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                                try {
                                    snapshotFlow {
                                        val p = bodyPresentation
                                        val visibleKeys = listState.layoutInfo.visibleItemsInfo.mapTo(HashSet()) { it.key }
                                        val keys =
                                            if (!bodyConnected ||
                                                p == null
                                            ) {
                                                emptySet()
                                            } else {
                                                bodyRows
                                                    .filterIsInstance<ThreadRow.Delivered>()
                                                    .filter { row ->
                                                        row.listKey(0) in visibleKeys &&
                                                            ((row.item as? ThreadItem.MessageItem)?.message?.id in expandedTools) &&
                                                            p.bodyItem(row.listKey(0)) != null
                                                    }.mapTo(mutableSetOf()) { it.listKey(0) }
                                            }
                                        val children =
                                            if (!bodyConnected || p == null) {
                                                emptySet()
                                            } else {
                                                val expanded =
                                                    bodyRows
                                                        .filterIsInstance<ThreadRow.Delivered>()
                                                        .filter {
                                                            it.listKey(0) in visibleKeys &&
                                                                (it.item as? ThreadItem.MessageItem)?.message?.id in expandedTools &&
                                                                p.childParent(it.listKey(0)) != null
                                                        }.map { it.listKey(0) }
                                                (expanded + visibleChildControls.filterKeys { it in visibleKeys }.values.flatten()).toSet()
                                            }
                                        Triple(p, keys, children)
                                    }.collect { (p, keys, children) ->
                                        if (p != null) {
                                            actionPublisher.demandChildren(p, children)
                                            actionPublisher.demandBodies(p, keys)
                                        }
                                    }
                                } finally {
                                    bodyPresentation?.let {
                                        actionPublisher.demandChildren(it, emptySet())
                                        actionPublisher.demandBodies(it, emptySet())
                                    }
                                }
                            }
                        }
                        val readerViewport = remember(listState) { ThreadListViewport(listState) }
                        val compositionObserver = LocalThreadListCompositionObserver.current
                        SideEffect { compositionObserver?.invoke(listState) }
                        val messageViewport = remember(listState) { mutableStateOf<Rect?>(null) }
                        val promptRowCount =
                            (shownQuestion?.let { it.batch.questions.size + 2 } ?: 0) +
                                (if (openRequest != null) PERMISSION_ROW_COUNT else 0) +
                                (if (answerRejected) 1 else 0)
                        val agentRelocation = readerViewport.relocationFor(rows, agentRows, promptRowCount, authoritative?.backgroundBlocks)
                        SideEffect { readerViewport.onRowsChanged(rows, agentRows, agentRelocation, authoritative?.backgroundBlocks) }
                        // "Go to agent" only scrolls the block's own root row into view; it never expands
                        // the block's collapsed run (that root draws as itself regardless, #1827
                        // follow-up — only its own tap, via ToolRunRow's onToggle, opens or closes a run).
                        LaunchedEffect(agentNavigation, agentNavigation.pending, rows, promptRowCount) {
                            val request = agentNavigation.pending ?: return@LaunchedEffect
                            val index =
                                reversedRows.indexOfFirst { row ->
                                    ((row as? ThreadRow.Delivered)?.item as? ThreadItem.MessageItem)?.message?.id == request.agentId
                                }
                            if (index >= 0) {
                                agentNavigation.scrollToRoot(listState, index + promptRowCount, request)
                            }
                        }
                        // Info banners retain their keys but render nothing; spacing follows the visible row.
                        val newestRenderedRow =
                            rows.lastOrNull { row ->
                                ((row as? ThreadRow.Delivered)?.item as? ThreadItem.Banner)?.level != BannerLevel.Info
                            }
                        val readRow = newestRenderedRow as? ThreadRow.Delivered
                        val readItem = readRow?.item
                        val readKey =
                            if (authoritative != null) {
                                authoritative.newestReadKey
                            } else {
                                newestRenderedRow?.listKey(rows.indexOf(newestRenderedRow))
                            }
                        val authoritativeCandidates =
                            remember(authoritative, rows, expandedTools) {
                                if (authoritative == null) {
                                    emptyList()
                                } else {
                                    rows
                                        .filterIsInstance<ThreadRow.Delivered>()
                                        .filter {
                                            authoritative.suppliesReadSight(
                                                it,
                                                expandedTools,
                                            )
                                        }.map { row ->
                                            val key = row.listKey(0)
                                            ThreadReadCandidate(key, row.item, authoritative.shownRowRevisions[key]).apply {
                                                if (row.summaryText != null) revealed = true
                                            }
                                        }
                                }
                            }
                        val readCandidate =
                            if (authoritative != null) {
                                authoritativeCandidates.firstOrNull { it.key == readKey }
                            } else {
                                remember(readKey, readRow) {
                                    if (readItem != null && readKey != null) ThreadReadCandidate(readKey, readItem) else null
                                }
                            }
                        ThreadReadViewport(
                            state = state,
                            listState = listState,
                            candidate = readCandidate,
                            otherCandidates = authoritativeCandidates,
                            viewport = messageViewport,
                            headerHeight = maxOf(headerHeight, topOverlayHeight),
                            composerHeight = composerHeight,
                            visible =
                                modalState == ModalUiState.Hidden &&
                                    shownQuestion == null &&
                                    !state.channelInfoOpen &&
                                    !state.contextSheetOpen &&
                                    !backgroundTasksOpen &&
                                    !sheetVisible &&
                                    !state.showRenameDialog &&
                                    state.channelEditor == null &&
                                    state.saveAsChannelDialog == null &&
                                    !state.deleteConfirmVisible &&
                                    !state.workspacePickerVisible &&
                                    !overflowExpanded &&
                                    openMenu == null &&
                                    !typeAheadVisible,
                            onEvent = { actionPublisher.dispatchRead(it, onOverflowEvent) },
                        )
                        val restAdjustment = ordinaryMessageRestAdjustment(newestRenderedRow, promptRowCount)
                        // #1352: prompt rows take the lowest indices of the reversed list and are never
                        // history, so the oldest thread row sits after them.
                        val oldestRowIndex by rememberUpdatedState(if (rows.isEmpty()) -1 else rows.size + promptRowCount - 1)
                        val historyPromptRows by rememberUpdatedState(promptRowCount)
                        val listPull =
                            remember(listState) {
                                OlderHistoryGesture(
                                    nearOldestEnd = {
                                        listState.layoutInfo.isNearOldestEnd(
                                            oldestRowIndex,
                                            listState.layoutInfo.viewportSize.height * 2f,
                                            historyPromptRows,
                                        )
                                    },
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
                            viewport = readerViewport,
                            newestRowKey = rows.lastOrNull()?.listKey(rows.lastIndex),
                            newestRow =
                                remember(rows, agentRows) {
                                    listOf(
                                        rows.lastOrNull(),
                                        agentRows.filterIsInstance<ThreadRow.Delivered>().filter {
                                            it.agentBlockId !=
                                                null
                                        },
                                    )
                                },
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
                        val rowMeasurementObserver = LocalThreadRowMeasurementObserver.current
                        val rowCompositionObserver = LocalAuthoritativeRowCompositionObserver.current
                        val demandedByKey =
                            remember(authoritative, rows) {
                                if (authoritative?.defersContents == true) rows.associateBy { it.listKey(0) } else null
                            }
                        val renderRow: @Composable (ThreadRow, ThreadRow?, Boolean) -> Unit = { row, nextRow, measuring ->
                            if (authoritative != null && rowCompositionObserver != null) {
                                val compositionKey = if (measuring) "measurement:${row.listKey(0)}" else row.listKey(0)
                                DisposableEffect(compositionKey, rowCompositionObserver) {
                                    rowCompositionObserver(compositionKey, true)
                                    onDispose { rowCompositionObserver(compositionKey, false) }
                                }
                            }
                            val candidate =
                                if (measuring) {
                                    null
                                } else if (authoritative != null) {
                                    authoritativeCandidates.firstOrNull { it.key == row.listKey(0) }
                                } else {
                                    readCandidate?.takeIf { row == readRow }
                                }
                            val toolId =
                                ((row as? ThreadRow.Delivered)?.item as? ThreadItem.MessageItem)
                                    ?.message
                                    ?.takeIf {
                                        it.role ==
                                            Role.Tool
                                    }?.id
                            val toolOwner =
                                (row as? ThreadRow.Delivered)?.agentBlockId?.takeIf { toolId != null && it != toolId }
                            val toolExpansion =
                                toolId?.let { id ->
                                    val source = authoritative?.bodyItem(row.listKey(0))
                                    ToolCallExpansion(
                                        id in expandedTools,
                                        missingBody = authoritative?.missingBody(row.listKey(0)) == true,
                                        bodyLoad = source?.let { authoritative.sourceSnapshot.bodyLoads[it.id to it.rev] },
                                        offline = !connected,
                                        retry = { authoritative?.let { actionPublisher.retryBody(it, row.listKey(0)) } },
                                        headline =
                                            source?.raw?.get("headline")?.let {
                                                (it as? kotlinx.serialization.json.JsonPrimitive)
                                                    ?.takeIf { value ->
                                                        value.isString
                                                    }?.content
                                                    ?.take(400)
                                            },
                                    ) {
                                        expandedTools =
                                            if (id in expandedTools) expandedTools - id else expandedTools + id
                                    }
                                }
                            ThreadRowContent(
                                rowRelocationSpec,
                                toolExpansion = toolExpansion,
                                modifier =
                                    Modifier
                                        // Keep ownership above the outlined surface's own tool-row tag.
                                        .then(toolOwner?.let { Modifier.testTag("background-agent-child:$it") } ?: Modifier)
                                        .layout { measurable, constraints ->
                                            val placeable = measurable.measure(constraints)
                                            if (!measuring) {
                                                readerViewport.onRowMeasured(row, nextRow, placeable.height)
                                                rowMeasurementObserver?.invoke(row.listKey(0))
                                            }
                                            layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
                                        }.then(
                                            if (candidate != null) {
                                                Modifier.onGloballyPositioned {
                                                    candidate.laidOut = true
                                                    if (candidate.row !is ThreadItem.MessageItem ||
                                                        (row as? ThreadRow.Delivered)?.summaryText != null
                                                    ) {
                                                        candidate.trailingEdge = it.positionInWindow().y + it.size.height
                                                    }
                                                }
                                            } else {
                                                Modifier
                                            },
                                        ),
                            ) {
                                when (row) {
                                    is ThreadRow.Delivered ->
                                        when (val item = row.item) {
                                            is ThreadItem.MessageItem ->
                                                if (row.summaryText != null) {
                                                    AuthoritativeSummaryRow(row.summaryText)
                                                } else {
                                                    CompositionLocalProvider(LocalMarkdownLinkFailure provides onMarkdownLinkFailure) {
                                                        MessageBubble(
                                                            message = item.message,
                                                            onContentPresented = { candidate?.revealed = true },
                                                            onContentTrailingEdge =
                                                                candidate?.let { tracked ->
                                                                    { _, bottom -> tracked.trailingEdge = bottom }
                                                                },
                                                            onReply = { pendingReplyDraft = onReplyToMessage(it) },
                                                            modifier =
                                                                if (row.agentBlockId ==
                                                                    item.message.id
                                                                ) {
                                                                    Modifier.testTag("background-agent:${item.message.id}")
                                                                } else if (
                                                                    row.agentBlockId != null && item.message.role == Role.Assistant
                                                                ) {
                                                                    Modifier
                                                                        .padding(
                                                                            start =
                                                                                MessageAreaRowSpacing *
                                                                                    ((toolDepths[item.message.parentToolUseId] ?: 0) + 1),
                                                                        ).testTag("background-agent-child:${row.agentBlockId}")
                                                                } else {
                                                                    Modifier
                                                                },
                                                            threadOpenedAt = threadOpenedAt,
                                                            toolNestingDepth = toolDepths[item.message.id] ?: 0,
                                                            joinsNextToolRow = row.joinsToolRow(nextRow),
                                                            attachmentStates = attachmentStates,
                                                            onAttachmentShown = if (measuring) ({ _ -> }) else onAttachmentShown,
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
                                                    }
                                                }
                                            is ThreadItem.SessionBoundary ->
                                                SessionBoundaryDelimiter(boundary = item)
                                            is ThreadItem.UnrecognizedMessage ->
                                                UnrecognizedMessageRow(item = item)
                                            // #1359: an info banner keeps its row and key but draws
                                            // nothing, as desktop's TimelineRow does.
                                            is ThreadItem.Banner ->
                                                if (item.level != BannerLevel.Info) {
                                                    BannerNoticeRow(item = item, agent = row.displayAgent ?: state.agent)
                                                }
                                            is ThreadItem.CompactionBoundary -> CompactionBoundaryDivider(item = item)
                                            is ThreadItem.ModelRefusal ->
                                                ModelRefusalRow(
                                                    item = item,
                                                    agent = row.displayAgent ?: state.agent,
                                                    switchBack = switchBackOffer?.takeIf { it.armedBy(item) },
                                                    onSwitchBack = onSwitchBack,
                                                    // #1494: a model the menu knows reads as its menu label.
                                                    knownModelLabel = state.runConfig::knownModelLabel,
                                                )
                                            is ThreadItem.BackgroundTaskLifecycle -> Unit
                                            is ThreadItem.StoppedTurn ->
                                                StoppedTurnRow(
                                                    item = item,
                                                    agent =
                                                        row.displayAgent ?: state.agent,
                                                )
                                        }
                                    // One render path for both kinds of queued row — the one the echo
                                    // correlated to and the one this device minted no echo for — so the
                                    // two cannot drift apart. The id is bound here, so the row never
                                    // holds one.
                                    is ThreadRow.Queued ->
                                        de.pyryco.mobile.ui.conversations.components.AuthoritativeSendRow(
                                            text = row.text,
                                            onDrop =
                                                if (row.authoritative) {
                                                    row.target?.let { target ->
                                                        { onOverflowEvent(ThreadEvent.AuthoritativeQueueAction(target, false)) }
                                                    }
                                                } else {
                                                    { onDropQueued(row.queuedMessageId) }
                                                },
                                            status = row.status,
                                            onSendNow =
                                                if (state.runConfig.midTurnInputSupported) {
                                                    if (row.authoritative) {
                                                        row.target?.let { target ->
                                                            { onOverflowEvent(ThreadEvent.AuthoritativeQueueAction(target, true)) }
                                                        }
                                                    } else {
                                                        { onSendQueuedNow(row.queuedMessageId) }
                                                    }
                                                } else {
                                                    null
                                                },
                                        )
                                    is ThreadRow.AgentStartMarker ->
                                        AgentStartMarker(
                                            row.description,
                                            row.finished,
                                            onGoToAgent = { agentNavigation.request(row.agentId) },
                                        )
                                    is ThreadRow.ToolRun ->
                                        Box(Modifier.testTag("tool-run:${row.runId}")) {
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
                                if (authoritative != null) {
                                    childControls[row.listKey(0)].orEmpty().forEach { parentKey ->
                                        AgentChildrenControl(
                                            authoritative,
                                            parentKey,
                                            connected,
                                            retry = { actionPublisher.childrenAction(authoritative, parentKey, true) },
                                            more = { actionPublisher.childrenAction(authoritative, parentKey, false) },
                                        )
                                    }
                                }
                            }
                        }
                        ThreadMessageList(
                            state = listState,
                            viewport = readerViewport,
                            measureBoundaryRow = { row, next ->
                                // Relocation cannot recompose a retired or newly closed body offscreen.
                                if (demandedByKey == null) {
                                    renderRow(row, next, true)
                                } else {
                                    demandedByKey[row.listKey(0)]?.let { current ->
                                        renderRow(current, next?.let { demandedByKey[it.listKey(0)] }, true)
                                    }
                                }
                            },
                            modifier =
                                Modifier.fillMaxSize().olderHistoryPull(listPull).onGloballyPositioned {
                                    val origin = it.positionInWindow()
                                    messageViewport.value = Rect(origin.x, origin.y, origin.x + it.size.width, origin.y + it.size.height)
                                },
                            headerHeight = headerHeight,
                            composerHeight = composerHeight,
                            // Padding follows measured chrome; the drawing viewport continues underneath both bars.
                            contentPadding =
                                PaddingValues(
                                    top = headerHeight + MessageAreaTopInset,
                                    // Match the separately rounded layout gaps, especially at fractional density.
                                    bottom =
                                        with(density) {
                                            (composerHeight.roundToPx() - ComposerTopGap.roundToPx() - restAdjustment.roundToPx())
                                                .coerceAtLeast(0)
                                                .toDp()
                                        },
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
                                    gutter = Modifier.fillMaxWidth().padding(horizontal = ComposerGutter),
                                )
                            }
                            // #1340: a refused answer stays in the slot its card held, above any newer card, until
                            // its X. No frame draws it: Figma 347:6617's Default pill, laid in the page unshadowed.
                            // Figma 668:3054: the pill sits flush on the stream's own top inset, with no extra
                            // gutter above it (#1599) — only a bottom gutter separates it from what follows.
                            if (answerRejected) {
                                item(key = "permission-rejection") {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(start = ComposerGutter, end = ComposerGutter, bottom = 4.dp)
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
                                contentType = { _, row -> row.contentType() },
                            ) { reversedIndex, row ->
                                val chronologicalIndex = rows.size - 1 - reversedIndex
                                renderRow(row, rows.getOrNull(chronologicalIndex + 1), false)
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
                    Box(Modifier.align(Alignment.TopEnd).onSizeChanged { topOverlayHeight = with(density) { it.height.toDp() } }) {
                        ThreadTopOverlay(
                            attentionPill = attentionPill,
                            usageLimit = usageLimit,
                            usageLimitDismissed = usageLimit?.dismissalKey() in dismissedUsageLimits,
                            onDismissUsageLimit = { usageLimit?.let(onDismissUsageLimit) },
                            showRePair = showRePair,
                            onRePair = onRePair,
                            connectionState = connectionState,
                            onRetryConnection = onRetry,
                            modifier =
                                Modifier
                                    .padding(start = ComposerGutter, top = headerHeight + TopOverlayTopGap, end = ComposerGutter),
                            mcpFailure = mcpFailure,
                            onOpenMcpFailure = onOpenMcpFailure,
                            sessionError = sessionError,
                            agent = state.agent,
                            turnOutcome = turnOutcome,
                            onCompact = onCompact,
                            transientError = errorNotices.currentMessage,
                            transientErrorOccurrence = errorNotices.currentOccurrence,
                            confirmation = confirmationNotices.currentMessage,
                            confirmationOccurrence = confirmationNotices.currentOccurrence,
                        )
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
            onVisibilityChanged = { typeAheadVisible = it },
        )
    }
    if (backgroundTasksOpen) {
        key(interactionScope) {
            BackgroundTaskPanel(
                roster = taskRoster,
                onDismiss = { backgroundTasksOpen = false },
                stopSupported = state.backgroundTaskStopSupported,
                expandedTaskIds =
                    if (authoritative == null) {
                        state.expandedBackgroundTaskIds
                    } else {
                        taskRoster
                            ?.tasks
                            .orEmpty()
                            .filter { it.toolCallId in authoritativeExpandedTasks }
                            .map { it.taskId }
                            .toSet()
                    },
                pendingTaskIds = state.pendingBackgroundTaskIds,
                onEvent = { event ->
                    if (authoritative == null) {
                        onOverflowEvent(event)
                    } else {
                        val taskId =
                            when (event) {
                                is ThreadEvent.BackgroundTaskToggle -> event.taskId
                                is ThreadEvent.BackgroundTaskStop -> event.taskId
                                else -> null
                            }
                        val task = taskRoster?.tasks?.firstOrNull { it.taskId == taskId && !it.isFinished }
                        val itemId = task?.toolCallId
                        if (itemId != null) {
                            if (event is ThreadEvent.BackgroundTaskToggle) {
                                authoritativeExpandedTasks =
                                    if (itemId in
                                        authoritativeExpandedTasks
                                    ) {
                                        authoritativeExpandedTasks - itemId
                                    } else {
                                        authoritativeExpandedTasks +
                                            itemId
                                    }
                            } else if (state.backgroundTaskStopSupported) {
                                onOverflowEvent(ThreadEvent.BackgroundTaskStop(requireNotNull(taskId), authoritative.scope, itemId))
                            }
                        }
                    }
                },
            )
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
    // Reuse the ticket-required shared full-height shell; the Figma confirmation supplies its copy and actions.
    state.runConfig.agentSwitch?.takeUnless { it.sending }?.let { confirmation ->
        MobileModal(
            title = stringResource(R.string.thread_agent_switch_title, agentName(confirmation.choice.agent)),
            onDismissRequest = { onOverflowEvent(ThreadEvent.AgentSwitchDismiss) },
            onSubmit = { onOverflowEvent(ThreadEvent.AgentSwitchConfirm) },
            submitLabel = stringResource(R.string.thread_agent_switch_action),
        ) {
            Text(
                stringResource(
                    R.string.thread_agent_switch_body,
                    agentName(confirmation.source),
                    confirmation.choice.label,
                    agentName(confirmation.choice.agent),
                ),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center,
            )
        }
    }
    if (sheetVisible) {
        StatusSheet(
            choices = state.runConfig.choices,
            menuAvailable = state.runConfig.menuAvailable,
            // The producer's own cut plus this client's render cap, summed for display only — each keeps
            // its own field on the state so neither is ever recomputed from the other.
            notListedModels = state.runConfig.droppedModels + state.runConfig.hiddenChoices,
            selectedModel =
                state.runConfig.agentSwitch
                    ?.takeIf { it.sending }
                    ?.choice
                    ?.value
                    ?: state.runConfig.selectedChoice?.value
                    ?: state.runConfig.confirmedSwitchChoice
                        ?.takeIf { it.agent == state.runConfig.agent }
                        ?.value,
            selectedAgent =
                state.runConfig.agentSwitch
                    ?.takeIf { it.sending }
                    ?.choice
                    ?.agent ?: state.runConfig.agent,
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
            enabled = state.runConfig.writable && connected && state.runConfig.agentSwitch?.sending != true,
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
    if (state.contextSheetOpen) {
        ContextSummarySheet(state = state, onEvent = onOverflowEvent)
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
                confirmationNotices.enqueue(confirmationScope, reason)
            }
        }
        ModalUiState.Hidden -> Unit
    }
}

@Composable
private fun AuthoritativeSummaryRow(summary: String) {
    SelectionContainer {
        Text(
            summary,
            modifier = Modifier.padding(horizontal = MessageContentGutter, vertical = MessageAreaRowSpacing),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun AgentChildrenControl(
    p: AuthoritativeThreadPresentation,
    key: String,
    connected: Boolean,
    retry: () -> Unit,
    more: () -> Unit,
) {
    val parent = p.childParent(key) ?: return
    val page = p.sourceSnapshot.childPages[parent.id to parent.rev]
    val load = p.sourceSnapshot.childLoads[parent.id to parent.rev]
    Column(Modifier.padding(horizontal = MessageContentGutter).testTag("agent-children:${parent.id}")) {
        val label =
            when {
                !connected && (page == null || page.cursor != null) -> R.string.agent_children_offline
                load == de.pyryco.mobile.data.repository.BodyLoad.Retry -> R.string.agent_children_failed
                load == de.pyryco.mobile.data.repository.BodyLoad.Loading || page == null -> R.string.agent_children_loading
                page.items.isEmpty() -> R.string.agent_children_empty
                else -> null
            }
        label?.let {
            Text(
                stringResource(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (connected && load == de.pyryco.mobile.data.repository.BodyLoad.Retry) {
            androidx.compose.material3.TextButton(onClick = retry) { Text(stringResource(R.string.tool_body_retry)) }
        } else if (connected && load == null && page?.cursor != null) {
            androidx.compose.material3.TextButton(onClick = more) { Text(stringResource(R.string.agent_children_more)) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThreadRowContent(
    relocationSpec: BringIntoViewSpec,
    modifier: Modifier = Modifier,
    toolExpansion: ToolCallExpansion? = null,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides relocationSpec, LocalToolCallExpansion provides toolExpansion) {
        Column(modifier) { content() }
    }
}

/** Keep the drawing viewport full size while relocating focus between the measured chrome surfaces. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThreadMessageList(
    state: LazyListState,
    viewport: ThreadListViewport,
    measureBoundaryRow: @Composable (ThreadRow, ThreadRow?) -> Unit,
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
        SubcomposeLayout(modifier) { constraints ->
            // Old boundary rows are measured but never placed, drawn, or exposed as input targets.
            // Use the same renderer and width as LazyColumn, with its unbounded item height.
            val itemConstraints = constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)
            val boundaryHeight =
                viewport.boundaryRows.sumOf { (row, next) ->
                    subcompose(row.listKey(0)) { measureBoundaryRow(row, next) }
                        .sumOf { it.measure(itemConstraints).height }
                }
            viewport.onBoundaryMeasured(boundaryHeight)
            val list =
                subcompose(Unit) {
                    LazyColumn(
                        state = state,
                        modifier = Modifier.onGloballyPositioned { viewport.onPositioned() },
                        reverseLayout = true,
                        contentPadding = contentPadding,
                        verticalArrangement = verticalArrangement,
                        content = content,
                    )
                }.single().measure(constraints)
            layout(list.width, list.height) { list.placeRelative(0, 0) }
        }
    }
}

/**
 * Figma `16:8`'s `Status area` (`111:3525`) — the composer's top band, carrying whichever live turn-status
 * signal is current (#643 moved this block here from the foot of the content `Column`).
 *
 * One status slot, top wins, decided by [statusArm] (#1311): connecting / reconnecting → resetting →
 * api-retry → compaction → stall → thinking / working / running tool. While a turn runs the
 * band always has a reading, as desktop's `workingIndicatorState` keeps one up. While the link is
 * unavailable, turn readings cannot be refreshed; Offline is instead shown in the Top overlay as a retry
 * pill.
 * No two may ever stack. Single-sourcing the mutual exclusion here, in the screen, is deliberate:
 * `isThinking` stays defined as the `turn_state` phase (other tests assert it directly), so suppressing it
 * at its source would make the VM's contract lie.
 *
 * A running Reset session's phase (#872) is the top turn arm, above api-retry since #1311 as on desktop:
 * the wrap-up is itself a claude turn, so without this ordering the reset the user started would read as
 * generic thinking or as a compaction inside it. api-retry (#594) is the "something is going wrong" signal, and the benign affordances below must
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
    switching: ThreadAgentSwitch?,
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
        if (waitingForAnswers && switching == null) {
            Image(
                painterResource(R.drawable.ic_question_glyph),
                contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                modifier = Modifier.size(14.dp, 16.dp),
            )
        } else {
            ThreadStatusGlyph(turning = switching != null || isBusy || localSendStage != LocalSendStage.None)
        }
        // Always present, so the pill keeps the band's right end while no reading shows.
        Box(Modifier.weight(1f).testTag(STATUS_READING_TEST_TAG)) {
            if (switching != null) {
                Text(
                    stringResource(
                        if (resetting?.phase ==
                            ResetStatus.Phase.WrappingUp
                        ) {
                            R.string.thread_agent_switch_wrapping_up
                        } else {
                            R.string.thread_agent_switch_pending
                        },
                        agentName(switching.choice.agent),
                        agentName(switching.source),
                    ),
                    modifier = Modifier.padding(vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else if (waitingForAnswers || waitingForPermission) {
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
                            isThinking = isThinking,
                            isBusy = isBusy,
                            localSendStage = localSendStage,
                            hasOpenTool = runningTool != null,
                        ),
                    apiRetry = apiRetry,
                    resetting = resetting,
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

/**
 * The extra trailing space under the newest row to drop from the composer's bottom content padding, so the
 * gap to the status band reads as the frames' 16dp regardless of which row kind sits last (#1630). A plain
 * `BubbleFrame` rests at 4dp over that baseline; a bubble carrying attachments and a queued row each rest
 * further over it (16dp, 8dp) by their own extra bottom space, which this backs back out. Any tool-call row
 * — nested in a background Agent block or not — already rests at the documented gap with no adjustment: its
 * own trailing space ([de.pyryco.mobile.ui.conversations.components.MessageRowVerticalSpacing]) already
 * equals the target, so backing out a further 12dp (as this once did for a tool call carrying a non-null
 * `agentBlockId`) double-subtracted and pulled the row under the band — the newest-row cutoff seen on
 * release 4592, screen-proofed in `BackgroundAgentRestGapTest`. Only the newest rendered row matters —
 * anything behind it does not touch the band.
 */
internal fun ordinaryMessageRestAdjustment(
    row: ThreadRow?,
    promptRows: Int,
): Dp {
    if (promptRows != 0) return 0.dp
    if (row is ThreadRow.Queued) return 8.dp
    val delivered = (row as? ThreadRow.Delivered) ?: return 0.dp
    val message = (delivered.item as? ThreadItem.MessageItem)?.message ?: return 0.dp
    return when {
        message.attachments.isNotEmpty() -> 16.dp
        message.toolCall == null -> 4.dp
        else -> 0.dp
    }
}

/** Which one reading the status band shows (#1311); see [statusArm]. */
internal enum class StatusArm {
    None,
    Connection,
    Resetting,
    ApiRetry,
    Compacting,
    Stalled,
    Thinking,
    Working,
    RunningTool,
    Sending,
    Waiting,
}

/**
 * The status band's one arm order (#1311), desktop's `workingIndicatorState` and
 * `workingIndicatorStateWithLocalSend` with Mobile's connection arm at the top. Top wins: connection,
 * Reset session, api-retry, compaction, stall, then the running turn — an open tool while busy, else thinking, else working — and last the
 * local-send window, which reads Sending or Waiting for the conversation's agent. Offline
 * returns [StatusArm.None]: the Top overlay's retry pill owns it.
 *
 * Turn outcomes live in the top overlay and do not take part in status selection (#1603).
 */
internal fun statusArm(
    connectionState: ConnectionState,
    resetting: Boolean,
    apiRetrying: Boolean,
    isCompacting: Boolean,
    isStalled: Boolean,
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
    val lineHeight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier =
                Modifier
                    // Reserve screen clearance without counting transparent margins as dialog content.
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints.offset(horizontal = -48.dp.roundToPx()))
                        layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
                    }.widthIn(max = 316.dp)
                    .fillMaxWidth()
                    .testTag("delete-dialog-surface"),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
                Text(
                    stringResource(R.string.delete_dialog_title),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    style = MaterialTheme.typography.headlineSmall.copy(lineHeightStyle = lineHeight),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.delete_dialog_body, displayName),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeightStyle = lineHeight),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    // Figma 673:3672 reserves 40 dp; the 48 dp targets extend into the blank gaps.
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.frameHeightWithTouchOverflow(top = 4.dp, bottom = 4.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text(
                            stringResource(R.string.delete_dialog_cancel),
                            style = MaterialTheme.typography.labelLarge.copy(lineHeightStyle = lineHeight),
                        )
                    }
                    TextButton(
                        onClick = onConfirm,
                        modifier = Modifier.frameHeightWithTouchOverflow(top = 4.dp, bottom = 4.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text(
                            stringResource(R.string.delete_dialog_confirm),
                            style = MaterialTheme.typography.labelLarge.copy(lineHeightStyle = lineHeight),
                        )
                    }
                }
            }
        }
    }
}

private fun ThreadItem.timestamp(): Instant =
    when (this) {
        is ThreadItem.MessageItem -> message.timestamp
        is ThreadItem.SessionBoundary -> occurredAt
        is ThreadItem.UnrecognizedMessage -> occurredAt
        is ThreadItem.Banner -> occurredAt
        is ThreadItem.CompactionBoundary -> occurredAt
        is ThreadItem.ModelRefusal -> occurredAt
        is ThreadItem.BackgroundTaskLifecycle -> occurredAt
        is ThreadItem.StoppedTurn -> occurredAt
    }

internal fun ThreadUiState.toChannelInfoUiModel(now: Instant = Clock.System.now()): ChannelInfoUiModel =
    ChannelInfoUiModel(
        conversationName = displayName,
        workspacePath = workspacePath,
        createdLabel =
            items.firstOrNull { it !is ThreadItem.BackgroundTaskLifecycle }?.let { formatRelativeTime(it.timestamp(), now) } ?: "—",
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
