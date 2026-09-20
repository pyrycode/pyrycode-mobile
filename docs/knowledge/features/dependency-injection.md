# Dependency injection

Koin is the project's DI framework. Two modules, both declared at `de/pyryco/mobile/di/AppModule.kt`, owned by `PyryApp.onCreate`: `appModule` (every singleton, repository, and ViewModel) and `conversationRepositoryModule(useRelay)` (the #350 flag-gated `ConversationRepository` binding selector — see [Adding a binding](#adding-a-binding)).

## What it does

Every singleton, repository, and ViewModel that needs construction wiring is declared as a Koin definition in `appModule`. Composables resolve dependencies with `koinViewModel()` / `koinInject()` from `koin-androidx-compose`; non-Compose code uses `by inject()` / `get()`. Bindings land here as tickets need them — see `AppModule.kt` for the current set.

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
    single { StableConversationRepository(get<RelayRepositoryCoordinator>().currentRepository) } // #352
    viewModel { ChannelListViewModel(get(), get()) }   // #45
}

// The #350 selector — the *only* module that binds the ConversationRepository interface.
fun conversationRepositoryModule(useRelay: Boolean = BuildConfig.USE_RELAY_REPOSITORY) = module {
    single<ConversationRepository> {
        if (useRelay) get<StableConversationRepository>() else get<FakeConversationRepository>()
    }
}
```

`app/build.gradle.kts` generates `BuildConfig.USE_RELAY_REPOSITORY` from the
`useRelayRepository` Gradle property. It defaults to `true`, selecting the existing
`StableConversationRepository` singleton; `-PuseRelayRepository=false` selects the
existing `FakeConversationRepository` singleton. Only the exact values `true` and
`false` are accepted; other values fail Gradle configuration. This is a build-time
selection with no runtime setter. See [README Build](../../../README.md#build).

The selector changes the repository injected into UI consumers. The lifecycle
driver, supervisor and coordinator still control connection establishment in
both modes; selecting the fake does not disable that stack.

Relay construction lives in `RelayConnectionFactory`. `appModule` registers one
eager, store-backed compatibility bundle and aliases its supervisor, Noise session
factory and coordinator as the existing concrete Koin types. The controller and
connection-state interfaces resolve that same supervisor; Koin disposal closes
the bundle. Independent hosts use `create(record)` and own their state outside
these temporary app-wide aliases. See [bundle configuration](relay-repository-coordinator.md#configuration)
and [Noise factory wiring](noise-ik-session.md#factory-wiring).

## Adding a binding

1. Open `de/pyryco/mobile/di/AppModule.kt`.
2. Add a definition inside the `module { ... }` block:
   - **Singleton** (e.g. a DataStore wrapper, repository): `single { AppPreferences(androidContext()) }`.
   - **Interface binding**: alias the existing owner, e.g. `single<RelayConnectionSupervisor> { get<RelayConnectionBundle>().supervisor } binds arrayOf(ConnectionStateSource::class, RelayConnectionController::class)`.
   - **Flag-gated fake↔real binding** (#350): register every candidate *concrete-only* in `appModule`, then bind the interface in a dedicated `fun fooModule(useX: Boolean = BuildConfig.USE_X) = module { single<Foo> { if (useX) get<Real>() else get<Fake>() } }` loaded alongside `appModule`. The selector **resolves** the candidates by type (`get<…>()`) — it never constructs them, so it carries none of their dependency weight, and it stays unit-testable via `koinApplication { … }` in isolation. See [`../codebase/350.md`](../codebase/350.md).
   - **ViewModel**: `viewModel { ChannelListViewModel(get()) }` — DSL import `org.koin.core.module.dsl.viewModel` (the multiplatform-safe path; the older `org.koin.androidx.viewmodel.dsl.viewModel` is being phased out). Resolved in composables with `koinViewModel<ChannelListViewModel>()` from `koin-androidx-compose`.
3. No registration step elsewhere. The modules are wired into `startKoin` once; the new definition flows through automatically. (A binding selected by a build flag goes in its own module per the #350 pattern above, not inside `appModule`.)

The transient pending-consumers comment from #32 has been fully consumed (#11 + #45 together) — there's no longer a placeholder block in `AppModule.kt`. New bindings append directly inside `module { ... }`, singletons before `viewModel { }` lines for readability.

Tests and previews that require the fake should pass
`conversationRepositoryModule(useRelay = false)` explicitly. This keeps them
independent of the build default and avoids relying on Koin override semantics.
`E2eInstrumentationRunner` installs `E2eTestApplication` for every instrumented run:
without `relayUrl`, it explicitly selects fake; with relay arguments, it replaces
the selector with the existing tapped stable-facade binding so the parser tap is
preserved.

`ConversationRepositoryBindingTest` verifies the generated flag and resolved
singleton against Gradle's separate `expectedUseRelayRepository` test property.
Deriving the expected choice from `BuildConfig` itself would let an incorrectly
generated flag pass. Exercise default, explicit-real and demo builds;
`RepositoryBindingInstrumentedTest` separately checks the installed test
application's fake binding. See [verification guidance](development-verification.md#test-scheduling-and-harnesses)
for its placement outside the excluded `e2e` package.

## Configuration

- **Dependencies:** `io.insert-koin:koin-bom` (pinned in `[versions]` as `koinBom`) and `koin-androidx-compose` (version pinned transitively by the BOM, no `version.ref` in the catalog). `koin-android` and `koin-core` come in transitively — do not add them explicitly.
- **Manifest:** `<application android:name=".PyryApp" …>` in `app/src/main/AndroidManifest.xml`.
- **Logger:** none (Koin's default `EmptyLogger`). Add `androidLogger(Level.INFO)` only when a real binding-misconfiguration bug surfaces.
- **Test helpers:** `koin-test` / `verify()` are deliberately not on the classpath yet. The first ticket to add a binding worth verifying brings them in.

## Edge cases / limitations

- **Two modules today** (`appModule` + the #350 `conversationRepositoryModule`). The second module exists *not* as a feature-module split but because the flag-gated `ConversationRepository` binding must be unit-testable in isolation — `modules(...)` is a varargs slot, and Koin resolves `get<…>()` cross-module, so the selector reads the two concrete singletons out of `appModule` lazily. Don't pre-split `appModule` itself (e.g. into a `dataModule`) before there are ~30 bindings or a feature-module extraction lands.
- **Module-level `val`, not an `object` / function.** Downstream tickets append single lines; an `object AppModule { val module = … }` form would force every binding to qualify through the object and adds no upside.
- **No `try/catch` around `startKoin`.** Initialisation failure (duplicate definition, missing factory) crashes the process — the stack trace is the debugging surface. No fallback path makes sense at the composition root.
- **Kotlin 2.2 alignment:** stay on Koin BOM `4.0.x`. Do not downgrade to `3.5.x` — it predates Kotlin 2.2 toolchain alignment. Bump *up* to the latest stable `4.0.x` patch if Gradle reports a compiler-version mismatch.

## Related

- Ticket notes: `../codebase/32.md` (scaffold), `../codebase/11.md` (first real binding — `AppPreferences`), `../codebase/45.md` (first interface-bound singleton + first `viewModel { }` line), `../codebase/196.md` (`FakeConnectionStateSource` ↔ `ConnectionStateSource`), [`../codebase/350.md`](../codebase/350.md) (the flag-gated `conversationRepositoryModule` selector — the second module + the `buildConfigField` `USE_RELAY_REPOSITORY` flag)
- Spec: `docs/specs/architecture/32-koin-di-scaffold.md`
- Repository selection: [Stable conversation repository](stable-conversation-repository.md) is the normal build binding; [FakeConversationRepository](conversation-repository.md#phase-1-implementation--fakeconversationrepository) is the explicit demo/test selection. The [#631 plan](../../specs/architecture/631-default-real-repository.md) records the default change on the existing #350 selector.
