# ChannelListScreen

Stateless `(hostState, onEvent)` composable that renders a Material 3 `Scaffold` with the list's own
top bar (an “Open menu” ellipsis at the left, one “Pair another host” plus at the right, above a rule — see
[The list's own top bar](channel-list-screen-how-it-works.md#the-lists-own-top-bar-737) below) above a single-`LazyColumn` conversation tree
(#731, #1189): each host appears once, with fixed Channels and Chats sections and their direct conversation
rows, drawn from `hostState` in host and source-list order using the row composables from
`ui/conversations/components/ConversationTreeRows.kt` (#730,
see [Tree rows](channel-list-screen-how-it-works.md#tree-rows-730) below). The flat `ConversationRow` list and the inline "Recent discussions"
section with its "See all" link into `Routes.DISCUSSION_LIST` are gone — #731 replaced both. Row taps carry
their own `serverId`, so a tree drawing rows from several hosts opens each on the host that owns it, never
through a selected-host adapter. Fold and selection state (which nodes are collapsed, which row was last
opened from this list) live in the ViewModel, so they survive recomposition, `LazyColumn` recycling, an
incoming snapshot and the thread round trip.

The floating action button that used to create a chat and open pairing is gone (#738). Pairing opens from
the fixed top-right toolbar control (#1186), and each host's Chats section carries a plus
that creates a chat directly on that host (since #1563) — see
[Add controls](channel-list-screen-tree-and-controls.md#add-controls-738). With the button
gone, the flat `ChannelListUiState` compatibility model (loading/error/empty placeholders,
`workspacePickerVisible`) retired with it: `hostState.hosts.isEmpty()` is now the tree's only blank.

Package: `de.pyryco.mobile.ui.conversations.list` (`app/src/main/java/de/pyryco/mobile/ui/conversations/list/`). File: `ChannelListScreen.kt`.

Tree labels use the [shared type ramp](shared-typography.md) checked against Figma sidebar `15:8`.

## What it does

Wraps its body in a `Scaffold` whose `topBar` is the file-private `ChannelListTopBar` (rendered in **every**
state and after scrolling to the final row). It has no title: “Open menu” at the left opens Settings
then Archive, and exactly one “Pair another host” control at the right emits `PairHostTapped` into the existing
[scanner/code flow](navigation.md#manual-pairing-entry-and-return). The menu's 6 × 24dp ellipsis and pairing's
24 × 24dp plus sit in separate 44 × 44dp targets; 20dp visual-frame gutters, a 1dp divider and a 24dp gap
to the first row apply in both themes — see
[The list's own top bar](channel-list-screen-how-it-works.md#the-lists-own-top-bar-737). There is no `floatingActionButton`
slot: #738 retired it, along with the flat `ChannelListUiState` it gated on.

The body branches on `hostState.hosts` — the only model the screen is fed:

- **`hostState.hosts.isEmpty()`** — no host has produced a snapshot yet, or there are none paired. Falls back
  to the centred `R.string.channel_list_empty` ("To pair a host, tap Pair another host at the top right.")
  copy. The guidance names the always-present toolbar control without asserting that no host is paired
  while snapshots are pending. Settings still opens with no selected host; Archive remains available in the menu but
  its tap does nothing without a selected host. This is the only
  blank-tree case; a paired host with a snapshot but no conversations still draws its own host row, which is
  content, not a blank screen. The `Loading` / `Error(message)` compatibility placeholders #738 removed drew
  from the retired flat state; a cold start or an upstream failure now renders this same empty copy rather
  than a distinct message — see [Edge cases](#edge-cases--limitations).
- **Otherwise** — a private `ConversationTree(hostState, onEvent, modifier)` composable renders each host
  with its Channels and Chats sections. It draws no global tier divider or workspace rows.
  Each Chats-section plus creates a chat for its host; the Channels-section plus opens Create channel.
  Both create in their host's daemon-default folder. See
  [Conversation tree (#731)](#conversation-tree-731) and [Add controls (#738)](channel-list-screen-tree-and-controls.md#add-controls-738).

`Routes.DISCUSSION_LIST` / `DiscussionListScreen` stay in the graph, unreachable — removing them was out of
\#731's scope and remains out of \#738's. The generic top app bar #732 was going to retire is already gone —
\#737 replaced it with the list's own bar, split off as the first of #732's two slices.

## Create chat failure notice (#1748)

A failed `hostState.createChat` request shows the unchanged `create_chat_failed` text
(“Couldn’t create the chat. Try again.”) in the shared [Error NoticePill](notice-pill.md).
It overlays the tree within 20dp side gutters, right-aligned 28dp below the measured
screen header. The tree keeps its layout, and the Settings/Archive menu and pairing
control remain usable. There is no error snackbar, X, dismissal or tap action.
The placement reuses Figma `685:4337` under #1604's authorisation; it does not introduce
a separate list frame. There is currently no sibling notice to stack beneath.

The composition-local timer is keyed by request ID and failure state. It preserves
Material's Short lifetime of 4000ms, adjusted through the accessibility manager with
icons/text/controls flags `true/true/false`. Recomposition cannot replay an expired
failure; a later failed request starts a fresh notice, a new request cancels the old
timer, and leaving composition cancels it. The caller supplies `LiveRegionMode.Polite`:
preserving a snackbar's accessibility-adjusted timeout alone would lose its failure
announcement. This announcement is independent of the pill's inert behavior.

`CreateChatFailureNoticeTest` supplies the actual failed-create state and checks text,
header-relative placement, unchanged tree geometry, usable menu/pairing controls,
polite semantics and absence of snackbar/dismiss/click actions. Its other methods
cover expiry, adjusted timeout flags, no recomposition replay, later failures and
screen exit. The real-activity `ListDesignCaptureTest.failedCreateChatNoticeAt412By892`
drives the Chats plus through a failing repository; see the
[retained comparison and evidence limits](../../../app/src/androidTest/assets/design-1220/list/index.md#create-chat-failure--error-pill-reuse-6854337).
The shared pill's inherited 22px single-line background versus Figma's 24px is tracked
in [#1757](https://github.com/pyrycode/pyrycode-mobile/issues/1757).

## Shape

```kotlin
sealed interface ChannelListEvent {
    /** The row's own host, resolved from the row itself — never from the selected-host adapter. */
    data class TreeRowTapped(val target: HostConversationTarget) : ChannelListEvent
    data class TreeFoldToggled(val key: TreeFoldKey) : ChannelListEvent
    data object SettingsTapped : ChannelListEvent
    /** The list's own archive entry — the same destination Settings' archived-discussions row opens (#737). */
    data object ArchiveTapped : ChannelListEvent
    /** The fixed toolbar's add control opens the existing scanner/code pairing flow. */
    data object PairHostTapped : ChannelListEvent
    /** The Chats-section plus creates a chat for its own host. */
    data class TreeHostChatAddTapped(val serverId: String) : ChannelListEvent
    /** A host row's edit control: open the Edit host modal for **that** row's host (#744). */
    data class TreeHostEditTapped(val serverId: String) : ChannelListEvent
    /** The open modal's OK, already trimmed. No `serverId` — the target is the open editor's, held in the
     *  view model; a second id here would be a second source of truth for which host is being renamed. */
    data class HostEditNameSubmitted(val name: String) : ChannelListEvent
    /** The modal's Cancel, Close and Back, which the shell routes through one dismissal callback. */
    data object HostEditDismissed : ChannelListEvent
    /** A Chats row's pencil: open the Edit chat modal on **that** row's own host and conversation (#827).
     *  Ids only — the view model reads the name from that host's own snapshot. */
    data class TreeChatEditTapped(val target: HostConversationTarget) : ChannelListEvent
    /** The open Edit chat modal's OK, already trimmed. No ids, for the reason
     *  [HostEditNameSubmitted] carries none: the target is the open editor's. */
    data class ChatEditNameSubmitted(val name: String) : ChannelListEvent
    /** The Edit chat modal's Cancel, Close and Back. */
    data object ChatEditDismissed : ChannelListEvent
    /** A Channels row's pen: open Edit channel on **that** row's own host and conversation (#667). The
     *  name is read from the host's own snapshot, never from the row's text. */
    data class TreeChannelEditTapped(val target: HostConversationTarget) : ChannelListEvent
    /** The Edit channel modal's OK: the name already trimmed by the component, the prompt verbatim — or
     *  `null` when the modal never showed a stored prompt — and the Mute notifications checkbox as it
     *  stands (#1021). No ids: the target is the open modal's. */
    data class ChannelEditSubmitted(val name: String, val systemPrompt: String?, val muted: Boolean) : ChannelListEvent
    /** The Edit channel modal's Archive channel; the target is the open modal's. */
    data object ChannelArchiveRequested : ChannelListEvent
    /** The Edit channel modal's Cancel, Close and Back. */
    data object ChannelEditDismissed : ChannelListEvent
    /** An Add workspace row selected (#904): the raw path, never the displayed text. */
    data class AddWorkspaceSelected(val path: String) : ChannelListEvent
    /** The new-folder dialog's trimmed name; the folder is created on the open modal's host. */
    data class AddWorkspaceFolderCreateRequested(val name: String) : ChannelListEvent
    /** The Add workspace modal's OK. */
    data object AddWorkspaceSubmitted : ChannelListEvent
    /** The Add workspace modal's Cancel, Close and Back. */
    data object AddWorkspaceDismissed : ChannelListEvent
    /** A host's Channels section plus opens Create channel for that host, even when empty (#1189). */
    data class TreeHostChannelAddTapped(val serverId: String) : ChannelListEvent
    /** Retained for the workspace editor; no sidebar workspace row emits this after #1189. */
    data class TreeWorkspaceEditTapped(val serverId: String, val cwd: String) : ChannelListEvent
    /** The retained Edit workspace modal's OK, already trimmed. No ids, for the reason
     *  [HostEditNameSubmitted] carries none: the target is the open editor's. */
    data class WorkspaceEditNameSubmitted(val name: String) : ChannelListEvent
    /** The Edit workspace modal's Cancel, Close and Back from the editor. */
    data object WorkspaceEditDismissed : ChannelListEvent
    /** The modal's Archive workspace: ask for a confirmation in place rather than archiving. */
    data object WorkspaceArchiveRequested : ChannelListEvent
    /** The archive confirmation accepted, through the shell's own OK. */
    data object WorkspaceArchiveConfirmed : ChannelListEvent
    /** The archive confirmation backed out of, returning to the editor rather than closing it. */
    data object WorkspaceArchiveDeclined : ChannelListEvent
}

@Composable
fun ChannelListScreen(
    hostState: HostChannelListState,
    onEvent: (ChannelListEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    /* … Scaffold { ChannelListTopBar } — no floatingActionButton slot since #738 — wrapping either the
         empty-tree placeholder or ConversationTree(hostState, onEvent, bodyModifier); AddWorkspaceModalBinding
         (#904, replacing the WorkspacePicker host #221 placed here) as a Scaffold sibling, present exactly
         while hostState.addWorkspace is non-null … */
}
```

`ExperimentalMaterial3Api` dropped from the file's `@OptIn` in #737 along with the `TopAppBar` import — the
list's own bar is a plain `Column`/`Row`/`IconButton`/`HorizontalDivider`, none of them experimental.
`ExperimentalFoundationApi` dropped from this file's `@OptIn` in #738 with `ChannelListFab`. The section
controls now use ordinary `clickable` in `ConversationTreeRows.kt`.

`RowTapped` and `RecentDiscussionsTapped` are gone — the two composables that emitted them
(`ConversationRow` at the top level, `SeeAllDiscussionsRow`) no longer exist in this file, and an event
nothing can emit is dead code. `CreateDiscussionTapped` and `LongPressFab` are gone too (#738).
`TreeHostChatAddTapped` now opens the host-qualified Create chat modal; `PairHostTapped` opens pairing.
`ChannelListEvent` still lives in `ChannelListScreen.kt`, with the destination wiring calling explicit
ViewModel methods for host and modal events
(`TreeHostChatAddTapped`, `TreeHostEditTapped`, `HostEditNameSubmitted`,
`HostEditDismissed` (#744), `TreeChatEditTapped`, `ChatEditNameSubmitted`, `ChatEditDismissed` (#827),
`AddWorkspaceSelected`, `AddWorkspaceFolderCreateRequested`, `AddWorkspaceSubmitted`,
`AddWorkspaceDismissed` (#904, replacing `WorkspacePicked` / `WorkspacePickerDismissed`),
`TreeWorkspaceEditTapped`, `WorkspaceEditNameSubmitted`, `WorkspaceEditDismissed`,
`WorkspaceArchiveRequested`, `WorkspaceArchiveConfirmed`, `WorkspaceArchiveDeclined` (#905));
`TreeRowTapped` / `TreeFoldToggled` / `SettingsTapped` / `ArchiveTapped` / `PairHostTapped` route through the
destination's `when (event)` instead (see [Wiring](channel-list-screen-how-it-works.md#wiring)).

The file-private `ChannelListFab` — the manually-composed `Surface` #22 → #221 built to own tap + long-press
directly (bypassing the M3 `FloatingActionButton` widget's own inner `Surface(onClick = ...)`, which would
otherwise shadow an outer `combinedClickable` — see [`../codebase/25.md`](../codebase/25.md) and
[`../codebase/221.md`](../codebase/221.md)) — is gone (#738). `TreeRowControl` now has a single
`clickable` action; the toolbar uses clickable `Box` controls with button semantics.
See [Add controls (#738)](channel-list-screen-tree-and-controls.md#add-controls-738).

## Conversation tree (#731)

Split into [ChannelListScreen — conversation tree and controls](channel-list-screen-tree-and-controls.md) on 2026-09-22 to keep this document under the 50000-byte cap the docs guard enforces. Every section — Conversation tree (#731), Add controls (#738) and Host row edit control (#744) — moved there verbatim, headings and anchors intact.

## How it works

Split into [ChannelListScreen — how it works](channel-list-screen-how-it-works.md) on 2026-09-22 to keep this document under the 50000-byte cap the docs guard enforces. Every section — How it works, Tree rows (#730), Wiring and Configuration — moved there verbatim, headings and anchors intact.

## Preview

Three `@Preview` composables (re-cut in #731 from the prior six flat/discussion previews), all
`widthDp = 412`:

- `ChannelListScreenTreePreview` (`@Preview(name = "Tree — Light", heightDp = 900, …)`) — a private
  `previewHostState(now)` builds two hosts (`"pyrybox"` / named, `"macbook"` / nameless) with channels
  and chats, one collapsed host (`TreeFoldKey(Host, "macbook")`) and one selected conversation.
  It shows the host-first hierarchy, direct rows and Channels plus.
- `ChannelListScreenTreeDarkPreview` — same data, dark theme (`uiMode = Configuration.UI_MODE_NIGHT_YES`).
- `ChannelListScreenEmptyPreview` (`@Preview(name = "No hosts — Light", …)`) — `hostState = HostChannelListState()`
  (no hosts). Renders the list's own bar above the centred empty-state copy — the tree's one blank state,
  and (since #738) the screen's only remaining placeholder path.

All three previews render the list's own bar since #737 and were compared against the Figma screenshot of
node `133-259` before that PR.
The separate `TreeRowsPreviewMatrix` in `ConversationTreeRows.kt` still shows the old global header and
workspace row; use the screen previews for the current hierarchy until that component preview is updated.

The `Loading` / `Error` previews #45's rationale used to justify skipping stayed unpreviewed through their
whole life and retired with the flat state itself (#738); the screen's transient states now have no visual
distinction from the tree's own blank at all — see the next section.

## Edge cases / limitations

- **The tree's only blank state is zero hosts.** A host with a snapshot but no channels or chats still draws
  its own `TreeHostRow` and empty Channels and Chats sections — that is content, not an empty screen. Only
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
- **Create chat failure is transient.** The [timed Error pill](#create-chat-failure-notice-1748)
  supplies the error affordance; retry is another Chats-plus tap, with no action on the notice.
  No `flowOn(Dispatchers.IO)` is added; see [ChannelListViewModel](channel-list-viewmodel.md).
- **Press-elevation animation is lost** on the manual-`Surface` construction `ChannelListFab` pioneered and
  `TreeRowControl` inherits (#221, #738, #744) — unchanged; the `combinedClickable` default ripple covers
  the feedback gap.
- **Screen test coverage.** `ChannelListScreenTest` (`app/src/sharedTest/.../list/ChannelListScreenTest.kt`)
  builds a hand-crafted multi-host `HostChannelListState` and asserts, among others: each host appears once
  with direct, source-ordered conversation rows; folding a host hides its sections and descendants, while
  folding one section hides only its own rows; a conversation row emits
  `TreeRowTapped` carrying *its own* host's `serverId`, not a selected one (the wrong-host regression,
  expressed as a failing assertion first); exactly the row matching `selected` asserts selected via
  `assertIsSelected` / `assertIsNotSelected` (a semantics read, not a colour read); each row carries its
  section's test tag; a tree far taller than the viewport reaches its last row via
  `performScrollToNode(hasScrollAction())`; and a nameless host and a nameless conversation render their
  fallback labels.
  `listBar_drawsMenuAndPairingAndNoneOfTheRetiredChrome_onEveryDraw` walks one composition through the empty
  placeholder and tree. It checks both toolbar controls, 44dp targets, the 6 × 24dp menu glyph, 24dp plus
  and outer 20dp visual-frame gutters, plus absence of the retired Settings/Archive buttons and titles.
  The tall-tree test also compares toolbar bounds and uses menu → Settings/Archive after scrolling.
  Menu tests cover row order, event routing, touch edges, outside taps over pairing without tap-through,
  Back, recomposition retention and anchor conversion with a nonzero screen origin. After a pointer tap
  reopens the menu, assert that its rows are displayed before Espresso Back: the tap can return before
  the new BackHandler composes (#1665).
  A same-window scrim consumes taps but leaves underlying tree labels in the semantics tree. Scope menu
  row selectors to action rows: a host or conversation named “Settings” or “Archive” can otherwise make
  a global text matcher ambiguous. The verifier identified this remaining E2E helper limitation on
  [PR #1743](https://github.com/pyrycode/pyrycode-mobile/pull/1743).
  `ChannelListColoursTest` measures the sole toolbar divider in light and dark themes: 1dp thickness, 20dp
  gutters and 24dp to the first host, including list padding. Measuring only a padding constant would
  miss extra space contributed by the list or row.
  `emptyState_rendersPlaceholder_whenThereAreNoHosts` checks the literal pairing guidance, absence of the
  retired plus instruction, the pairing control emitting `PairHostTapped` and Settings emitting `SettingsTapped`.
  Reading the expected copy from the same string resource would also pass with guidance pointing to a
  missing control (#1169).
  `chatsSectionAddControl_targetsItsOwnHost` drives a two-host tree's **second** Chats plus with colliding
  conversation ids and checks its host-specific name and emitted target;
  `chatsSectionAddControl_doesNotFoldTheRowItSitsIn` proves the plus keeps its own click action.
  `emptySecondHostHasItsOwnChatsCreateControlWithoutFolding` covers a host with no conversations.
  `addWorkspaceModal_drawsOnItsStateAndGatesOkOnSelectionAndItsHost`
  (#904, replacing the retired `workspacePicker_drawsExactlyWhenItsTargetIsSet`) draws exactly while
  `hostState.addWorkspace` is set, asserts OK stays disabled with no selection and while the modal's own
  host is disconnected and enables once both hold, and asserts a tapped recent row reports its raw
  (unclamped) path rather than the displayed, clamped text. `addWorkspaceModal_showsACreatedSelectionAndReportsANewFolderAndStaticFailures`
  covers a selection absent from `addWorkspaceRecent` drawing in its own section, the new-folder entry
  reporting a trimmed name without disturbing the selection, and each failure flag showing its own static
  string.
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

  `ChannelListScreenTest` gained (#1021): `editChannelModal_muteRowOpensAtTheHostsFlag_andOkReportsTheToggledValue`
  asserts the Mute notifications row is the checkbox role at the 48dp touch floor, opens `isOn` for a
  muted `savedMuted`, and that OK reports the toggled value on `ChannelEditSubmitted.muted` — including
  after a `failed` state change, which keeps the operator's toggle where they left it rather than
  reverting to the opening value; `editChannelModal_muteRowOpensUncheckedForAnUnmutedChannel` covers the
  unmuted open. Every existing `ChannelEditSubmitted(...)` expectation in this file gained `muted = false`,
  and the prompt-redaction assertion still passes with the widened event.

## Related

- Ticket notes: [`../codebase/46.md`](../codebase/46.md), [`../codebase/21.md`](../codebase/21.md),
  [`../codebase/22.md`](../codebase/22.md), [`../codebase/23.md`](../codebase/23.md),
  [`../codebase/26.md`](../codebase/26.md), [`../codebase/68.md`](../codebase/68.md),
  [`../codebase/69.md`](../codebase/69.md) (inline recent-discussions section — retired by #731),
  [`../codebase/99.md`](../codebase/99.md), [`../codebase/162.md`](../codebase/162.md),
  [`../codebase/221.md`](../codebase/221.md) (FAB long-press → `WorkspacePicker` — the button itself
  retired by #738, the picker wiring it originated carried forward as the host row's long-press until
  #904 replaced that one use with [`AddWorkspaceModal`](mobile-modal-callers.md#callers))
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
  `docs/specs/architecture/827-rename-chat-from-tree-row.md`,
  `docs/specs/architecture/904-add-workspace-modal.md`,
  `docs/specs/architecture/905-edit-and-archive-workspace.md`,
  `docs/specs/architecture/878-tree-conversation-attention-dot.md`
- Upstream: [ChannelListViewModel](./channel-list-viewmodel.md) (`hostState` producer — fold/selection state,
  `onHostRowTapped`, `onFoldToggled`, `createChat`,
  and the retained `openAddWorkspace`,
  `selectAddWorkspaceFolder`, `createAddWorkspaceFolder`, `submitAddWorkspace`, `dismissAddWorkspace`
  (replacing `openHostWorkspacePicker`, `pickHostWorkspace`, `dismissHostWorkspacePicker`), since #744
  `openHostEditor`, `submitHostName`, `dismissHostEditor`, and since #745 `requestHostUnpair`,
  `confirmHostUnpair`, `declineHostUnpair`, and since #827 `openChatEditor`, `submitChatName`,
  `dismissChatEditor`, `isHostConnected`, and since #905 `openWorkspaceEditor`, `submitWorkspaceName`,
  `requestWorkspaceArchive`, `confirmWorkspaceArchive`, `declineWorkspaceArchive`,
  `dismissWorkspaceEditor`; the compatibility `state` producer, `onEvent`
  reducer and `navigationEvents` this screen once also consumed retired with the button in #738), [Tree
  rows](channel-list-screen-how-it-works.md#tree-rows-730) (`TreeHostRow` / `TreeHostSectionRow` / `TreeConversationRow`,
  #730; `TreeRowControl` since #738, renamed from `TreeAddControl` in #744), [`EditHostModal`](mobile-modal-callers.md#callers)
  (#743's shell content, driven by this screen since #744), [`EditChatModal`](mobile-modal-callers.md#callers)
  (#826's shell content, driven by this screen since #827), [`AddWorkspaceModal`](mobile-modal-callers.md#callers)
  (#904's shell content, replacing this screen's own use of [WorkspacePicker](./workspace-picker.md#consumers)),
  [`EditWorkspaceModal`](mobile-modal-callers.md#callers) (#905's retained folder editor, no longer opened from a
  tree row), [`HostWorkspaceGroup`](channel-list-viewmodel-projection.md)
  (#729's workspace projection, still available to other consumers), [ConversationAvatar](./conversation-avatar.md),
  [Navigation](./navigation.md), [Dependency injection](./dependency-injection.md)
- Downstream: #737 (done — draws the list's own settings + archive bar in the `topBar` slot this section
  describes; #740, done, added the rung-3 scenario reaching Archived through the list's own archive entry
  rather than Settings' — see [Interactive stream e2e](../../e2e-interactive-stream.md#what-rung-3-is-made-of)),
  #738 (done — the remaining half of #732's split; retired the FAB and
  the compatibility `ChannelListUiState` placeholders #731 deliberately kept, and gave the list its own
  section-header and host-row add controls), #744 (done, split from #642 — the host row's edit control and
  the rename path this section describes), #745 (done, split from #642 — wires `Unpair host` behind a
  confirmation, this section's own [Host row edit control](channel-list-screen-tree-and-controls.md#host-row-edit-control-744)), #904 (done, split
  from #664 — moves the host row's long-press from the `WorkspacePicker` sheet into
  [`AddWorkspaceModal`](mobile-modal-callers.md#callers), this section's own [Add controls](channel-list-screen-tree-and-controls.md#add-controls-738)), #905 (done, split
  from #664 — every workspace row's own pencil, opening [`EditWorkspaceModal`](mobile-modal-callers.md#callers) on
  that row's own host and exact `cwd` to call the `renameWorkspace` / `archiveWorkspace` repository methods
  #663 added; #664's other half, adding a workspace, already landed as #904's host-row long-press, above),
  #878 (done, split from #668 — draws each row's `ConversationAttention` (#877) as the leading dot's fill
  and content description, this section's own [Attention dot](channel-list-screen-tree-and-controls.md#attention-dot-878)),
  #676 (closed 2026-09-25 — the live
  emulator scenario for #744's rename flow, #745's removal, #715's two-host archive/restore case,
  #905's rename/archive flow and #878's attention states; #905's rename/archive flow is now proven
  live by #1087's `interactiveTurn_addRenameArchiveWorkspace_roundTripsThroughTheHost`, see
  [Live mode](../../e2e-interactive-stream.md#live-mode-rung-3-live-relay)), #668
  (indicator-pair live accuracy, conversation-row unread/activity state — #878 split off drawing the
  state; #668 remains open for the rest), #665 (conversation-row edit
  pencil), #675 (disconnected-host
  repair control), #154 / Phase 3 Settings / Phase 4 items predating #731 remain as recorded in
  [`../codebase/`](../codebase/) history.
