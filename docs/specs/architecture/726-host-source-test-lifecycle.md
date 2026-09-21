# 726 — Stop a `Dispatchers.Default`-backed `HostConversationSource` outliving its unit test

## Files read

- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt` → `HostConversationSource`,
  `reconcile`, `update`, `publish`, `dispose`, `repositoryFor`, `demo`, `relay` — the class under
  diagnosis; `dispose` and every publish path share one instance monitor, which is what makes a
  post-`dispose` publish impossible and makes `repositoryFor` a sound disposal probe.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → `hostConversationModule`, `appModule`,
  `conversationRepositoryModule` — the only construction site that takes the `Dispatchers.Default`
  default, and the `onClose { it?.dispose() }` that ties disposal to Koin close.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` → `hostState`
  (`stateIn(viewModelScope, WhileSubscribed(...))`) — the `Dispatchers.Main`-bound subscriber that a
  background publish resumes.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/DiscussionListViewModel.kt` → `hostState`
  — the same subscriber shape on the discussion side.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/HostChannelListViewModelTest.kt` →
  `appModuleInjectsSharedDemoSourceAndCreatesThroughExistingFakeSingleton`, `teardown`, `Fixture` —
  one of the two leak sites; `Fixture` builds its own source on the test dispatcher and is not at risk.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/HostDiscussionListViewModelTest.kt` →
  `appModuleInjectsSharedDemoSourceAndPromotesThroughExistingFakeSingleton`, `teardown` — the second
  leak site.
- `app/src/test/java/de/pyryco/mobile/di/RelayConnectionFactoryTest.kt` →
  `selectorSharesHostSourceAndKeepsDemoLookupSeparateFromSavedRelayHosts` — the third Koin-resolving
  site; already asserts disposal by hand, and is the precedent this ticket generalises.
- `app/src/test/java/de/pyryco/mobile/di/ConversationRepositoryBindingTest.kt` → `withSelector` —
  loads the selector but never resolves the source, so it constructs none. Confirmed, not touched.
- `app/src/test/java/de/pyryco/mobile/di/HostConversationSourceTest.kt` — the #704 contract suite;
  every case passes an explicit test dispatcher. Untouched, per AC 4.
- `docs/knowledge/features/dependency-injection.md` § "Snapshot lifetime", § "Testing" — records that
  the Koin singleton owns its cache "until Koin close calls `dispose()`", and names the three tests
  that resolve it. The lesson this ticket adds is that Koin close is necessary but not sufficient:
  it also has to be *ordered and proven* against `Dispatchers.resetMain()`.
- `docs/knowledge/features/development-verification.md` § JVM unit-test checks — where the
  documentation stage records the pitfall (see Documentation handoff).

## Context

`HostChannelListViewModelTest > projectionPreservesHostPairsOrderMetadataAndCachedRowsWithoutWaitingForPreviews`
fails intermittently with "Module with the Main dispatcher had failed to initialize", raised from a
`CoroutineScheduler$Worker` frame inside `HostConversationSource.publish`. The named test is the
victim: the throw happens on a background thread and attaches to whatever is running.

### What the run evidence shows

A temporary probe was added to `HostConversationSource` (construction site, dispatcher, every
`publish` with its thread, `state.subscriptionCount` and whether `Dispatchers.Main` was resolvable,
and every `dispose`), and the full JVM suite was run twice — once normally, once with
`kotlinx.coroutines.scheduler.{core,max}.pool.size=1` to starve the `Dispatchers.Default` pool and
push publishes as late as possible. Both runs were green; the probe output is the evidence, not the
pass/fail. The probe was removed before this plan was committed. Results, identical across both runs:

| Source | Construction site | Dispatcher | Publishes from a `DefaultDispatcher-worker` | Subscribers at publish |
|---|---|---|---|---|
| 4 of 4 `Default`-backed | `hostConversationModule` only | `Dispatchers.Default` | yes | see below |
| all others (≈50) | test fixtures | explicit test dispatcher | no — test thread only | n/a |

Of the four `Dispatchers.Default`-backed instances:

- `RelayConnectionFactoryTest.selectorSharesHostSourceAndKeepsDemoLookupSeparateFromSavedRelayHosts`
  (two instances, demo and relay legs) publishes from worker threads while `Dispatchers.Main` is
  **unset** — but with `subscriptionCount == 0`, so `StateFlowImpl.setValue` resumes nobody and
  nothing can throw. Hazardous shape, currently inert.
- `HostChannelListViewModelTest.appModuleInjectsSharedDemoSourceAndCreatesThroughExistingFakeSingleton`
  and
  `HostDiscussionListViewModelTest.appModuleInjectsSharedDemoSourceAndPromotesThroughExistingFakeSingleton`
  publish from worker threads with `subscriptionCount == 1` — the `stateIn(viewModelScope, …)`
  collector in `ChannelListViewModel.hostState` / `DiscussionListViewModel.hostState`, bound to
  `Dispatchers.Main.immediate`.

**These two are the only publishes in the entire JVM unit suite that can resume a
`Dispatchers.Main`-bound continuation from a background thread** — exactly the captured stack. They
are the publishing instances the ticket asks to name. Both come from the `hostConversationModule`
`single`, reached through `conversationRepositoryModule(false)` → `includes(…)`, via
`HostConversationSource.demo` with its `Dispatchers.Default` default.

### Why Koin's `onClose` is not the defect

Ruled out, so it is not re-derived downstream. `SingleInstanceFactory.drop` invokes
`beanDefinition.callbacks.onClose` with the held value; `InstanceRegistry.close` calls `dropAll()` on
every factory; `Koin.close` calls it; `KoinApplication.close` calls `Koin.close`. Read from the
`koin-core-jvm` 4.0.4 bytecode, and corroborated by the probe: **zero** undisposed sources at JVM
shutdown across both runs. Disposal through `includes()` works.

### What is actually wrong

`dispose()` and every path that reaches `publish()` are `@Synchronized` on the same monitor and
`disposed`-guarded, so a disposed source provably cannot publish — the refinement's point 2 holds.
The defect is therefore not *whether* the source is disposed but *whether disposal is ordered and
proven against the class's `Dispatchers.resetMain()`*, and in both leak sites nothing guarantees it:

1. **The container is built outside the guarded region.** In both tests the
   `KoinApplication.init().modules(…)` call and the `app.koin.get<…ViewModel>()` that constructs the
   source sit *before* the `try`. A throw there — a missing binding, a preference-migration failure,
   a future edit — creates a `Dispatchers.Default`-backed source with a `Dispatchers.Main`-bound
   subscriber on a path with no `finally` at all. The class's `@After` then runs
   `Dispatchers.resetMain()` unconditionally, and the next worker-thread publish resumes that
   subscriber into a torn-down Main.
2. **Nothing asserts that disposal happened.** Neither test checks the source after `app.close()`.
   A future edit that drops the `close()`, or reorders it after `resetMain()`, stays green until the
   race fires on someone else's ticket — which is how this reached the board in the first place.

`RelayConnectionFactoryTest.selectorShares…` already does the right thing by hand
(`assertTrue(source.snapshots.value.isEmpty())`, `assertNull(source.repositoryFor(…))` after close).
This ticket makes that the shared, enforced shape for all three sites.

The fix is test-lifecycle only, which is the ticket's stated preference. No production file changes,
so the #704 contract (collection continues without subscribers until `dispose()`; `repositoryFor`
refuses a retired host) is unchanged by construction, and `HostConversationSourceTest` is untouched.

This ticket records a pitfall worth an evergreen note but not an ADR; the Documentation handoff below
routes it.

## Design

One new test-only helper in `app/src/test/java/de/pyryco/mobile/di/KoinHostSources.kt`:

```kotlin
/** Owns the Koin containers a JVM test resolves a HostConversationSource from. */
class KoinHostSources {
    /** Resolves the shared source from [app] and registers it for the disposal proof. */
    fun source(app: KoinApplication, liveHostId: String = HostConversationSource.DEMO_SERVER_ID): HostConversationSource

    /** Closes every registered container and proves each source stopped. Call before resetMain. */
    fun closeAndAssertStopped()

    /** `@After` net: closes anything a test left open, then fails naming that omission. */
    fun assertAllClosed()
}
```

Behaviour:

- `source(app, liveHostId)` resolves `HostConversationSource` from the container and records the
  triple (container, source, the host id that is live in that container).
- `closeAndAssertStopped()` closes each registered container, then for each source asserts
  `repositoryFor(liveHostId) == null` and `snapshots.value.isEmpty()`, then clears its registry so a
  second call is a no-op. Idempotent by design: every owning test calls it in its own `finally`, and
  the class `@After` calls the net below.
- `assertAllClosed()` captures whether anything is still registered, runs `closeAndAssertStopped()`
  so the leak cannot escape the test method even when it fires, and then fails if anything had been
  left open. Closing first and failing second is deliberate: a red test must not also leave a live
  `Dispatchers.Default` collector behind for the next test to inherit.

Why `repositoryFor` is the primary proof and not `snapshots`: it is `@Synchronized`, so reading it
from the test thread takes the same monitor the worker threads take, giving the happens-before edge;
and it returns `null` **only** when `disposed` is set, because the demo lookup returns the fake for
`demo` and the relay lookup returns a live repository for a connected host. `disposed` set under that
monitor means `scope.cancel()` already ran and every later `reconcile`/`update` returns before
`publish()` — i.e. collection has stopped, with no sleep, no loop and no tolerance.
`snapshots.value.isEmpty()` is kept as a secondary check because it is the existing
`selectorShares…` assertion and is cheap, but it can be vacuously true for an unpaired container, so
it is not load-bearing.

Call sites:

- `HostChannelListViewModelTest` — move the container build and `app.koin.get<ChannelListViewModel>()`
  inside the guarded region of `appModuleInjectsSharedDemoSourceAndCreatesThroughExistingFakeSingleton`;
  obtain the source through `hostSources.source(app)`; replace the `finally`'s `app.close()` with
  `hostSources.closeAndAssertStopped()`; add `hostSources.assertAllClosed()` to `teardown()` **before**
  `Dispatchers.resetMain()`.
- `HostDiscussionListViewModelTest` — the same three changes on
  `appModuleInjectsSharedDemoSourceAndPromotesThroughExistingFakeSingleton` and its `teardown()`.
- `RelayConnectionFactoryTest` — `selectorSharesHostSourceAndKeepsDemoLookupSeparateFromSavedRelayHosts`
  obtains its source through `hostSources.source(app, liveHostId)` and replaces its hand-written
  `app.close()` plus the two post-close assertions with `closeAndAssertStopped()`; the existing
  `registry.dispose()` / `runCurrent()` stay, in that order, after the close. A new `@After` on the
  class calls `assertAllClosed()`.

Untouched: the `Fixture` classes in both list tests (their sources take the test dispatcher and are
already cancelled and disposed in `teardown()`), `ChannelListViewModelTest` (disposes its sources),
`ConversationRepositoryBindingTest` (constructs none), and `HostConversationSourceTest`.

## State + concurrency model

No coroutines are added. The helper runs entirely on the JUnit test thread. Its only concurrency
contract is the monitor acquisition described above: `repositoryFor` is the synchronisation point
between the test thread and the `Dispatchers.Default` workers, and the assertion is taken after
`KoinApplication.close()` has returned, so `dispose()` has completed and no further `publish()` can
execute. The ordering requirement the whole fix rests on — helper before `Dispatchers.resetMain()` in
each class's `@After` — is expressed as explicit statement order, not as a JUnit `TestRule`: a
`TestRule`'s after-block runs *after* all `@After` methods, i.e. on the wrong side of `resetMain()`.

## Error handling

Assertion failures only; no new runtime failure modes. Failure messages name the test class and the
`serverId` constant and nothing else — no repository, record, transport or `toString()` of a
connection object.

## Testing strategy

JVM unit tests only (`./gradlew testDebugUnitTest`); no Compose UI test, no emulator scenario. This
is not an operator-facing flow, so § B1's rung-3 requirement does not fire.

- RED: with the helper wired in, temporarily remove the container close from
  `closeAndAssertStopped()` and run the three classes — the disposal assertion must fail for each of
  the three Koin-resolving cases. Restore, then GREEN.
- The three converted cases keep every assertion they have today; the only additions are the
  disposal proof and the `@After` net.
- Scoped verification: `testDebugUnitTest` for `HostChannelListViewModelTest`,
  `HostDiscussionListViewModelTest`, `RelayConnectionFactoryTest`, `HostConversationSourceTest` and
  `ConversationRepositoryBindingTest`, plus `lint` and `assembleDebug`. The whole-suite regression is
  the verifier's gate.

## Open questions

- Does the `@After` net belong on `RelayConnectionFactoryTest`, whose tests reset Main inside their
  own bodies rather than in an `@After`? Resolved in Phase B: yes — the net still reddens on a leaked
  container, and that class is where a future Koin-resolving test is most likely to be added.
- Should `hostConversationModule` take a dispatcher parameter so tests can pull the source into
  virtual time? Rejected: it is a production signature change made for test convenience, and those
  two tests exist specifically to exercise the production DI graph. Recorded here so the verifier can
  see it was considered rather than missed.

## Documentation handoff

Pending, owned by the documentation stage — not by this builder or the verifier.

- **Requirement:** record the pitfall in `docs/knowledge/features/development-verification.md`, in
  the section on JVM unit-test checks: a unit test that constructs or resolves a component backed by
  `Dispatchers.Default` must stop it before `Dispatchers.resetMain()`, and the resulting failure
  attaches to an unrelated test rather than to the leaking one. Name the observable symptom
  ("Module with the Main dispatcher had failed to initialize" raised from a
  `CoroutineScheduler$Worker` frame) so the next occurrence is searchable.
- No other documentation-only acceptance criteria are carried on this ticket.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — the change adds no parsing, no inbound verb and no new path from
  daemon-authored text into Compose. The one boundary it touches is the host-identity check in
  `HostConversationSource.repositoryFor`, and it touches it only by *reading* it: the converted
  `selectorShares…` case must keep asserting `repositoryFor` rejects the other mode's host (`"demo"`
  in relay mode, `"A"` in demo mode) verbatim, because those assertions are what keep a retired or
  foreign host's repository out of another host's snapshot (#704 and its follow-up).
- [Tokens, secrets, credentials] No findings by construction, one discipline to hold: the helper's
  assertion messages interpolate only the test class name and the `serverId` constant. Never the
  `ConversationRepository`, the `PairedServerRecord`, or a transport — `RelayConnectionFactoryTest`
  runs real Noise peers, and a `toString()` of a connection object in a failure message would put
  handshake-adjacent state into CI output.
- [File / storage operations] Not applicable — no filesystem access. The helper holds references in
  an in-memory list for the duration of one test method.
- [Inter-process / Android attack surface] Not applicable — JVM unit tests only; no manifest, intent
  filter, pending intent, provider or WebView change.
- [Cryptographic primitives] No findings — no crypto is added or reordered. One concrete constraint:
  in `selectorShares…` the close order must stay `closeAndAssertStopped()` → `registry.dispose()` →
  `runCurrent()`. Disposing the registry before closing the container would tear the connection
  bundles down underneath a live source and change what the test proves about transport shutdown.
- [Network & I/O] No findings — no client, timeout, TLS or backoff setting is touched, and no
  assertion about transports being closed is removed.
- [Error messages, logs, telemetry] No findings — `HostConversationSource.dispose` keeps its
  content-free `event=host_snapshots_disposed` log unchanged. See the Tokens finding for the
  test-side message rule.
- [Concurrency] The category this ticket lives in. Three decisions, all in Design/State above:
  (a) the disposal proof must go through the `@Synchronized` `repositoryFor`, not a plain field read
  or `snapshots.value` alone, or it has no happens-before edge against the `Dispatchers.Default`
  workers; (b) the `@Synchronized` guards and the host-identity checks in `update` are not weakened —
  no production line changes at all; (c) the net must run before `Dispatchers.resetMain()`, which is
  why it is a statement in each `@After` rather than a `TestRule` whose after-block would run on the
  wrong side of the tear-down.
- [Threat model alignment] Not applicable to this ticket's surface — the production
  `HostConversationSource` contract is byte-for-byte unchanged, so no mobile threat (hostile relay,
  token theft from disk, hostile daemon frame, UI-side leakage) moves. The hazardous *shape* the
  probe found in `selectorShares…` — a `Dispatchers.Default`-backed source publishing while Main is
  unset — is addressed here by the disposal proof rather than deferred.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-21
