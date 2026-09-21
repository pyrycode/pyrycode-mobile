# #731 — Assemble the mobile conversation tree and open the right host

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` → `ChannelListScreen`,
  `ChannelListEvent`, `RecentDiscussionsSection`, `SeeAllDiscussionsRow`, `ChannelsSectionHeader` — the
  surface this slice replaces; the chrome (`ChannelListFab`, `TopAppBar`, `WorkspacePicker`) it keeps.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` → `HostChannelListState`,
  `HostChannelListEntry`, `HostConversationTarget`, `onHostRowTapped`, `sendHostDiscussion` — the host-qualified
  state and the navigation channel the row taps now reach directly.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/HostWorkspaceGroup.kt` → `HostWorkspaceGroup`,
  `HostConversationRow`, `groupConversationsByWorkspace` — #729's projection; `(serverId, cwd)` is the group
  identity and `displayName` is text only. The fold key reuses exactly that identity.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRows.kt` → `TreeSectionHeader`,
  `TreeHostRow`, `TreeWorkspaceRow`, `TreeConversationRow` — #730's stateless rows. They carry their own tree
  indent and no gutter; `TreeConversationRow` already draws the selected fill and takes a `modifier`.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → the `Routes.CHANNEL_LIST` composable, `Routes.thread`,
  `openThread`, `HostWorkspaceRepository` — where `ChannelListEvent.RowTapped` currently resolves its host
  through `ThreadDestinationFactory.selectedServerId()`, the wrong-host bug this slice removes.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt` → `HostConversationSnapshot` — `displayName`
  is nullable, `channels`/`chats` are the active, unarchived rows this tree draws.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/DiscussionPreviewRow.kt` → the
  `conversation.name?.takeIf { it.isNotBlank() } ?: untitled_discussion` fallback convention, mirrored here.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt` →
  `arriveInSeededThread` — waits for `SEED_CHANNEL_NAME` text on the list and taps it. Nothing may start folded.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` →
  `interactiveTurn_saveAsChannel_promotesToChannelTier` — step 8's drilldown hop disappears with the
  recent-discussions section; the tier read moves onto the assembled list.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreenTest.kt` → the flat-list
  render/tap tests this slice replaces, and the chrome tests it keeps.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/HostChannelListViewModelTest.kt` → its `fixture()`
  (two hosts, `Host` and `host`) and `row()` helper — the fold and selection tests reuse both.
- `docs/knowledge/features/channel-list-screen.md` § "Tree rows (#730)" — "choosing what a nameless host reads
  as is #731's call"; `docs/knowledge/features/channel-list-viewmodel-projection.md` § "State projection" —
  the group-key rule and the "never flatten rows across hosts" constraint.
- `docs/e2e-interactive-stream.md` § "Constraints" — tolerant presence/absence matchers only, never a delta
  count or a timing assertion.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8 (mobile Sidebar adaptation
`133-259`)

A single scrolling column inside a 20dp horizontal gutter: a section header ("Channels"), then one host
container per host — the host row, its workspace rows, and each workspace's conversation rows — with 16dp
between host containers and 12dp between the header and the first host. A 1px `outlineVariant` rule with
~28dp of air on each side separates that section from the second one ("Chats" — the screenshot repeats
"Channels" as sample content). One conversation row is drawn in the selected fill, one host is drawn
collapsed, and the row-level indentation, glyphs and type scale are #730's, already shipped.

## Context

The channel list still renders the flat `ChannelListUiState.channels` plus an inline recent-discussions
section with a "see all" link into `Routes.DISCUSSION_LIST`. #729 landed the grouped state
(`HostChannelListEntry.channelGroups` / `chatGroups`) and #730 landed the row composables; neither has a
consumer. This slice assembles them, owns the fold and selection state, and rewires the row tap.

The rewiring cannot be deferred. Every row tap today resolves its host through
`ThreadDestinationFactory.selectedServerId()` — one host for the whole screen. The moment the tree draws rows
from several hosts, that adapter opens the wrong host's conversation. `Routes.thread` already takes a
`HostConversationTarget` and `ChannelListViewModel.onHostRowTapped` already accepts one, so the fix is to hand
each row its own target instead of asking the adapter.

**Sizing — the floor beats the ceiling.** The refiner estimated ~820 lines of total written work against an
800-line ceiling, and my own sketch lands in the same band. The work is not split anyway: the only seam is
"render the tree" / "give rows their own host target", and the first without the second lands a wrong-host bug
on `main` that no verifier of the render slice could catch on its own. That is the one-consumer floor case —
the second slice's only consumer is the first — so the ticket is built whole, with the overage stated here.

No ADR is warranted: this is an assembly of two already-recorded decisions (#729's group identity, #730's row
contract), not a new one.

## Design

Three production files change: `ChannelListScreen.kt`, `ChannelListViewModel.kt`, `MainActivity.kt`, plus two
new strings in `res/values/strings.xml`.

### New state types (in `ChannelListViewModel.kt`, beside `HostChannelListState`)

```kotlin
enum class ConversationTreeSection { Channels, Chats }

/** A foldable node: a host row when [cwd] is null, that host's workspace row otherwise. */
data class TreeFoldKey(val section: ConversationTreeSection, val serverId: String, val cwd: String? = null)
```

The key is the ([section], `serverId`, exact `cwd`) triple, never a display name — #729's rule, extended by
`section` because the design draws the same host in both sections and the two must fold independently. `cwd` is
compared exactly, as `groupConversationsByWorkspace` produced it.

### Fold and selection state (`ChannelListViewModel`)

Two private `MutableStateFlow`s fold into the existing `hostState` combine, which grows from two sources to
four:

- `collapsedKeys: MutableStateFlow<Set<TreeFoldKey>>` — **collapsed** keys, not expanded ones, so the empty
  initial set means "every host and every workspace expanded" with no reconciliation when a host or workspace
  arrives later. `onFoldToggled(key)` adds or removes.
- `lastOpenedTarget: MutableStateFlow<HostConversationTarget?>` — set synchronously in `onHostRowTapped` before
  the channel send, and in `sendHostDiscussion` before its own send so a freshly created discussion is the
  highlighted row on return. It is *last opened from this list*, not *currently open*: the phone shows the list
  and the thread as separate destinations.

`HostChannelListState` gains `collapsed: Set<TreeFoldKey>` and `selected: HostConversationTarget?`. Both live in
the ViewModel, so they survive recomposition, `LazyColumn` recycling, an incoming snapshot emission, and the
thread round trip (the channel-list `NavBackStackEntry` owns the ViewModel and stays on the back stack).

### The tree (`ChannelListScreen`)

`ChannelListScreen` takes `hostState: HostChannelListState` alongside the existing `state: ChannelListUiState`
and `onEvent`. `ChannelListEvent` gains `TreeRowTapped(target: HostConversationTarget)` and
`TreeFoldToggled(key: TreeFoldKey)`, and loses `RowTapped` and `RecentDiscussionsTapped` — the two composables
that emitted them are gone, and an event nothing can emit is dead code.

The body is one `LazyColumn` — no nested scroll container anywhere — with
`contentPadding = PaddingValues(horizontal = TreeGutter, bottom = …)`: the gutter is the list's, per the design
and #730's note, and the bottom inset keeps the FAB off the last row. A private
`LazyListScope.conversationTreeSection(section, titleRes, hosts, collapsed, selected, onEvent)` emits, per
section: the `TreeSectionHeader` item, then per host a `TreeHostRow` item, then — unless the host's key is
collapsed — per workspace group a `TreeWorkspaceRow` item and, unless *its* key is collapsed, one
`TreeConversationRow` per conversation. Both sections plus a `HorizontalDivider` item between them are emitted
into the same `LazyColumn`.

- **Item keys** are built by a private `treeItemKey(vararg parts: String)` that length-prefixes each part, so no
  daemon-authored `serverId`, `cwd` or conversation id can collide with another row's key by embedding the
  separator (a `LazyColumn` duplicate key is a crash, and `LiteralScreenNavigationTest` already exercises a
  `serverId` full of punctuation).
- **Display text.** A host reads `displayName?.takeIf { it.isNotBlank() } ?: R.string.unnamed_host` — the
  nameless-host fallback #730 left to this slice, mirroring `DiscussionPreviewRow`'s `untitled_discussion`
  convention, so a nameless host never draws as a blank row and no opaque `serverId` reaches the UI. A
  conversation reads the same `name`-or-`untitled_discussion` rule. A workspace reads
  `HostWorkspaceGroup.displayName` verbatim. All three stay display text: the tap target is built from
  `serverId` + `conversation.id`, the fold key from `serverId` + `cwd`.
- **Row target.** Each conversation row's `onClick` emits `TreeRowTapped(HostConversationTarget(row.serverId,
  row.conversation.id))` — the row's *own* host, taken from `HostConversationRow`, never from the adapter.
  `selected = target == hostState.selected`, so at most one row across both sections is highlighted (a
  conversation belongs to exactly one host, one section and one workspace group).
- **Tier tag.** Each conversation row carries `Modifier.testTag(...)` naming its tier —
  `TREE_CHANNEL_ROW_TEST_TAG` / `TREE_CHAT_ROW_TEST_TAG`, `internal const` in the screen file. The two tiers are
  visually identical by design, so no production string distinguishes them; this is the handle the promote
  scenario's tier read needs (see Testing strategy). `testTag` is already a production-side mechanism in this
  codebase (`ScannerScreen`'s reticle and hint) and is invisible to TalkBack.
- **Placeholders.** `Loading`, `Error` and the empty copy stay exactly as they are and render whenever
  `hostState.hosts` is empty — a paired operator with a host but no conversations sees host rows, which is
  content, not a blank screen; an operator with no hosts at all still gets the flat state's placeholder. The top
  bar, the FAB (including its long-press and the `WorkspacePicker` it opens) and their content descriptions are
  untouched; #732 owns their removal.

Previews are re-cut to the design: a two-host, two-workspace tree with one collapsed host and one selected row,
light and dark, plus the retained empty state.

### Wiring (`MainActivity`)

The `Routes.CHANNEL_LIST` composable passes `hostState` into the screen and maps the two new events to
`vm.onHostRowTapped(event.target)` and `vm.onFoldToggled(event.key)`, dropping the `RowTapped` branch that
called `destinations.selectedServerId()` and the `RecentDiscussionsTapped` branch that navigated to
`Routes.DISCUSSION_LIST`. The FAB paths keep `selectedServerId()`; `Routes.DISCUSSION_LIST` and
`DiscussionListScreen` stay in place, unreachable, as the ticket directs.

## State + concurrency model

No new coroutines and no new scope. `collapsedKeys` and `lastOpenedTarget` are `MutableStateFlow`s mutated on
the caller's thread from `onFoldToggled` / `onHostRowTapped` (both Main, both synchronous) and read by the
existing `hostState` combine, which keeps `stateIn(viewModelScope, WhileSubscribed(5_000),
HostChannelListState())`. `sendHostDiscussion` keeps its existing `launchGuardedRepoCall` path and sets
`lastOpenedTarget` on the same coroutine that sends to `hostNavigationChannel`. The screen collects `hostState`
through the existing `collectAsStateWithLifecycle` in `MainActivity`; the `LazyColumn` holds no state of its own
beyond its scroll position.

## Error handling

Nothing new can fail: the tree is a pure projection of already-derived state. A host whose snapshot has not
arrived yet contributes an empty subtree, a host with no conversations draws a host row and no workspaces, and
a nameless host draws the fallback label. Repository errors keep reaching the existing
`ChannelListUiState.Error` placeholder. Fold toggles log a content-free `RelayLog.d` line (event name and the
resulting expanded flag — no `serverId`, `cwd` or name).

## Testing strategy

**Unit (`HostChannelListViewModelTest`, reusing its two-host `fixture()`):**

- Every host and every workspace is expanded on first emission; toggling a host key collapses only that host,
  and the same host's key in the other section stays expanded.
- Fold state is keyed by `(section, serverId, cwd)`: two hosts holding the same `cwd` fold independently, and a
  snapshot that changes only `displayName` / `workspaceLabel` leaves the collapsed set and the rendered groups
  untouched.
- `onHostRowTapped` records the target as `selected` and a later snapshot emission does not clear it; a second
  tap replaces it.

**Instrumented (`ChannelListScreenTest`, stateless screen over a hand-built `HostChannelListState`):**

- Both sections render their host, workspace and conversation rows; the seeded rows are all visible with no
  fold applied (the property the scripted device suites depend on).
- Folding a host hides its workspaces and their conversations; folding a workspace hides only its own
  conversations and leaves the sibling workspace's rows visible.
- Tapping a conversation row that belongs to the *second* host emits `TreeRowTapped` with that host's
  `serverId` — the wrong-host regression, expressed as a failing assertion first.
- The row matching `selected` asserts selected and the others assert not selected (the rows use
  `Modifier.selectable`, so this is a semantics read, not a colour read).
- A tree far taller than the viewport scrolls to its last row in one container (`performScrollToNode`).
- A nameless host renders the fallback label; the empty placeholder still renders with no hosts; the existing
  chrome tests (title, settings, FAB) keep passing unchanged.

Navigation itself is not re-proven here — the scripted gate drives tap-to-thread end to end.

**Device (rung 4, unchanged):** the seven scripted scenarios reach the seeded channel through
`arriveInSeededThread`, which waits for the seeded name and taps it. Nothing starts folded, so they keep
working; `scripted stream` is run locally as the representative scenario.

**Device (rung 3, repaired):** `interactiveTurn_saveAsChannel_promotesToChannelTier` loses its drilldown hop.
Its step 8 becomes a tier read on the assembled list: the unique name is present on a node carrying
`TREE_CHANNEL_ROW_TEST_TAG`, and absent from every node carrying `TREE_CHAT_ROW_TEST_TAG`. That is strictly
stronger than the old absence-in-the-drilldown, and stays inside the ladder's Constraints — presence/absence
with generous timeouts, no delta count. The docstring paragraph justifying the drilldown is replaced by one
recording why the tiers need a tag at all (the two tiers instance the same component and share every string).
`#566`'s stale aside about the removed "Recent discussions" section header is corrected in the same pass. The
live suite is the dispatcher's to run after the verifier; `needs-real-claude` stays on the issue.

## Documentation handoff

Pending for the documentation stage — no file under `docs/knowledge/` is touched by this slice.

- `docs/knowledge/features/channel-list-screen.md`: **replace** (not append — the file is ~44.6 KB against
  `scripts/docs-guard.sh`'s 50 KB ceiling) its flat-list and recent-discussions description with the assembled
  tree: the two sections, the single `LazyColumn` and its gutter, the fold state keyed by
  `(section, serverId, cwd)`, the last-opened selection, the nameless-host fallback, the host-qualified row
  target, and the per-tier test tags. § "Tree rows (#730)" should note its "nothing consumes them yet" line is
  now stale.

## Open questions

- Whether the section-tier `testTag` should instead become a production content description once #668 gives
  conversation rows real status semantics. Resolve by leaving the tag alone in this slice and noting it in the
  PR's lessons.
- Exact vertical rhythm between the section header, the host containers and the divider — the design's
  12/16/28dp are transcribed, then checked against the screenshot from a preview before the PR opens.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries] SHOULD FIX — `treeItemKey` is a new, unbounded path for daemon-authored text.** Host
  `displayName`, workspace `displayName` and conversation `name` all reach the UI through #730's
  `boundedRowText`, which clamps them before layout or a formatted description, and `workspaceDisplayName`
  clamps the workspace label to `MAX_WORKSPACE_LABEL_CHARS` upstream of that. The `LazyColumn` item keys are
  the exception: they concatenate the raw `serverId`, the exact `cwd` and the raw conversation id, and the
  `item(key = …)` arguments are evaluated eagerly on every recomposition of the list content. A daemon frame
  carrying a multi-megabyte `cwd` is handled gracefully by every shipped render path (a 128-character `take`)
  but would be *copied per recomposition* by the key builder — the one place in this design where oversized
  daemon text amplifies instead of truncating. No inbound frame-size cap exists in `data/network/` to fall back
  on, and `WorkspaceUpdatedPayloadDto` deliberately does not re-enforce the daemon's own label limit. Phase B
  therefore bounds each key part to a fixed prefix and appends the part's full length, inside the
  length-prefixed join. Uniqueness is unaffected for well-formed input: a collision would need two ids sharing
  a 256-character prefix *and* an identical length, and a daemon able to forge that could simply send two
  conversations with the same id, which already crashes the keyed list on `main` today. The bound removes a new
  memory-exhaustion vector without adding a new crash class.
- **[Trust boundaries] No further findings.** `cwd` is compared and keyed, never opened, resolved or rendered;
  `serverId` and conversation ids reach only `HostConversationTarget`, which `Routes.thread` already
  percent-encodes. Daemon text reaches a `Text` node and #730's formatted fold description, nothing else — no
  WebView, no markup, no URL, no filename, no log field. The tap target is built from identities, never from a
  display name, so a rename cannot retarget a row.
- **[Tokens, secrets, credentials] No findings.** This slice reads no credential and writes none. Fold state
  and the last-opened target hold a `serverId` and a `conversationId` — identifiers, not secrets — and live
  only in ViewModel memory; nothing is persisted, so no new at-rest surface and no backup exposure.
- **[File / storage operations] No findings** — no filesystem access of any kind. The `cwd` is a grouping key
  and a display source, never a path the app opens, so no traversal or TOCTOU surface exists to canonicalise.
- **[Inter-process / Android attack surface] No findings** — no new exported component, intent filter, deep
  link, `PendingIntent`, content provider, push handling or WebView. The only new navigation is the existing
  in-process `Routes.thread` route, reached through the existing `hostNavigationChannel`.
- **[Cryptographic primitives] Not applicable** — no randomness, no key material, no comparison against a
  secret. Row identity comparison (`target == hostState.selected`) is between two non-secret identifiers, so
  constant-time comparison is not required.
- **[Network & I/O] No findings** — a pure projection of state the existing `HostConversationSource` already
  collects. No socket, timeout, TLS or reconnect behaviour changes; nothing new is sent to the daemon.
- **[Error messages, logs, telemetry] SHOULD FIX — the new fold log line must stay content-free.**
  `onFoldToggled` logs one `RelayLog.d` line; it carries the event name and the resulting expanded flag only —
  never `serverId`, `cwd`, a display name or the built item key. No telemetry is added, and the tier test tags
  are static app-authored literals.
- **[Concurrency] SHOULD FIX — mutate `collapsedKeys` with `update {}`, not a read-then-assign.** Toggling is
  read-modify-write on a `MutableStateFlow`; both call paths are Main-thread today, but `update {}` is free and
  removes the check-then-act shape. No new coroutine, scope or cancellation path is introduced, and
  `lastOpenedTarget` is a single atomic assignment whose last-writer-wins semantics are the intended ones. The
  collapsed set is deliberately *not* pruned against incoming snapshots: pruning would silently unfold a host
  that momentarily disappears during a reconnect, contradicting AC-2, and growth is bounded by user taps (one
  entry per fold) holding references to strings the process already retains.
- **[Threat model alignment] Named and addressed, one accepted.** A *malicious relay* stays content-blind and
  on-path: dropping or delaying frames leaves stale or empty subtrees and adds no new hang, because this slice
  introduces no blocking call. A *hostile daemon frame* is covered by the first finding plus #730's clamp. *UI
  leakage is the accepted one*: the assembled tree shows every host's full channel and chat names at once,
  where the flat list showed channels plus three recent discussions, so screenshot, overlay and
  accessibility-eavesdropping surface grows with the product decision this ticket implements. The app has no
  `FLAG_SECURE` policy today and adopting one is out of scope here — it belongs with the list's own screen
  ownership (#732) or a dedicated privacy ticket, not with this assembly.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-21
