# ThreadOverflowMenu — ViewModel dispatcher

Split out of [ThreadOverflowMenu](thread-overflow-menu.md) on 2026-09-05 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [ThreadOverflowMenu](thread-overflow-menu.md); see that document for what it does, its edge cases and its links.

## ViewModel dispatcher

`ThreadViewModel.onOverflowEvent` is a `when` dispatcher over the sealed surface. Three side effects route through `launchGuardedRepoCall { repository.<verb>(state.value.conversationId, …) }` (`DeleteConfirm` since [#227](../codebase/227.md), `RenameSubmit`, `SaveAsChannelSubmit`; guarded since [#490](../codebase/490.md) — see [Guarded repo launch](guarded-repo-launch.md); was a bare `viewModelScope.launch`); since [#227](../codebase/227.md) `DeleteConfirm`'s launch also `send(ThreadNavigation.PopBack)` after the mutation, **kept inside the guarded block** so a throwing mutation skips the pop-back. `NewSession` (since [#540](../codebase/540.md)) and `Archive` (since [#556](../codebase/556.md), moved **out of** the guarded group) are each a dedicated side effect deliberately **not** through `launchGuardedRepoCall` — `sendNewSession()` and `sendArchive()` (see below) each surface a failure as a snackbar instead of swallowing it, because `launchGuardedRepoCall`'s inert swallow (#490) is the opposite of their contract. `Archive` was guarded through #227–#549; [#556](../codebase/556.md) gave it its own path because, unlike the other three guarded verbs, an archive failure must not strand the user with zero feedback (the ticket's "no crash, no silent no-op" AC). Eight visibility-flag flips support four transient-surface flows (`Rename` / `RenameDismiss`, `SaveAsChannel` / `SaveAsChannelDismiss`, `ChannelInfo` / `ChannelInfoDismiss` since [#226](../codebase/226.md), and `Delete` / `DeleteDismiss` since [#227](../codebase/227.md)); a ninth opens the [`WorkspacePicker`](workspace-picker.md) host (`ChangeWorkspace -> pendingWorkspacePicker.value = true` since [#208](../codebase/208.md), reusing the [#137](../codebase/137.md) flag the empty-thread chip already opens). No case remains a no-op `Unit` arm as of [#540](../codebase/540.md).

```kotlin
fun onOverflowEvent(event: ThreadEvent) {
    when (event) {
        ThreadEvent.Archive -> {                                           // close-sheet since #227
            pendingChannelInfo.value = false
            sendArchive()  // #556 — own surfacing path, not the guard; PopBack lives inside its success continuation
        }
        ThreadEvent.Delete -> pendingDeleteConfirm.value = true            // added in #227 — open dialog, no repo call
        ThreadEvent.DeleteConfirm -> {                                     // added in #227
            pendingDeleteConfirm.value = false
            pendingChannelInfo.value = false
            launchGuardedRepoCall {  // guarded #490
                repository.delete(state.value.conversationId)
                navigationChannel.send(ThreadNavigation.PopBack)
            }
        }
        ThreadEvent.DeleteDismiss -> pendingDeleteConfirm.value = false    // added in #227 — close dialog only
        ThreadEvent.Rename -> pendingRenameDialog.value = true             // added in #141
        is ThreadEvent.RenameSubmit -> {                                   // added in #141
            pendingRenameDialog.value = false
            launchGuardedRepoCall {  // guarded #490
                repository.rename(state.value.conversationId, event.name)
            }
        }
        ThreadEvent.RenameDismiss -> pendingRenameDialog.value = false     // added in #141
        ThreadEvent.SaveAsChannel ->                                       // added in #142
            pendingSaveAsChannelDialog.value =
                SaveAsChannelDialogState(initialName = AUTO_SUGGESTED_CHANNEL_NAME)
        is ThreadEvent.SaveAsChannelSubmit -> {                            // added in #142
            pendingSaveAsChannelDialog.value = null
            launchGuardedRepoCall {  // guarded #490
                repository.promote(
                    state.value.conversationId,
                    event.name,
                    resolveWorkspace(event.name, event.workspace),
                )
            }
        }
        ThreadEvent.SaveAsChannelDismiss ->                                // added in #142
            pendingSaveAsChannelDialog.value = null
        ThreadEvent.ChannelInfo -> pendingChannelInfo.value = true         // added in #226
        ThreadEvent.ChannelInfoDismiss -> pendingChannelInfo.value = false // added in #226
        ThreadEvent.ChangeWorkspace -> pendingWorkspacePicker.value = true // added in #208 — opens the #137 picker
        ThreadEvent.NewSession -> sendNewSession()                        // added in #540 — see below, not guarded
        ThreadEvent.EditChannel -> openChannelEditor()                    // added in #1561 — see below
        is ThreadEvent.ChannelEditSubmit ->                               // added in #1561
            channelEditor.submit(event.name, event.systemPrompt, event.muted)
        ThreadEvent.ChannelEditArchive -> channelEditor.archive()         // added in #1561
        ThreadEvent.ChannelEditDismiss -> channelEditor.dismiss()         // added in #1561
    }
}
```

### EditChannel — `ChannelEditorController` (#1561)

A channel's Rename slot became Edit (Figma `675:5883`): the four events above delegate straight to a
`ChannelEditorController` instance (`ui/conversations/list/ChannelEditorController.kt`) the VM
constructs in its own `init`, exactly the class [`ChannelListViewModel`](channel-list-viewmodel.md#channeleditorcontroller-667--1561)
built for its Channels row pencil (#667) and now shares rather than copies:

```kotlin
private val channelEditor =
    ChannelEditorController(
        scope = viewModelScope,
        isHostLive = { hostAvailable.value },
        repositoryFor = { repository },
        awaitRepository = { hostAvailable.first { it }; repository },
        onArchived = { leaveForList() },
    )
```

Three differences from the list's instance, all a consequence of the thread already holding one fixed
repository and one fixed host rather than resolving either per press: `isHostLive` and `awaitRepository`
read this VM's existing `hostAvailable: StateFlow<Boolean>` instead of a host snapshot lookup;
`repositoryFor` always returns the same `repository`, never re-resolved by server id; and `onArchived`
calls `leaveForList()` — the same navigation `sendArchive()` (this doc, above) drives on a successful
`Archive` — so a confirmed archive from the modal leaves the thread exactly as the menu's own Archive
does, through one shared exit rather than two. `state` folds `channelEditor.state` and `hostAvailable`
into `ThreadUiState.channelEditor` / `.hostAvailable` through one more `combine` stage chained after the
existing `mcpStatusReading` fold (see [`ThreadUiState`](thread-screen-how-it-works-state.md)) — not inside
the five-arity ceiling `TransientDialogs` already sits at, since this fold is chained rather than joining
that group.

`EditChannel`'s own handler, `openChannelEditor()`, is not a one-line delegation like the other three —
it has its own async unknown-channel check, since the thread (unlike the list) does not keep a live
snapshot of every channel's name and mute flag on hand for a synchronous lookup:

```kotlin
private fun openChannelEditor() {
    viewModelScope.launch {
        val channel = conversations.first().firstOrNull { it.id == conversationId && it.isPromoted }
        if (channel == null) {
            RelayLog.d { "event=channel_editor_open_rejected code=unknown_channel" }
            return@launch
        }
        channelEditor.open(HostConversationTarget(serverId, conversationId), channel.name, channel.muted)
    }
}
```

`conversations` is this VM's own existing conversation-list flow (already collected elsewhere in the
class); `firstOrNull { it.id == conversationId && it.isPromoted }` is the guard that keeps `EditChannel` a
no-op on a discussion — reachable only if a caller dispatches the event directly, since
[`ThreadOverflowMenu`](thread-overflow-menu.md) itself only ever emits it when `isPromoted` is already
true. A channel's new name reaches the thread title the same way a rename always has — through
`conversations` re-emitting off the repository's own stream — so this method patches nothing locally.

**No disconnect-sweep parity with the list's #1336 rule.** `ChannelListViewModel`'s `init` block calls
`channelEditor.closeUnless { it.serverId in live }` on every snapshot so a host that drops closes its own
open editor; `ThreadViewModel` does not call the equivalent here, so the thread's Edit channel modal stays
open across a host disconnect and relies on `hostAvailable` to disable its OK and Archive, the same gate
the list's other, non-#1336-swept modals use. This was a considered, not accidental, omission — see
[Open Questions](../../specs/architecture/1561-thread-edit-channel.md) in the ticket's plan — revisit only
if a later ticket asks for it.

- **`Archive` was the only side effect through [#252](../codebase/252.md); [#141](../codebase/141.md) added `RenameSubmit`; [#142](../codebase/142.md) added `SaveAsChannelSubmit`; [#227](../codebase/227.md) added `DeleteConfirm`.** Through [#549](../codebase/549.md) all four read the conversation id from `state.value` (one-grep convention with `sendMessage`) and launched via `launchGuardedRepoCall` on `viewModelScope` (guarded since [#490](../codebase/490.md)); **[#556](../codebase/556.md) moved `Archive` out of this group** onto its own `sendArchive()` (see below) — the mutation-before-`send(PopBack)` ordering below still describes `DeleteConfirm` (and described `Archive` pre-#556). `RenameSubmit` / `SaveAsChannelSubmit` carry no follow-on side effect; **`DeleteConfirm` does** — since [#227](../codebase/227.md) it first closes the sheet (`pendingChannelInfo.value = false`), then inside the launch runs the suspend repo call **before** `navigationChannel.send(ThreadNavigation.PopBack)`. That mutation-before-`send` ordering is load-bearing: once `MainActivity` consumes `PopBack` and calls `popBackStack()`, the VM's `viewModelScope` is cancelled, but the mutation has already returned (same ordering `ChannelListViewModel.CreateDiscussionTapped` relies on; full rationale in [the per-ticket notes](../codebase/227.md#lessons-learned)). `Archive`'s own `sendArchive()` ([#556](../codebase/556.md)) preserves the same mutation-before-`PopBack` ordering, just inside its own coroutine rather than the guarded block — see the dispatcher code block above and the `Archive` bullet in the surfacing note below. **`ChangeWorkspace` left the no-op group in [#208](../codebase/208.md)** — it flips the existing `pendingWorkspacePicker: MutableStateFlow<Boolean>` (the [`WorkspacePicker`](workspace-picker.md) host-visibility flag introduced by [#137](../codebase/137.md)) to `true`, opening the same picker the empty-thread chip opens; the picked / dismissed handlers (`onWorkspacePicked` / `onWorkspacePickerDismissed`) are reused as-is — no parallel flow, no new field, no new render call (see [the per-ticket notes](../codebase/208.md)). **`NewSession` left the no-op group last, in [#540](../codebase/540.md)** — a private `sendNewSession()` launches `repository.startNewSession(conversationId)` (the VM's own id, `workspace` defaulted null, return discarded) inside a two-catch try: `CancellationException` rethrown first (JVM: it extends `IllegalStateException`, so ordering is load-bearing), then `IllegalStateException` (not-connected) sends once on a new one-shot `newSessionErrorChannel` → `newSessionErrors: Flow<Unit>`, collected by [`ThreadScreen`](thread-screen.md) as a second snackbar (the `modalSendErrors` idiom cloned, fixed local string `new_session_failed`, never the exception message). Success is passive — no repo reply exists to await; the [#336](../codebase/336.md) fold renders the session-boundary delimiter when `session_transition` later arrives. **`ChannelInfo` left the no-op group in [#226](../codebase/226.md)** — it flips a private `pendingChannelInfo: MutableStateFlow<Boolean>` (the [`ChannelInfoSheet`](channel-info-sheet.md) host-visibility flag), with `ChannelInfoDismiss` flipping it back. **`Delete` / `DeleteDismiss` ([#227](../codebase/227.md))** flip a fourth `pendingDeleteConfirm: MutableStateFlow<Boolean>` (the delete-confirmation `AlertDialog` flag); `Delete` opens it with **no repo call** (AC #2), `DeleteDismiss` closes it only (AC #3). All four transient flags route through the same `transientDialogs` pre-combiner (widened 3→4 args in #227, keeping the outer `state` `combine` at five-arity).
- **One-shot pop-back via `Channel` + `receiveAsFlow`** ([#227](../codebase/227.md)). The VM owns `private val navigationChannel = Channel<ThreadNavigation>(capacity = Channel.BUFFERED)` exposed as `val navigationEvents: Flow<ThreadNavigation> = navigationChannel.receiveAsFlow()`; `Archive` / `DeleteConfirm` `send(PopBack)` after mutating, and `MainActivity` collects it via `LaunchedEffect(vm)` to call `navController.popBackStack()`. A `Channel` (not a `StateFlow<Boolean>`) because it delivers each element once and never replays — on rotation the consumed `PopBack` is gone, so the thread doesn't pop a second time (AC #5). Copies the `ChannelListViewModel` nav pattern verbatim; the collection-site rule (NavHost concern → collect in `MainActivity`, in-screen effect → collect in-screen) is documented in [`ThreadScreen`](thread-screen-how-it-works-sheets.md#channelinfosheet-archivedelete--pop-back-nav-post-227).
- **The save-as-channel flow is split across three cases** ([#142](../codebase/142.md)) mirroring the rename family's three-case shape. `SaveAsChannel` (menu-tap trigger; #204) sets `pendingSaveAsChannelDialog.value = SaveAsChannelDialogState(initialName = AUTO_SUGGESTED_CHANNEL_NAME)` — the nullable sub-state carrier bundles the seeded `"New channel"` pre-fill with the visibility flag (visible iff non-null) per the [#78](../codebase/78.md) `PendingPromotion` shape, contrasting with `pendingRenameDialog: MutableStateFlow<Boolean>` (which doesn't need a separate seed; the rename dialog reads `initialName` from `state.displayName`). `SaveAsChannelSubmit(name, workspace)` flips the flag back to `null` **synchronously before** launching `repository.promote(state.value.conversationId, event.name, resolveWorkspace(event.name, event.workspace))` — `resolveWorkspace` is a file-scope `private` helper that returns `"pyry-workspace/channels/${name.toChannelSlug()}"` for `DEDICATED` and `null` for `SCRATCH` (preserve the discussion's existing cwd per the [`ConversationRepository.promote(... workspace = null)`](conversation-repository.md) contract). `SaveAsChannelDismiss` flips the flag to `null` only. `AUTO_SUGGESTED_CHANNEL_NAME = "New channel"` is a file-scope constant; Phase 4 swaps it for a generator reading the first user message off `state.value.items`.
- **The rename flow is split across three cases.** `Rename` (menu-tap trigger) flips `pendingRenameDialog.value = true`, which surfaces to `ThreadUiState.showRenameDialog` via the main `combine(...)` block. `RenameSubmit(name)` (dialog Save tap) flips the flag back to `false` **synchronously before** launching `repository.rename(...)` — the synchronous ordering matters: it dismisses the dialog immediately so the UI doesn't wait for the suspend to complete, and a re-tap of `Rename` during an in-flight rename has well-defined state. `RenameDismiss` (dialog Cancel / outside-tap / back-press) flips the flag to `false` only.
- **Conversation id is read from `state.value.conversationId`, not the constructor-captured `private val conversationId`.** Both resolve to the same value (the data-class default mirrors the constructor field; no mutation path overwrites it), but `state.value.conversationId` matches the existing `sendMessage` read pattern for one-grep convention. Applies to `Archive`, `RenameSubmit`, and `SaveAsChannelSubmit`.
- **`pendingRenameDialog: MutableStateFlow<Boolean>` is a private VM field**, mirroring `pendingWorkspacePicker` from [#137](../codebase/137.md). [#142](../codebase/142.md) adds a sibling `pendingSaveAsChannelDialog: MutableStateFlow<SaveAsChannelDialogState?>` (nullable sub-state shape per [#78](../codebase/78.md)). **The outer `combine(...)` stays at five-arity** — #142 cashed in #141's arity-5 ceiling warning by introducing a private `data class TransientDialogs(val renameVisible: Boolean, val saveAsChannel: SaveAsChannelDialogState?)` plus a pre-combiner `transientDialogs: Flow<TransientDialogs>` that folds both transient flags into one source. The main combine's `pendingRenameDialog` arm became `transientDialogs`, the lambda destructures `dialogs.renameVisible` and `dialogs.saveAsChannel`, and the five-arity envelope is preserved. [#226](../codebase/226.md) folded a **third** transient flag into the same group — `pendingChannelInfo: MutableStateFlow<Boolean>` for the [`ChannelInfoSheet`](channel-info-sheet.md) host — widening the inner `transientDialogs` `combine` 2→3 args and adding `channelInfoOpen: Boolean` to `TransientDialogs`; the outer combine stays at five. The `TransientDialogs` group is now the standard home for any new thread-screen transient-surface flag while the outer combine is at its ceiling. **The VM-owned vs. screen-hoisted choice matters**: dialog visibility belongs on the VM (and on `ThreadUiState`) because the dialog represents in-progress operation state and has multiple potential trigger points (today's overflow item, tomorrow's TopAppBar tap-to-rename) — centralising on the VM keeps the dialog single-sourced. Contrast against `overflowExpanded` ([#252](../codebase/252.md)) and `sheetVisible` ([#254](../codebase/254.md)), which are screen-hoisted `var ... by rememberSaveable { mutableStateOf(false) }` because they're pure UI presentation with no business meaning the VM needs to react to.
- **No optimistic UI update; crash-guarded since #490 for the three remaining guarded verbs, no error *surface* for them.** `delete(id)` ([#227](../codebase/227.md)), `rename(id, name)`, and `promote(id, name, workspace)` can throw `IllegalArgumentException` / `IllegalStateException` for programmer-error cases per the [`ConversationRepository`](conversation-repository.md) contract *and* — under the relay repository — for relay failures (`RelayErrorException` from a server `error` frame, `IllegalStateException` not-connected). `UnsupportedOperationException` is now historical only — `rename` ([#530](../codebase/530.md)), `archive`/`unarchive` ([#549](../codebase/549.md)), `delete` ([#532](../codebase/532.md)), and `changeWorkspace` ([#560](../codebase/560.md), the former last un-wired mutation) are all wired live, still gated dormant behind `mutationsSupported`. The programmer-error branch stays unreachable in production (the id came from our own state, the name is non-blank by the dialog's gate, the conversation is non-promoted by construction — `Save as channel…` is gated on `!isPromoted` per [#204](../codebase/204.md)); the **relay** branches are reachable, so since [#490](../codebase/490.md) each of these three launches routes through [`launchGuardedRepoCall`](guarded-repo-launch.md), which inertly swallows those three types (**fail-quietly**, no user-facing surface) while deliberately leaving `IllegalArgumentException` uncaught (fail-fast). **`archive(id)` is the one exception to this fail-quiet posture** ([#556](../codebase/556.md)): its private `sendArchive()` catches `RelayErrorException` **and** `IllegalStateException` (two types, not the guard's three — `UnsupportedOperationException` cannot originate for archive, both impls implement it concretely) and, instead of swallowing, `trySend(Unit)`s on a new one-shot `archiveErrors: Flow<Unit>` (the `newSessionErrors` idiom cloned, #540), collected by [`ThreadScreen`](thread-screen.md) as a third snackbar with the fixed local string `archive_failed` — **never** the caught exception's message (the caught `RelayErrorException.message` is server-supplied and must not reach the un-secured Activity window the snackbar draws in). `CancellationException` is rethrown **first** (it extends `IllegalStateException` on the JVM) so `viewModelScope` teardown mid-archive is never mis-surfaced. `navigationChannel.send(ThreadNavigation.PopBack)` sits strictly inside the success continuation — a failure emits on `archiveErrors` and stays on the thread; `IllegalArgumentException` (`conversation.not_found`) and the #318 decode exception stay uncaught, same unreachable/fail-loud posture as the guard. **`changeWorkspace(id, path)` (via `onWorkspacePicked`, not this menu's own `ThreadEvent` dispatch — see [`WorkspaceChip`](workspace-chip.md)) is the third exception to the fail-quiet posture** ([#561](../codebase/561.md)): its private `sendChangeWorkspace(path)` clones `sendArchive`'s two-catch set (`RelayErrorException` **and** `IllegalStateException`, request/reply reachable) onto a new one-shot `changeWorkspaceErrors: Flow<Unit>`, collected by [`ThreadScreen`](thread-screen.md) as a fourth snackbar (`change_workspace_failed`) — but diverges from `sendArchive` on **success**: no `PopBack`, since the workspace chip re-labels itself list-driven off the [#560](../codebase/560.md) confirmed upsert and the user stays on the thread either way. **One subtlety for `delete`:** the Phase-0 production-bound `FakeConversationRepository` has always **overridden** the *interface's* throwing default with a tolerant no-op-on-unknown removal (see [the per-ticket Lessons learned](../codebase/227.md#lessons-learned)); the live `RemoteConversationRepository` now matches that tolerance too — `delete` on `conversation.not_found` **converges as success** rather than propagating `IllegalArgumentException` like `rename`/`archive` do ([#532](../codebase/532.md), the deliberate divergence from the family's IAE-crash-on-not-found posture). The existing `state` flow re-emits naturally when the repository mutates the underlying `observeConversations` set; no manual `_state.value = ...` write needed.
