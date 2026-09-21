# ChannelListScreen

Stateless `(state, hostState, onEvent)` composable that renders a Material 3 `Scaffold` with a `TopAppBar`
(leading Pyrycode logo + app-name title + trailing settings gear, #68) and a manually-composed FAB (#22 →
\#221) above a single-`LazyColumn` conversation tree (#731): a Channels section and a Chats section, each
holding host rows, their workspace rows and those workspaces' conversation rows, drawn from `hostState`
(#729's `HostChannelListEntry.channelGroups` / `chatGroups`) using the row composables from
`ui/conversations/components/ConversationTreeRows.kt` (#730, see [Tree rows](#tree-rows-730) below). The flat `ConversationRow` list and the
inline "Recent discussions" section with its "See all" link into `Routes.DISCUSSION_LIST` are gone — #731
replaced both. Row taps carry their own `serverId`, so a tree drawing rows from several hosts opens each on
the host that owns it, never through a selected-host adapter. Fold and selection state (which nodes are
collapsed, which row was last opened from this list) live in the ViewModel, so they survive recomposition,
`LazyColumn` recycling, an incoming snapshot and the thread round trip.

Package: `de.pyryco.mobile.ui.conversations.list` (`app/src/main/java/de/pyryco/mobile/ui/conversations/list/`). File: `ChannelListScreen.kt`.

## What it does

Wraps its body in a `Scaffold` whose `topBar` is a Material 3 `TopAppBar` (rendered in **every** state) and
whose `floatingActionButton` slot hosts the file-private `ChannelListFab` (rendered only when
`state is Loaded || state is Empty`, #22; a manually-composed `Surface` rather than the M3 widget, so an
outer `combinedClickable` can own tap + long-press — see [Manual FAB](#channellistfab--manually-composed-surface-not-m3-floatingactionbutton-221)).

The body branches on `hostState.hosts`, not on `state`, now that `hostState` carries the tree's own rows:

- **`hostState.hosts.isEmpty()`** — no host has produced a snapshot yet, or there are none paired. Falls back
  to the compatibility `state`'s placeholders: `Loading` → centred `"Loading…"`; `Error(message)` → centred
  `"Couldn't load channels: $message"`; `Loaded`/`Empty` → the centred `R.string.channel_list_empty`
  ("Tap + to start a conversation") copy. This is the only blank-tree case; a paired host with a snapshot but
  no conversations still draws its own host row, which is content, not a blank screen.
- **Otherwise** — a private `ConversationTree(hostState, onEvent, modifier)` composable renders the full
  two-section tree. See [Conversation tree (#731)](#conversation-tree-731).

The `state` compatibility placeholders (loading/error/empty copy, `workspacePickerVisible`) and the FAB's
visibility gate are otherwise untouched from their pre-#731 shape; #732 retires `state`, the top bar and the
button together, along with the now-unreachable `Routes.DISCUSSION_LIST` / `DiscussionListScreen`.

## Shape

```kotlin
sealed interface ChannelListEvent {
    /** The row's own host, resolved from the row itself — never from the selected-host adapter. */
    data class TreeRowTapped(val target: HostConversationTarget) : ChannelListEvent
    data class TreeFoldToggled(val key: TreeFoldKey) : ChannelListEvent
    data object SettingsTapped : ChannelListEvent
    data object CreateDiscussionTapped : ChannelListEvent
    data object LongPressFab : ChannelListEvent
    data class WorkspacePicked(val workspace: String) : ChannelListEvent
    data object WorkspacePickerDismissed : ChannelListEvent
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChannelListScreen(
    state: ChannelListUiState,
    hostState: HostChannelListState,
    onEvent: (ChannelListEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    /* … Scaffold { TopAppBar, ChannelListFab } wrapping either the placeholder branch or
         ConversationTree(hostState, onEvent, bodyModifier); WorkspacePicker host as a
         Scaffold sibling, unchanged since #221 … */
}
```

`RowTapped` and `RecentDiscussionsTapped` are gone — the two composables that emitted them
(`ConversationRow` at the top level, `SeeAllDiscussionsRow`) no longer exist in this file, and an event
nothing can emit is dead code. `ChannelListEvent` still lives in `ChannelListScreen.kt`, not
`ChannelListViewModel.kt`: the screen remains the producer for every variant except the three the VM
consumes directly (`CreateDiscussionTapped`, `LongPressFab`, `WorkspacePicked`, `WorkspacePickerDismissed`);
`TreeRowTapped` / `TreeFoldToggled` / `SettingsTapped` route through the destination's `when (event)` instead
(see [Wiring](#wiring)).

```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelListFab(
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onTapLabel: String,
    onLongPressLabel: String,
) {
    Surface(
        modifier = Modifier
            .size(56.dp)
            .combinedClickable(
                onClick = onTap,
                onLongClick = onLongPress,
                onClickLabel = onTapLabel,
                onLongClickLabel = onLongPressLabel,
                role = Role.Button,
            )
            .semantics { contentDescription = onTapLabel },
        shape = FloatingActionButtonDefaults.shape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(imageVector = Icons.Default.Add, contentDescription = null)
        }
    }
}
```

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

## How it works

### Stateless `(state, hostState, onEvent)` contract

No `viewModel()`, no `koinViewModel()`, no `LocalContext.current`, no `NavController` parameter. `hostState`
joined `state` and `onEvent` as a third parameter in #731 — `state` still carries the compatibility
loading/empty/error placeholders and `workspacePickerVisible`; `hostState` carries the host-qualified rows,
the collapsed nodes and the last-opened target. The canonical CLAUDE.md shape (hoist state to the ViewModel;
UI receives state + `onEvent`) is unchanged, just with a second state parameter.

### `Scaffold` + `TopAppBar` chrome (#21) + `ChannelListFab` (#22 → #221)

Unchanged by #731. The screen owns its own chrome rather than relying on a shared `TopAppBar` slot threaded
through the NavHost; the outer `Scaffold` in `MainActivity` carries system-bar insets only. The `TopAppBar` is
the small/leading-aligned Material 3 default, with no scroll behaviour or navigation icon. The FAB sits at
default `FabPosition.End`; the conditional `if (state is Loaded || state is Empty)` lives inside the slot's
composable lambda because `Scaffold.floatingActionButton` is a non-nullable `@Composable () -> Unit`.

#### `ChannelListFab` — manually-composed `Surface`, not M3 `FloatingActionButton` (#221)

\#22 originally used the M3 `FloatingActionButton` widget. #221 needed a long-press gesture; wrapping the M3
FAB in an outer `Box.combinedClickable { FloatingActionButton(onClick = {}) { … } }` does **not** work — the
M3 widget composes its own inner `Surface(onClick = ...)`, which installs a `Modifier.clickable` on a leaf
node that consumes pointer events before they can propagate to the outer `combinedClickable`. Same failure
shape as #25's `ConversationRow` `ListItem` shadowing (see [`../codebase/25.md`](../codebase/25.md) and
[`../codebase/221.md`](../codebase/221.md)). The fix: a file-private `ChannelListFab` reproduces the M3
standard FAB visual (56dp `Surface`, `FloatingActionButtonDefaults.shape`, `primaryContainer` /
`onPrimaryContainer`, 6dp tonal + shadow elevation) with the `combinedClickable` directly on its modifier as
the sole pointer-input subscriber; `role = Role.Button` plus `onClickLabel` / `onLongClickLabel` make TalkBack
announce both gestures. The M3 widget's "lower elevation while held" press animation is lost and accepted as
a tradeoff — the `combinedClickable` default ripple covers the user-facing feedback gap.

#### `WorkspacePicker` host as Scaffold sibling (#221)

After the `Scaffold { ... }` block closes, the screen composes `WorkspacePicker(visible, onPicked, onDismiss)`
as a sibling of the Scaffold, not inside its content lambda — matching #78's `SaveAsChannelDialog` placement,
since the sheet manages its own `Popup`/`Window` above the entire activity. `visible` is derived from an
exhaustive `when (state)` mapping `Loaded`/`Empty` to their `workspacePickerVisible: Boolean` and
`Loading`/`Error` to `false`. `onPicked(path)` dispatches `WorkspacePicked(path)`; `onDismiss` dispatches
`WorkspacePickerDismissed`. Unchanged by #731.

## Tree rows (#730)

`ui/conversations/components/ConversationTreeRows.kt` supplies the four stateless composables this screen
assembles: `TreeSectionHeader`, `TreeHostRow`, `TreeWorkspaceRow` and `TreeConversationRow`. #731 is their
first and, as of this writing, only consumer — this screen's `treeSection` (above) is the call site. The rows
remain stateless and resolve nothing about which host or workspace they belong to; every parameter is display
text, a flag or a callback the caller (this screen) already resolved. Row-level clamping, truncation,
selection-fill and connection-indicator details are not repeated here — see
`docs/specs/architecture/730-mobile-tree-rows.md`; this document covers only how the screen assembles and
drives them.

## Wiring

`PyryNavHost`'s `Routes.CHANNEL_LIST` composable resolves `ChannelListViewModel` and collects both `state` and
`hostState`, passing both into `ChannelListScreen`. The event `when` maps the two tree events straight to the
VM: `is ChannelListEvent.TreeRowTapped -> vm.onHostRowTapped(event.target)` and
`is ChannelListEvent.TreeFoldToggled -> vm.onFoldToggled(event.key)` — no adapter, no `selectedServerId()`
lookup, because the row already carries its own host. This is the wrong-host fix #731 landed with the render:
before it, every row tap (including rows this tree would draw from a second host) resolved through
`destinations.selectedServerId()`, one host for the whole screen. `ChannelListEvent.SettingsTapped` still
navigates to `Routes.SETTINGS`; the `RecentDiscussionsTapped` branch that navigated to `Routes.DISCUSSION_LIST`
is gone with the event. The FAB paths (`CreateDiscussionTapped`, `LongPressFab`, `WorkspacePicked`,
`WorkspacePickerDismissed`) are unchanged and still resolve through `destinations.selectedServerId()` /
`pickHostWorkspace` / `dismissHostWorkspacePicker`, since the FAB has no per-row host to read from.
`Routes.DISCUSSION_LIST` and `DiscussionListScreen` stay in the graph, unreachable — removing them is out of
\#731's scope.

See [ViewModel wiring](channel-list-viewmodel.md#wiring) for the Koin binding, the `hostState` combine and the
fold/selection state, and [flat-list compatibility](navigation.md#temporary-flat-list-compatibility) for what
of the pre-#729 adapter remains (the FAB paths only, now). The screen keeps its `(state, hostState, onEvent)`
contract; ViewModels remain scoped to their `NavBackStackEntry`, and `collectAsStateWithLifecycle()` controls
screen subscriptions.

## Configuration

- **Dependencies:** `androidx.lifecycle:lifecycle-runtime-compose` (catalog: `androidx-lifecycle-runtime-compose`) for `collectAsStateWithLifecycle`. **Koin compose:** `org.koin.androidx.compose.koinViewModel`. **Icons:** `androidx.compose.material:material-icons-core` — `Icons.Default.Settings` / `Icons.Default.Add`; don't reach for `material-icons-extended` for single-glyph needs.
- **Strings added in #731:** `R.string.chats_section_header` ("Chats") and `R.string.unnamed_host`
  ("Unnamed host"). **Strings retired in #731:** `recent_discussions_section_header`,
  `see_all_discussions_label`, `cd_see_all_discussions` — deleted from `res/values/strings.xml` alongside the
  section they described.
- **Strings retained:** `R.string.app_name`, `cd_open_settings`, `cd_new_discussion`,
  `cd_long_press_fab_pick_workspace`, `channel_list_empty`, `cd_pyrycode_logo`, `channels_section_header`,
  `untitled_discussion` (reused by both the tree's conversation fallback and the pre-existing discussion-row
  fallback).
- **Drawables:** `R.drawable.ic_pyry_logo` (since #68).

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
  (no hosts), `state = ChannelListUiState.Empty(...)`. Renders the TopAppBar above the centred empty-state copy
  and the FAB — the one placeholder path #731 retains.

`Loading` and `Error` are still not previewed — transient placeholders, unchanged rationale from #45.

## Edge cases / limitations

- **The tree's only blank state is zero hosts.** A host with a snapshot but no channels or chats still draws
  its own `TreeHostRow` and no workspaces underneath — that is content, not an empty screen. Only
  `hostState.hosts.isEmpty()` falls back to the compatibility placeholders.
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
- **Press-elevation animation is lost** on the manual-`Surface` FAB (#221) — unchanged; the `combinedClickable`
  default ripple covers the feedback gap.
- **Instrumented test coverage.** `ChannelListScreenTest` (`app/src/androidTest/.../list/ChannelListScreenTest.kt`)
  builds a hand-crafted `HostChannelListState` and asserts, among others: both sections render their host,
  workspace and conversation rows with nothing folded on first show; folding a host hides its workspaces and
  their conversations while folding a workspace hides only its own rows; a conversation row emits
  `TreeRowTapped` carrying *its own* host's `serverId`, not a selected one (the wrong-host regression,
  expressed as a failing assertion first); exactly the row matching `selected` asserts selected via
  `assertIsSelected` / `assertIsNotSelected` (a semantics read, not a colour read); each row carries its
  section's test tag; a tree far taller than the viewport reaches its last row via
  `performScrollToNode(hasScrollAction())`; a nameless host and a nameless conversation render their fallback
  labels; and the retained chrome tests (`TopAppBar` title + settings gear, FAB tap, no-hosts placeholder)
  keep passing. Navigation itself is not re-proven here — the scripted device gate drives tap-to-thread end to
  end.

## Related

- Ticket notes: [`../codebase/46.md`](../codebase/46.md), [`../codebase/21.md`](../codebase/21.md),
  [`../codebase/22.md`](../codebase/22.md), [`../codebase/23.md`](../codebase/23.md),
  [`../codebase/26.md`](../codebase/26.md), [`../codebase/68.md`](../codebase/68.md),
  [`../codebase/69.md`](../codebase/69.md) (inline recent-discussions section — retired by #731),
  [`../codebase/99.md`](../codebase/99.md), [`../codebase/162.md`](../codebase/162.md),
  [`../codebase/221.md`](../codebase/221.md) (FAB long-press → `WorkspacePicker`)
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
  `docs/specs/architecture/731-assemble-conversation-tree.md`
- Upstream: [ChannelListViewModel](./channel-list-viewmodel.md) (`hostState` producer — fold/selection state,
  `onHostRowTapped`, `onFoldToggled`, `sendHostDiscussion`; compatibility `state` producer + `onEvent` reducer
  + `navigationEvents`), [Tree rows](#tree-rows-730) (`TreeSectionHeader` / `TreeHostRow` / `TreeWorkspaceRow`
  / `TreeConversationRow`, #730), [`HostWorkspaceGroup`](channel-list-viewmodel-projection.md) (#729's
  workspace projection this screen iterates), [ConversationAvatar](./conversation-avatar.md),
  [WorkspacePicker](./workspace-picker.md), [Navigation](./navigation.md), [Dependency injection](./dependency-injection.md)
- Downstream: #732 (removes the top bar, the FAB and the compatibility `ChannelListUiState` placeholders this
  slice deliberately kept; adds the section-header and host-row add controls), #668 (indicator-pair live
  accuracy, conversation-row unread/activity state), #665 (conversation-row edit pencil), #642 (host-row edit
  control), #664 (add-workspace content behind #732's control), #675 (disconnected-host repair control), #154
  / Phase 3 Settings / Phase 4 items predating #731 remain as recorded in
  [`../codebase/`](../codebase/) history.
