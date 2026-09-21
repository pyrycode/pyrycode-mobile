# Dependency injection

Koin is the project's DI framework. `PyryApp.onCreate` loads `appModule` and
`conversationRepositoryModule(useRelay)` from `de/pyryco/mobile/di/AppModule.kt`.
The selector includes `hostConversationModule(useRelay)` for the shared host list
source and binds the compatibility `ConversationRepository` interface.

## What it does

`appModule` wires concrete repositories, connection owners, settings and ViewModels;
the selector modules choose the shared host source and compatibility repository.
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
}

// The #350 selector — the *only* module that binds the ConversationRepository interface.
fun conversationRepositoryModule(useRelay: Boolean = BuildConfig.USE_RELAY_REPOSITORY) = module {
    includes(hostConversationModule(useRelay))
    single<ConversationRepository> {
        if (useRelay) get<StableConversationRepository>() else get<FakeConversationRepository>()
    }
}

fun hostConversationModule(useRelay: Boolean) = module {
    single {
        if (useRelay) HostConversationSource.relay(get())
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

The selector chooses both the compatibility repository and the host source. The
lifecycle driver and registry still control every saved host's connection
establishment in both modes; selecting the fake does not disable that stack.

`appModule` eagerly owns `RelayConnectionRegistry` and disposes it on Koin close.
Both pairing-store interfaces resolve one [observable decorator](paired-server-store.md#wiring--usage).
Its initial and successful-mutation revisions drive collection reconciliation;
the registry uses `RelayConnectionFactory.create(record)` for one retained bundle
per exact server id. Foreground/background lifetime belongs to this registry.

`RelayConnectionController` and `ConnectionStateSource` resolve the registry.
The stable repository, Settings status, and Thread events, modal and outbound
actions follow its latest-saved surviving selection. No separate compatibility
connection is created. With no selection, these dependencies remain resolvable
with empty repository/events, hidden modal and idle/down status. See
[bundle configuration](relay-repository-coordinator.md#configuration).

Concrete `RelayConnectionBundle`, `RelayConnectionSupervisor`, `NoiseSessionFactory`
and `RelayRepositoryCoordinator` bindings are Koin **factory resolutions of the
selected retained owner**, not singleton snapshots or newly constructed bundles.
They serve test/diagnostic callers, including deterministic reconnect helpers;
resolving them without a selection fails with `no paired host`. Long-lived app
consumers use the registry projections rather than holding these selected aliases.
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
collectors, clears the cache and disables lookup; registry disposal publishes no
hosts. Nothing is persisted across app restart.

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

### Demo binding

With `useRelay = false`, the same source type exposes exactly one host:
`HostConversationSource.DEMO_SERVER_ID` (`demo`), local name `Demo`, with both link
states `Connected`. Lists and exact lookup use the existing
`FakeConversationRepository` singleton. Only the exact id `demo` resolves; saved
relay hosts never enter these snapshots or lookups, even though their connection
owners still exist.

`ChannelListViewModel` receives this shared source as its third constructor
dependency and exposes [host-qualified state and actions](channel-list-viewmodel.md#state-projection).
`DiscussionListViewModel` receives it as its second dependency for
[host-qualified navigation and captured promotion](discussion-list-viewmodel.md#wiring).
Both retain flat-screen state/events and bare-id navigation through the selected-host
facade or fake; host actions use exact lookup and separate navigation streams.
Thread routing remains #636 and tree rendering #641.

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
preserved. Both selectors include `hostConversationModule` so relay instrumentation
also resolves the shared relay source without replacing its tapped facade.

## Testing

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

## Related

- Ticket notes: `../codebase/32.md` (scaffold), `../codebase/11.md` (first real binding — `AppPreferences`), `../codebase/45.md` (first interface-bound singleton + first `viewModel { }` line), `../codebase/196.md` (`FakeConnectionStateSource` ↔ `ConnectionStateSource`), [`../codebase/350.md`](../codebase/350.md) (the flag-gated `conversationRepositoryModule` selector — the second module + the `buildConfigField` `USE_RELAY_REPOSITORY` flag)
- Spec: `docs/specs/architecture/32-koin-di-scaffold.md`
- Host source: [snapshot design and coherent-lookup revision](../../specs/architecture/704-host-conversation-snapshots.md).
- Repository selection: [Stable conversation repository](stable-conversation-repository.md) is the normal build binding; [FakeConversationRepository](conversation-repository.md#phase-1-implementation--fakeconversationrepository) is the explicit demo/test selection. The [#631 plan](../../specs/architecture/631-default-real-repository.md) records the default change on the existing #350 selector.
