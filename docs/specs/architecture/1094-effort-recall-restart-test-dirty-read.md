# #1094 — `aSuccessfulTap_survivesAnAppRestart`: the wait misses DataStore's emission

## Files read

- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelEffortRecallTest.kt` → `aSuccessfulTap_survivesAnAppRestart`: the only file this ticket changes.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `onEffortSelected`, `sendSessionSettings`: the tap's write path. The write runs on `viewModelScope`, which is the unconfined test `Main`, so `setSessionSettings` is called before the tap returns and `effortRecall.remember` follows on the same coroutine.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/EffortRecall.kt` → `EffortRecall.remember`, `asRememberedEffortStore`: `remember` returns only after `AppPreferences.setRememberedEffort` returns.
- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt` → `setRememberedEffort`, `rememberedEffort`: `edit` returns once DataStore has committed the file.
- DataStore 1.1.7 sources (`datastore-core-android-1.1.7-sources.jar`) → `DataStoreImpl.data`, `readDataAndUpdateCache`, `readDataOrHandleCorruption`, `writeData`; `DataStoreInMemoryCache.tryUpdate`; `SingleProcessCoordinator`; `FileStorage.writeScope`.
- `docs/knowledge/features/development-verification.md` § Test scheduling and harnesses: the paragraph on `first { predicate }` in `withTimeout` against a live DataStore. It describes this ticket's mechanism.

## Design source

N/A: test-only, not UI-visible.

## Cause

The test's wait `prefs1.rememberedEffort.first { it == "max" }` starts while the tap's DataStore write is in progress. DataStore 1.1.7 can then tag a stale read with the new version and drop the real update:

1. The writer (`transformAndWrite`, holding the coordinator mutex) calls `writeData`. That increments the version to 1 **before** it writes the scratch file, fsyncs it and renames it. Only after that does it put `Data("max", v1)` in the in-memory cache.
2. The test's collector enters `data`, `readState(requireLock = false)`, then `readDataAndUpdateCache`. It sees the cached `Data(v0)` but the coordinator version 1, so the cache looks stale. `tryLock` fails because the writer holds the mutex. `readDataOrHandleCorruption(hasWriteFileLock = false)` reads the file without the lock, gets the pre-rename content (no key, so `null`), and labels it `preLockVersion = 1`. The lock was not acquired, so the cache is not updated.
3. `data` emits `null` and then collects the cache with `dropWhile { it is Data && it.version <= startState.version }`. The writer's `Data("max", v1)` has version 1 ≤ 1, so it is dropped. No later write occurs, so `first { it == "max" }` waits until the timeout, however long it is.

The window is the fsync plus the rename, so under load (lint running alongside) the collector's first read lands in it more often.

**Reproduction on current `main`:** the test body ran 300 times in a loop, each run with a fresh file, store and view model, and a 1 s `withTimeoutOrNull` wait. Without extra load, 14 of 300 waits missed. In every miss `repo.calls` held the tap's write, so the tap was not skipped, and a fresh `rememberedEffort.first()` straight after the miss read `max`. The write had succeeded; only the wait missed it.

## Change

Stop reading `prefs1` while the tap is writing. Wrap `prefs1.asRememberedEffortStore()` in a test-local `RememberedEffortStore` that delegates `read` and `remember` and completes a `CompletableDeferred` once the delegated `remember` returns, that is once DataStore has committed the file. The test awaits that signal in place of `rememberedEffort.first { it == "max" }`. It keeps the existing `withContext(Dispatchers.Default) { withTimeout(5_000) { … } }` so the wait stays bounded in real time. The timeout is not lengthened and nothing is retried.

After the tap, also assert that `repo.calls` holds `SetSessionSettingsPayloadDto(SESSION, effort = "max")`. A skipped tap (`run_config_write_skipped`) then fails at once with a clear message, not as a timeout.

The restart half is unchanged. It cancels `scope1`, joins `job1`, opens a second DataStore on the same file and asserts that its `first()` reads `max`. That is still the proof that the tap's value is read back by a fresh DataStore. The second store has no concurrent writer, so its plain `first()` is not exposed to the race.

No production code changes. The race is DataStore's documented dirty-read behaviour. Production code never waits on a predicate against a concurrent write.

## Testing strategy

- The changed test itself, run through `./gradlew testDebugUnitTest --tests '*ThreadViewModelEffortRecallTest'`.
- Load proof, not committed: the same 300-iteration scratch loop driven through the new wait, with 0 misses expected, compared with 14 of 300 before the fix. The results are recorded on the ticket and in the PR.

## Documentation handoff

None: the ticket names no documentation requirement. The existing § Test scheduling and harnesses paragraph already describes the mechanism. The PR's Lessons learned gives the documentation stage the DataStore specifics (version bumped before the file write, stale read tagged with the new version) if it wants to add them.
