# Run configuration acknowledgement wait (#2075)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `awaitFooter`, the remembered-effort scenario, `assertRecalledThenApplied`, and `restoreSettings` define the existing proof and failure cleanup.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/StatusSheet.kt`: `StatusSheetContent` groups Model, Effort and Permission; `sectionTitle` reports pending writes.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`: `onEffortSelected` and `sendSessionSettings` expose optimistic selection, then remember acknowledged writes and request a fresh reading before clearing pending.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/EffortRecall.kt`: `remember` and `awaitWrite` establish success-only persistence and the first-send barrier.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelEffortRecallTest.kt`: `ScriptedRepo` supplies the delayed acknowledgement pattern.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/StatusSheetTest.kt`: existing sheet selection, pending and interaction coverage.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/QuestionAnswerStage.kt`: fixed stage diagnostics avoid emitting daemon text.
- `docs/knowledge/features/thread-composer-footer-effort-recall.md`: Remembered effort recall requires acknowledged writes, fresh readings and unchanged explicit choices.
- `docs/e2e-interactive-stream.md`: What rung 3 is made of defines the existing two-turn restart proof.
- `docs/knowledge/features/development-verification-gates.md`: the regression belongs in sharedTest and runs under Robolectric.

## Context

The recovery brief identifies a reproducible harness race. `awaitFooter` sees the optimistic radio selection, constructs a list containing pending plus every sheet text, and accepts any element satisfying the note predicate. Ordinary text therefore satisfies its default predicate even while Effort is applying, before the following remembered-effort assertion is valid. Commit `4e4ebb72c` (#1196) introduced that contract; it is present on the reported failing mobile tree (`f98d50139d`, main `6629a7a538`) and the unchanged passing mobile tree `0acab7ce274b`.

The archived #1869 failure stage and daemon revision are unavailable. The earlier focused pass (mobile `0acab7ce274b`, daemon `a536d17b1e18`, 1 executed/passed) does not diagnose it. This plan repairs the controlled acknowledgement race and adds evidence for future stage comparisons, without claiming the race explains that archived occurrence. No further SSH access will be attempted.

Remote overlap: #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1869, #1879 and #1896 change other scenario blocks in `InteractiveStreamE2ETest`; none changes this wait or recall scenario. Keep edits local and additive.

## Design

Extract the existing wait into a sharedTest `ComposeTestRule` extension used by `awaitFooter` and controlled regressions. Give the three existing section containers static semantics test tags and the effort note its own tag. No visible layout, copy or product state changes.

Each wait iteration reads one requested section subtree. Selection, absence of its applying header and its optional note must all agree in that snapshot. A missing section cannot settle. The existing Effort placeholder continues to support an applied reading without a selectable level. Text or selection from another section cannot satisfy the wait. Keep opening, closing and timeouts unchanged; add no retry or longer deadline.

Use fixed remembered-effort stage labels around fixture preparation, priming acknowledgement/persistence, graph restart, chat/channel recall confirmation before send, first-turn applied readings, explicit-choice protection and cleanup. Diagnostics report fixed stage and outcome codes only. Errors retain their original cause. Cleanup still attempts every restore and clears remembered effort on failure; activity closure runs even if cleanup fails.

## State and concurrency model

No production jobs or flows change. The shared wait polls Compose semantics synchronously at the existing deadline. The controlled regression drives a real `ThreadViewModel` with a fake repository whose acknowledgement and fresh settings emissions are independently held; the VM scope is cancelled after the test. Persistence is recorded by a fake remembered-effort store.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| Tap selects optimistically before acknowledgement | `RunConfigurationWaitTest.optimisticEffortWaitsForAcknowledgementAndFreshReading` |
| Acknowledgement persists effort while fresh read remains pending | Same test; wait remains blocked with persistence complete |
| Confirming fresh reading clears pending | Same test; identical wait completes |
| Matching labels or notes occur in another section | `RunConfigurationWaitTest` selection/note isolation controls |
| Requested section is absent | `RunConfigurationWaitTest` absence control |
| Activity/graph restart reuses on-device remembered value | Existing named live scenario, GraphRestart stage |
| Fresh chat and channel each recall before first send and apply after one turn | Existing live scenario, separate Chat/Channel stages |
| Explicit saved effort survives recall opening | Existing live scenario, ExplicitChoice stage |
| Failure during any scenario stage | Stage wrapper regression; existing `finally` restore/clear and nested activity closure |

## Error handling

A wait timeout reports only section, selection-match, pending and note-match booleans; no raw daemon labels or note text. A scenario stage failure adds its fixed stage name while retaining the original exception. Cleanup diagnostics distinguish restored settings and remembered-effort clearing failures without replacing an earlier scenario failure. No network, daemon or protocol change.

## Testing strategy

Write the shared delayed-ack regression first against the extracted old wait and observe it complete too early. After repair, assert it stays blocked before ack and while the requested fresh read is pending, then succeeds on settlement. Add wrong-section selection/note, note absence, missing-section and permission/model controls. Run the existing `StatusSheetTest` and `ThreadViewModelEffortRecallTest` alongside the new shared tests. Run one affected shared regression class on the managed Android 13 device to check real semantics.

The live method remains device-only because it uses a real daemon/relay, on-device DataStore and graph restart. Ticket acceptance explicitly assigns its fresh full live run to the dispatcher; no separate focused live run is required. `## Live tests` will request `all` because the repaired helper is shared. Require the named recall method's executed/pass/fail/skip counts and actual mobile/daemon revisions from that full run; later documentation records the evidence.

Run lint, assembleDebug, androidTest Kotlin compilation and Spotless, then merge current main, push and run assembleDebug plus `scripts/pre-verify.py --gradle` with the prepared PR body.

## Open Questions

None. The historical failure attribution remains explicitly unproven; the controlled race is independently testable.

## Documentation handoff

- Pending documentation stage: `docs/knowledge/features/thread-composer-footer-effort-recall.md`, Remembered effort recall — record the corrected acknowledgement wait and its controlled regression evidence.
- Pending documentation stage: `docs/e2e-interactive-stream.md`, What rung 3 is made of — record the fresh dispatcher full-live named pass with counts, mobile/daemon revisions and retained stage diagnostics. Keep the archived occurrence's missing diagnosis separate from the reproduced race.

## Sizing

One deliverable: a corrected shared acknowledgement wait with regression and stage evidence. Forecast approximately 600 written lines, one production file, no new exported product types, no existing consumer signature changes, three acceptance criteria and no production state-machine branches. Below all builder boundaries.
