# ChannelListScreen

Stateless `(hostState, onEvent)` composable that renders a Material 3 `Scaffold` with the list's own
top bar (a settings entry and an archive entry above a rule, #737 — see
[The list's own top bar](#the-lists-own-top-bar-737) below) above a single-`LazyColumn` conversation tree
(#731): a Channels section and a Chats section, each holding host rows, their workspace rows and those
workspaces' conversation rows, drawn from `hostState` (#729's `HostChannelListEntry.channelGroups` /
`chatGroups`) using the row composables from `ui/conversations/components/ConversationTreeRows.kt` (#730,
see [Tree rows](#tree-rows-730) below). The flat `ConversationRow` list and the inline "Recent discussions"
section with its "See all" link into `Routes.DISCUSSION_LIST` are gone — #731 replaced both. Row taps carry
their own `serverId`, so a tree drawing rows from several hosts opens each on the host that owns it, never
through a selected-host adapter. Fold and selection state (which nodes are collapsed, which row was last
opened from this list) live in the ViewModel, so they survive recomposition, `LazyColumn` recycling, an
incoming snapshot and the thread round trip.

The floating action button that used to create a chat and open pairing is gone (#738): each section header
now carries its own add control that opens pairing's existing scanner entry, and each host row carries one
that starts a chat on **that row's** host — see [Add controls](#add-controls-738) below. With the button
gone, the flat `ChannelListUiState` compatibility model (loading/error/empty placeholders,
`workspacePickerVisible`) retired with it: `hostState.hosts.isEmpty()` is now the tree's only blank.

Package: `de.pyryco.mobile.ui.conversations.list` (`app/src/main/java/de/pyryco/mobile/ui/conversations/list/`). File: `ChannelListScreen.kt`.

## What it does

Wraps its body in a `Scaffold` whose `topBar` is the file-private `ChannelListTopBar` (rendered in **every**
state — see [The list's own top bar](#the-lists-own-top-bar-737) below). There is no `floatingActionButton`
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
  [Conversation tree (#731)](#conversation-tree-731) and [Add controls (#738)](#add-controls-738).

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
the producer for every variant except the seven the VM's destination wiring consumes directly
(`TreeHostAddTapped`, `TreeHostAddLongPressed`, `TreeHostEditTapped`, `HostEditNameSubmitted`,
`HostEditDismissed` (#744), `WorkspacePicked`, `WorkspacePickerDismissed`);
`TreeRowTapped` / `TreeFoldToggled` / `SettingsTapped` / `ArchiveTapped` / `PairHostTapped` route through the
destination's `when (event)` instead (see [Wiring](#wiring)).

The file-private `ChannelListFab` — the manually-composed `Surface` #22 → #221 built to own tap + long-press
directly (bypassing the M3 `FloatingActionButton` widget's own inner `Surface(onClick = ...)`, which would
otherwise shadow an outer `combinedClickable` — see [`../codebase/25.md`](../codebase/25.md) and
[`../codebase/221.md`](../codebase/221.md)) — is gone (#738). The same construction lives on in
`TreeAddControl`, the control both new add controls draw; see [Add controls (#738)](#add-controls-738).

## Conversation tree (#731)

`ConversationTree(hostState, onEvent, modifier)` is a single `LazyColumn` — **no nested scroll region
anywhere** — with `contentPadding = PaddingValues(start = TreeGutter, end = TreeGutter, bottom = TreeBottomInset)`.
`TreeGutter = 20.dp` is the list's own horizontal gutter; the row composables from #730 carry only their own
tree indent and no gutter, per that ticket's note. `TreeBottomInset = 88.dp` keeps the FAB off the last row.
The private `LazyListScope.treeSection(section, hostState, onEvent)` emits one section's items — the
`TreeSectionHeader`, then per host a `TreeHostRow`, then (unless the host's fold key is collapsed) per
workspace group a `TreeWorkspaceRow` and (unless *its* key is collapsed) one `TreeConversationRow` per
conversation — and `ConversationTree` calls it once for `ConversationTreeSection.Channels`, emits a
`HorizontalDivider` item (`outlineVariant @ 0.60f`, `TreeSectionRuleGap = 28.dp` above / `TreeSectionRuleBottomGap
= 16.dp` below, matching the Figma rule between sections), then once more for `Chats` — all three into the
*same* `LazyColumn`, so the whole tree scrolls as one container and `performScrollToNode` can reach the last
row of either section. `TreeFirstHostGap = 8.dp` / `TreeHostGap = 16.dp` space the header-to-first-host and
host-to-host gaps.

**Fold key and node identity.** `TreeFoldKey(section: ConversationTreeSection, serverId: String, cwd: String? = null)`
is a host row when `cwd` is null, that host's workspace row otherwise — [`ChannelListViewModel`](channel-list-viewmodel.md)
owns the type and the collapsed set; see that document for why the key includes `section` and why `cwd` is
compared exactly. `ConversationTreeSection` (`Channels`, `Chats`) also selects the section's string resource
(`R.string.channels_section_header` / `R.string.chats_section_header`) and its row test tag (below).

**Display text and fallback.** A host reads `displayName?.takeIf { it.isNotBlank() } ?: R.string.unnamed_host`
— the nameless-host fallback #730 left open, mirroring `DiscussionPreviewRow`'s `untitled_discussion`
convention, so a nameless host or a blank-string name never draws as a blank row and no opaque `serverId`
reaches the UI. A conversation reads the same `name`-or-`untitled_discussion` rule. A workspace reads
`HostWorkspaceGroup.displayName` verbatim (already resolved by `workspaceDisplayName` upstream). All three
stay display text only: the tap target is built from `serverId` + `conversation.id`, the fold key from
`serverId` + `cwd`, so a rename cannot retarget a row or fold/unfold anything.

**Row target and selection.** Each conversation row's `onClick` emits
`TreeRowTapped(HostConversationTarget(row.serverId, row.conversation.id))` — `row.serverId` comes from
`HostWorkspaceGroup`'s own `HostConversationRow`, i.e. the row's *own* host, never from
`ThreadDestinationFactory.selectedServerId()`. `selected = target == hostState.selected`, so at most one row
across both sections is highlighted (a conversation belongs to exactly one host, one section and one
workspace group).

**Tier test tags.** Each conversation row carries `Modifier.testTag(section.rowTestTag)` —
`internal const val TREE_CHANNEL_ROW_TEST_TAG = "tree-channel-row"` /
`internal const val TREE_CHAT_ROW_TEST_TAG = "tree-chat-row"` in `ChannelListScreen.kt`. The two tiers are
visually identical by design (same component, same glyph, same type scale, no tier word on the row), so no
production string distinguishes them; the tags are the one durable handle a test has for reading a
conversation's tier off the assembled list. `testTag` was already a production-side mechanism in this
codebase (`ScannerScreen`'s reticle and hint) and is invisible to TalkBack. `InteractiveStreamE2ETest`'s
`interactiveTurn_saveAsChannel_promotesToChannelTier` reads them directly — see
[`docs/e2e-interactive-stream.md`](../../e2e-interactive-stream.md).

**Arrival marker.** The screen's own root — the `Scaffold`'s `modifier`, above the `if (hostState.hosts.isEmpty())`
branch that picks between the empty placeholder and the tree — carries
`internal const val CHANNEL_LIST_TEST_TAG = "channel-list"` (#736), so both draws carry it by
construction (four before #738 retired the flat state's `Loading`/`Error` placeholders; two since). Same
shape as the two tags above (app-authored literal, no daemon text, invisible to TalkBack), but it names the
destination rather than any chrome drawn on it: unlike the button wait it replaced, it does not imply a
loaded list, and it survived #732/#738's chrome changes without a second migration. `InteractiveStreamE2ETest`'s
shared `awaitChannelList()` helper reads it; `createChat()` and `openWorkspacePicker()` now drive a paired
host's own add control via `treeHostAddTestTag(serverId)` (below) rather than the retired button's fixed
content description — see [`docs/e2e-interactive-stream.md`](../../e2e-interactive-stream.md).

**Item keys.** A private `treeItemKey(vararg parts: String)` length-prefixes each part before joining them
with `|`, so no daemon-authored `serverId`, `cwd` or conversation id can forge another row's key by embedding
the separator — a `LazyColumn` duplicate key is a crash, not a rendering glitch. Any part over
`MAX_KEY_PART_CHARS = 256` is clamped to that prefix with its own length appended (`take(256) + "~" + length`)
before the length-prefixing, so a daemon frame carrying a multi-megabyte `cwd` or id is bounded once instead
of being copied whole on every recomposition — `item(key = …)` arguments are evaluated eagerly on every
recomposition of the list content, which is what makes this the one place in the design where oversized
daemon text amplifies instead of truncating (flagged in the ticket's security review). Uniqueness for
well-formed input is unaffected: a collision needs two ids sharing both a 256-character prefix and an
identical length.

## Add controls (#738)

Both new controls live in `ConversationTreeRows.kt` and share one file-private `TreeRowControl` (renamed
from `TreeAddControl` in #744, when the host row's edit control became its second call site): a
`Box.size(48.dp).clip(CircleShape).combinedClickable(...)` drawing a 16dp caller-supplied `icon` tinted
`colorScheme.primary`, carrying the caller's content description and (for the host row's two controls) the
caller's `testTag`. An optional `onLongClick` / `onLongClickLabel` pair makes the control drive both
gestures when supplied — the same `combinedClickable`-on-the-control-itself construction `ChannelListFab`
used, for the same reason: an M3 `IconButton` composes its own inner `clickable` that would shadow an outer
`combinedClickable`'s long-press (see [`../codebase/25.md`](../codebase/25.md),
[`../codebase/221.md`](../codebase/221.md)).

- **`TreeSectionHeader`** gained `onAddTapped: () -> Unit`. Its content description is
  `R.string.cd_tree_section_pair_host` formatted with the section's own title — app-authored, never daemon
  text, so it is not run through `boundedRowText`. Tapping it emits `ChannelListEvent.PairHostTapped`, which
  the route maps to `navController.navigate(Routes.SCANNER)` — pairing's **existing** entry, reused rather
  than a second flow. Both of that entry's completions already land back on `channel_list` (camera pops
  `SCANNER` inclusive; paste-code pops the graph), so no pop or flag is needed here. See
  [Navigation](navigation.md#manual-pairing-entry-and-return).
- **`TreeHostRow`** gained `serverId: String`, `onAddTapped: () -> Unit` and `onAddLongPressed: () -> Unit`.
  Tap emits `TreeHostAddTapped(serverId)` (the route calls `vm.createHostDiscussion(serverId)`); long-press
  emits `TreeHostAddLongPressed(serverId)` (`vm.openHostWorkspacePicker(serverId)`) — the row's **own** host,
  the same discipline `TreeRowTapped` already used for taps, never `ThreadDestinationFactory.selectedServerId()`.
  Its two content descriptions (`cd_tree_host_new_chat` / `cd_tree_host_pick_workspace`) are formatted with
  the row's already-`boundedRowText`-clamped display name — computed once and passed down, so no path formats
  an unbounded daemon-authored name into a description.

**Naming rule.** Both controls repeat down the screen — one section header per section, one host row per
host — so each has to say which section or host it acts on, the way the fold controls already name their
row (`cd_tree_row_expand` / `cd_tree_row_collapse`, formatted with the row's name). No production string
distinguishes two section headers or two host rows from each other otherwise.

**The device-suite handle.** `TreeHostRow` also builds `treeHostAddTestTag(serverId)` — a public top-level
function in `ConversationTreeRows.kt` — and attaches it to its own control's `Modifier.testTag(...)`. The id
comes from the saved `PairedServer` record the operator scanned, not a daemon frame, but a hostile QR could
still make it enormous; `treeHostAddTestTag` clamps it the same way `treeItemKey` clamps its parts —
truncated with the original length appended (`take(256) + "~" + length`) — so two ids sharing a 256-character
prefix cannot collapse onto one tag. `InteractiveStreamE2ETest`'s `createChat()` / `openWorkspacePicker()`
read it, built from the harness's own `ARG_SERVER_ID` instrumentation argument — see
[`docs/e2e-interactive-stream.md`](../../e2e-interactive-stream.md#what-rung-3-is-made-of). Sibling handles
in this codebase (`CHANNEL_LIST_TEST_TAG`, `TREE_CHANNEL_ROW_TEST_TAG` / `TREE_CHAT_ROW_TEST_TAG`) are
`internal`; this one is `public` because it is a function computed from caller-supplied input rather than a
fixed constant, and both its production caller (`TreeHostRow`, same module) and its test caller (`androidTest`,
a friend source set) already resolve `internal` — the wider visibility is not load-bearing, just consistent
with taking a parameter.

**Nesting.** The host row's control sits inside `FoldableTreeRow`'s own `clickable`, which merges descendant
semantics — but `combinedClickable` merges too, and merging stops at a merging descendant, so the control
keeps its own node, its own name, its own tag and its own click action; a tap on it never reaches the row's
fold. `ChannelListScreenTest` asserts this directly rather than trusting the inherited rule.

**The 48dp trade.** The design pins a 16dp plus with its centre 10dp from the row's content edge. Touch needs
48dp, and centring a 16dp glyph in a 48dp target lands its centre about 22dp further inboard than the design
draws it — the same trade #731 took growing the design's 28dp pointer rows to a size a thumb can hit. Taken
deliberately, recorded in a KDoc comment on `TreeRowControl` in `ConversationTreeRows.kt`. The section
header's own band grows from the design's bare 20dp text line to 48dp for the same reason — it carries a
control now, not just a label.

**Not in this slice.** `TreeWorkspaceRow` draws no add control — adding a workspace is #663's control and
\#664's content, deliberately a tier above the host row's plus. The add control's eventual modal content
(what a section header's pairing flow shows once it lands, beyond reusing the existing scanner) is #664's;
this slice supplies only the control and its target.

## Host row edit control (#744)

`TreeHostRow` gained a third parameter, `onEditTapped: () -> Unit`, and draws a second `TreeRowControl` —
`Icons.Filled.Edit`, tinted `colorScheme.primary` like the plus — between `ConnectionLegPair` and the add
control. The design's hover treatment swaps the leg dots for the pencil and the plus, in that order; the
phone has no hover, so all three are drawn persistently in that same order: dots, pencil, plus. Tap emits
`TreeHostEditTapped(serverId)` (the route calls `vm.openHostEditor(serverId)`) — the row's own host, the
same discipline the fold, tap and add controls already use. There is no long-press path: the control has
one action, so it passes neither `onLongClick` nor `onLongClickLabel` to `TreeRowControl`.

Its content description is `R.string.cd_tree_host_edit` formatted with the row's already-`boundedRowText`-clamped
display name, for the same reason the add control's two descriptions are: the control repeats down the
screen and has to say which host it acts on. `treeHostEditTestTag(serverId)` mirrors `treeHostAddTestTag`
— both now delegate to one private `boundedTagId` clamp (`take(256) + "~" + length`) so the two tags
cannot drift apart — and is attached to the pencil's own `Modifier.testTag(...)`.

Opening the modal, filling it from the host's stored pairing record, and saving the entered name are the
view model's job — see [Wiring](#wiring) below and [ChannelListViewModel](channel-list-viewmodel.md). The
modal itself, [`EditHostModal`](mobile-modal.md#callers), is unchanged by this ticket; its `Unpair host`
action was wired to an empty lambda here until #745, which gives it a confirmation-gated removal — the
modal swaps its own content in place for a prompt naming the host, and the shell's own Cancel/OK footer
carries the decision. See [ChannelListViewModel](channel-list-viewmodel.md#wiring) for the removal's three
methods, the ordering that keeps a failed store write from clearing the host's cached workspace, and the
`saving` guard that stops a decline or a second request from racing an in-flight write.

## How it works

### Stateless `(hostState, onEvent)` contract

No `viewModel()`, no `koinViewModel()`, no `LocalContext.current`, no `NavController` parameter. `hostState`
joined `onEvent` as a second parameter in #731 and became the screen's **only** state parameter in #738,
when the compatibility `state: ChannelListUiState` retired with the button it fed. `hostState` carries the
host-qualified rows, the collapsed nodes, the last-opened target and the workspace picker's target. The
canonical CLAUDE.md shape (hoist state to the ViewModel; UI receives state + `onEvent`) is unchanged.

### The list's own top bar (#737)

Replaced the M3 `TopAppBar` #21 gave the screen — the design retires the app's generic top app bar (with it,
the app name and the Pyry logo, #68) and gives the list its own chrome: a settings entry at the leading
content edge, an archive entry beside it, and a one-pixel rule closing the bar. The screen still owns its own
chrome rather than relying on a shared `TopAppBar` slot threaded through the NavHost; the outer `Scaffold` in
`MainActivity` carries system-bar insets only.

The file-private `ChannelListTopBar(onEvent)` is a `Column`: a `Row` of two 48dp `IconButton`s (`Icons.Default.Settings`
emitting `SettingsTapped`, `Icons.Default.Archive` emitting `ArchiveTapped`, each a 24dp `Icon` tinted
`colorScheme.primary` with its own `contentDescription`), then a `HorizontalDivider` in `outlineVariant` held
back to the tree's existing `SECTION_RULE_ALPHA` — the same treatment the tree's between-sections rule already
gives the design's identically-styled rectangle.

**It lives in the `Scaffold`'s `topBar` slot, not the tree's scroll container**, so it draws above the
`hostState.hosts` branch and is carried by **both** of the screen's draws — the empty placeholder and the
assembled tree (four before #738 retired the flat state's loading and error texts) — without that branch
being touched. This is what makes the "bar on every draw" requirement fall out of the structure rather than
needing to be re-proven per state.

**Geometry.** Figma's glyphs are 24dp with centres 32dp and 84dp from the screen edge, a rule 20dp below them
and 28dp of air above the first section header. Touch needs 48dp (the same minimum `TreeRowMinHeight` holds
the tree rows to), and wrapping a 24dp glyph in a 48dp `IconButton` adds `BarTouchSlack = (48dp − 24dp) / 2 =
12dp` of slack on every side of it — so each of the design's offsets is taken *less that slack*
(`BarTopGap = 24dp − 12dp`, `BarRuleGap = 20dp − 12dp`), which lands both glyph centres exactly where the
design puts them while giving each entry a full 48dp touch target. The row is inset by `TreeGutter -
BarTouchSlack` and its two entries spaced by `BarEntryGap = 4.dp` (52dp between centres, less the two 48dp
targets). A 48dp target does not have to move a 24dp glyph off its design position — for a glyph in a
fixed-size target this is arithmetic, not the trade-off the tree rows' 28/28/24 → 48dp note describes.

**Known spacing gap (verifier SHOULD-FIX, not blocking, unresolved at merge).** `BarBottomGap`, the
`HorizontalDivider`'s bottom padding, is `28.dp` — but `TreeSectionHeader` carries its own `padding(top =
12.dp)`, so the first `Channels` header actually sits **40dp** below the rule, not the design's 28dp. The file
already solves this same relationship correctly for the *between-sections* rule (`TreeSectionRuleBottomGap =
16.dp` + the header's own 12dp = 28dp) — the fix here is the same shape, `BarBottomGap = 16.dp`. Worth taking
before #738 builds more chrome on this same rule.

**Insets.** No window insets of its own, unlike the `TopAppBar` it replaced. M3's `Scaffold` gives the body a
top padding equal to the measured `topBar` height and leaves the window inset to the bar itself; the retired
`TopAppBar` applied `TopAppBarDefaults.windowInsets` on top of the status-bar padding `MainActivity`'s outer
`Scaffold` already applies to the whole `PyryNavHost` — a doubled status-bar inset that went unnoticed until
this replacement quietly removed it. Worth checking before the same `TopAppBar` → plain-`topBar` swap is made
on a screen whose outer `Scaffold` isn't already padding for it.

The `floatingActionButton` slot and the file-private `ChannelListFab` it hosted are gone (#738) — see
[Add controls (#738)](#add-controls-738) for what replaced both of its gestures, and for
`TreeAddControl`, which inherits the manually-composed-`Surface`-not-`IconButton` construction
`ChannelListFab` pioneered in #221 for the same reason.

#### `WorkspacePicker` host as Scaffold sibling (#221)

After the `Scaffold { ... }` block closes, the screen composes `WorkspacePicker(visible, onPicked, onDismiss)`
as a sibling of the Scaffold, not inside its content lambda — matching #78's `SaveAsChannelDialog` placement,
since the sheet manages its own `Popup`/`Window` above the entire activity. `visible` reads
`hostState.workspacePickerServerId != null` directly (#738) — before, this was a `when (state)` copy of the
same fact into `Loaded`/`Empty.workspacePickerVisible`, `Loading`/`Error` mapped to `false`; the direct read
is strictly more correct, since the old copy could hold a non-null picker target while the flat state was
still `Loading` and draw nothing. `onPicked(path)` dispatches `WorkspacePicked(path)`; `onDismiss` dispatches
`WorkspacePickerDismissed`.

## Tree rows (#730)

`ui/conversations/components/ConversationTreeRows.kt` supplies the four stateless composables this screen
assembles: `TreeSectionHeader`, `TreeHostRow`, `TreeWorkspaceRow` and `TreeConversationRow`, plus the
file-private `TreeAddControl` the first two now draw (#738, see [Add controls](#add-controls-738) above).
\#731 is their first and, as of this writing, only consumer — this screen's `treeSection` (above) is the call
site. The rows remain stateless and resolve nothing about which host or workspace they belong to; every
parameter is display text, a flag or a callback the caller (this screen) already resolved — `TreeHostRow`'s
new `serverId` parameter is the one exception, used only to name its own add control for the device suites,
never to resolve anything the row draws. Row-level clamping, truncation, selection-fill and
connection-indicator details are not repeated here — see `docs/specs/architecture/730-mobile-tree-rows.md`;
this document covers only how the screen assembles and drives them.

## Wiring

`PyryNavHost`'s `Routes.CHANNEL_LIST` composable resolves `ChannelListViewModel` and collects `hostState` —
the screen's only state since #738 — passing it into `ChannelListScreen`. The event `when` maps the two tree
events straight to the VM: `is ChannelListEvent.TreeRowTapped -> vm.onHostRowTapped(event.target)` and
`is ChannelListEvent.TreeFoldToggled -> vm.onFoldToggled(event.key)` — no adapter, no `selectedServerId()`
lookup, because the row already carries its own host. This is the wrong-host fix #731 landed with the render;
\#738 carried the same discipline into creation: `is ChannelListEvent.TreeHostAddTapped ->
vm.createHostDiscussion(event.serverId)` and `is ChannelListEvent.TreeHostAddLongPressed ->
vm.openHostWorkspacePicker(event.serverId)`, both against the control's own row, never
`destinations.selectedServerId()`. #744 carries the same discipline into editing: `is
ChannelListEvent.TreeHostEditTapped -> vm.openHostEditor(event.serverId)`,
`is ChannelListEvent.HostEditNameSubmitted -> vm.submitHostName(event.name)` and
`ChannelListEvent.HostEditDismissed -> vm.dismissHostEditor()`. #745 adds three more, none carrying a
`serverId` because the target is the open editor's, held in the view model:
`ChannelListEvent.HostUnpairRequested -> vm.requestHostUnpair()`,
`ChannelListEvent.HostUnpairConfirmed -> vm.confirmHostUnpair()` and
`ChannelListEvent.HostUnpairDeclined -> vm.declineHostUnpair()`. `ChannelListEvent.SettingsTapped` still
navigates to `Routes.SETTINGS`;
`ChannelListEvent.ArchiveTapped` navigates to `Routes.ARCHIVED_DISCUSSIONS` (#737) — the same argument-free
route Settings' `onOpenArchivedDiscussions` already opens, one destination reached by two doors rather than a
second route. `ChannelListEvent.PairHostTapped` navigates to `Routes.SCANNER` (#738) — no pop, no flag: the
scanner's own completions already return here (see [Add controls](#add-controls-738) above). The
`RecentDiscussionsTapped` branch that navigated to `Routes.DISCUSSION_LIST` is gone with the event, and so
are the FAB's own branches (`CreateDiscussionTapped`, `LongPressFab`) — #738 retired the button and the
`destinations.selectedServerId()` capture those two branches made; `WorkspacePicked` /
`WorkspacePickerDismissed` are unchanged and still resolve through `vm.pickHostWorkspace` /
`vm.dismissHostWorkspacePicker`. `Routes.DISCUSSION_LIST` and `DiscussionListScreen` stay in the graph,
unreachable — removing them remains out of scope.

See [ViewModel wiring](channel-list-viewmodel.md#wiring) for the Koin binding, the `hostState` combine and the
fold/selection state, and [flat-list compatibility](navigation.md#temporary-flat-list-compatibility) for the
pre-#729 adapter's one remaining consumer (`DiscussionListScreen`, unreachable) now that #738 removed this
screen's last use of it. The screen keeps its `(hostState, onEvent)` contract; ViewModels remain scoped to
their `NavBackStackEntry`, and `collectAsStateWithLifecycle()` controls screen subscriptions.

**The editor's target lives in the view model, not the screen (#744).** `ChannelListScreen` composes
[`EditHostModal`](mobile-modal.md#callers) as a `Scaffold` sibling, the same placement `WorkspacePicker`
already uses, drawn only while `hostState.hostEditor != null` and reading its `serverIdentity`,
`relayAddress`, `initialName`, `saving`, `failed` and (since #745) `confirmingUnpair` straight off that
state — no screen-local copy. The error slot resolves `unpairFailed` ahead of `failed`
(`R.string.edit_host_unpair_failed` / `R.string.edit_host_save_failed`) from an explicit flag rather than by
inferring the failing operation from `confirmingUnpair`, so the slot cannot report the wrong operation's
string after a step change. Both flags are resolved here rather than in the view model, which keeps that
free of `Context` and keeps an identity or a relay address from ever reaching the shell's live region.
`submissionEnabled` keeps the shell's default `true`: a blank name must stay submittable, since clearing it
is how a host returns to its unnamed treatment; the same flag also gates the shell's OK while confirming a
removal, so a future caller that passes `false` to block a rename would also make the destructive step
unconfirmable — a latent coupling the verifier flagged as a NIT, not addressed, worth a KDoc line next time
the file is opened. `ChannelListViewModel` owns the read (`openHostEditor`, via
`PairedServerCollectionStore.loadById`), the write (`submitHostName`, via `setDisplayName`), the close
(`dismissHostEditor`) and, since #745, the confirmation gate (`requestHostUnpair`, `declineHostUnpair`) and
the removal itself (`confirmHostUnpair`) — see [ChannelListViewModel](channel-list-viewmodel.md) for the
editor state's shape, its concurrency guard against two rows' pencils racing on the same publish, and the
removal's ordering and `saving` guard.

## Configuration

- **Dependencies:** `androidx.lifecycle:lifecycle-runtime-compose` (catalog: `androidx-lifecycle-runtime-compose`) for `collectAsStateWithLifecycle`. **Koin compose:** `org.koin.androidx.compose.koinViewModel`. **Icons:** `androidx.compose.material:material-icons-core` — `Icons.Default.Settings` / `Icons.Default.Add` / `Icons.Default.Archive` (#737, matched to the design's `box-archive-solid` FontAwesome glyph rather than vendoring a drawable, the same precedent the tree rows set); don't reach for `material-icons-extended` for single-glyph needs.
- **Strings added in #731:** `R.string.chats_section_header` ("Chats") and `R.string.unnamed_host`
  ("Unnamed host"). **Strings retired in #731:** `recent_discussions_section_header`,
  `see_all_discussions_label`, `cd_see_all_discussions` — deleted from `res/values/strings.xml` alongside the
  section they described.
- **Strings added in #737:** `R.string.cd_open_archive` ("Open archive") — the list's own archive entry,
  deliberately distinct from the Archived screen's own `archived_title` and from Settings'
  `archived_discussions_settings_row` so no existing device-suite matcher collides with it. **Strings retired
  in #737:** `cd_pyrycode_logo` — its only consumer was the retired top app bar's logo.
- **Strings retained:** `R.string.app_name` (still the manifest label; no longer rendered on this screen since
  #737), `cd_open_settings` (carried forward verbatim onto the new settings entry so
  `InteractiveStreamE2ETest`'s `CD_OPEN_SETTINGS` mirror keeps matching), `channel_list_empty`,
  `channels_section_header`, `untitled_discussion` (reused by both the tree's conversation fallback and the
  pre-existing discussion-row fallback).
- **Strings added in #738:** `R.string.cd_tree_section_pair_host` ("Pair another host, %1$s"),
  `cd_tree_host_new_chat` ("New chat on %1$s") and `cd_tree_host_pick_workspace` ("Pick a workspace for the
  new chat on %1$s") — the two add controls' content descriptions, each formatted with the section title or
  the row's already-clamped host name. **Strings retired in #738:** `cd_new_discussion` and
  `cd_long_press_fab_pick_workspace` — the retired button's two labels; nothing else in `res/values/strings.xml`
  referenced them.
- **Strings added in #744:** `R.string.cd_tree_host_edit` ("Edit host %1$s") — the edit control's content
  description, formatted the same way the add control's two are. `R.string.edit_host_save_failed`
  ("Couldn't save the host name. Try again.") lives beside `EditHostModal`'s own strings in
  `res/values/strings.xml` and is deliberately generic — it names neither the server identity nor the relay
  address, since the shell renders it verbatim into a live region.
- **Drawables:** `R.drawable.ic_pyry_logo` (since #68) — no longer used on this screen since #737 retired the
  logo along with the old bar; its only remaining consumer is [`WelcomeScreen`](welcome-screen.md).

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
  row's subtree still drawn, proving the nesting claim in [Add controls](#add-controls-738) rather than
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
  `docs/specs/architecture/745-unpair-host-from-edit-modal.md`
- Upstream: [ChannelListViewModel](./channel-list-viewmodel.md) (`hostState` producer — fold/selection state,
  `onHostRowTapped`, `onFoldToggled`, `createHostDiscussion`, `openHostWorkspacePicker`, since #744
  `openHostEditor`, `submitHostName`, `dismissHostEditor`, and since #745 `requestHostUnpair`,
  `confirmHostUnpair`, `declineHostUnpair`; the compatibility `state` producer, `onEvent`
  reducer and `navigationEvents` this screen once also consumed retired with the button in #738), [Tree
  rows](#tree-rows-730) (`TreeSectionHeader` / `TreeHostRow` / `TreeWorkspaceRow` / `TreeConversationRow`,
  #730; `TreeRowControl` since #738, renamed from `TreeAddControl` in #744), [`EditHostModal`](mobile-modal.md#callers)
  (#743's shell content, driven by this screen since #744), [`HostWorkspaceGroup`](channel-list-viewmodel-projection.md)
  (#729's workspace projection this screen iterates), [ConversationAvatar](./conversation-avatar.md),
  [WorkspacePicker](./workspace-picker.md), [Navigation](./navigation.md), [Dependency injection](./dependency-injection.md)
- Downstream: #737 (done — draws the list's own settings + archive bar in the `topBar` slot this section
  describes; #740 files the still-open follow-up, a rung-3 scenario reaching Archived through the list's own
  archive entry rather than Settings'), #738 (done — the remaining half of #732's split; retired the FAB and
  the compatibility `ChannelListUiState` placeholders #731 deliberately kept, and gave the list its own
  section-header and host-row add controls), #744 (done, split from #642 — the host row's edit control and
  the rename path this section describes), #745 (done, split from #642 — wires `Unpair host` behind a
  confirmation, this section's own [Host row edit control](#host-row-edit-control-744)), #676 (the live
  emulator scenario for #744's rename flow and #745's removal, blocked by both and still open), #715 (rebinds the
  archive entry to a specific host instead of the unscoped `Routes.ARCHIVED_DISCUSSIONS`), #668
  (indicator-pair live accuracy, conversation-row unread/activity state), #665 (conversation-row edit
  pencil), #663 (the workspace row's own add control — not #738's), #664 (the add controls' modal content,
  beyond #738's reuse of the existing pairing scanner for the section header), #675 (disconnected-host
  repair control), #154 / Phase 3 Settings / Phase 4 items predating #731 remain as recorded in
  [`../codebase/`](../codebase/) history.
