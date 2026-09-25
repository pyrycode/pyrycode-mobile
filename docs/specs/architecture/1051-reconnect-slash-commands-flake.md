# #1051 — flaky live test: interactiveTurn_reconnect_slashCommandsAndCompactStillWork

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_reconnect_slashCommandsAndCompactStillWork`, step 2's comment above the `firstOnLive` read of `observeSlashCommandMenu` — the only line this ticket changes.
- pyrycode/pyrycode-relay#154 (closed 2026-09-25, PR #155) — replaced the 16-frame per-phone outbox with a 4 MiB byte budget (`phoneOutboxBudget`), so the daemon's connect-time reconcile burst no longer overflows it and closes the phone's fresh connection.

## Design source

N/A — test-comment change, no UI.

## Change

Step 2's comment currently attributes the reconnect's dropped-and-redialled connection to #1029 and #1039. The cause was found on this ticket: the relay's 16-frame per-phone outbox overflowed on the daemon's connect-time reconcile burst (pyrycode/pyrycode-relay#154). The comment is reworded to name pyrycode/pyrycode-relay#154 as the cause. The `firstOnLive` read that follows the redial stays, and no timeout (`THREAD_TIMEOUT_MS` or any other) changes. Nothing else moves: no production code, no helper, no new test.

## Testing strategy

No new logic, so no new proof in this repo. `./gradlew compileDebugAndroidTestKotlin` confirms the file still compiles. The acceptance proof is the dispatcher's post-verifier real-Claude live gate passing this test with pyrycode/pyrycode-relay#154's fix deployed to the production relay. Deployment is an operator step outside this repo; if the gate fails, check the run's `daemon.log` for `v2 handshake accept` followed within ~50 ms by `v2 peer close teardown close_code=1001` — present means the fix is not deployed, absent means a new cause to record on the ticket rather than a timeout to widen.

## Documentation handoff

None named by the ticket.
