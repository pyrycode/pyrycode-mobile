# Thread screen

Outer shell for the `conversation_thread/{serverId}/{conversationId}` route. `ThreadScreen` owns the frame, message region and composer placement; its linked topics below cover state, rows, overlays, sheets and tests. The current [Figma thread frame](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8) governs the static dark layout. See [shared typography](shared-typography.md) for text roles.

## Map

Split on 2026-09-05 to keep this document under the 50000-byte cap the docs guard enforces. Each section named below moved verbatim, heading and anchors intact, into its own document:

- [Thread screen — shape](thread-screen-shape.md) — `Shape`
- [Thread screen — how it works, ViewModel state](thread-screen-how-it-works-state.md) — `SavedStateHandle.get<String>("conversationId").orEmpty() (lifted to a private val)`, `combine(observeConversations, observeMessages, pendingWorkspacePicker).stateIn(WhileSubscribed) — three upstreams since #137`, `observeConversations(All).map { firstOrNull } — option (c) for displayName derivation`, `stateIn(viewModelScope, WhileSubscribed(5_000), initialValue = ThreadUiState(id, id))`, `private fun Conversation.displayName() — re-declared, not extracted`, `Display-name fallback chain`, `Free rename re-emission`, `items and queuedMessages stay two ThreadUiState fields — the join with the backlog is render-time, not VM-time (#782)`
- [Thread screen — how it works, the list, the chip, the empty state and the status row](thread-screen-how-it-works-list-and-status-row.md) — `LazyColumn(reverseLayout = true) — established in #126, populated in #246, dimmed in #136, nested in a Column since #201, rows folded with the queued backlog since #782`, `Workspace-chip wiring (post-#137)`, `Empty-state branch (post-#138)`, `Status-row wiring (post-#145)`
- [Thread screen — how it works, the sheets](thread-screen-how-it-works-sheets.md) — `Status Sheet hosting (post-#254)`, `ChannelInfoSheet hosting (post-#226)`, `ChannelInfoSheet Archive/Delete + pop-back nav (post-#227)`
- [Thread screen — how it works, overlays, retry and the app bar](thread-screen-how-it-works-overlays-and-app-bar.md) — `Connection status placement`, `Thinking-indicator placement (post-#407)`, `Thread top overlay placement (post-#1002)`, `Interrupt-affordance placement (post-#459)`, `Stall-promotion-banner placement (post-#396, retired from the screen in #883)`, `Permission-modal overlay placement (post-#446)`, `fun retry() — non-suspend, VM owns the launch`, `connectionState: StateFlow<ConnectionState> — same lifetime as state`, `ThreadTopAppBar — Figma 16:8 chrome`, `Modifier ordering inside the body`
- [Thread screen — testing](thread-screen-testing.md) — `Testing`
- [Thread screen — previews and edge cases](thread-screen-previews-and-edge-cases.md) — `Previews`, `Edge cases / limitations`
- [Thread screen — composer drafts and attachments](thread-screen-composer-drafts-and-attachments.md) — split out 2026-09-24: `Composer draft ownership`, `Composer pending attachments`

The sections that stay here: `## What it does`, `Queued rows expose an independent **Send now** action before drop only when the current
session's fresh capability report explicitly enables mid-turn input (#1642). Settings replacement,
session change and owning-host disconnect invalidate stale support. A tap uses the destination's
repository without confirmation or optimistic row movement; failure uses drop's existing inert
treatment. Backlog removal does not establish delivered position: the later user-message push does,
and a late tap can open the next turn. See [queue control and ordering](queued-backlog.md#sending-a-queued-entry-now-1642)
and [row geometry/accessibility](queued-backlog-section.md#styling).

## Wiring` (minus the two subsections above), `## Configuration`, `## Related`.

## What it does

Opening or reopening a thread shows the current text of a streaming row created
before opening immediately, even if it has never received `turn_end`. Appended
text reveals progressively, and a reply first arriving after opening starts with
no visible text. `ThreadScreen` remembers the phone-clock opening time per
conversation and passes it to [MessageBubble](message-bubble.md#streaming-variant--progressive-reveal--blinking-caret-since-184).
A fresh thread composition captures a new time, so arrived text does not replay.

The thread follows [Figma `16:8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8).
Its message list fills the screen area below the system status bar and above the IME,
scrolling behind the full-width translucent, progressively blurred header and composer.
The frame glow and scrim remain behind the thread; foreground controls stay sharp.
Rows own their 20dp gutters, without a second list inset.

The header's visible content row starts 24dp below the screen-area top, with a 28dp
row, 16dp rule gap and 1dp rule: 69dp total, retaining 48dp Back and overflow targets.
At the oldest end, measured header height plus 28dp places the oldest row below the
rule; short threads keep their top alignment. Top-overlay pills share that 28dp
clearance and the 20dp right gutter. The composer owns 16dp top/bottom and 20dp side
padding. Its measured height follows attachments and multiline drafts, excluding
IME padding. At the newest end an ordinary message surface rests 12dp above the
status band. Other row types keep their own internal spacing, owned by
[#1630](https://github.com/pyrycode/pyrycode-mobile/issues/1630); whichever ticket
integrates second must recheck the combined ordinary-row gap.

IME opening, dismissal and reopening resize the drawing viewport and lift the
composer. The remembered reverse list retains its follow-newest and history-reader
rules. See [list reservations](thread-screen-how-it-works-list-and-status-row.md),
[chrome and overlays](thread-screen-how-it-works-overlays-and-app-bar.md#threadtopappbar--figma-168-chrome)
and [hardware evidence](thread-screen-testing.md#testing).

`ThreadScreen` uses a `Scaffold` with `ThreadTopAppBar`, a message-region `Box` containing either `EmptyThreadState` or the remembered reverse-layout `LazyColumn`, and a composer column in `bottomBar`. Connecting and Reconnecting appear in the composer status band; Offline Retry and pairing Re-pair appear in `ThreadTopOverlay`, pinned over the message region without reflowing the list. The list folds delivered and queued rows, draws every row at full opacity (session boundaries included, since #1578), and leaves each row's rendering to its own component. The footer, attachments and input keep their existing behavior and visual ownership. The header menu uses the shared Actions overlay below the live button (#1666), hosted over the Scaffold with IME-constrained scrolling and header-priority Back; see [overlay wiring](thread-screen-how-it-works-overlays-and-app-bar.md#header-actions-overlay-1666).

Back, title and overflow stay reachable through their callbacks. The overflow opens `ThreadOverflowMenu` beneath its control; screen-owned state hosts the rename, save-as-channel, status and other modal flows described in the linked topics. Workspace picker state remains in the screen contract for older flows, but the current thread frame and overflow expose no workspace chip, label, picker trigger or workspace-edit action.

## How it works

Split on 2026-09-05 into four documents, listed in § Map above: [ViewModel state](thread-screen-how-it-works-state.md), [the list, the chip, the empty state and the status row](thread-screen-how-it-works-list-and-status-row.md), [the sheets](thread-screen-how-it-works-sheets.md), and [overlays, retry and the app bar](thread-screen-how-it-works-overlays-and-app-bar.md). Every `###` subsection kept its heading and anchor.

The current session's `state.runConfig.memorySearch` feeds [Channel info](channel-info-sheet.md) through `toChannelInfoUiModel`, each [session boundary](session-boundary-delimiter.md), and [channel overflow](thread-overflow-menu.md) through `ThreadTopAppBar`. The same report drives all three surfaces after a replacement report or conversation change: Channel info shows installed, disabled and unknown states, while the boundary and channel menu offer Install only for confirmed absence. An omitted report is unknown, not absent. Memory search retrieves stored knowledge. It does not capture the conversation or give the agent access to all messages above a session boundary.

Connection readings from the [Connecting](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=627-1740) and [Reconnecting](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=627-4657) dark frames share the composer band. [Offline](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=627-4910) uses an overlay Retry pill; rejected pairing shows Re-pair there instead.

The overlay's exact-host Retry has live coverage in
`InteractiveStreamE2ETest.interactiveTurn_offlineRetry_reconnectsSameHostAndReplies`
([#1286](https://github.com/pyrycode/pyrycode-mobile/issues/1286)). A harness-owned
daemon failure produces the actual Offline pill while the thread stays open;
restarting the same host and tapping Retry clears it and permits a new rendered
real-Claude reply without re-pairing. The 2026-09-30 full live XML includes this
passing, unskipped method: 44 executed, 0 failed, 0 skipped. See the
[coverage and evidence boundary](../../e2e-interactive-stream.md#offline-retry-proof)
and [dispatcher evidence](https://github.com/pyrycode/pyrycode-mobile/issues/1286#issuecomment-5914205992).

The five transient readings in the composer's [status band](thread-screen-how-it-works-overlays-and-app-bar.md#thinking-indicator-placement-post-407-moved-in-643) follow the [input status component `533:1957`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-1957), inspected with the [pill variants `347:6618`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=347-6618) and thread `16:8` on 2026-09-29. Thinking has the supplied snowflake glyph; retry, compaction and Reset use a fixed-length rotating arc; outcomes use the shared error pill. Figma specifies no dedicated frame for the latter four readings or their combination with a task pill, so their component treatment is the reference, not a full-screen pixel match.

The running-task pill shares the reading's 24 dp band at normal text scale, or sits at its right end alone. Only this thread caller gives the shared primary-container `NoticePill` a 24 dp minimum height (no minimum width since [#1628](https://github.com/pyrycode/pyrycode-mobile/issues/1628)); its 6 dp corners, `bodySmall` label and 8/4 dp padding follow the inspected Figma task-pill node `568:3162`, whose 104 × 24 dp frame is the hug width of "2 tasks running", not a width floor. The band and pill can grow for larger text. A zero count removes the pill; a live count uses the client-owned singular or plural label. Tapping it opens the same background-task panel as the count-free top menu. The [status-band layout](thread-screen-how-it-works-overlays-and-app-bar.md#thinking-indicator-placement-post-407-moved-in-643) covers its interaction with readings and nearby controls.

The [model-refusal row's switch-back offer](model-refusal-row.md#switch-back-1360) owns its failure
feedback inline. A refused or failed write leaves an enabled retry button and its retry line immediately;
only this caller suppresses the shared run-configuration snackbar, while ordinary model/effort edits
still report there (#1615). Retry clears the inline failure during pending. The visible outline is a
32 dp minimum independent of the 48 dp touch target; wrapped destinations and enlarged text grow it so
the retry line stays below all label lines. Button extensions invoke switch-back without toggling details.

## Wiring

### Koin binding

```kotlin
// di/AppModule.kt
viewModel { get<ThreadDestinationFactory>().thread(get(), get()) }
```

The factory receives the entry's `SavedStateHandle` and the app-scoped
`ComposerDraftStore` ([#789](../codebase/789.md)). It no longer receives
`AppPreferences` — [#807](../codebase/807.md) removed the thread's last read of it
(`defaultModel` / `defaultEffort`), so no thread-destination state reads a device
preference any more; see [thread-composer-footer.md § Sourcing](thread-composer-footer.md#sourcing).
It reads the exact `serverId` and binds a `StableConversationRepository` to that
retained bundle's coordinator stream; `ThreadViewModel` continues to read the
host-local `conversationId`. The bundle also supplies connection state, live
session events, the current permission modal and its answer/cancel callbacks, and
Stop. See [destination dependencies](dependency-injection-host-conversation-source.md#destination-ownership).

Messages, sessions, queue state, Send, Reset session, queue drop, Send now and existing
repository-backed actions stay with that owner. Switching compatibility selection
while a thread or permission prompt is open cannot redirect reads or writes.
Reconnect replaces the concrete repository beneath the same owner facade; an A
outage leaves B usable. During disconnect/handshake, existing empty/default reads,
unavailable actions and error handling remain in effect without using B as a
fallback. Overlay Retry passes the captured bundle to the registry's
[lifecycle-checked exact-host Retry](dependency-injection-host-conversation-source.md#exact-host-retry-and-lifecycle).

With `useRelay = false`, the explicit `demo` destination uses the existing fake
singleton, a connected fake connection source, empty live events, hidden modal
state and inert default control callbacks, even with real hosts saved.

### Destination block

`PyryNavHost` registers `Routes.CONVERSATION_THREAD` with
`Routes.hostArguments()`, reconstructs its `HostConversationTarget`, and calls
`HostDestination` before resolving `koinViewModel<ThreadViewModel>()`. The guard
waits for saved-host initialization; unknown/removed owners return to the list.
Both route arguments survive saved back-stack restoration. Each entry owns its
ViewModel, so equal conversation ids on A and B remain distinct. The temporary
flat-list adapters capture selection only at entry, then consume host-qualified
navigation; see [navigation ownership](navigation.md#how-it-works).

Since [#661](question-batch-modal.md), this same destination block also collects `vm.questionModal` for the
conversation's held [clarification batch](question-batch-modal.md). **Through #1305's plan this was drawn
as a gate-shaped `Dialog` directly beside the `ThreadScreen` call, not a `ThreadScreen` parameter — #1305
superseded that placement.** The batch now renders inline, inside the message stream, so it *is* a
`ThreadScreen` parameter: `questionState = questionModal` and `onQuestionEvent = { event, generation ->
vm.onQuestionEvent(event, generation) }` are passed straight into the `ThreadScreen(...)` call here, and the
process-lifetime picks live in the app-scoped `QuestionDraftStore` rather than this destination's own state
— see [Question batch modal § Placement](question-batch-modal.md#placement-inline-in-threadscreen-since-1305)
and [§ Batch ownership](question-batch-modal.md#batch-ownership-process-lifetime-drafts-source--and-request-bound-sends).

The destination collects the ViewModel's state, connection/live indicators and
permission state with `collectAsStateWithLifecycle()`, and passes callbacks and
error flows into the stateless `ThreadScreen`. `ThreadNavigation.PopBack` remains
collected in `LaunchedEffect(vm)` at the route, where the `NavController` lives.
[#883](../../specs/architecture/883-retire-literal-screen.md) removed the
`literal_screen/{serverId}/{conversationId}` destination this block used to reach
from the overflow menu's "Show the literal screen" item, once the daemon dropped
the server-side screen-snapshot render path — the destination no longer collects
`vm.isStalled` either, since that flag's only consumer was the retired stall
promotion banner (see [Stall state](stall-state.md)).

The guard also wraps the screen in `HostWorkspaceRepository`, providing
`LocalWorkspacePickerRepository` for the route host. This covers the nested
[WorkspacePicker](workspace-picker.md)'s own recents/folder-creation reads and
writes as well as `vm::onWorkspacePicked`. A correct ViewModel binding alone
cannot prevent a descendant's global Koin lookup from consulting another host.
The facade follows reconnect and preserves the picker's existing generic failure
UI and cancellation behavior. Production-route picker tests exercise this boundary;
see [DI testing](dependency-injection.md#testing).

### Composer drafts and attachments

Split out on 2026-09-24 into [Thread screen — composer drafts and attachments](thread-screen-composer-drafts-and-attachments.md): `Composer draft ownership` (the `ComposerDraftStore` text map, its creation/restore/clear/eviction rules) and `Composer pending attachments` (the parallel attachment map, the `AttachmentReader` trust boundary, the [#933](https://github.com/pyrycode/pyrycode-mobile/issues/933) picker and strip, and the [#934](https://github.com/pyrycode/pyrycode-mobile/issues/934) paste path that joins the same sink). Both subsections kept their heading and anchor.

## Configuration

- **Dependencies:** no new entries. `ConversationRepository` was already on classpath; `kotlinx.coroutines.flow.stateIn` rides in via the existing `kotlinx-coroutines-core` (catalog: `libs.coroutines.core`). No `gradle/libs.versions.toml` edits.
- **Strings:** `R.string.cd_back` reused, `R.string.cd_more_actions` added in #139 (`<string name="cd_more_actions">More actions</string>`), `R.string.cd_thread_status_expand` added in #145 for the trailing icon on `ThreadStatusRow`, retired with that row by [#808](../codebase/808.md) and carried onto the [Status sheet opener](thread-composer-footer.md) it replaced it with. `R.string.thread_re_pair` ("Pairing error - Re-pair") added in [#843](https://github.com/pyrycode/pyrycode-mobile/issues/843) for the status area's Re-pair button, moved into the [Top overlay's pairing pill](thread-top-overlay.md#the-pairing-pill) by [#1002](https://github.com/pyrycode/pyrycode-mobile/issues/1002); `R.string.thread_notice_dismiss` ("Dismiss notice") added by #1002 for the usage pill's dismiss X. `R.plurals.thread_task_count` ("%1$d task running" / "%1$d tasks running") added by [#1043](https://github.com/pyrycode/pyrycode-mobile/issues/1043) for the status band's task-count pill — a client-owned plural, never daemon-authored text. Naming follows the project's `cd_*` content-description convention.
- **Route identity survives process recreation.** The back-stack entry restores both `serverId` and `conversationId` into `SavedStateHandle`; the factory rebinds the saved host and `stateIn` re-subscribes on collection. Thread data is fetched again, not persisted as route state.

## Related

- Ticket notes: [`../codebase/126.md`](../codebase/126.md) (skeleton), [`../codebase/139.md`](../codebase/139.md) (TopAppBar promotion), [`../codebase/188.md`](../codebase/188.md) (input bar + `sendMessage`), [`../codebase/201.md`](../codebase/201.md) (ConnectionBanner wiring), [`../codebase/137.md`](../codebase/137.md) (WorkspaceChip + WorkspacePicker hosting + `ThreadUiState` widening to six fields), [`../codebase/246.md`](../codebase/246.md) (LazyColumn body filled with typed `ThreadItem` render loop + seventh `items` field on `ThreadUiState`), [`../codebase/136.md`](../codebase/136.md) (above-delimiter opacity + `itemsIndexed` migration + `mostRecentSessionBoundaryIndex` helper + `previewItemsWithBoundaries`), [`../codebase/138.md`](../codebase/138.md) (empty-state prompt + `if (!hasMessages) … else …` branch around the `LazyColumn`), [`../codebase/145.md`](../codebase/145.md) (ThreadStatusRow stacked above ThreadInputBar in bottomBar; `model`/`effort`/`tokenPercent` on ThreadUiState; the hoisted `onExpandClick` placeholder that #254 later deleted), [`../codebase/251.md`](../codebase/251.md) (sealed `ThreadEvent` + `ThreadViewModel.onOverflowEvent` dispatcher; the [`ThreadOverflowMenu`](thread-overflow-menu.md) composable lands exported but not mounted), [`../codebase/252.md`](../codebase/252.md) (mounts `ThreadOverflowMenu` inside `ThreadTopAppBar`'s `actions` slot, hoists `var overflowExpanded by rememberSaveable { mutableStateOf(false) }`, renames `onOverflowClick` to `onOverflowEvent`, binds `vm::onOverflowEvent` at the `MainActivity` destination), [`../codebase/254.md`](../codebase/254.md) ([`StatusSheet`](status-sheet.md) shell + Model section + screen-hoisted `sheetVisible` + `onModelSelected` parameter swap), [`../codebase/141.md`](../codebase/141.md) (rename dialog wiring — `RenameDialog` composable hosted as a `Scaffold` sibling, `showRenameDialog: Boolean` field on `ThreadUiState`, `pendingRenameDialog: MutableStateFlow<Boolean>` as the fourth `combine` source, two new `ThreadEvent` cases `RenameSubmit(name)` + `RenameDismiss`, three new arms on `onOverflowEvent` that replace the [#252](../codebase/252.md) `Rename` no-op), [`../codebase/185.md`](../codebase/185.md) (streaming auto-scroll — `LazyListState` hoist + `NestedScrollConnection` user-input filter + two `LaunchedEffect`s on item 0's measured size and the bottom-anchor predicate), [`../codebase/226.md`](../codebase/226.md) ([`ChannelInfoSheet`](channel-info-sheet.md) host as a fifth `Scaffold` sibling — `channelInfoOpen` + three ingredient fields on `ThreadUiState`, `pendingChannelInfo` folded into `TransientDialogs`, `ChannelInfoDismiss` event, and the pure `toChannelInfoUiModel` mapper), [`../codebase/227.md`](../codebase/227.md) (the sheet's Archive / Delete wired — `Archive` close-and-pop, `Delete` / `DeleteConfirm` / `DeleteDismiss` + the `DeleteConfirmationDialog` sixth sibling + `deleteConfirmVisible` flag + the `ThreadNavigation` one-shot pop-back channel collected in `MainActivity`), [`../codebase/446.md`](../codebase/446.md) (the permission-modal overlay as the seventh `Scaffold` sibling — the `modalState` / `onModalOption` / `onModalCancel` params + the snackbar host + the `PermissionModalOverlay` / `ModalOptionButton` / `dismissReasonText` private composables + the dialog-window `FLAG_SECURE`), [`../codebase/603.md`](../codebase/603.md) (deleted `tokenPercent` / `tokensUsed` / `tokensTotal` and the `STUB_*` constants #145 introduced, once [#601](../codebase/601.md) and [#602](../codebase/602.md) had both stopped reading them; stripped the matching arg from all five `ThreadScreen.kt` preview literals), [`../codebase/15.md`](../codebase/15.md) (the placeholder route this slice replaces), [#789](https://github.com/pyrycode/pyrycode-mobile/issues/789) (composer text ownership moved from `ThreadInputBar`'s `rememberSaveable` to the app-scoped [`ComposerDraftStore`](#composer-draft-ownership); no visual change), [#790](https://github.com/pyrycode/pyrycode-mobile/issues/790) (host-removal and conversation-deletion draft eviction — see [Composer draft ownership](#composer-draft-ownership); no visual change), [#798](https://github.com/pyrycode/pyrycode-mobile/issues/798) (the same two removals now also clear cached conversation content — see [Conversation cache § Removal on unpair](conversation-cache.md#removal-on-unpair--forgetremovedhost) and [Caching conversation repository § delete](caching-conversation-repository.md#delete--removing-the-cache-alongside-the-daemon-798); no visual change), [#981](https://github.com/pyrycode/pyrycode-mobile/issues/981) (the newest-row pin — a second `LaunchedEffect` beside [`#185`](../codebase/185.md)'s streaming auto-scroll follows any new newest row into view, streaming or not, unless the reader has scrolled away; see [Thread screen — list and status row](thread-screen-how-it-works-list-and-status-row.md); no visual change)
- Specs: `docs/specs/architecture/15-conversation-thread-placeholder-route.md`, `docs/specs/architecture/126-thread-screen-skeleton.md`, `docs/specs/architecture/139-thread-topappbar-back-title-overflow.md`, `docs/specs/architecture/188-thread-input-bar.md`, `docs/specs/architecture/201-thread-screen-wire-connectionbanner.md`, `docs/specs/architecture/137-workspace-chip-empty-new-discussion-thread.md`, `docs/specs/architecture/246-wire-thread-items-into-lazycolumn.md`, `docs/specs/architecture/136-above-delimiter-opacity-treatment.md`, `docs/specs/architecture/138-empty-thread-state-copy-visual.md`, `docs/specs/architecture/145-thread-status-row.md`, `docs/specs/architecture/185-auto-scroll-thread-streaming.md`, `docs/specs/architecture/981-thread-pins-new-newest-row.md`, `docs/specs/architecture/252-thread-overflow-menu-mount-and-wire.md`, `docs/specs/architecture/227-channelinfosheet-archive-delete-actions.md`, `docs/specs/architecture/789-per-conversation-composer-drafts.md`, `docs/specs/architecture/790-drop-drafts-on-host-or-conversation-removal.md`, `docs/specs/architecture/843-thread-re-pair.md` (the composer status area's Re-pair action — see [overlays and app bar § trailing contextual-action slot](thread-screen-how-it-works-overlays-and-app-bar.md#thinking-indicator-placement-post-407-moved-in-643))
- Upstream: [Navigation](navigation.md) (the `conversation_thread/{serverId}/{conversationId}` route this destination consumes), [Conversation repository](conversation-repository.md) (the `observeConversations(All)` + `observeMessages` + `changeWorkspace` surfaces the VM consumes; `sendMessage` it forwards to since #188; `observeMessages` + `changeWorkspace` added since #137), [Connection state](connection-state.md) (the `ConnectionStateSource.observe()` / `retry()` contract the VM consumes since #201), [Dependency injection](dependency-injection.md) (the host-owned `ThreadDestinationFactory` binding), [Paired server store](paired-server-store.md#wiring--usage) (the `ObservablePairedServerStore.remove` hook that evicts a host's drafts on unpair, since #790)
- Child components: [Thread input bar](thread-input-bar.md) (the composer in `bottomBar`, landed in #188), [Slash-command type-ahead](slash-command-type-ahead.md) (composer suggestions anchored above the input bar, via [Options overlay](options-overlay.md); landed in [#885](https://github.com/pyrycode/pyrycode-mobile/issues/885) — see [overlays and app bar § Slash-command type-ahead placement](thread-screen-how-it-works-overlays-and-app-bar.md#slash-command-type-ahead-placement-post-885)), [Thread composer footer](thread-composer-footer.md) (the model/effort buttons and Status-sheet opener below the input bar in `bottomBar`; landed as the `ThreadStatusRow` single status line in #145, tap-to-open the Status Sheet wired in #254, replaced by the two-button footer plus [Options overlay](options-overlay.md) in [#808](../codebase/808.md)), [Connection status indicator](connection-banner.md) (Connecting/Reconnecting in the composer; Offline Retry in the overlay), [WorkspaceChip](workspace-chip.md) (the empty-discussion chip between banner and list, wired in #137), [WorkspacePicker](workspace-picker.md) (rendered as Scaffold sibling, wired in #137), [StatusSheet](status-sheet.md) (rendered as Scaffold sibling gated on screen-hoisted `sheetVisible`, wired in #254 — Model section only; #229/#230 append sibling sections), [RenameDialog](rename-dialog.md) (rendered as Scaffold sibling gated on `state.showRenameDialog`, wired in [#141](../codebase/141.md); seeded with `state.displayName` as `initialName`, emits `ThreadEvent.RenameSubmit(name)` / `RenameDismiss` through `onOverflowEvent`), [ChannelInfoSheet](channel-info-sheet.md) (rendered as Scaffold sibling gated on `state.channelInfoOpen`, wired in [#226](../codebase/226.md); fed a `ChannelInfoUiModel` via the pure `state.toChannelInfoUiModel(now)` mapper, Rename / Change workspace emit-then-dismiss through `onOverflowEvent`; Archive / Delete wired in [#227](../codebase/227.md) — Archive closes-and-pops, Delete opens the `DeleteConfirmationDialog`), [DeleteConfirmationDialog](thread-screen-how-it-works-sheets.md#channelinfosheet-archivedelete--pop-back-nav-post-227) (private confirm dialog rendered as the sixth Scaffold sibling gated on `state.deleteConfirmVisible`, wired in [#227](../codebase/227.md)), [Permission-modal overlay](permission-modal-overlay.md) (the `PermissionModalOverlay` `BasicAlertDialog` rendered as the seventh Scaffold sibling when `modalState is ModalUiState.Open`, wired in [#446](../codebase/446.md); collects the hoisted app-level [`currentModal`](current-modal-state.md) from #445, dialog-window `FLAG_SECURE`, `Dismissed` → mapped-reason snackbar; answering is #444), [EmptyThreadState](empty-thread-state.md) (the centered empty-thread prompt that occupies the `weight(1f)` slot when `!hasMessages`, wired in #138), [MessageBubble](message-bubble.md) (`MessageItem` row dispatch since #246), [SessionBoundaryDelimiter](session-boundary-delimiter.md) (`SessionBoundary` row dispatch since #246), [Model refusal row](model-refusal-row.md) (`ThreadItem.ModelRefusal` row dispatch, `listKey()` and `timestamp()` arms, wired in [#875](../codebase/875.md)), [Stopped-turn row](stopped-turn-row.md) (`ThreadItem.StoppedTurn` row dispatch, `listKey()` and `timestamp()` arms, wired in [#1356](https://github.com/pyrycode/pyrycode-mobile/issues/1356)), [ThreadOverflowMenu](thread-overflow-menu.md) (client-owned Actions overlay consuming `ThreadEvent`, hosted directly by the screen since #1666 — the screen owns `overflowExpanded` via `rememberSaveable`)
- Downstream remaining (each layers on top of the post-#246 render loop, not the chrome): [#141](../codebase/141.md) ✅ rename dialog wired (via `ThreadEvent.Rename` from the overflow menu — `onTitleClick` stays a `{}` no-op, a future ticket may bind it as a second entry point), [#229](https://github.com/pyrycode/pyrycode-mobile/issues/229) Effort + YOLO sections append into the existing `StatusSheetContent` `Column` (may revisit the [#254](../codebase/254.md) auto-close-on-select decision for the multi-toggle UX), [#230](https://github.com/pyrycode/pyrycode-mobile/issues/230) Context window section appends as the third section (if a `Section` shape genuinely emerges, factor then), [#208](https://github.com/pyrycode/pyrycode-mobile/issues/208) overflow "Change workspace…" (reuses `workspacePickerVisible` + the two picker handlers introduced by #137; fills the `ThreadEvent.ChangeWorkspace` branch of `onOverflowEvent`), and generic scroll-to-bottom on every new newest row — shipped by [#981](https://github.com/pyrycode/pyrycode-mobile/issues/981), distinct from [`#185`](../codebase/185.md)'s streaming-only auto-anchor: #185 re-pins only while `isStreaming = true` and only against item 0's measured-size growth, while #981 added a second, identity-keyed pin beside it that also follows a row that arrives already finalized (see [Thread screen — list and status row](thread-screen-how-it-works-list-and-status-row.md)). [#136](../codebase/136.md) above-delimiter opacity landed `0.55f` alpha over rows above the most-recent `SessionBoundary`; [#138](../codebase/138.md) landed the centered empty-thread prompt that replaces the `LazyColumn` whenever `!state.hasMessages`; [#145](../codebase/145.md) landed the `Model · effort · NN% used ▴` status row above the input bar; [`#251`](../codebase/251.md) landed the sealed `ThreadEvent` + `ThreadViewModel.onOverflowEvent` dispatcher + stateless [`ThreadOverflowMenu`](thread-overflow-menu.md) composable (exported but not mounted; `Archive` is the only branch wired today); [`#252`](../codebase/252.md) mounted the overflow menu inside `ThreadTopAppBar`'s `actions` slot (the screen owns `overflowExpanded` via `rememberSaveable`, the renamed `onOverflowEvent` parameter binds `vm::onOverflowEvent` at the destination); [`#254`](../codebase/254.md) landed the [`StatusSheet`](status-sheet.md) shell with the Model section (the row's `onExpandClick` is wired internally, the screen owns `sheetVisible` via `rememberSaveable`, and `MainActivity` binds `onModelSelected = vm::onModelSelected`).
- Figma: [`16:8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8) (the populated Conversation Thread Screen canvas; this slice ships the outer shell + the TopAppBar chrome + the composer in `bottomBar` + the connection status band and top overlay + the empty-discussion workspace chip between banner and list + the picker host at screen root + the populated message list with bubble/delimiter dispatch from #246 + the above-delimiter dim from #136 + the centered empty-thread prompt from #138 in the no-`MessageItem` branch + the status row above the input bar from #145 + the Status Sheet host at screen root from #254), subframe [`16:58`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-58) (the status row specifically), subframe [`20:100`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=20-100) (the Status Sheet; this slice ships the Model section region only)
