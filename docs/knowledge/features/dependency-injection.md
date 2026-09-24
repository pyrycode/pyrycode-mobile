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
    viewModel {
        // #877: the thread is what knows its conversation is being viewed. A `ConversationViewing`
        // handle opens that conversation on its own host and holds it read until this VM is cleared.
        val handle = get<SavedStateHandle>()
        get<ThreadDestinationFactory>().thread(handle, get()).also { thread ->
            val viewing = get<ConversationViewing>().view(handle.get<String>("serverId").orEmpty(), handle.get<String>("conversationId").orEmpty())
            thread.addCloseable(viewing)
        }
    }
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
    // #797: the demo branch resolves no cache, same rule as HostConversationSource.demo below.
    single { ThreadDestinationFactory(useRelay, get(), get(), get(), decorateRepository, cache = if (useRelay) get() else null) }
    // #877: one viewing tracker per app, shared by every thread destination and the host source.
    single { ConversationViewing() }
    single {
        // #796: the demo branch resolves no cache — there is nothing persisted for a fake host to restore.
        if (useRelay) HostConversationSource.relay(get(), cache = get(), viewing = get())
        else HostConversationSource.demo(get<FakeConversationRepository>(), viewing = get())
    } onClose { it?.dispose() }
}
```

`ThreadDestinationFactory.repository(serverId, bundle)` wraps its `StableConversationRepository`
in [`CachingConversationRepository`](caching-conversation-repository.md) *before* calling
`decorateRepository`, so an instrumentation decorator like `TappingConversationRepository` still
observes the restored, merged thread rather than being layered around a cache it never sees:

```kotlin
decorateRepository(
    if (cache != null && serverId.isNotEmpty()) CachingConversationRepository(stable, cache, serverId) else stable,
)
```

A blank `serverId` (a malformed route) and the demo branch both skip the cache entirely.

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

See [Dependency injection — host conversation source and destination ownership](dependency-injection-host-conversation-source.md)
for host identity and snapshots, snapshot lifetime, on-disk restore, attention state (#877),
exact-host repository access, destination ownership, exact-host retry and the demo binding.

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
the existing `observeMessages` collection without another backfill request. Since
\#797, this composes with the [caching
wrapper](caching-conversation-repository.md#wiring--under-decoraterepository-not-in-it)
that now sits underneath the hook: the tap observes the restored, merged thread,
not a pre-restore projection.

Resolving the cache inside `ThreadDestinationFactory` (#797) means **any** container
that builds a thread destination under `useRelay = true` now needs a
`ConversationCache` binding, even one with no `androidContext()` to build a real
`FileConversationCache` from. Three `RelayConnectionFactoryTest` containers picked
up the same `single<ConversationCache> { InertConversationCache }` override their
sibling container already carried for #796's `HostConversationSource` — before
\#797 that override was only needed where the host-list source was built.

### `AttachmentStore` and context-free thread destination containers (#899)

`ThreadDestinationFactory` gained a matching `attachments: AttachmentStore? = null` constructor param
(#899), resolved the same way as `cache`: `hostConversationModule` passes `if (useRelay) get() else
null`, and that `get()` runs inside the `single { ThreadDestinationFactory(...) }` block — so it
resolves an `AttachmentStore` the moment `ThreadDestinationFactory` itself is resolved, not only when a
retrieval actually runs. `AttachmentStore`'s own `single` in `appModule` builds it from
`androidContext().noBackupFilesDir`, so any `useRelay = true` container built with no `androidContext()`
now fails the same `MissingAndroidContextException` way `ConversationCache`'s absence did for #797 — but
for a **wider** set of containers than #797 touched, because `ThreadDestinationFactory` is built (and so
`attachments` is resolved) by every test that constructs a thread destination's Koin graph, not
only `RelayConnectionFactoryTest`. Four containers needed the fix in rework: the three
`RelayConnectionFactoryTest` containers plus one each in `SettingsNavigationTest`, `ArchiveNavigationTest`
and `LiteralScreenNavigationTest`. Each now overrides `single { InertAttachmentStore }`
(`app/src/sharedTest/java/de/pyryco/mobile/di/InertAttachmentStore.kt`) beside its existing
`InertConversationCache` override, for the same reason: withholding `androidContext()` from the test
container is load-bearing on its own, so the fake keeps the missing-dependency failure loud rather than
silently building a real Keystore/filesystem-backed store other tests are meant to prove. Production
wiring is unchanged — see [Attachment retrieval](attachment-retrieval.md). Any future container that
builds a thread destination under `useRelay = true` with no `androidContext()` inherits this requirement
too.

## Testing

`RelayConnectionFactoryTest.destinationBindingsKeepCollidingIdsOnTheirHostAcrossSelectionAndReconnect`
resolves the production thread binding against two Noise peers. It combines
colliding conversation, modal and queue ids with distinct content and snapshots,
then checks owner-specific outbound frames and no corresponding action on B.
It covers selection changes, A disconnect/handshake/reconnect while B remains
usable, and demo isolation with both real hosts saved. [#883](../../specs/architecture/883-retire-literal-screen.md)
removed this test's literal-VM arms; host B's outbound list, which had held only
B's `request_snapshot`, is now asserted empty instead — still proving A's actions
never leak to B.

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
independent injection and cannot establish that ownership boundary. It kept its
name and its workspace-picker coverage across [#883](../../specs/architecture/883-retire-literal-screen.md)'s
removal of the literal-screen destination it originally covered — see
[Navigation § Testing](navigation.md#testing).

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
Both `HostChannelListViewModelTest` and `HostDiscussionListViewModelTest` install a dispatching
`StandardTestDispatcher(testScheduler)` as `Main` before resolving the ViewModel in their appModule
test, rather than relying on the class's own `UnconfinedTestDispatcher`: an unconfined `Main` lets the
source's `Dispatchers.Default` worker run the ViewModel and the test body in place, which can carry
`closeAndAssertStopped()` itself onto that worker and make the #726 proof vacuous (#892) — see
[the thread-identity detail](development-verification.md#test-scheduling-and-harnesses).

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

`HostConversationSourceTest` also covers #840's `retryHost`: it forwards the exact
id once and makes no call once the source is disposed.

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

`ConversationAttentionTest` (#877) covers `resolveAttention` and `HostAttentionState` as
pure functions: one adjacent precedence pair each (Waiting over Running, Running over
Failed, Failed over Unread, Unread over Idle), a blank-id modal counting for no row, a
question batch setting `WaitingForAnswer`, `Interrupted` not counting as failed while
`StoppedEarly` does, a turn completed while viewing never becoming unread, `opened`
clearing both unread and failed, a next-turn start clearing failed, a re-delivered
`TurnEnd` doing nothing whether it is in the `counted` list or equals a stored position,
`disconnected` clearing `running` only, and `restored` letting live positions win over
stored ones.

`HostConversationSourceAttentionTest` (#877) covers `launchAttention` through two fake
hosts' `HostConversationConnection` flows: two hosts sharing a conversation id keep
separate state; opening on host A leaves host B's row unread;
`oneHostLosingItsConnectionStopsOnlyItsRunningAndKeepsBothLegs` clears one host's running
states while its `ConnectionStatus` legs stay split and the other host is untouched;
re-delivery after a reconnect (repository null, then a new repository, then the same
`TurnEnd`) marks nothing; positions written through a recording cache are restored by a
new source (the app-restart case); and a `ConversationViewing` handle suppresses unread
while open and re-enables it on close.
`twoHostsSharingAConversationIdKeepSeparateStateAndOpeningTouchesOneHost` also pins that no
conversation id, turn id, modal text or batch text reaches captured `RelayLog` output.

`HostChannelListViewModelTest` (#877) adds one case pinning `HostChannelListEntry.attentionFor`
against `hostSource.attention` and one pinning that `onHostRowTapped` clears a row's unread
state through `markOpened`.

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
- Host source: [Dependency injection — host conversation source and destination ownership](dependency-injection-host-conversation-source.md) (host identity, snapshot lifetime, on-disk restore, attention state, exact-host access, destination ownership, retry, demo binding); [snapshot design and coherent-lookup revision](../../specs/architecture/704-host-conversation-snapshots.md); [conversation cache](conversation-cache.md) is the on-disk store #796 wired the source to, `docs/specs/architecture/796-cached-host-conversation-list-restore.md` its plan.
- Attention state: `docs/specs/architecture/877-conversation-attention-state.md` (#877's plan, including its Revisions entry on `ConversationViewing` and its Security review); [conversation cache § Read positions](conversation-cache.md#read-positions-877) for the persisted half; [ChannelListViewModel § state projection](channel-list-viewmodel-projection.md#attention-join-877) for the join into `hostState`. Drawing the state and its live acceptance are the blocked follow-up ticket and #676.
- Repository selection: [Stable conversation repository](stable-conversation-repository.md) is the normal build binding; [FakeConversationRepository](conversation-repository.md#phase-1-implementation--fakeconversationrepository) is the explicit demo/test selection. The [#631 plan](../../specs/architecture/631-default-real-repository.md) records the default change on the existing #350 selector.
- [Attachment retrieval](attachment-retrieval.md) (#899) — the `AttachmentStore` single and the
  `ThreadDestinationFactory` `attachments` param § AttachmentStore above wires; [caching conversation
  repository](caching-conversation-repository.md) is the consumer.
