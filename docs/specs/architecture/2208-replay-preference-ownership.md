# Replay preference ownership (#2208)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/E2eTestApplication.kt`: `onCreate` and `rebuildGraph` compose the process-wide test graph and carry its preferences between rebuilds.
- `app/src/androidTest/java/de/pyryco/mobile/recording/RecordedSuiteNative.kt`: `begin`, `finish` and `restoreOrdinaryTestEnvironment` isolate recorded preferences and pairings.
- `app/src/androidTest/java/de/pyryco/mobile/recording/RecordedSuiteIsolationDeviceTest.kt`: existing unsuccessful replay cleanup regression.
- `app/src/sharedTest/java/de/pyryco/mobile/notifications/NotificationTapNavigationTest.kt`: an ordinary test loads the shared `appModule` into an isolated Koin application and closes it.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/host/UnpairNavigationTest.kt`: the same isolated-module lifetime pattern.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt`: `appModule` is a shared module value; its singleton factories and unclosed DataStore can outlive an isolated graph.
- `app/src/test/java/de/pyryco/mobile/di/AppPreferencesTeardownTest.kt`: resolving preferences before graph closure must still permit later file initialization.
- `docs/knowledge/features/development-verification-gates.md`: complete reports and selected-test counts establish device execution.
- `docs/knowledge/features/app-preferences.md` and `docs/recorded-tests.md`: persistent preferences and strict offline replay boundaries.

## Context

The Linux in-depth profile mixes ordinary isolated Koin tests with recorded scenarios in the same instrumentation process. The original background-agent and footer failures report competing DataStores for `app_prefs`; the delete failure has no diagnostic body. A shared `appModule` singleton factory can be dropped by closing an isolated graph even while its former DataStore remains active in the application graph. Rebuild-time carry-over alone does not protect the initial ordinary graph. Establish this with a controlled regression before repairing the fixture. No production/UI contract changes or decision record are expected.

## Design

Give `E2eTestApplication` ownership of one process-lifetime preferences instance, bound through a fresh override module in both initial graph paths and every rebuild. Closing an ordinary isolated graph must not discard the application's preference factory. Keep the existing `appModule` production behavior and after-graph-close initialization contract unchanged. The application-owned DataStore stays alive until instrumentation process exit, matching its file ownership lifetime.

Extend the native isolation regression to load and close an isolated graph using the real shared `appModule`, then enter replay, clean up, and enter another replay. Assert store identity and readable/writable state across ordinary → replay → ordinary transitions. Seed synthetic preferences and a paired host, verify exact restoration after incomplete replay, and retain a successful replay restoration check using an original recorded scenario. Always restore the regression's own initial state in `finally`.

No signatures or production call sites change. Expected total written work is under 450 lines, zero exported production types, four acceptance criteria, and no new state-machine reject branches. No overlapping feature branch touches the planned files at planning time.

## State and concurrency model

The preferences instance belongs to the test Application, independent of any Koin graph. Its existing DataStore I/O scope lasts for the instrumentation process. Graph changes stay on main with no active Activity, preserving lifecycle observer removal. Replay scopes, reader draining and cancellation retain their current owners and time limits.

## State transitions and identity reuse

| Event | Required coverage |
| --- | --- |
| Ordinary isolated graph closes before replay | `RecordedSuiteIsolationDeviceTest.ordinaryGraphClosurePreservesApplicationPreferences` preserves identity and performs real file I/O. |
| Incomplete replay restores original graph and state | `RecordedSuiteIsolationDeviceTest.incompleteReplayRestoresOrdinaryInstrumentationArgumentsAndGraph` checks arguments, preferences, pairings and fake repository. |
| A second replay reuses the same file and host identities | Repeat the incomplete replay in the isolation regression, with ordinary graph closure between sessions. |
| Successful replay cleanup | Run the three original recorded methods and assert restoration around a successful original ping scenario in the isolation class. |
| Application graph rebuild | Existing `PeerIdentityLifecycleTest` plus identity checks in the isolation regression. |

## Error handling

Preserve the original replay failure and cleanup suppression behavior. Failed replay must still restore arguments, preferences and pairings and rebuild the ordinary graph. No acceptance retries, time-limit increases, scenario skips, fixture changes or relaxed request matching.

## Testing strategy

Reproduce the complete in-depth Linux command on unchanged main. Add a native regression and observe its failure before the fixture fix; real DataStore file ownership, Android Keystore pairings and the instrumentation Application require a device. Run the isolation class, all three named failing methods, and the affected recorded class after repair. Run `AppPreferencesTeardownTest`, focused lint/build/formatting and final pre-verification checks. Obtain a fresh complete two-shard in-depth profile at the pushed candidate; inspect counts, all selected reports and logs. Retain raw evidence privately with hashes and commit a content-free evidence summary under Android test assets. Ordinary UI and scripted-all gates remain dispatcher-owned. Verification scope is Linux.

## Open Questions

- Confirm the shared-module factory invalidation with a controlled failing regression and classify the empty delete failure from fresh native reports.
- Record full-profile inventory, completion and any independently established inherited failures in the PR and evidence summary.

## Documentation handoff

- Pending documentation stage: `docs/knowledge/features/development-verification-gates.md`, Device gate guidance: diagnosed ownership boundary and counted Linux evidence.
- Pending documentation stage: `docs/recorded-tests.md`, replay isolation lifecycle: document the application-owned preference boundary if its documented lifecycle changes.
