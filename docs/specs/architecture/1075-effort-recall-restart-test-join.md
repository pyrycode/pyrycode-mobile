# #1075 — `aSuccessfulTap_survivesAnAppRestart`: wait for the first DataStore to close

Test-only fix. No production files change.

## Files read

- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelEffortRecallTest.kt` → `aSuccessfulTap_survivesAnAppRestart` — the test being fixed; `collectedVm`, `ScriptedRepo.setSessionSettings` — how the tap reaches the store.
- `app/src/test/java/de/pyryco/mobile/data/preferences/AppPreferencesTest.kt` → `rememberedEffort_survivesProcessDeath` — the same restart done correctly: keep the first scope's `Job`, then `scope1.cancel(); job1.join()` before opening the second store.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/EffortRecall.kt` → `EffortRecall.remember`, `asRememberedEffortStore` — the tap's acknowledged write goes straight to `AppPreferences.setRememberedEffort`.
- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt` → `rememberedEffort`, `setRememberedEffort` — plain `DataStore.edit` / `data.map`.

## Design source

N/A — test-only change, nothing visible.

## Change

The test cancels `scope1` and immediately calls `PreferenceDataStoreFactory.create` on the same file. DataStore unregisters the file from its active-file set only when the first store's scope job *completes*, and `cancel()` does not wait for that, so the second store's first read throws `IllegalStateException: There are multiple DataStores active for the same file` (reproduced: fails every time when the class runs alone). The fix mirrors `rememberedEffort_survivesProcessDeath`: hold the first scope's `Job` in a `job1` val and `job1.join()` after `scope1.cancel()`. The assertion is unchanged: the value written by the view model's tap is read back by a freshly created DataStore on the same file. No timeout is lengthened, no retry added, nothing ignored.

The intermittent `TimeoutCancellationException` from the 5s `withTimeout` in the full `./gradlew check` is not yet explained by this race (the timeout guards the wait *before* the restart). Resolution is recorded under Open questions after the verification runs.

## Testing strategy

The test itself is the proof. `./gradlew :app:testDebugUnitTest --tests 'de.pyryco.mobile.ui.conversations.thread.ThreadViewModelEffortRecallTest' --rerun` must pass 10 consecutive runs (it fails 1 of 1 before the fix), then one full `./gradlew check` per the ticket's acceptance criterion.

## Open questions

- Does the join also account for the full-check timeout? If a full `./gradlew check` still times out, find the cause rather than assume it.
