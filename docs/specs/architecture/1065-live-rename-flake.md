# #1065 — Prove the relay outbox fix for the live rename flake

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_renameConversation_relabelsTopBarAndListRow` and the companion's `THREAD_TIMEOUT_MS` — the test the live gate must pass unchanged. Neither is edited.
- pyrycode/pyrycode-relay#154 (closed) and its PR #155 — the fix: each phone's outbox is bounded by bytes (`phoneOutboxBudget`, 4 MiB) instead of 16 frames, so the daemon's connect-time burst no longer closes a new phone connection about 40 ms after the handshake.
- pyrycode/pyrycode-relay release `v0.1.3` — identical to `a306fd3`, the merge of PR #155.

## Design source

N/A — no UI change; this ticket proves a sibling-repository fix in the live gate.

## Change

No source change. The ticket's cause, recorded in its finding comment, is the relay closing each new phone connection 31–43 ms after its handshake, so the test's `create_conversation` never reached the daemon and step 3's 30 s thread wait timed out. That cause is fixed in pyrycode/pyrycode-relay#154. At 2026-09-25T06:08Z the production relay's `/healthz` reported `"version":"v0.1.3"` with `uptime_seconds` 661, so the fix has been live since about 05:57Z. The deploy ticket pyrycode/pyrycode-relay#156 was still open at that time; the `/healthz` version is the evidence. `THREAD_TIMEOUT_MS` stays at 30 s and no retry is added, per the ticket.

## Testing strategy

The proof is the dispatcher's post-verifier real-Claude gate (`needs-real-claude`), which runs `interactiveTurn_renameConversation_relabelsTopBarAndListRow` unchanged against the fixed relay. If it fails again, check the gate's `daemon.log` for `v2 handshake accept` followed within about 50 ms by `v2 peer close teardown close_code=1001`. Without that pattern the cause is new and its evidence goes on this ticket.

## Documentation handoff

The ticket names none.
