# Navigation

Single-activity Compose Navigation host. `MainActivity` is the only Activity; all screens are `composable` destinations under one `NavHost`.

## What it does

Boots into `welcome` on a fresh install or `channel_list` when a saved pairing
exists. The graph has ten routes; thread and literal-screen destinations carry
both the owning `serverId` and the host-local `conversationId`.

- **`welcome`** (start destination when no paired-server record exists) — renders `WelcomeScreen` (#7).
- **`scanner`** — renders [ScannerScreen](scanner-screen.md) with its destination-scoped ViewModel, camera permission launcher and live preview. A decoded QR is parsed into an immutable fingerprint/record confirmation state without writing. Confirm saves, starts the controller and navigates to `channel_list`, popping the scanner inclusively; this camera path does not await encrypted readiness. Decline/Back from confirmation re-arms scanning. Paste actions navigate to `pair_code`; ordinary Back pops to the caller.
- **`pair_code`** — renders [PairCodeScreen](paste-code-dialog.md) with a destination-scoped `PairCodeViewModel`. Its optional-name form, fingerprint confirmation and saved-target connection wait stay within one route. Cancel returns to the caller; success clears the previous graph entries and opens `channel_list` only after both target connection legs are ready.
- **`channel_list`** — renders [ChannelListScreen](channel-list-screen.md), fed entirely by host-qualified state since #738 retired the flat compatibility model and its `selectedServerId()` adapter for this screen. Its own section-header add control also opens `scanner` (`ChannelListEvent.PairHostTapped → navController.navigate(Routes.SCANNER)`, #738) — the first entry into pairing that does not require an unpaired phone, reusing the existing `scanner` destination above rather than adding a second flow; both of that destination's completions (camera confirm, and manual entry via `pair_code`) already land back here.
- **`discussions`** — renders [DiscussionListScreen](discussion-list-screen.md). Unreachable since #731 retired the channel list's "see all" link that was its only entry point; the route, screen and its adapter (captures host-qualified row and promotion targets, consumes only `hostNavigationEvents`) stay in the graph regardless — removing them is out of scope for both #731 and #738.
- **`conversation_thread/{serverId}/{conversationId}`** — renders [ThreadScreen](thread-screen.md#wiring) with a destination-scoped ViewModel and dependencies from the exact retained host. Back pops the stack; “Show the literal screen” passes the same target to `Routes.literal(target)`.
- **`literal_screen/{serverId}/{conversationId}`** — renders [LiteralScreenSurface](literal-screen-surface.md#wiring). Each new back-stack entry owns a fresh `LiteralScreenViewModel`, isolating snapshots even when two hosts use the same conversation id. Request and Retry use that entry's host and conversation.
- **`settings?serverId={serverId}`** — renders `SettingsScreen` (#64; About-row wiring #90; Storage-row wiring #94; About-row copy + License-row treatment #163; About section → single navigable entry #271; host ownership #749). Reached by the settings entry on the channel list's own bar (#21, redrawn as its own bar by #737) via `ChannelListEvent.SettingsTapped → navController.navigate(Routes.settings(destinations.selectedServerId()))` — the gear captures the current host's exact server id **once, at tap time**, the same compatibility adapter the list's other temporary consumers use (#749). Destination block passes four navigation lambdas: `onBack = { navController.popBackStack() }`, `onOpenArchivedDiscussions` (nullable since #715 — non-null only when this destination's own captured owner is non-empty, `{ navController.navigate(Routes.archive(owner)) }`; `null` when it owns no host, which `SettingsRow` renders inert rather than offering a tap that could only be rejected) (#94, rebound to an owner by #715), `onOpenAbout = { navController.navigate(Routes.ABOUT) }` (#271), and `onPairServer = { navController.navigate(Routes.SCANNER) }` (#749 — the same destination the channel list's own pairing entry opens, #738). An earlier lambda `onOpenLicense = { navController.navigate(Routes.LICENSE) }` from #91 was dropped in #163 along with the `LicenseScreen` parameter; #271's `onOpenAbout` is its structural successor — the whole About section is now a single navigable entry into [`AboutScreen`](about-screen.md). See [Settings screen](settings-screen.md).
- **`archived_discussions/{serverId}`** — renders `ArchivedDiscussionsScreen` (#94) backed by `ArchivedDiscussionsViewModel`; a secondary screen listing archived discussions with an inline restore affordance. **Owned by a required path segment since #715** (unlike Settings' optional query argument above — Settings has to stay open for an unpaired phone, Archive has no such case, so a route that cannot express "no owner" is the cheapest way to keep one from being invented) and, **unlike Settings, wrapped in `HostDestination`**: an unknown or newly-removed owner bounces to `channel_list` rather than falling through to another host's archive, which is exactly what keeps a colliding conversation id on two hosts from restoring the wrong one. Reached from the Settings Storage section's "Archived discussions" row (inherits the owner Settings' own destination already holds — see above) and, since #737, from the channel list's own bar via `ChannelListEvent.ArchiveTapped → destinations.selectedServerId()?.let { navController.navigate(Routes.archive(it)) }` (rebound from the pre-#715 unscoped `Routes.ARCHIVED_DISCUSSIONS` navigate) — the same destination, two doors, each capturing its owner from a deliberately different source: Settings' row inherits a destination-held owner, the list's entry re-reads selection on every tap. Destination block follows the `koinViewModel<…>()` + `collectAsStateWithLifecycle()` shape, additionally collecting `vm.host` for the header; the inline `when (event)` intercepts `ArchivedDiscussionsEvent.BackTapped → navController.popBackStack()` and forwards `is RestoreRequested` / `is TabSelected` to `vm.onEvent(event)` (the VM owns the `unarchive` side-effect). No `navigationEvents` channel — restore stays on-screen and the row drops on the next `MutableStateFlow` re-emission. See [Archived Discussions screen](archived-discussions-screen.md).
- **`about`** — renders [`AboutScreen`](about-screen.md) (#271); a **static** sub-screen (no ViewModel) listing the app version + build SHA, the open-source repo link, an inert privacy-policy row, and the MIT license line. Reached from the Settings About section's "About" row via `onOpenAbout`. Destination block is the minimal `composable(Routes.ABOUT) { AboutScreen(onBack = { navController.popBackStack() }) }` — no `koinViewModel<…>()`, no `collectAsStateWithLifecycle()`, no `onEvent` (the screen is stateless apart from `rememberScrollState()`). Both the TopAppBar back arrow and the system back gesture return to Settings via the default `popBackStack()`. Shows that a sub-screen with no real state drops the VM/state-collection machinery the `archived_discussions` block carries. See [About screen](about-screen.md).

## How it works

`MainActivity.setContent` uses `produceState<Boolean?>`, keyed by both the injected
`PairedServerCollectionStore` and `AppPreferences`, to read the full saved-host
collection once with `list()`. It passes the set of exact `entry.record.serverId`
values to `migrateDefaultWorkspace` and awaits success before composing `PyryNavHost`.
Case and whitespace remain significant; the latest-host `load()` cannot establish
ownership when several hosts are saved.

While collection loading or migration is pending, the value stays null and the
existing neutral `Surface` keeps both pairing and conversation creation unreachable.
A returned migration failure keeps that surface in place, with no automatic retry
or retry UI; restarting the Activity can retry. Composition disposal cancels the
work, and cancellation does not open navigation. Startup logs contain only static
event/outcome codes.

On success, a nonempty snapshot selects `channel_list`; an empty snapshot selects
`welcome`. One initial host receives an eligible legacy default only if its own
key is absent. Zero or multiple initial hosts permanently record no legacy owner,
so a later pairing cannot inherit the unowned path. Missing or unreadable paired
storage counts as an empty snapshot. Restarts recheck migration, but its persisted
decision prevents another transfer or owner change. See the
[workspace storage contract](app-preferences.md#what-it-does). Completing this
decision before exposing pairing prevents a new host from changing the initial
ownership set.

The internal graph defaults to `rememberNavController()` and accepts a controller
for production-route tests. Pairing later in the session navigates explicitly; it
does not rewrite the graph's initial destination. Screens receive callbacks, never
a `NavController`.

### Manual pairing entry and return

Every scanner paste action (viewport, denied and error) navigates to
`Routes.PAIR_CODE`; the scanner remains underneath. The route resolves
`koinViewModel<PairCodeViewModel>()` from `appModule`, using the observable
collection store and registry. Its state holds both drafts in memory, without
saving the pairing code in navigation arguments or saved instance state.
The channel list's own section-header add control is a list entry point into this flow
(`scanner`, then this route via Paste — #738); it is the first door into pairing reachable
from an already-paired phone, where previously only the unpaired `welcome` screen had one.

Pair validates the trimmed code and opens the existing fingerprint surface in
the same destination. Confirm uses exactly the record displayed there. The host
name is trimmed only for persistence: nonblank names are local metadata, while
blank names leave new hosts unnamed and preserve existing names. Writes target
the exact case-sensitive server id; names and relay URLs never identify a host.

| Phase | Cancel, toolbar Back or Android Back |
| --- | --- |
| Editing, including failure feedback | Enter Cancelled and pop to the invoking destination; make no further writes. |
| Confirming | Decline/Android Back returns to the unchanged draft without saving. |
| Saving credentials/name | Dismissal and editing are blocked until persistence finishes. |
| Connecting | Cancel the wait, enter Cancelled and pop; later readiness cannot navigate. |

Credential-save failure cannot start a new connection. Name-write or connection
failure after saving returns to the draft with explicit retained-pairing feedback
and Retry. Retry crosses the fingerprint gate again and upserts the same host for
an unchanged code. Cancel does not undo saved credentials or a successful name
write. See [failure behavior](paste-code-dialog.md#failure-and-cancellation).

The connection wait follows the complete saved record through registry
reconciliation, including replacement credentials on re-pairing. Another host's
connection, a stale bundle or bare relay readiness cannot complete it. Both relay
and encrypted-session status must be Connected within 30 seconds; a new
unavailable status ends the wait earlier.

`LaunchedEffect(state.phase)` translates Cancelled to `popBackStack()` and Complete
to `navigate(CHANNEL_LIST)` with `popUpTo(navController.graph.id) { inclusive = true }`
and `launchSingleTop = true`. Successful manual pairing therefore leaves no
onboarding entry to return to. Registry connection lifetime remains independent
of the destination and follows app lifecycle.

### Host-qualified destinations

The internal `Routes` object is shared with navigation tests. `Routes.thread(target)`
and `Routes.literal(target)` each URI-encode `serverId` and `conversationId`
independently. `Routes.hostArguments()` declares both as `NavType.StringType`, and
`Routes.target(arguments)` reconstructs the exact `HostConversationTarget`. Reserved
characters cannot become route separators. Domain ids and wire payloads remain
host-local; do not encode the host into a repository conversation id.

Both list destinations collect only their ViewModel's `hostNavigationEvents` in
`LaunchedEffect(vm)` and pass each target to `openThread`. The flat row callback
invokes the host command rather than navigating separately. `openThread` suppresses
only a target identical to the current thread's full pair. A/A/B in one burst
therefore yields one A entry and a distinct B entry, even when conversation ids
collide. Do not use `launchSingleTop` on this parameterized thread route: it can
retain the previous entry's ViewModel across different host arguments.

Resolve `koinViewModel()` inside each guarded destination so its
`NavBackStackEntry` owns the ViewModel and seeds its `SavedStateHandle` with both
identifiers. Back reveals the prior entry; reopening after a pop creates a new
one. Saved back-stack restoration preserves the same host/conversation pair.
Thread-to-literal navigation copies the target from the thread entry, never from
compatibility selection. Literal Retry re-fetches through that destination's
repository; snapshot text is not written to saved state.

### Settings: an optionally-owned destination

`Routes.SETTINGS` is `"settings?serverId={serverId}"` — an **optional query argument**
(`Routes.settingsArguments()`, `defaultValue = ""`), not a path segment, because a path segment has
no empty form and this destination has to open on a phone with nothing paired (#749). Navigation
Compose matches the bare `"settings"` route against that pattern via the argument's own default —
confirmed on-device rather than assumed, since it was an open question in the plan. Three helpers
join the existing `thread`/`literal`/`hostArguments`/`target` set: `Routes.settings(serverId: String?)`
(bare `"settings"` when null/empty, else `Uri.encode`d into the query param — same per-component
encoding discipline as `Routes.thread`), `Routes.settingsArguments()`, and `Routes.settingsOwner(arguments)`.

This destination is **deliberately not wrapped in `HostDestination`**: that guard bounces an unknown
or removed host back to `channel_list`, which is exactly wrong here — Settings is where an unpaired
or newly-unpaired phone goes to pair, so it must stay open when its captured owner resolves to
nothing. `SettingsViewModel.connection` (see [Settings ViewModel](settings-viewmodel.md)) carries that
tolerance instead: a blank or unrecognised owner is a `SettingsConnectionState`, not a navigation
rejection. `ThreadDestinationFactory.settings(handle, preferences, repository)` reads `serverId` from
the `SavedStateHandle` the same way `thread`/`literal` do, but never resolves it to a connection
bundle — Settings reads identity and status only, so a saved-but-disconnected owner is still its
owner.

**Host-to-host navigation out of Settings (#750).** Since #750 the Connection section draws one row
per saved host, and tapping any row but the destination's own re-enters this same route with a
different captured owner: `navController.navigate(Routes.settings(serverId)) { popUpTo(Routes.SETTINGS) { inclusive = true } }`.
This is **lateral** movement between two instances of one destination, not descent, and the
`popUpTo … inclusive` is the deliberate choice that follows from that: it destroys the current
Settings `NavBackStackEntry` (and with it the `ViewModel` that captured the old owner) before pushing
the new one, so Back from *any* host's Settings returns to the channel list a hop chain started from,
rather than retracing every hop A→B→A→B made along the way. `launchSingleTop` is **not** the
alternative it looks like: it reuses the current back-stack entry, which would keep the existing
`ViewModel` — and the owner it captured at construction — alive while the route argument underneath it
changed, silently reintroducing the compatibility-selection-style bug #749 fixed. The owner's own row
carries no `onClick` at all, so a self-navigation loop back to the same Settings instance is
structurally impossible rather than merely suppressed.

### Archive: a required-owner destination, two doors (#715)

Where Settings' route argument is optional because the destination has to open with no owner,
`Routes.ARCHIVED_DISCUSSIONS = "archived_discussions/{serverId}"` is a **required** path segment: Archive
has no legitimate no-owner state, so making one inexpressible in the route is cheaper than guarding
against it appearing. Three helpers mirror `thread`/`literal`/`settings`: `Routes.archive(serverId)`
(`Uri.encode`d into the segment), `Routes.archiveArguments()`, and `Routes.archiveOwner(arguments)`.
And where Settings is deliberately **not** wrapped in `HostDestination` (an unpaired phone must be able
to open it), Archive **is** — the same shape `thread`/`literal` use — because an unknown or
newly-removed owner falling through to another host's rows is exactly the failure this ticket closes.
The device test's removal case proves the guard fires by asserting the destination's *departure*, not
merely the other host's absence: a host-bound repository under an unknown owner still emits
`emptyList()`, which renders as a plausible empty archive and would hide a guard that silently failed.

The destination has **two doors**, and — the lesson a rework on this ticket cost — changing the shape
of the route constant from a bare string to a pattern is a fan-out change even though the declaration
is one line: every caller has to follow. Settings' own "Archived discussions" row is one door, and
inherits the owner its own destination already captured (`Routes.settingsOwner(arguments)`), building
`onOpenArchivedDiscussions` only when that owner is non-empty. The channel list's own archive entry
(`ChannelListEvent.ArchiveTapped`, #737) is the second, independent door, and reads compatibility
selection **fresh at every tap** — `destinations.selectedServerId()?.let { navController.navigate(Routes.archive(it)) }`
— the same source the settings gear beside it captures from. A rework on this ticket had to repair this
second call site: it still navigated to the bare `Routes.ARCHIVED_DISCUSSIONS` constant after that
constant became a route pattern, so Navigation matched the pattern and bound the literal text
`{serverId}` as the owner, and `HostDestination` rejected it as unknown and bounced the tap straight
back to the list it came from — a live, operator-facing affordance going nowhere, missed by
`ChannelListScreenTest.archiveEntry_emitsArchiveTapped` (which asserts only that the event is emitted,
never where it lands) and by every route-level test, because all of them — including the preserved
`InteractiveStreamE2ETest.interactiveTurn_archiveRestore_roundTripsListMembership` — reach Archive
through the Settings door alone. Neither door can pass a blank owner into `Routes.archive`: Settings
draws its row inert on `null`, and the list's tap does nothing with no host selected.

The ViewModel side of this ownership is [`ThreadDestinationFactory.archive`](dependency-injection.md#destination-ownership) — the exact-host
repository seam from #636, resolved once at construction rather than per restore tap, so a selection
change, reconnect or unpair can move neither the rows nor a pending write. See
[Archived Discussions screen § Settings row + nav graph](archived-discussions-screen.md#settings-row--nav-graph)
for the full route/binding/header account.

### Host availability

`HostDestination` observes registry membership before resolving the ViewModel.
A saved host whose retained bundle has not initialized yet waits without rendering
another host's content. An unknown or removed host returns to `channel_list`,
clearing the invalid entries above it; the rejection log contains only a static
code. A disconnected or handshaking bundle remains a valid destination, with the
existing unavailable-action behavior and no selection fallback.

[ThreadDestinationFactory](dependency-injection.md#destination-ownership) binds
reads and actions to the retained owner. Reconnect switches its concrete repository
without changing route ownership; changing compatibility selection cannot redirect
the open thread, permission prompt, picker or literal Retry. Demo routes carry the
explicit `demo` id and use the existing fake singleton with inert live/modal/control
dependencies, even when relay hosts are saved. Creation reads the `demo` workspace
default or scratch, independently of any paired host's migrated legacy default.

### Temporary flat-list compatibility

[ChannelListScreen](channel-list-screen.md)'s assembled conversation tree (#729/#730/#731, split from
\#641) resolves each row's host from the row itself — `TreeRowTapped(HostConversationTarget)` maps straight to
`vm.onHostRowTapped(target)`, with no `selectedServerId()` lookup. `TreeHostAddTapped(serverId)` /
`TreeHostAddLongPressed(serverId)` (#738) carry the same discipline into creation: they map straight to
`vm.createHostDiscussion(serverId)` / `vm.openHostWorkspacePicker(serverId)` against the add control's own
row, never `selectedServerId()`. `ChannelListScreen` and its `ChannelListViewModel` therefore have **no**
remaining consumer of the compatibility adapter below — #738 retired the flat `ChannelListUiState`, its
`onEvent` reducer and the `repository` constructor parameter that fed them, along with the last FAB path
that read `selectedServerId()` for this screen. The still-unreachable `DiscussionListScreen`
(`Routes.DISCUSSION_LIST`, left in the graph but with no way to navigate to it since #731 retired its "see
all" entry point) is the adapter's sole remaining consumer: at row tap, `selectedServerId()` captures the
current relay owner or explicit demo id and `RowTapped` / `SaveAsChannelRequested` resolve through it.

Discussion promotion uses `requestHostPromotion(target)`, then
`confirmHostPromotion` / `cancelHostPromotion`. The route projects the captured
host prompt into the flat `Loaded.pendingPromotion` display model; selection changes
do not change the confirmation target. Legacy bare-id navigation flows remain on
the ViewModels for compatibility but are not collected by the production graph.

`HostWorkspaceRepository` wraps both the thread destination and the channel list with
`LocalWorkspacePickerRepository`. The list side passes `hostState.workspacePickerServerId`
directly — the exact host the host row's long-press opened the picker for (#738; previously
the same field, but populated via the FAB's `selectedServerId()` capture rather than the row's
own id) — so recent folders, folder creation and the final workspace/create action agree on
ownership. Binding only the ViewModel leaves the picker's independent repository lookup
exposed to selection changes. Settings and archive destination migration remains #637; see
[WorkspacePicker](workspace-picker.md).

## Adding a route

1. Add a `const val MY_ROUTE = "my_route"` (or `"my_route/{argName}"` for a parameterized route) to `Routes`. Keep the `{name}` placeholder inside the constant — the graph DSL consumes the literal pattern.
2. Add a `composable(Routes.MY_ROUTE) { MyScreen(...) }` block inside `PyryNavHost`. For parameterized routes, declare arguments explicitly: `arguments = listOf(navArgument("argName") { type = NavType.StringType })`. `StringType` is the default, but the explicit form documents the type at the call site and isolates the swap point.
3. For host-owned conversation destinations, reuse `Routes.hostArguments()` and `Routes.target(...)`, guard host membership, and resolve the ViewModel inside the destination. The factory reads `serverId` from the entry's `SavedStateHandle`; the ViewModel keeps the host-local `conversationId`.
4. Wire the screen's navigation callbacks via `navController.navigate(...)` in the lambda passed from `PyryNavHost` — keep the screen Composable itself stateless and `NavController`-free.

Screens take navigation as `() -> Unit` callbacks, not a `NavController`. This is what lets routing and destination wiring land in parallel tickets (the #7 / #8 / #14 pattern).

## Configuration

- **Dependency:** `androidx.navigation:navigation-compose`, pinned via `navigationCompose` in `gradle/libs.versions.toml`. Compose BOM does **not** cover this artifact group — it needs its own version pin.
- **Back-stack policy:** ordinary `navigate(route)` creates destination entries. Thread navigation suppresses only an identical current host/conversation target. Camera success pops the scanner inclusively; manual pairing success clears the graph's previous entries. Invalid-host rejection returns to the channel list and clears the invalid entries. Those list transitions use `launchSingleTop`; host-qualified thread navigation does not.
- **Start-destination gating:** `NavHost` composition waits for the full saved-host read and successful workspace migration described [above](#how-it-works). Only then does snapshot emptiness choose the initial destination. Later pairing uses explicit navigation; it does not rewrite the back stack.
- **Insets:** the outer `Scaffold` in `MainActivity` owns system-bar insets and passes them down via the NavHost's `Modifier.padding(innerPadding)`. Screens may apply their own `systemBarsPadding()` on top (harmless double-padding); don't refactor existing screens to drop it.

## Edge cases / limitations

- **No type-safe routes yet.** The first parameterized route (`conversation_thread/{conversationId}`, #15; VM-backed since #126) landed on string constants by design — partially migrating one route while siblings stay as `String` is worse than either end-state. A full migration of `Routes` to `@Serializable` data classes remains a separate, larger future ticket; do not bundle it with a feature ticket.
- **No deep links, no animations.** `composable(Routes.X) { ... }` only — no `deepLinks = listOf(...)`, no custom `enterTransition` / `exitTransition`.
## Testing

`PairCodeScreenTest.cancelToolbarAndAndroidBackReturnToCallerWithoutSaving`
mounts the production graph and opens `pair_code` over both Welcome and the
channel list. It exercises all three exit actions after validation failure and
checks the collection is unchanged. Directly navigating from the list here tests
return semantics, not the future #641 entry affordance. The
[pair-code tests](paste-code-dialog.md#testing) separately cover confirmation,
failure/retry, cancellation fencing and actual keyboard reachability.

[`StartupWorkspaceMigrationTest`](../../../app/src/androidTest/java/de/pyryco/mobile/StartupWorkspaceMigrationTest.kt)
launches the actual `MainActivity` with a controlled collection store and real
`AppPreferences` over a gated DataStore. Delay the collection read and migration
write independently and assert that neither pairing nor creation is exposed.
Returned IO failure must leave both navigation and the ownership decision pending;
a fresh launch after recovery can succeed. Zero/multiple-host launches followed by
a single-host launch must remain ownerless, while an initial sole owner remains
unchanged. [Storage tests](app-preferences.md#testing) separately prove durable
reopening and transaction atomicity.

Close and relaunch the Activity for cold-start ownership tests.
`ActivityScenario.recreate()` restores the existing navigation stack, including
Welcome; expecting it to choose a new initial route tests the wrong lifecycle
([#712 test finding](https://github.com/pyrycode/pyrycode-mobile/pull/717)).

`LiteralScreenNavigationTest` mounts the production `PyryNavHost`, `Routes` and
Koin bindings directly, bypassing the Activity startup gate. It cannot prove
migration completes before navigation opens. It covers both host event streams,
A/A/B duplicate suppression, reserved characters, distinct destination ViewModels,
back/reopen, saved-state restoration, thread overflow to literal, Retry and
invalid-host return. A copied minimal graph can pass while production route
arguments or bindings are wrong.
Its picker cases open the actual descendant component with two Noise peers,
distinct recents and assertions that the other host receives no picker reads or
writes across selection changes and reconnect.

`SettingsNavigationTest` (#749; extended #750) copies that harness — production `PyryNavHost`,
`Routes` and Koin bindings, two Noise peers, no Activity startup gate — for the Settings destination
specifically. It proves the captured owner survives a compatibility-selection change (closing one
host's supervisor first, so a screen still following selection could not keep showing a connected
relay), saved-state restoration and Back-then-reopen with a different selection; and that an unknown
owner (`Routes.settings("ghost")`) and a destination opened with `Routes.settings(null)` both keep
Settings open with no saved host's identity claimed as this one's. Server ids carry reserved
characters (`A /?#%`) so the query-argument encoding is proven, not assumed. Since #750, both saved
hosts are drawn on screen throughout every scenario — what identifies the destination's owner is now
the badge on its row (`assertBadgedRowIs`), not the other host's absence, since the other host is no
longer absent. Two scenarios added by #750: every saved host renders its own live identity and status
and follows a rename and a status change while Settings stays open, without leaving the screen; and
tapping the non-owner row hops to that host's own Settings by its **exact** id (proven against
Alpha's reserved-character id, not Bravo's plain one) and one `popBackStack()` from there reaches the
channel list rather than retracing the hop — the `popUpTo … inclusive` back-stack behaviour above,
proven on device.

`ArchiveNavigationTest` (#715) copies that same harness for the Archive destination: both hosts hold
an archived conversation under the **same** id, proving the colliding-id case means giving two hosts
the same id rather than two different ones. It covers the captured owner surviving a
compatibility-selection change, saved-state restoration and Back/reopen under a different selection;
restore reaching only the owner's repository while the other host's peer receives no archive verb at
all and its matching-id row stays archived; removing the owner while Archive is open ending in a
departure to `channel_list` rather than a silently-empty render of another host's rows; and, separately,
the channel list's own archive entry opening the selected host's archive and following a later
selection change on a second tap — the case added in this ticket's rework once the verifier found that
door still bounced. See [Archived Discussions screen § Testing](archived-discussions-screen.md#testing).

[Production DI tests](dependency-injection.md#testing) separately verify colliding
ids, content and outbound actions. The existing `InteractiveStreamE2ETest` ping
and Reset-session regressions remain in the [live gate](../../e2e-interactive-stream.md#pre-ship-gate).
That gate does not prove two-host navigation/reconnect or phone-reply continuity;
those rung-3 scenarios remain #673, and the two-host archive/restore rung-3 scenario specifically is #676.

## Related

- Ticket notes: `../codebase/8.md` (NavHost setup), `../codebase/12.md` (Scanner stub + first destination-block Koin/coroutine wiring), `../codebase/13.md` (conditional start destination + `produceState` gating), `../codebase/14.md` (Welcome `onSetup` → `Intent.ACTION_VIEW` + `LocalContext.current` capture in a `composable` block), `../codebase/15.md` (first parameterized route), `../codebase/16.md` (Settings placeholder + interactive-placeholder factoring rule), `../codebase/46.md` (first VM-backed destination — `koinViewModel<…>()` + `collectAsStateWithLifecycle()` shape, inline `when (event)` → `navigate` translation), `../codebase/21.md` (channel-list `SettingsTapped` → `Routes.SETTINGS` wiring + `material-icons-core` on the classpath), `../codebase/24.md` (`discussions` route — first destination with dual nav wiring + a back-arrow `navigationIcon` reusing `R.string.cd_back`), `../codebase/26.md` (`discussions` route wired into the live graph — `ChannelListEvent.RecentDiscussionsTapped → navController.navigate(Routes.DISCUSSION_LIST)`), `../codebase/126.md` (`conversation_thread/{conversationId}` body flipped from placeholder `Text(...)` to real `ThreadScreen` + `ThreadViewModel`; path-argument extraction moves from `backStackEntry.arguments?.getString(...)` into the VM's `SavedStateHandle` via Koin's `viewModel { ThreadViewModel(get()) }` block), `../codebase/271.md` (`about` route — first static sub-screen added with no VM, mirroring the `archived_discussions` block minus the state-collection machinery), [`../codebase/382.md`](../codebase/382.md) (`literal_screen/{conversationId}` route — second parameterized route; per-back-stack-entry `koinViewModel()` for a fresh, per-conversation VM, plus the `onShowLiteralScreen` pure-navigation callback threaded from the thread overflow menu, mirroring `onOpenAbout`)
- Specs: `docs/specs/architecture/8-navigation-compose-setup.md`, `docs/specs/architecture/12-stub-scanner-screen.md`, `docs/specs/architecture/13-conditional-navhost-start-destination.md`, `docs/specs/architecture/15-conversation-thread-placeholder-route.md`, `docs/specs/architecture/16-settings-placeholder-route.md`, `docs/specs/architecture/21-channel-list-top-app-bar.md`, `docs/specs/architecture/126-thread-screen-skeleton.md`, `docs/specs/architecture/271-dedicated-about-screen.md`, `docs/specs/architecture/749-settings-destination-host-owner.md` (optional-owner route + `HostDestination`-guard exemption), `docs/specs/architecture/750-settings-saved-host-connection-rows.md` (host-to-host navigation + `popUpTo … inclusive` back-stack decision), `docs/specs/architecture/715-archive-host-owner.md` (required-owner route + `HostDestination` wrap + the two-doors fix)
- Consumers: [Welcome screen](welcome-screen.md), [Scanner screen](scanner-screen.md), [Paired server store](paired-server-store.md) (read by startup and written by confirmed camera/manual pairing), [Thread screen](thread-screen.md) (VM-backed destination since #126), [Settings screen](settings-screen.md) + [About screen](about-screen.md) (the `settings` → `about` sub-screen pair, #271), [Archived Discussions screen](archived-discussions-screen.md)
- Follow-ups: Phase 2 thread UI (the outer shell at `conversation_thread/{conversationId}` shipped in #126; downstream slices #128–#140 / #145 fill the message list, input bar, status row, connection banner, session-boundary delimiter, empty states, and TopAppBar overflow), Phase 3 Settings sections (data-layer wiring for remaining no-op rows; `SettingsScreen` shell + About-section Version/Open-source rows already wired since #64 / #90; License row is text-only since #163), pairing — parsing and fingerprint confirmation are shipped for both camera and manual input; scanner redesign remains #640, list entry points #641, and the real two-host pairing/rename/unpair scenario #676 (which also owns the two-host archive/restore rung-3 e2e)
