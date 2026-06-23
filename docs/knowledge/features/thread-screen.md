# Thread screen

Outer shell for the conversation thread at the `conversation_thread/{conversationId}` route. Skeleton landed in [#126](../codebase/126.md) (route + VM + empty `LazyColumn`); the TopAppBar slot was promoted from placeholder to the real Figma `16:8` chrome in [#139](../codebase/139.md); the `bottomBar` slot was filled with the composer in [#188](../codebase/188.md) — see [Thread input bar](thread-input-bar.md); the [`ConnectionBanner`](connection-banner.md) was wired between the TopAppBar and the message list in [#201](../codebase/201.md) (split from #197, originally #134); the empty-discussion [`WorkspaceChip`](workspace-chip.md) was wired between the banner and the message list in [#137](../codebase/137.md), which also widened `ThreadUiState` by four fields and added the screen-root [`WorkspacePicker`](workspace-picker.md) host. The `LazyColumn` body was filled with the typed `ThreadItem` render loop in [#246](../codebase/246.md) — replacing the [#126](../codebase/126.md) `items(emptyList<Unit>()) { }` placeholder with `items(state.items.asReversed(), key = …)` dispatching `MessageItem` → [`MessageBubble`](message-bubble.md) and `SessionBoundary` → [`SessionBoundaryDelimiter`](session-boundary-delimiter.md), and widening `ThreadUiState` with `items: List<ThreadItem> = emptyList()`. Above-delimiter opacity treatment landed in [#136](../codebase/136.md) — the `items(...)` call shape migrated to `itemsIndexed(...)` and each row got wrapped in `Box(Modifier.alpha(rowAlpha))` against a `mostRecentSessionBoundaryIndex(state.items)` cutoff so rows strictly before the most recent `SessionBoundary` render at `ABOVE_DELIMITER_ALPHA = 0.55f` (the latest boundary itself stays full-opacity). The empty-thread prompt [`EmptyThreadState`](empty-thread-state.md) was wired below the chip in [#138](../codebase/138.md) — the unconditional `LazyColumn` block became the `else` arm of `if (!state.hasMessages) EmptyThreadState(...) else { LazyColumn(...) }`, taking the same `weight(1f)` slot whenever the thread has no `ThreadItem.MessageItem` rows. The [`ThreadStatusRow`](thread-status-row.md) was stacked above the input bar inside the same `bottomBar` slot in [#145](../codebase/145.md) — the single-child `bottomBar = { ThreadInputBar(...) }` widened to a `Column { ThreadStatusRow(...); ThreadInputBar(...) }`, with three new `ThreadUiState` fields (`model`, `effort`, `tokenPercent`) and a new `onExpandClick: () -> Unit` parameter on `ThreadScreen` (placeholder until the Status Sheet landed). The [`StatusSheet`](status-sheet.md) was wired in [#254](../codebase/254.md) — `ThreadScreen` hoists `var sheetVisible by rememberSaveable { mutableStateOf(false) }`, the row's `onExpandClick = { sheetVisible = true }` is wired internally, the sheet renders as a `Scaffold` sibling alongside the [`WorkspacePicker`](workspace-picker.md), and the `onExpandClick` parameter on `ThreadScreen` is deleted in favour of a new `onModelSelected: (Model) -> Unit` parameter bound to `vm::onModelSelected` ([#253](../codebase/253.md)) at the `MainActivity` destination. The [`ThreadOverflowMenu`](thread-overflow-menu.md) was mounted inside `ThreadTopAppBar`'s `actions` slot in [#252](../codebase/252.md) — `ThreadScreen` hoists a second `var overflowExpanded by rememberSaveable { mutableStateOf(false) }`, `ThreadTopAppBar` gains three parameters (`overflowExpanded`, `onOverflowDismiss`, `onOverflowEvent`) and wraps the existing `IconButton` + the newly-mounted `ThreadOverflowMenu` in a `Box` so the M3 `DropdownMenu` anchors below the icon, the screen's `onOverflowClick: () -> Unit = {}` parameter is renamed in place to `onOverflowEvent: (ThreadEvent) -> Unit = {}`, and `MainActivity` binds `onOverflowEvent = vm::onOverflowEvent`. The [`RenameDialog`](rename-dialog.md) was wired in [#141](../codebase/141.md) — `ThreadUiState` gains `showRenameDialog: Boolean = false` (driven by a private `pendingRenameDialog: MutableStateFlow<Boolean>` folded as the **fourth** source into the main `combine(...)` block, which is now five-arity total at the native overload ceiling), `ThreadEvent` gains two cases (`data class RenameSubmit(val name: String)` carrying the trimmed name + `data object RenameDismiss`), `ThreadViewModel.onOverflowEvent` replaces the [#252](../codebase/252.md) `Rename` no-op with `pendingRenameDialog.value = true` and adds the two new arms (`RenameSubmit` flips the flag false synchronously then launches `repository.rename(state.value.conversationId, event.name)`; `RenameDismiss` flips the flag false), and `ThreadScreen` renders `RenameDialog(initialName = state.displayName, onSubmit = { onOverflowEvent(ThreadEvent.RenameSubmit(it)) }, onDismiss = { onOverflowEvent(ThreadEvent.RenameDismiss) })` as a third `Scaffold` sibling alongside the [`WorkspacePicker`](workspace-picker.md) and [`StatusSheet`](status-sheet.md). The [`SaveAsChannelDialog`](save-as-channel-dialog.md) was wired in [#142](../codebase/142.md) — `ThreadUiState` gains `saveAsChannelDialog: SaveAsChannelDialogState? = null` (nullable sub-state carrier bundling the seeded pre-fill name with the visibility flag per the [#78](../codebase/78.md) `PendingPromotion` shape; visible iff non-null), `ThreadEvent` gains two cases (`data class SaveAsChannelSubmit(val name: String, val workspace: WorkspaceChoice)` carrying the trimmed name + the radio-selected workspace choice + a new top-level `enum class WorkspaceChoice { DEDICATED, SCRATCH }`, plus `data object SaveAsChannelDismiss`), the outer `combine(...)` stays at the five-arity ceiling via a new private `data class TransientDialogs(val renameVisible: Boolean, val saveAsChannel: SaveAsChannelDialogState?)` pre-combiner that folds `pendingRenameDialog` + `pendingSaveAsChannelDialog` into one source (the lambda destructures `dialogs.renameVisible` / `dialogs.saveAsChannel`), `ThreadViewModel.onOverflowEvent` replaces the [#204](../codebase/204.md) `SaveAsChannel` no-op with `pendingSaveAsChannelDialog.value = SaveAsChannelDialogState(initialName = AUTO_SUGGESTED_CHANNEL_NAME)` (the seeded `"New channel"` Phase-0 stub) and adds the two new arms (`SaveAsChannelSubmit` flips the flag null synchronously then launches `repository.promote(state.value.conversationId, event.name, resolveWorkspace(event.name, event.workspace))` where `resolveWorkspace` is a file-scope helper returning `"pyry-workspace/channels/${name.toChannelSlug()}"` for `DEDICATED` and `null` for `SCRATCH`; `SaveAsChannelDismiss` flips the flag null), and `ThreadScreen` renders `state.saveAsChannelDialog?.let { dialogState -> SaveAsChannelDialog(initialName = dialogState.initialName, onSubmit = { name, workspace -> onOverflowEvent(ThreadEvent.SaveAsChannelSubmit(name = name, workspace = workspace)) }, onDismiss = { onOverflowEvent(ThreadEvent.SaveAsChannelDismiss) }) }` as a fourth `Scaffold` sibling. `MainActivity` is unchanged across both #141 and #142 — the pre-existing method-reference binding `vm::onOverflowEvent` from [#252](../codebase/252.md) handles the four new event variants automatically. The [`ChannelInfoSheet`](channel-info-sheet.md) was hosted from the thread overflow in [#226](../codebase/226.md) — `ThreadUiState` gains `channelInfoOpen: Boolean = false` (driven by a private `pendingChannelInfo: MutableStateFlow<Boolean>` folded as a **third** flag into the existing `TransientDialogs` pre-combiner group, so the outer `combine` stays at its five-arity ceiling) plus three raw ingredient fields (`workspacePath: String`, `lastUsedAt: Instant?`, `sessionCount: Int`) populated from the live `Conversation`, `ThreadEvent` gains `data object ChannelInfoDismiss`, `ThreadViewModel.onOverflowEvent` lifts `ChannelInfo` out of the no-op branch (`ChannelInfo` → `pendingChannelInfo.value = true`; `ChannelInfoDismiss` → `false`; the no-op arm shrinks to `NewSession, ChangeWorkspace`), and `ThreadScreen` renders `ChannelInfoSheet` as a **fifth** `Scaffold` sibling gated on `state.channelInfoOpen`, fed a `ChannelInfoUiModel` assembled by a pure `internal fun ThreadUiState.toChannelInfoUiModel(now)` mapper (the `internal` model is never put on the public `ThreadUiState`). The sheet's Rename / Change workspace lambdas emit `ThreadEvent.Rename` / `ThreadEvent.ChangeWorkspace` then dismiss; Archive / Delete / close are dismiss-only (sibling [#227](../codebase/227.md) wires the real archive/delete-from-sheet flow); Install is a Phase 3+ no-op. `MainActivity` is again unchanged — `vm::onOverflowEvent` carries the new events. The sheet's Archive / Delete were filled in [#227](../codebase/227.md) — the `onArchive` lambda now emits `ThreadEvent.Archive` (whose handler is extended to **close the sheet, archive, and pop back to the channel list**) and `onDelete` emits `ThreadEvent.Delete` (which opens a Material 3 `DeleteConfirmationDialog`; only its **Delete** confirm calls `repository.delete(...)` then pops back, its **Cancel** / scrim-tap closes the dialog and leaves the sheet open). `ThreadEvent` gains `Delete` / `DeleteConfirm` / `DeleteDismiss`, `ThreadUiState` gains `deleteConfirmVisible: Boolean = false` (a private `pendingDeleteConfirm` folded as the **fourth** flag into the `TransientDialogs` pre-combiner, outer `combine` still five-arity), `ThreadScreen` renders `DeleteConfirmationDialog` as a **sixth** `Scaffold` sibling (independent of the sheet block so it layers over the still-open sheet), and the pop-back rides a new one-shot `navigationChannel = Channel<ThreadNavigation>(BUFFERED)` exposed as `navigationEvents` and collected in `MainActivity` via `LaunchedEffect(vm)` — **the first change to the thread destination block since the chrome landed** (`vm::onOverflowEvent` had carried every prior event additively). Remaining downstream `feat(ui/thread):` work ([#208](https://github.com/pyrycode/pyrycode-mobile/issues/208) overflow → picker wiring — which reuses the picker state introduced by #137 and fills the `ThreadEvent.ChangeWorkspace` branch on the VM, plus [#229](https://github.com/pyrycode/pyrycode-mobile/issues/229) / [#230](https://github.com/pyrycode/pyrycode-mobile/issues/230) which append Effort + YOLO + Context window sections into the same `StatusSheetContent`) lands additively without rewriting the skeleton.

Package: `de.pyryco.mobile.ui.conversations.thread` (`app/src/main/java/de/pyryco/mobile/ui/conversations/thread/`). Files: `ThreadScreen.kt`, `ThreadTopAppBar.kt`, `ThreadInputBar.kt`, `ThreadViewModel.kt`. The [`ConnectionBanner`](connection-banner.md) it consumes lives one package over at `ui/conversations/components/ConnectionBanner.kt`. Figma reference frame: [`16:8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8) — the TopAppBar region (back arrow + title + `more_vert` overflow), the empty reverse-layout `LazyColumn` shell, the composer (subframe `16:61`), and the banner slot between them are in scope here; the message list, status row, and other body decorations in the same frame are deferred to the downstream tickets above.

## What it does

Renders the chrome of the thread surface — a `Scaffold` with a real Figma-matched `TopAppBar` (back icon + tappable title + overflow icon hosting the mounted [`ThreadOverflowMenu`](thread-overflow-menu.md) since [#252](../codebase/252.md)) over a `Column { ConnectionBanner; (WorkspaceChip?); (EmptyThreadState | LazyColumn(reverseLayout = true)) }` body, a `Column { ThreadStatusRow; ThreadInputBar }` cluster in the `bottomBar` slot (since [#145](../codebase/145.md)), and two `Scaffold` siblings ([`WorkspacePicker`](workspace-picker.md) since [#137](../codebase/137.md) and [`StatusSheet`](status-sheet.md) since [#254](../codebase/254.md), both gated on screen-hoisted visibility flags) — and resolves the conversation's `displayName` + the global `ConnectionState` from the repository / source via `ThreadViewModel`. When `state.hasMessages` is `false`, the body renders [`EmptyThreadState`](empty-thread-state.md) (a single centered `bodyMedium` / `onSurfaceVariant` prompt — "Send a message to get started", #138) in the `weight(1f)` slot the list would have occupied; otherwise the `LazyColumn` body iterates `state.items.asReversed()` (post-[#246](../codebase/246.md)) via `itemsIndexed(...)` (since [#136](../codebase/136.md)) and dispatches at the `ThreadItem` sealed-interface level: `MessageItem` → `MessageBubble(message = item.message)`, `SessionBoundary` → `SessionBoundaryDelimiter(boundary = item)`. Each row is wrapped in `Box(Modifier.alpha(rowAlpha))` so rows whose chronological index is strictly before `mostRecentSessionBoundaryIndex(state.items)` render at `ABOVE_DELIMITER_ALPHA = 0.55f` (above-delimiter de-emphasis from [#136](../codebase/136.md)); rows at or after the cutoff render at full opacity. The role-level `User`/`Assistant`/`Tool` selection lives inside [`MessageBubble`](message-bubble.md), not at the screen. Tapping the back arrow pops the nav back stack via an `onBack: () -> Unit` callback. Typing into the composer and tapping send (or the IME `Send` action) routes through `onSendMessage: (String) -> Unit` into `ThreadViewModel.sendMessage` and onward to `ConversationRepository.sendMessage`. Tapping the banner in the `Offline` state invokes `onRetry: () -> Unit`, bound to `ThreadViewModel.retry()` which launches `ConnectionStateSource.retry()` on `viewModelScope` (Phase-2 no-op; Phase-4 swaps the binding without touching the VM or the screen). Under `Connected` (the steady state the fake always reports) the banner short-circuits to zero height so the screen looks pre-#201 byte-identical. Tapping the title invokes `onTitleClick` (still a `{}` no-op default — the rename flow lands via the overflow item, not the title tap; a future ticket may bind `onTitleClick = { onOverflowEvent(ThreadEvent.Rename) }` to add a second entry point that opens the same [`RenameDialog`](rename-dialog.md)); tapping the overflow flips the screen-owned `overflowExpanded` flag and opens the mounted `ThreadOverflowMenu`, whose item taps escape via `onOverflowEvent: (ThreadEvent) -> Unit` bound to `vm::onOverflowEvent` ([#252](../codebase/252.md)). Tapping the overflow's **Rename** item routes `ThreadEvent.Rename` to the VM, which flips `pendingRenameDialog → true`; the resulting `state.showRenameDialog == true` triggers `ThreadScreen` to render the [`RenameDialog`](rename-dialog.md) ([#141](../codebase/141.md)) seeded with `initialName = state.displayName` — Save emits `ThreadEvent.RenameSubmit(trimmedName)` to call `repository.rename(state.value.conversationId, name)`, Cancel / outside-tap emits `ThreadEvent.RenameDismiss` to clear the flag. Tapping the discussion-only **Save as channel…** item (added in [#204](../codebase/204.md), wired in [#142](../codebase/142.md)) routes `ThreadEvent.SaveAsChannel` to the VM, which sets `pendingSaveAsChannelDialog → SaveAsChannelDialogState(initialName = "New channel")`; the resulting `state.saveAsChannelDialog != null` triggers `ThreadScreen` to render the [`SaveAsChannelDialog`](save-as-channel-dialog.md) seeded with the Phase-0 stub name + DEDICATED radio selected by default — Save emits `ThreadEvent.SaveAsChannelSubmit(trimmedName, WorkspaceChoice)` to call `repository.promote(state.value.conversationId, name, resolveWorkspace(name, workspace))` (the VM-private slug helper resolves `DEDICATED` → `"pyry-workspace/channels/<slug>"` and `SCRATCH` → `null` for preserve-existing-cwd), Cancel / outside-tap emits `ThreadEvent.SaveAsChannelDismiss` to clear the sub-state.

## Shape

```kotlin
// ThreadViewModel.kt
data class ThreadUiState(
    val conversationId: String,
    val displayName: String,
    val isPromoted: Boolean = false,                  // new in #137
    val hasMessages: Boolean = false,                 // new in #137
    val workspaceLabel: String = "scratch",           // new in #137
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
    val tokenPercent: Int = 0,                        // new in #145
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
                workspaceLabel = conv?.workspaceLabel() ?: "scratch",
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
                tokenPercent = STUB_TOKEN_PERCENT,                // new in #145
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
        viewModelScope.launch {
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
        viewModelScope.launch {
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

    companion object {                                      // new in #145; STUB_MODEL deleted in #253; STUB_EFFORT deleted in #229
        // Phase 4 swap point: replace with backend AgentStatus flow.
        private const val STUB_TOKEN_PERCENT = 73
    }
}

private fun Conversation.displayName(): String =
    name?.takeIf { it.isNotBlank() }
        ?: if (isPromoted) "Untitled channel" else "Untitled discussion"

private fun Conversation.workspaceLabel(): String =        // new in #137
    if (cwd.isEmpty() || cwd == DEFAULT_SCRATCH_CWD) "scratch"
    else cwd.substringAfterLast('/').ifEmpty { cwd }

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
            )
        },
        bottomBar = {                                  // wrapped in Column in #145
            Column(modifier = Modifier.fillMaxWidth()) {
                ThreadStatusRow(
                    model = state.selectedModel.label(),         // state.model → state.selectedModel.label() in #253
                    effort = state.selectedEffort.label(),       // state.effort → state.selectedEffort.label() in #229
                    tokenPercent = state.tokenPercent,
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

`ThreadUiState` is still a **`data class`, not a `sealed interface`.** The canonical pattern in this codebase (`ChannelListUiState`, `DiscussionListUiState`, `ArchivedDiscussionsUiState`) is sealed `Loading | Empty | Loaded | Error` precisely because each represents a real async data-load stage; this screen still has zero `Loading` / `Error` distinction worth modelling (the `displayName` lookup falls back to the raw id rather than failing, so there is no error state to project). The first downstream ticket that introduces an error path or a "load-bearing waiting" frame is the one that widens to a sealed envelope; the current `data class` becomes the `Loaded` variant via grep-replace. Post-[#227](../codebase/227.md) the class carries eighteen fields (`conversationId`, `displayName`, `isPromoted`, `hasMessages`, `workspaceLabel`, `workspacePickerVisible`, `showRenameDialog`, `saveAsChannelDialog`, `items`, `channelInfoOpen`, `deleteConfirmVisible`, `workspacePath`, `lastUsedAt`, `sessionCount`, `selectedModel`, `selectedEffort`, `yoloEnabled`, `tokenPercent`); the additions since #145 (`showRenameDialog` from #141, `saveAsChannelDialog` from #142, `yoloEnabled` from #229, `channelInfoOpen` / `workspacePath` / `lastUsedAt` / `sessionCount` from #226, and `deleteConfirmVisible` from #227) all default so the pre-existing `assertEquals(ThreadUiState(...), vm.state.value)` test cases compile unchanged. The `model: String` field was retyped to `selectedModel: Model` in [#253](../codebase/253.md) and the `effort: String` field was similarly retyped to `selectedEffort: Effort` in [#229](../codebase/229.md) — both as part of typing the Status Sheet's selection contracts; the fields' data-class default positions are preserved.

`ThreadScreen`'s signature is **`(state, onBack, onSendMessage, connectionState, onRetry, modifier, isThinking, isStalled, onTitleClick, onOverflowEvent, onModelSelected, onWorkspaceChipTapped, onWorkspacePicked, onWorkspacePickerDismissed)` — mixed: one sealed-event sink (`onOverflowEvent`) alongside six flat callbacks, plus three flat `State` parameters (`connectionState`, `isThinking`, `isStalled`).** Every other VM-backed screen in the codebase uses `(state: UiState, onEvent: (Event) -> Unit)` once they have at least one VM-owned event. #188 added `onSendMessage`, #201 added `onRetry`, #137 added three workspace handlers, #145 added a hoisted `onExpandClick` placeholder that #254 deleted in favour of an internal `{ sheetVisible = true }` plus a new `onModelSelected: (Model) -> Unit` parameter bound to `vm::onModelSelected` ([#253](../codebase/253.md)) — all as flat callbacks rather than folding into a sealed `ThreadEvent`. [`#251`](../codebase/251.md) **introduced** the `sealed interface ThreadEvent` (co-located at the top of `ThreadViewModel.kt`) and a `ThreadViewModel.onOverflowEvent(event: ThreadEvent)` dispatcher, **scoped to overflow only**: the existing six plain handlers (`sendMessage`, `retry`, `onWorkspaceChipTapped`, `onWorkspacePicked`, `onWorkspacePickerDismissed`, `onModelSelected`) are not migrated to the sealed surface. [`#252`](../codebase/252.md) mounted the [`ThreadOverflowMenu`](thread-overflow-menu.md) inside `ThreadTopAppBar`'s `actions` slot — `ThreadScreen` hoists `var overflowExpanded by rememberSaveable { mutableStateOf(false) }`, the screen's `onOverflowClick: () -> Unit = {}` parameter was renamed in place to `onOverflowEvent: (ThreadEvent) -> Unit = {}`, and `MainActivity` binds `onOverflowEvent = vm::onOverflowEvent` at the destination block. The screen-level `(state, onEvent)` collapse for *every* event (rolling the six plain methods into `ThreadEvent` too) is option (iii) from #203's convention question and remains deferred. `connectionState` stays a flat `State` parameter — it's state, not an event; [`#407`](../codebase/407.md) added `isThinking: Boolean = false` on the same footing (a defaulted flat `State` sibling, after `modifier`); [`#396`](../codebase/396.md) added `isStalled: Boolean = false` likewise. [`#382`](../codebase/382.md) added a defaulted `onShowLiteralScreen: () -> Unit = {}` (forwarded through `ThreadTopAppBar` to [`ThreadOverflowMenu`](thread-overflow-menu.md)'s always-available "Show the literal screen" item) — another **flat callback, not a `ThreadEvent`**: it is pure navigation (`MainActivity` wires `{ navController.navigate("literal_screen/$conversationId") }`), so routing it through the sealed surface would have coupled a view-only action to the VM. Mirrors `onOpenAbout`; keeps `ThreadViewModel`/`ThreadEvent` untouched. [`#446`](../codebase/446.md) added `modalState: ModalUiState = ModalUiState.Hidden` on the same footing as `isThinking` / `isStalled` (a defaulted flat `State` sibling — the hoisted app-level `currentModal` from #445), plus two then-**inert** flat callbacks `onModalOption: (String) -> Unit = {}` / `onModalCancel: () -> Unit = {}`; all defaulted, so no call-site cascade. [`#452`](../codebase/452.md) **made those live** and added two more defaulted siblings — `armedOptionId: String? = null` (a flat `State` sibling, the VM-scoped armed option from #451) and `modalSendErrors: Flow<Unit> = emptyFlow()` (a payload-free one-shot) — with `MainActivity` collecting `vm.armedOptionId`, forwarding `vm.modalSendErrors` by reference, and binding `onModalOption`/`onModalCancel` to `vm::onModalOption`/`vm::onModalCancel`; still all flat (not folded into `ThreadEvent`), still no call-site cascade.

## How it works

### `SavedStateHandle.get<String>("conversationId").orEmpty()` (lifted to a `private val`)

The `.orEmpty()` is a **type-system narrowing, not a defensive fallback** — Compose Navigation guarantees the `navArgument("conversationId") { type = NavType.StringType }` is present before the destination composes. Post-#139 the `conversationId` is lifted to a `private val` field on the VM so the `map { … }` body and the `stateIn(initialValue = …)` both reference one source of truth; before #139 the inline `savedStateHandle.get<String>("conversationId").orEmpty()` lived directly in the `MutableStateFlow(...)` constructor. The narrowing collapses `String?` to `String` so the `data class` field reads cleanly and the title `Text` is non-null at every callsite.

### `combine(observeConversations, observeMessages, pendingWorkspacePicker).stateIn(WhileSubscribed)` — three upstreams since #137

Post-#137 the single `.map { … }` over `observeConversations(All)` was widened to a `combine` of three upstreams:

```kotlin
combine(
    repository.observeConversations(ConversationFilter.All),
    repository.observeMessages(conversationId),
    pendingWorkspacePicker,
) { conversations, items, pickerVisible ->
    val conv = conversations.firstOrNull { it.id == conversationId }
    ThreadUiState(
        conversationId = conversationId,
        displayName = conv?.displayName() ?: conversationId,
        isPromoted = conv?.isPromoted ?: false,
        hasMessages = items.any { it is ThreadItem.MessageItem },
        workspaceLabel = conv?.workspaceLabel() ?: "scratch",
        workspacePickerVisible = pickerVisible,
    )
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = ThreadUiState(conversationId, displayName = conversationId))
```

`pendingWorkspacePicker = MutableStateFlow(false)` is a private hot source backing the chip-opens-picker signal. `observeMessages` re-emits on `sendMessage` (the `hasMessages` flip happens there) and on `changeWorkspace` (a new `SessionBoundary` arrives; `hasMessages` stays `false` because boundaries don't count toward the `MessageItem`-only filter). The `initialValue` block is byte-identical to its pre-#137 shape — it still constructs `ThreadUiState(conversationId, displayName = conversationId)` with the four new fields defaulting; the `state_initialValue_isConversationIdPlaceholderBeforeSubscription` test continues to pass full equality. Conversation-missing edge case: `conv` is `null` → `isPromoted = false` (treated as discussion), `workspaceLabel = "scratch"` (safe default for the chip). The `combine` over three independent signals was the right shape; collapsing the message subscription into the conversations map by calling `observeMessages(id).first()` inside the lambda would have blocked the upstream — see [`../codebase/137.md`](../codebase/137.md) lessons learned.

### `observeConversations(All).map { firstOrNull }` — option (c) for `displayName` derivation

#139 picked option (c) of three plumbing options for surfacing `displayName` in `ThreadUiState` — filter the existing `observeConversations(All)` flow inside the VM rather than adding a route nav arg (option a) or a new `observeConversation(id)` repo method (option b). Tradeoffs:

| Option | Files | Tradeoff |
| --- | --- | --- |
| (a) Second nav arg `displayName` | Route + 4 call sites + VM + state | URL-encoding for unicode at every call site, scatters fallback logic, no live-rename re-emission |
| (b) New `observeConversation(id)` on repo | Interface + Fake + VM + state + screen + new top-bar file = ≥5 `.kt` | Cleanest contract long-term; busts the S file-count red line |
| **(c) Filter `All` in VM** | VM + state + screen + new top-bar file + AppModule = 4 `.kt` | One-call-line scan, free rename re-emission, no repo-surface change — **picked** |

The `firstOrNull { it.id == conversationId }` scan is `O(n)` per upstream emission — acceptable at Phase 0 fake-cardinality (~5 records). When the Phase 4 Ktor-backed impl lands and the per-emission scan has a real cost, a follow-up introduces `observeConversation(id): Flow<Conversation?>` (option b's endpoint). `ConversationFilter.All` is the right filter (not `Channels` / `Discussions`) because the thread screen is reachable from the archive screen and future deep links; restricting to non-archived would silently break those paths.

### `stateIn(viewModelScope, WhileSubscribed(5_000), initialValue = ThreadUiState(id, id))`

Same lifetime policy as `ChannelListViewModel` / `DiscussionListViewModel`: the upstream subscription re-uses across configuration changes (rotation) without leaking when the screen leaves the back stack for >5s. The `initialValue` falls back to `ThreadUiState(conversationId, displayName = conversationId)` — before the upstream's first emission lands, the AppBar renders the path id, matching the post-emission fallback when the id is missing. The fake's `MutableStateFlow.map` chain emits synchronously, so in practice the placeholder is invisible; it exists for type safety and process-death restoration.

The pre-#139 VM was `MutableStateFlow(initial).asStateFlow()` (synchronous, hot from line 1, no `viewModelScope.launch`). The shape upgrade to `stateIn(WhileSubscribed)` is **byte-identical at the destination**: the `val state: StateFlow<ThreadUiState>` surface and the `val state by vm.state.collectAsStateWithLifecycle()` consumer pattern do not change. The interchange is deliberate — picking `MutableStateFlow` in #126 cost zero at the destination so that #139's widening to a cold-flow upstream was a drop-in replacement.

### `private fun Conversation.displayName()` — re-declared, not extracted

```kotlin
private fun Conversation.displayName(): String =
    name?.takeIf { it.isNotBlank() }
        ?: if (isPromoted) "Untitled channel" else "Untitled discussion"
```

The same extension also lives **privately** in `ArchivedDiscussionsScreen.kt:164-166`. #139 deliberately did **not** extract to a shared helper — promotion to `internal fun` in a new file would have pushed the spec to a 5-`.kt` diff (busting the S-budget). Two occurrences is below the canonical extract-on-third-use threshold; the third caller (likely #140's overflow menu's "Rename …" label) is the ticket that extracts to `internal fun Conversation.displayName()` under `ui/conversations/` and deletes both private copies.

The fallback strings are **Kotlin literals**, not `stringResource(R.string.untitled_discussion)`. ViewModels have no `Context`, and the existing private extension in `ArchivedDiscussionsScreen.kt` has been shipping with literals since #176; maintaining symmetry with the existing pattern is more important than tightening string-resource layering in #139. Revisit when the app needs localization beyond English — at that point the fallback moves into the UI layer (the composable that consumes `displayName`) rather than the VM.

### Display-name fallback chain

`conv?.displayName() ?: conversationId` collapses through three layers:

1. `name?.takeIf { it.isNotBlank() }` — real name if set and non-blank
2. `if (isPromoted) "Untitled channel" else "Untitled discussion"` — kind-aware fallback for `name = null` or `name = ""`
3. `conversationId` — when `firstOrNull { it.id == conversationId }` returns `null` (path not user-reachable in production; defensive against deep links and process-death races)

`displayName` is always a non-null `String`, so the title `Text` never needs a null check.

### Free rename re-emission

When #141's rename success path eventually calls `repository.rename(conversationId, newName)`, the fake's `MutableStateFlow<State>` updates; `observeConversations(All)` re-emits a list with the renamed conversation; the `.map { … }` recomputes `displayName`; `stateIn` publishes the new `ThreadUiState`; `collectAsStateWithLifecycle()` triggers recomposition. The `state_displayName_reemitsOnRename` test pins this — #141's only TopAppBar-related work is wiring the dialog's success path to `repository.rename(...)`; the live-title-update is already wired. Out-of-scope item from #139's ticket body ("plumbing a live rename back into the title without re-navigating — covered when #141 lands") is actually **already free** post-#139.

### `LazyColumn(reverseLayout = true)` — established in #126, populated in #246, dimmed in #136, nested in a `Column` since #201

The body shape since [#246](../codebase/246.md) iterates `state.items.asReversed()` with stable composite keys and dispatches at the `ThreadItem` sealed-interface level only — `MessageItem` → `MessageBubble(message = item.message)`, `SessionBoundary` → `SessionBoundaryDelimiter(boundary = item)`. The screen does **not** re-dispatch by `Message.role`; [`MessageBubble`](message-bubble.md) owns that selection internally. No `verticalArrangement = Arrangement.Bottom` override — `reverseLayout = true` already pins the first item to the bottom edge.

**Source-list reversal is required.** `observeMessages` returns items chronologically ascending (index 0 = oldest), but `LazyColumn(reverseLayout = true)` draws the **first** item at the bottom. For "newest at the bottom" the screen reverses before passing — `state.items.asReversed()` is the Kotlin stdlib O(1) view (no allocation, no copy), and it's a `List<ThreadItem>` so it slots into `itemsIndexed(...)` directly. Keys are computed from the underlying items, so the view's reversed index is irrelevant for identity.

**Stable keys are per-subtype with a string namespace prefix.** `MessageItem` → `"msg:${item.message.id}"` (the canonical row identity assigned at message creation in `FakeConversationRepository.sendMessage`, surviving all state transitions). `SessionBoundary` → `"boundary:${item.previousSessionId}->${item.newSessionId}"` — each transition is unique by construction (a session can only become "previous" once per stream, and the fake's `buildThreadItems` only emits a boundary when `prior != null && prior != message.sessionId`). The `"msg:"` / `"boundary:"` prefixes namespace the two subtypes so no key collision is possible between a message id and a session id that share a string. The `itemsIndexed(...)` key lambda ignores the `Int` first arg — identity stays anchored to item fields, not position (anti-pattern to mix the two).

**Above-delimiter opacity (since [#136](../codebase/136.md)).** Each row is wrapped in `Box(Modifier.alpha(rowAlpha))` around the existing `when (item)` dispatch. `rowAlpha` is computed inline: a `chronologicalIndex` is reconstructed from the reversed-list index (`state.items.size - 1 - reversedIndex`), then compared strict-`<` against a `cutoffChronologicalIndex = remember(state.items) { mostRecentSessionBoundaryIndex(state.items) }`. Rows above the cutoff render at the file-private `ABOVE_DELIMITER_ALPHA = 0.55f` constant; rows at or after the cutoff (including the boundary itself) render at `1f`. `mostRecentSessionBoundaryIndex` is an `internal` top-level helper at the bottom of the file (`items.indexOfLast { it is ThreadItem.SessionBoundary }`); its `-1` return for the no-boundary case combines with the strict `<` to give AC3 ("zero boundaries → all rows full opacity") for free. The wrap inherits to every row variant — user/assistant `MessageBubble`, `ToolCallRow`, nested `SessionBoundaryDelimiter` — because `Modifier.alpha(...)` is a render-only `graphicsLayer` effect and none of the row composables hold internal opacity state. **Interaction is not gated** — `ToolCallRow`'s `clickable` `Surface` stays expandable above the cutoff (alpha runs in the draw layer, after pointer input). That matches the user-story intent ("still legible, can scroll up and re-read"); if a future ticket gates above-cutoff interaction, it adds the gate at the inner `Surface`'s `enabled =` (not by stripping the alpha modifier).

Post-#201 the `LazyColumn` is nested inside a `Column` wrapper alongside the `ConnectionBanner` (see [Connection-banner wiring](#connection-banner-wiring) below). The list carries `Modifier.fillMaxWidth().weight(1f)` rather than `.fillMaxSize()` — inside a `Column`, `fillMaxSize` ignores `weight` semantics and over-claims vertical space, fighting with siblings. The `reverseLayout = true` semantics are unchanged: the list scrolls upward from the bottom of its weight-allocated region, with the banner pinned above it.

**Streaming auto-scroll (since [#185](../codebase/185.md)).** While any `ThreadItem.MessageItem` in `state.items` carries `message.isStreaming = true`, the `LazyColumn` keeps the streaming bubble's growing bottom edge anchored at the viewport bottom. Six composition-scoped pieces of state hoisted at the top of the `else` block — adjacent to the existing `reversedItems` / `cutoffChronologicalIndex` lines — implement it: `val listState = rememberLazyListState()` (threaded as `state =` on the `LazyColumn`); `val hasStreamingMessage by remember(state.items) { derivedStateOf { state.items.any { it is ThreadItem.MessageItem && it.message.isStreaming } } }` (the gate on the auto-pin coroutine, scan re-runs only when the list reference changes); `var userScrolledAway by remember { mutableStateOf(false) }` (yield flag); `val autoScrollNestedScroll = remember { object : NestedScrollConnection { ... } }` (sets `userScrolledAway = true` iff `source == NestedScrollSource.UserInput && available.y != 0f`, attached via `Modifier.nestedScroll(autoScrollNestedScroll)` on the column); `LaunchedEffect(listState) { snapshotFlow { firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0 }.collect { atBottom -> if (atBottom) userScrolledAway = false } }` (resumes auto-follow when the user manually returns to the bottom); and `LaunchedEffect(hasStreamingMessage, listState) { if (!hasStreamingMessage) return@LaunchedEffect; snapshotFlow { layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }?.size ?: 0 }.distinctUntilChanged().collect { if (!userScrolledAway) listState.scrollToItem(0) } }` (the auto-pin loop — re-anchors on every layout-pass size change of item 0). `scrollToItem(0)` (not `animateScrollToItem`) is the right primitive: instant, O(1) when already pinned, and it does **not** dispatch through `NestedScrollSource.UserInput` so it cannot recursively trip its own yield flag. Reverse-layout's bottom anchor is `firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0`; both effects rely on that. The auto-pin `LaunchedEffect` is gated by `hasStreamingMessage`, so non-streaming threads start no collector (AC4); when `isStreaming` flips `false` the effect re-launches with the new key and the early-return cancels the collector (AC3). The Phase-0 seed never flips `isStreaming = false` (the static seed stays streaming forever) — AC3's cancel path is verifiable only by code-review or a local seed flip + re-install; Phase 4's WS feed will exercise it naturally. The companion concern from [#184](../codebase/184.md) — `StreamingAssistantBody` losing its `revealedLength` when the bubble scrolls off-screen — is sidestepped (not solved) in the auto-pin happy path: keeping the bubble in the viewport prevents disposal. If the user yields by scrolling away during streaming, the bubble can still off-screen and re-reset on return; the Phase-4 hoist-into-VM fix from [#184](../codebase/184.md) is the proper remedy.

### Workspace-chip wiring (post-#137)

Between the `ConnectionBanner` and the `LazyColumn`, the body `Column` carries a conditional [`WorkspaceChip`](workspace-chip.md):

```kotlin
if (!state.isPromoted && !state.hasMessages) {
    WorkspaceChip(
        workspaceLabel = state.workspaceLabel,
        onClick = onWorkspaceChipTapped,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
```

The `&&` predicate stays inlined at the call site (not derived onto a hidden `showWorkspaceChip` boolean) because the same two raw fields will be re-consumed by #138 (empty-state copy) and #208 (overflow → picker entry point) — pre-fusing them would force re-derivation downstream. `workspaceLabel` is already-derived (the VM resolves `Conversation.cwd` → basename at the flow boundary via the private `workspaceLabel()` extension); composables never see the raw path. The chip lives in the existing `Column` slot, **not** inside the `LazyColumn` as a header item — it doesn't participate in scroll, doesn't get recycled, doesn't need a `key`, and the current empty `items(emptyList<Unit>()) { }` body has no `item { … }` slot to add it to without forcing a structural rewrite that #128's eventual `items(state.messages) { … }` would have to undo.

Inside the same `Column`, immediately after the `Scaffold`'s closing brace, the [`WorkspacePicker`](workspace-picker.md) host is rendered as a **`Scaffold` sibling** (not inside the content slot):

```kotlin
WorkspacePicker(
    visible = state.workspacePickerVisible,
    onPicked = onWorkspacePicked,
    onDismiss = onWorkspacePickerDismissed,
)
```

The picker's `ModalBottomSheet` lives in its own window, so source-order placement doesn't affect Z-order — sibling-to-Scaffold mirrors [`ChannelListScreen.kt:180-184`](channel-list-screen.md) exactly (the canonical wiring shape from [#221](../codebase/221.md)). #208 will reuse this exact host call by routing its overflow tap to the same `pendingWorkspacePicker.value = true` flag; no second `WorkspacePicker` invocation needed.

The three VM handlers (`onWorkspaceChipTapped`, `onWorkspacePicked`, `onWorkspacePickerDismissed`) all mirror `ChannelListViewModel`'s picker-trigger handlers. `onWorkspacePicked(path)` clears the flag *then* launches `repository.changeWorkspace(conversationId, path)` on `viewModelScope`; the returned `Session` is discarded — the `Conversation.cwd` update propagates back via the `observeConversations` re-emission to the `combine` arm, and `workspaceLabel` recomputes automatically. `onWorkspacePickerDismissed` clears the flag only — no repository call. See [`WorkspaceChip`](workspace-chip.md) for the full data-flow.

### Empty-state branch (post-#138)

Below the chip and above the `bottomBar` composer, the body `Column` carries a single `if (!state.hasMessages) … else …` branch:

```kotlin
if (!state.hasMessages) {
    EmptyThreadState(
        modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 24.dp),
    )
} else {
    val reversedItems = state.items.asReversed()
    val cutoffChronologicalIndex =
        remember(state.items) { mostRecentSessionBoundaryIndex(state.items) }
    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), reverseLayout = true) {
        itemsIndexed(items = reversedItems, key = { _, item -> … }) { reversedIndex, item -> … }
    }
}
```

[`EmptyThreadState`](empty-thread-state.md) renders the centered "Send a message to get started" prompt; the caller supplies the `weight(1f)` so the prompt fills exactly the space the list would have occupied. The 24.dp horizontal inset is intentionally 8dp wider than the chip's `horizontal = 16.dp` — a centered single line wants more breathing room than a left-aligned chip on the 360dp portrait minimum. Predicate is `!state.hasMessages`, not `state.items.isEmpty()` — symmetric with the chip's `!hasMessages` half (chip and prompt appear/disappear together) and correct for the `SessionBoundary`-only edge case where the user taps the chip → `changeWorkspace` emits a boundary before any message lands (the prompt stays visible until a real `MessageItem` arrives, instead of letting a lonely delimiter float above the input bar). The `remember(state.items) { mostRecentSessionBoundaryIndex(...) }` block from [#136](../codebase/136.md) lives **inside the `else` arm only** — its key is `emptyList()` in the empty arm, so computing the cutoff there is wasted work.

### Status-row wiring (post-#145)

Inside the same `Scaffold.bottomBar` slot, the [`ThreadStatusRow`](thread-status-row.md) stacks above the [`ThreadInputBar`](thread-input-bar.md) inside a shared wrapper `Column`:

```kotlin
bottomBar = {
    Column(modifier = Modifier.fillMaxWidth()) {
        ThreadStatusRow(
            model = state.selectedModel.label(),         // String → enum-derived in #253
            effort = state.selectedEffort.label(),       // String → enum-derived in #229
            tokenPercent = state.tokenPercent,
            onExpandClick = { sheetVisible = true },     // wired internally in #254
        )
        ThreadInputBar(onSend = onSendMessage)
    }
},
```

Pre-#145 the slot held only `ThreadInputBar(onSend = onSendMessage)`. The wrapper `Column` is one of two new things in #145; the other is the `ThreadStatusRow` itself. Crucially, **`Modifier.imePadding()` lives inside `ThreadInputBar` on its own outer `Column` (since [#188](../codebase/188.md)), not on the `Scaffold` bottomBar** — so only the input bar lifts when the soft keyboard opens, and the status row stays visually anchored above the lifted input bar. No new `imePadding` on the outer wrapper.

The three new `ThreadUiState` fields shipped in #145 (`model`, `effort`, `tokenPercent`) were populated from companion-object constants (`STUB_MODEL = "Opus 4.7"`, `STUB_EFFORT = "high"`, `STUB_TOKEN_PERCENT = 73`) inside the same `combine(...)` block. In [#253](../codebase/253.md) `model: String` was retyped to `selectedModel: Model` and rewired to a pre-combined `selectedModelFlow = combine(appPreferences.defaultModel, modelOverride) { default, override -> override ?: default }`; the `STUB_MODEL` constant was deleted. In [#229](../codebase/229.md) `effort: String` was retyped to `selectedEffort: Effort` and rewired to a pre-combined `selectedEffortFlow` mirroring `selectedModelFlow`'s shape; `STUB_EFFORT` was deleted. `STUB_TOKEN_PERCENT` survives untouched. The stub `tokenPercent = 73` lands inside the [`warning`](warning-color.md) band so the threshold-driven color renders live in the running app, not just in previews. Phase-4 swap point: delete the remaining single-constant companion, swap `STUB_TOKEN_PERCENT` inside `combine` for a read off a backend `AgentStatus` flow arm.

The row reads the typed enums as `state.selectedModel.label()` (via the [`Model.label()`](app-preferences.md#design-decision-defer-label-extensions-on-data-layer-enums) extension at `data/preferences/Model.kt`) and `state.selectedEffort.label()` (via the public [`Effort.label()`](app-preferences.md#design-decision-defer-label-extensions-on-data-layer-enums) extension at `ui/settings/EffortPickerDialog.kt:77`, widened from `internal` to public in [#229](../codebase/229.md) when this slice became the second consumer) — `ThreadStatusRow`'s `model: String` / `effort: String` parameter shape is preserved so the row's previews, its signature, and its internal `AnnotatedString` body stay byte-identical. `ThreadViewModel.onModelSelected(model: Model)` and `onEffortSelected(effort: Effort)` are the per-conversation override surfaces — synchronous `MutableStateFlow.value` writes, no `appPreferences.setDefault*(...)` calls (overrides are in-memory only; Phase 4 will persist).

Post-[#254](../codebase/254.md) the `onExpandClick` parameter on `ThreadScreen` is **deleted** — the screen owns the trigger via an internal `{ sheetVisible = true }` lambda passed straight to the row. A new `onModelSelected: (Model) -> Unit = {}` parameter takes its slot on the signature, bound to `vm::onModelSelected` ([#253](../codebase/253.md)) at the `MainActivity` destination. [#229](../codebase/229.md) appended two more sheet callbacks: `onEffortSelected: (Effort) -> Unit = {}` and `onYoloToggled: (Boolean) -> Unit = {}`, both bound to the matching `vm::` method references. Tapping the row opens the [`StatusSheet`](status-sheet.md); model/effort selections forward to the VM and auto-close the sheet; YOLO toggles forward to the VM but **keep the sheet open** (a Switch is a state-change the user may want to immediately reverse). See the [Status Sheet hosting](#status-sheet-hosting-post-254) section below for the host wiring.

### Status Sheet hosting (post-#254)

At the top of the `ThreadScreen` body (before the `Scaffold`), the screen hoists a `rememberSaveable` visibility flag:

```kotlin
var sheetVisible by rememberSaveable { mutableStateOf(false) }
```

`rememberSaveable` (over bare `remember`) survives configuration changes (rotation) at zero VM-surface cost — same idiom as [`WorkspacePicker`](workspace-picker.md). The flag is **UI-local presentation state** with no business meaning the VM needs to react to; pushing it onto `ThreadUiState` would dirty the VM contract with screen-presentation concerns. The status row's `onExpandClick = { sheetVisible = true }` is wired internally inside the `bottomBar` block (above).

After the existing [`WorkspacePicker`](workspace-picker.md) sibling at screen root, the [`StatusSheet`](status-sheet.md) renders as a second `Scaffold` sibling, gated on `sheetVisible`:

```kotlin
if (sheetVisible) {
    StatusSheet(
        selectedModel = state.selectedModel,
        onModelSelected = { model ->
            onModelSelected(model)
            sheetVisible = false              // auto-close on Model pick
        },
        selectedEffort = state.selectedEffort,        // new in #229
        onEffortSelected = { effort ->                // new in #229
            onEffortSelected(effort)
            sheetVisible = false              // auto-close on Effort pick
        },
        yoloEnabled = state.yoloEnabled,              // new in #229
        onYoloToggled = onYoloToggled,                // new in #229 — passthrough; NO auto-close on toggle
        onDismiss = { sheetVisible = false },
    )
}
```

Three design points pinned in #254 + one widened in #229:

1. **Gated `if (sheetVisible) { StatusSheet(...) }`, not `AnimatedVisibility`.** `ModalBottomSheet` runs its own enter/exit animation; wrapping in `AnimatedVisibility` would double-animate the sheet. The `if` creates the composable on first show and destroys it on dismiss — what `ModalBottomSheet` expects. Same shape as the [`WorkspacePicker`](workspace-picker.md) sibling.
2. **Sibling-to-Scaffold placement.** `ModalBottomSheet` lives in its own window, so source-order placement doesn't affect Z-order. Placing the sheet inside the `Scaffold`'s content slot would have worked but would mix the sheet's window-managed lifecycle with the body's layout-managed siblings.
3. **Asymmetric auto-close (resolution of the [#254](../codebase/254.md) open question, decided in [#229](../codebase/229.md)).** Single-pick sections (Model radio, Effort chip) wrap in `{ value -> upstream(value); sheetVisible = false }` — a discrete pick is a complete action, M3 modal-bottom-sheet convention. Toggle sections (YOLO Switch) pass the upstream callback straight through with no auto-close — a Switch is a state-change the user may want to immediately reverse; closing the sheet would force a re-open just to undo. Apply the same rule to [#230](https://github.com/pyrycode/pyrycode-mobile/issues/230)'s Context window section based on whether it's a picker or a toggle.
4. **`ModalBottomSheet`'s hide animation runs on a coroutine the framework owns** — no `sheetState.hide()` call needed before flipping `sheetVisible = false`.

The screen-side `onModelSelected: (Model) -> Unit = {}`, `onEffortSelected: (Effort) -> Unit = {}`, `onYoloToggled: (Boolean) -> Unit = {}` all default to `{}` so the four existing `@Preview` composables keep compiling unchanged; `MainActivity` binds all three at the `CONVERSATION_THREAD` destination:

```kotlin
ThreadScreen(
    // ...
    onModelSelected = vm::onModelSelected,
    onEffortSelected = vm::onEffortSelected,     // new in #229
    onYoloToggled = vm::onYoloToggled,           // new in #229
    // ...
)
```

### ChannelInfoSheet hosting (post-#226)

[#226](../codebase/226.md) hosts the [`ChannelInfoSheet`](channel-info-sheet.md) (shipped stateless in [#217](../codebase/217.md)) from the thread overflow → **Channel info** item. Unlike `sheetVisible` / `overflowExpanded` (screen-hoisted `rememberSaveable` flags), the visibility flag is **VM-driven** — the trigger arrives through `onOverflowEvent(ThreadEvent.ChannelInfo)`, not a screen-local tap — so it lives on `ThreadUiState.channelInfoOpen`, backed by a private `pendingChannelInfo: MutableStateFlow<Boolean>` folded as the **third** flag into the existing `transientDialogs` pre-combiner group (widening that inner `combine` 2→3 args, adding `channelInfoOpen` to `TransientDialogs`; the outer `state` `combine` stays at its five-arity ceiling). The `onOverflowEvent` dispatcher lifts `ChannelInfo` out of the no-op branch: `ChannelInfo → pendingChannelInfo.value = true`, `ChannelInfoDismiss → false`.

Two design points pinned in #226:

1. **The `internal ChannelInfoUiModel` is never put on the public `ThreadUiState`** (that's a public-API-exposes-internal-type error). Instead `ThreadUiState` carries stdlib-typed *ingredients* (`workspacePath: String`, `lastUsedAt: Instant?`, `sessionCount: Int`, plus the already-present `displayName` / `conversationId` / `items`), and a **pure non-`@Composable` mapper** `internal fun ThreadUiState.toChannelInfoUiModel(now: Instant = Clock.System.now()): ChannelInfoUiModel` assembles the model just before the host call. The `internal` mapper + injectable `now` is directly callable from a JVM unit test (`ThreadScreenMapperTest`), so relative-time labels and the `MessageItem`-only count are covered without a device. `createdLabel` derives from the earliest collected `ThreadItem`'s timestamp (via a small `private fun ThreadItem.timestamp()` mapping `MessageItem → message.timestamp`, `SessionBoundary → occurredAt`); `lastActivityLabel` from `lastUsedAt`; both via the app-wide [`formatRelativeTime`](workspace-chip.md) helper and both em-dash `"—"` when their source is absent. There is **no persisted `Conversation.createdAt`** — adding it would cascade ~45 constructor literals across ~16 files; deferred to a schema ticket.

2. **Delegating actions emit-then-dismiss; out-of-scope actions were dismiss-only.** Rename / Change workspace fire `{ onOverflowEvent(<Action>); onOverflowEvent(ChannelInfoDismiss) }` — Rename's downstream [`RenameDialog`](rename-dialog.md) is fully wired ([#141](../codebase/141.md)) so it takes effect; `ChangeWorkspace` is still a VM no-op (harmless, forward-compatible). In #226 **Archive / Delete were dismiss-only** with an inline comment: emitting `ThreadEvent.Archive` there (it *is* wired to `repository.archive(...)`) would archive with no pop-back nav — a half-built flow. The real archive/delete-from-sheet path (pop-back nav + delete-confirmation dialog) was sibling [#227](../codebase/227.md), **now landed — see [the next subsection](#channelinfosheet-archivedelete--pop-back-nav-post-227).** `onInstallMemoryPlugin` is an empty `{ /* TODO: Phase 3+ */ }` (no event, no dismiss). Like the other sheet hosts, button-close flips the boolean directly (immediate composition removal) rather than driving `sheetState.hide()` — swipe-down / scrim / back-press still animate via the M3 `ModalBottomSheet` (code review flagged the button-dismiss-without-animation as a non-blocking, intentional NIT — a cross-sheet polish concern, not per-sheet).

`MainActivity` is unchanged in #226 — the pre-existing `onOverflowEvent = vm::onOverflowEvent` binding carries `ChannelInfo` / `ChannelInfoDismiss` automatically. ([#227](../codebase/227.md) is the first slice to touch the thread destination block again — for the `navigationEvents` collection, below.)

### ChannelInfoSheet Archive/Delete + pop-back nav (post-#227)

[#227](../codebase/227.md) fills the two dismiss-only placeholders. **Archive** reuses the existing `ThreadEvent.Archive` (no sheet-scoped event) — its handler is extended from archive-in-place to "close sheet → archive → pop back to the channel list." The intentional consequence: the overflow-menu Archive path now also closes-and-pops (the menu still *emits* `ThreadEvent.Archive` unchanged; only the VM handler behavior changed, so the emission-only overflow tests are unaffected). **Delete** is a new three-event surface — `Delete` (open the confirm dialog, **no repo call**), `DeleteConfirm` (delete + close dialog + close sheet + pop back), `DeleteDismiss` (close dialog only; sheet stays open).

Sheet-button wiring becomes `onArchive = { onOverflowEvent(ThreadEvent.Archive) }` and `onDelete = { onOverflowEvent(ThreadEvent.Delete) }` (the stale dismiss-only comment is deleted). The confirm dialog renders as a **sixth `Scaffold` sibling**, gated on `state.deleteConfirmVisible` and **independent of** the `if (state.channelInfoOpen)` sheet block, so it layers over the still-open sheet (Cancel returns to the sheet, AC #3):

```kotlin
if (state.deleteConfirmVisible) {
    DeleteConfirmationDialog(
        displayName = state.displayName,
        onConfirm = { onOverflowEvent(ThreadEvent.DeleteConfirm) },
        onDismiss = { onOverflowEvent(ThreadEvent.DeleteDismiss) },  // scrim/back-tap = cancel
    )
}
```

`DeleteConfirmationDialog` is a private composable in `ThreadScreen.kt` mirroring `PromotionConfirmationDialog` ([#78](../codebase/78.md), `DiscussionListScreen.kt`): `AlertDialog(onDismissRequest = onDismiss, title, text interpolating the name via `stringResource(R.string.delete_dialog_body, displayName)`, confirmButton/dismissButton as `TextButton`s)`. The destructive confirm is a **plain `TextButton`** (no `colorScheme.error` tint) per the #78 convention — code review flagged the lack of destructive emphasis as a non-blocking NIT and judged it correct. Title copy is type-neutral ("Delete conversation?") because the sheet serves both channels and discussions.

**One-shot pop-back via `Channel` + `receiveAsFlow`, collected in `MainActivity`.** The VM gains `private val navigationChannel = Channel<ThreadNavigation>(capacity = Channel.BUFFERED)` exposed as `val navigationEvents: Flow<ThreadNavigation> = navigationChannel.receiveAsFlow()` (a new single-member `sealed interface ThreadNavigation { data object PopBack }`). The `Archive` and `DeleteConfirm` launches `send(ThreadNavigation.PopBack)` **after** the suspend repo call returns — mutation-before-`send` so the VM's `viewModelScope` cancellation (triggered when `popBackStack()` clears the destination) can't truncate the mutation; the same ordering `ChannelListViewModel.CreateDiscussionTapped` relies on. `MainActivity` collects it in the thread `composable` (the first edit to that block since the chrome):

```kotlin
LaunchedEffect(vm) {
    vm.navigationEvents.collect { event ->
        when (event) { ThreadNavigation.PopBack -> navController.popBackStack() }
    }
}
```

A `Channel` (not a `StateFlow<Boolean>`) is the **one-shot guarantee (AC #5)**: it delivers each element once and never replays, so on rotation `LaunchedEffect(vm)` re-collects the same VM's flow but the consumed `PopBack` is gone — no second pop. The collection site is `MainActivity`, not the screen, because the consumer (`popBackStack()`) is a NavHost concern — this is the twin of the `ChannelListNavigation` collection and calls the same `popBackStack()` that `onBack` already calls; contrast [`ArchivedDiscussionsViewModel.effects`](archived-discussions-screen.md), collected in-screen because *its* effect drives a snackbar. See [the per-ticket notes](../codebase/227.md) for the full decision record.

### Connection-banner wiring

The Scaffold content slot wraps the [`ConnectionBanner`](connection-banner.md) above the `LazyColumn` in a `Column`:

```kotlin
Column(modifier = Modifier.padding(inner).fillMaxSize()) {
    ConnectionBanner(state = connectionState, onRetry = onRetry)
    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), reverseLayout = true) { … }
}
```

Three design points pinned in #201:

- **Structural, not overlay.** Per the AC, the banner pushes the message list down by its intrinsic height; under `Connected` the banner's early `return` collapses to zero height so the steady-state look is byte-identical to pre-#201. Alternatives (`Box` with manual offset, `Scaffold` content overlay, `topBar = { Column { TopAppBar; ConnectionBanner } }`) all either change the overlay semantics (the AC forbids overlay) or force a refactor of `ThreadTopAppBar`. The `Column` wrapper is structurally minimal.
- **`Modifier.padding(inner)` lives on the outer `Column`, not the `LazyColumn`.** The Scaffold's content-inset wraps the banner *and* the list — applying `padding(inner)` only to the `LazyColumn` (leaving the banner outside the inset) would let the banner draw under the AppBar's status-bar inset on edge-to-edge devices.
- **`Connected` short-circuit is the steady state.** The fake's `FakeConnectionStateSource` always emits `Connected`; the banner's public-entry `when` returns early on `Connected` with no composition. Under normal use the banner is invisible; the integration is exercised by VM unit tests that push `Offline` / `Connecting` / `Reconnecting` through the fake (see [Testing](#testing) below).

`connectionState: ConnectionState` is a flat parameter rather than a field on `ThreadUiState` — see [`#201`'s ticket notes](../codebase/201.md) for the full rationale. Short version: the existing `state` derivation stays untouched (no `combine(...)` ceremony, no churn to the seven existing tests that pattern-match `ThreadUiState`); connection state is global (every screen would consume the same source under future work) while conversation state is per-screen; reflecting that orthogonality at the type level is cleaner than artificial fusion. The destination block consumes two `collectAsStateWithLifecycle()` calls — the same shape `SettingsViewModel` already uses for its four separate flows. [`#406`](../codebase/406.md) added a **second** such sibling signal to the VM on this same rationale: `val isThinking: StateFlow<Boolean>` (`ThreadViewModel.kt:216`), the live `turn_state` thinking-phase flag reduced from the [coordinator](relay-repository-coordinator.md)'s `liveSessionEvents` seam — a transient, connection-scoped cross-cutting signal kept off `ThreadUiState` so the `state` combine stays zero-touch. [`#407`](../codebase/407.md) then threaded it into `ThreadScreen` as a **third** flat sibling parameter — `isThinking: Boolean = false` (defaulted, after `modifier`, `ThreadScreen.kt:74`), collected at `MainActivity` via `vm.isThinking.collectAsStateWithLifecycle()` exactly parallel to `connectionState` — and rendered the at-work [`ThinkingIndicator`](thinking-indicator.md) at the foot of the content `Column` (see [Thinking-indicator placement (post-#407)](#thinking-indicator-placement-post-407) below). See [Turn-state thinking flag](turn-state-thinking-flag.md) for the data path. [`#396`](../codebase/396.md) added a **fourth** sibling signal on the *same* rationale: `val isStalled: StateFlow<Boolean>` (`ThreadViewModel.kt:225`, beside `isThinking`), sourced straight off the already-injected `repository.observeStall(conversationId)` (#395) — **no constructor/DI/interface change**, unlike `isThinking` which needed the new `liveSessionEvents` ctor param — and threaded into `ThreadScreen` as a **fourth** flat sibling parameter `isStalled: Boolean = false` (defaulted, after `modifier`, `ThreadScreen.kt:75`), collected at `MainActivity` via `vm.isStalled.collectAsStateWithLifecycle()`. It deviates from the ticket's "hoist into `UiState`" Technical Note deliberately: stall is the same signal class as `isThinking`, so siblinghood is consistent and the 5-arity `state` combine stays untouched. See [Stall state](stall-state.md) for the data path and [Stall-promotion-banner placement (post-#396)](#stall-promotion-banner-placement-post-396) below for the render.

`onRetry` binds to `vm::retry` at the destination — method reference, not a fresh lambda, so the binding is stable across recompositions (the lambda allocation only happens once per VM lifecycle, not per recomposition).

### Thinking-indicator placement (post-#407)

[#407](../codebase/407.md) mounts the stateless [`ThinkingIndicator`](thinking-indicator.md) as the **final child of the content `Column`**, *after* the `if (!state.hasMessages) EmptyThreadState(...) else { LazyColumn(...) }` block closes and *outside* it (`ThreadScreen.kt:221`):

```kotlin
Column {
    ConnectionBanner(state = connectionState, onRetry = onRetry)
    // optional WorkspaceChip …
    if (!state.hasMessages) EmptyThreadState(...) else LazyColumn(reverseLayout = true, ...) { … }
    ThinkingIndicator(isThinking = isThinking, modifier = Modifier.fillMaxWidth())
}
```

- **Outside the branch, not gated on `hasMessages`.** Both arms (`EmptyThreadState` / `LazyColumn`) take `weight(1f)`; the indicator is wrap-height and sits directly below that weighted region, above the `bottomBar` (status row + input). One call site therefore surfaces it identically in the **empty-thread** case (thinking precedes the first assistant text, AC #2) and the populated case — no duplication, no `hasMessages` condition.
- **Foot = bottom / most-recent edge.** `reverseLayout = true` pins list index 0 (newest message) to the bottom of the list region, so the indicator lands directly beneath the latest message and above the composer — the visual "foot" the AC names.
- **Zero-touch to the list.** The `LazyColumn`, its `weight`/`reverseLayout`, and the streaming auto-scroll effects are unchanged; the indicator is a sibling row, not a list item, so it never participates in `itemsIndexed`/keying or the auto-pin loop.
- Driven solely by the hoisted `isThinking` flag (`if (!isThinking) return` inside the composable) — it holds no local state and emits nothing when not thinking (AC #1/#3/#4). See [Thinking indicator](thinking-indicator.md) for the composable's shape, a11y (`cd_thread_thinking`), and previews.

### Stall-promotion-banner placement (post-#396)

[#396](../codebase/396.md) mounts the stateless [`StallPromotionBanner`](stall-promotion-banner.md) directly **below** [`ConnectionBanner`](connection-banner.md) in the content `Column` (`ThreadScreen.kt:123`), above the workspace chip / empty state / message list — i.e. **outside** the scrolling `LazyColumn`:

```kotlin
Column {
    ConnectionBanner(state = connectionState, onRetry = onRetry)
    StallPromotionBanner(isStalled = isStalled, onShowLiteralScreen = onShowLiteralScreen)
    // optional WorkspaceChip …
    if (!state.hasMessages) EmptyThreadState(...) else LazyColumn(reverseLayout = true, ...) { … }
    ThinkingIndicator(isThinking = isThinking, modifier = Modifier.fillMaxWidth())
}
```

- **Top, not foot — the counterpoint to `ThinkingIndicator`.** A stall promotion is a "do this now" recommendation, so it takes the most-prominent top slot directly analogous to `ConnectionBanner` (connection-degraded → top banner; parse-degraded/stalled → top banner); the at-work thinking indicator stays at the foot. Being **outside** the `LazyColumn`, it stays pinned while the stall holds rather than scrolling away.
- **Reuses the already-shipped snapshot action (AC #4).** The banner's `onClick` is the **same** `onShowLiteralScreen` (#382) the overflow "Show the literal screen" item triggers — no new `ThreadEvent`, no new navigation, no new data path.
- Driven solely by the hoisted `isStalled` flag (`if (!isStalled) return` inside the composable) — holds no local state, emits nothing when not stalled (AC #1/#2/#3). See [Stall promotion banner](stall-promotion-banner.md) for the composable's shape, a11y (`cd_thread_stall_promotion`), tertiary-container styling (design-owed), and previews.

### Permission-modal overlay placement (post-#446)

[#446](../codebase/446.md) renders the hoisted app-level [`currentModal`](current-modal-state.md) (#445) as the **seventh `Scaffold` sibling** — a `when (modalState)` block after the `DeleteConfirmationDialog` block (`ThreadScreen.kt:314`), **outside** the content `Column` (it is a floating dialog window, not part of the thread layout). `MainActivity` collects `currentModal` (and, since [#452](../codebase/452.md), `armedOptionId`) via `collectAsStateWithLifecycle` and forwards them as the defaulted `modalState` / `armedOptionId` params, exactly like `isThinking` / `isStalled`; `modalSendErrors` is forwarded by reference and collected inside `ThreadScreen` (single-consumer; the snackbar is a screen concern). Full doc: [Permission-modal overlay](permission-modal-overlay.md).

- **Separate surface, not a `LazyColumn` row.** `Open` → a private `PermissionModalOverlay` built on **`BasicAlertDialog`** (not the 2-button `AlertDialog` — the option count varies 4/2), rendering verbatim `title` / `prompt` / `options` (wire **array order**); `ModalOptionButton` is a stateless **3-way** ([#452](../codebase/452.md), `isArmed` precedence): the fail-safe-deny `defaultOptionId` → filled `Button`, the VM's armed non-default (`armedOptionId`) → `FilledTonalButton` (below the default's emphasis), else `OutlinedButton`, each with its `stateDescription` marker. `modalClass` is carried but not branched on. **Live since [#452](../codebase/452.md):** the option-tap forwards verbatim to `onModalOption` (the VM decides arm-vs-send, no UI-side arming), an explicit low-emphasis Cancel `TextButton` reaches `onModalCancel`, and `filterTouchesWhenObscured = true` on the dialog's own window adds a tapjacking net now that taps are live.
- **App-level, not per-conversation.** Modal events carry no `conversation_id`, so there is one `currentModal` across the app and the overlay shows over **whichever thread is active** — no `conversationId` filter (contrast the per-conversation thread items).
- **Dismiss = snackbar, not overlay.** `Dismissed` renders no overlay and fires a `LaunchedEffect(modalId)` snackbar surfacing a **mapped local** reason (`dismissReasonText`: remote/local/timeout + a generic forward-compat fallback). The Scaffold gains a `remember { SnackbarHostState() }` + `snackbarHost` (the only change to the existing Scaffold), mirroring the [`ArchivedDiscussionsScreen`](archived-discussions-screen.md) dismiss-reason precedent. Keying on `modalId` (a sticky terminal state in #445's fold) fires it exactly once per resolution.
- **Security (this slice owns the render-time obligations #445 deferred).** Plain `Text` only (never [`MarkdownText`](markdown-text.md)/`SelectionContainer`), and `DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)` sets `FLAG_SECURE` on the **dialog's own window** — the [#381](../codebase/381.md) [`LiteralScreenSurface`](literal-screen-surface.md) precedent flags the **Activity** window, which a dialog draws outside of, so `SecureOn` (not the default `Inherit`) is load-bearing. No modal text reaches `rememberSaveable` / saved-instance state; the mapped-not-echoed dismiss reason is a confidentiality requirement (the snackbar draws in the un-secured Activity window). `dismissOnBackPress`/`dismissOnClickOutside = false` — a permission gate ignores stray taps; cancel is the explicit Cancel button only. [#452](../codebase/452.md) adds the remaining live-tap obligations: **send-error confidentiality** (the `modalSendErrors` event is `Flow<Unit>` + a fixed local `modal_send_failed` string ⇒ structurally no payload reaches the snackbar) and **tapjacking** (`filterTouchesWhenObscured = true` on the dialog's own window, the View-level analog of `SecureOn`, different fabric from the second-confirm UX belt).

### `fun retry()` — non-suspend, VM owns the launch

```kotlin
fun retry() {
    viewModelScope.launch { connectionStateSource.retry() }
}
```

The VM exposes `retry()` as a non-`suspend` method; the `viewModelScope.launch` body wraps the source's `suspend fun retry()`. UI callers bind `vm::retry` directly to `ConnectionBanner`'s `onRetry: () -> Unit` slot without `rememberCoroutineScope { ... }.launch { ... }`. Same shape as `fun sendMessage(text: String)` from #188 — the convention in this codebase is never to expose `suspend` on a VM. If the screen is destroyed mid-call the launch is cancelled, which is fine for the Phase-2 no-op body; in Phase 4 the real source's `suspend fun retry()` may do network I/O, and `viewModelScope` cancellation will propagate as expected.

No `try/catch` around `connectionStateSource.retry()`. Per the `ConnectionStateSource` interface KDoc (#196), failures surface as state transitions (`Offline`), not exceptions; the Phase-2 fake cannot throw. No `.catch { ... }` on the upstream `observe()` either — premature defense.

### `connectionState: StateFlow<ConnectionState>` — same lifetime as `state`

```kotlin
val connectionState: StateFlow<ConnectionState> =
    connectionStateSource.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionState.Connected)
```

`SharingStarted.WhileSubscribed(5_000)` matches the `state` flow's lifetime policy — both share the same `viewModelScope` and subscription window. If the screen resumes within 5s of leaving the back stack, the existing collector is reused (no `Connected` flash from re-subscription). `initialValue = ConnectionState.Connected` matches the fake's seeded value so the screen's first frame paints with the banner already short-circuited.

### `ThreadTopAppBar` — Figma `16:8` chrome

`ThreadTopAppBar(title, onBack, onTitleClick, onOverflowClick, overflowExpanded, onOverflowDismiss, onOverflowEvent, modifier)` is a **public** stateless composable in its own file (`ThreadTopAppBar.kt`). Three slots:

- `navigationIcon` — `IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, R.string.cd_back) }`. `R.string.cd_back` reused — same string that backs every other back-arrow in the app.
- `title` — `Text(text = title, modifier = Modifier.clickable(onClick = onTitleClick).semantics { role = Role.Button })`. The `clickable` modifier rides on `Text` (not on a wrapping `Row`) so the ripple aligns with the visible text bounds rather than the full title-slot column. `Role.Button` keeps TalkBack announcing the title as activatable. The default `TopAppBar` title slot already renders `titleLarge` against `colorScheme.onSurface`, matching Figma `16:14` — do **not** override `style` or `color`.
- `actions` — `Box { IconButton(onClick = onOverflowClick) { Icon(Icons.Filled.MoreVert, R.string.cd_more_actions) }; ThreadOverflowMenu(expanded = overflowExpanded, onDismiss = onOverflowDismiss, onEvent = onOverflowEvent) }`. `MoreVert` lives at `androidx.compose.material.icons.filled.MoreVert` (non-automirrored — the icon is symmetric, no RTL flip). String `cd_more_actions = "More actions"` introduced in #139; named generically so non-thread overflows can reuse it. The `Box` wrap landed in [#252](../codebase/252.md) — M3's `DropdownMenu` anchors to its parent layout, so wrapping the icon + menu in a single `Box` makes the menu open directly below the icon (the canonical M3 single-icon-overflow pattern); placing the menu as a sibling of the icon directly inside the implicit `actions` `Row` would anchor against the row's bounds, mis-positioning a single-icon overflow.

**`TopAppBar` defaults are correct for this ticket.** Figma `16:8` uses `Schemes/surface` for the bar background, which matches `TopAppBarDefaults.topAppBarColors().containerColor` (= `colorScheme.surface`). Do **not** add `colors = TopAppBarDefaults.topAppBarColors(containerColor = ...)`. No `scrollBehavior` (Figma does not specify collapse-on-scroll; explicitly out of scope per the ticket body). No `windowInsets` override (the outer `MainActivity` `Scaffold` doesn't consume the top inset, so the default already handles status-bar inset correctly).

The composable is **stateless** per the project convention — no `remember`, no `MutableState`, no `rememberSnackbarHostState`. All four callbacks are caller-owned.

### Modifier ordering inside the body

`Modifier.padding(inner).fillMaxSize()` — same shape as `DiscussionListScreen.kt:101`. Do **not** invert to `.fillMaxSize().padding(inner)` (that would draw under the AppBar shadow before applying the inset). No `Modifier.systemBarsPadding()` — the outer `Scaffold` in `MainActivity` already passes `innerPadding` into `PyryNavHost`, and the per-screen `Scaffold` adds its own `inner` for the AppBar; both are applied.

## Wiring

### Koin binding

```kotlin
// di/AppModule.kt:36
viewModel { ThreadViewModel(get(), get(), get(), get()) }
```

Post-[#253](../codebase/253.md): four `get()`s. The first resolves to the back-stack entry's `SavedStateHandle` (auto-provided by Koin's `koin-androidx-compose` artifact via `LocalViewModelStoreOwner`, which Compose Navigation 2.9+ wires to the `NavBackStackEntry`). The second resolves to the `ConversationRepository` singleton bound at `AppModule.kt:28` (`single { FakeConversationRepository() } bind ConversationRepository::class`). The third (added in #201) resolves to the `ConnectionStateSource` singleton bound at `AppModule.kt:31` since #196 (`single { FakeConnectionStateSource() } bind ConnectionStateSource::class`). The fourth (added in #253) resolves to the `AppPreferences` singleton bound at `AppModule.kt:29` since [#11](../codebase/11.md) (`single { AppPreferences(get()) }`). No new module entries — all four deps were already in scope; no new `gradle/libs.versions.toml` entries.

### Destination block

```kotlin
// MainActivity.kt:197-218 (post-#227)
composable(
    route = Routes.CONVERSATION_THREAD,
    arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
) {
    val vm = koinViewModel<ThreadViewModel>()
    val state by vm.state.collectAsStateWithLifecycle()
    val connectionState by vm.connectionState.collectAsStateWithLifecycle()   // added in #201
    LaunchedEffect(vm) {                                                      // added in #227 — one-shot pop-back
        vm.navigationEvents.collect { event ->
            when (event) { ThreadNavigation.PopBack -> navController.popBackStack() }
        }
    }
    ThreadScreen(
        state = state,
        onBack = { navController.popBackStack() },
        onSendMessage = vm::sendMessage,                       // added in #188
        connectionState = connectionState,                     // added in #201
        onRetry = vm::retry,                                   // added in #201
        onOverflowEvent = vm::onOverflowEvent,                 // added in #252
        onModelSelected = vm::onModelSelected,                 // added in #254 (replaces onExpandClick = {} from #145)
        onWorkspaceChipTapped = vm::onWorkspaceChipTapped,             // added in #137
        onWorkspacePicked = vm::onWorkspacePicked,                     // added in #137
        onWorkspacePickerDismissed = vm::onWorkspacePickerDismissed,   // added in #137
    )
}
```

The `Routes.CONVERSATION_THREAD = "conversation_thread/{conversationId}"` constant, the `navArgument` declaration, and the four pre-existing call sites at `MainActivity.kt:142-143, 151-152, 169-170, 178-179` are **all unchanged** from #15 / #126. The `onTitleClick` lambda still falls through to its `{}` default until #141 (rename dialog) wires `onTitleClick = { showRenameDialog = true }`. The `onOverflowEvent = vm::onOverflowEvent` binding landed in [#252](../codebase/252.md) — the icon-tap trigger now lives internally inside `ThreadScreen` (`onOverflowClick = { overflowExpanded = true }` passed to `ThreadTopAppBar`), and each menu-item tap escapes through `onOverflowEvent` to the VM dispatcher. The `// TODO(#146): open Status Sheet` placeholder + the `onExpandClick = {}` line that #145 introduced were both deleted in [#254](../codebase/254.md); the sheet-open trigger now lives internally inside `ThreadScreen` (`onExpandClick = { sheetVisible = true }` inside the `bottomBar`), and the new `onModelSelected = vm::onModelSelected` binding forwards the radio-row tap to [`ThreadViewModel.onModelSelected`](thread-screen.md#viewmodel) ([#253](../codebase/253.md)). Method-reference syntax `vm::sendMessage` / `vm::retry` / `vm::onOverflowEvent` / `vm::onModelSelected` (rather than fresh lambdas) avoids new lambda allocations per recomposition of the destination block. [#227](../codebase/227.md) added the `LaunchedEffect(vm) { vm.navigationEvents.collect { … popBackStack() } }` block (plus an `import …thread.ThreadNavigation`) — **the only structural change to this block since the chrome**; every prior thread event rode the additive `vm::onOverflowEvent` binding without touching `MainActivity`. The pop-back consumer is here (not in `ThreadScreen`) because `popBackStack()` is a NavHost concern, and it reuses the same `popBackStack()` the `onBack` lambda already calls.

## Configuration

- **Dependencies:** no new entries. `ConversationRepository` was already on classpath; `kotlinx.coroutines.flow.stateIn` rides in via the existing `kotlinx-coroutines-core` (catalog: `libs.coroutines.core`). No `gradle/libs.versions.toml` edits.
- **Strings:** `R.string.cd_back` reused, `R.string.cd_more_actions` added in #139 (`<string name="cd_more_actions">More actions</string>`), `R.string.cd_thread_status_expand` added in #145 for the trailing icon on [`ThreadStatusRow`](thread-status-row.md). Naming follows the project's `cd_*` content-description convention.
- **State survives process death.** The back-stack entry's `SavedStateHandle` is re-populated with the `navArgument` value on process recreation; the VM constructor reads `conversationId` identically on fresh creation and restoration. `stateIn` re-subscribes on first `collectAsStateWithLifecycle()` call after restoration.

## Testing

`app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt` — thirty-two JUnit 4 tests post-[#227](../codebase/227.md) (seven from #139, two from #188, three from #201, six from #137 in the workspace-chip group, one added in #246 for the `items` passthrough, two added in #145 for the stub effort/tokenPercent + default-model fields, four added in #253 for `selectedModel` plumbing, three added in #226 for channel-info open/dismiss/ingredient-population, four added in #227 for the delete family + one-shot nav — the numbered list below covers the #139→#253 core; the #226/#227 additions are summarised in the sibling-test paragraph after it). The pre-#139 plain-JUnit shape (no `runTest`, no `Dispatchers.setMain`) no longer works because `stateIn(viewModelScope, …)` requires a `Main` test dispatcher to publish emissions in test scope; post-#253 the file additionally needs the `runTest { }` wrapper around every test because `makeVm` is now a `TestScope.()` receiver function that constructs a `TemporaryFolder`-backed `AppPreferences` on `backgroundScope`. The file adopts the canonical scaffold from `ChannelListViewModelTest:1-60` (extended in #253 with the prefs-DataStore harness from `AppPreferencesTest`):

```kotlin
@Before fun setUpMainDispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
@After  fun tearDownMainDispatcher() { Dispatchers.resetMain() }
```

Since #201 the file also carries a `private fun makeVm(handle, repository, source = FakeConnectionStateSource())` helper at the bottom. Every existing `ThreadViewModel(handle, repository)` call site was rewritten as `makeVm(handle, repository)` to absorb the new constructor arg without threading a fixture through nine call sites — the default arg keeps `state`-focused tests terse and the three connection-focused tests pass an explicit source. Same shape as `ChannelListViewModelTest.makeVm` from #239. In [#253](../codebase/253.md) the helper gained a fourth defaulted parameter `prefs: AppPreferences = AppPreferences(newDataStore())` and became a `TestScope` receiver function (so it can call the `TestScope.newDataStore()` helper that builds a `PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { tmp.newFile("prefs_${UUID.randomUUID()}.preferences_pb") })`). The `UUID.randomUUID()` per call guarantees per-VM prefs isolation when a single test constructs two VMs in one `runTest { }` block.

Tests:

1. **`state_initialValue_isConversationIdPlaceholderBeforeSubscription`** — synchronously reads `vm.state.value` *without* a launched collector; asserts the `stateIn` `initialValue` is `ThreadUiState(id, displayName = id)`. Pins the placeholder contract.
2. **`state_resolvedTitle_isChannelNameForSeededChannel`** — `runTest { launch collector; advanceUntilIdle(); assert displayName == "Personal" }` against `seed-channel-personal` and `FakeConversationRepository()`. Pins the happy-path channel-name resolution.
3. **`state_resolvedTitle_isUntitledDiscussionForUnnamedDiscussion`** — same shape against `seed-discussion-a` (seeded with `name = null, isPromoted = false`). Pins the discussion fallback.
4. **`state_resolvedTitle_isUntitledChannelForUnnamedChannel`** — uses the file-scope `fixedRepo(listOf(Conversation(name = null, isPromoted = true, …)))` helper; asserts `"Untitled channel"`. Pins the channel fallback.
5. **`state_resolvedTitle_fallsBackToConversationIdWhenConversationMissing`** — `fixedRepo(emptyList())` with `conversationId = "ghost-id"`; asserts `displayName == "ghost-id"`. Pins the missing-conversation fallback.
6. **`state_displayName_reemitsOnRename`** — collect into a `mutableListOf<ThreadUiState>()`; call `repository.rename("seed-channel-personal", "Personal — renamed")` inside the same `runTest` block; assert the post-rename emission's `displayName` matches. **Pins the load-bearing contract** that the downstream rename dialog (#141) inherits "for free" from option (c).
7. **`state_collapsesAbsentConversationIdToEmptyString`** — `SavedStateHandle(initialState = emptyMap())`; assert `state.value.conversationId == ""` synchronously. Pins the `.orEmpty()` narrowing (path not user-reachable in production).
8. **`sendMessage_blankText_isNoOp`** (#188) — calls `vm.sendMessage("")` then `vm.sendMessage("   \n\t ")`; asserts observed messages unchanged. VM-side blank-rejection contract.
9. **`sendMessage_nonBlankText_appendsToConversation`** (#188) — calls `vm.sendMessage("Hello world")`; asserts the appended `ThreadItem.MessageItem` lands with `content = "Hello world"`, `role = Role.User`, `sessionId = "seed-session-personal"`.
10. **`connectionState_initialValue_isConnected`** (#201) — construct VM with default `FakeConnectionStateSource()`. Without any collector, assert `vm.connectionState.value == ConnectionState.Connected`. Pins the AC1 default + the `WhileSubscribed` initialValue contract. Synchronous; no `runTest { }` wrapper needed.
11. **`connectionState_reemitsOnSourceChange`** (#201) — construct a `FakeConnectionStateSource` explicitly, build the VM with it, launch a collector, call `source.emit(ConnectionState.Offline)`, `advanceUntilIdle()`, assert `vm.connectionState.value == ConnectionState.Offline`. Pins the AC1 "exposes current state" wiring (not just the initialValue).
12. **`retry_invokesSourceRetry`** (#201) — construct a `RecordingConnectionStateSource` (file-private test double; see below), call `vm.retry()`, `advanceUntilIdle()`, assert `source.retryCallCount == 1`. Pins AC2: the VM actually forwards to the source rather than swallowing the call.
13. **`state_workspaceLabel_isScratch_whenCwdIsEmptyString`** (#137) — `repository.createDiscussion(workspace = null)`, assert pre-condition `freshDiscussion.cwd == ""`, then assert `vm.state.value.workspaceLabel == "scratch"`. Exercises the `cwd.isEmpty()` branch of `Conversation.workspaceLabel()` — the actual default state of a fresh discussion per `FakeConversationRepository.createDiscussion`.
14. **`state_workspaceLabel_isScratch_whenCwdIsDefaultScratchSentinel`** (#137) — `fixedRepo` with one discussion at `cwd = DEFAULT_SCRATCH_CWD`, assert label `"scratch"`. Exercises the sentinel branch — both `""` and the sentinel must collapse to the same label, matching `FakeConversationRepository.bumpWorkspace`'s no-bound-workspace filter.
15. **`state_workspaceLabel_isBasename_forArbitraryCwd`** (#137) — `fixedRepo` with `cwd = "pyry-workspace/my-app"`, assert label `"my-app"`. Exercises the `substringAfterLast('/')` happy path.
16. **`state_chipFields_reflectChannelAndMessagePresence`** (#137) — single test walking both raw chip-gate fields across two VMs. Seeded channel (`seed-channel-personal`) → assert `isPromoted = true`, `hasMessages = true` (the seed carries messages). Fresh discussion via `createDiscussion(null)` → assert `isPromoted = false`, `hasMessages = false`. Then call `discussionVm.sendMessage("hi")`, `advanceUntilIdle()`, assert `hasMessages` flipped to `true` while `isPromoted` stays `false`. Locks the two raw signals that the chip's call-site `if (!isPromoted && !hasMessages)` consumes.
17. **`onWorkspacePicked_callsChangeWorkspaceOnceAndClearsPickerFlag`** (#137) — fresh discussion, call `vm.onWorkspaceChipTapped()`, assert `workspacePickerVisible == true`. Call `vm.onWorkspacePicked("pyry-workspace/my-app")`, `advanceUntilIdle()`, assert `workspacePickerVisible == false` AND the conversation's `cwd` is now `"pyry-workspace/my-app"` (re-fetched via `repository.observeConversations(All).first().first { it.id == … }`). Pins the side-effect-plus-flag-clear contract.
18. **`onWorkspacePickerDismissed_clearsFlagWithoutCallingChangeWorkspace`** (#137) — fresh discussion, `onWorkspaceChipTapped`, `onWorkspacePickerDismissed`, assert `workspacePickerVisible == false` AND the conversation's `cwd` is unchanged (re-fetched via the same path). Pins the no-side-effect dismiss path.
19. **`state_items_reflectsObserveMessagesStream`** (#246) — `FakeConversationRepository()`, VM on `seed-channel-personal` (the fake's seeded channel carries seeded messages per [#161](../codebase/161.md)), `runTest { launch collector; advanceUntilIdle() }`, assert `vm.state.value.items.isNotEmpty()` and `vm.state.value.items.first() is ThreadItem.MessageItem`. Mirrors the shape of `sendMessage_nonBlankText_appendsToConversation` from [#188](../codebase/188.md). Pins the load-bearing passthrough: the `observeMessages` stream now reaches the screen, not just the `hasMessages` derivation.
20. **`state_initialValue_includesDefaultModelEffortAndTokenPercentDefaults`** (#145; renamed in [#253](../codebase/253.md) from `*Stub*` → `*Default*`) — wrapped in `runTest { }` for the prefs-IO context (was synchronous pre-#253). Constructs `makeVm(handle, FakeConversationRepository())`, asserts `vm.state.value.selectedModel == Model.OPUS_4_7`, `effort == "high"`, `tokenPercent == 0`. Pins the data-class default contract — the pre-subscription initial frame that `stateIn(initialValue = …)` publishes.
21. **`state_postSubscription_emitsDefaultModelEffortAndTokenPercent`** (#145; renamed in [#253](../codebase/253.md)) — `runTest { launch collector; advanceUntilIdle() }`, asserts `selectedModel == Model.OPUS_4_7`, `effort == "high"`, `tokenPercent == 73`. Pins the VM's explicit population inside `combine` — the assertion that fails if the Phase-4 swap drops `effort` or `tokenPercent`, or if the prefs-defaulted `selectedModel` stops propagating through the new pre-combined `selectedModelFlow`. The 0-vs-73 asymmetry on `tokenPercent` between #20 and #21 is intentional.
22. **`selectedModel_followsAppPreferencesDefault`** ([#253](../codebase/253.md)) — `runTest { }`; calls `prefs.setDefaultModel(Model.SONNET_4_6)` **before** VM construction, then `vm.state.first { it.selectedModel == Model.SONNET_4_6 }` inside `withTimeout(2.seconds)`. Pins the AC line "populates `selectedModel` from `appPreferences.defaultModel` on conversation open".
23. **`selectedModel_reemitsWhenAppPreferencesDefaultChanges`** ([#253](../codebase/253.md)) — subscribe, assert default `Model.OPUS_4_7`, call `prefs.setDefaultModel(Model.HAIKU_4_5)`, assert `state.first { it.selectedModel == Model.HAIKU_4_5 }`. Pins the reactive contract — the override-less case is a pure passthrough of `appPreferences.defaultModel`.
24. **`onModelSelected_overridesPerConversationWithoutMutatingPreferences`** ([#253](../codebase/253.md)) — the AC's **explicit verification line**: pre-asserts `prefs.defaultModel.first() == Model.OPUS_4_7`, calls `vm.onModelSelected(Model.HAIKU_4_5)`, asserts `vm.state.value.selectedModel == Model.HAIKU_4_5`, then re-asserts `prefs.defaultModel.first() == Model.OPUS_4_7` (unchanged). Fails if anyone wires `onModelSelected` to also call `appPreferences.setDefaultModel(...)`.
25. **`onModelSelected_overrideWinsOverSubsequentDefaultChange`** ([#253](../codebase/253.md)) — sets override to `Model.HAIKU_4_5`, then changes the prefs default to `Model.SONNET_4_6`, asserts `selectedModel` stays `Model.HAIKU_4_5`. Pins the `override ?: default` rule baked into `selectedModelFlow`.


The `fixedRepo(conversations)` helper is an anonymous `object : ConversationRepository { … }` with `TODO("not used")` overrides plus a `flowOf(conversations)`-backed `observeConversations`. It's kept local rather than extracted — each test's bespoke conversation shape would force a builder-shaped helper that doesn't pay for itself yet.

Sibling test file added in [#136](../codebase/136.md): `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenCutoffTest.kt` — six JUnit 4 tests against the `internal` top-level helper `mostRecentSessionBoundaryIndex(items: List<ThreadItem>): Int`: `emptyList → -1`, `messagesOnly → -1`, `singleBoundary → its index`, `multipleBoundaries → latest index`, `boundaryAtFirstPosition → 0`, `boundaryAtLastPosition → lastIndex`. No `runTest`, no `Dispatchers.setMain`, no coroutines — the helper is pure and synchronous. File-private `msg(id, sessionId, role, timestamp)` and `boundary(previousSessionId, newSessionId, occurredAt)` constructors keep the body terse; `BoundaryReason.Clear` is fine for every fixture (the helper doesn't discriminate on reason). The Compose-side correctness of the per-row `Box(Modifier.alpha(...))` wrap is verified visually by the two new `@Preview`s; no `ComposeTestRule` in this ticket because there's no interactive behaviour to assert.

Sibling test files added in [#226](../codebase/226.md): `app/src/test/java/.../thread/ThreadScreenMapperTest.kt` — three JVM unit tests over the pure `internal fun ThreadUiState.toChannelInfoUiModel(now)` mapper (label/count derivation + pass-through with a seeded `items` list and injected `now`; empty-items → em-dash `createdLabel` + zero `messageCount`; null `lastUsedAt` → em-dash `lastActivityLabel`), and `app/src/androidTest/java/.../thread/ThreadScreenChannelInfoTest.kt` — an instrumented Compose test (six `@Test`s following the `ThreadScreenOverflowTest` idiom) asserting the sheet renders with `channelInfoOpen = true` and that each button records the right event sequence (Rename / Change workspace emit-then-dismiss; Archive / Delete / close dismiss-only). `ThreadViewModelTest` also gained three `@Test`s (open / dismiss / ingredient-population from a seeded `Conversation`), and the existing `onOverflowEvent_otherCases_doNotCallArchive` dropped `ChannelInfo` from its iteration list (moved to its own positive test). The instrumented file is written but **not run** (no device); see [`../codebase/226.md`](../codebase/226.md) for the full breakdown.

Tests added in [#227](../codebase/227.md): in `ThreadViewModelTest`, `RecordingRepo` gains a `delete` override + `deleteCalls` list (else it inherits the throwing interface default and the confirm test crashes), and nav is asserted by collecting `navigationEvents` into a list via a second `launch`. The existing archive test was renamed `onOverflowEvent_archive_archivesClosesSheetAndPopsBack` and now also asserts `channelInfoOpen == false` + one `PopBack`; three new `@Test`s cover delete-tap (dialog opens, no `delete` call, no nav), delete-cancel (`DeleteDismiss` closes the dialog, sheet stays open, no `delete`), and delete-confirm (`delete` called + both surfaces closed + one `PopBack`); plus `navigationEvents_eachPopBackDeliveredExactlyOnce_notReplayed` pins AC #5 (consumed `PopBack` not replayed; a second trigger produces its own single event). `ThreadScreenChannelInfoTest` gained a `deleteConfirmState()` helper + a defaulted `state` param on `setContent`, renamed the two archive/delete tests to expect `[ThreadEvent.Archive]` / `[ThreadEvent.Delete]`, and added three dialog tests (title + interpolated-body display, dialog **Delete** → `[DeleteConfirm]`, **Cancel** → `[DeleteDismiss]`) — written but **not run** (no device). See [`../codebase/227.md`](../codebase/227.md) for the full breakdown.

The `RecordingConnectionStateSource` test double introduced in #201 lives at the bottom of `ThreadViewModelTest.kt` alongside `fixedRepo`:

```kotlin
private class RecordingConnectionStateSource : ConnectionStateSource {
    private val state = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
    var retryCallCount: Int = 0
        private set
    override fun observe(): Flow<ConnectionState> = state.asStateFlow()
    override suspend fun retry() { retryCallCount++ }
}
```

It cannot reuse `FakeConnectionStateSource` because the fake's `retry()` is a no-op — an assertion-of-effect through the fake would have nothing to observe. The recording double is deliberate test-local fixture; pre-staging this as a public-ish helper in `data/repository/` would be premature (no other consumer needs it).

No instrumented Compose test for `ThreadScreen` or `ThreadTopAppBar`. The codebase has no `androidTest` infrastructure for thread/list screens beyond existing fixtures. Visual verification is by `./gradlew assembleDebug` + `./gradlew installDebug` + manual tap from channel-list / discussion-list / FAB-create-discussion paths (channel name renders, discussion fallback renders, banner stays hidden under steady-state `Connected`, back pops, title-tap and overflow-tap are no-op stubs with TalkBack announcing role + content description correctly).

## Previews

Four `@Preview`s at the bottom of `ThreadScreen.kt`:

- **`ThreadScreenLightPreview`** ([#246](../codebase/246.md), `tokenPercent = 73` added in [#145](../codebase/145.md)) — `PyrycodeMobileTheme(darkTheme = false) { ThreadScreen(state = ThreadUiState(conversationId = "seed-channel-personal", displayName = "kitchenclaw refactor", isPromoted = true, items = previewItems(), tokenPercent = 73), onBack = {}, onSendMessage = {}, connectionState = ConnectionState.Connected, onRetry = {}) }`. The `"kitchenclaw refactor"` seed reproduces the Figma `16:14` title literal. The `tokenPercent = 73` lands the status row in the warning band so the screen-level preview exercises the threshold-driven color.
- **`ThreadScreenDarkPreview`** ([#246](../codebase/246.md), `tokenPercent = 73` added in [#145](../codebase/145.md)) — same seed with `darkTheme = true`. Verifies the AppBar `Schemes/surface` `#101418` token, the [`MessageBubble`](message-bubble.md) bubble palettes, and the [`SessionBoundaryDelimiter`](session-boundary-delimiter.md) divider colour all render against the dark theme without explicit color overrides.
- **`ThreadScreenAboveDelimiterDimLightPreview`** ([#136](../codebase/136.md), `tokenPercent = 73` added in [#145](../codebase/145.md)) — same wrapper, seed = `previewItemsWithBoundaries()` (nine items, two `SessionBoundary` markers). The older `s0 → s1 Clear` delimiter renders at `0.55f` (AC5: older boundaries are themselves dimmed); the newer `s1 → s2 WorkspaceChange` delimiter renders at `1f` (the cutoff line stays full-opacity).
- **`ThreadScreenAboveDelimiterDimDarkPreview`** ([#136](../codebase/136.md), `tokenPercent = 73` added in [#145](../codebase/145.md)) — same seed with `darkTheme = true`. Verifies the `0.55f` alpha is comfortably legible against the M3 dark surface — the deliberate `0.55f` (story) vs Figma's `0.5f` (frame) split pays off here.

The original two previews call file-private `previewItems(): List<ThreadItem>` added in [#246](../codebase/246.md) that builds four chronologically-ordered items: one `User` message, one `Assistant` message, one `Tool` message with a `ToolCall(toolName = "read_file", input = "kitchenclaw/db/schema.ts", output = "184 lines")`, and one `SessionBoundary(reason = BoundaryReason.Clear)`. Timestamps via `Instant.parse("2026-05-17T14:32:00Z")` with +10s / +20s / +60s offsets. **`isPromoted = true` is load-bearing** — `hasMessages` is a default-`false` raw field on a hand-constructed `ThreadUiState` (previews don't go through the VM's `combine` reducer, so `items.any { it is MessageItem }` derivation never runs), and `isPromoted = false` + `hasMessages = false` would light the `WorkspaceChip` gate. Setting `isPromoted = true` suppresses the chip and is semantically faithful to the channel-name seed.

The two `…AboveDelimiterDim…` previews call a sibling file-private `previewItemsWithBoundaries(): List<ThreadItem>` added in [#136](../codebase/136.md): two `s0` messages (user + assistant) above an `s0 → s1 BoundaryReason.Clear` delimiter, then three `s1` messages (user + assistant + a `Role.Tool` `ToolCall(toolName = "read_file", input = "kitchenclaw/db/schema.ts", output = "184 lines")`) above an `s1 → s2 BoundaryReason.WorkspaceChange` (`workspaceCwd = "~/Workspace/Projects/KitchenClaw"`), then two `s2` messages. Chronologically ascending timestamps via `Instant.parse(...)` literals starting at `2026-05-17T13:00:00Z`. The two-boundary shape is required by AC6 — a single-boundary seed couldn't visually exercise AC5 ("older boundaries are themselves dimmed").

All four previews use the steady-state `ConnectionState.Connected`, so the banner is invisible. **No `Offline` / `Connecting` / `Reconnecting` preview variants here** — the four-state matrix already lives in [`ConnectionBannerPreviewMatrix`](connection-banner.md#preview) from #200; duplicating would add no fidelity beyond confirming the wiring. None passes `onTitleClick` / `onOverflowEvent` — the defaults apply (the menu is system-popup-windowed, so the closed-state preview is faithful and no extra preview is needed for the open state). No standalone preview for `ThreadTopAppBar` (would duplicate the screen-level preview's coverage).

## Edge cases / limitations

- **Message rendering is wired into the `LazyColumn` post-#246.** The VM's `combine` reducer now projects the full `List<ThreadItem>` onto `ThreadUiState.items` (the same list it already collected for the `hasMessages` gate); the screen iterates `state.items.asReversed()` via `itemsIndexed(...)` (since [#136](../codebase/136.md)) and dispatches `MessageItem` / `SessionBoundary` at the sealed-interface level. Sending a message via the composer (#188) appends through the repository and re-emits — the new message renders at the bottom of the list, the workspace chip disappears, and the empty-state prompt is replaced by the list on the same emission. **Above-delimiter opacity is wired since [#136](../codebase/136.md)** — `Box(Modifier.alpha(ABOVE_DELIMITER_ALPHA))` wraps rows whose chronological index is strictly before `mostRecentSessionBoundaryIndex(state.items)`; the boundary itself and everything after stay at `1f`. **No scroll-to-bottom on append in the general case** — landing a new message does not auto-scroll; the `reverseLayout = true` flag pins index 0 of the reversed view to the bottom but a scrolled-up user is not snapped back. Tracked as a follow-up. **Exception: streaming auto-anchor (since [#185](../codebase/185.md))** — while any item in `state.items` has `isStreaming = true`, the column re-anchors to the bottom on every layout-pass size change of item 0, yielding to user-initiated drag/fling via a `NestedScrollConnection` filter on `NestedScrollSource.UserInput`. See the [`LazyColumn` section](#lazycolumnreverselayout--true--established-in-126-populated-in-246-dimmed-in-136-nested-in-a-column-since-201) above for the full wiring. **No animation on opacity transitions** — when a new `SessionBoundary` arrives the affected rows swap to `0.55f` instantly; an `animateFloatAsState(targetValue = rowAlpha)` per-row fade is a follow-up that owns its own Compose-side test.
- **Empty-thread prompt renders in the `weight(1f)` slot when `!state.hasMessages` (since [#138](../codebase/138.md)).** [`EmptyThreadState`](empty-thread-state.md) takes over the space the `LazyColumn` would have occupied — same `weight(1f)`, plus a 24.dp horizontal inset. The branch is a pure render decision against the existing `hasMessages` field (no new VM surface). On a fresh discussion the empty branch coexists with the chip above it; on a fresh channel only the empty branch renders (the chip is gated on `!isPromoted` as well). The prompt and the chip appear/disappear together on the same `combine`-arm re-emission. **No `AnimatedVisibility` on the swap** — `Crossfade` between `EmptyThreadState` and `LazyColumn` is a follow-up if designer signs off on a duration.
- **`displayName` falls back to the raw `conversationId` when the lookup misses.** Path is not user-reachable in production (every nav edge passes a real id) but the property is observable and pinned by a test. Deep links and process-death restoration go through the same path.
- **Banner is invisible under normal use.** The Phase-2 `FakeConnectionStateSource` always emits `Connected`; the banner short-circuits to zero height. The integration is exercised by VM unit tests that push `Offline` / `Connecting` / `Reconnecting` through the fake — the user-facing disconnected affordance lands when Phase 4 swaps the binding to the real Ktor-backed source.
- **`retry()` is a Phase-2 no-op.** The fake's `retry()` does nothing; the VM still routes the call through `viewModelScope.launch` so the Phase-4 swap is binding-only. Tapping the `Offline` banner today fires the ripple, increments `RecordingConnectionStateSource.retryCallCount` in tests, and does nothing observable in the running app.
- **Title-tap ripple aligns with text bounds, not the full title-slot column.** Deliberate tradeoff for the rename entry — if #141's UX widens to "tap anywhere in the bar to rename", `ThreadTopAppBar` widens the clickable region to a wrapping `Row`.
- **`onTitleClick` is still a no-op stub at the destination block.** #141 wires the rename dialog through it. Until that lands, the title-tap fires a ripple, announces `Role.Button` to TalkBack, and does nothing visible. The overflow icon's no-op posture ended in [`#252`](../codebase/252.md) — tapping the icon opens the mounted [`ThreadOverflowMenu`](thread-overflow-menu.md), and tapping any item escapes via `onOverflowEvent = vm::onOverflowEvent` to the VM dispatcher (the four non-`Archive` cases still no-op at the VM level until each per-item follow-up wires its handler — UX-wise the menu still closes correctly thanks to the dismiss-before-handler ordering).
- **No scroll behaviour on the TopAppBar.** Figma `16:8` does not specify collapse-on-scroll or elevation overlay; `scrollBehavior` is left at the default. Out-of-scope per the ticket body.
- **No `Modifier.systemBarsPadding()` on the per-screen `Scaffold`.** Outer `MainActivity` `Scaffold` already passes `innerPadding`; status-bar inset is already handled upstream — don't add it again.
- **Per-emission `firstOrNull` scan.** `O(n)` over the full conversation list. Acceptable at Phase 0 fake-cardinality; a follow-up introduces `observeConversation(id): Flow<Conversation?>` when the Phase 4 Ktor-backed impl makes the cost real.
- **No `AnimatedVisibility` on banner state changes.** State changes swap the banner in/out instantly. Open follow-up — needs a designer-signed-off cross-fade duration.

## Related

- Ticket notes: [`../codebase/126.md`](../codebase/126.md) (skeleton), [`../codebase/139.md`](../codebase/139.md) (TopAppBar promotion), [`../codebase/188.md`](../codebase/188.md) (input bar + `sendMessage`), [`../codebase/201.md`](../codebase/201.md) (ConnectionBanner wiring), [`../codebase/137.md`](../codebase/137.md) (WorkspaceChip + WorkspacePicker hosting + `ThreadUiState` widening to six fields), [`../codebase/246.md`](../codebase/246.md) (LazyColumn body filled with typed `ThreadItem` render loop + seventh `items` field on `ThreadUiState`), [`../codebase/136.md`](../codebase/136.md) (above-delimiter opacity + `itemsIndexed` migration + `mostRecentSessionBoundaryIndex` helper + `previewItemsWithBoundaries`), [`../codebase/138.md`](../codebase/138.md) (empty-state prompt + `if (!hasMessages) … else …` branch around the `LazyColumn`), [`../codebase/145.md`](../codebase/145.md) (ThreadStatusRow stacked above ThreadInputBar in bottomBar; `model`/`effort`/`tokenPercent` on ThreadUiState; the hoisted `onExpandClick` placeholder that #254 later deleted), [`../codebase/251.md`](../codebase/251.md) (sealed `ThreadEvent` + `ThreadViewModel.onOverflowEvent` dispatcher; the [`ThreadOverflowMenu`](thread-overflow-menu.md) composable lands exported but not mounted), [`../codebase/252.md`](../codebase/252.md) (mounts `ThreadOverflowMenu` inside `ThreadTopAppBar`'s `actions` slot, hoists `var overflowExpanded by rememberSaveable { mutableStateOf(false) }`, renames `onOverflowClick` to `onOverflowEvent`, binds `vm::onOverflowEvent` at the `MainActivity` destination), [`../codebase/254.md`](../codebase/254.md) ([`StatusSheet`](status-sheet.md) shell + Model section + screen-hoisted `sheetVisible` + `onModelSelected` parameter swap), [`../codebase/141.md`](../codebase/141.md) (rename dialog wiring — `RenameDialog` composable hosted as a `Scaffold` sibling, `showRenameDialog: Boolean` field on `ThreadUiState`, `pendingRenameDialog: MutableStateFlow<Boolean>` as the fourth `combine` source, two new `ThreadEvent` cases `RenameSubmit(name)` + `RenameDismiss`, three new arms on `onOverflowEvent` that replace the [#252](../codebase/252.md) `Rename` no-op), [`../codebase/185.md`](../codebase/185.md) (streaming auto-scroll — `LazyListState` hoist + `NestedScrollConnection` user-input filter + two `LaunchedEffect`s on item 0's measured size and the bottom-anchor predicate), [`../codebase/226.md`](../codebase/226.md) ([`ChannelInfoSheet`](channel-info-sheet.md) host as a fifth `Scaffold` sibling — `channelInfoOpen` + three ingredient fields on `ThreadUiState`, `pendingChannelInfo` folded into `TransientDialogs`, `ChannelInfoDismiss` event, and the pure `toChannelInfoUiModel` mapper), [`../codebase/227.md`](../codebase/227.md) (the sheet's Archive / Delete wired — `Archive` close-and-pop, `Delete` / `DeleteConfirm` / `DeleteDismiss` + the `DeleteConfirmationDialog` sixth sibling + `deleteConfirmVisible` flag + the `ThreadNavigation` one-shot pop-back channel collected in `MainActivity`), [`../codebase/446.md`](../codebase/446.md) (the permission-modal overlay as the seventh `Scaffold` sibling — the `modalState` / `onModalOption` / `onModalCancel` params + the snackbar host + the `PermissionModalOverlay` / `ModalOptionButton` / `dismissReasonText` private composables + the dialog-window `FLAG_SECURE`), [`../codebase/15.md`](../codebase/15.md) (the placeholder route this slice replaces)
- Specs: `docs/specs/architecture/15-conversation-thread-placeholder-route.md`, `docs/specs/architecture/126-thread-screen-skeleton.md`, `docs/specs/architecture/139-thread-topappbar-back-title-overflow.md`, `docs/specs/architecture/188-thread-input-bar.md`, `docs/specs/architecture/201-thread-screen-wire-connectionbanner.md`, `docs/specs/architecture/137-workspace-chip-empty-new-discussion-thread.md`, `docs/specs/architecture/246-wire-thread-items-into-lazycolumn.md`, `docs/specs/architecture/136-above-delimiter-opacity-treatment.md`, `docs/specs/architecture/138-empty-thread-state-copy-visual.md`, `docs/specs/architecture/145-thread-status-row.md`, `docs/specs/architecture/185-auto-scroll-thread-streaming.md`, `docs/specs/architecture/252-thread-overflow-menu-mount-and-wire.md`, `docs/specs/architecture/227-channelinfosheet-archive-delete-actions.md`
- Upstream: [Navigation](navigation.md) (the `conversation_thread/{conversationId}` route this destination consumes), [Conversation repository](conversation-repository.md) (the `observeConversations(All)` + `observeMessages` + `changeWorkspace` surfaces the VM consumes; `sendMessage` it forwards to since #188; `observeMessages` + `changeWorkspace` added since #137), [Connection state](connection-state.md) (the `ConnectionStateSource.observe()` / `retry()` contract the VM consumes since #201), [Dependency injection](dependency-injection.md) (Koin `viewModel { ThreadViewModel(get(), get(), get()) }` binding — third `get()` added in #201)
- Child components: [Thread input bar](thread-input-bar.md) (the composer in `bottomBar`, landed in #188), [Thread status row](thread-status-row.md) (the always-visible status surface stacked above the input bar in `bottomBar`, landed in #145; tap-to-open the Status Sheet wired in #254), [ConnectionBanner](connection-banner.md) (the banner between TopAppBar and message list, wired in #201), [WorkspaceChip](workspace-chip.md) (the empty-discussion chip between banner and list, wired in #137), [WorkspacePicker](workspace-picker.md) (rendered as Scaffold sibling, wired in #137), [StatusSheet](status-sheet.md) (rendered as Scaffold sibling gated on screen-hoisted `sheetVisible`, wired in #254 — Model section only; #229/#230 append sibling sections), [RenameDialog](rename-dialog.md) (rendered as Scaffold sibling gated on `state.showRenameDialog`, wired in [#141](../codebase/141.md); seeded with `state.displayName` as `initialName`, emits `ThreadEvent.RenameSubmit(name)` / `RenameDismiss` through `onOverflowEvent`), [ChannelInfoSheet](channel-info-sheet.md) (rendered as Scaffold sibling gated on `state.channelInfoOpen`, wired in [#226](../codebase/226.md); fed a `ChannelInfoUiModel` via the pure `state.toChannelInfoUiModel(now)` mapper, Rename / Change workspace emit-then-dismiss through `onOverflowEvent`; Archive / Delete wired in [#227](../codebase/227.md) — Archive closes-and-pops, Delete opens the `DeleteConfirmationDialog`), [DeleteConfirmationDialog](#channelinfosheet-archivedelete--pop-back-nav-post-227) (private confirm dialog rendered as the sixth Scaffold sibling gated on `state.deleteConfirmVisible`, wired in [#227](../codebase/227.md)), [Permission-modal overlay](permission-modal-overlay.md) (the `PermissionModalOverlay` `BasicAlertDialog` rendered as the seventh Scaffold sibling when `modalState is ModalUiState.Open`, wired in [#446](../codebase/446.md); collects the hoisted app-level [`currentModal`](current-modal-state.md) from #445, dialog-window `FLAG_SECURE`, `Dismissed` → mapped-reason snackbar; answering is #444), [EmptyThreadState](empty-thread-state.md) (the centered empty-thread prompt that occupies the `weight(1f)` slot when `!hasMessages`, wired in #138), [MessageBubble](message-bubble.md) (`MessageItem` row dispatch since #246), [SessionBoundaryDelimiter](session-boundary-delimiter.md) (`SessionBoundary` row dispatch since #246), [ThreadOverflowMenu](thread-overflow-menu.md) (stateless `DropdownMenu` of five overflow items consuming the sealed `ThreadEvent`; landed exported in [`#251`](../codebase/251.md), mounted inside `ThreadTopAppBar`'s `actions` slot in [`#252`](../codebase/252.md) — the screen owns `overflowExpanded` via `rememberSaveable`)
- Downstream remaining (each layers on top of the post-#246 render loop, not the chrome): [#141](../codebase/141.md) ✅ rename dialog wired (via `ThreadEvent.Rename` from the overflow menu — `onTitleClick` stays a `{}` no-op, a future ticket may bind it as a second entry point), [#229](https://github.com/pyrycode/pyrycode-mobile/issues/229) Effort + YOLO sections append into the existing `StatusSheetContent` `Column` (may revisit the [#254](../codebase/254.md) auto-close-on-select decision for the multi-toggle UX), [#230](https://github.com/pyrycode/pyrycode-mobile/issues/230) Context window section appends as the third section (if a `Section` shape genuinely emerges, factor then), [#208](https://github.com/pyrycode/pyrycode-mobile/issues/208) overflow "Change workspace…" (reuses `workspacePickerVisible` + the two picker handlers introduced by #137; fills the `ThreadEvent.ChangeWorkspace` branch of `onOverflowEvent`), and generic scroll-to-bottom on every message append (still not ticketed — distinct from [`#185`](../codebase/185.md)'s streaming-only auto-anchor; #185 re-pins only while `isStreaming = true` and only against item 0's measured-size growth, not against new-`MessageItem` arrivals). [#136](../codebase/136.md) above-delimiter opacity landed `0.55f` alpha over rows above the most-recent `SessionBoundary`; [#138](../codebase/138.md) landed the centered empty-thread prompt that replaces the `LazyColumn` whenever `!state.hasMessages`; [#145](../codebase/145.md) landed the `Model · effort · NN% used ▴` status row above the input bar; [`#251`](../codebase/251.md) landed the sealed `ThreadEvent` + `ThreadViewModel.onOverflowEvent` dispatcher + stateless [`ThreadOverflowMenu`](thread-overflow-menu.md) composable (exported but not mounted; `Archive` is the only branch wired today); [`#252`](../codebase/252.md) mounted the overflow menu inside `ThreadTopAppBar`'s `actions` slot (the screen owns `overflowExpanded` via `rememberSaveable`, the renamed `onOverflowEvent` parameter binds `vm::onOverflowEvent` at the destination); [`#254`](../codebase/254.md) landed the [`StatusSheet`](status-sheet.md) shell with the Model section (the row's `onExpandClick` is wired internally, the screen owns `sheetVisible` via `rememberSaveable`, and `MainActivity` binds `onModelSelected = vm::onModelSelected`).
- Figma: [`16:8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8) (the populated Conversation Thread Screen canvas; this slice ships the outer shell + the TopAppBar chrome + the composer in `bottomBar` + the connection banner slot between them + the empty-discussion workspace chip between banner and list + the picker host at screen root + the populated message list with bubble/delimiter dispatch from #246 + the above-delimiter dim from #136 + the centered empty-thread prompt from #138 in the no-`MessageItem` branch + the status row above the input bar from #145 + the Status Sheet host at screen root from #254), subframe [`16:58`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-58) (the status row specifically), subframe [`20:100`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=20-100) (the Status Sheet; this slice ships the Model section region only)
