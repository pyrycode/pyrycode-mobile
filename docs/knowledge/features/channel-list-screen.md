# ChannelListScreen

Stateless `(hostState, onEvent)` composable that renders a Material 3 `Scaffold` with the list's own
top bar (a settings entry and an archive entry above a rule, #737 — see
[The list's own top bar](channel-list-screen-how-it-works.md#the-lists-own-top-bar-737) below) above a single-`LazyColumn` conversation tree
(#731): a Channels section and a Chats section, each holding host rows, their workspace rows and those
workspaces' conversation rows, drawn from `hostState` (#729's `HostChannelListEntry.channelGroups` /
`chatGroups`) using the row composables from `ui/conversations/components/ConversationTreeRows.kt` (#730,
see [Tree rows](channel-list-screen-how-it-works.md#tree-rows-730) below). The flat `ConversationRow` list and the inline "Recent discussions"
section with its "See all" link into `Routes.DISCUSSION_LIST` are gone — #731 replaced both. Row taps carry
their own `serverId`, so a tree drawing rows from several hosts opens each on the host that owns it, never
through a selected-host adapter. Fold and selection state (which nodes are collapsed, which row was last
opened from this list) live in the ViewModel, so they survive recomposition, `LazyColumn` recycling, an
incoming snapshot and the thread round trip.

The floating action button that used to create a chat and open pairing is gone (#738): each section header
now carries its own add control that opens pairing's existing scanner entry, and each host row carries one
that starts a chat on **that row's** host — see [Add controls](channel-list-screen-tree-and-controls.md#add-controls-738) below. With the button
gone, the flat `ChannelListUiState` compatibility model (loading/error/empty placeholders,
`workspacePickerVisible`) retired with it: `hostState.hosts.isEmpty()` is now the tree's only blank.

Package: `de.pyryco.mobile.ui.conversations.list` (`app/src/main/java/de/pyryco/mobile/ui/conversations/list/`). File: `ChannelListScreen.kt`.

## What it does

Wraps its body in a `Scaffold` whose `topBar` is the file-private `ChannelListTopBar` (rendered in **every**
state — see [The list's own top bar](channel-list-screen-how-it-works.md#the-lists-own-top-bar-737) below). There is no `floatingActionButton`
slot: #738 retired it, along with the flat `ChannelListUiState` it gated on.

The body branches on `hostState.hosts` — the only model the screen is fed:

- **`hostState.hosts.isEmpty()`** — no host has produced a snapshot yet, or there are none paired. Falls back
  to the centred `R.string.channel_list_empty` ("Tap + to start a conversation") copy. This is the only
  blank-tree case; a paired host with a snapshot but no conversations still draws its own host row, which is
  content, not a blank screen. The `Loading` / `Error(message)` compatibility placeholders #738 removed drew
  from the retired flat state; a cold start or an upstream failure now renders this same empty copy rather
  than a distinct message — see [Edge cases](#edge-cases--limitations).
- **Otherwise** — a private `ConversationTree(hostState, onEvent, modifier)` composable renders the full
  two-section tree, its section headers and host rows each carrying their own add control. See
  [Conversation tree (#731)](#conversation-tree-731) and [Add controls (#738)](channel-list-screen-tree-and-controls.md#add-controls-738).

`Routes.DISCUSSION_LIST` / `DiscussionListScreen` stay in the graph, unreachable — removing them was out of
\#731's scope and remains out of \#738's. The generic top app bar #732 was going to retire is already gone —
\#737 replaced it with the list's own bar, split off as the first of #732's two slices.

## Shape

```kotlin
sealed interface ChannelListEvent {
    /** The row's own host, resolved from the row itself — never from the selected-host adapter. */
    data class TreeRowTapped(val target: HostConversationTarget) : ChannelListEvent
    data class TreeFoldToggled(val key: TreeFoldKey) : ChannelListEvent
    data object SettingsTapped : ChannelListEvent
    /** The list's own archive entry — the same destination Settings' archived-discussions row opens (#737). */
    data object ArchiveTapped : ChannelListEvent
    /** A section header's add control: pair an additional host (#738). Carries no section — both
     *  headers open the same pairing flow, so only the control's own name disambiguates. */
    data object PairHostTapped : ChannelListEvent
    /** A host row's add control: a chat on **that** row's host, in its default workspace (#738). */
    data class TreeHostAddTapped(val serverId: String) : ChannelListEvent
    /** The same control held: pick that host's workspace first — the path the retired button long-pressed. */
    data class TreeHostAddLongPressed(val serverId: String) : ChannelListEvent
    /** A host row's edit control: open the Edit host modal for **that** row's host (#744). */
    data class TreeHostEditTapped(val serverId: String) : ChannelListEvent
    /** The open modal's OK, already trimmed. No `serverId` — the target is the open editor's, held in the
     *  view model; a second id here would be a second source of truth for which host is being renamed. */
    data class HostEditNameSubmitted(val name: String) : ChannelListEvent
    /** The modal's Cancel, Close and Back, which the shell routes through one dismissal callback. */
    data object HostEditDismissed : ChannelListEvent
    /** A Chats row's edit control: open the Edit chat modal for **that** row's own host and
     *  conversation (#827). Channels rows draw no control and emit nothing here — editing a
     *  channel is #667. */
    data class TreeChatEditTapped(val target: HostConversationTarget) : ChannelListEvent
    /** The open Edit chat modal's OK, already trimmed. No ids, for the reason
     *  [HostEditNameSubmitted] carries none: the target is the open editor's. */
    data class ChatEditNameSubmitted(val name: String) : ChannelListEvent
    /** The Edit chat modal's Cancel, Close and Back. */
    data object ChatEditDismissed : ChannelListEvent
    data class WorkspacePicked(val workspace: String) : ChannelListEvent
    data object WorkspacePickerDismissed : ChannelListEvent
}

@Composable
fun ChannelListScreen(
    hostState: HostChannelListState,
    onEvent: (ChannelListEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    /* … Scaffold { ChannelListTopBar } — no floatingActionButton slot since #738 — wrapping either the
         empty-tree placeholder or ConversationTree(hostState, onEvent, bodyModifier); WorkspacePicker host
         as a Scaffold sibling, unchanged since #221, its visible read straight off
         hostState.workspacePickerServerId … */
}
```

`ExperimentalMaterial3Api` dropped from the file's `@OptIn` in #737 along with the `TopAppBar` import — the
list's own bar is a plain `Column`/`Row`/`IconButton`/`HorizontalDivider`, none of them experimental.
`ExperimentalFoundationApi` dropped from this file's `@OptIn` in #738 with `ChannelListFab` — the file's own
`combinedClickable` usage went with it; the tree rows' `TreeAddControl` (below) carries that experimental
opt-in now, local to `ConversationTreeRows.kt`.

`RowTapped` and `RecentDiscussionsTapped` are gone — the two composables that emitted them
(`ConversationRow` at the top level, `SeeAllDiscussionsRow`) no longer exist in this file, and an event
nothing can emit is dead code. `CreateDiscussionTapped` and `LongPressFab` are gone too (#738), replaced by
the host-qualified `TreeHostAddTapped` / `TreeHostAddLongPressed` pair, and `PairHostTapped` is new.
`ChannelListEvent` still lives in `ChannelListScreen.kt`, not `ChannelListViewModel.kt`: the screen remains
the producer for every variant except the ten the VM's destination wiring consumes directly
(`TreeHostAddTapped`, `TreeHostAddLongPressed`, `TreeHostEditTapped`, `HostEditNameSubmitted`,
`HostEditDismissed` (#744), `TreeChatEditTapped`, `ChatEditNameSubmitted`, `ChatEditDismissed` (#827),
`WorkspacePicked`, `WorkspacePickerDismissed`);
`TreeRowTapped` / `TreeFoldToggled` / `SettingsTapped` / `ArchiveTapped` / `PairHostTapped` route through the
destination's `when (event)` instead (see [Wiring](channel-list-screen-how-it-works.md#wiring)).

The file-private `ChannelListFab` — the manually-composed `Surface` #22 → #221 built to own tap + long-press
directly (bypassing the M3 `FloatingActionButton` widget's own inner `Surface(onClick = ...)`, which would
otherwise shadow an outer `combinedClickable` — see [`../codebase/25.md`](../codebase/25.md) and
[`../codebase/221.md`](../codebase/221.md)) — is gone (#738). The same construction lives on in
`TreeAddControl`, the control both new add controls draw; see [Add controls (#738)](channel-list-screen-tree-and-controls.md#add-controls-738).

## Conversation tree (#731)

Split into [ChannelListScreen — conversation tree and controls](channel-list-screen-tree-and-controls.md) on 2026-09-22 to keep this document under the 50000-byte cap the docs guard enforces. Every section — Conversation tree (#731), Add controls (#738) and Host row edit control (#744) — moved there verbatim, headings and anchors intact.

## How it works

Split into [ChannelListScreen — how it works](channel-list-screen-how-it-works.md) on 2026-09-22 to keep this document under the 50000-byte cap the docs guard enforces. Every section — How it works, Tree rows (#730), Wiring and Configuration — moved there verbatim, headings and anchors intact.

## Preview

Three `@Preview` composables (re-cut in #731 from the prior six flat/discussion previews), all
`widthDp = 412`:

- `ChannelListScreenTreePreview` (`@Preview(name = "Tree — Light", heightDp = 900, …)`) — a private
  `previewHostState(now)` builds two hosts (`"pyrybox"` / named, `"macbook"` / nameless) each with three-ish
  channels and chats across two workspace `cwd`s, one collapsed key
  (`TreeFoldKey(Channels, "macbook")`) and one selected target. Canonical "matches Figma `133-259`" preview:
  two sections, host containers, workspace rows, one collapsed host, one selected conversation row.
- `ChannelListScreenTreeDarkPreview` — same data, dark theme (`uiMode = Configuration.UI_MODE_NIGHT_YES`).
- `ChannelListScreenEmptyPreview` (`@Preview(name = "No hosts — Light", …)`) — `hostState = HostChannelListState()`
  (no hosts). Renders the list's own bar above the centred empty-state copy — the tree's one blank state,
  and (since #738) the screen's only remaining placeholder path.

All three previews render the list's own bar since #737 and were compared against the Figma screenshot of
node `133-259` before that PR.

The `Loading` / `Error` previews #45's rationale used to justify skipping stayed unpreviewed through their
whole life and retired with the flat state itself (#738); the screen's transient states now have no visual
distinction from the tree's own blank at all — see the next section.

## Edge cases / limitations

- **The tree's only blank state is zero hosts.** A host with a snapshot but no channels or chats still draws
  its own `TreeHostRow` and no workspaces underneath — that is content, not an empty screen. Only
  `hostState.hosts.isEmpty()` falls back to the empty placeholder — the same copy for a cold start (no
  snapshot yet) and an upstream failure, now that #738 retired the flat state's distinct `Loading` /
  `Error(message)` texts. No acceptance criterion named this collapse; it falls directly out of AC-4's
  instruction to retire the flat state along with its placeholders.
- **A disconnected host still draws its previously loaded rows.** Since #796, `HostConversationSource`
  seeds a newly created host entry from the on-disk [conversation cache](conversation-cache.md) when no
  live list has arrived yet, so a saved host restarted or unreachable draws the channels and chats it
  last loaded rather than an empty node beneath its row. The seed never writes `connectionStatus`, so
  `TreeHostRow` keeps rendering that host's real disconnected status — this screen needed no change and
  gained no second indicator; see
  [dependency injection § Restore from the on-disk cache](dependency-injection-host-conversation-source.md#restore-from-the-on-disk-cache-796)
  for the write/seed/race mechanism.
- **Fold state is never pruned against an incoming snapshot.** A host or workspace that momentarily
  disappears during a reconnect comes back exactly as folded as the operator left it — see
  [ChannelListViewModel](channel-list-viewmodel.md) for the ViewModel-side rationale.
- **Selection is "last opened from this list", not "currently open."** The phone shows the list and the
  thread as separate destinations, so nothing is ever "open" while the list is on screen; `hostState.selected`
  is the row the operator most recently tapped or the discussion most recently created from this screen, and
  stays highlighted when the thread is dismissed and the list comes back.
- **No `rememberLazyListState` / scroll-position persistence.** Unchanged from the flat-list era: `LazyColumn`
  auto-saves scroll position within a single composition; `rememberSaveable(saver = LazyListState.Saver)` is
  the next step if a real bug surfaces.
- **No error/loading affordance on the create call itself**, **no retry affordance on `Error`**, **no
  `flowOn(Dispatchers.IO)` anywhere in the chain** — all unchanged from the flat-list era; see
  [ChannelListViewModel](channel-list-viewmodel.md) for the state-projection side of each.
- **Press-elevation animation is lost** on the manual-`Surface` construction `ChannelListFab` pioneered and
  `TreeRowControl` inherits (#221, #738, #744) — unchanged; the `combinedClickable` default ripple covers
  the feedback gap.
- **Instrumented test coverage.** `ChannelListScreenTest` (`app/src/androidTest/.../list/ChannelListScreenTest.kt`)
  builds a hand-crafted `HostChannelListState` and asserts, among others: both sections render their host,
  workspace and conversation rows with nothing folded on first show; folding a host hides its workspaces and
  their conversations while folding a workspace hides only its own rows; a conversation row emits
  `TreeRowTapped` carrying *its own* host's `serverId`, not a selected one (the wrong-host regression,
  expressed as a failing assertion first); exactly the row matching `selected` asserts selected via
  `assertIsSelected` / `assertIsNotSelected` (a semantics read, not a colour read); each row carries its
  section's test tag; a tree far taller than the viewport reaches its last row via
  `performScrollToNode(hasScrollAction())`; and a nameless host and a nameless conversation render their
  fallback labels.
  `listBar_drawsBothEntriesAndNoneOfTheRetiredChrome_onEveryDraw` (#737, reshaped #738) walks one composition
  through both draws — the empty placeholder and the tree (four before #738 retired the flat state's loading
  and error draws) — identifying each by its own distinguishing copy before asserting both bar entries
  `assertIsDisplayed` and the app name / logo description `assertDoesNotExist`; a bar placed inside the tree's
  scroll container would have passed on the loaded draw alone and vanished on the placeholder, which is the
  mistake this walk exists to catch. `archiveEntry_emitsArchiveTapped` (#737) guards the new event, mirroring
  the unchanged `settingsGear_emitsSettingsTapped` that guards `cd_open_settings` surviving.
  `sectionHeaders_eachCarryTheirOwnPairingControl` (#738) asserts both section headers carry a control,
  each separately named, each emitting `PairHostTapped`; `hostRowAddControl_targetsItsOwnHost_onTapAndOnLongPress`
  drives a two-host tree's **second** host and asserts the emitted `TreeHostAddTapped` /
  `TreeHostAddLongPressed` carry that host's `serverId`, so a globally-selected wiring could not pass;
  `hostRowAddControl_doesNotFoldTheRowItSitsIn` taps the control and asserts one `TreeHostAddTapped` with the
  row's subtree still drawn, proving the nesting claim in [Add controls](channel-list-screen-tree-and-controls.md#add-controls-738) rather than
  trusting the inherited merge rule. `workspacePicker_drawsExactlyWhenItsTargetIsSet` (#738) replaces the old
  `when (state)` assertion with a direct read of `hostState.workspacePickerServerId`.
  Navigation itself is not re-proven here — the scripted device gate drives tap-to-thread end to end.

  `ConversationTreeRowsTest` gained (#744): `hostRow_editControl_isNamedForItsHostAndReportsOnlyItsOwnTap`
  — the pencil is named for its host, distinguishable from the add control beside it, and reports its own
  tap without folding the row or reaching the add control; `hostRow_editControl_clampsAnOversizedIdIntoItsTestHandle`
  — mirrors the add control's own clamp test; `hostRowEditControl_targetsItsOwnHost_andDoesNotFoldTheRowItSitsIn`
  — a two-host tree's pencil still resolves to its own row even where a second section repeats the same tag.
  `ChannelListScreenTest` gained `editHostModal_drawsTheOpenEditorsOwnValuesAndReportsOkAndDismissal` (the
  identity and relay address as the design's two inert rows, the name as the editable value, OK reporting
  the already-trimmed name), `editHostModal_whileSaving_cannotStartASecondSave` and
  `editHostModal_afterAFailure_staysOpenAndActionableAndStatesItGenerically` (one generic string, naming
  neither the identity nor the relay address) — split from one planned saving-and-failure test into two
  because `createComposeRule` permits only one `setContent` per test, so a single test could not render two
  different editor states.

  #745 replaced `editHostModal_unpairActionIsInertInThisSlice`, per the ticket's own instruction, with three
  cases that walk the confirmation rather than assert its absence:
  `editHostModal_unpairAction_asksForConfirmationNamingTheHost_andDecliningReturnsToTheEditor` (tapping
  `Unpair host` emits `HostUnpairRequested`, the confirmation names the host and the name field is gone,
  Cancel emits `HostUnpairDeclined` — not `HostEditDismissed` — and the field returns with its draft
  intact), `editHostModal_unpairConfirmation_namesAnUnnamedHostByItsRowsOwnFallback` (the fallback matches
  the row's own rule) and `editHostModal_afterAFailedUnpair_namesTheUnpairRatherThanTheSaveAndStaysActionable`
  (the unpair string shows, the save string does not, and OK still emits `HostUnpairConfirmed`).
  `EditHostModalTest` gained the matching component-level case,
  `unpairConfirmationReplacesTheContentInPlaceAndEveryDismissalRouteDeclines`: confirming swaps the identity
  rows and name field for the prompt inside the same shell, Cancel / the close glyph / system Back each
  decline rather than dismiss, and OK confirms exactly once.

  `ChannelListScreenTest` gained (#827): every Chats row carries a pencil named "Edit chat <name>", Channels
  rows carry none, and tapping the pencil emits exactly `TreeChatEditTapped` with the row's own target and no
  `TreeRowTapped`. With an open `chatEditor`, the field pre-fills, OK emits `ChatEditNameSubmitted` with the
  trimmed name, Cancel emits `ChatEditDismissed`, and `failed` shows the generic string. A mutable host state
  in the harness flips a target host to disconnected — OK disables without closing the modal or losing the
  typed name — and back to connected, re-enabling it. **A `LazyColumn` row below the fold is not composed**,
  so a pencil assertion on a lower row needs `performScrollToNode(hasScrollAction())` first — the same call
  the tree's own reach-the-last-row test above already uses; `onAllNodes(...).assertCountEquals(1)` against
  an uncomposed row finds nothing and reads as a missing pencil rather than as an unscrolled list.

## Related

- Ticket notes: [`../codebase/46.md`](../codebase/46.md), [`../codebase/21.md`](../codebase/21.md),
  [`../codebase/22.md`](../codebase/22.md), [`../codebase/23.md`](../codebase/23.md),
  [`../codebase/26.md`](../codebase/26.md), [`../codebase/68.md`](../codebase/68.md),
  [`../codebase/69.md`](../codebase/69.md) (inline recent-discussions section — retired by #731),
  [`../codebase/99.md`](../codebase/99.md), [`../codebase/162.md`](../codebase/162.md),
  [`../codebase/221.md`](../codebase/221.md) (FAB long-press → `WorkspacePicker` — the button itself
  retired by #738, the picker wiring it originated carried forward)
- Specs: `docs/specs/architecture/46-channellistscreen-lazycolumn-tap-nav.md`,
  `docs/specs/architecture/21-channel-list-top-app-bar.md`,
  `docs/specs/architecture/22-channel-list-fab-new-discussion.md`,
  `docs/specs/architecture/23-channel-list-empty-state.md`,
  `docs/specs/architecture/26-recent-discussions-pill.md`,
  `docs/specs/architecture/68-channel-list-figma-polish.md`,
  `docs/specs/architecture/69-channel-list-recent-discussions-section.md`,
  `docs/specs/architecture/99-channel-list-screen-compose-tests.md`,
  `docs/specs/architecture/162-channel-list-discussion-preview-row-last-message.md`,
  `docs/specs/architecture/221-channel-list-fab-long-press-workspace-picker.md`,
  `docs/specs/architecture/730-mobile-tree-rows.md`,
  `docs/specs/architecture/731-assemble-conversation-tree.md`,
  `docs/specs/architecture/737-list-settings-archive-bar.md`,
  `docs/specs/architecture/738-list-add-controls-retire-fab.md`,
  `docs/specs/architecture/744-host-row-edit-and-rename.md`,
  `docs/specs/architecture/745-unpair-host-from-edit-modal.md`,
  `docs/specs/architecture/827-rename-chat-from-tree-row.md`
- Upstream: [ChannelListViewModel](./channel-list-viewmodel.md) (`hostState` producer — fold/selection state,
  `onHostRowTapped`, `onFoldToggled`, `createHostDiscussion`, `openHostWorkspacePicker`, since #744
  `openHostEditor`, `submitHostName`, `dismissHostEditor`, and since #745 `requestHostUnpair`,
  `confirmHostUnpair`, `declineHostUnpair`, and since #827 `openChatEditor`, `submitChatName`,
  `dismissChatEditor`, `isHostConnected`; the compatibility `state` producer, `onEvent`
  reducer and `navigationEvents` this screen once also consumed retired with the button in #738), [Tree
  rows](channel-list-screen-how-it-works.md#tree-rows-730) (`TreeSectionHeader` / `TreeHostRow` / `TreeWorkspaceRow` / `TreeConversationRow`,
  #730; `TreeRowControl` since #738, renamed from `TreeAddControl` in #744), [`EditHostModal`](mobile-modal.md#callers)
  (#743's shell content, driven by this screen since #744), [`EditChatModal`](mobile-modal.md#callers)
  (#826's shell content, driven by this screen since #827), [`HostWorkspaceGroup`](channel-list-viewmodel-projection.md)
  (#729's workspace projection this screen iterates), [ConversationAvatar](./conversation-avatar.md),
  [WorkspacePicker](./workspace-picker.md), [Navigation](./navigation.md), [Dependency injection](./dependency-injection.md)
- Downstream: #737 (done — draws the list's own settings + archive bar in the `topBar` slot this section
  describes; #740, done, added the rung-3 scenario reaching Archived through the list's own archive entry
  rather than Settings' — see [Interactive stream e2e](../../e2e-interactive-stream.md#what-rung-3-is-made-of)),
  #738 (done — the remaining half of #732's split; retired the FAB and
  the compatibility `ChannelListUiState` placeholders #731 deliberately kept, and gave the list its own
  section-header and host-row add controls), #744 (done, split from #642 — the host row's edit control and
  the rename path this section describes), #745 (done, split from #642 — wires `Unpair host` behind a
  confirmation, this section's own [Host row edit control](channel-list-screen-tree-and-controls.md#host-row-edit-control-744)), #676 (the live
  emulator scenario for #744's rename flow, #745's removal and #715's two-host archive/restore case,
  blocked by all three and still open), #668
  (indicator-pair live accuracy, conversation-row unread/activity state), #665 (conversation-row edit
  pencil), #664 (the workspace row's own add control and its modal content, beyond #738's reuse of
  the existing pairing scanner for the section header; calls the `renameWorkspace` / `archiveWorkspace`
  repository methods #663 added with no UI of its own), #675 (disconnected-host
  repair control), #154 / Phase 3 Settings / Phase 4 items predating #731 remain as recorded in
  [`../codebase/`](../codebase/) history.
