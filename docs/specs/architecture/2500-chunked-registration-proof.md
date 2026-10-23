# #2500 — Reliable chunked registration proof

## Files read

- `scripts/test_e2e_daemon_fault.py`: `GenerationReadinessTest.controller` owns the synthetic controller/child; the chunk test starts scanning before child output is guaranteed.
- `scripts/e2e-daemon-fault.py`: `await_relay_ready`, `start`, `refresh` and `evidence` retain generation offsets, cross-read suffixes, bounded waits and liveness qualification.
- `docs/specs/architecture/2137-daemon-generation-readiness.md`: the original readiness contract and generation rejection matrix.
- `docs/knowledge/features/development-verification.md` and its gates/emulator evidence topics: focused builder scope and retained counted evidence.
- `docs/e2e-interactive-stream.md`, “Offline Retry registration evidence”: controlled causation must remain distinct from unavailable historical child evidence.

## Context

One behaviour: make the real subprocess chunk-boundary proof independent of synthetic child startup time, without changing controller readiness. Three acceptance criteria; forecast about 230 written lines including this plan, tests and retained evidence, no exported production types or migrated consumers. The nearest #2137 repair wrote 78 inserted and 16 deleted script lines. No decision record is needed.

The named test passes cold on base `45b86ff05dd71c8904a006c8b8c8a7c6489616c7` (1 executed/passed). A controlled 750 ms child startup delay makes that unchanged test fail (1 executed/failed): start returns 503 after 250 ms, phase `registration_timeout`, child alive, generation 1, zero child-output bytes. The child-start marker identifies the same PID before the timeout. Scanning has no bytes to inspect. Raw evidence is under `/tmp/builder-2500/controlled-startup-red/` and will be retained with the final evidence fixtures.

Historical macOS run `029ebb9efcb37b5357941798675118624034d794fc3c76b24c5d8e3e1bfcda1e`, digest `7154056c9225c6da415e8e6d401b4a5030b074b7165dc96ec419136f4cc80d2d`, remains failed: 218 executed, 217 passed, 1 failed. Its logs contain only the 503 assertion, with no child/controller snapshot; the controlled reproduction establishes a fixture race, not the exact historical scheduling event.

Overlap: #2052 touches the controller and another test class in the same file; this repair stays local to `GenerationReadinessTest` and does not depend on it.

## Design

Keep `await_relay_ready` unchanged. Share the chunk assertion between the named test and a delayed-child regression. The child writes 4090 padding bytes plus the first six token bytes, flushes, and holds the remainder behind a fixture release file. A bounded setup wait observes exactly 4096 bytes before invoking the controller; child startup consumes this setup budget, not the 250 ms readiness deadline.

Require HTTP 503 for the incomplete token with a living, unready child. Release the remainder and wait boundedly for all fixture bytes before requesting readiness. Require HTTP 200, running/ready, and identical PID and generation. The token appears only once, across the scanner's real 4096-byte read boundary. Remove neither the padding nor token-suffix carry requirement. Retain response and controller/child diagnostics on assertion failure.

The added delayed-child regression runs the same proof after a 750 ms child startup delay. First run it with the original unsynchronized assertion and retain the failure, then introduce the setup barrier. A scanner negative control with suffix carry removed must still fail the repaired proof.

## State and concurrency model

No production concurrency changes. The existing serialized HTTP server owns and joins its child. Only the synthetic child waits for a fixture file; controller teardown still terminates it on every path. Setup waits have a five-second monotonic deadline and short polling sleeps; HTTP waits and controller readiness deadlines retain their existing bounds.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| Cold child startup before output | `test_registration_token_across_chunks` |
| Startup exceeds the short registration deadline | `test_registration_token_across_chunks_after_delayed_startup` |
| Incomplete token, then completed token with unchanged identity | Both chunk tests assert 503 then 200, same PID/generation |
| Missing token or repeated timeout | Existing initial-unready and repeated-timeout tests |
| Stopped/exited predecessor, exited child, response-time exit | Existing stopped/exited-generation, early-exit and success-snapshot tests |
| Cleanup after failed proof | Existing fixture `finally` still terminates/joins the controller and owned child |

## Error handling

Setup exhaustion fails with child byte count and controller phase evidence. A partial token remains a readiness timeout, never success. No accepted 503 replaces the final 200 requirement. No retry loops wrap failed readiness assertions; the partial-token and completed-token requests prove different states.

## Testing strategy

Linux only. Retain command, revision, raw output and executed/passed/failed/error/skipped counts for the cold named test, original controlled failure, added delayed regression red/green, scanner negative control and final entire `GenerationReadinessTest` class. Retain raw controller/child evidence and hashes in `scripts/e2e-fixtures/readiness-2500/`; scratch runners remain outside the worktree. Run affected script-file coverage if shared fixture changes require it. Complete scripts discovery belongs to the dispatcher, including passage of the named method; it stays pending in the PR. No wider investigation is needed if the controlled startup experiment and scanner negative answer the local question.

Run required formatting, lint, assembly and final `scripts/pre-verify.py --gradle` gates after merging main. No UI, device, Figma or live evidence applies.

## Open Questions

- Resolved: the observed controlled timeout occurs before any child output, distinguishing startup from scanning; preserve uncertainty about the historical macOS attempt.

## Revisions

- 2026-10-23 documentation acceptance: the accepted verifier at `46de5b203c83cea6d458bffc679dd080e75e1875` confirms the complete Linux scripts gate passed 219/219, zero failures/errors/skips, including both chunk-token methods. This resolves the dispatcher acceptance left pending above and in the builder handoff. See [counted results and retained evidence](../../e2e-interactive-stream.md#chunked-registration-proof-2500); the original macOS failure remains failed and its exact timing unknown.
- 2026-10-23: The implemented proof follows the design. Both the original delayed proof and added regression failed with zero child bytes; the repaired class passes 12/12. Removing suffix carry in a temporary controller copy fails with all 4113 bytes present, confirming scanner coverage. Raw evidence, source snapshots and a verified manifest are retained in `scripts/e2e-fixtures/readiness-2500/`. Including these artifacts brings total written work to about 600 lines, still one behaviour with no production changes and within all ceilings. Scratch runners are retained as text evidence snapshots for reproducibility.
