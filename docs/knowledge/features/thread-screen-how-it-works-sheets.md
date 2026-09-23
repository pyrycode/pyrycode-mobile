# Thread screen — how it works, the sheets

Split out of [Thread screen](thread-screen.md) on 2026-09-05 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [Thread screen](thread-screen.md); see that document for what it does, its edge cases and its links.

### Status Sheet hosting (post-#254)

At the top of the `ThreadScreen` body (before the `Scaffold`), the screen hoists a `rememberSaveable` visibility flag:

```kotlin
var sheetVisible by rememberSaveable { mutableStateOf(false) }
```

`rememberSaveable` (over bare `remember`) survives configuration changes (rotation) at zero VM-surface cost — same idiom as [`WorkspacePicker`](workspace-picker.md). The flag is **UI-local presentation state** with no business meaning the VM needs to react to; pushing it onto `ThreadUiState` would dirty the VM contract with screen-presentation concerns. The status row's `onExpandClick = { sheetVisible = true }` is wired internally inside the `bottomBar` block (above).

After the existing [`WorkspacePicker`](workspace-picker.md) sibling at screen root, the [`StatusSheet`](status-sheet.md) renders as a second `Scaffold` sibling, gated on `sheetVisible`. **[#807](../codebase/807.md) re-sourced every argument below off the daemon's own readings** — the shape shown is the current one:

```kotlin
if (sheetVisible) {
    StatusSheet(
        choices = state.runConfig.choices,
        menuAvailable = state.runConfig.menuAvailable,
        notListedModels = state.runConfig.droppedModels + state.runConfig.hiddenChoices,
        selectedModel = state.runConfig.selectedModel,
        onModelSelected = { value ->
            onModelSelected(value)
            sheetVisible = false              // auto-close on Model pick
        },
        effortChoices = state.runConfig.effortChoices,
        selectedEffort = state.runConfig.selectedEffort,
        onEffortSelected = { level ->
            onEffortSelected(level)
            sheetVisible = false              // auto-close on Effort pick
        },
        pending = state.runConfig.pending,
        enabled = state.runConfig.writable,   // "" sessionId ⇒ read-only, not a failure
        yoloEnabled = state.yoloEnabled,
        onYoloToggled = onYoloToggled,        // passthrough; NO auto-close on toggle
        onDismiss = { sheetVisible = false },
    )
}
```

Through [#807](../codebase/807.md) this call passed `selectedModel: Model` / `selectedEffort: Effort` sourced from `AppPreferences.defaultModel` / `defaultEffort` with an in-memory per-conversation override. #807 deleted that sourcing outright: every argument above now reads off `state.runConfig` (a [`ThreadRunConfig`](thread-composer-footer.md#sourcing), itself folded from `ConversationRepository.observeSessionSettings` + `observeModelMenu`), `choices` / `effortChoices` are the daemon's own published rows rather than the `Model` / `Effort` enums, and `enabled` is new — an empty `SessionSettings.sessionId` means the daemon has no session to address, so the sheet goes read-only rather than sending a write the server would refuse. See [status-sheet.md](status-sheet.md) for the sheet-side signature and rendering rules.

Three design points pinned in #254 + one widened in #229 (still true post-#807 — only the argument sourcing changed):

1. **Gated `if (sheetVisible) { StatusSheet(...) }`, not `AnimatedVisibility`.** `ModalBottomSheet` runs its own enter/exit animation; wrapping in `AnimatedVisibility` would double-animate the sheet. The `if` creates the composable on first show and destroys it on dismiss — what `ModalBottomSheet` expects. Same shape as the [`WorkspacePicker`](workspace-picker.md) sibling.
2. **Sibling-to-Scaffold placement.** `ModalBottomSheet` lives in its own window, so source-order placement doesn't affect Z-order. Placing the sheet inside the `Scaffold`'s content slot would have worked but would mix the sheet's window-managed lifecycle with the body's layout-managed siblings.
3. **Asymmetric auto-close (resolution of the [#254](../codebase/254.md) open question, decided in [#229](../codebase/229.md)).** Single-pick sections (Model radio, Effort chip) wrap in `{ value -> upstream(value); sheetVisible = false }` — a discrete pick is a complete action, M3 modal-bottom-sheet convention. Toggle sections (YOLO Switch) pass the upstream callback straight through with no auto-close — a Switch is a state-change the user may want to immediately reverse; closing the sheet would force a re-open just to undo. Apply the same rule to [#230](https://github.com/pyrycode/pyrycode-mobile/issues/230)'s Context window section based on whether it's a picker or a toggle.
4. **`ModalBottomSheet`'s hide animation runs on a coroutine the framework owns** — no `sheetState.hide()` call needed before flipping `sheetVisible = false`.

The screen-side `onModelSelected: (String) -> Unit = {}` and `onEffortSelected: (String) -> Unit = {}` (retyped off `Model` / `Effort` by [#807](../codebase/807.md) — the argument is a published `ModelMenuRow.value` / effort-level string, forwarded verbatim and never parsed) plus `onYoloToggled: (Boolean) -> Unit = {}` all default to `{}` so the existing `@Preview` composables keep compiling unchanged; `MainActivity` binds all three at the `CONVERSATION_THREAD` destination:

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
