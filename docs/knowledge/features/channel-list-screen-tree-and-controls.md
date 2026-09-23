# ChannelListScreen — conversation tree and controls

Split out of [ChannelListScreen](channel-list-screen.md) on 2026-09-22 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [ChannelListScreen](channel-list-screen.md); see that document for the rest.

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
view model's job — see [Wiring](channel-list-screen-how-it-works.md#wiring) below and [ChannelListViewModel](channel-list-viewmodel.md). The
modal itself, [`EditHostModal`](mobile-modal.md#callers), is unchanged by this ticket; its `Unpair host`
action was wired to an empty lambda here until #745, which gives it a confirmation-gated removal — the
modal swaps its own content in place for a prompt naming the host, and the shell's own Cancel/OK footer
carries the decision. See [ChannelListViewModel](channel-list-viewmodel.md#wiring) for the removal's three
methods, the ordering that keeps a failed store write from clearing the host's cached workspace, and the
`saving` guard that stops a decline or a second request from racing an in-flight write.

## Host row reconnect control (#840)

`RelayLinkStatus.isDisconnected()` (`ConversationTreeRows.kt`, `internal`) classifies a host row's relay
leg: an exhaustive `when` with no `else`, so a case added to `RelayLinkStatus` later has to be classified
here rather than silently falling through. `Reconnecting`, `Offline`, `DaemonAbsent` and (#841)
`PairingRejected` are disconnected; `Idle` (a deliberate background close, not an error), `Connecting`
and `Connected` are not. `PairingRejected`'s reconnect control therefore reads and routes exactly like
any other disconnected host's: `reconnectHost` → `retryHost` → the supervisor's `retry()`, which dials
once and can be rejected again — the exhaustive `when` is what forced this case to be classified rather
than silently falling through to "connected".

`TreeHostRow` reads `connectionStatus.relay.isDisconnected()` and, when true, draws the design's
disconnected treatment. `FoldableTreeRow` gained an optional `accent: Color? = null` (default `null` keeps
today's `onSurfaceVariant`/`onSurface` tints); the host row passes `colorScheme.error` for both the glyph
and the name when disconnected. A fourth `TreeRowControl` — `Icons.Filled.Power`, tinted `colorScheme.primary`
like the other three — is drawn inboard of `ConnectionLegPair`, tagged `treeHostReconnectTestTag(serverId)`
(shares `boundedTagId`'s clamp with the add and edit tags). Tap emits `TreeHostReconnectTapped(serverId)`
(the route calls `vm.reconnectHost(serverId)`) — the row's own host, the same discipline the fold, tap,
add and edit controls all use. There is no long-press path, same as the edit control.

Its content description is `R.string.cd_tree_host_reconnect` ("Reconnect %1$s") formatted with the row's
already-`boundedRowText`-clamped display name, for the same reason the other three controls' descriptions
are. The row's fold chevron is unchanged and still present on a disconnected row — the whole row remains
the fold control, and removing that affordance for one state was explicitly left out of scope.

**Figma token deviation.** The frame binds the disconnected name to `Schemes/error-container`, which
renders `#ffdad6` on the light scheme's near-white surface — legible against a dark background, illegible
against light. The row uses `colorScheme.error` instead for both glyph and name, which reads as
error-toned in both schemes; this satisfies the ticket's ask for the theme's existing error colors without
adding a token. Check a Figma frame's bound color against both app themes before matching it literally —
a token that reads correctly in the design tool's own (usually dark) preview can be the wrong choice for
the light scheme.

See [Dependency injection § Exact-host Retry and lifecycle](dependency-injection.md#exact-host-retry-and-lifecycle)
for the `HostConversationSource.retryHost` seam this control drives, and
[ChannelListViewModel](channel-list-viewmodel.md#wiring) for `reconnectHost`'s routing.

## Chat row edit control (#827)

`TreeConversationRow` gained an optional fourth parameter, `onEditTapped: (() -> Unit)? = null`. A
non-null value draws a `TreeRowControl(Icons.Filled.Edit, …)` at the row's trailing edge — the design's
hover pencil, drawn permanently for the same reason the host row's is (#744). The name `Text` takes
`Modifier.weight(1f, fill = onEditTapped != null)` only while the control is present, which pushes the
pencil to the trailing edge and ellipsizes a long name before it reaches it; a row with no callback is
laid out exactly as before. `TreeRowControl` already stays its own semantics node with its own click
inside a `FoldableTreeRow`'s merging `clickable` (established for the host row's controls, above), so a
tap on the pencil never opens the row's thread or moves `selected`.

The row's own `boundedRowText(conversationName)` clamp is computed once and reused for both the `Text`
and the pencil's `R.string.cd_tree_chat_edit` content description — the same one-clamp-two-uses shape the
host row's controls use, and for the same reason: two identical accessible names on the same screen would
be indistinguishable to TalkBack.

**Only Chats rows draw it.** `treeSection` in [ChannelListScreen](channel-list-screen.md) passes
`onEditTapped` from an exhaustive `when (section)` — `null` for `ConversationTreeSection.Channels`,
`{ onEvent(TreeChatEditTapped(target)) }` for `Chats` — rather than a parameter on the section itself, so
a channel's own editor (#667) can be added later without touching this row. `target` is the row's own
`HostConversationTarget`, resolved the same way `TreeRowTapped`'s already is, never the selected host.

Opening the modal from that target, resolving which host renames it, and following that host's connection
live are the view model's job — see [ChannelListViewModel](channel-list-viewmodel.md#wiring) — and the
modal itself is [`EditChatModal`](mobile-modal.md#callers), unchanged by this ticket except for gaining
its first caller. Archive stays wired to an empty lambda here until #828, the same placeholder the host
row's Unpair action carried between #744 and #745.
