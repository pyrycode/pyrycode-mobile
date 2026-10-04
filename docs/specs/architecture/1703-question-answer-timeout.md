# #1703 — Identify the remaining question-answer timeout

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_questionAnswer_reachesTheAskingConversation`, `answerHostPeer`, `answerChat`, `awaitInlineQuestion`, `awaitNoInlineQuestion`, `awaitTurnEnd` and `peerStep` map the scenario's blocking operations.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `open`, `awaitQuestion`, `awaitQuestionDismissed`, `answerQuestion` and `awaiting` retain bounded waits and record received frames.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/PeerDeviceKeyStore.kt`: the host/token identity survives peer recreation; #1686's repair is already present.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/PeerWait.kt` and `app/src/test/java/de/pyryco/mobile/e2e/PeerWaitTest.kt`: timeout diagnostics retain the original cause and do not extend deadlines.
- `docs/e2e-interactive-stream.md`: question-answer scenario and #1686/#1702 evidence distinguish admission, Compose and coroutine failures.
- `docs/knowledge/features/development-verification-emulator-evidence.md`: deleted gate worktrees can remove the per-test logcat; retained stderr and isolated daemon logs remain usable.
- `scripts/android-test-gate.py`: the dispatcher captures fresh reports and logcats; the builder must not run the live suite.

## Context

The retained #1753 branch stderr reports a bare `TimeoutCancellationException` after 30000 ms on mobile `51dcf02e2b9e869b3c763ea835aa149f26d8b6fa`, daemon `65df98859f32e49ba59a42c4446d650b7625cf62`, Claude 2.1.280. Its base reports the same unnamed timeout on mobile `c77d9ca1a1e3a4e7ae1ae5f5f3f4ebe3bc0bfc55`, with the same daemon and Claude versions. Both app trees contain #1702's actions reveal and #1686's retained pairing identity.

The branch report is `2026-10-04T20-06-38-354Z_real-claude-gate_#1753.stderr.log`; the base is its `real-claude-gate-base` counterpart under the agents repository's `logs/`. The branch's retained `pyry-e2e.LVvrhI/daemon-answer.log` contains eight handshake accepts and no `static_key_mismatch`, `bound_to_other_key` or handshake-reject records. The removed gate worktree prevents correlating per-test logcat with the daemon log. These artifacts do not identify the failed operation. No remaining cause is asserted.

## Design

First make the failing wait identifiable, as the ticket explicitly requires when retained artifacts are insufficient. Add a test-only `QuestionAnswerStage` enum and an inline `questionAnswerStep(stage, linkState, block)` wrapper. Wrap setup, peer waits, phone selection/submission, disappearance checks and completed-turn waits in the named scenario. On coroutine or Compose timeout, report the fixed operation label and lazily read content-free peer link state, retaining the original exception as cause. Other failures and ordinary cancellation pass through unchanged. No payloads, ids, tokens, paths or answer labels enter the diagnostic.

The enum and wrapper live in `app/src/sharedTest/java/de/pyryco/mobile/e2e/QuestionAnswerStage.kt`, with pure JVM regressions in `app/src/test/java/de/pyryco/mobile/e2e/QuestionAnswerStepTest.kt`. A local adapter in the existing device scenario supplies `peer::linkState`. No ViewModel, UI state or event shape changes.

All waits retain their existing deadlines. Keep the scenario enabled, #1702's actions reveal, phone selection, remote/answered assertions, chosen-label-only reply, peer dismissal without a phone tap and both completed turns. No retries, production code or question UI changes are planned.

Overlaps: #1642, #1682, #1689, #1690, #1691, #1693 and #1695 touch other scenario bodies; the named-method edit and separate diagnostic file are local and independent.

Forecast: about 250 written lines including this plan, three test/harness files, one internal enum, no consumer signature changes, three acceptance criteria and two diagnostic timeout branches. This is within the ticket's estimate and builder limits. No decision record is needed.

## State and concurrency model

The synchronous inline wrapper owns no state, jobs, dispatchers or flows. It delegates to the existing operations on the test thread; their coroutine deadlines and Compose waits remain authoritative. `SecondClientPeer` retains its IO scope, recorded-frame StateFlow, redial supervision and cancellation through `close` in the scenario's `finally`. Phone connection lifecycle behavior is unchanged.

## Error handling

Raw `TimeoutCancellationException` and `ComposeTimeoutException` become an `AssertionError` identifying the operation, with the original throwable as cause. Existing helpers that already convert timeouts into named assertion failures retain those failures unchanged. Successful values, refusals and ordinary cancellation pass through without evaluating diagnostics. Failure text contains only test-authored stage labels and the peer's existing content-free status.

## Testing strategy

Write virtual-time regressions first against a pass-through diagnostic seam: each stage must identify an unanswered operation at the unchanged deadline, retain its timeout cause and inspect state only on failure. Check successful values, refusal and ordinary cancellation propagation, and a Compose timeout. Run these alongside `PeerWaitTest`, `PeerDeviceKeyStoreTest`, `RedialingLinkTest` and `LiveConnectionReadsTest`. Run lint, assembleDebug, compileDebugAndroidTestKotlin, formatting and one zero-Claude scripted scenario. The existing live scenario requires a real emulator, peer, daemon and Claude; the builder cannot execute it in isolation without the dispatcher harness.

## Open Questions

The underlying remaining flake is unresolved. A diagnostic-only pass is not proof of its repair. The dispatcher must retain the fresh full live run's stderr, XML and failing per-test logcat. If it fails, the operation label determines the repair and focused regression on builder re-entry. If the cause is upstream, file/link its owning issue rather than changing mobile assertions. A successful same-tree rerun is insufficient acceptance evidence.

## Live evidence handoff

Pending dispatcher execution: fresh full `python3 scripts/android-test-gate.py live`, executed/failed/skipped counts and explicit named-method execution/result. Preserve `needs-real-claude` and use `needs-live-artifacts` to return the captured evidence to the builder. On re-entry, record sanitized operation evidence and app/daemon revisions in this plan's Context/Revisions and on #1703, implement the implicated repair with its red/green regression, and rerun focused checks. Do not close out the flake based on instrumentation alone.

## Documentation handoff

Pending for the documentation stage after diagnosis and repair: `docs/e2e-interactive-stream.md`, question-answer scenario and Verification status; `docs/knowledge/features/development-verification-emulator-evidence.md`, Emulator and real evidence. Describe the identified operation, diagnostic labels and fresh live counts without copying private logs.
