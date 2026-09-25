# #1053 — flaky live test: interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → the KDoc of `interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes`, its paragraph on running last — the only lines this ticket changes.
- pyrycode/pyrycode-relay#154 (closed 2026-09-25) — replaced the relay's 16-frame per-phone outbox, which the daemon's connect-time reconcile burst overflowed, closing every fresh phone and peer connection 35–65 ms after its handshake.
- `docs/specs/architecture/1051-reconnect-slash-commands-flake.md` — the sibling with the same cause and the same one-comment shape.

In flight on the same file: #1017 and #1051 edit other scenarios; neither touches this KDoc.

## Design source

N/A — test-comment change, no UI.

## Change

The KDoc says peers opened on the first daemon stopped carrying frames in two live runs, and that the test runs last "until that is explained". The cause is now explained on this ticket: the relay's per-phone outbox overflowed on the daemon's connect-time reconcile burst once the daemon held 15–23 sessions (pyrycode/pyrycode-relay#154). The sentence is reworded to name that cause. The test's name, and so its last place in JUnit's name-hash order, stay the same. No timeout changes and no production code moves.

## Testing strategy

No new logic, so no new proof in this repo. `./gradlew compileDebugAndroidTestKotlin` confirms the file still compiles. The acceptance proof is the dispatcher's post-verifier real-Claude live gate passing this test with pyrycode/pyrycode-relay#154's fix deployed to the production relay. If it fails, check the run's `daemon.log` for `v2 handshake accept` followed within ~50 ms by `v2 peer close teardown`: present means the fix is not deployed; absent means a new cause, to record on the ticket rather than widen a timeout.

## Documentation handoff

None named by the ticket.
