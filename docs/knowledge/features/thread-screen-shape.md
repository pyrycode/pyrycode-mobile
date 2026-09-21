# Thread screen — shape

Split out of [Thread screen](thread-screen.md) on 2026-09-05 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [Thread screen](thread-screen.md); see that document for what it does, its edge cases and its links.

## Shape

```kotlin
// ThreadViewModel.kt
data class ThreadUiState(
    val conversationId: String,
    val displayName: String,
    val isPromoted: Boolean = false,                  // new in #137
    val hasMessages: Boolean = false,                 // new in #137
    val workspaceLabel: String = "scratch",           // new in #137; label-first (workspaceDisplayName) since #722
    val workspacePickerVisible: Boolean = false,      // new in #137
    val showRenameDialog: Boolean = false,            // new in #141
    val saveAsChannelDialog: SaveAsChannelDialogState? = null,  // new in #142 — nullable sub-state carrier
    val items: List<ThreadItem> = emptyList(),        // new in #246
    val queuedMessages: List<QueuedMessage> = emptyList(),  // new in #461 — the queued-message backlog (#460 observeQueue); see queued-backlog-section.md
    val channelInfoOpen: Boolean = false,             // new in #226 — ChannelInfoSheet visibility
    val deleteConfirmVisible: Boolean = false,        // new in #227 — delete-confirmation AlertDialog visibility
    val workspacePath: String = "",                   // new in #226 — conv.cwd (full path; cf. leaf-only workspaceLabel)
    val lastUsedAt: Instant? = null,                  // new in #226 — conv.lastUsedAt; formatted in screen layer
    val sessionCount: Int = 0,                        // new in #226 — conv.sessionHistory.size
    val selectedModel: Model = Model.OPUS_4_7,        // new in #145, retyped String→Model in #253
    val selectedEffort: Effort = Effort.HIGH,         // new in #145 as `effort: String`, retyped String→Effort in #229
    val yoloEnabled: Boolean = false,                 // new in #229 — always false, ignores AppPreferences.defaultYolo
    // model/effort/tokenPercent trio from #145 removed in #603 — see thread-status-row.md
)

class ThreadViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: ConversationRepository,         // private val since #188
    private val connectionStateSource: ConnectionStateSource, // new in #201
    private val appPreferences: AppPreferences,             // new in #253
) : ViewModel() {
    private val conversationId: String =
        savedStateHandle.get<String>("conversationId").orEmpty()

    private val pendingWorkspacePicker = MutableStateFlow(false)  // new in #137

    private val pendingRenameDialog = MutableStateFlow(false)     // new in #141

    private val pendingSaveAsChannelDialog =                      // new in #142 — nullable sub-state, not Boolean
        MutableStateFlow<SaveAsChannelDialogState?>(null)

    private val pendingChannelInfo = MutableStateFlow(false)      // new in #226 — ChannelInfoSheet visibility

    private val pendingDeleteConfirm = MutableStateFlow(false)    // new in #227 — delete-confirmation dialog visibility

    private val navigationChannel =                              // new in #227 — one-shot pop-back, mirrors ChannelListViewModel
        Channel<ThreadNavigation>(capacity = Channel.BUFFERED)
    val navigationEvents: Flow<ThreadNavigation> =               // new in #227 — collected in MainActivity
        navigationChannel.receiveAsFlow()

    private val modelOverride = MutableStateFlow<Model?>(null)    // new in #253 — null ⇒ "use Settings default"

    private val selectedModelFlow: Flow<Model> =                  // new in #253
        combine(appPreferences.defaultModel, modelOverride) { default, override -> override ?: default }

    private val effortOverride = MutableStateFlow<Effort?>(null)  // new in #229 — null ⇒ "use Settings default"

    private val selectedEffortFlow: Flow<Effort> =                // new in #229 — mirrors selectedModelFlow shape
        combine(appPreferences.defaultEffort, effortOverride) { default, override -> override ?: default }

    private val yoloEnabled = MutableStateFlow(false)             // new in #229 — hardcoded false, NO defaultYolo read

    private val runConfigFlow: Flow<RunConfig> =                  // new in #229 — pre-combiner keeps outer combine at 5-arity
        combine(selectedModelFlow, selectedEffortFlow, yoloEnabled) { model, effort, yolo ->
            RunConfig(model, effort, yolo)
        }

    private val transientDialogs: Flow<TransientDialogs> =        // new in #142; widened 2→3 in #226, 3→4 in #227 — keeps outer combine at 5-arity
        combine(
            pendingRenameDialog, pendingSaveAsChannelDialog, pendingChannelInfo, pendingDeleteConfirm,
        ) { rename, save, channelInfo, deleteConfirm ->
            TransientDialogs(
                renameVisible = rename, saveAsChannel = save,
                channelInfoOpen = channelInfo, deleteConfirmVisible = deleteConfirm,
            )
        }

    // new in #337 — folds the #313 finished-message projection together with the live assistant_delta
    // stream (liveSessionEvents: the #406 coordinator seam, an injected ctor flow) so an in-flight turn
    // renders as one growing isStreaming MessageItem that settles into the finished message on turn_end.
    // With the inert emptyFlow() default this is just the observeMessages arm. See streaming-assistant-turns.md.
    private val threadItems: Flow<List<ThreadItem>> =
        merge(
            repository.observeMessages(conversationId).map(ThreadInput::Finished),
            liveSessionEvents.map(ThreadInput::Live),
        ).scan(ThreadFold(emptyList(), null)) { fold, input -> fold.reduce(input, conversationId) }
            .map { it.render() }
            .distinctUntilChanged()

    // new in #461 — folds the queued-message backlog (#460 observeQueue) together with threadItems into a
    // file-private ThreadContent(items, queued), so the 5-arm typed `state` combine keeps ONE content arm
    // (the threadItems slot below becomes threadContent; queuedMessages = content.queued). The queue is
    // thread content, so it rides ThreadUiState — NOT a sibling StateFlow like isStalled/isThinking; this
    // is the only edit, MainActivity is untouched. See queued-backlog-section.md.
    private val threadContent: Flow<ThreadContent> =
        combine(threadItems, repository.observeQueue(conversationId)) { items, queued ->
            ThreadContent(items, queued)
        }

    val state: StateFlow<ThreadUiState> =
        combine(                                                  // shape since #137; widened 3→4 in #253; widened 4→5 in #141; arm 4 bundled in #142; arm 5 bundled in #229
            repository.observeConversations(ConversationFilter.All),
            threadContent,                                        // arm 2: was observeMessages (#337 threadItems); folded with observeQueue → ThreadContent in #461
            pendingWorkspacePicker,
            transientDialogs,                                     // new in #142 — bundles pendingRenameDialog + pendingSaveAsChannelDialog
            runConfigFlow,                                        // new in #229 — bundles selectedModelFlow + selectedEffortFlow + yoloEnabled
        ) { conversations, content, pickerVisible, dialogs, runConfig ->
            val conv = conversations.firstOrNull { it.id == conversationId }
            ThreadUiState(
                conversationId = conversationId,
                displayName = conv?.displayName() ?: conversationId,
                isPromoted = conv?.isPromoted ?: false,
                hasMessages = content.items.any { it is ThreadItem.MessageItem },  // sourced from content.items since #461
                workspaceLabel = workspaceDisplayName(cwd = conv?.cwd ?: "", label = conv?.workspaceLabel),  // #137 basename-only rule replaced by the shared label-first rule in #722
                workspacePickerVisible = pickerVisible,
                showRenameDialog = dialogs.renameVisible,         // new in #141, destructured from bundle in #142
                saveAsChannelDialog = dialogs.saveAsChannel,      // new in #142
                items = content.items,                            // new in #246; sourced from the ThreadContent fold since #461
                queuedMessages = content.queued,                  // new in #461 — the queued-message backlog (queued-backlog-section.md)
                channelInfoOpen = dialogs.channelInfoOpen,        // new in #226, destructured from the TransientDialogs bundle
                deleteConfirmVisible = dialogs.deleteConfirmVisible,  // new in #227
                workspacePath = conv?.cwd ?: "",                  // new in #226
                lastUsedAt = conv?.lastUsedAt,                    // new in #226
                sessionCount = conv?.sessionHistory?.size ?: 0,   // new in #226
                selectedModel = runConfig.model,                  // new in #145, rewired from STUB_MODEL → prefs+override in #253, bundled in #229
                selectedEffort = runConfig.effort,                // new in #145 as STUB_EFFORT, rewired to prefs+override in #229
                yoloEnabled = runConfig.yoloEnabled,              // new in #229
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue =
                ThreadUiState(
                    conversationId = conversationId,
                    displayName = conversationId,
                ),
        )

    val connectionState: StateFlow<ConnectionState> =     // new in #201
        connectionStateSource
            .observe()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ConnectionState.Connected,
            )

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        launchGuardedRepoCall {                           // guarded #490
            repository.sendMessage(state.value.conversationId, text)
        }
    }

    fun retry() {                                          // new in #201
        viewModelScope.launch { connectionStateSource.retry() }
    }

    fun onWorkspaceChipTapped() {                          // new in #137
        pendingWorkspacePicker.value = true
    }

    fun onWorkspacePicked(path: String) {                  // new in #137
        pendingWorkspacePicker.value = false
        launchGuardedRepoCall {                            // guarded #490
            repository.changeWorkspace(conversationId, path)
        }
    }

    fun onWorkspacePickerDismissed() {                     // new in #137
        pendingWorkspacePicker.value = false
    }

    fun onModelSelected(model: Model) {                    // new in #253 — synchronous, does not write prefs
        modelOverride.value = model
    }

    fun onEffortSelected(effort: Effort) {                 // new in #229 — synchronous, does not write prefs
        effortOverride.value = effort
    }

    fun onYoloToggled(enabled: Boolean) {                  // new in #229 — synchronous, single writer of yoloEnabled
        yoloEnabled.value = enabled
    }

    private data class RunConfig(                          // new in #229 — file-private bundle for the runConfigFlow pre-combine
        val model: Model,
        val effort: Effort,
        val yoloEnabled: Boolean,
    )

    private data class ThreadContent(                      // new in #461 — file-private bundle for the threadContent pre-combine
        val items: List<ThreadItem>,
        val queued: List<QueuedMessage>,
    )

    // companion object (STUB_MODEL, STUB_EFFORT, STUB_TOKEN_PERCENT) fully deleted in #603 —
    // STUB_MODEL and STUB_EFFORT went earlier (#253, #229); STUB_TOKEN_PERCENT was its last member.
}

private fun Conversation.displayName(): String =
    name?.takeIf { it.isNotBlank() }
        ?: if (isPromoted) "Untitled channel" else "Untitled discussion"

// private fun Conversation.workspaceLabel(): String, new in #137, deleted in #722 — replaced by the
// shared de.pyryco.mobile.ui.workspace.workspaceDisplayName(cwd, label) called above; see workspace-chip.md
// § `workspaceLabel` derivation for the label-first rule and the MAX_WORKSPACE_LABEL_CHARS clamp.

// ThreadScreen.kt
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    state: ThreadUiState,
    onBack: () -> Unit,
    onSendMessage: (String) -> Unit,         // new in #188, no default
    connectionState: ConnectionState,        // new in #201, no default
    onRetry: () -> Unit,                     // new in #201, no default
    modifier: Modifier = Modifier,
    onTitleClick: () -> Unit = {},
    onOverflowEvent: (ThreadEvent) -> Unit = {},      // new in #252 — renamed in place from onOverflowClick (#139)
    onShowLiteralScreen: () -> Unit = {},             // new in #382 — pure navigation to the literal_screen destination (not a ThreadEvent)
    onModelSelected: (Model) -> Unit = {},            // new in #254 — replaces onExpandClick from #145
    onEffortSelected: (Effort) -> Unit = {},          // new in #229
    onYoloToggled: (Boolean) -> Unit = {},            // new in #229
    onWorkspaceChipTapped: () -> Unit = {},           // new in #137
    onWorkspacePicked: (String) -> Unit = {},         // new in #137
    onWorkspacePickerDismissed: () -> Unit = {},      // new in #137
    modalState: ModalUiState = ModalUiState.Hidden,   // new in #446 — hoisted app-level currentModal (#445)
    armedOptionId: String? = null,                    // new in #452 — VM-scoped armed non-default option (#451)
    modalSendErrors: Flow<Unit> = emptyFlow(),        // new in #452 — payload-free one-shot send-failure (#451)
    onModalOption: (String) -> Unit = {},             // new in #446 — LIVE since #452 → vm::onModalOption (passes ModalOption.id)
    onModalCancel: () -> Unit = {},                   // new in #446 — LIVE since #452 → vm::onModalCancel
    onDropQueued: (Long) -> Unit = {},                // new in #467 — LIVE → vm::onDropQueued (passes QueuedMessage.id); drives QueuedBacklog onDrop
) {
    var sheetVisible by rememberSaveable { mutableStateOf(false) }   // new in #254
    var overflowExpanded by rememberSaveable { mutableStateOf(false) }   // new in #252
    val snackbarHostState = remember { SnackbarHostState() }   // new in #446 — dismiss-reason + (#452) send-error host
    // #452: collect the payload-free modalSendErrors into the snackbar (fixed local string — never the payload)
    val modalSendFailedMessage = stringResource(R.string.modal_send_failed)
    LaunchedEffect(modalSendErrors, snackbarHostState) {
        modalSendErrors.collect { snackbarHostState.showSnackbar(modalSendFailedMessage) }
    }
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },   // new in #446
        topBar = {
            ThreadTopAppBar(
                title = state.displayName,
                onBack = onBack,
                onTitleClick = onTitleClick,
                onOverflowClick = { overflowExpanded = true },        // wired internally in #252
                overflowExpanded = overflowExpanded,                  // new in #252
                onOverflowDismiss = { overflowExpanded = false },     // new in #252
                onOverflowEvent = onOverflowEvent,                    // new in #252
                onShowLiteralScreen = onShowLiteralScreen,            // new in #382 — forwarded to the overflow menu
                mutationsSupported = state.mutationsSupported,        // new in #508 — false in relay mode hides the four mutation items
            )
        },
        bottomBar = {                                  // wrapped in Column in #145
            Column(modifier = Modifier.fillMaxWidth()) {
                ThreadStatusRow(
                    model = state.selectedModel.label(),         // state.model → state.selectedModel.label() in #253
                    effort = state.selectedEffort.label(),       // state.effort → state.selectedEffort.label() in #229
                    onExpandClick = { sheetVisible = true },     // wired internally in #254
                )
                ThreadInputBar(onSend = onSendMessage)
            }
        },
    ) { inner ->
        Column(                              // wrapper introduced in #201
            modifier = Modifier.padding(inner).fillMaxSize(),
        ) {
            ConnectionBanner(state = connectionState, onRetry = onRetry)
            if (!state.isPromoted && !state.hasMessages) {       // new in #137
                WorkspaceChip(
                    workspaceLabel = state.workspaceLabel,
                    onClick = onWorkspaceChipTapped,
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (!state.hasMessages) {                            // branch added in #138
                EmptyThreadState(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 24.dp),
                )
            } else {
                val reversedItems = state.items.asReversed()              // hoisted in #136
                val cutoffChronologicalIndex =                            // new in #136
                    remember(state.items) { mostRecentSessionBoundaryIndex(state.items) }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    reverseLayout = true,
                ) {
                    itemsIndexed(                        // body filled in #246, indexed in #136
                        items = reversedItems,
                        key = { _, item ->
                            when (item) {
                                is ThreadItem.MessageItem ->
                                    "msg:${item.message.id}"
                                is ThreadItem.SessionBoundary ->
                                    "boundary:${item.previousSessionId}->${item.newSessionId}"
                            }
                        },
                    ) { reversedIndex, item ->
                        val chronologicalIndex = state.items.size - 1 - reversedIndex
                        val rowAlpha =
                            if (chronologicalIndex < cutoffChronologicalIndex)
                                ABOVE_DELIMITER_ALPHA
                            else 1f
                        Box(modifier = Modifier.alpha(rowAlpha)) {        // wrap added in #136
                            when (item) {
                                is ThreadItem.MessageItem ->
                                    MessageBubble(message = item.message)
                                is ThreadItem.SessionBoundary ->
                                    SessionBoundaryDelimiter(boundary = item)
                            }
                        }
                    }
                }
            }
        }
    }
    WorkspacePicker(                              // Scaffold sibling, new in #137
        visible = state.workspacePickerVisible,
        onPicked = onWorkspacePicked,
        onDismiss = onWorkspacePickerDismissed,
    )
    if (state.showRenameDialog) {                 // Scaffold sibling, new in #141
        RenameDialog(
            initialName = state.displayName,
            onSubmit = { onOverflowEvent(ThreadEvent.RenameSubmit(it)) },
            onDismiss = { onOverflowEvent(ThreadEvent.RenameDismiss) },
        )
    }
    state.saveAsChannelDialog?.let { dialogState ->   // Scaffold sibling, new in #142
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
    if (sheetVisible) {                           // Scaffold sibling, new in #254; widened in #229
        StatusSheet(
            selectedModel = state.selectedModel,
            onModelSelected = { model ->
                onModelSelected(model)
                sheetVisible = false              // auto-close on selection
            },
            selectedEffort = state.selectedEffort,          // new in #229
            onEffortSelected = { effort ->                  // new in #229 — auto-close (discrete pick)
                onEffortSelected(effort)
                sheetVisible = false
            },
            yoloEnabled = state.yoloEnabled,                // new in #229
            onYoloToggled = onYoloToggled,                  // new in #229 — NO auto-close (toggle state-change)
            onDismiss = { sheetVisible = false },
        )
    }
    if (state.channelInfoOpen) {                  // Scaffold sibling, new in #226; gated on the VM-driven flag
        ChannelInfoSheet(
            model = state.toChannelInfoUiModel(),
            mutationsSupported = state.mutationsSupported, // new in #508 — false in relay mode hides the Actions section
            onRename = {                                      // delegate then dismiss
                onOverflowEvent(ThreadEvent.Rename)
                onOverflowEvent(ThreadEvent.ChannelInfoDismiss)
            },
            onChangeWorkspace = {
                onOverflowEvent(ThreadEvent.ChangeWorkspace)
                onOverflowEvent(ThreadEvent.ChannelInfoDismiss)
            },
            onArchive = { onOverflowEvent(ThreadEvent.Archive) },            // close + archive + pop, since #227
            onDelete = { onOverflowEvent(ThreadEvent.Delete) },              // opens DeleteConfirmationDialog, since #227
            onInstallMemoryPlugin = { /* TODO: Phase 3+ */ },                // no event, no dismiss
            onDismiss = { onOverflowEvent(ThreadEvent.ChannelInfoDismiss) },
        )
    }
    if (state.deleteConfirmVisible) {             // Scaffold sibling, new in #227; layers over the still-open sheet
        DeleteConfirmationDialog(
            displayName = state.displayName,
            onConfirm = { onOverflowEvent(ThreadEvent.DeleteConfirm) },
            onDismiss = { onOverflowEvent(ThreadEvent.DeleteDismiss) },  // scrim/back-tap = cancel
        )
    }
    when (modalState) {                           // Scaffold sibling, new in #446 — the SEVENTH sibling
        is ModalUiState.Open ->                   // separate-surface overlay (NOT a LazyColumn row)
            PermissionModalOverlay(open = modalState, onOption = onModalOption, onCancel = onModalCancel)
        is ModalUiState.Dismissed ->              // no overlay; surface the mapped resolution reason ONCE
            LaunchedEffect(modalState.modalId) {  // keyed on modalId (sticky terminal state) → fires once
                snackbarHostState.showSnackbar(dismissReasonText(modalState.source))
            }
        ModalUiState.Hidden -> Unit
    }
}

// ThreadScreen.kt — private confirm dialog (new in #227), mirrors PromotionConfirmationDialog (#78)
@Composable
private fun DeleteConfirmationDialog(displayName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_dialog_title)) },
        text = { Text(stringResource(R.string.delete_dialog_body, displayName)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.delete_dialog_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.delete_dialog_cancel)) } },
    )
}

// ThreadScreen.kt — the permission-modal overlay private composables (base #446, made live #452). Full doc:
// features/permission-modal-overlay.md. BasicAlertDialog with FLAG_SECURE on its OWN window.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermissionModalOverlay(open: ModalUiState.Open, armedOptionId: String?, onOption: (String) -> Unit, onCancel: () -> Unit) {
    BasicAlertDialog(
        onDismissRequest = onCancel,              // inert while both dismiss flags false; cancel = the explicit button
        properties = DialogProperties(
            securePolicy = SecureFlagPolicy.SecureOn,  // FLAG_SECURE on the dialog's own window (load-bearing)
            dismissOnBackPress = false, dismissOnClickOutside = false,  // gate ignores stray taps
        ),
    ) {
        // #452: now that taps are live, harden the dialog's OWN window against tapjacking (no semantics node → code-review-verified)
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { dialogWindow?.decorView?.filterTouchesWhenObscured = true }
        Surface(shape = AlertDialogDefaults.shape, color = AlertDialogDefaults.containerColor, tonalElevation = AlertDialogDefaults.TonalElevation) {
            Column(Modifier.padding(24.dp)) {
                Text(open.title, style = MaterialTheme.typography.headlineSmall)    // verbatim plain Text — never MarkdownText
                Text(open.prompt, style = MaterialTheme.typography.bodyMedium)      // verbatim plain Text
                open.options.forEach { option ->                                   // wire array order = display order
                    ModalOptionButton(option.label,
                        isDefault = option.id == open.defaultOptionId,
                        isArmed = option.id == armedOptionId,                      // #452: reflects the VM's armed option — no UI-side arming
                        onClick = { onOption(option.id) })                         // every tap forwards verbatim
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onCancel, Modifier.align(Alignment.End)) { Text(stringResource(R.string.modal_cancel)) }  // #452: explicit Cancel
            }
        }
    }
}

@Composable
private fun ModalOptionButton(label: String, isDefault: Boolean, isArmed: Boolean, onClick: () -> Unit) {
    // #452: stateless 3-way, isArmed precedence. armed non-default → FilledTonalButton (below the default's
    // emphasis) + stateDescription "Tap again to confirm"; default (fail-safe-deny) → filled Button +
    // stateDescription "Default"; else → OutlinedButton. No remember-based arm (the arm lives on the VM).
    when {
        isArmed -> FilledTonalButton(onClick, Modifier.fillMaxWidth().semantics { stateDescription = … }) { Text(label) }
        isDefault -> Button(onClick, Modifier.fillMaxWidth().semantics { stateDescription = … }) { Text(label) }
        else -> OutlinedButton(onClick, Modifier.fillMaxWidth()) { Text(label) }
    }
}

@Composable
private fun dismissReasonText(source: String): String =   // mapped LOCAL string, never echoes the wire token
    when (source) {
        "remote" -> stringResource(R.string.modal_dismissed_remote)
        "local" -> stringResource(R.string.modal_dismissed_local)
        "timeout" -> stringResource(R.string.modal_dismissed_timeout)
        else -> stringResource(R.string.modal_dismissed_resolved)   // graceful forward-compat fallback
    }

// ThreadScreen.kt — pure mapper + timestamp helper, near mostRecentSessionBoundaryIndex (new in #226)
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

// ThreadTopAppBar.kt
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadTopAppBar(
    title: String,
    onBack: () -> Unit,
    onTitleClick: () -> Unit,
    onOverflowClick: () -> Unit,
    overflowExpanded: Boolean,                        // new in #252
    onOverflowDismiss: () -> Unit,                    // new in #252
    onOverflowEvent: (ThreadEvent) -> Unit,           // new in #252
    onShowLiteralScreen: () -> Unit,                  // new in #382 — required; forwarded to ThreadOverflowMenu
    modifier: Modifier = Modifier,
)
```

`ThreadUiState` is still a **`data class`, not a `sealed interface`.** The canonical pattern in this codebase (`ChannelListUiState`, `DiscussionListUiState`, `ArchivedDiscussionsUiState`) is sealed `Loading | Empty | Loaded | Error` precisely because each represents a real async data-load stage; this screen still has zero `Loading` / `Error` distinction worth modelling (the `displayName` lookup falls back to the raw id rather than failing, so there is no error state to project). The first downstream ticket that introduces an error path or a "load-bearing waiting" frame is the one that widens to a sealed envelope; the current `data class` becomes the `Loaded` variant via grep-replace. Post-[#227](../codebase/227.md) the class carried eighteen fields, including `tokenPercent`; the additions since #145 (`showRenameDialog` from #141, `saveAsChannelDialog` from #142, `yoloEnabled` from #229, `channelInfoOpen` / `workspacePath` / `lastUsedAt` / `sessionCount` from #226, and `deleteConfirmVisible` from #227) all default so the pre-existing `assertEquals(ThreadUiState(...), vm.state.value)` test cases compile unchanged. [#603](../codebase/603.md) has since removed `tokenPercent` (see below); later tickets not covered by this ticket note have added further fields (`currentSessionId`, `mutationsSupported`) not reflected in the field count above. The `model: String` field was retyped to `selectedModel: Model` in [#253](../codebase/253.md) and the `effort: String` field was similarly retyped to `selectedEffort: Effort` in [#229](../codebase/229.md) — both as part of typing the Status Sheet's selection contracts; the fields' data-class default positions are preserved.

`ThreadScreen`'s signature is **`(state, onBack, onSendMessage, connectionState, onRetry, modifier, isThinking, isStalled, onTitleClick, onOverflowEvent, onModelSelected, onWorkspaceChipTapped, onWorkspacePicked, onWorkspacePickerDismissed)` — mixed: one sealed-event sink (`onOverflowEvent`) alongside six flat callbacks, plus three flat `State` parameters (`connectionState`, `isThinking`, `isStalled`).** Every other VM-backed screen in the codebase uses `(state: UiState, onEvent: (Event) -> Unit)` once they have at least one VM-owned event. #188 added `onSendMessage`, #201 added `onRetry`, #137 added three workspace handlers, #145 added a hoisted `onExpandClick` placeholder that #254 deleted in favour of an internal `{ sheetVisible = true }` plus a new `onModelSelected: (Model) -> Unit` parameter bound to `vm::onModelSelected` ([#253](../codebase/253.md)) — all as flat callbacks rather than folding into a sealed `ThreadEvent`. [`#251`](../codebase/251.md) **introduced** the `sealed interface ThreadEvent` (co-located at the top of `ThreadViewModel.kt`) and a `ThreadViewModel.onOverflowEvent(event: ThreadEvent)` dispatcher, **scoped to overflow only**: the existing six plain handlers (`sendMessage`, `retry`, `onWorkspaceChipTapped`, `onWorkspacePicked`, `onWorkspacePickerDismissed`, `onModelSelected`) are not migrated to the sealed surface. [`#252`](../codebase/252.md) mounted the [`ThreadOverflowMenu`](thread-overflow-menu.md) inside `ThreadTopAppBar`'s `actions` slot — `ThreadScreen` hoists `var overflowExpanded by rememberSaveable { mutableStateOf(false) }`, the screen's `onOverflowClick: () -> Unit = {}` parameter was renamed in place to `onOverflowEvent: (ThreadEvent) -> Unit = {}`, and `MainActivity` binds `onOverflowEvent = vm::onOverflowEvent` at the destination block. The screen-level `(state, onEvent)` collapse for *every* event (rolling the six plain methods into `ThreadEvent` too) is option (iii) from #203's convention question and remains deferred. `connectionState` stays a flat `State` parameter — it's state, not an event; [`#407`](../codebase/407.md) added `isThinking: Boolean = false` on the same footing (a defaulted flat `State` sibling, after `modifier`); [`#396`](../codebase/396.md) added `isStalled: Boolean = false` likewise. [`#382`](../codebase/382.md) added a defaulted `onShowLiteralScreen: () -> Unit = {}` (forwarded through `ThreadTopAppBar` to [`ThreadOverflowMenu`](thread-overflow-menu.md)'s always-available "Show the literal screen" item) — another **flat callback, not a `ThreadEvent`**: it is pure navigation (`MainActivity` wires `{ navController.navigate("literal_screen/$conversationId") }`), so routing it through the sealed surface would have coupled a view-only action to the VM. Mirrors `onOpenAbout`; keeps `ThreadViewModel`/`ThreadEvent` untouched. [`#446`](../codebase/446.md) added `modalState: ModalUiState = ModalUiState.Hidden` on the same footing as `isThinking` / `isStalled` (a defaulted flat `State` sibling — the hoisted app-level `currentModal` from #445), plus two then-**inert** flat callbacks `onModalOption: (String) -> Unit = {}` / `onModalCancel: () -> Unit = {}`; all defaulted, so no call-site cascade. [`#452`](../codebase/452.md) **made those live** and added two more defaulted siblings — `armedOptionId: String? = null` (a flat `State` sibling, the VM-scoped armed option from #451) and `modalSendErrors: Flow<Unit> = emptyFlow()` (a payload-free one-shot) — with `MainActivity` collecting `vm.armedOptionId`, forwarding `vm.modalSendErrors` by reference, and binding `onModalOption`/`onModalCancel` to `vm::onModalOption`/`vm::onModalCancel`; still all flat (not folded into `ThreadEvent`), still no call-site cascade.
