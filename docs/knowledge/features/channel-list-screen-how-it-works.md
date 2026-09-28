# ChannelListScreen — how it works

Split out of [ChannelListScreen](channel-list-screen.md) on 2026-09-22 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [ChannelListScreen](channel-list-screen.md); see that document for the rest.

## How it works

### Stateless `(hostState, onEvent)` contract

No `viewModel()`, no `koinViewModel()`, no `LocalContext.current`, no `NavController` parameter. `hostState`
joined `onEvent` as a second parameter in #731 and became the screen's **only** state parameter in #738,
when the compatibility `state: ChannelListUiState` retired with the button it fed. `hostState` carries the
host-qualified rows, the collapsed nodes, the last-opened target and (since #904) the open Add workspace
modal's target and its own host's recent folders. The
canonical CLAUDE.md shape (hoist state to the ViewModel; UI receives state + `onEvent`) is unchanged.

### The list's own top bar (#737)

Replaced the M3 `TopAppBar` #21 gave the screen — the design retires the app's generic top app bar (with it,
the app name and the Pyry logo, #68) and gives the list its own chrome: a settings entry at the leading
content edge, an archive entry beside it, and (since #1186) one “Pair another host” plus at the right,
with a 1dp rule closing the titleless bar. The screen still owns its own
chrome rather than relying on a shared `TopAppBar` slot threaded through the NavHost; the outer `Scaffold` in
`MainActivity` carries system-bar insets only.

The file-private `ChannelListTopBar(onEvent)` is a `Column`: a full-width `Row` keeps Settings and Archive
together at the left and pairing at the right, followed by a `HorizontalDivider`. Each control is a
48dp `IconButton` with a 24dp Material `Icon` tinted `colorScheme.primary`:

| Icon | TalkBack name | Event |
| --- | --- | --- |
| `Settings` | Open settings | `SettingsTapped` |
| `Archive` | Open archive | `ArchiveTapped` |
| `Add` | Pair another host | `PairHostTapped` |

The existing Material icons remain the approximation of Figma's FontAwesome artwork. Both this divider and
the tree's between-sections rule use the private `sidebarRuleColor()` helper: `inversePrimary` for a dark
surface, `outlineVariant` for a light surface, each at `SECTION_RULE_ALPHA = 0.60f`.

**Panel colours ([#1158](../../specs/architecture/1158-dark-sidebar-colours.md)).** The list's `Scaffold`
composites the current scheme's `scrim` at 30% over `surface` when `surface.luminance() < 0.5f`; its transparent
top bar inherits that same fill, including with no hosts. The divider helper uses the same luminance check,
so both treatments follow the active app theme and previews independently of the system theme. Light
surfaces keep their plain `surface` fill and `outlineVariant` rules at 60%.

This treatment belongs to Figma's nested sidebar panel `I133:259;103:2959`; the outer frame's surface already
matches the design. Keep the fill local to the list Scaffold: global theme tokens, system bars and other
screens retain their existing colours. Selected rows keep their existing distinct fill.

**It lives in the `Scaffold`'s `topBar` slot, not the tree's scroll container**, so it draws above the
`hostState.hosts` branch and is carried by **both** of the screen's draws — the empty placeholder and the
assembled tree (four before #738 retired the flat state's loading and error texts) — without that branch
being touched. This is what makes the "bar on every draw" requirement fall out of the structure rather than
needing to be re-proven per state.

**Geometry ([#1186](../../specs/architecture/1186-sidebar-pairing-toolbar.md)).** Figma's 24dp top inset
and 4dp inside its 28dp wrapper put each 24dp glyph's top at 28dp relative to the panel. A 48dp target
adds `BarTouchSlack = 12.dp` around the glyph, so `BarTopGap = 28dp − 12dp = 16dp`. Targets end at 64dp;
`BarRuleGap = 16dp − 12dp = 4dp` places the 1dp divider at 68dp. Horizontal target padding is
`TreeGutter − BarTouchSlack = 8dp` on both sides, placing the outer glyph edges at the 20dp content gutters.
The two left targets have a 4dp gap, giving 52dp between centres. Copying the design's 44dp centre spacing
would overlap 48dp targets; keep this deliberate spacing adaptation.

**First-row spacing.** The global Channels/Chats headers are no longer emitted. `BarBottomGap = 24.dp`
is the entire gap from the divider's bottom to the first host row: both list-top padding and first-host
padding are zero. Do not carry forward the old section header's inner-label slack calculation. The
between-groups divider has 28dp padding on each side; see [conversation tree](channel-list-screen-tree-and-controls.md#conversation-tree-731).

**Insets.** No window insets of its own, unlike the `TopAppBar` it replaced. M3's `Scaffold` gives the body a
top padding equal to the measured `topBar` height and leaves the window inset to the bar itself; the retired
`TopAppBar` applied `TopAppBarDefaults.windowInsets` on top of the status-bar padding `MainActivity`'s outer
`Scaffold` already applies to the whole `PyryNavHost` — a doubled status-bar inset that went unnoticed until
this replacement quietly removed it. Worth checking before the same `TopAppBar` → plain-`topBar` swap is made
on a screen whose outer `Scaffold` isn't already padding for it.

The `floatingActionButton` slot and the file-private `ChannelListFab` it hosted are gone (#738) — see
[Add controls (#738)](channel-list-screen-tree-and-controls.md#add-controls-738) for what replaced both of its gestures, and for
`TreeAddControl`, which inherits the manually-composed-`Surface`-not-`IconButton` construction
`ChannelListFab` pioneered in #221 for the same reason.

#### Add workspace modal as Scaffold sibling (#221, replaced #904)

After the `Scaffold { ... }` block closes, the screen composes `AddWorkspaceModalBinding(hostState, onEvent)`
as a sibling of the Scaffold, not inside its content lambda — matching #78's `SaveAsChannelDialog` placement
and, since [`MobileModal`](mobile-modal.md) is itself a `Dialog`, the same placement `HostEditorModal` and
`ChatEditorModal` use below it. The private binding returns early when `hostState.addWorkspace` is `null`,
so the modal draws exactly while a target is open; `hostAvailable = hostState.isHostConnected(state.serverId)`
is read fresh on every draw from the same host-snapshot flow the rows render from, `loading = state.busy`,
and `error` resolves `createFailed` / `startFailed` to one of two static strings. `onSelect` dispatches
`AddWorkspaceSelected(path)`, `onCreateFolder` dispatches `AddWorkspaceFolderCreateRequested(name)`,
`onSubmit` dispatches `AddWorkspaceSubmitted`, and every dismissal route dispatches `AddWorkspaceDismissed`.

**#904 replaced the bottom-sheet `WorkspacePicker(visible, onPicked, onDismiss)` this section used to
describe here** — `visible = hostState.workspacePickerServerId != null`, `onPicked` → `WorkspacePicked(path)`,
`onDismiss` → `WorkspacePickerDismissed` — with the shared `MobileModal` shell. `WorkspacePicker` itself is
unchanged and still composed the same way, as a `Scaffold` sibling, by the thread screen and by Settings;
see [WorkspacePicker § Consumers](workspace-picker.md#consumers).

## Tree rows (#730)

`ui/conversations/components/ConversationTreeRows.kt` supplies the three stateless composables this screen
assembles: `TreeHostRow`, `TreeHostSectionRow` and `TreeConversationRow`, plus their shared file-private
`TreeRowControl` (see [Add controls](channel-list-screen-tree-and-controls.md#add-controls-738)).
`TreeSectionHeader` and `TreeWorkspaceRow` remain available as shared components, but this screen emits
neither. The rows remain stateless and resolve nothing about which host or workspace they belong to; every
parameter is display text, a flag or a callback the caller (this screen) already resolved — `TreeHostRow`'s
new `serverId` parameter is the one exception, used only to name its own add control for the device suites,
never to resolve anything the row draws. Row-level clamping, truncation, selection-fill and
connection-indicator details are not repeated here — see `docs/specs/architecture/730-mobile-tree-rows.md`;
this document covers only how the screen assembles and drives them.

## Wiring

`PyryNavHost`'s `Routes.CHANNEL_LIST` composable resolves `ChannelListViewModel` and collects `hostState` —
the screen's only state since #738 — passing it into `ChannelListScreen`. Since #904 the route no longer
wraps this destination in `HostWorkspaceRepository` either: nothing on this screen reads
`LocalWorkspacePickerRepository` any more, since the Add workspace modal reads and writes through the
view model's own host-resolved-at-the-press lookup instead of the composition local — see
[WorkspacePicker § Repository ownership](workspace-picker.md#repository-ownership) for the thread and
Settings destinations that still wrap it. The event `when` maps the two tree
events straight to the VM: `is ChannelListEvent.TreeRowTapped -> vm.onHostRowTapped(event.target)` and
`is ChannelListEvent.TreeFoldToggled -> vm.onFoldToggled(event.key)` — no adapter, no `selectedServerId()`
lookup, because the row already carries its own host. This is the wrong-host fix #731 landed with the render;
\#1190 carries the same discipline into creation: `TreeHostChatAddTapped(serverId)` opens the
Chats-section confirmation for that host, and Create sends `createDiscussion(null)` through the held host's
repository, never `destinations.selectedServerId()`. #744 carries the same discipline into editing: `is
ChannelListEvent.TreeHostEditTapped -> vm.openHostEditor(event.serverId)`,
`is ChannelListEvent.HostEditNameSubmitted -> vm.submitHostName(event.name)` and
`ChannelListEvent.HostEditDismissed -> vm.dismissHostEditor()`. #745 adds three more, none carrying a
`serverId` because the target is the open editor's, held in the view model:
`ChannelListEvent.HostUnpairRequested -> vm.requestHostUnpair()`,
`ChannelListEvent.HostUnpairConfirmed -> vm.confirmHostUnpair()` and
`ChannelListEvent.HostUnpairDeclined -> vm.declineHostUnpair()`. #827 carries the same discipline into a
Chats row's own pencil, resolving from the row's own target rather than the selected host a fourth time:
`is ChannelListEvent.TreeChatEditTapped -> vm.openChatEditor(event.target)`,
`is ChannelListEvent.ChatEditNameSubmitted -> vm.submitChatName(event.name)` and
`ChannelListEvent.ChatEditDismissed -> vm.dismissChatEditor()`. `ChannelListEvent.SettingsTapped` still
navigates to `Routes.SETTINGS`;
`ChannelListEvent.ArchiveTapped` reads the current selection and navigates to that host's archive since
\#715 (`destinations.selectedServerId()?.let { navController.navigate(Routes.archive(it)) }`; a null
selection taps to nothing) — the same destination Settings' `onOpenArchivedDiscussions` opens, but the
two capture their owner from different sources: this door re-reads selection on every tap, Settings'
row inherits the owner its own destination already holds. Before #715 this called the bare
`Routes.ARCHIVED_DISCUSSIONS`, and a rework was needed after that constant became a route pattern —
see [Navigation § Archive](navigation.md#archive-a-required-owner-destination-two-doors-715).
`ChannelListEvent.PairHostTapped` navigates to `Routes.SCANNER` (#738) — no pop, no flag: the
scanner's own completions already return here (see [Add controls](channel-list-screen-tree-and-controls.md#add-controls-738) above). The
`RecentDiscussionsTapped` branch that navigated to `Routes.DISCUSSION_LIST` is gone with the event, and so
are the FAB's own branches (`CreateDiscussionTapped`, `LongPressFab`) — #738 retired the button and the
`destinations.selectedServerId()` capture those two branches made. Since #904, `AddWorkspaceSelected`,
`AddWorkspaceFolderCreateRequested`, `AddWorkspaceSubmitted` and `AddWorkspaceDismissed` map to
`vm.selectAddWorkspaceFolder(event.path)`, `vm.createAddWorkspaceFolder(event.name)`,
`vm.submitAddWorkspace()` and `vm.dismissAddWorkspace()` — none carrying a `serverId`, since the target is
already the open modal's — replacing the `WorkspacePicked` / `WorkspacePickerDismissed` branches that used
to resolve through `vm.pickHostWorkspace` / `vm.dismissHostWorkspacePicker` here. `Routes.DISCUSSION_LIST` and `DiscussionListScreen` stay in the graph,
unreachable — removing them remains out of scope.

See [ViewModel wiring](channel-list-viewmodel.md#wiring) for the Koin binding, the `hostState` combine and the
fold/selection state, and [flat-list compatibility](navigation.md#temporary-flat-list-compatibility) for the
pre-#729 adapter's one remaining consumer (`DiscussionListScreen`, unreachable) now that #738 removed this
screen's last use of it. The screen keeps its `(hostState, onEvent)` contract; ViewModels remain scoped to
their `NavBackStackEntry`, and `collectAsStateWithLifecycle()` controls screen subscriptions.

**The editor's target lives in the view model, not the screen (#744).** `ChannelListScreen` composes
[`EditHostModal`](mobile-modal-callers.md#callers) as a `Scaffold` sibling, the same placement `WorkspacePicker`
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

**A Chats row's own pencil follows that chat's own host, live (#827).** `ChannelListScreen` composes a
private `ChatEditorModal(hostState, onEvent)` as a third `Scaffold` sibling, after `HostEditorModal`,
drawn only while `hostState.chatEditor != null`. It passes
[`EditChatModal`](mobile-modal-callers.md#callers) `conversationId`, `initialName`, `saving` and `failed` straight
off that state, and `hostAvailable = hostState.isHostConnected(editor.serverId)` — read fresh on every
draw from the same host-snapshot flow the rows themselves render from, so a disconnect disables OK and a
reconnect re-enables it without the modal leaving composition or losing the typed name; the error slot
resolves the one generic string, `R.string.edit_chat_save_failed`, the same way the host editor's does.
`onArchiveRequested = {}` stays unwired until #828. `ChannelListViewModel` owns the open
(`openChatEditor`, reading the name from the target host's own snapshot, never from row text or another
host's list), the write (`submitChatName`, resolving `ConversationRepository.rename` from the target's
own `serverId` at the press, never the selected host) and the close (`dismissChatEditor`) — see
[ChannelListViewModel](channel-list-viewmodel.md#wiring) for the state's shape and its
`compareAndSet` terminal-transition discipline against a write finishing after a dismissal.

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
- **Toolbar pairing string (#1186):** `R.string.cd_pair_another_host` ("Pair another host") names the
  single toolbar control. `channel_list_empty` points to it at the top right without claiming no hosts
  are paired while snapshots are pending. Global Channels/Chats title rows are no longer rendered.
- **Strings added in #738:** `R.string.cd_tree_section_pair_host` ("Pair another host, %1$s", retained
  for the shared `TreeSectionHeader`, no longer used by this screen),
  `cd_tree_host_new_chat` ("New chat on %1$s") and `cd_tree_host_pick_workspace` ("Pick a workspace for the
  new chat on %1$s") — the shared header uses its section title; host controls use the row's
  already-clamped host name. **Strings retired in #738:** `cd_new_discussion` and
  `cd_long_press_fab_pick_workspace` — the retired button's two labels; nothing else in `res/values/strings.xml`
  referenced them.
- **Strings added in #744:** `R.string.cd_tree_host_edit` ("Edit host %1$s") — the edit control's content
  description, formatted the same way the add control's two are. `R.string.edit_host_save_failed`
  ("Couldn't save the host name. Try again.") lives beside `EditHostModal`'s own strings in
  `res/values/strings.xml` and is deliberately generic — it names neither the server identity nor the relay
  address, since the shell renders it verbatim into a live region.
- **Strings added in #827:** `R.string.cd_tree_chat_edit` ("Edit chat %1$s") — a Chats row's edit control's
  content description, formatted the same way the host row's is. `R.string.edit_chat_save_failed`
  ("Couldn't rename the chat. Try again.") lives beside `EditChatModal`'s own strings and, like
  `edit_host_save_failed`, is deliberately generic — it names neither the chat nor the server's own
  message, since the shell renders it verbatim into a live region.
- **Strings added in #904:** `add_workspace_title` ("Add workspace"), `add_workspace_recent` ("Recent") and
  `add_workspace_create_folder` ("Create new folder under pyry-workspace…") keep the bottom sheet's own
  wording, which the device suites already matched; `add_workspace_new_folder` ("New folder") labels a
  selection absent from recents; `add_workspace_empty` is the no-recents-yet line; `add_workspace_create_failed`
  and `add_workspace_start_failed` are the two static failure sentences, generic for the same reason
  `edit_chat_save_failed` is. The content-description strings the row's own add control uses
  (`cd_tree_host_pick_workspace` from #738) are unchanged — only what the long-press opens moved.
- **Drawables:** `R.drawable.ic_pyry_logo` (since #68) — no longer used on this screen since #737 retired the
  logo along with the old bar; its only remaining consumer is [`WelcomeScreen`](welcome-screen.md).
