# #953 — Deflake `newToken_isPersisted_andDoesNotWake`

## Files read

- `app/src/test/java/de/pyryco/mobile/push/PyryMessagingServiceTest.kt` → `newToken_isPersisted_andDoesNotWake`, `message_onlyWakesTheHosts_andIgnoresEveryField` — the flaky test and its sibling that must stay unchanged.
- `app/src/main/java/de/pyryco/mobile/push/PyryMessagingService.kt` → `PyryMessagingService.onNewToken`, `PushTokenSink` — the sink resolves from Koin per call and already takes a `dispatcher`; no production seam is needed.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → the `app_prefs` DataStore, `AppPreferences` and `PushTokenSink` bindings — the DataStore runs on its default IO scope; `RelayConnectionFactory` captures `AppPreferences.pushToken` at start.
- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt` → `pushToken`, `setPushToken`.
- `app/src/test/java/de/pyryco/mobile/data/preferences/AppPreferencesTest.kt` → DataStore-over-`TemporaryFolder` construction to mirror.
- `app/src/test/java/de/pyryco/mobile/robolectric/RobolectricTestApp.kt` → fresh Koin per test.

## Design source

N/A: test-only change, nothing visible.

## Cause (measured)

Temporary diagnostics in the test (not committed) recorded the write latency, the sink's `push_token_stored` log, a thread dump at the timeout, and a fresh `pushToken.first()` after it. The class was run 6 times idle and then under a CPU load of 30 `yes` processes on 10 cores.

- Idle, in isolation and in a full `testDebugUnitTest`: the token arrives in 27 to 48 ms. In the full run the IO pool is idle, with 13 parked workers and none blocked, so starved IO is ruled out.
- Under load the failure reproduces, at 5 of 5 and then 4 of 6 runs. With the wait raised to 60 s it still times out, so the write is not simply late. In every failing run the sink logged `event=push_token_stored outcome=success`, and a fresh `pushToken.first()` after the timeout returned `fcm-rotated`. No uncaught exception was recorded, which rules out a swallowed exception.

The write therefore lands. What fails is the test's waiting collector. `first { it == "fcm-rotated" }` subscribes to the new `app_prefs` DataStore while the sink's first write to it is still running, reads `null`, and never receives the update: it stays parked in the DataStore's in-memory `StateFlow`. That is a lost emission inside DataStore 1.1.7 when a collector's first read overlaps the first write. Load makes the overlap likely by slowing both sides. The 5 s wall-clock wait only turned the lost emission into a timeout.

The same overlap can in principle happen in production: a `pushToken` collector starting while a rotation is written. That is outside this ticket and is filed as a separate bug (linked in the PR). It does not change the fix here.

## Change

`newToken_isPersisted_andDoesNotWake` becomes a `runTest` test that owns every coroutine the write uses and reads only after the write has finished.

- It builds an `AppPreferences` over a `PreferenceDataStoreFactory.create` DataStore whose scope is the test's `backgroundScope`, with its file in a `TemporaryFolder`. It builds a `PushTokenSink` over those preferences on `StandardTestDispatcher(testScheduler)`. It loads the sink into Koin with `loadKoinModules`, the same override the class already uses for the driver.
- It calls `service.onNewToken("fcm-rotated")` and then `advanceUntilIdle()`. That runs the sink's launch and the DataStore's whole write on the test thread, to completion.
- It then asserts `preferences.pushToken.first()` with no predicate equals `"fcm-rotated"`, and that `controller.calls` is empty. A plain `first()` never waits for a later value: an unfinished write fails the test instead of racing a clock. The collector starts after the write, so no emission can be lost.
- It disposes the sink at the end. `runTest` cancels `backgroundScope`, which closes the DataStore.

The sibling test, `setUp`, the production `PushTokenSink` and the `app_prefs` binding stay unchanged. The service still resolves the sink through Koin, so the test still covers service → sink → `AppPreferences`. It no longer uses the `app_prefs` file location, which is not part of what the test checks.

## Testing strategy

The changed test is its own proof.

- RED: under the load harness above, the current test fails (reproduced 5 of 5).
- GREEN: the new test passes under the same load. The class passes 20 consecutive `--rerun` runs. A full `./gradlew check` passes.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. `PyryMessagingService.onNewToken` still takes the token only from FCM and passes it only to `PushTokenSink`. The test changes which sink instance Koin returns inside the test and nothing in production.
- [Tokens] No findings. The token stays in the DataStore-backed `AppPreferences`. The test's DataStore lives in a JUnit `TemporaryFolder` and holds a fixed fake token. `PushTokenSink` still logs only `outcome=`, never the token.
- [File / storage] No findings. The test file path comes from `TemporaryFolder`, and nothing untrusted reaches a path.
- [Android attack surface] No findings. The service manifest entry and the push wake path are unchanged, and the sibling test that holds "a message only wakes" is unchanged.
- [Crypto] Not applicable. No keys, randomness or comparisons are touched.
- [Network & I/O] Not applicable. No socket or relay code is touched.
- [Logs] No findings. The temporary diagnostics that wrote thread dumps and log lines to a scratch file are removed before commit, and none of them wrote the token.
- [Concurrency] No findings for this change. The production sink keeps its app-lifetime `Dispatchers.IO` scope, so a write still survives the service's destruction. The test sink's scope is disposed and the DataStore's scope is `backgroundScope`, so nothing leaks into later tests. OUT OF SCOPE: a `pushToken` collector in production can in principle miss a rotation written while its first read is running. It is filed as its own bug ticket, linked in the PR.
- [Threat model] No new threat. The push-wake trust rule is still held by the unchanged sibling test.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-24
