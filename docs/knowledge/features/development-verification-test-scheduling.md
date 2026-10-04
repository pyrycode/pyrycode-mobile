# Development verification — test scheduling and harnesses

Split out of [Development verification](development-verification.md) on 2026-10-02 to keep that
document under the 50000-byte size cap the docs guard enforces. Every section below moved here
verbatim and kept its heading, so its anchors are unchanged. Part of
[Development verification](development-verification.md); see that document for the other topics
and its links.

## Test scheduling and harnesses

The routine UI gate excludes `de.pyryco.mobile.e2e` through the instrumentation
`notPackage` argument. A test in that package can compile without running in this
gate. Keep ordinary application-wiring assertions in their owning package:
`de.pyryco.mobile.di.RepositoryBindingInstrumentedTest` checks the installed test
application's fake binding when no relay arguments are supplied. Confirm its
testcase appears in the gate XML; compilation alone does not prove isolation.

Test peers sharing a pairing token must share its bound static identity for the instrumentation
process (#1698). `PeerDeviceStaticKeyStore` in `sharedTest` serializes creation by exact server id
and token, independently of app credential storage, peer close and Koin graph rebuild. Return copies
of both key arrays and of `publicKey()`: `NoiseSessionFactory` wipes its caller-owned private-key
buffer after copying it into fresh per-dial Noise state. A returned buffer being zeroed must not
corrupt the retained identity. `PeerDeviceStaticKeyStoreTest` covers continuity, host/token isolation,
copy safety, concurrent creation and repeated factory creation; never include keys or tokens in
assertion output. See [the rung-3 peer](../../e2e-interactive-stream.md#what-rung-3-is-made-of).

A standalone peer scenario can pass while later full-suite scenarios fail because an earlier peer
bound their shared token. Open and close a prior peer with the same pairing before the observing peer
when testing this lifecycle, as the [Stop scenario](../../e2e-interactive-stream.md#what-rung-3-is-made-of)
does (#1696); both opens must satisfy the existing handshake/probe readiness contract. Comparing
stored key arrays alone does not prove the authenticated identity on the wire.
`PeerDeviceStaticKeyStoreTest.sequentialFactoriesPresentSameBoundIdentityInFreshNoiseHandshakes`
uses fresh vendored Noise responders to authenticate successive initiator keys and decrypt each
synthetic hello. It checks the same static identity and token but different handshake messages,
protecting identity continuity without sharing ephemeral or cipher state. Restoring the former
per-instance lifecycle made this exact method fail at the identity assertion (1 executed, 1 failed,
0 skipped); the repaired focused peer/factory/redial/wait run passed all 30 tests (0 failed, 0 skipped).
See [PR #1704's verification evidence](https://github.com/pyrycode/pyrycode-mobile/pull/1704#issuecomment-5975107860).

Readiness coverage must go beyond decrypting the initiator hello.
`OffscreenPeerReadinessTest.sequentialClosedPeersCompleteBoundHandshakeAndEncryptedReadinessProbe`
(#1692) completes `hello`/`hello_ack` for two sequential sessions, authenticates the same token-bound
static identity, and exchanges encrypted `list_conversations` → `conversations` envelopes with matching
`in_reply_to`. Each session uses a fresh factory and vendored Noise responder; destroy the session,
responder and transport cipher pair before constructing the next. Only the static identity survives.
This in-memory regression proves the encrypted readiness contract, while the live offscreen scenario's
same-token prior-peer open/close protects it through the real relay. It does not model relay/redial
scheduling. The former per-instance identity lifecycle failed the token-binding assertion (1 executed,
1 failed, 0 skipped); the repaired focused peer/factory/redial/wait run passed 31 tests (0 failed/errors,
0 skipped). See [PR #1711's verification evidence](https://github.com/pyrycode/pyrycode-mobile/pull/1711#issuecomment-5975716513)
and [the full live result](../../e2e-interactive-stream.md#verification-status).

A graph-lifecycle identity test can pass while contaminating the next test's repository binding.
`E2eTestApplication.rebuildGraph()` must preserve the original fake/relay mode selected by the relay
instrumentation argument, carry the existing DataStore, unregister the old lifecycle driver and dispose
old Koin owners. Call it on the main thread with no activity alive; rebuild in guaranteed cleanup and
assert repository mode and DataStore continuity. `PeerIdentityLifecycleTest` is e2e-only in
`E2E_ONLY_SOURCES`, so the routine UI gate skips it; preserve the source-dependency guard that prevents
ordinary UI/runner sources from depending on peer helpers.

Run the lifecycle test followed by the binding check in one no-relay instrumentation process under
the shared device lock:

```bash
./gradlew :app:pixel2Api33AtdDebugAndroidTest --rerun -Pandroid.testInstrumentationRunnerArguments.class=de.pyryco.mobile.e2e.PeerIdentityLifecycleTest,de.pyryco.mobile.di.RepositoryBindingInstrumentedTest --console=plain
```

Retain fresh XML from the selected managed-device report path before another device run overwrites
it, alongside the command log and exit status. Require both named methods to execute and pass, with
failed/error/skipped counts recorded. For #1698, `/tmp/builder-1698/rework-device-green.xml` records
**2 executed, 0 failed/errors, 0 skipped**: `sequentialPeersRetainIdentityAfterCloseAndAppGraphRebuild`
then `ordinaryInstrumentation_explicitlyBindsFakeRepository`; the adjacent command log records exit 0.
This focused check supplements dispatcher gates. The full live attachment proof and daemon diagnostic
comparison are recorded in [Verification status](../../e2e-interactive-stream.md#verification-status).

Use `runCurrent()` after pushing a fake relay item when the test path is a
channel-to-StateFlow cascade with no timer. `advanceUntilIdle()` does not
necessarily drain that background collector. Reserve it for tests whose contract
really advances virtual time. When adding a finite watchdog or timeout, drive only
the intended deadline with `advanceTimeBy(...)` followed by `runCurrent()`;
`advanceUntilIdle()` also advances newly armed watchdogs.

A fake repository seed backed by a `MutableStateFlow` — `FakeConversationRepository.setSlashCommandMenu`,
for example — needs `advanceUntilIdle()` before a `ViewModel.state.value` assertion sees it, even with an
`UnconfinedTestDispatcher` installed as `Main` (#884): the write still has to propagate through whatever
`map` / `combine` / `stateIn` chain sits between the fake's `MutableStateFlow` and the cached `state.value`.
A scripted fake's plain `MutableSharedFlow.emit`, by contrast, does not need it.

A plain-Kotlin controller constructed with `runTest`'s `backgroundScope` as its owner scope (rather
than the `TestScope` itself) never leaves its initial state under `advanceUntilIdle()` —
`backgroundScope` coroutines are not what that call drains, so every assertion fails on the test's own
setup, not on the code under test (#824). Pass the `TestScope` as the owner scope, or call
`runCurrent()` after launching in `backgroundScope`. The same gap bit a DataStore built with
`PreferenceDataStoreFactory.create(scope = backgroundScope, ...)` in a test (#953): its write actor
never ran under `advanceUntilIdle()`, so a read straight after saw the old value. Give the store its
own `CoroutineScope(StandardTestDispatcher(testScheduler) + Job())` instead and cancel it at the end —
see [Push messaging service § Testing](push-messaging-service.md#testing) for the full case. A third
instance: `RedialingLinkTest` (#1036) supervises its redial loop in `backgroundScope` by default, so a
case that needs the loop to have redialed drives `advanceTimeBy(...)` past the backoff plus
`runCurrent()`, not `advanceUntilIdle()`, for the same reason. More
generally, a `first { predicate }` wrapped in `withTimeout` against a live DataStore or other
in-memory `StateFlow` is not a safe "wait a bit": if the collector's first read overlaps the write it
is waiting for, the read returns the pre-write value and the emission it needed is never replayed, so
the wait times out no matter how high the timeout is. Prefer owning every coroutine the write uses and
reading once with a plain `first()` after draining the scheduler, over racing a wall clock against
writer code the test does not control.

A real file-backed DataStore can drop the emission for a documented reason, not just a scheduling
accident: DataStore 1.1.7's `writeData` increments the store's version before it writes the scratch
file, fsyncs it and renames it, and only afterwards updates the in-memory cache. A collector whose
first read lands in that window sees the pre-rename file content under the already-bumped version, so
`data`'s `dropWhile { it.version <= startState.version }` discards the real update once it finally
lands, as "not newer". The window is just the fsync plus the rename, so the miss rate tracks machine
load: `ThreadViewModelEffortRecallTest.aSuccessfulTap_survivesAnAppRestart` (#1094) missed 14 of 300
loop iterations unloaded and 34 of 300 under load, throwing `TimeoutCancellationException` only in the
full `./gradlew check`, never in a focused run of the one test class. There the write is driven by
production code (`ThreadViewModel.onEffortSelected` -> `EffortRecall.remember`), so the test cannot own
that coroutine the way the "owning every coroutine" advice above assumes. The fix instead waits on the
write call itself returning, not on the data stream: wrap the real `RememberedEffortStore` in a
delegating one whose `remember` calls through and then completes a `CompletableDeferred`, and await that
deferred in place of `store.data.first { predicate }`. To wait for a write you don't control, wait for
the writing call to return, never for a predicate over the read side.

A test that simulates an app restart by cancelling one `DataStore`'s owner scope and immediately
opening a second `PreferenceDataStoreFactory.create` on the same file must `join()` the first scope's
`Job`, not merely `cancel()` it: DataStore unregisters a file from its process-wide active-file set only
when the owning scope's job *completes*, and `cancel()` only requests that — it returns before the job
finishes. A second store opened in the gap sees the file still registered and its first read throws
`IllegalStateException: There are multiple DataStores active for the same file`, reproducing every time
the affected test runs alone (#1075, mirroring the pattern `AppPreferencesTest.rememberedEffort_survivesProcessDeath`
and `HostWorkspacePreferencesTest` — see [App preferences § Testing](app-preferences.md#testing) —
already used correctly). Hold the first scope's `Job` in a named `val` and call `job1.join()` right after
`scope1.cancel()`, before constructing the second store.

A JVM unit test that constructs or resolves a component backed by `Dispatchers.Default` — a Koin
singleton reached without an injected test dispatcher, for example — must stop it before that test
class's `Dispatchers.resetMain()`, not merely dispose it. The failure this produces attaches to
whichever test happens to be running next, not to the test that actually leaked the collector,
because the background thread throws asynchronously: watch for "Module with the Main dispatcher had
failed to initialize" raised from a `CoroutineScheduler$Worker` frame. Closing the owning container
in a `finally` is necessary but not sufficient — the close has to be ordered ahead of `resetMain()`
and the source has to be asserted stopped, not merely assumed disposed. `KoinHostSources`
(`app/src/test/java/de/pyryco/mobile/di/KoinHostSources.kt`) is the shared helper for
`HostConversationSource`: it registers every Koin container a test resolves the source from, closes
them, and proves each source stopped by reading the `@Synchronized` `repositoryFor` — the
load-bearing check, since it shares the monitor the worker threads take and returns `null` only once
`dispose()` has actually run, while a raw `snapshots.value.isEmpty()` read has no such
happens-before edge and can pass vacuously. `HostChannelListViewModelTest`,
`HostDiscussionListViewModelTest` and `RelayConnectionFactoryTest` call it from each resolving
test's own `finally`, and each class's `@After` calls `assertAllClosed()` before
`Dispatchers.resetMain()` runs (#726). A `JUnit4` `TestRule` cannot enforce that ordering: a rule's
`after`-block runs after every `@After` method, which is the wrong side of the reset.

The #726 proof holds only when it runs on the thread that later calls `resetMain()` — #892 found a
case where it does not. With an `UnconfinedTestDispatcher` installed as `Main`, a resumption resumes
in place on whichever thread wakes it: the `Dispatchers.Default` worker's `StateFlow` publish resumes
the ViewModel's collector in place, and the ViewModel resumes the test body's `first {}` the same way.
From there the rest of the test body — including its `finally` and `closeAndAssertStopped()` — runs on
the publishing worker, nested inside the very `publish` it is meant to fence. `dispose()` is
`@Synchronized` on that same monitor, so the reentrant call from the worker passes trivially and the
proof is vacuous, while the cancelled ViewModel coroutines underneath still have to unwind into `Main`
after the test thread calls `resetMain()`. This surfaced as `dismissingTheChatEditorSendsNothing`
failing in `HostChannelListViewModelTest`: the leaking test
(`appModuleInjectsSharedDemoSourceAndCreatesThroughExistingFakeSingleton`) runs immediately before it
in JUnit's method order, and kotlinx-coroutines-test reports an exception raised between tests at the
*next* `runTest`, not the one that leaked it — check the test immediately before the failing one in the
XML report's order before trusting which test caused a flake like this. The migration from test thread
to worker was frequent (up to ~97% of iterations in a stress loop) but the resulting crash was rare,
since it also needed the worker's unwind to race the test thread's teardown — a window that a stress
loop alone did not reliably open, but a loaded machine (a full `check` run, lint running alongside) did.
A test that pairs a Koin-built `HostConversationSource` with a ViewModel must install a *dispatching*
`StandardTestDispatcher(testScheduler)` as `Main` (not an `UnconfinedTestDispatcher`) before resolving
the ViewModel, so a worker's resumption is queued for the test thread instead of running in place; both
`HostChannelListViewModelTest` and `HostDiscussionListViewModelTest` do this now. `KoinHostSources`
also records the thread that constructs it and asserts `closeAndAssertStopped()` runs on that same
thread, failing before closing anything on a mismatch — so a regression fails the test that caused it
instead of a later, unrelated one.

Gradle gates do not exercise shell scripts. For a change under `scripts/`, extract
the changed function into a scratch file, add strict shell options and test it with
stubbed helpers. Exercise preflight guards with nonexistent `PYRY_BIN` and relay
paths so the script cannot start a real process. Keep the scratch file outside the
worktree.

Shell teardown must preserve the original test result. A final optional check
such as `[ -n "$ISO_HOME" ] && ...` can return failure when no isolated home exists,
turning a passing suite into a failed command. `e2e-emulator.sh` captures the
incoming status, uses explicit optional branches, and returns that status.
`test_e2e_emulator_cleanup.py` exercises success and failure with and without an
isolated home, including retention of failure artifacts. Check both the process
status and executed XML results; neither overrides a disagreement with the other.

A post-test diagnostic step needs its own status capture, distinct from that
teardown. Appending `|| STATUS=$?` to the Gradle test invocation (`STATUS=0` set
just before it) captures the test task's exit code before `set -euo pipefail` can
exit the script, so a following diagnostic step still runs; the step's own
explicit `exit "${STATUS}"` then keeps the run non-zero. A helper called from that
step must be written with `if` statements, never a trailing `cond && grep` chain —
`set -e` kills the run at the call site when such a chain's last command finds
nothing, which for a log scan is the common, successful case
(`report_stale_pairing_codes` in `e2e-emulator.sh`, #993; the sibling `report_relay_link_drops`, #1132,
names each daemon whose relay link dropped for a reason other than the teardown's own kill).

In `test_e2e_emulator_gradle.py`, add new cases for one of these diagnostic steps to the
`unittest.TestCase` that already extracts and runs it (`EmulatorBuildBeforeMintTest` for the failed-test
branch), not to a new subclass of it — subclassing to reuse its extraction helpers also re-runs every
inherited test method under the subclass's name, which silently multiplies the suite (#1132: a first draft
took it from 12 tests to 17 before the new cases moved into the base class).

Splitting a Gradle build step ahead of the device test task, to keep build time out
of a time-limited window elsewhere in the harness (#993 moved e2e pairing-code
minting after `assembleDebug assembleDebugAndroidTest`, so a slow build no longer
burns the daemon's 15-minute redemption window), is not defeated by the test
task's own `--rerun` (`PYRY_FORCE_TEST_RUN=1`): that flag reruns only the test task
itself, not its dependencies, so the compile and package tasks the split build
already ran still report `UP-TO-DATE` in the test task's `--console=plain` output.

The Android gate must search only the report path selected by `DEVICE`:
`connected/debug` for `connected`, otherwise `managedDevice/debug/<DEVICE>`, under
`app/build/outputs/androidTest-results`. Freshness alone is insufficient: a fresh
report from another profile or execution path can falsely satisfy a broad search,
while searching only managed reports misses a successful connected run.
`test_android_test_gate.py` exercises both paths with fresh wrong-path reports,
missing or stale selected reports, failed XML and a failing process despite passing
XML. Preserve path selection and process-status checks together.

The host-wide device hold (#1071) is one kernel `flock` per process, taken on a
descriptor `main()` opens itself and releases when it returns — not a
process-lifetime descriptor threaded through the module. A second `flock` from
the same process on a fresh descriptor still conflicts with the first, so a test
that calls `main()` more than once must let each call finish (and so release)
before the next; nothing in the API stops a test from taking the lock twice and
blocking on itself. Every test that drives `main()` also points
`ANDROID_USER_HOME` at its own temp directory first: without that, the test
takes the real lock file beside the host's actual `~/.android/avd/gradle-managed`,
and a real gate run elsewhere on the host would block the test for the full wait
bound instead of failing fast. `DeviceHoldTest` proves the hold against a real
second process holding the lock, not a mock — including a SIGKILLed holder
releasing immediately and a holder that finishes mid-wait.
