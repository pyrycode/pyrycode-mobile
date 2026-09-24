# #892 — Demo-source test body migrates onto a Default worker and races `resetMain`

## Files read

- `app/src/test/java/de/pyryco/mobile/di/KoinHostSources.kt` → `KoinHostSources.closeAndAssertStopped`, `assertAllClosed` — the #726 disposal proof this ticket found a hole in.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt` → `update`, `publish`, `dispose`, `isCurrent` — every publish runs under the instance monitor; `dispose` takes the same monitor, reentrantly.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → `hostConversationModule` — the demo source Koin builds takes the `Dispatchers.Default` default.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` → `hostState` — `flatMapLatest` over `snapshots`, `stateIn(viewModelScope, WhileSubscribed)`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/DiscussionListViewModel.kt` → `hostState` — `combine` over `snapshots`, `stateIn(viewModelScope, Eagerly)`.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/HostChannelListViewModelTest.kt` → `appModuleInjectsSharedDemoSourceAndCreatesThroughExistingFakeSingleton`, `setup`/`teardown` — the leaking test; it runs immediately before `dismissingTheChatEditorSendsNothing` in JUnit's method order.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/HostDiscussionListViewModelTest.kt` → `appModuleInjectsSharedDemoSourceAndPromotesThroughExistingFakeSingleton` — same shape, same exposure.
- `app/src/test/java/de/pyryco/mobile/di/RelayConnectionFactoryTest.kt` → `selectorSharesHostSourceAndKeepsDemoLookupSeparateFromSavedRelayHosts` — builds a demo source but attaches no ViewModel and runs on the default `StandardTestDispatcher`; not exposed.
- `docs/specs/architecture/726-host-source-test-lifecycle.md` — the proof's stated happens-before argument.

In-flight overlap: `feature/904` edits a different test in `HostChannelListViewModelTest`; this ticket's edit there is local to the appModule test.

## Design source

N/A — test harness only, nothing UI-visible.

## Context — the diagnosis (acceptance criterion 1)

**Evidence.** A temporary harness (not committed) ran the channel appModule test body in a loop, with the class's own `setMain(UnconfinedTestDispatcher)` / `resetMain` bracket, recorded `Thread.currentThread()` at each step, and held whichever thread completed the test body for 200 ms from a `job.invokeOnCompletion` handler to widen the window. In the iterations where the Koin demo source published after `first {}` had subscribed, the log read `start: Test worker`, `after-first: DefaultDispatcher-worker-3`, `finally: DefaultDispatcher-worker-3`, and the following empty `runTest` failed with the gate's exact chain: `CompletionHandlerException` (`ChildCompletion … StandaloneCoroutine{Cancelled}`) → `DispatchException` from `ScopeCoroutine.afterCompletion` → `safeIsDispatchNeeded` → `TestMainDispatcherJvm.kt:45` → `MainDispatchers.kt:111` → `Looper`, on a worker whose stack is `publish ← update ← reconcile` collecting `FakeConversationRepository.observeConversations`. Without the hold, 20 runs of the three classes and 3000 loop iterations stayed green: the window is the worker's unwind time against the test thread's `runTest` wrap-up, which only a loaded machine (the verifier's full `check`, lint running alongside) opens.

**The coroutine.** A cancelled child of `ChannelListViewModel.hostState`'s pipeline (a `StandaloneCoroutine` under `flatMapLatest`/`combine`, in `viewModelScope`) completing on the demo source's `Dispatchers.Default` worker. Its `ChildCompletion` finalises the parent `ScopeCoroutine`, whose `afterCompletion` resumes the caller through `Dispatchers.Main`, after the test thread's `resetMain`.

**The test that started it.** `HostChannelListViewModelTest.appModuleInjectsSharedDemoSourceAndCreatesThroughExistingFakeSingleton`. The failure is reported to the next `runTest`, which in JUnit's method order is `dismissingTheChatEditorSendsNothing`. `HostDiscussionListViewModelTest.appModuleInjectsSharedDemoSourceAndPromotesThroughExistingFakeSingleton` has the same exposure.

**How it got past #726.** Both `Main` and the test body run on `UnconfinedTestDispatcher`, which resumes a coroutine in place on whatever thread wakes it. The demo source's worker publishes under the source's monitor; `StateFlow.value` resumes the ViewModel's collector in place, which resumes the test body's `first {}` in place. The rest of the test body, including its `finally` — `viewModelScope.cancel()` and `closeAndAssertStopped()` — then runs on the worker, nested inside that same `publish`. `dispose()` is `@Synchronized`, and the worker already holds the monitor, so it enters reentrantly; `repositoryFor` returns null and the proof passes. Its happens-before argument assumes the prover and the publisher are different threads. Here they are the same thread, and the frames beneath the proof are the cancelled ViewModel coroutines that still have to unwind. The test body completes, the test thread leaves `runTest` and calls `resetMain`, and the worker then unwinds into `Dispatchers.Main`.

## Design

Two changes, test harness only. No production file changes: `HostConversationSource` behaves as designed — publishing to subscribers in place is how `StateFlow` works, and the hazard is a test `Main` that runs resumed coroutines on the publisher's thread.

1. **Close the path (acceptance criterion 2).** In both appModule tests, install `StandardTestDispatcher(testScheduler)` as `Main` at the top of the test body, before the ViewModel is resolved. A Main-bound continuation resumed by a worker is then queued on the test scheduler and run by `runTest` on the test thread; no ViewModel code, and so no test-body code, can run on the demo source's worker. The class's `@After` still calls `resetMain` after `assertAllClosed`. This is the pattern `RelayConnectionFactoryTest` already uses for its Koin ViewModel tests.
2. **Make the leak fail in its own test (acceptance criterion 2, second sentence).** `KoinHostSources` records the thread that constructs it — the JUnit thread, which later runs `@After` and `resetMain`. `closeAndAssertStopped` first asserts that it runs on that thread, naming the thread it found and the cause, and on a mismatch fails **without** closing anything: the containers stay tracked, so the owner-thread `@After` net closes them, and that close blocks on the source's monitor until the worker has unwound. A proof on the owning thread is ordered before `resetMain` by program order; a proof on any other thread is not.

## State + concurrency model

Unchanged in production. In the two tests, `Main` becomes a `StandardTestDispatcher` sharing the class dispatcher's `testScheduler`; the test body stays on the class's `UnconfinedTestDispatcher`. `runTest` waits for dispatches from the worker and runs them on the test thread. The channel ViewModel's `WhileSubscribed` stop timer may now fire in virtual time once `first {}` returns; the test reads nothing from `hostState` afterwards.

## Error handling

The new assertion is a test failure with a message; it throws before touching any container, so it cannot strand one.

## Testing strategy

- With change 2 and without change 1, run the channel appModule test in a loop: the owning test fails with the new message whenever the body migrates, instead of a later test failing on the race.
- With both changes, 20 consecutive `testDebugUnitTest --tests` runs of the three classes together with `--rerun`, then one full `./gradlew test` (acceptance criterion 3).
- The temporary harness from the diagnosis re-run against the fixed test shape shows no migration.

## Open questions

- Whether `StandardTestDispatcher` as `Main` changes either test's observable sequence (virtual-time stop timer, navigation events). Resolved in Phase B by running them.

## Documentation handoff

None required by the ticket. Candidate for the documentation stage: `docs/knowledge/features/development-verification.md` (or the owning test-lifecycle topic that records #726) could note that a disposal proof only holds on the thread that calls `resetMain`, and that an `UnconfinedTestDispatcher` `Main` lets a `Dispatchers.Default` publisher run the test body. Pending for the documentation stage.
