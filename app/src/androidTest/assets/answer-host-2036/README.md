# Answer-host Keystore ownership evidence (#2036)

All runs below used managed `pixel2Api33Atd`, Android 13/API 33, AOSP ATD, and the existing host-wide device hold. The repair preserves real Keystore storage and strict failures; it neither resets app saved state nor ignores the regression. No real-Claude turn was used. Raw artifacts remain under `/tmp/builder-2036/<run>/`; this directory retains sanitized evidence durably with the PR.

## Established cause

The private-DataStore `KeystorePairedServerStoreTest` deleted the UID-wide production alias `pyrycode.paired_server_wrap` in lost-key tests and teardown. App DataStore blobs remained, including encrypted empty collections. A subsequent app read could return a graceful empty list, but strict mutation still had to authenticate the retained blob. In the fresh sweep, cleanup logcat orders failed read, failed seed save, then failed teardown removal. Removal masked the original failed save.

The controlled `storageTestKeyLossAndTeardownPreserveAppPairings` probe seeded two named, ordered records through the real app store, invoked the existing lost-key method and its actual teardown, then required a successful app snapshot. Before repair this failed with `paired server read failed: keystore`, with failed cleanup removal suppressed. After giving that test its own real Keystore alias, the unchanged probe passed and drove the actual answer-host fixture plus its second activity. The separate failed-save probe established and repaired primary-error masking and attempts to remove IDs that had never been saved.

Production construction retains its fixed alias. Only the internal storage-test construction accepts a fixture-owned alias; encryption, strict mutations and credential redaction remain unchanged. The answer-host fixture uses strict snapshots and removes only its persisted random IDs. Named/ordered pre-existing entries survive on success, failed body and failed save.

## Counted runs

Executed excludes skipped cases. Passed excludes failures and errors. Every focused selected method executed; no focused method was skipped or missing.

| Run | Revision | Exit | Executed | Passed | Failed | Errors | Skipped |
| --- | --- | --- | --- | --- | --- | --- | --- |
| [before-isolated](before-isolated.json) | `292c52bca` | 0 | 1 | 1 | 0 | 0 | 0 |
| [before-classes](before-classes.json) | `75a10c4a4` (plan only) | 0 | 2 | 2 | 0 | 0 | 0 |
| [before-in-depth](before-in-depth.json) | `75a10c4a4` (plan only) | 1 | 1573 | 1572 | 1 | 0 | 2 |
| [red-key-custody](red-key-custody.json) | `75a10c4a4` + test-only patch | 1 | 1 | 0 | 1 | 0 | 0 |
| [red-failed-save](red-failed-save.json) | same test-only patch | 1 | 1 | 0 | 1 | 0 | 0 |
| [green-key-custody](green-key-custody.json) | `75a10c4a4` + repair patch | 0 | 1 | 1 | 0 | 0 | 0 |
| [green-failed-save](green-failed-save.json) | same repair patch | 0 | 1 | 1 | 0 | 0 | 0 |
| [after-isolated](after-isolated.json) | `c69770073` | 0 | 1 | 1 | 0 | 0 | 0 |
| [after-classes](after-classes.json) | `c69770073` | 0 | 5 | 5 | 0 | 0 | 0 |
| [after-storage-class](after-storage-class.json) | `c69770073` | 0 | 17 | 17 | 0 | 0 | 0 |
| [after-in-depth](after-in-depth.json) | `c69770073` | 0 | 1576 | 1576 | 0 | 0 | 2 |

The retained #2027 baseline at `38332325152c20432f27e5c1c0ad1cc923714c5a` had 1573 executed, 1572 passed, 1 failed, 0 errors and 2 skipped, exit 1. The fresh before sweep reproduced those counts and the same sole cleanup failure. Its isolated/class baselines passed: sweep ordering, rather than the target guard alone, exposed the key contamination.

Both acceptance methods passed in `after-classes-selected.xml`: `AnswerHostSetupTest.offlinePrecedingHostDoesNotBlockAnswerHostPairing` and `AnswerHostSetupCleanupTest.targetedPairingAfterFixtureTeardownKeepsItsHostGuard`. Both also executed and passed in `after-in-depth-selected.xml`. The three additional after-sweep cases are this ticket's regression methods. The before/after testcase comparison in [comparison.json](comparison.json) finds no missing case, exactly those three additions and no unrelated failure/error. The unchanged skips are `RenameDialogCaptureTest.renameAtFigmaViewport` and `ComposerPasteTest.pastingAnImageUri_reachesTheAttachmentPath_andLeavesTheDraftEmpty`.

## Commands and artifact format

Each `<run>.json` contains the exact command, full tested revision, exit status, aggregate counts, named method outcomes and unrelated failures. Red/early-green runs also carry the starting diff SHA-256 and `<run>-tested.patch.gz`, so an uncommitted test or repair run is reproducible. All later runs use the clean committed implementation.

Focused selections use:

```sh
./gradlew :app:pixel2Api33AtdDebugAndroidTest --rerun \
  '-Pandroid.testInstrumentationRunnerArguments.class=<selection from run JSON>' \
  -Pandroid.testInstrumentationRunnerArguments.notPackage=de.pyryco.mobile.e2e \
  -Pandroid.testInstrumentationRunnerArguments.disableAnimations=true --console=plain
```

Both in-depth runs use the retained baseline's exact selection:

```sh
./gradlew :app:pixel2Api33AtdDebugAndroidTest --rerun \
  -Pandroid.experimental.androidTest.numManagedDeviceShards=2 \
  -Pandroid.testInstrumentationRunnerArguments.notPackage=de.pyryco.mobile.e2e \
  -Pandroid.testInstrumentationRunnerArguments.disableAnimations=true --console=plain
```

`<run>-all.xml.gz` preserves every testcase's class, method, duration and outcome, plus suite timestamps/counts. Logs, properties and arbitrary error details are removed. `<run>-selected.xml` includes the answer-host methods and every failure/error/skip, with counts recalculated for that subset and original totals in `source-tests`. `<run>-hashes.json` fingerprints original XML. `<run>-selected.log` uses an allowlist of store operation/status/static code, probe stage and pairing lifecycle events from the relevant answer-host and lost-key logcats. Device logcat uses local time (UTC+3); XML timestamps use UTC. Tokens, key material, blobs, names and provider/parser details are omitted.

Normal configured UI/scripted gates remain dispatcher-owned and must run unchanged. The UI evidence must explicitly confirm both acceptance methods passed with nonzero counts. This builder-owned in-depth comparison does not replace that gate. Documentation handoff is recorded in the plan and PR.
