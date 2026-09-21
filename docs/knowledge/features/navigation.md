# Navigation

Single-activity Compose Navigation host. `MainActivity` is the only Activity; all screens are `composable` destinations under one `NavHost`.

## What it does

Boots into `welcome` on a fresh install or `channel_list` when a saved pairing
exists. The graph has nine routes; thread and literal-screen destinations carry
both the owning `serverId` and the host-local `conversationId`.

- **`welcome`** (start destination when no paired-server record exists) — renders `WelcomeScreen` (#7).
- **`scanner`** — renders `ScannerScreen` (#12), stateful + camera-permission-driven since #326 and showing a **live CameraX preview** since #334. A **decoded QR** (not a tap) is parsed + validated into a **real `PairedServer`** (#320 — the [Pairing payload parser](pairing-payload-parser.md)) and, on success, persisted via `PairedServerStore.save(...)` before navigating to `channel_list` with the scanner popped from the back stack (`popUpTo(SCANNER){inclusive=true}`, driven by a `LaunchedEffect(state)` on the `Decoded` state); a parse/persist failure routes to `ScannerUiState.Error`. The TopAppBar back arrow `popBackStack()`s to Welcome. The route owns a `ScannerViewModel`, the runtime permission launcher, and the [Camera preview](camera-preview.md) (injected through a `cameraPreview` slot gated on `ReadyToScan`). See [Scanner screen](scanner-screen.md).
- **`channel_list`** — renders [ChannelListScreen](channel-list-screen.md). The temporary flat list still reads selected-host state, while its route adapter captures a host for row/create/picker actions and consumes `hostNavigationEvents`.
- **`discussions`** — renders [DiscussionListScreen](discussion-list-screen.md), reached from the channel list's recent-discussions link. Its adapter captures host-qualified row and promotion targets and consumes only `hostNavigationEvents`; Back pops the stack.
- **`conversation_thread/{serverId}/{conversationId}`** — renders [ThreadScreen](thread-screen.md#wiring) with a destination-scoped ViewModel and dependencies from the exact retained host. Back pops the stack; “Show the literal screen” passes the same target to `Routes.literal(target)`.
- **`literal_screen/{serverId}/{conversationId}`** — renders [LiteralScreenSurface](literal-screen-surface.md#wiring). Each new back-stack entry owns a fresh `LiteralScreenViewModel`, isolating snapshots even when two hosts use the same conversation id. Request and Retry use that entry's host and conversation.
- **`settings`** — renders `SettingsScreen` (#64; About-row wiring #90; Storage-row wiring #94; About-row copy + License-row treatment #163; About section → single navigable entry #271). Reached by the channel-list TopAppBar's gear `IconButton` (#21) via `ChannelListEvent.SettingsTapped → navController.navigate(Routes.SETTINGS)`. Destination block passes three navigation lambdas: `onBack = { navController.popBackStack() }`, `onOpenArchivedDiscussions = { navController.navigate(Routes.ARCHIVED_DISCUSSIONS) }` (#94), and `onOpenAbout = { navController.navigate(Routes.ABOUT) }` (#271). An earlier third lambda `onOpenLicense = { navController.navigate(Routes.LICENSE) }` from #91 was dropped in #163 along with the `LicenseScreen` parameter; #271's `onOpenAbout` is its structural successor — the whole About section is now a single navigable entry into [`AboutScreen`](about-screen.md). See [Settings screen](settings-screen.md).
- **`archived_discussions`** — renders `ArchivedDiscussionsScreen` (#94) backed by `ArchivedDiscussionsViewModel`; a secondary screen listing archived discussions with a long-press → "Restore" affordance. Reached from the Settings Storage section's "Archived discussions" row. Destination block follows the `koinViewModel<…>()` + `collectAsStateWithLifecycle()` shape; the inline `when (event)` intercepts `ArchivedDiscussionsEvent.BackTapped → navController.popBackStack()` and forwards `is RestoreRequested` to `vm.onEvent(event)` (the VM owns the `unarchive` side-effect). No `navigationEvents` channel — restore stays on-screen and the row drops on the next `MutableStateFlow` re-emission. See [Archived Discussions screen](archived-discussions-screen.md).
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

Until the host/workspace tree in #641, both list screens render their existing
selected-host (or demo) `state`. At row activation, `selectedServerId()` captures
the current relay owner or explicit demo id. Channel creation calls
`createHostDiscussion(serverId)`; long-press opens `openHostWorkspacePicker(serverId)`.
The host picker's captured id drives visibility in the flat `Loaded`/`Empty` states,
and completion/dismissal use `pickHostWorkspace` / `dismissHostWorkspacePicker`.
Asynchronous creation reads that host's default and retains its identity through
the preference read, repository lookup and success navigation; see
[host creation](channel-list-viewmodel.md#one-shot-navigation-via-channelbuffered-22).

Discussion promotion uses `requestHostPromotion(target)`, then
`confirmHostPromotion` / `cancelHostPromotion`. The route projects the captured
host prompt into the flat `Loaded.pendingPromotion` display model; selection changes
do not change the confirmation target. Legacy bare-id navigation flows remain on
the ViewModels for compatibility but are not collected by the production graph.

`HostWorkspaceRepository` wraps both the thread destination and the flat channel
screen with `LocalWorkspacePickerRepository`. It uses the thread's route host or
the list picker's captured host, so recent folders, folder creation and the final
workspace/create action agree on ownership. Binding only the ViewModel leaves the
picker's independent repository lookup exposed to selection changes. Settings and
archive destination migration remains #637; see [WorkspacePicker](workspace-picker.md).

## Adding a route

1. Add a `const val MY_ROUTE = "my_route"` (or `"my_route/{argName}"` for a parameterized route) to `Routes`. Keep the `{name}` placeholder inside the constant — the graph DSL consumes the literal pattern.
2. Add a `composable(Routes.MY_ROUTE) { MyScreen(...) }` block inside `PyryNavHost`. For parameterized routes, declare arguments explicitly: `arguments = listOf(navArgument("argName") { type = NavType.StringType })`. `StringType` is the default, but the explicit form documents the type at the call site and isolates the swap point.
3. For host-owned conversation destinations, reuse `Routes.hostArguments()` and `Routes.target(...)`, guard host membership, and resolve the ViewModel inside the destination. The factory reads `serverId` from the entry's `SavedStateHandle`; the ViewModel keeps the host-local `conversationId`.
4. Wire the screen's navigation callbacks via `navController.navigate(...)` in the lambda passed from `PyryNavHost` — keep the screen Composable itself stateless and `NavController`-free.

Screens take navigation as `() -> Unit` callbacks, not a `NavController`. This is what lets routing and destination wiring land in parallel tickets (the #7 / #8 / #14 pattern).

## Configuration

- **Dependency:** `androidx.navigation:navigation-compose`, pinned via `navigationCompose` in `gradle/libs.versions.toml`. Compose BOM does **not** cover this artifact group — it needs its own version pin.
- **Back-stack policy:** ordinary `navigate(route)` creates destination entries. Thread navigation suppresses only an identical current host/conversation target. Scanner success pops the scanner inclusively; invalid-host rejection returns to the channel list and clears the invalid entries. Those list transitions use `launchSingleTop`; host-qualified thread navigation does not.
- **Start-destination gating:** `NavHost` composition waits for the full saved-host read and successful workspace migration described [above](#how-it-works). Only then does snapshot emptiness choose the initial destination. Later pairing uses explicit navigation; it does not rewrite the back stack.
- **Insets:** the outer `Scaffold` in `MainActivity` owns system-bar insets and passes them down via the NavHost's `Modifier.padding(innerPadding)`. Screens may apply their own `systemBarsPadding()` on top (harmless double-padding); don't refactor existing screens to drop it.

## Edge cases / limitations

- **No type-safe routes yet.** The first parameterized route (`conversation_thread/{conversationId}`, #15; VM-backed since #126) landed on string constants by design — partially migrating one route while siblings stay as `String` is worse than either end-state. A full migration of `Routes` to `@Serializable` data classes remains a separate, larger future ticket; do not bundle it with a feature ticket.
- **No deep links, no animations.** `composable(Routes.X) { ... }` only — no `deepLinks = listOf(...)`, no custom `enterTransition` / `exitTransition`.
## Testing

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

[Production DI tests](dependency-injection.md#testing) separately verify colliding
ids, content and outbound actions. The existing `InteractiveStreamE2ETest` ping
and Reset-session regressions remain in the [live gate](../../e2e-interactive-stream.md#pre-ship-gate).
That gate does not prove two-host navigation/reconnect or phone-reply continuity;
those rung-3 scenarios remain #673.

## Related

- Ticket notes: `../codebase/8.md` (NavHost setup), `../codebase/12.md` (Scanner stub + first destination-block Koin/coroutine wiring), `../codebase/13.md` (conditional start destination + `produceState` gating), `../codebase/14.md` (Welcome `onSetup` → `Intent.ACTION_VIEW` + `LocalContext.current` capture in a `composable` block), `../codebase/15.md` (first parameterized route), `../codebase/16.md` (Settings placeholder + interactive-placeholder factoring rule), `../codebase/46.md` (first VM-backed destination — `koinViewModel<…>()` + `collectAsStateWithLifecycle()` shape, inline `when (event)` → `navigate` translation), `../codebase/21.md` (channel-list `SettingsTapped` → `Routes.SETTINGS` wiring + `material-icons-core` on the classpath), `../codebase/24.md` (`discussions` route — first destination with dual nav wiring + a back-arrow `navigationIcon` reusing `R.string.cd_back`), `../codebase/26.md` (`discussions` route wired into the live graph — `ChannelListEvent.RecentDiscussionsTapped → navController.navigate(Routes.DISCUSSION_LIST)`), `../codebase/126.md` (`conversation_thread/{conversationId}` body flipped from placeholder `Text(...)` to real `ThreadScreen` + `ThreadViewModel`; path-argument extraction moves from `backStackEntry.arguments?.getString(...)` into the VM's `SavedStateHandle` via Koin's `viewModel { ThreadViewModel(get()) }` block), `../codebase/271.md` (`about` route — first static sub-screen added with no VM, mirroring the `archived_discussions` block minus the state-collection machinery), [`../codebase/382.md`](../codebase/382.md) (`literal_screen/{conversationId}` route — second parameterized route; per-back-stack-entry `koinViewModel()` for a fresh, per-conversation VM, plus the `onShowLiteralScreen` pure-navigation callback threaded from the thread overflow menu, mirroring `onOpenAbout`)
- Specs: `docs/specs/architecture/8-navigation-compose-setup.md`, `docs/specs/architecture/12-stub-scanner-screen.md`, `docs/specs/architecture/13-conditional-navhost-start-destination.md`, `docs/specs/architecture/15-conversation-thread-placeholder-route.md`, `docs/specs/architecture/16-settings-placeholder-route.md`, `docs/specs/architecture/21-channel-list-top-app-bar.md`, `docs/specs/architecture/126-thread-screen-skeleton.md`, `docs/specs/architecture/271-dedicated-about-screen.md`
- Consumers: [Welcome screen](welcome-screen.md), [Scanner screen](scanner-screen.md), [Paired server store](paired-server-store.md) (read by the start-destination gate + written by the Scanner placeholder, #295), [Thread screen](thread-screen.md) (VM-backed destination since #126), [Settings screen](settings-screen.md) + [About screen](about-screen.md) (the `settings` → `about` sub-screen pair, #271), [Archived Discussions screen](archived-discussions-screen.md)
- Follow-ups: Phase 2 thread UI (the outer shell at `conversation_thread/{conversationId}` shipped in #126; downstream slices #128–#140 / #145 fill the message list, input bar, status row, connection banner, session-boundary delimiter, empty states, and TopAppBar overflow), Phase 3 Settings sections (data-layer wiring for remaining no-op rows; `SettingsScreen` shell + About-section Version/Open-source rows already wired since #64 / #90; License row is text-only since #163), Phase 4 pairing — the `scanner` body is now real CameraX + ML Kit QR (#326 permission flow, #333 decode core, #334 live preview); remaining: payload parse → real `PairedServer` (#320), fingerprint/safety-number gate (#321)
