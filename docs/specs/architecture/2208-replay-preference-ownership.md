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
| Application graph rebuild | Store identity checks in the isolation regression and the original recorded restart scenarios in the complete profile. |
| Seeded test returns normally or throws an assertion after replay cleanup | `seededStateCleanupRestoresRegistryAfterSuccess` and `seededStateCleanupRestoresRegistryAfterAssertionFailure` wait for the synthetic host in the ordinary registry, then verify final cleanup releases its connection and restores exactly the original persisted pairings, preferences and registry hosts. |

## Error handling

Preserve the original replay failure and cleanup suppression behavior. Failed replay must still restore arguments, preferences and pairings and rebuild the ordinary graph. No acceptance retries, time-limit increases, scenario skips, fixture changes or relaxed request matching.

## Testing strategy

Reproduce the complete in-depth Linux command on unchanged main. Add a native regression and observe its failure before the fixture fix; real DataStore file ownership, Android Keystore pairings and the instrumentation Application require a device. Run the isolation class, all three named failing methods, and the affected recorded class after repair. Run `AppPreferencesTeardownTest`, focused lint/build/formatting and final pre-verification checks. Obtain a fresh complete two-shard in-depth profile at the pushed candidate; inspect counts, all selected reports and logs. Retain raw evidence privately with hashes and commit a content-free evidence summary under Android test assets. Ordinary UI and scripted-all gates remain dispatcher-owned. Verification scope is Linux.

## Open Questions

- Resolved: the controlled native regression fails because isolated graph closure replaces the application's DataStore. The fresh main sweep reports instrumentation process crashes after duplicate-store exceptions; delete has an empty crash record, not a product assertion failure. The original delete scenario must pass after repair.
- Resolved: the full profile reports all 1692 unique selected XML cases: 1690 executed/passed, zero failures, and two existing skips. All 71 recorded scenarios and the three isolation tests pass. Evidence and count reconciliation are in `app/src/androidTest/assets/datastore-2208/evidence.txt`; no inherited failure was found.

## Documentation handoff

- Pending documentation stage: `docs/knowledge/features/development-verification-gates.md`, Device gate guidance: diagnosed ownership boundary and counted Linux evidence.
- Pending documentation stage: `docs/recorded-tests.md`, replay isolation lifecycle: document the application-owned preference boundary if its documented lifecycle changes.

## Revisions

- 2026-10-17: Confirmed the planned ownership cause with one executed/failed native regression before the fixture change. The fresh dispatcher sweep at `a0c7db693245cce59d67377f672d2bec4c7dca65` reproduces all three failures (113 executed, 110 passed, 3 failed), with explicit instrumentation process-crash diagnostics. The existing after-graph-close production contract remains unchanged. Rebuild coverage uses the isolation regression and recorded restart scenarios, which run in the non-live UI profile, rather than the separately packaged e2e peer test.
- 2026-10-17: The focused six-test selection and complete two-shard Linux profile pass at pushed candidate `7426bb4613ff0a9da79c8580937bc5d00312bc50`. The final raw XML reconciles both shards without duplicate or missing cases; the two pre-existing skips explain the progress counter's apparent overrun. Durable raw evidence and source revisions are recorded in the committed evidence summary.
- 2026-10-17: Verifier finding 1 identifies another ownership boundary in the new seeded regression fixture: restoring its original DataStore snapshot does not notify `ObservablePairedServerStore.revision`, so the ordinary `RelayConnectionRegistry` can retain the synthetic host. `withSeededState` must restore the snapshot and rebuild the ordinary graph on main in `finally`, with no Activity alive. Two deterministic native probes cover normal return and a deliberate assertion failure, establish registry ownership before cleanup, and require exact original host restoration afterwards. Its private callback becomes suspending so the probes can await registry reconciliation. Their own outer `finally` also restores the snapshot and graph to contain an expected pre-fix failure. This adds no production changes, production signatures, exported types or state-machine branches; the complete change remains under the sizing limits. No in-flight branch overlaps the regression file.
- 2026-10-17: Both new probes execute and fail before the cleanup fix: 2 executed, 0 passed, 2 failed, 0 skipped. Each fails because the ordinary registry still owns the synthetic connection after the persisted snapshot is restored. Exact pre-fix source, raw XML and both phone logs are retained with hashes in `rework-controlled-red/` under the ticket's private evidence root.
- 2026-10-17: After the final cleanup rebuild, the focused selection executes and passes all 8 cases: all five isolation methods and the three original failures, with zero failures/skips. Scripted ping also executes and passes both cases, with zero failures/skips and no Claude turns. Raw rework evidence is retained under the ticket's private evidence root and indexed in the committed evidence summary.
- 2026-10-17: The complete Linux profile at pushed repair `3f4703e08` reports all 1694 selected cases: 1692 executed/passed, zero failures/errors and the same two skips. All 71 recorded scenarios and five isolation tests pass. Instrumentation identities/outcomes, both shard XMLs and joined XML match exactly; membership differs from the prior complete profile only by the two new probes. All 1694 phone logs contain zero duplicate-DataStore errors. The separately retained managed task executed zero tests because its cleaned launcher environment omitted the agents path; dispatcher repair `pyrycode/agent-dispatcher#294` is in Backlog with priority low. The documented local gate supplied complete evidence at the same candidate without changing the forwarding wrapper. Documentation handoff includes both Application-owned preferences and the final seeded registry cleanup boundary.
