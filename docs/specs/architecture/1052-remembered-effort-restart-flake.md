# #1052 — Prove the relay outbox fix for the live remembered-effort flake

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_rememberedEffort_recalledAfterRestartIntoFreshChatAndChannel` — the test the live gate must pass unchanged. It is an always-on `@Test` on `main`, with no `@Ignore`. Neither the test nor `THREAD_TIMEOUT_MS` (30 s) is edited.
- pyrycode/pyrycode-relay#154 (closed 2026-09-25T05:29Z) and its PR #155 — the fix: each phone's outbox is bounded by bytes instead of 16 frames, so the daemon's connect-time burst no longer closes a new phone connection about 40 ms after the handshake.
- pyrycode/pyrycode-relay release `v0.1.3`, published 2026-09-25T05:58Z. Its commits since `v0.1.2` are exactly #154's spec, fix, docs and merge.
- pyrycode/pyrycode-relay#156 (closed 2026-09-26T07:29Z) — the operator's deploy of that release, the blocker that dispatched this run.
- `docs/specs/architecture/1066-live-peer-attachment-flake.md` — the sibling flake with the same cause, handled the same way.

## Design source

N/A — no UI change; this ticket proves a sibling-repository fix in the live gate.

## Change

No source change. The ticket's finding comment records the cause: after step 3's restart, the relay closed each new phone connection about 40 ms after its handshake, for 34–61 s. Step 4's 30 s footer wait then saw no session settings, or the first request died with `connection torn down before reply` from `RelayRequests.failAllPending`. The effort recall itself worked in every run once a connection held.

At 2026-09-26T07:30:34Z the production relay's `/healthz` reported `"version":"v0.1.3"` with `uptime_seconds` 92014, so the fix has been live since about 2026-09-25T05:57Z. Every recorded flake on this ticket predates that. The last one, on the #1017 gate, ran from 05:40Z to 05:51Z against `v0.1.2`, and its `daemon.log` showed the 1001 fast-close pattern.

## Testing strategy

The proof is the dispatcher's post-verifier real-Claude gate (`needs-real-claude`). It runs `interactiveTurn_rememberedEffort_recalledAfterRestartIntoFreshChatAndChannel` unchanged against the fixed relay. No timeout is raised and no retry is added, as the ticket requires. If the test fails again, check the gate's `daemon.log` for `v2 handshake accept` followed within about 50 ms by `v2 peer close teardown close_code=1001`. Without that pattern the cause is new, and its evidence goes on this ticket.

## Documentation handoff

The ticket names none.
