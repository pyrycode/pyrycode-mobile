# #1066 — Prove the relay outbox fix for the live peer-attachment flake

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload` — the test the live gate must pass unchanged. It is no longer `@Ignore`d on `main` now that #1020 has merged. Neither the test nor any timeout is edited.
- pyrycode/pyrycode-relay#154 (closed 05:29Z) and its PR #155 — the fix: each phone's outbox is bounded by bytes (`phoneOutboxBudget`, 4 MiB) instead of 16 frames, so the daemon's connect-time burst no longer closes a new phone connection 31–43 ms after the handshake.
- pyrycode/pyrycode-relay release `v0.1.3`, published 2026-09-25T05:58Z from `main` after the merge of PR #155.
- #1065's plan on `feature/1065` — the sibling flake with the same cause, handled the same way.

## Design source

N/A — no UI change; this ticket proves a sibling-repository fix in the live gate.

## Change

No source change. The ticket's cause, recorded in its finding comment, is the relay closing each new phone connection shortly after its handshake, so step 1's `create_conversation` failed with `connection torn down before reply` from `RelayRequests.failAllPending`. Both blockers are closed: pyrycode/pyrycode-relay#154 fixes the cause, and #1020 removed the test's `@Ignore`.

At 2026-09-25T06:09:44Z the production relay's `/healthz` reported `"version":"v0.1.3"` with `uptime_seconds` 766, so the fix has been live since about 05:57Z. Every recorded flake predates that. The last one, on the #1017 gate, ran from 05:40Z to 05:51Z. That run's kept log holds only the JUnit result, so its `daemon.log` could not be checked for the 1001 pattern. The gate ran against the relay from before the fix.

## Testing strategy

The proof is the dispatcher's post-verifier real-Claude gate (`needs-real-claude`). It runs `interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload` unchanged against the fixed relay. No timeout is raised and no retry is added, as the ticket requires. If the test fails again, check the gate's `daemon.log` for `v2 handshake accept` followed within about 50 ms by `v2 peer close teardown close_code=1001`. Without that pattern the cause is new, and its evidence goes on this ticket.

## Documentation handoff

The ticket names none.
