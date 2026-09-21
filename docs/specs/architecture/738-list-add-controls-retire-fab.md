# #738 — New chats and pairing on the list's own add controls

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` → `ChannelListScreen`,
  `ChannelListEvent`, `ChannelListFab`, `treeSection`, `ChannelListTopBar`, `CHANNEL_LIST_TEST_TAG` — the
  screen that loses the button and gains the two controls, and the tag convention the new handle copies.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` → `ChannelListUiState`,
  `ChannelListNavigation`, `ChannelListViewModel.state`, `.navigationEvents`, `.onEvent`,
  `createHostDiscussion`, `openHostWorkspacePicker`, `pickHostWorkspace`, `dismissHostWorkspacePicker`,
  `HostChannelListState.workspacePickerServerId` — everything retired and everything the controls drive.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRows.kt` →
  `TreeSectionHeader`, `TreeHostRow`, `FoldableTreeRow`, `foldActionLabel`, `boundedRowText`,
  `ConnectionLegPair` — where the two controls land, the clamp daemon-authored names already pass through,
  and the fold labels' naming pattern the new labels mirror.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → the `Routes.CHANNEL_LIST` `composable`,
  `Routes.SCANNER`, the scanner's `onPersisted` and `PairCodePhase.Complete` branches,
  `HostWorkspaceRepository` — the route being rewired, and proof both pairing completions already land on
  the list (camera pops `SCANNER` inclusive, paste-code pops the graph).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/WorkspacePicker.kt` → `WorkspacePicker` —
  the picker's `visible` contract and its `LocalWorkspacePickerRepository` fallback.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → `viewModel { ChannelListViewModel(...) }` — the one
  construction site of the view model whose constructor loses its repository.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt` → `HostConversationSnapshot` — where a
  host row's `serverId` and `displayName` come from, and that both are daemon-authored.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModelTest.kt` → the whole class:
  every assertion in it is on the flat state or `onEvent`, so it retires with them.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/HostChannelListViewModelTest.kt` →
  `defaultCreationCapturesHostBeforePreferenceSuspensionAndResolvesFreshRepositoryAtSend`,
  `pickerCapturesHostOverridesPreferenceClearsSynchronouslyAndDismissesWithoutCreating`,
  `guardedFailuresLeaveNoNavigation…`, `rowTargetsAndLegacySelectedProjectionAndActionsUseSeparateNavigationStreams`,
  `Fixture` — the host-qualified coverage the ticket says already exists, and the two tests that read the
  legacy stream.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreenTest.kt` → `setTree`,
  `loaded`, `fab_emitsCreateDiscussionTapped`, `listBar_drawsBothEntriesAndNoneOfTheRetiredChrome_onEveryDraw`,
  `arrivalMarker_isCarriedByEveryDrawOfTheList_exactlyOnce`, `emptyState_rendersPlaceholder_whenThereAreNoHosts`
  — the four-draw shape that collapses to two draws once the flat state is gone.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `createChat`,
  `openWorkspacePicker`, `CD_NEW_DISCUSSION` — #736's two helpers, the only sites in that class naming the
  control.
- `app/src/androidTest/java/de/pyryco/mobile/StartupWorkspaceMigrationTest.kt` → `assertChannelList`,
  `assertPending` — **two further sites #736 did not migrate**, still keying on the button's literal
  `"New discussion"`. Found by grepping the literal rather than the resource id.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/E2eTestApplication.kt` → `ARG_SERVER_ID` — the harness pairs
  exactly one host and knows its `serverId`, which is what makes a per-host handle drivable.
- `docs/knowledge/features/channel-list-screen.md` § "Tier test tags", `channel-list-viewmodel.md` — the tag
  reasoning reused here, and the flat model's documented shape. Read, not edited.
- `docs/specs/architecture/736-device-suite-list-marker-and-create-helpers.md` — the handoff #736 wrote for
  this ticket: two helper bodies to edit, and its own open question about bare arrival waits.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8

Node `15-8` marks its `FAB` frame hidden and hosts the mobile Sidebar adaptation `133-259`. Each `Sidebar
header` draws its title at `M3/label/large` on `Schemes/on-surface-variant` with a small primary-coloured
plus pinned to the header's trailing edge; each `Host` row draws the same plus at its own trailing edge, in
the design's hover treatment alongside #642's pencil. The phone has no hover, so both pluses are drawn
persistently; the pencil stays absent with #642, and the host row keeps the connection-leg dots the hover
treatment swaps out, placed inboard of the new control.

## Context

The list's chrome is being moved onto the tree itself. #737 took the top app bar; this slice takes the
floating action button, whose two paths — tap for a chat in the default workspace, long-press to choose one —
resolve their host through `ThreadDestinationFactory.selectedServerId`, a flat-list adapter that cannot be
right once the tree draws rows from several hosts. The host-qualified replacements already exist and are
unit-tested (`createHostDiscussion`, `openHostWorkspacePicker`, `pickHostWorkspace`,
`dismissHostWorkspacePicker`); they have had no control to drive. This slice gives them one, per host row,
and gives pairing an entry from the list — today a phone that is already paired cannot add a second host at
all, because the only door into the scanner is the Welcome screen.

With the button gone the flat `ChannelListUiState` flow, its events and its `ChannelListNavigation` channel
have no consumer, so they retire here rather than lingering as a second, hidden list model behind the tree.

No ADR is warranted: this consumes #729's host projection and #731's tree, and reuses #731's decision that an
app-authored `testTag` is the device suites' durable handle. Nothing new is decided.

## Design

### The two controls

Both live in `ConversationTreeRows.kt` and share one private `TreeAddControl` composable: a
`Modifier.size(TreeAddTouchSize)` `IconButton` drawing `Icons.Default.Add` at `TreeGlyphSize` tinted
`MaterialTheme.colorScheme.primary`, carrying the caller's content description and the caller's `testTag`.
It takes an optional `onLongPress`; when present the control uses `combinedClickable` with both labels rather
than `IconButton`'s plain `onClick`, which is the same construction `ChannelListFab` used and the reason the
long-press path survives the button's removal unchanged.

- **`TreeSectionHeader`** gains `onAddTapped: () -> Unit`. Its bare `Text` becomes a `Row` holding the title
  (weighted, still `heading()`) and the control. The label is `cd_tree_section_pair_host`, formatted with the
  section title the caller already resolved — an app-authored resource string, never daemon text, so it is
  not passed through `boundedRowText`.
- **`TreeHostRow`** gains `onAddTapped`, `onAddLongPressed` and `serverId`. The control goes in
  `FoldableTreeRow`'s existing `trailing` slot, outboard of `ConnectionLegPair`. Its labels are
  `cd_tree_host_new_chat` and `cd_tree_host_pick_workspace`, both formatted with the **bounded** host name —
  the same `boundedRowText(hostName)` value `FoldableTreeRow` already feeds `foldActionLabel`, computed once
  and passed in rather than re-clamped.

`serverId` is new on `TreeHostRow` and is used for one thing: `treeHostAddTestTag(serverId)`, a length-clamped
app-authored prefix plus the id, so the device suites can name *which* host's control they are driving. The
row still resolves nothing from it.

**The 48dp trade.** The design pins a 16dp plus with its centre 10dp from the content edge. Touch needs 48dp
and an `IconButton` centres its glyph in that box, so with the rows' existing `TreeRowEndPadding` the glyph's
centre lands 32dp inboard instead of 10dp. That deviation is taken deliberately and is the same trade #731
took when it grew the design's 28dp rows to 48dp; it is recorded in a comment on `TreeAddControl`. The
section header's band grows from 36dp to 48dp for the same reason — it is a control-bearing row now, not a
bare label.

**Nesting.** The host row's control sits inside `FoldableTreeRow`'s `clickable`, which merges descendants.
An `IconButton` sets `shouldMergeDescendantSemantics` itself, and merging stops at a merging descendant, so
the control stays its own node with its own name, its own tag and its own click action; a tap on it does not
reach the row's fold. The screen test asserts exactly this rather than assuming it.

### Events

`ChannelListEvent` loses `CreateDiscussionTapped` and `LongPressFab` and gains:

- `data object PairHostTapped` — no payload. Both headers emit the same event because both open the same
  pairing flow; the section is what the *label* disambiguates, and a section identifier in the payload would
  establish no capability the route could act on.
- `data class TreeHostAddTapped(val serverId: String)` and `data class TreeHostAddLongPressed(val serverId: String)`
  — the row's own host, resolved from the row, never from the selected-host adapter. Same reasoning and same
  shape as `TreeRowTapped`.

`WorkspacePicked` and `WorkspacePickerDismissed` stay: the picker is still the long-press path's second step.

### The screen

`ChannelListScreen` loses its `state: ChannelListUiState` parameter entirely. The `floatingActionButton` slot
and `ChannelListFab` go. The body branch collapses from a four-way `when (state)` to the `hostState.hosts`
emptiness test that already gated it — a tree with no hosts is the only blank the list draws, so the loading
and error placeholders go with the flat state. The picker's `visible` becomes
`hostState.workspacePickerServerId != null`, which is the same value the route was copying into the flat
state; the copy just stops.

The two `@Preview`s lose `previewLoaded()`; the no-host preview keeps its blank.

### The view model

Deleted: `ChannelListUiState`, `ChannelListNavigation`, `state`, `navigationChannel`, `navigationEvents`,
`onEvent`, `pendingWorkspacePicker`, and the now-unused imports. `repository` leaves the constructor with
them — it was read only by the deleted flow, and an unused constructor property is dead weight plus a
compiler warning — which is the one line changed in `AppModule.kt`.

Nothing else moves. `hostState`, `hostNavigationEvents`, `onFoldToggled`, `onHostRowTapped` and the four
host-qualified action methods are untouched.

### The route

`MainActivity`'s `Routes.CHANNEL_LIST` composable stops collecting `vm.state` and stops rebuilding a flat
state. Its `when (event)` maps:

- `PairHostTapped` → `navController.navigate(Routes.SCANNER)`. No pop, no flag: the scanner's own camera
  completion pops `SCANNER` inclusive and lands on `CHANNEL_LIST` with `launchSingleTop`, reusing the entry
  already below it; the paste-code completion pops the graph. Both already end on the list, which is why this
  reuses the existing entry rather than adding a flow.
- `TreeHostAddTapped` → `vm.createHostDiscussion(event.serverId)`.
- `TreeHostAddLongPressed` → `vm.openHostWorkspacePicker(event.serverId)`.

`HostWorkspaceRepository(hostState.workspacePickerServerId, destinations)` is unchanged; the picker still
resolves its repository from the host it was opened for.

## State + concurrency model

No new state, no new coroutine, no new dispatcher. The controls are pure callbacks into methods that already
exist; `createHostDiscussion` and `pickHostWorkspace` keep running inside `launchGuardedRepoCall` on
`viewModelScope`, cancelled with the view model. Deleting `state` removes a `stateIn` and its
`SharingStarted.WhileSubscribed` subscription, and deleting `navigationChannel` removes one buffered
`Channel`; nothing is left holding a collector. `hostState` keeps its own `stateIn` untouched.

## Error handling

No new failure mode. An add tap on a host whose repository is gone takes the existing
`event=host_chat_create_rejected code=unavailable` branch and draws nothing; a create that throws takes the
existing guarded path. Retiring the flat state removes the list's `Error` placeholder, which only ever
rendered `repository.observeConversations`' failure through the selected-host adapter — a surface that is
itself being retired, and one no host-qualified failure ever reached.

## Testing strategy

- **`ChannelListScreenTest` (device, Compose).** New: each section header's control emits `PairHostTapped`
  and carries its own section-named description (two controls, two distinct names, both reachable); a host
  row's control emits `TreeHostAddTapped` for **its own** `serverId` on tap and `TreeHostAddLongPressed` on
  long press, proven with two hosts so a globally selected one cannot pass; the control's tap does not fold
  its row (the nesting claim above). Retired: `fab_emitsCreateDiscussionTapped`. Reshaped: `setTree`, the
  four-draw walks in `listBar_…` and `arrivalMarker_…` collapse to the two draws that remain, and
  `emptyState_…` drops its flat argument.
- **`HostChannelListViewModelTest` (unit).** The two tests reading `navigationEvents` lose that collector and
  the `legacy` list; `rowTargets…` loses its flat-state assertions and keeps the separation it was really
  proving — that a row tap and a create reach the host-qualified stream. No new unit test: the ticket's own
  claim that nothing is proved only in the flat class is checked by reading it, and the four host-qualified
  behaviours (default capture, picker override, unavailable target, guarded failure) are already covered.
- **`ChannelListViewModelTest`** retires whole.
- **`InteractiveStreamE2ETest`** — `createChat` and `openWorkspacePicker` re-point at the host row's control
  by `treeHostAddTestTag(<ARG_SERVER_ID>)`, read from the instrumentation arguments the harness already
  passes; `CD_NEW_DISCUSSION` goes. This is the handle that stays unambiguous with a second host paired.
- **`StartupWorkspaceMigrationTest`** — `assertChannelList` and `assertPending` move to
  `CHANNEL_LIST_TEST_TAG`, which is strictly better for `assertPending`'s absence check (the marker is on the
  root of every draw, so its absence means the destination is absent, not merely that one control is).
- No rung-3 scenario is added or removed. This ticket does not ship a new operator-facing flow — it re-hangs
  two existing gestures — and AC-5 makes the live gate the migration's own proof, which is the dispatcher's
  post-verifier run.
- Focused verification: the new screen tests on the managed device, `testDebugUnitTest` for
  `HostChannelListViewModelTest`, `compileDebugAndroidTestKotlin`, `lint`, `assembleDebug`.

## Documentation handoff

Pending for the documentation stage, from the ticket's own handoff section — not edited here:

- `docs/knowledge/features/channel-list-screen.md` — the two add controls, their naming rule, and the
  retirement of the flat state from the screen.
- `docs/knowledge/features/channel-list-viewmodel.md` — the retirement of `ChannelListUiState`,
  `ChannelListNavigation`, `onEvent` and the `repository` constructor parameter.
- `docs/knowledge/features/navigation.md` — that pairing an additional host is reachable from the list.
- `docs/e2e-interactive-stream.md` — its create-workspace-folder scenario still describes a long-press on the
  floating button; correct it to the host row's add control, and record `treeHostAddTestTag` beside #736's
  arrival marker.

## Sizing

One boundary line exceeded, re-counted against this written plan rather than against the estimate line:
**14 consumer call sites needing simultaneous update, against a limit of 10.**

1. `MainActivity`'s `Routes.CHANNEL_LIST` composable — the flat collect and four event branches.
2. `AppModule`'s `viewModel { ChannelListViewModel(...) }` — one argument fewer.
3. `ChannelListScreenTest.setTree`, 4. its `loaded` helper, 5. `fab_emitsCreateDiscussionTapped` (retired),
6. `listBar_drawsBothEntriesAndNoneOfTheRetiredChrome_onEveryDraw`,
7. `arrivalMarker_isCarriedByEveryDrawOfTheList_exactlyOnce`, 8. `emptyState_rendersPlaceholder_whenThereAreNoHosts`.
9. `ChannelListViewModelTest` — the whole class, retired.
10. `HostChannelListViewModelTest.defaultCreationCapturesHostBeforePreferenceSuspensionAndResolvesFreshRepositoryAtSend`,
11. `…rowTargetsAndLegacySelectedProjectionAndActionsUseSeparateNavigationStreams`.
12. `InteractiveStreamE2ETest.createChat`, 13. `…openWorkspacePicker`.
14. `StartupWorkspaceMigrationTest.assertChannelList` **and** `assertPending` — two sites #736 did not
    migrate, counted as one class-local pair; the refiner's own count of 11 predates finding them.

Every other line passes: 5 production `.kt` files (`ChannelListScreen`, `ChannelListViewModel`,
`ConversationTreeRows`, `MainActivity`, `AppModule`) plus `strings.xml`; ~540 lines of total written work
against 800 (the retirement is ~800 lines of deletion, which is not written work); 4 new exported
declarations; 5 acceptance criteria; 0 new reject branches.

**The split gate is closed** — `parent 732 grandparent 641` — so `needs-human:sizing` is applied with the
measurement and the split is not proposed. The split that would otherwise have been made is a two-way cut at
the obvious seam: **(A)** land the two add controls, remove the button, re-point the four device-suite
helpers, leaving the flat state inert; **(B)** retire the flat state, its events, its navigation channel, the
flat test class and the two legacy collectors.

It is worth recording that the floor rule would have rejected that split independently of the depth gate: B
has no consumer at all — it is almost entirely deletion, and its only "deliverable" is the absence of dead
code — so it fails the one-consumer floor, and the floor wins over the ceiling. Two gates agreeing is why
this is carried as one ticket rather than treated as a near miss.

## Open questions

- `channel_list_empty` reads "Tap + to start a conversation". With the button gone, the no-host blank draws no
  `+` at all — the section headers that carry the pairing plus are inside the tree the blank replaces. The
  copy is left alone here: the blank's survival is the ticket's explicit instruction, the AC names no copy
  change, and inventing product copy is not this ticket's call. Raised in the PR body for the verifier and
  for #715, which owns host removal and therefore owns the state that reaches this blank in practice.
- #736 deferred whether its two bare arrival waits want content-bearing ones. Nothing here changes that, and
  no flake has been observed; still deferred.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** Two untrusted values cross into new UI surfaces. `HostConversationSnapshot.displayName`
  is daemon-authored and reaches two new formatted content descriptions; the design pins those labels to the
  value `boundedRowText` has already clamped, computed once in `TreeHostRow` and passed down, so no path
  formats the raw name. `HostConversationSnapshot.serverId` reaches the UI for the **first time**, as
  `treeHostAddTestTag`'s input. Its provenance is narrower than the name's: it comes from the saved
  `PairedServer` record the operator scanned (`RelayConnectionRegistry` builds every
  `HostConversationConnection` from `record.serverId`), not from a daemon frame — so a hostile relay or daemon
  cannot author it, but a hostile QR still can, and it is unbounded. The design clamps it. Both values reach
  a semantics string and nothing else: no markup, no URL, no filename, no cache key, no log.
- **[Trust boundaries]** SHOULD FIX — the plan says "length-clamped" without saying what happens to two ids
  sharing a clamped prefix. Mirror `treeItemKey`: clamp, then append the original length, so distinct ids stay
  distinct. The consequence of a collision is test-only (two nodes matching one tag), but the fix is one
  expression and the precedent is in the same file's sibling.
- **[Tokens, secrets, credentials]** No new token is minted, stored, read, compared or logged. The design
  widens *when* pairing can be started — from the list, not only from first-run Welcome — which is the
  ticket's purpose. The controls between a scan and a saved server are unchanged and still apply:
  `parsePairingPayload`, the fingerprint confirmation gate, and `confirmPairingAndConnect` writing through
  `PairedServerStore`. A second paired server is an already-supported state, not a new one. Accepted.
- **[File / storage operations]** Not applicable: the diff contains no filesystem call. The workspace
  picker's create-folder path is reached unchanged and validates on the daemon side.
- **[Inter-process / Android attack surface]** Not applicable: no exported component, intent filter, deep
  link, pending intent, content provider or WebView is added. `PairHostTapped` navigates in-process through
  Navigation-Compose to the app-authored `Routes.SCANNER` constant — no `Intent`, and no operand derived from
  daemon input. The camera permission prompt becomes reachable from the list; it is user-initiated and its
  request path is unchanged.
- **[Cryptographic primitives]** Not applicable: no RNG, key, nonce, comparison or handshake code is added or
  moved. Pairing's crypto sits downstream of a route hop this ticket does not enter.
- **[Network & I/O]** No socket, frame, URL or timeout is touched. `createHostDiscussion` sends an existing
  verb over the existing session. Dropping `repository` from `ChannelListViewModel`'s constructor changes no
  lifetime: `ConversationRepository` is a Koin `single` other screens still resolve.
- **[Error messages, logs, telemetry]** SHOULD FIX — no new log call is planned, and none must be added while
  wiring the controls: the host name and `serverId` stay out of `RelayLog`. The existing
  `host_chat_create_started` / `host_workspace_picker_opened` / `host_chat_create_rejected` events are
  content-free and unchanged. Net reduction: retiring the flat state removes the only surface that rendered a
  relay-authored exception message to the user (`"Couldn't load channels: ${state.message}"`).
- **[Concurrency]** OUT OF SCOPE — `pickHostWorkspace` reads `pendingHostWorkspacePicker.value` and then
  clears it without `getAndUpdate`, so two picks landing in one frame could both resolve the same host and
  create two chats. It predates this ticket, is unchanged by this diff, and fixing it would be an out-of-scope
  production edit; the same double-fire shape existed on the button's two paths. No such failure has been
  observed. It belongs with whichever ticket next touches that method.
- **[Concurrency]** No finding on what this ticket does add: the deletions remove a `stateIn` and a buffered
  `Channel` with no remaining sender or collector, and `pickHostWorkspace` reads
  `pendingHostWorkspacePicker`, never the deleted `pendingWorkspacePicker`. The controls launch nothing of
  their own — they call methods already scoped to `viewModelScope` through `launchGuardedRepoCall`.
- **[Threat model alignment]** A hostile relay is content-blind and on-path; it authors none of the new
  inputs. A hostile daemon authors `displayName`, which is bounded and rendered as text into a content
  description. An accessibility service can read the new labels, but they expose no information class the host
  row's own name and fold label did not already expose; `testTag` is not surfaced to accessibility services.
  Token theft from disk, screenshot leakage and overlay attacks are untouched by this diff and stay where they
  were.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-21
