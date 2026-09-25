# #1060 — live push-across-reconnect flake: prove the relay fix in the live gate

Short plan: the change is a proof run, with no source change.

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_backgroundPrompt_pushPostsExactlyOneAlertAcrossReconnect` — the flaking scenario; `REDIAL_WAIT_MS` and `THREAD_TIMEOUT_MS` in its companion stay unchanged, as the ticket requires.
- pyrycode/pyrycode-relay#154 and its PR #155 — the fix: the phone outbox is bounded by bytes (`phoneOutboxBudget`, 4 MiB) instead of 16 frames, so the daemon's connect-time reconcile burst no longer overflows it and closes the phone's redial with 1001.

## Design source

N/A — test-infrastructure ticket with no UI change.

## Change

None in this repository. The cause, recorded in the ticket's finding comment, is the relay closing each phone redial 33–46 ms after its handshake, which lets `callOnLive` outwait `REDIAL_WAIT_MS` on the doubling backoff. The fix merged in pyrycode/pyrycode-relay#155 at 2026-09-25T05:29Z. Release `v0.1.3` points at exactly that merge commit (a GitHub compare of the merge commit against `v0.1.3` reports them identical). At 06:06Z the production relay's `/healthz` reported `"version":"v0.1.3"` with an uptime of 553 s, so the fix has run in production since about 05:57Z. The latest flake recorded on the ticket, the #1017 gate at 05:51Z, ran before that deploy. The deploy ticket pyrycode/pyrycode-relay#156 was still open at 06:06Z, but the deploy it tracks is already live.

Nothing else moves: no timeout or redial wait is raised and no retry is added.

## Testing strategy

The proof is the dispatcher's post-verifier live gate for this `needs-real-claude` ticket, which runs the unchanged scenario against the fixed production relay. No new assertion is needed, because the scenario already fails on the fault. If it flakes again, check the kept `daemon.log` for `v2 handshake accept` followed by `v2 peer close teardown close_code=1001` within about 50 ms. With that pattern present, the relay fix did not hold. Without it, the cause is something else, and the new evidence goes on this ticket.

## Documentation handoff

The ticket names none. Pending for the documentation stage: nothing required.
