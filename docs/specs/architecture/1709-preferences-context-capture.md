# #1709: Capture preferences Context before deferred file initialization

## Files read

- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt`: the `appModule` DataStore binding defers its Koin lookup; the `MessageTrail` binding already captures Context eagerly.
- `app/src/test/java/de/pyryco/mobile/di/AppPreferencesTeardownTest.kt` on `origin/feature/1694`: `resolvedDataStoreDoesNotLookUpContextInAClosedGraph` controls deferred initialization after isolated graph teardown; retain it here without `@Ignore`.
- `app/src/test/java/de/pyryco/mobile/data/preferences/AppPreferencesTest.kt`: preference defaults, round trips and restart persistence.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/E2eTestApplication.kt`: `rebuildGraph` carries the resolved DataStore into the replacement graph.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/PeerIdentityLifecycleTest.kt`: `sequentialPeersRetainIdentityAfterCloseAndAppGraphRebuild` asserts DataStore identity survives graph rebuilds.
- `app/src/androidTest/java/de/pyryco/mobile/di/RepositoryBindingInstrumentedTest.kt`: `ordinaryInstrumentation_explicitlyBindsFakeRepository` verifies repository mode after lifecycle checks.
- `docs/knowledge/features/app-preferences.md`: the `app_prefs` filename is a storage contract; DataStore owns its separate I/O scope.
- `docs/knowledge/features/dependency-injection.md`: use the real binding to verify deferred dependencies and preserve existing teardown ownership.

## Change

Resolve `androidContext()` inside the DataStore singleton factory and capture that Context in `produceFile`, matching `MessageTrail`. File initialization stays deferred on DataStore's existing I/O scope, but no longer consults Koin after graph closure. Keep `app_prefs`, the singleton, and e2e DataStore carry-over unchanged. No new types, state, signatures, failure branches or logging events are needed.

In-flight overlap: #1694 adds the regression test but depends on this repair; copy only that test unignored, without importing its unrelated harness changes. There are no overlapping production edits.

## Testing strategy

Run the retained teardown regression unignored before implementation and require its `ClosedScopeException` failure, then rerun it alongside `AppPreferencesTest` and `ConversationRepositoryBindingTest` after the fix. Run `testDebugUnitTest` once for the ticket's explicit full-unit acceptance criterion, plus lint, assembleDebug and forced spotlessCheck after formatting. Run the existing managed-device `PeerIdentityLifecycleTest` and `RepositoryBindingInstrumentedTest` together, and the scripted reconnect scenario for graph/harness continuity. These existing device tests require Android lifecycle owners and on-device storage; add no device-only tests or live scenarios because this repair has no operator-facing behavior.
