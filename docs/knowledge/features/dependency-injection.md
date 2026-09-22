# Dependency injection

Koin is the project's DI framework. `PyryApp.onCreate` loads `appModule` and
`conversationRepositoryModule(useRelay)` from `de/pyryco/mobile/di/AppModule.kt`.
The selector includes `hostConversationModule(useRelay)` for the shared host list
source and destination factory, and binds the compatibility `ConversationRepository`
interface.

## What it does

`appModule` wires concrete repositories, connection owners, settings and ViewModels;
the selector modules choose the shared host source, destination dependencies and
compatibility repository.
Composables resolve dependencies with `koinViewModel()` / `koinInject()` from
`koin-androidx-compose`; non-Compose code uses `by inject()` / `get()`.
See `AppModule.kt` for the current bindings.

## How it works

`PyryApp : Application` runs `startKoin { androidContext(this@PyryApp); modules(appModule, conversationRepositoryModule()) }` in `onCreate`. Android guarantees `Application.onCreate` finishes before any activity is created, so any `koinViewModel()` / `by inject()` call from a composable or activity is safe by construction. No nullable container, no lazy init guard — Koin is ready by the time UI code runs.

```kotlin
// de/pyryco/mobile/PyryApp.kt
class PyryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@PyryApp)
            // Real repository by default; -PuseRelayRepository=false selects the demo.
            modules(appModule, conversationRepositoryModule())
        }
    }
}

// de/pyryco/mobile/di/AppModule.kt
val appModule = module {
    single<DataStore<Preferences>> { /* … */ }   // #11
    single { AppPreferences(get()) }              // #11
    single { FakeConversationRepository() }       // #45/#350 — concrete-only; interface bound below
    single { StableConversationRepository(get<RelayConnectionRegistry>().currentRepository) }
    viewModel { ChannelListViewModel(get(), get(), get()) }
    viewModel { DiscussionListViewModel(get(), get()) }
    viewModel { get<ThreadDestinationFactory>().settings(get(), get()) }   // #749; dropped its `repository` arg in #715
    viewModel { get<ThreadDestinationFactory>().archive(get()) }           // #715, replacing the pre-#715 one-arg ArchivedDiscussionsViewModel(get())
    viewModel { get<ThreadDestinationFactory>().thread(get(), get()) }
    viewModel { get<ThreadDestinationFactory>().literal(get()) }
}

// The #350 selector — the *only* module that binds the ConversationRepository interface.
fun conversationRepositoryModule(useRelay: Boolean = BuildConfig.USE_RELAY_REPOSITORY) = module {
    includes(hostConversationModule(useRelay))
    single<ConversationRepository> {
        if (useRelay) get<StableConversationRepository>() else get<FakeConversationRepository>()
    }
}

fun hostConversationModule(
    useRelay: Boolean,
    decorateRepository: (ConversationRepository) -> ConversationRepository = { it },
) = module {
    single { ThreadDestinationFactory(useRelay, get(), get(), get(), decorateRepository) }
    single {
        // #796: the demo branch resolves no cache — there is nothing persisted for a fake host to restore.
        if (useRelay) HostConversationSource.relay(get(), cache = get())
        else HostConversationSource.demo(get<FakeConversationRepository>())
    } onClose { it?.dispose() }
}
```

`app/build.gradle.kts` generates `BuildConfig.USE_RELAY_REPOSITORY` from the
`useRelayRepository` Gradle property. It defaults to `true`, selecting the existing
`StableConversationRepository` singleton; `-PuseRelayRepository=false` selects the
existing `FakeConversationRepository` singleton. Only the exact values `true` and
`false` are accepted; other values fail Gradle configuration. This is a build-time
selection with no runtime setter. See [README Build](../../../README.md#build).

The selector chooses the compatibility repository, host source and destination
factory together. The lifecycle driver and registry still control every saved
host's connection establishment in both modes; selecting the fake does not disable
that stack.

`appModule` eagerly owns `RelayConnectionRegistry` and disposes it on Koin close.
Both pairing-store interfaces resolve one [observable decorator](paired-server-store.md#wiring--usage).
Its initial and successful-mutation revisions drive collection reconciliation;
the registry uses `RelayConnectionFactory.create(record)` for one retained bundle
per exact server id. Foreground/background lifetime belongs to this registry.

`RelayConnectionController` and `ConnectionStateSource` resolve the registry.
The compatibility stable repository and Settings status follow its latest-saved
surviving selection. Host-qualified threads use the exact owner described below.
No separate compatibility connection is created. With no selection, the registry
projections remain resolvable with empty repository/events, hidden modal and
idle/down status. See
[bundle configuration](relay-repository-coordinator.md#configuration).

Concrete `RelayConnectionBundle`, `RelayConnectionSupervisor`, `NoiseSessionFactory`
and `RelayRepositoryCoordinator` bindings are Koin **factory resolutions of the
selected retained owner**, not singleton snapshots or newly constructed bundles.
They serve test/diagnostic callers, including deterministic reconnect helpers;
resolving them without a selection fails with `no paired host`. Long-lived
compatibility consumers use registry projections; host-qualified destinations
resolve `connectionFor(serverId)` rather than these selected aliases.
See [Noise factory wiring](noise-ik-session.md#factory-wiring).

### Host identity and snapshots

`HostConversationSource.snapshots` is a shared
`StateFlow<List<HostConversationSnapshot>>` in `di/`. Each relay snapshot carries
the exact, case-sensitive saved `serverId`, nullable local `displayName`, separate
relay/pyrycode legs in `connectionStatus`, and `channels` / `chats` lists. Host
identity belongs to this aggregation boundary: `Conversation` and wire payloads
remain host-local. Equal conversation ids or workspace paths on different hosts
stay distinct; display names and paths are never routing aliases. Snapshots expose
no pairing records, tokens or keys.

Registry reconciliation publishes every saved host, including hosts awaiting their
first reply with empty lists; no saved hosts produces an empty source. The source
collects `ConversationFilter.All` once per current repository and partitions active
rows into promoted Channels and unpromoted Chats (discussions), excluding archived
rows. It preserves each partition's repository order and the original records,
including ids and workspace paths verbatim. It adds no frame consumer.

### Snapshot lifetime

The Koin singleton starts collection on first resolution and owns its in-memory
cache until Koin close calls `dispose()`. Collection continues with zero screen
subscribers. Each host's status and list collectors run independently, so a silent
host cannot delay another host's rows or status.

A null repository, disconnect, background close or reconnect awaiting its first
list emission retains that host's last rows. Each actual emission atomically
replaces both lists for that host, including an empty result. Feed this cache from
the per-host coordinator streams: the selected-host
[stable facade's cold reads](stable-conversation-repository.md#cold-reads--flatmaplatest-switch-with-an-empty-fallback)
emit an empty fallback on disconnect and cannot distinguish it from a real empty
reply. The compatibility facade keeps that behavior.

The coordinator's repository-stream identity identifies a retained bundle
generation. A display-name-only edit updates metadata while preserving its bundle,
collectors and rows. Removal or credential-driven bundle replacement cancels the
old collectors and discards that host's rows; a replacement starts empty. Updates
check both the registered generation and current repository identity, because
cancellation alone cannot reject every late callback. Source disposal cancels its
collectors, clears the in-memory cache and disables lookup; registry disposal
publishes no hosts. The in-memory cache itself does not survive app restart — but
see below: as of #796, an accepted live list also lands in an on-disk
`ConversationCache`, and a newly created entry seeds itself from that document
before its first live emission arrives.

### Restore from the on-disk cache (#796)

`HostConversationSource.relay(...)` takes an optional trailing
`cache: ConversationCache? = null` (the [conversation cache](conversation-cache.md)'s
contract). `appModule` binds the one production `FileConversationCache` under
`Context.noBackupFilesDir` — never `filesDir`, since the manifest's
`allowBackup="true"` would otherwise carry cached conversation names and cwds into
cloud backup and device-to-device transfer while the Keystore-wrapped pairing
credentials that authorize reading them do not — and `hostConversationModule`'s
relay branch passes it as `cache = get()`; the demo branch passes none.

Each live list `update` accepts — one that clears the existing stale-entry and
superseded-repository guards — is written to the cache verbatim, archived rows
included, so the stored document mirrors exactly what the daemon reported and the
same `withRows` filter (the single promoted/archived split, used by both paths) is
applied identically on read. When `reconcile` creates a new entry and a cache is
present, it also launches a read of that host's cached document; a non-empty
result seeds the snapshot through `withRows`. The seed never writes
`connectionStatus` — a restored list must not read as a connected one, and
`TreeHostRow`'s existing status treatment stays the only disconnected affordance
(see [ChannelListScreen § Edge cases](channel-list-screen.md#edge-cases--limitations)).
A restore *seeds*; a live list still *replaces* wholesale, exactly as before #796
— so a conversation id already drawn cannot gain a second row and a conversation
the daemon stops reporting cannot survive a live replacement, with no merge or
dedup anywhere.

**The race a restore's read length creates.** A cache read has no bound relative
to the daemon's own list arriving, so a slow restore can complete *after* a live
list has already landed for the same entry — and the existing stale-entry /
superseded-repository guards both check *who is current*, not *what already won*,
so neither rejects that write. `Held` therefore carries its own `live: Boolean`,
set the moment a live-list write is accepted; a restoring write is rejected once
`live` is already set. `live` is read and written only inside the same
`@Synchronized update` this class already serializes every snapshot mutation
through, and the cache read suspends *outside* that monitor, so the check-and-set
is atomic against the live path.

A failed or empty cache read is never treated as authoritative:
`readConversations` returns `emptyList()` both for a document that was never
written and for one that failed to parse — the two are indistinguishable by
design (see [conversation cache § Failure model](conversation-cache.md#failure-model--graceful-reads-reporting-mutations))
— so only a non-empty read publishes anything, and only a live list may ever
empty a host.

### Exact-host repository access

`repositoryFor(serverId)` resolves `RelayConnectionRegistry.connectionFor` by exact
id, without consulting compatibility selection. Unknown, removed, disconnected or
handshaking hosts return `null`, even if their snapshots still contain rows.
The coordinator's internal `liveRepository()` checks one active connection under
its teardown lock: the owner must be active, its transport must be identical to
the supervisor's current transport, and that connection's actual pump must be
`Open`.

The asynchronous `currentRepository` cache can still hold a retired repository
when a replacement transport first arrives. Cached rows or status therefore do
not establish current availability. This lookup returns availability at the time
of the check; a later disconnect can still make an operation fail. See the
[coordinator's availability checks](relay-repository-coordinator.md#the-single-connection-source-and-the-open-gated-currentrepository-421--493).

### Destination ownership

`ThreadDestinationFactory` is a scope-free singleton in `hostConversationModule`.
The Koin `viewModel` definitions call `thread(handle, preferences)` and
`literal(handle)`; the factory reads `serverId` from the destination's
`SavedStateHandle`, while each ViewModel reads its unchanged host-local
`conversationId`. [Navigation](navigation.md#host-qualified-destinations) supplies
both arguments and scopes ViewModels to individual back-stack entries.

`ThreadDestinationFactory.settings(handle, preferences)` (#749; dropped its third `repository`
parameter in #715) is the third destination method, in the same shape but with two deliberate
differences from `thread`/`literal`: the owner it reads from the `SavedStateHandle` is **optional**
(`handle.get<String>("serverId").orEmpty()` — a blank owner is a valid destination state, not an
error), and it never resolves that id to a connection bundle — `SettingsViewModel` reads a saved
host's identity and status only, so a saved-but-disconnected owner is still its owner. `preferences`
is the same `AppPreferences` singleton every other `SettingsViewModel` dependency already used; #749
did not touch it, per its explicit deferral of `archivedDiscussionCount` and the default-workspace
picker to #715/#714. #714 closed the workspace half of that deferral: `preferences` is still the one
process-wide `AppPreferences` singleton, but `SettingsViewModel` now reads and writes
`defaultWorkspace` through it under the destination's own `ownerServerId` key rather than app-wide,
and the workspace picker's own repository is bound by the route (`HostWorkspaceRepository`, keyed by
`SettingsViewModel.workspacePickerServerId`), not by a repository argument here — see
[SettingsViewModel § Configuration/usage](settings-viewmodel.md#configuration--usage). #715 closed
the other half: `settings` now builds `SettingsViewModel(preferences, repository(serverId), serverId, hosts())`
— `archivedDiscussionCount` reads the **owner's** exact-host repository (below) rather than the
compatibility one, so the number on the Storage row matches the archive its own row opens. Under a
blank owner that facade is backed by a permanently-`null` repository, whose cold reads are
`emptyList()` — a count of zero, the honest answer for a destination owning no host.

`ThreadDestinationFactory.archive(handle)` (#715) is the fourth destination method, and the Archive
route's owner counterpart to `settings` above: it reads the **same** `serverId` key from the
`SavedStateHandle`, but — unlike `settings` — a blank owner here is never valid (`Routes.ARCHIVED_DISCUSSIONS`
is a required path segment, and [`HostDestination`](navigation.md#archive-a-required-owner-destination-two-doors-715)
rejects an unresolvable one before this method is reached). It builds
`ArchivedDiscussionsViewModel(repository(serverId), hostLabel(serverId))`, where `repository(serverId)`
is #636's exact-host seam verbatim — resolved **once**, at construction, so a selection change,
reconnect or unpair can move neither the rows nor a pending restore, and a host holding the same
conversation id under a different owner is unreachable by construction. The private `hostLabel(serverId): Flow<String>`
helper maps the existing `hosts()` projection (below) down to a single resolved display name
(`displayName` when non-blank, else `serverId`) — the same name-or-id fallback `SettingsHostRow.name`
uses, deliberately duplicated rather than shared (one line; sharing it would mean exporting a
resolution helper for a single caller, following #177's precedent for `Conversation.displayName()`).
Only that resolved `String` crosses into `ArchivedDiscussionsViewModel`; neither a `SettingsHost` nor
a stored `PairedServerEntry` reaches it, so no pairing token or server static key does either.

`settings` also builds the private `hosts(): Flow<List<SettingsHost>>` that becomes
`SettingsViewModel`'s fourth constructor argument — every saved host's identity plus its own live
status, joined from two sources because neither alone carries all four display fields:
`registry.hostConnections` (`RelayConnectionRegistry`, exposed here as the factory's own
`hostConnections` property) carries the per-host `status: StateFlow<ConnectionStatus>` and
`displayName`, and re-emits on every store revision so a rename, a pairing and an unpair all reach
the projection; the relay URL lives only in the stored record, so the `map` reads
`store.list()` once per `hostConnections` emission and joins by exact `serverId` — one decrypt for
the whole saved-host blob per revision, not one `PairedServerCollectionStore.loadById` per host
decrypting it N times for the same data. The three display fields (`serverId`, `displayName`,
`relayUrl`) are copied out explicitly into `SettingsHost`; the joined `PairedServerEntry` never
escapes the `map` block, because it carries the pairing token and server static key and
`SettingsHost` has no redacting `toString`. In demo mode (`useRelay = false`) `hosts()` instead
yields a single fixed `SettingsHost` for `HostConversationSource.DEMO_SERVER_ID`, mirroring the
`selectedServerId()` demo shape below. The returned `Flow` is deliberately cold — `SettingsViewModel`
lifts it with its own `stateIn`, so two Settings entries on the back stack (e.g. after Back and
reopen under a different capture) do not share one projection or its subscription lifetime.

Relay destinations capture the exact `connectionFor(serverId)` retained bundle.
Their repository is a `StableConversationRepository` over **that coordinator's**
`currentRepository` stream. Capturing `HostConversationSource.repositoryFor`'s
concrete live repository would strand the destination after reconnect; injecting
the global compatibility facade would redirect it on selection changes. The
destination facade adds no scope or jobs: cold reads switch with the owner stream,
and one-shot calls retain their existing arguments and errors. Disconnected or
handshaking owners yield empty/default reads and unavailable writes; B's healthy
connection cannot substitute for A's unavailable one.

The same bundle supplies the thread's supervisor state, live-session events,
current modal, modal answer/cancel and interrupt callbacks. Repository-backed
session/queue state, Send, Reset session, queue drop and existing thread actions
use the owner facade. Literal Request/Retry use a facade over that same host's
coordinator. Compatibility selection cannot change an open prompt's display or
answer target, even with colliding conversation/modal ids. App preferences remain
shared. The navigation guard waits for saved-host initialization and rejects
unknown/removed hosts before constructing their ViewModels; there is no fallback
to selection.

Ownership also covers descendant injection. `HostWorkspaceRepository` provides
`LocalWorkspacePickerRepository` around the thread and flat channel screen,
remembering a factory repository for the route host or captured picker host.
`WorkspacePicker` uses it for recents and folder creation, so its returned path
and the ViewModel's final action reach the same host across selection/reconnect.
Without that provider, a correctly bound ViewModel can still combine B's folders
with A's workspace change. The picker's nullable-local fallback remains the
compatibility Koin binding for Settings; #749 gave the Settings destination itself
exact-host ownership of its identity and connection status, but deliberately left
the default-workspace picker on this compatibility binding — that migration is #714.

### Exact-host Retry and lifecycle

The thread's `ConnectionStateSource.retry()` calls
`RelayConnectionRegistry.retryHost(serverId, expectedBundle)`. The registry holds
the same monitor used by reconciliation, removal, replacement, background close
and disposal while checking foreground state, disposal and exact bundle identity,
then running the supervisor's nonblocking Retry. A queued Retry cannot reopen a
retired or background owner, and it never retries another selected host.

Checking identity before calling the supervisor outside this monitor leaves a
check/use race: bundle teardown closes the supervisor but does not permanently
disable its `retry()`/`connect()` path. Keep validation and the nonblocking call
under one lifecycle boundary. Literal-screen Retry is a separate snapshot re-fetch;
it retains the destination repository and existing snapshot error mapping.

### Demo binding

With `useRelay = false`, the same source type exposes exactly one host:
`HostConversationSource.DEMO_SERVER_ID` (`demo`), local name `Demo`, with both link
states `Connected`. Lists and exact lookup use the existing
`FakeConversationRepository` singleton. Only the exact id `demo` resolves; saved
relay hosts never enter these snapshots or lookups, even though their connection
owners still exist.

Demo thread, literal and picker repositories also resolve that same singleton.
The thread gets `FakeConnectionStateSource` (`Connected`) and its inert default
live-event, hidden-modal and control dependencies. Saved real hosts never supply
demo content, permissions or controls. `selectedServerId()` returns `demo` in this
mode; in relay mode it captures the current exact selected host for temporary
flat-list entry points, and (since #749) for the channel list's settings gear —
`ChannelListEvent.SettingsTapped → navController.navigate(Routes.settings(destinations.selectedServerId()))`
captures the owner once, at tap time, into the Settings route; see
[Navigation § Settings](navigation.md#settings-an-optionally-owned-destination).

`ChannelListViewModel` receives this shared source as its third constructor
dependency and exposes [host-qualified state and actions](channel-list-viewmodel-projection.md#state-projection).
`DiscussionListViewModel` receives it as its second dependency for
[host-qualified navigation and captured promotion](discussion-list-viewmodel.md#wiring).
Both retain compatibility state/events and bare-id navigation APIs, but production
routes collect only `hostNavigationEvents`. The flat list still displays the
selected facade or fake; its adapters call host-aware row/create/picker/promotion
commands and project captured picker/promotion visibility into the existing screen
state. Asynchronous completion retains the captured host. See
[flat-list compatibility](navigation.md#temporary-flat-list-compatibility);
tree rendering remains #641. #749 moved the Settings destination itself (identity +
connection status) to exact-host ownership; #714 and #715 closed the two remaining
compatibility-bound pieces Settings still showed — the default-workspace picker and
the archived-discussion count, respectively — so nothing on this destination reads
compatibility selection any longer.

## Adding a binding

1. Open `de/pyryco/mobile/di/AppModule.kt`.
2. Add a definition inside the `module { ... }` block:
   - **Singleton** (e.g. a DataStore wrapper, repository): `single { AppPreferences(androidContext()) }`.
   - **Interface binding**: alias the existing owner, e.g. `single<RelayConnectionController> { get<RelayConnectionRegistry>() }`.
   - **Flag-gated fake↔real binding** (#350): register every candidate *concrete-only* in `appModule`, then bind the interface in a dedicated `fun fooModule(useX: Boolean = BuildConfig.USE_X) = module { single<Foo> { if (useX) get<Real>() else get<Fake>() } }` loaded alongside `appModule`. The selector **resolves** the candidates by type (`get<…>()`) — it never constructs them, so it carries none of their dependency weight, and it stays unit-testable via `koinApplication { … }` in isolation. See [`../codebase/350.md`](../codebase/350.md).
   - **ViewModel**: `viewModel { ChannelListViewModel(get(), get(), get()) }` — DSL import `org.koin.core.module.dsl.viewModel` (the multiplatform-safe path; the older `org.koin.androidx.viewmodel.dsl.viewModel` is being phased out). Resolved in composables with `koinViewModel<ChannelListViewModel>()` from `koin-androidx-compose`.
3. No registration step elsewhere. The modules are wired into `startKoin` once; the new definition flows through automatically. (A binding selected by a build flag goes in its own module per the #350 pattern above, not inside `appModule`.)

The transient pending-consumers comment from #32 has been fully consumed (#11 + #45 together) — there's no longer a placeholder block in `AppModule.kt`. New bindings append directly inside `module { ... }`, singletons before `viewModel { }` lines for readability.

Tests and previews that require the fake should pass
`conversationRepositoryModule(useRelay = false)` explicitly. This keeps them
independent of the build default and avoids relying on Koin override semantics.
`E2eInstrumentationRunner` installs `E2eTestApplication` for every instrumented run:
without `relayUrl`, it explicitly selects fake; with relay arguments, it replaces
the selector with the existing tapped stable-facade binding so the parser tap is
preserved. Both selectors include `hostConversationModule`. The relay selector also
passes `decorateRepository = ::TappingConversationRepository`, wrapping the
factory's exact-host facades as well as the compatibility facade. Decorating only
the global interface would miss thread reads after the route migration. The
identity-default decorator adds no production subscription; instrumentation taps
the existing `observeMessages` collection without another backfill request.

## Testing

`RelayConnectionFactoryTest.destinationBindingsKeepCollidingIdsOnTheirHostAcrossSelectionAndReconnect`
resolves production thread/literal bindings against two Noise peers. It combines
colliding conversation, modal and queue ids with distinct content and snapshots,
then checks owner-specific outbound frames and no corresponding action on B.
It covers selection changes, A disconnect/handshake/reconnect while B remains
usable, literal Retry, and demo isolation with both real hosts saved.

The same class's
`destinationRetryKeepsLifecycleLockThroughDialAndRejectsRetiredOwners` asserts
that Retry holds the registry monitor at dial and rejects queued calls after
replacement/removal/disposal.
`queuedDestinationRetryCannotReopenAfterBackgroundClose` checks the background
edge. Settled identity checks alone would miss the removal race.

`SettingsNavigationTest` (#749) exercises `ThreadDestinationFactory.settings` and its `hosts()`
projection against the production graph and Koin bindings, with two Noise peers: a captured owner
outlives a compatibility-selection change (closing one host's supervisor first, so the assertion is
discriminating — see [Settings ViewModel § Testing](settings-viewmodel.md#testing)), saved-state
restoration and Back/reopen under a different selection, and both an unknown and an absent owner
keep the destination open with no saved host's identity on screen. See
[Navigation § Testing](navigation.md#testing) for the scenario list.

`ArchiveNavigationTest` (#715) exercises `ThreadDestinationFactory.archive` and `hostLabel` the same
way, but with both peers holding an archived conversation under the **same** id: the owner's
repository (and only the owner's) receives `unarchive_conversation`, the other host's identically-numbered
row stays archived, and removing the owner while Archive is open leaves the destination for the
channel list rather than rendering the other host's rows. A fourth case covers the channel list's own
archive door, added after a rework found it still navigating to the pre-#715 unscoped route. See
[Navigation § Archive](navigation.md#archive-a-required-owner-destination-two-doors-715) and
[Archived Discussions screen § Testing](archived-discussions-screen.md#testing).

`LiteralScreenNavigationTest` exercises the production graph and bindings,
including the actual workspace picker with distinct A/B recents, folder creation,
selection changes and reconnect. Constructor-only tests and direct
`WorkspacePickerInternal(repository = fake)` tests bypass the descendant's
independent injection and cannot establish that ownership boundary.

`HostChannelListViewModelTest.appModuleInjectsSharedDemoSourceAndCreatesThroughExistingFakeSingleton`
resolves the actual `appModule` ViewModel definition with JVM preferences and the
fake selector. It verifies that the source is shared, only `demo` resolves, and a
host-targeted creation appears in the existing fake singleton's rows. A test that
constructs the ViewModel directly would miss a missing third constructor binding.

`HostDiscussionListViewModelTest.appModuleInjectsSharedDemoSourceAndPromotesThroughExistingFakeSingleton`
likewise resolves the actual discussion binding, verifies its shared demo source,
and observes promotion through the existing fake singleton. Its optional second
constructor parameter preserves repository-only fixtures, so those fixtures alone
cannot prove that production DI supplies the host contract.

These two tests and `RelayConnectionFactoryTest.selectorSharesHostSourceAndKeepsDemoLookupSeparateFromSavedRelayHosts`
are the only `app/src/test` sites that resolve the Koin-built `HostConversationSource` — the source
takes `hostConversationModule`'s `Dispatchers.Default` default, so its `stateIn(viewModelScope, …)`
subscriber runs on a real worker thread outside the test scheduler. All three resolve it through the
`KoinHostSources` test helper (`app/src/test/java/de/pyryco/mobile/di/KoinHostSources.kt`) rather
than closing the container and trusting disposal: `closeAndAssertStopped()` closes the container and
then asserts `repositoryFor(liveHostId) == null`, which is `@Synchronized` on the same monitor the
publisher takes and only returns `null` once `dispose()` has completed, giving a real
happens-before edge instead of a plain field read. Each class's `@After` also calls
`assertAllClosed()` before `Dispatchers.resetMain()`, because an unguarded window between building
the container and disposing it otherwise lets a background publish resume a torn-down Main on an
unrelated test (#726) — see [the JVM unit-test pitfall](development-verification.md#test-scheduling-and-harnesses).

`ConversationRepositoryBindingTest` verifies the generated flag and resolved
singleton against Gradle's separate `expectedUseRelayRepository` test property.
Deriving the expected choice from `BuildConfig` itself would let an incorrectly
generated flag pass. Exercise default, explicit-real and demo builds;
`RepositoryBindingInstrumentedTest` separately checks the installed test
application's fake binding. See [verification guidance](development-verification.md#test-scheduling-and-harnesses)
for its placement outside the excluded `e2e` package.

`RelayConnectionFactoryTest.appModuleTracksLatestSurvivorWithoutReplacingStableConsumers`
checks unpaired startup, first pairing, selection changes and last-host removal
with both selectors. It verifies stable facade identity, selected concrete aliases,
and matching repository, event, modal, status and action targets without extra
transports. Its JVM container loads definitions with a fixture registry, avoiding
Android's eager `ProcessLifecycleOwner` initialization.

`HostConversationSourceTest` exercises collection without screen subscribers,
silent hosts, case-sensitive ids, unchanged row order/paths, empty replies and
cache retirement. Its manual repository deliberately retains a collector callback
after cancellation and emits through it after reconnect, generation replacement,
removal and disposal. A cancellation-cooperative fake alone would pass while a
missing identity guard still allowed late writes. Real Noise registry fixtures in
`RelayConnectionFactoryTest` cover saved metadata, background/resume, credential
rotation and both selectors' singleton identity and disposal.

`HostConversationSourceTest` also covers #796's cache: a seeded host with no live
repository draws its cached rows filtered the same way as a live one while its
`connectionStatus` stays the disconnected value it was given; two disconnected
hosts each restore only their own rows; a live list landing after a restore
replaces it wholesale (retired id gone, still-reported id appears once); a restore
whose read completes *after* a live list has landed does not overwrite it — the
`live`-flag guard, driven by ordering the fake's read completion behind the live
emission; the accepted live list is written to the cache verbatim including
archived rows, and a list rejected by the superseded-repository guard is not
written; and a failing cache write logs one static event with no server id,
conversation id, name or cwd in captured `RelayLog` output. `demo(...)` is
asserted to touch no cache.

`ConversationCacheBindingInstrumentedTest` (`app/src/androidTest/java/de/pyryco/mobile/di/`,
added in #796) resolves `ConversationCache` from the live Koin container, writes
one host through it, and asserts the document lands under `Context.noBackupFilesDir`
and that nothing is created under `Context.filesDir` — the property [conversation
cache § Root and storage scope](conversation-cache.md#root-and-storage-scope--nobackupfilesdir-never-filesdir)
names this ticket as owning. It is the only instrumented test that resolves the
real binding: `SettingsNavigationTest`, `ArchiveNavigationTest` and
`LiteralScreenNavigationTest` each build a relay-mode container from `appModule`
on a device, where supplying the real `Context` was available and closer to
production — but it would hand them the real cache over one `noBackupFilesDir`
shared by every test and every run on a reused managed device, and #796's own
restore-on-entry-creation is what makes that unsafe: a `live = true` case in one
test would leave rows on disk that a later disconnected host with the same server
id in another test draws for free. Each of the three instead binds a shared
androidTest `InertConversationCache` fake over the container, the same pattern
`RelayConnectionFactoryTest` uses with its own file-private copy (separate source
sets cannot share one). Withholding the Context is also load-bearing on its own:
it is what turned the new dependency's absence into a loud
`MissingAndroidContextException` in these three classes rather than a silent
reach into real DataStore or Keystore state — caught only because the `di` and
`ui` packages were run as wholes, not the single class each ticket touched. See
[Edge cases](#edge-cases--limitations) below for the general form of that lesson.

`repositoryForRejectsRetiredRepositoryAtReconnectTransportEdge` observes replacement
transport arrival with an unconfined collector and calls lookup synchronously,
before cached repository projections catch up. It requires `null` at that edge
and throughout a held handshake, then the new repository after completing that
same handshake. A settled assertion after `runCurrent()` misses the
[reproduced reconnect race](https://github.com/pyrycode/pyrycode-mobile/pull/707#issuecomment-5753640430).

## Configuration

- **Dependencies:** `io.insert-koin:koin-bom` (pinned in `[versions]` as `koinBom`) and `koin-androidx-compose` (version pinned transitively by the BOM, no `version.ref` in the catalog). `koin-android` and `koin-core` come in transitively — do not add them explicitly.
- **Manifest:** `<application android:name=".PyryApp" …>` in `app/src/main/AndroidManifest.xml`.
- **Logger:** none (Koin's default `EmptyLogger`). Add `androidLogger(Level.INFO)` only when a real binding-misconfiguration bug surfaces.
- **Test helpers:** `koin-test` / `verify()` are deliberately not on the classpath yet. The first ticket to add a binding worth verifying brings them in.

## Edge cases / limitations

- **Shared selector definitions.** `conversationRepositoryModule` includes `hostConversationModule`; the tapped instrumentation selector includes that same host-source module. Koin resolves the concrete owners from `appModule` lazily. Keep shared source bindings in the included module so replacing the compatibility interface binding does not silently omit them.
- **Module-level `val`, not an `object` / function.** Downstream tickets append single lines; an `object AppModule { val module = … }` form would force every binding to qualify through the object and adds no upside.
- **No `try/catch` around `startKoin`.** Initialisation failure (duplicate definition, missing factory) crashes the process — the stack trace is the debugging surface. No fallback path makes sense at the composition root.
- **Kotlin 2.2 alignment:** stay on Koin BOM `4.0.x`. Do not downgrade to `3.5.x` — it predates Kotlin 2.2 toolchain alignment. Bump *up* to the latest stable `4.0.x` patch if Gradle reports a compiler-version mismatch.
- **A lazy Android-bound Koin definition is only lazy until something upstream resolves it.** Every `single { }` bound with `androidContext()` in `appModule` is dormant in a JVM/instrumented container built without one, as long as nothing that container resolves reaches it — `RelayConnectionFactoryTest`'s bare-`appModule` construction relied on exactly that until #796 made the relay `HostConversationSource` resolve `ConversationCache`, which reddened it and three instrumented navigation tests with `MissingAndroidContextException`. A binding-scoped test run misses this; only running the whole `di`/`ui` package catches a change that makes a previously-dormant dependency hard. See [Testing](#testing) above for the fix (`InertConversationCache` overrides, not a supplied Context).

## Related

- Ticket notes: `../codebase/32.md` (scaffold), `../codebase/11.md` (first real binding — `AppPreferences`), `../codebase/45.md` (first interface-bound singleton + first `viewModel { }` line), `../codebase/196.md` (`FakeConnectionStateSource` ↔ `ConnectionStateSource`), [`../codebase/350.md`](../codebase/350.md) (the flag-gated `conversationRepositoryModule` selector — the second module + the `buildConfigField` `USE_RELAY_REPOSITORY` flag)
- Spec: `docs/specs/architecture/32-koin-di-scaffold.md`
- Host source: [snapshot design and coherent-lookup revision](../../specs/architecture/704-host-conversation-snapshots.md); [conversation cache](conversation-cache.md) is the on-disk store #796 wired the source to, `docs/specs/architecture/796-cached-host-conversation-list-restore.md` its plan.
- Repository selection: [Stable conversation repository](stable-conversation-repository.md) is the normal build binding; [FakeConversationRepository](conversation-repository.md#phase-1-implementation--fakeconversationrepository) is the explicit demo/test selection. The [#631 plan](../../specs/architecture/631-default-real-repository.md) records the default change on the existing #350 selector.
