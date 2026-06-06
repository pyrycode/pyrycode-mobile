# #350 — Flag-gated Fake↔Remote ConversationRepository binding swap

## Files to read first

- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:39-91` — the Koin module. **Line 57** binds `FakeConversationRepository` to `ConversationRepository` (the default). **Lines 80-84** register `StableConversationRepository` as a resolvable concrete singleton that does **not** bind the interface. This is the only file with real logic to change.
- `app/src/main/java/de/pyryco/mobile/PyryApp.kt:8-16` — composition root; `startKoin { modules(appModule) }`. The new module gets added to the `modules(...)` list here.
- `app/build.gradle.kts:42-53` — `defaultConfig` block; note the existing `buildConfigField("String", "GIT_SHA", …)` at lines 49-50 and `buildFeatures { buildConfig = true }` at 70-73. The new boolean flag mirrors the `GIT_SHA` pattern.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt:46-48` — constructor is `(currentRepository: StateFlow<ConversationRepository?>)`. Trivially constructible in a unit test with `MutableStateFlow(null)` — no Android deps. This is what makes the binding unit-testable in isolation.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:48-50` — no-arg constructor (`initialMessages` defaults to empty). Pure JVM (kotlinx-datetime, coroutines, `java.util.UUID`). Constructible in a unit test.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:20` — the interface both impls satisfy; the binding target.

Memory / convention notes (not in codegraph — markdown/lessons):
- `[[ktlint-filename-rule-single-class]]` — ktlint's single-public-class-filename rule fires only when a file's sole **public top-level type** mismatches the filename. `appModule` (a `val`) + `conversationRepositoryModule` (a `fun`) are not types, so two module declarations may coexist in `AppModule.kt` with no filename constraint. **Do not add a new production file for the module.**
- `[[gradle-single-test-class-task]]` — run the new test with `./gradlew testDebugUnitTest --tests "de.pyryco.mobile.di.ConversationRepositoryBindingTest"`. Bare `./gradlew test --tests …` fails (`test` is the aggregate task).

## Context

Phase 4 backend integration. `StableConversationRepository` (the stable facade ViewModels hold across relay connection churn, #352) is already a resolvable singleton in `AppModule.kt` but does **not** bind `ConversationRepository` — `FakeConversationRepository` still holds that binding. This slice adds a build-time flag that selects which implementation wins the `ConversationRepository` binding. The flag defaults **OFF** (fake), so it is safe to land before the relay backend is functional end-to-end.

The connection-state source (`RelayConnectionSupervisor`, #307) is already the real, bound `ConnectionStateSource` — so the only binding needing a fake/real choice here is `ConversationRepository`. No new repository wiring; just a binding selector.

Out of scope (do **not** attempt here): flipping the flag ON for real use, and any live-network verification. That production flip additionally depends on #346/#347/#348 (v2 mutations), #336 (session boundaries), and #337 (streaming).

## Design

### 1. The flag — `buildConfigField` boolean

In `app/build.gradle.kts` `defaultConfig`, beside the existing `GIT_SHA` field:

```kotlin
buildConfigField("boolean", "USE_RELAY_REPOSITORY", "false")
```

This generates `de.pyryco.mobile.BuildConfig.USE_RELAY_REPOSITORY: Boolean = false`. `buildConfig = true` is already enabled (line 72), so no `buildFeatures` change.

**Why a `buildConfigField` boolean, not a build flavor / build type / DataStore setting:**
- It is the minimal seam — one line, no new variant matrix, follows the existing `GIT_SHA` precedent.
- It is a **compile-time constant baked into the binary**. There is no runtime setter, no settings UI, no persisted key. This is exactly the property the ticket requires ("not settable by any untrusted input") — satisfied structurally, for free. See § Security review.
- To flip ON for a developer build later, change the literal to `"true"` (or add a per-`buildType` override). That is a deliberate developer/build action, out of scope here.

### 2. The binding selector — a small dedicated module

`ConversationRepository` is bound by a new module function whose `useRelay` parameter defaults to the flag. Add to `AppModule.kt` (same file as `appModule`):

```kotlin
fun conversationRepositoryModule(
    useRelay: Boolean = BuildConfig.USE_RELAY_REPOSITORY,
): Module = module {
    single<ConversationRepository> {
        if (useRelay) get<StableConversationRepository>() else get<FakeConversationRepository>()
    }
}
```

- `BuildConfig` is already imported (line 9). Add `import org.koin.core.module.Module`.
- The selector resolves the two concrete singletons by type (`get<…>()`); it never constructs them, so it carries none of their dependency weight.

**Why a separate module rather than folding the selector into a parameterized `appModule`:** the selector must be unit-testable via real DI resolution (AC #3) without dragging in the Android-bound graph. `appModule` eagerly wires `LifecycleConnectionDriver` (needs `ProcessLifecycleOwner`) and the keystore/Noise chain (needs `androidContext()` and `Build.MODEL`) — resolving anything through it requires Robolectric/instrumented. Isolating the selector into its own module lets a plain JVM unit test load **only** `conversationRepositoryModule(…)` plus a tiny stub-deps module and assert the resolved type. This is the minimal seam that makes the AC testable as a fast unit test, not over-engineering.

### 3. Edits to `AppModule.kt`'s `appModule`

- **Line 57:** drop the interface bind so the Fake is concrete-only:
  - `single { FakeConversationRepository() } bind ConversationRepository::class` → `single { FakeConversationRepository() }`
- **Lines 80-84:** `StableConversationRepository` single is unchanged — it is already concrete-only.
- After the change, **`appModule` no longer binds `ConversationRepository` at all**; the interface is bound exclusively by `conversationRepositoryModule`. (This avoids a duplicate-definition collision between the old Fake bind and the new selector.)

### 4. Composition root — `PyryApp.kt`

Load both modules; the selector reads the flag via its default param:

```kotlin
startKoin {
    androidContext(this@PyryApp)
    modules(appModule, conversationRepositoryModule())
}
```

Add `import de.pyryco.mobile.di.conversationRepositoryModule`. Module load order is irrelevant — the selector's `get<…>()` is lazy and resolves the concrete singletons from `appModule` cross-module.

### Data flow

```
BuildConfig.USE_RELAY_REPOSITORY (compile-time const, default false)
        │  default param
        ▼
conversationRepositoryModule(useRelay)
        │  single<ConversationRepository>
        ▼
   useRelay ? get<StableConversationRepository>()   // #352 facade → coordinator → connection-scoped remote repo
            : get<FakeConversationRepository>()      // in-memory seed data (unchanged default)
        ▼
ViewModels resolve ConversationRepository (unchanged call sites)
```

ViewModel constructors and every other `get<ConversationRepository>()` consumer are untouched — they keep resolving the interface; only what answers it changes.

## State + concurrency model

None introduced. The selector is a one-shot `if` evaluated when Koin first resolves the `single<ConversationRepository>`, then memoized by Koin for the process lifetime. No coroutine, no scope, no `StateFlow`, no shared mutable state, no TOCTOU. The facade's flow/scope/cancellation behaviour is owned by #352 and is not touched here.

## Error handling

- DI resolution cannot fail in the wired app: both branches' concrete singletons are registered in `appModule`, loaded alongside the selector in `PyryApp`.
- If a malformed **test** loads `conversationRepositoryModule(useRelay = true)` without providing a `StableConversationRepository` singleton, Koin throws `NoDefinitionFoundException` at `get()` — a loud test-wiring error, which is acceptable (fail fast in tests).
- Flag-ON-with-no-live-connection behaviour (facade emits empty projections / throws `IllegalStateException` on one-shots) is #352's contract and out of scope here. The flag defaults OFF, so this slice ships no live-network behaviour.

## Testing strategy

New unit test: `app/src/test/java/de/pyryco/mobile/di/ConversationRepositoryBindingTest.kt`. Plain JUnit + coroutines-test; **no `koin-test` dependency** — use `org.koin.dsl.koinApplication` (in `koin-core`, transitively on the unit-test classpath via `koin-androidx-compose`). Use a **local, isolated** `koinApplication { … }` per case (never `startKoin`/`GlobalContext`, which would need `stopKoin` teardown and risk cross-test bleed); `close()` the app in a `finally`/`@After`. No eager singletons are present in the loaded modules, so `createEagerInstances` is a no-op and nothing touches Android.

Test scaffolding (describe, don't pre-write): a helper that builds `koinApplication { modules(stubDeps, conversationRepositoryModule(useRelay = <param>)) }` and returns its `Koin`, where `stubDeps = module { single { FakeConversationRepository() }; single { StableConversationRepository(MutableStateFlow(null)) } }`.

Scenarios (bullet specs — developer writes bodies in the project idiom):

- **Flag off → Fake bound (AC #2).** Build with `useRelay = false`; `get<ConversationRepository>()` is a `FakeConversationRepository`.
- **Flag on → facade bound (AC #3), asserted via DI resolution only.** Build with `useRelay = true`; `get<ConversationRepository>()` is a `StableConversationRepository`. No network is opened (the stub facade wraps `MutableStateFlow(null)`).
- **Default param tracks the build flag and the safe default is fake (AC #1).** Build with `conversationRepositoryModule()` (no arg); `get<ConversationRepository>()` is a `FakeConversationRepository`, because `BuildConfig.USE_RELAY_REPOSITORY` is `false` in the debug/test build. This test pins the default-OFF guarantee: if anyone flips the `buildConfigField` literal to `true`, this case goes red — an intended tripwire. Add a one-line comment in the test saying so.
- **Force-fake regardless of flag (AC #4).** Document + assert the override mechanism: a test/preview that needs fake mode constructs its Koin graph with `conversationRepositoryModule(useRelay = false)`, which yields `FakeConversationRepository` independent of any build-flag value. This is the primary force-fake mechanism. (Koin also permits a later module to override the binding, but the parameter mechanism is preferred — it avoids depending on Koin override semantics.)
- **Singleton identity (light).** Resolving `ConversationRepository` twice returns the same instance (Koin `single`). Optional; one assertion.

**Previews.** Composables in this app are stateless (`state, onEvent`) and previews pass fake state directly — previews do not start Koin, so they are already flag-independent; no change needed. A `@Preview` that genuinely needs a repository instance constructs `FakeConversationRepository()` directly or builds a scope with `conversationRepositoryModule(useRelay = false)`. No production preview module is required by this slice.

**Existing instrumented tests** (`androidTest`) run against the real `PyryApp`, which now loads `conversationRepositoryModule()` with the default flag `false` → still fake-bound. Behaviour is identical to today (AC #2 for tests).

Run: `./gradlew testDebugUnitTest --tests "de.pyryco.mobile.di.ConversationRepositoryBindingTest"`, then `./gradlew test lint`.

## Open questions

- None blocking. The flag value for a future relay-enabled developer build (flip the literal vs. add a `debug` buildType override vs. a `relay` build flavor) is deferred to the production-flip ticket(s); this slice only establishes the OFF-default seam.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries] No findings.** The flag is a compile-time constant (`BuildConfig.USE_RELAY_REPOSITORY`) baked into the APK — it is never parsed from any runtime input (no QR payload, deep link, push body, intent extra, or network frame reaches it). The one trust boundary that matters in Phase 4, relay-network → process, lives entirely behind `StableConversationRepository`/`RemoteConversationRepository` (#351/#352), which this slice neither adds nor modifies. This slice moves only an object reference selected by a build constant.
- **[Tokens, secrets, credentials] N/A by design.** No token, secret, or credential is introduced, read, stored, or logged. The selector chooses between two already-wired repositories. Pairing keys/tokens remain in `data/crypto`, untouched.
- **[File / storage] N/A.** No file or storage operation is added. The flag is a `buildConfigField`, not persisted state — nothing is written to DataStore/`SharedPreferences`/disk. (A DataStore-backed *user-settable* toggle was the obvious alternative and was **rejected** precisely because it would create a runtime trust boundary and a path for a confused/hostile setting to flip the backend — see Threat-model below.)
- **[Inter-process / Android attack surface] N/A.** No new `Activity`/`Service`/`BroadcastReceiver`/`<intent-filter>`/deep link/`PendingIntent`/`ContentProvider`/`WebView`. No `android:exported` surface changes.
- **[Cryptographic primitives] N/A.** No RNG, key, or comparison. The selection is a plain boolean `if`, not a security-relevant comparison.
- **[Network & I/O] N/A in this slice.** No socket, OkHttp config, timeout, TLS setting, or frame handling is added. The flag defaults OFF, so no live network behaviour ships. Turning it ON later routes traffic through the existing relay stack (#307/#351/#352), whose network hardening is owned by those tickets; the production flip and its live-connection security verification are explicitly out of scope (depend on #346/#347/#348, #336, #337).
- **[Error messages, logs, telemetry] SHOULD FIX (minor, design-mandated).** The selector must emit **no logs** — consistent with the no-log posture of the facade/coordinator/pump in #352. There is no secret or payload in scope to leak, and a one-time DI `if` has no reason to log. If a developer adds a debug log of the chosen impl, it must contain only the class name (no host, no token, no payload). Code-review checks: no new `Log`/`Timber` call in `conversationRepositoryModule`.
- **[Concurrency] N/A.** No coroutine, scope, `StateFlow`, or shared mutable state is introduced. The selector is evaluated once and memoized by Koin; no TOCTOU.
- **[Threat model alignment] Addressed — this is the load-bearing property.** The relevant mobile threat is *"untrusted input flips the app onto the real backend (or off the fake)."* The design defeats it structurally: the flag is a compile-time `BuildConfig` constant with **no runtime setter** — no settings UI, no DataStore key, no intent extra, no deep-link param can mutate it. A hostile actor cannot flip it without recompiling the APK, which is inside the developer/build trust domain. The ticket's "not settable by untrusted input" requirement is satisfied by construction, with no added enforcement code. Out of scope and named: the production flip and its live verification (#346/#347/#348, #336, #337).

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-06-06
