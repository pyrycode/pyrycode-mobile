# #1078 — Prove the relay outbox fix for the live interrupted-retrieval flake

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_interruptedRetrieval_retryLoadsThePeersFile` — the test the live gate must pass unchanged. It reached `main` when PR #1045 (#1017) merged on 2026-09-25T06:54Z. Neither the test, its single Retry tap, nor `REPLY_TIMEOUT_MS` is edited.
- pyrycode/pyrycode-relay#154 and its fix — each phone's outbox is bounded by bytes instead of 16 frames, so the daemon's connect-time burst no longer closes a new phone connection about 30 ms after the handshake.
- pyrycode/pyrycode-relay#156 (closed 2026-09-26T07:29Z) — the deploy: v0.1.3, Fly release 14, live since 2026-09-25T05:56Z.
- `docs/specs/architecture/1066-live-peer-attachment-flake.md` — the sibling flake with the same cause, handled the same way.

## Design source

N/A — no UI change; this ticket proves a sibling-repository fix in the live gate.

## Change

No source change. The ticket's finding comment traces the failure to the relay closing the phone's two connections after the test's deliberate cut, each 32–33 ms after its handshake with `close_code=1001`. `awaitConnected()` returned on the first of them, so the single Retry tap sent the retrieval onto a connection that died before the request reached the daemon, and the row never became ready within the 90 s wait. The failed-with-Retry state is the designed result of a dropped retrieval, so the mobile code is correct as it stands.

Both blockers are closed: #1017 merged, and pyrycode/pyrycode-relay#156 deployed #154's fix. At 2026-09-26 during this run, the production relay's `/healthz` reported `"version":"v0.1.3"` with `uptime_seconds` 93181. The only recorded flake, on the #1017 gate from 05:40Z to 05:51Z on 2026-09-25, ran against v0.1.2.

## Testing strategy

The proof is the dispatcher's post-verifier real-Claude gate (`needs-real-claude`). It runs `interactiveTurn_interruptedRetrieval_retryLoadsThePeersFile` unchanged against the fixed relay. No timeout is raised and no Retry tap is added, as the ticket requires. If the test fails again, read the gate's kept `daemon.log` for the phone's connections (`device_name="Android ATD built for arm64"`) logging `v2 handshake accept` and then `v2 peer close teardown close_code=1001` about 30–45 ms later, around the Retry. Without that chain the cause is new, and its evidence goes on this ticket.

No Gradle task runs in this build: nothing under `app/` or the build configuration changes.

## Documentation handoff

The ticket names none.
