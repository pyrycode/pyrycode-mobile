# Atomic replay close (#2219)

## Files read

- `app/src/sharedTest/java/de/pyryco/mobile/recording/SuiteConnections.kt`: `RecordedTransport.close`, `ReplayPump.send`, and `beforeClose` coordinate local state and the strict tape.
- `app/src/sharedTest/java/de/pyryco/mobile/recording/RecordedSuiteTape.kt`: `eligible`, `hasEligibleRefusal`, and `drain` require the captured close before its refused request and inbound end.
- `app/src/test/java/de/pyryco/mobile/recording/SuiteConnectionsTest.kt`: the existing blocked-sender regression supplies the controlled monitor-interleaving pattern.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_interruptedRetrieval_retryLoadsThePeersFile`, `LinkCut`, and `setHostLink` retain the original failure, Retry, open and save assertions.
- `app/src/androidTest/java/de/pyryco/mobile/recording/RecordedSuiteNative.kt`: `finish` waits for the final checkpoint and reports incomplete tape consumption.
- `app/src/androidTest/assets/recorded/suite/interactiveTurn_interruptedRetrieval_retryLoadsThePeersFile.json`: genuine capture has a close followed by an explicitly refused attachment request, then inbound end; recovery uses the next connection generation.
- `docs/recorded-tests.md`: replay requires exact refused requests and complete consumption; replay isolation does not require separate processes.
- `docs/knowledge/features/attachment-retrieval.md`: the cut runs synchronously at the request log, before send; transfer failure must wake promptly.
- `docs/knowledge/features/development-verification-test-scheduling.md`: force a scheduling condition rather than infer causality from isolated success.

## Context

The retained #2213 Linux gate failed the final 30-second checkpoint with only the
old generation's refused attachment request, inbound end and checkpoint pending.
Its focused rerun passed 1/1 in 6.65 seconds. Both remain evidence, not acceptance
retries. `RecordedTransport.close` currently publishes `localClose` before taking
the hub monitor in `lifecycle`. A send holding that monitor can see a closed
transport while the tape still requires its close. `hasEligibleRefusal` then
returns false and the send bypasses exact matching. Recovery can succeed while
the final checkpoint waits forever for that unconsumed refusal.

The forecast is below 300 written lines, no new exported types or signature
changes, one deliverable, three criteria, and no new reject branches. The #2208
analogue changed 114 added/11 deleted lines; this repair is a smaller lock change
with controlled concurrency coverage. No overlapping remote feature branch
touches the two planned implementation/test files. No decision record is needed.

## Design

Serialize the whole `RecordedTransport.close` transition on the same hub monitor
as `ReplayPump.send`: idempotence, close publication, tape lifecycle consumption,
and pump state transition. Preserve all complete request comparisons and counts.
Do not change recordings, scenario assertions, deadlines or production code.

## State and concurrency model

The existing parent-owned hub scope and delivery queue remain unchanged. The
monitor makes a close and send linearizable; a second close cannot return while
another thread has published an unfinished close. The regression owns and joins
its worker and closes the hub in cleanup. No coroutine lifetime changes.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| Concurrent transport close, idempotent pump close, and refused request | New `aRacingCloseCannotHideTheCapturedRefusedAttachmentRequest` forces the first closer to wait behind the hub monitor, then closes and sends on its holder. |
| Closed pump with an incorrect refused payload | New focused negative test rejects a changed attachment identity after close. |
| Sender queued behind completed close | Existing `aSendWaitingBehindCloseCannotUseAnEarlierOpenState`. |
| Restart, link cut, Retry on the next generation, and final teardown | Original recorded method, five consecutive focused Linux executions. |

## Error handling

Existing strict mismatch and incomplete-consumption failures remain. Missing or
changed refused requests must fail; no cancellation is swallowed or retried.

## Testing strategy

Write the controlled JVM regression first and retain its failing XML/output
before the lock repair. Run `SuiteConnectionsTest` and affected tape tests after
repair. The named native scenario uses real app storage, Activity restart and
background connection collectors, so it remains device-only. Run its original
assertions five times without failures/skips, retain XML, output and revisions,
and leave the configured full UI gate to the dispatcher. Run focused build,
lint, formatting, instrumented compilation and final pre-verification checks.

## Open Questions

- Confirm the controlled regression fails with the same missing refused request
  before implementation, and passes with atomic close. Record measured results
  in the PR and causal ticket comment.

## Revisions

### 2026-10-17 — controlled failure confirmed

The forced overlapping-close regression failed twice before repair, each with
one executed/failed test and zero skips. Both failures leave precisely the
refused attachment request and inbound end unconsumed. The cold native baseline
passed 1/1 in 7.816 seconds, consistent with the historical fail-then-pass. The
supervisor's synchronous close races its cancelled loop's final transport close;
the regression forces that overlap with a held hub monitor. No prior test state
is required. Serialize close publication and lifecycle with send as planned.
The remaining open question is resolved by retaining the repaired focused results
and a content-free evidence index at
`app/src/test/resources/verification/recorded-close-2219/evidence.json`.

## Documentation handoff

Pending documentation stage: `docs/recorded-tests.md`, replay scheduling and
verified evidence: describe atomic close publication/lifecycle consumption and
link the controlled failed/repaired regression and five focused Linux runs.
