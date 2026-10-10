# Send now live timeout diagnosis (#1898)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_sendQueuedNow_reachesRunningTurn`, `freshSettings`, `hostRepository`, `awaitConnected`, and conversation-list reads establish the waits and existing proof.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `open`, `awaitQueue`, and `linkState` establish bounded peer waits and safe diagnostic metadata.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/QuestionAnswerStage.kt`: `questionAnswerStep` supplies the existing operation-labelled timeout pattern.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/LiveConnectionReads.kt`: `firstOnLive` documents connection replacement during scenario reads.
- `scripts/e2e-emulator.sh`: the live Send now release watcher waits for isolated-daemon delivery; the deterministic twin uses the same delivery fence.
- `docs/e2e-interactive-stream.md`: “Send now coverage (#1642)” distinguishes the daemon ordering race from this unlocated timeout.
- `docs/knowledge/features/development-verification-emulator-evidence.md`: preserve original deadlines, causes, shared-process evidence, and revision-linked XML.
- `docs/knowledge/features/queued-backlog.md`: queue snapshots replace the backlog; removal alone cannot prove user delivery.

## Context

The retained full run reports 64 executed, 13 failed, 0 errors, 0 skipped. The named method has a bare 30000 ms coroutine timeout. Its contrasting rerun reports 13 executed, 1 failed, 0 errors, 0 skipped and passes this method. Both harness stderr records identify mobile `a7a4b484d7260c4dc7418b85df9c10f2b7245398` and daemon `6019328b378cad587f69b7bc94de37febbdf8556`; the issue's feature/main revisions precede the tested merge. The failure stack does not identify an operation. Retained `pyry-e2e.GeWqYT/daemon.log` has no Send now delivery event, whereas passing `pyry-e2e.niOQ0a/daemon.log` does. This narrows investigation but does not establish a cause or repair.

The failed scenario follows `interactiveTurn_interruptedUpload_retriesIntoOneMessageWithItsBytes`; the passing rerun follows `interactiveTurn_createEditArchiveChannel_readsPromptBack`. The original method-level XML and phone logcat disappeared with the gate worktree. Do not infer a particular early wait from that gap. No decision record is needed.

## Design

Add a test-only `SendNowStage` enum and `sendNowStep` wrapper beside the existing question-answer diagnostic. Each scenario operation receives a fixed label. Timeout diagnostics lazily read peer link state and phone connection metadata: saved harness host present, selected host matches, relay/daemon state names and current repository availability. Never include pairing records, identifiers, prompts, frame payloads or exception messages from the daemon. Preserve the original exception as the cause. Do not retry or extend deadlines.

Wrap setup, session warmup/settings, peer admission/send, held-tool observation, phone queue/send action, peer queue observations, turn completion and final phone verification separately. Preserve all original assertions. Add bounded observation to the final phone row read, which currently has no explicit outer timeout.

No production or wire changes are justified without failure evidence. If fresh diagnostics locate a responsible defect, revise this plan before repairing it and add a controlled regression test. A passing diagnostic run alone is not a cause repair. The full dispatcher gate remains the required live acceptance.

Overlaps in `InteractiveStreamE2ETest` with #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1869, #1879, #1888, #1896, #1899 and #1900 affect other methods or helpers; the local scenario instrumentation needs none of those changes.

## State and concurrency model

No new coroutine scope, flow or connection ownership. Existing instrumentation calls retain their scopes. Diagnostics read current state synchronously only after a timeout; peer closure stays in `finally`.

## State transitions and identity reuse

| Event | Check |
| --- | --- |
| A coroutine wait expires | `SendNowStepTest.coroutineTimeoutNamesStageAndCurrentLinksAtOriginalDeadline` preserves deadline and original cause. |
| A Compose wait expires | `SendNowStepTest.composeTimeoutNamesStageAndPreservesCause` identifies the exact phone operation. |
| Connection state changes while waiting | `SendNowStepTest.coroutineTimeoutNamesStageAndCurrentLinksAtOriginalDeadline` reads state lazily at failure. |
| Cancellation or non-timeout failure | Dedicated tests require the same exception, with no diagnostic read or retry. |
| Scenario executes after a predecessor in one process | Dispatcher full live gate retains method XML, phone logcat and tested revisions; diagnostic snapshots expose selected-host and link readiness. |

## Error handling

Only coroutine and Compose timeout exceptions become operation-labelled assertion failures. Other failures and cancellation propagate unchanged. Diagnostic messages contain static labels and content-free link state. Existing peer and tool failures retain their causes.

## Testing strategy

Write controlled JVM checks for diagnostic failure propagation, original deadline, lazy link reads and single execution before implementing the wrapper. Run `SendNowStepTest` and existing `QuestionAnswerStepTest`, `PeerWaitTest`, and `LiveConnectionReadsTest`. Compile Android tests and run the deterministic `send-now` twin with counted XML. The live scenario requires real device transport, a real foreground Bash tool and live Claude; it remains device-only. The ticket assigns the fresh full live gate to the dispatcher. Retain full/rerun historical method XML excerpts and revision metadata as test assets without raw credentials or payload logs. Builder diagnostics are evidence gathering, not claimed live repair.

## Open Questions

- Which operation timed out? Historical evidence cannot answer; stage diagnostics and newly retained phone/daemon evidence must locate it.
- Is the cause scenario, mobile or daemon owned? No production repair is selected until evidence answers this.

## Documentation handoff

- Pending: `docs/e2e-interactive-stream.md`, “Send now coverage (#1642)”: record diagnostic stages, historical failure/pass distinction, and eventual dispatcher method-level XML, mobile/daemon revisions and suite counts. Do not describe diagnostic passes as a repair.
