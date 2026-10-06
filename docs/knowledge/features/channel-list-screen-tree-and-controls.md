# ChannelListScreen — conversation tree and controls

Split out of [ChannelListScreen](channel-list-screen.md) on 2026-09-22 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [ChannelListScreen](channel-list-screen.md); see that document for the rest.

## Conversation tree (#731)

`ConversationTree(hostState, onEvent, modifier)` is a single `LazyColumn` — **no nested scroll region
anywhere** — with `contentPadding = PaddingValues(start = TreeGutter, end = TreeGutter, bottom = TreeBottomInset)`.
`TreeGutter = 20.dp` is the list's own horizontal gutter; the row composables from #730 carry only their own
tree indent and no gutter, per that ticket's note. `TreeBottomInset = 16.dp` leaves air below the last row;
the FAB and its former 88dp clearance are gone. There is no list-top padding.
`ConversationTree` calls `treeHost` once per `hostState.hosts` entry, in host order. An expanded
`TreeHostRow` emits its fixed Channels and Chats `TreeHostSectionRow`s, each followed by that host's
`channels` or `chats` directly in source order when expanded. It does not flatten `channelGroups` or
`chatGroups`: grouping by `cwd` would reorder the rows. There are no workspace rows or global tier
headers. All rows share the same `LazyColumn`, so `performScrollToNode` can reach the final row.
The first host has zero extra top padding; subsequent hosts use `TreeHostGap = 16.dp`. The fixed
[toolbar](channel-list-screen-how-it-works.md#the-lists-own-top-bar-737) supplies the 24dp gap from its
divider to the first row. With the old tier divider gone, the toolbar rule is the only full-width rule.
The section content begins 4dp and conversation content 12dp from the list's 20dp content edge.
A conversation row's `Box` pads that 12dp on the start only; it ends flush at the tree gutter
like `TreeHostRow` does, so the Channels/Chats row pens share one horizontal centre with the
Edit host pen ([#1334](https://github.com/pyrycode/pyrycode-mobile/issues/1334) — the row used
to pad both sides, which put its pen 12dp left of the host pen's column).
Each section uses a closed/open folder glyph and right/down chevron to match its fold state. Its fold
and both section plus controls retain separate 48dp touch targets and host-qualified TalkBack names.

**Fold key and node identity.** `TreeFoldKey(section: ConversationTreeSection, serverId: String)`
uses `Host`, `Channels` or `Chats` as its node kind. [`ChannelListViewModel`](channel-list-viewmodel.md)
owns the collapsed set independently of snapshots and the last-opened selection. Folding a host or either
section therefore leaves selection intact and survives a thread round trip and reconnect. Item keys also
include node kind and host id, so equal conversation ids on different hosts cannot collide.

**Display text and fallback.** A host reads `displayName?.takeIf { it.isNotBlank() } ?: R.string.unnamed_host`
— the nameless-host fallback #730 left open, mirroring `DiscussionPreviewRow`'s `untitled_discussion`
convention, so a nameless host or a blank-string name never draws as a blank row and no opaque `serverId`
reaches the UI. A conversation reads the same `name`-or-`untitled_discussion` rule. Both stay display text
only: targets use `serverId` + `conversation.id`, and fold keys use `serverId` + node kind, so renaming
cannot retarget or unfold a row.

**Row target and selection.** Each conversation row's `onClick` emits
`TreeRowTapped(HostConversationTarget(host.serverId, conversation.id))` using the enclosing row's *own*
host, never `ThreadDestinationFactory.selectedServerId()`. Edit, attention and promotion use that same
host-qualified target. `selected = target == hostState.selected`, so a colliding id on another host does
not acquire the highlight.

Since #1523, in the static dark palette a selected `TreeConversationRow` uses
`colorScheme.primaryContainer` (`#134a74`), matching Figma `15:8`'s `Hover` instance
(node `398:7258`), and a pressed row uses `colorScheme.onPrimary` (`#003355`), the darker
`pyrycode discord integration` row on the same frame — the reverse of the two fills'
earlier roles. Explicit light and wallpaper-colour variants in isolated tests keep the
translucent `primaryContainer.copy(alpha = SELECTED_FILL_ALPHA)` selection and `primaryContainer`
pressed fill, both unchanged by #1523. `ChannelListColoursTest` samples the selected-row pixel; a
contrast-only assertion could pass while the role was still wrong. A still capture cannot show the
pressed fill, since it is transient; `ConversationTreeRowsTest`'s held-pointer pixel test is the
evidence for it instead.

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
shared `awaitChannelList()` helper reads it; `createChat()` drives the paired host's Chats plus via
`treeHostChatAddTestTag(serverId)` and confirms the dialog — see
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

**Host section sort order (\#1331).** Each host's Channels and Chats sections sort alphabetically,
independently of each other; archived rows are not in either section. The sort key comes from
`HostWorkspaceGroup.kt`'s `conversationSortKey(label)`: trim, `Normalizer.normalize(_, NFKD)`, strip
every combining mark (`Regex("\\p{Mn}+")`), then `lowercase()` under `Locale.ROOT`. The label itself —
fed to both the key function and the row's own text — is the conversation's non-blank `name`, otherwise
the same `R.string.untitled_discussion` placeholder `treeHost` draws, so sorted and drawn text can never
diverge. `conversationLabelComparator(placeholder)` in the same file orders ascending by that key, then
by the trimmed label, then by conversation id, all three compared as UTF-16 code units via
`String.compareTo` — no locale collation and no natural-number order, so "Chat 10" sorts before "Chat 2",
and "Alpha" sorts before "alpha" on a key tie. `ConversationTree` resolves the placeholder string once via
`stringResource`, then sorts under `remember(hostState.hosts, untitled)` and hands the sorted lists to
`treeHost`, which takes them as parameters rather than reading `host.channels` / `host.chats` directly —
`treeHost` is a `LazyListScope` extension and cannot call `stringResource` or `remember` itself. Because
the sort is a pure, synchronous derivation keyed on every `hostState.hosts` emission, a rename, an
auto-named chat or a new chat moves to its sorted position on the very next emission, with no
pull-to-refresh. `ConversationListProjection.project`'s own `sortedByDescending { it.lastUsedAt }` is
untouched — it still feeds other readers — and `HostWorkspaceGroup`'s `groupConversationsByWorkspace`
keeps its own first-encounter group order; only the per-section row order changes. Row keys, selection
(`HostConversationTarget`) and fold keys (`TreeFoldKey`) already address a row by host id and conversation
id or by host id and section, so a re-sort moves a row without breaking its selection, fold state or edit
target. Desktop's `channelListViewModel.ts` implements the identical rule as `compareByTitle`; keep the
two texts in sync if either is refined. Known residual gaps, not yet observed in practice: the sort key is
recomputed per comparison rather than cached per row (negligible at realistic list sizes), and Kotlin's
`trim()` does not strip a leading/trailing U+FEFF the way JavaScript's `trim()` does, so a name framed by
a BOM could sort differently between the two apps.

## Add controls (#738)

The fixed toolbar plus pairs another host. Each host has a Channels-section plus (#1189) and a
Chats-section plus (#1190), including when either section has no conversations. The section plus and fold
are separate 48dp targets. `TreeRowControl` in `ConversationTreeRows.kt` draws their 16dp glyphs with a
`clickable` and a host-qualified content description; it has no long-press path.

**A disconnected host draws neither control (#1336).** `treeHost` computes `connected =
hostState.isHostConnected(host.serverId)` once per host and passes `null` for both section pluses and
every conversation row's pen when it is false; `TreeHostSectionRow.onAddTapped` took the nullable
`(() -> Unit)?` shape `TreeConversationRow.onEditTapped` already had. A null plus or pen draws nothing —
the row keeps its layout, and the fold and row taps are untouched. They reappear on the very next snapshot
that reports the host connected. This excludes the Edit host pen and the reconnect/re-pair/update controls
on [the host row](#host-row-edit-control-744), which stay regardless of connection, and the toolbar's
pairing plus, which pairs a new host rather than acting on an existing one. The toolbar cannot close over
the host-availability bug #1190 feared for a different reason: this is a rendering gate, not a request
that could be sent and fail. See [ChannelListViewModel](channel-list-viewmodel.md) for the companion rule
that closes any already-open Create channel, Edit chat or Edit channel modal, and clears a failed Chats
create, for a host that stops being connected — this reverses #1190's keep-open-on-disconnect rule.

- **Toolbar “Pair another host”** uses the static `R.string.cd_pair_another_host`, with no section suffix.
  Tapping it emits `ChannelListEvent.PairHostTapped`, which the route maps to
  `navController.navigate(Routes.SCANNER)`. The scanner's paste action opens code pairing; code Cancel/Back
  returns to the scanner, and scanner Back returns to the invoking list. Successful camera pairing pops
  `SCANNER` inclusive; successful code pairing waits for the saved target's connection and clears the graph
  to `channel_list`. `TreeSectionHeader` and its resources remain available, but the list no longer emits
  that component or its pairing controls. See
  [Navigation](navigation.md#manual-pairing-entry-and-return).
**Chats-section creation.** Since #1563, `TreeHostChatAddTapped(serverId)` sends
`createDiscussion(null)` directly to that host, leaving `cwd` for the daemon to choose.
Success selects and opens the returned chat; failure shows the
[timed Error pill](channel-list-screen.md#create-chat-failure-notice-1748), with retry
through another plus tap. There is no Create chat confirmation dialog. Request identity
prevents an older response from replacing a newer request's state. The old host-row
plus and its Add workspace long press are removed; folder choice remains in the thread's
[workspace picker](workspace-picker.md#consumers), while the underlying Add workspace state remains.

**Naming rule.** Pairing is unique and needs no section qualifier. Host controls still repeat down the
screen and name the host they act on, the way fold controls name their row (`cd_tree_row_expand` /
`cd_tree_row_collapse`, formatted with the row's name). `InteractiveStreamE2ETest.pairHostByCode` targets
the unique toolbar description with `onNode`; it retains the paste, fingerprint confirmation and
connection/return waits. The retired section-qualified pairing name is no longer a list selector.

**Device-suite handles.** `treeHostChatAddTestTag(serverId)` and `treeHostChannelAddTestTag(serverId)`
identify the two section controls. Their id clamp includes the original length after a 256-character
prefix, so unusually long host ids cannot make the tags collide merely by sharing that prefix.
`InteractiveStreamE2ETest.createChat()` uses the Chats tag to create directly. See
[`docs/e2e-interactive-stream.md`](../../e2e-interactive-stream.md#what-rung-3-is-made-of).

**Nesting.** A section plus sits inside `FoldableTreeRow`'s clickable but keeps its own semantics node,
name, tag and click action. `ChannelListScreenTest` proves tapping it does not fold the section.

**The 48dp trade.** The design pins a 16dp plus with its centre 10dp from the row's content edge. Touch needs
48dp, and centring a 16dp glyph in a 48dp target lands its centre about 22dp further inboard than the design
draws it — the same trade #731 took growing the design's 28dp pointer rows to a size a thumb can hit. Taken
deliberately, recorded in a KDoc comment on `TreeRowControl` in `ConversationTreeRows.kt`. This tree-control
geometry is separate from the toolbar's 24dp glyphs and 52dp left-control centre spacing.

**Channels-section creation.** The Channels section's own 48dp plus opens
[`CreateChannelModal`](mobile-modal-callers.md#callers) for that host. Its TalkBack name includes
the host; the section fold and plus have separate targets, and the plus does not fold the section. See
[Create channel control](#workspace-row-create-channel-control-958).

## Host row edit control (#744)

Host rows drew the two connection dots beside this pencil (`ConnectionLegPair`) from #744 through #1009;
\#1333 removed them, and the paragraphs below describe the current, dot-free row. The thread's
`ConnectionStatusLine` still shows both legs.

`TreeHostRow` draws a persistent `TreeRowControl` pencil. Tap emits
`TreeHostEditTapped(serverId)` (the route calls `vm.openHostEditor(serverId)`) for that row's host. The
former trailing plus is gone; the pencil has one click action and no long press.

Its content description is `R.string.cd_tree_host_edit` formatted with the row's already-`boundedRowText`-clamped
display name, so repeated controls still say which host they act on. `treeHostEditTestTag(serverId)`
uses the shared `boundedTagId` clamp and is attached to the pencil's own `Modifier.testTag(...)`.

Opening the modal, filling it from the host's stored pairing record, and saving the entered name are the
view model's job — see [Wiring](channel-list-screen-how-it-works.md#wiring) below and [ChannelListViewModel](channel-list-viewmodel.md). The
modal itself, [`EditHostModal`](mobile-modal-callers.md#callers), is unchanged by this ticket; its `Unpair host`
action was wired to an empty lambda here until #745, which gives it a confirmation-gated removal — the
modal swaps its own content in place for a prompt naming the host, and the shell's own Cancel/OK footer
carries the decision. See [ChannelListViewModel](channel-list-viewmodel.md#wiring) for the removal's three
methods, the ordering that keeps a failed store write from clearing the host's cached workspace, and the
`saving` guard that stops a decline or a second request from racing an in-flight write.

\#1525 recorded the pen as a deliberate deviation from Figma `15:8`
(https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8), which at the time drew no pen on host rows. The
frame has since added a persistent pencil to every host row too, always visible rather than revealed by a state like
hover or selection, so the deviation is resolved rather than merely tolerated. `TreeHostRow`'s pencil remains the
only Edit host entry point for a non-owner host, since Settings opens the editor for the owner only, via
`SettingsViewModel.openOwnerHostEditor`.

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

**`UpdateRequired` gets its own control and caption (#1009).** The row-level Play Store action
\#1008 deferred has landed, the way #842 changed the target for `PairingRejected`. `TreeHostRow` computes
`val update = connectionStatus.relay as? RelayLinkStatus.UpdateRequired` and, when non-null, swaps the
plug for `TreeRowControl(icon = Icons.Filled.Download, …)` tagged `treeHostUpdateTestTag(serverId)`
(shares `boundedTagId`'s clamp with the other row tags) — the update control never carries the reconnect
tag, so a test can assert "no plug" directly. Tap still reports through the row's one `onReconnectTapped`
callback, so the row itself stays agnostic; `treeHost`'s `onReconnectTapped` switch (`ChannelListScreen.kt`)
is what branches three ways now: `PairingRejected` → `TreeHostRePairTapped`, `is UpdateRequired` →
`ChannelListEvent.TreeHostUpdateTapped` (a `data object`, carrying nothing — the store listing needs no
host, and the daemon-authored minimum version must never reach an event, a link, a content description or
a log line), else `TreeHostReconnectTapped`. `PyryNavHost` maps the new event to
`LocalUriHandler.current.openUri(PLAY_STORE_URL)` — `internal const val PLAY_STORE_URL` in
`ChannelListScreen.kt`, an app-authored literal pinned to the published listing rather than derived from
`BuildConfig.APPLICATION_ID` (a debug suffix there would name a listing that does not exist; the current
debug build sets no such suffix, but the constant stays a literal on principle, not because of that build
detail). It never calls `reconnectHost`/`retry()`.

A caption `Text` (`bodySmall`/`onSurfaceVariant`, start-aligned with the host name) sits below the row,
inside the host's own lazy item — folding the host only drops the rows below it, so the caption stays
visible while folded. It reads `tree_host_update_required_version` when `update.minClientVersion` is
non-null, else `tree_host_update_required`; this caption is the only place the version renders, already
bounded by #1008's `validMinClientVersion` shape (three 1–6-digit parts, ≤ 20 characters). The fold
chevron and pencil remain — a too-old host can still be edited or
unpaired — even though the Figma frame omits the chevron; the row remains the fold control for every
disconnected state, and dropping that affordance for one state was left out of scope.

`TreeHostRow` reads `connectionStatus.relay.isDisconnected()` and, when true, draws the design's
disconnected treatment. `FoldableTreeRow` gained an optional `accent: Color? = null` (default `null` keeps
today's `onSurfaceVariant`/`onSurface` tints); the host row passes `colorScheme.error` for both the glyph
and the name when disconnected. A `TreeRowControl` with `Icons.Filled.Power` is drawn before the pencil,
tagged `treeHostReconnectTestTag(serverId)` (sharing `boundedTagId`'s clamp with
the edit tag). Tap emits `TreeHostReconnectTapped(serverId)` for the row's own host. There is no long press.

Its content description is `R.string.cd_tree_host_reconnect` ("Reconnect %1$s") formatted with the row's
already-`boundedRowText`-clamped display name, for the same reason the other host controls' descriptions
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

## Chat row edit control (#827, retired by #1563)

Chats are renamed from the thread's Rename item. #1563 removed every conversation pen;
\#1582 removed the unused `TreeConversationRow.onEditTapped` / `editDescription` API,
pen rendering, `cd_tree_chat_edit` / `cd_tree_channel_edit` resources and both list editor
routes and bindings. Selection and row opening still use the row's own host-qualified target.
Host edit controls and their separate tap regions remain unchanged.

Control retirement must include design capture callers and component previews as well as
screen tests: the earlier pen removal missed a capture that still tapped the retired control.
The no-pen regression checks unmerged label prefixes independently of string resources,
then proves that selected and unselected rows still open on their own hosts.

## Channels row edit control (#667, retired by #1563)

A channel is edited through the thread's Edit item (#1561). #1582 removed list hosting,
state, events and routes after #1563 removed the pen. `ChannelEditorController`,
`ChannelEditorState` and `ChannelPromptReading` remain in `ui.conversations.list`, with
unchanged behavior; package location does not imply list ownership. See the
[controller contract](channel-list-viewmodel.md#channeleditorcontroller-667--1561) and
[thread modal binding](thread-screen-how-it-works-sheets.md#editchannelmodal-hosting-post-1561).

## Workspace row edit and archive control (#905)

This section records the former tree entry point. #1189 removed workspace rows from the sidebar, so
their pencil no longer opens the editor there. The folder settings and repository operations remain,
and the live scenario now exercises them through the host's repository and Archive screen.

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

Opening [`EditWorkspaceModal`](mobile-modal-callers.md#callers) from that target, applying the label rule and
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
`ConversationRepository.kt`. See [Mobile modal § Callers](mobile-modal-callers.md#callers) for the modal's own
field, its label-rule edge case and a Compose semantics trap in its test.

## Workspace row create-channel control (#958)

The #958 control used to sit on a workspace row and create a channel in its `cwd`. The host-first tree
removed those rows. `TreeHostSectionRow` now renders the plus only for Channels and emits
`TreeHostChannelAddTapped(serverId)`; Chats has no plus. The fixed heading stays visible when empty, so
creation is reachable before the host has a channel. `openCreateChannel(serverId)` checks that the host
exists in the current snapshot, then opens [`CreateChannelModal`](mobile-modal-callers.md#callers) without a folder
override. The modal's nullable `cwd` is null for this action. The repository omits `cwd` from the wire
request, letting the daemon choose its default folder regardless of the app's saved per-host default.
The returned conversation retains the daemon-confirmed `cwd`.

OK still sends one `createChannel` and then writes a non-blank system prompt to the created conversation.
If that second write fails, retry uses the created id and does not create another channel. A dismissed
modal does not reopen on a late response. See [System prompt editor](system-prompt-editor.md) for why
this write bypasses that editor.

## Attention dot (#878)

`TreeConversationRow` accepts `attention: ConversationAttention = ConversationAttention.Idle`
(`di/ConversationAttention.kt`, #877) and passes it to the private
`ConversationStatusDot(attention)`. The dot is a 6dp circle (`TreeDotSize`); only Idle has a
1dp border (`TreeDotRingWidth`). An earlier mobile-only `Failed` state, filled `error`,
was removed by #1451: a turn that ends Failed or StoppedEarly while its conversation is
not viewed counts as an ordinary completed turn and resolves Unread, then Idle once
opened. No dot ever draws the `error` fill.

**Paint and selection (#1679).** The redraw at Figma `106:3077` supersedes #1524's
all-state inverse-primary ring and selected-Idle fill. Both blue states now use
`colorScheme.primary` in every palette (`#9DCBFC` in static dark). Idle keeps only that
ring at 50% alpha, with a transparent centre whether its row is selected, open or
pressed. Selection still changes the row background; it is no longer an input to the
dot. Running is a solid primary disc; Unread and WaitingForAnswer are solid success
and warning discs with no border.

| State | Ring | Fill | Content description |
| --- | --- | --- | --- |
| `Idle`, including selected | `primary` at 50% alpha, 1dp | none (`Color.Transparent`) | "Idle" |
| `Running` | none | `primary` (`#9DCBFC` in static dark), blinking | "Running" |
| `Unread` | none | `success` (`#2FC038`) | "Unread" |
| `WaitingForAnswer` | none | `warning` (`#D8B85A` in static dark) | "Waiting for your answer" |

**Blink stays off the row.** `Running`'s alpha comes from `rememberInfiniteTransition`, created only
inside the `Running` branch — leaving that state drops the transition from composition — animating
`1f → 0.3f` over a 1000ms `EaseInOut` half-period with `RepeatMode.Reverse` (desktop: opacity `1 → 0.3 → 1`
over 2s ease-in-out; the two halves add up the same). The `State<Float>` is read inside
`Modifier.graphicsLayer { alpha = … }`, not in the composable body, so each animation frame redraws only
the dot's layer and never recomposes `TreeConversationRow` or its `Text`.

**The state names itself.** `ConversationStatusDot` sets `Modifier.clearAndSetSemantics { contentDescription
= … }` from an exhaustive `ConversationAttention` → string-resource map (`cd_conversation_attention_idle` /
`_running` / `_unread` / `_waiting`, `strings.xml`) — the same self-describing-dot-in-a-merging-row
shape the host row's connection legs used before #1333 removed them. The row's own `selectable` merges
that description with the conversation name,
so TalkBack reads e.g. "Running, kitchenclaw refactor" — the meaning never rests on colour alone.

**Wiring.** `treeHost` passes `attention = entry.attentionFor(conversation.id)` —
[`HostChannelListEntry.attentionFor`](channel-list-viewmodel.md), Idle by default, joined from
`hostSource.attention` (see [state projection § Attention
join](channel-list-viewmodel-projection.md#attention-join-877)). This ticket only draws the state;
deriving it — the attention precedence and turn-state/turn-end join — belongs to the host source.

**Paint regression coverage.**
`ConversationTreeRowsTest.conversationRow_statusDots_matchRedrawnPaintAndBlink` renders
all four states plus selected Idle on known static-dark backgrounds. It checks 6dp
bounds and centre/ring-region pixels, then advances the clock through both blink
half-periods: Running fades to 0.3 alpha and returns, while the other dots' pixels stay
unchanged. Semantics alone cannot catch a wrong fill or an unwanted border. The
shared fixture pins its Compose density so the 1dp ring samples remain comparable on
Robolectric and devices; see [Compose evidence](development-verification-compose-evidence.md#compose-evidence).

**Scope.** The live run of these states on a real turn, split from #676, is proven by
`interactiveTurn_attentionDot_followsARealTurn` (#1090, rung 3) in
[`e2e-interactive-stream.md`](../../e2e-interactive-stream.md#pre-ship-gate): the peer's ping in one
chat marks only that chat's row Unread while every other composed row keeps its state, opening it reads
Idle again, and the peer's held permission prompt in a second chat marks that row Waiting until the peer
answers it. Every read is off the dot's content description, never its colour, and `Running` is never
asserted there — it is transient on a ping, like the thinking spinner. See [ChannelListScreen §
Related](channel-list-screen.md#related).
