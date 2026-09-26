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
= 14.dp` below, plus the centred header's 14dp inner slack for 28dp to the label at normal font size;
see [section-label spacing](channel-list-screen-how-it-works.md#the-lists-own-top-bar-737)), then once more for `Chats` — all three into the
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
  emits `TreeHostAddLongPressed(serverId)` — the row's **own** host, the same discipline `TreeRowTapped`
  already used for taps, never `ThreadDestinationFactory.selectedServerId()`. Its two content descriptions
  (`cd_tree_host_new_chat` / `cd_tree_host_pick_workspace`) are formatted with the row's already-
  `boundedRowText`-clamped display name — computed once and passed down, so no path formats an unbounded
  daemon-authored name into a description.

**Long-press opens Add workspace, not a sheet (#904).** `TreeHostAddLongPressed(serverId)` now maps to
`vm.openAddWorkspace(serverId)`, which opens [`AddWorkspaceModal`](mobile-modal.md#callers) — the shared
`MobileModal` shell — on that row's own host rather than the bottom-sheet `WorkspacePicker` the control
opened before. Picking a recent folder or creating one there, then OK, starts an unpromoted chat in exactly
that folder on that host and opens its thread; a failure keeps the modal open instead of surfacing after a
sheet that has already closed. The control itself, its content description, its long-press affordance and
`treeHostAddTestTag(serverId)` are unchanged — only what the long-press opens moved. See
[`ChannelListViewModel`](channel-list-viewmodel.md#wiring) for `AddWorkspaceState` and its five transitions,
and [`WorkspacePicker`](workspace-picker.md#consumers) for why the thread's and Settings' pickers, reached
through other controls, are unaffected.

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

**#905 added the pencil; #958 added a plus, but only on Channels rows.** #663 (the same phase as #905) added
`renameWorkspace` / `archiveWorkspace` to the host-owned repository with no UI caller; #905 is that caller —
see [Workspace row edit and archive control (#905)](#workspace-row-edit-and-archive-control-905) below. The
host row's own plus (a tier above, opening [`AddWorkspaceModal`](mobile-modal.md#callers) in #904) still
creates an unpromoted chat in a picked or new folder; the workspace row's plus, added later, creates a
**promoted channel** directly at that row's own folder — see
[Workspace row create-channel control (#958)](#workspace-row-create-channel-control-958) below.

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
here rather than silently falling through. `Reconnecting`, `Offline`, `DaemonAbsent`, (#841)
`PairingRejected` and (#1008) `UpdateRequired` are disconnected; `Idle` (a deliberate background close,
not an error), `Connecting` and `Connected` are not. The exhaustive `when` is what forced
`PairingRejected` and `UpdateRequired` to each be classified rather than silently falling through to
"connected".

**The plug control's target diverges by state (#842).** `Reconnecting`, `Offline` and `DaemonAbsent` still
read and route through the original path: `TreeHostReconnectTapped(serverId)` → `vm.reconnectHost(serverId)`
→ `retryHost` → the supervisor's `retry()`, which dials once and can be rejected again. `PairingRejected` is
different — a retry cannot recover a rejected credential — so `ChannelListScreen`'s `TreeHostRow` call
branches on `host.connectionStatus.relay == RelayLinkStatus.PairingRejected` and emits
`ChannelListEvent.TreeHostRePairTapped(serverId)` instead; `PyryNavHost` routes that event to
`navController.navigate(Routes.pairCode(serverId))`, opening the code-pair flow scoped to that host rather
than retrying. The row's visual treatment and classification are unchanged — only the tap target differs.
See [pair-with-code target mode](paste-code-dialog.md#re-pairing-a-target-host-842).

**`UpdateRequired` gets its own control, dot and caption (#1009).** The row-level Play Store action
\#1008 deferred has landed, the way #842 changed the target for `PairingRejected`. `TreeHostRow` computes
`val update = connectionStatus.relay as? RelayLinkStatus.UpdateRequired` and, when non-null, swaps the
plug for `TreeRowControl(icon = Icons.Filled.Download, …)` tagged `treeHostUpdateTestTag(serverId)`
(shares `boundedTagId`'s clamp with the other row tags) — the update control never carries the reconnect
tag, so a test can assert "no plug" directly. Tap still reports through the row's one `onReconnectTapped`
callback, so the row itself stays agnostic; `treeSection`'s `onReconnectTapped` switch (`ChannelListScreen.kt`)
is what branches three ways now: `PairingRejected` → `TreeHostRePairTapped`, `is UpdateRequired` →
`ChannelListEvent.TreeHostUpdateTapped` (a `data object`, carrying nothing — the store listing needs no
host, and the daemon-authored minimum version must never reach an event, a link, a content description or
a log line), else `TreeHostReconnectTapped`. `PyryNavHost` maps the new event to
`LocalUriHandler.current.openUri(PLAY_STORE_URL)` — `internal const val PLAY_STORE_URL` in
`ChannelListScreen.kt`, an app-authored literal pinned to the published listing rather than derived from
`BuildConfig.APPLICATION_ID` (a debug suffix there would name a listing that does not exist; the current
debug build sets no such suffix, but the constant stays a literal on principle, not because of that build
detail). It never calls `reconnectHost`/`retry()`.

`ConnectionLegPair` gained `hostIdle: Boolean = false`, true only for `UpdateRequired`: the inboard
(host) dot then draws as a private `IdleLegDot` — `ConversationStatusDot`'s idle drawing (transparent
fill, 1dp `primary` ring), described "Pyrycode: idle" (`cd_tree_host_leg_idle`) — instead of the shared
`LegDot`/`toLegVisual` mapping. The outboard relay dot is untouched and keeps the mapping it shares with
the Settings status line.

A caption `Text` (`bodySmall`/`onSurfaceVariant`, start-aligned with the host name) sits below the row,
inside the host's own lazy item — folding the host only drops the rows below it, so the caption stays
visible while folded. It reads `tree_host_update_required_version` when `update.minClientVersion` is
non-null, else `tree_host_update_required`; this caption is the only place the version renders, already
bounded by #1008's `validMinClientVersion` shape (three 1–6-digit parts, ≤ 20 characters). The fold
chevron, and the pencil and plus controls, are unchanged — a too-old host can still be edited or
unpaired — even though the Figma frame omits the chevron; the row remains the fold control for every
disconnected state, and dropping that affordance for one state was left out of scope.

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

See [Dependency injection § Exact-host Retry and lifecycle](dependency-injection-host-conversation-source.md#exact-host-retry-and-lifecycle)
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

**Both sections draw it, each to its own modal (#827, then #667).** `treeSection` in
[ChannelListScreen](channel-list-screen.md) passes `onEditTapped` from an exhaustive `when (section)` —
originally `null` for `ConversationTreeSection.Channels` and `{ onEvent(TreeChatEditTapped(target)) }`
for `Chats` — rather than a parameter on the section itself, which is exactly what let
[Edit channel](#channels-row-edit-control-667) (#667) add the `Channels` arm later, binding
`TreeChannelEditTapped(target)` and its own `editDescription`, without touching this row's shape at all.
`target` is the row's own `HostConversationTarget`, resolved the same way `TreeRowTapped`'s already is,
never the selected host.

Opening the modal from that target, resolving which host renames it, and following that host's connection
live are the view model's job — see [ChannelListViewModel](channel-list-viewmodel.md#wiring) — and the
modal itself is [`EditChatModal`](mobile-modal.md#callers), unchanged by this ticket except for gaining
its first caller. Archive chat was wired in #828, the same placeholder-then-wire shape the host row's
Unpair action carried between #744 and #745 — but unlike Unpair, Archive takes no confirmation step,
since the host's own Archive screen restores the chat.

## Channels row edit control (#667)

`TreeConversationRow` gained a fifth parameter, `@StringRes editDescription: Int =
R.string.cd_tree_chat_edit`, generalising the pencil's content description that #827 hard-wired to the
chat string: `treeSection`'s exhaustive `when (section)` now passes `cd_tree_chat_edit` for `Chats` (as
before) and `cd_tree_channel_edit` for `Channels`, alongside `{ onEvent(TreeChannelEditTapped(target)) }`
in place of the `null` every Channels row passed until this ticket — the pencil itself, its permanent
(non-hover) drawing and its own merging-semantics node inside `FoldableTreeRow`'s `clickable` are
unchanged from #827's chat-row shape, since both tiers share one row composable. `target` is the row's
own `HostConversationTarget`, the same targeting discipline every row control in this file uses.

Opening [`EditChannelModal`](mobile-modal.md#callers) from that target, reading the channel's stored
prompt once the row's host has a live repository, and resolving which host renames, writes the prompt or
archives are `ChannelListViewModel`'s job — see [ChannelListViewModel](channel-list-viewmodel.md#wiring).
Unlike every other row control here, the modal cannot fill its second field synchronously at open: the
name comes from the row's own host snapshot the way `EditChatModal`'s and `EditWorkspaceModal`'s seeds
do, but the system prompt is a separate daemon round trip, so the field opens **disabled** with a static
reading line until that read lands. `ChannelFormFields` gained the two parameters this needs —
`promptEnabled: Boolean = true` and `promptNote: String? = null`, the note drawn as `supportingText` in
the same slot the over-limit message already used, so a caller that never passes a note is unaffected —
rather than teaching the shared form to run its own read, keeping `ChannelFormFields` itself as inert as
`EditChatModal`'s field always was. See [Mobile modal § Callers](mobile-modal.md#callers) for the full
caller contract: the target-tagged prompt reading, the `null`-until-shown prompt draft that keeps an
unread prompt from ever being overwritten, and the outlined Archive action with no confirmation step.

## Workspace row edit and archive control (#905)

`TreeWorkspaceRow` gained a fourth parameter, `onEditTapped: (() -> Unit)? = null`, and — same shape as
the host and chat rows' pencils — a non-null value draws a permanent `TreeRowControl(Icons.Filled.Edit,
…)` in `FoldableTreeRow`'s trailing slot rather than only on hover, since the phone has no hover. The
workspace name is clamped once through `boundedRowText` and reused for both the row's `Text` and the
pencil's `R.string.cd_tree_workspace_edit` content description, the same one-clamp-two-uses shape every
other row control uses. `TreeRowControl` keeps its own merging-semantics node inside `FoldableTreeRow`'s
own `clickable`, so a tap on the pencil edits the workspace without folding the row.

`treeSection` binds `onEditTapped` on every workspace row in both sections to
`{ onEvent(TreeWorkspaceEditTapped(group.serverId, group.cwd)) }` — `HostWorkspaceGroup`'s own `serverId`
and `cwd`, never `displayName`, the same targeting discipline every other row control in this file uses.
Two workspaces on different hosts can show the same folder name, so the shown name is display text only
and never the write target.

Opening [`EditWorkspaceModal`](mobile-modal.md#callers) from that target, applying the label rule and
resolving which host writes are its `ChannelListViewModel` job — see
[ChannelListViewModel](channel-list-viewmodel.md#wiring). `openWorkspaceEditor(serverId, cwd)` reads the
shown name from that host's own snapshot rather than from the row: it looks up the first channel or chat
whose `cwd` matches exactly (a `HostWorkspaceGroup` carries no label of its own to reopen with), and opens
nothing for a host or `cwd` the snapshot does not hold. OK sends one `renameWorkspace` through
`workspaceLabelFor` (`ui/workspace/WorkspaceDisplayName.kt`): the trimmed input, `null` to clear when it
is blank or matches the folder's own name (including a clamped cut of an overlong folder name, but only
when the clamp actually cut it — an uncut folder name is compared exactly, untrimmed). Archive workspace
swaps the modal's content for a confirmation in place, the same shape `EditHostModal`'s unpair step uses;
confirming calls `archiveWorkspace` for that host and `cwd` only, and a partial failure keeps the
confirmation open so a retry archives only the rows still active — see the operation's own contract in
`ConversationRepository.kt`. See [Mobile modal § Callers](mobile-modal.md#callers) for the modal's own
field, its label-rule edge case and a Compose semantics trap in its test.

## Workspace row create-channel control (#958)

`TreeWorkspaceRow` gained a fifth parameter, `onAddTapped: (() -> Unit)? = null`, drawn as a second,
trailing `TreeRowControl(Icons.Filled.Add, …)` **after** the #905 pencil — the row now carries dots-free
pencil-then-plus, the same left-to-right order the host row's edit-then-add pair established in #744. Both
controls keep their own merging-semantics node inside `FoldableTreeRow`'s `clickable`, so tapping either
neither folds the row nor triggers the other.

`treeSection` passes `onAddTapped` only for `ConversationTreeSection.Channels` rows, bound to
`{ onEvent(TreeWorkspaceAddTapped(group.serverId, group.cwd)) }` — `HostWorkspaceGroup`'s own `serverId`
and `cwd`, the same targeting discipline the #905 pencil uses. Chats-section rows pass `null` and draw no
plus: a chat's workspace is not yet a channel, so there is nothing to promote it *from* at that tier (Save
as channel, on the chat itself, is the promotion path there — see
[Save as channel dialog](save-as-channel-dialog.md)). The content description
(`R.string.cd_tree_workspace_new_channel`, "New channel in %1$s") reuses the row's already-`boundedRowText`-
clamped name, the same one-clamp-two-uses shape the pencil's description uses.

Opening [`CreateChannelModal`](mobile-modal.md#callers) from that target, resolving the repository at the
press and running the two-write create-then-prompt sequence are `ChannelListViewModel`'s job — see
[ChannelListViewModel](channel-list-viewmodel.md#wiring). `openCreateChannel(serverId, cwd)` opens only when
that host's snapshot holds an active **channel** at exactly `cwd` — the same source a Channels-section row's
existence already implies, so the plus's own visibility and the open guard agree by construction. OK sends
one `createChannel(name, cwd)`; a non-blank system prompt is then written with `setSystemPrompt` to the
**created** conversation, never read back. See [System prompt editor § intro](system-prompt-editor.md) for
why this write bypasses that editor entirely.

## Attention dot (#878)

`TreeConversationRow` gained `attention: ConversationAttention = ConversationAttention.Idle`
(`di/ConversationAttention.kt`, #877) — the row's one state, declared last, after #667's `editDescription`,
rather than directly after `modifier`; it is still a trailing defaulted parameter, so every existing
positional call site still compiles. The row's leading slot, previously `IdleStatusDot` and always the design's plain
ring, became `ConversationStatusDot(attention)`: the same 8dp box and 1dp `primary` ring on every state
(`TreeDotSize`, `TreeDotRingWidth`), now filled by an exhaustive `when` — mirrors desktop's
`ConversationStatusDot` (`pyrycode-desktop/src/renderer/src/screens/channels/ConversationStatusDot.tsx`)
one-for-one except `Failed`, which has no desktop counterpart and takes `error`:

| State | Fill | Content description |
| --- | --- | --- |
| `Idle` | none (`Color.Transparent`) | "Idle" |
| `Running` | `colorScheme.tertiary`, blinking | "Running" |
| `Unread` | `colorScheme.success` | "Unread" |
| `WaitingForAnswer` | `colorScheme.warning` | "Waiting for your answer" |
| `Failed` | `colorScheme.error` | "Failed" |

**Blink stays off the row.** `Running`'s alpha comes from `rememberInfiniteTransition`, created only
inside the `Running` branch — leaving that state drops the transition from composition — animating
`1f → 0.3f` over a 1000ms `EaseInOut` half-period with `RepeatMode.Reverse` (desktop: opacity `1 → 0.3 → 1`
over 2s ease-in-out; the two halves add up the same). The `State<Float>` is read inside
`Modifier.graphicsLayer { alpha = … }`, not in the composable body, so each animation frame redraws only
the dot's layer and never recomposes `TreeConversationRow` or its `Text`.

**The state names itself.** `ConversationStatusDot` sets `Modifier.clearAndSetSemantics { contentDescription
= … }` from an exhaustive `ConversationAttention` → string-resource map (`cd_conversation_attention_idle` /
`_running` / `_unread` / `_waiting` / `_failed`, `strings.xml`) — the same self-describing-dot-in-a-merging-row
shape `LegDot` already used. The row's own `selectable` merges that description with the conversation name,
so TalkBack reads e.g. "Running, kitchenclaw refactor" — the meaning never rests on colour alone.

**Wiring.** `treeSection` passes `attention = entry.attentionFor(row.conversation.id)` —
[`HostChannelListEntry.attentionFor`](channel-list-viewmodel.md), Idle by default, joined from
`hostSource.attention` (see [state projection § Attention
join](channel-list-viewmodel-projection.md#attention-join-877)). This ticket only draws the state;
deriving it — the five-state precedence, the turn-state/turn-end join — is #877's.

**Scope.** The live run of these states on a real turn, split from #676, is proven by
`interactiveTurn_attentionDot_followsARealTurn` (#1090, rung 3) in
[`e2e-interactive-stream.md`](../../e2e-interactive-stream.md#pre-ship-gate): the peer's ping in one
chat marks only that chat's row Unread while every other composed row keeps its state, opening it reads
Idle again, and the peer's held permission prompt in a second chat marks that row Waiting until the peer
answers it. Every read is off the dot's content description, never its colour, and `Running` is never
asserted there — it is transient on a ping, like the thinking spinner. See [ChannelListScreen §
Related](channel-list-screen.md#related).
