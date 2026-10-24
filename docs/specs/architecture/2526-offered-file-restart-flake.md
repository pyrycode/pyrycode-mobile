# Offered-file restart reliability (#2526)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: offered-file scenario, `restartApp`, `readyAttachmentRow`, `assertOpensAndSaves` and scroll helpers define the failing phase and three attachment consumers.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/ActivityIntentStub.kt`: `answer` and `onStartActivity` define intent interception and concurrent observation.
- `app/src/androidTest/java/de/pyryco/mobile/recording/RecordedMobileSuiteTest.kt`: original native scenario delegation and rule ownership.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/QuestionAnswerPhone.kt`: existing geometry-aware physical target placement accounts for thread chrome before one gesture.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/AttachmentActionTarget.kt` and `AttachmentActionTargetTest.kt`: shared physical target seam and controlled restored-offer placement regressions.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageAttachments.kt`: deferred and retrieved rows share click semantics but different callbacks.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`: `onAttachmentRequested`, `claimAttachment` and `loadAttachment` distinguish request admission from retrieval completion.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/AttachmentActions.kt`: `rememberAttachmentActions` opens the source carried by a load event; `openAttachment` starts the external viewer.
- `docs/knowledge/features/message-bubble-attachment-slot.md`: a visually ready file can still be deferred; the assertion must retain fetch-on-tap coverage.
- `docs/knowledge/features/attachment-retrieval.md`: retrieval/restart contracts and strict recorded replay evidence.
- `docs/recorded-tests.md`: preserve original captured bytes, strict consumption and restart ownership.
- `docs/knowledge/features/development-verification.md`: focused native execution and durable counted evidence.

## Context

On macOS, #2491 and #2507 timed out waiting for ACTION_VIEW after restart, before save began. #2507's retained verifier bundle is `8a4f4e7fe71f09e8c7b0d8c82e4c8e0372637ca4a2ccc9c3129f691d96ce5f68`; `verifier-gate__2507_5.stderr.log` identifies `assertOpensAndSaves`' viewer wait. Cleanup focus snapshots cannot establish which earlier phase failed. No decision record is needed for a local reliability repair.

## Design

Keep one behaviour: reliable attachment open/save after graph restart with the existing retrieval, URI, read-grant, digest, uniqueness and save-notice assertions. First execute the named method unchanged. Establish which boundary loses progress using the current row semantics, existing structured attachment request/load/open events, and Activity monitor answers. Add a controlled regression for the demonstrated boundary before its repair. Keep diagnostics content-free and local to the attachment checks; forward and restore any borrowed log sink in `finally`.

The expected repair surface is the attachment test/helper or intent stub, with one focused regression class under `app/src/androidTest/` if native instrumentation is needed. Do not alter recording payloads, request matching, assertion deadlines or unrelated scenarios. Record the demonstrated cause and concrete repair under Revisions before implementation handoff. A production defect or materially wider migration triggers a scope reassessment before expansion.

Remote overlap review found shared-file changes on #1682, #1689–#1695, #1766, #1869, #1879, #1947, #2009, #2032, #2052, #2057, #2088–#2092, #2098–#2100, #2102, #2104, #2112, #2114, #2117–#2119, #2138, #2139, #2175, #2311, #2462 and #2490. Diffs affecting this surface add diagnostics to other consumers or the already integrated restart fence; none is a prerequisite. Keep edits local and additive.

## State and concurrency model

No new application state or jobs. Attachment retrieval remains owned by the thread ViewModel and effects by the mounted screen. Activity monitor answers cross from Main to the instrumentation thread through the existing concurrent list. Any diagnostic observer is scoped to one assertion and restored on every exit.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| Offer arrives, row caches, old Activity and graph close, replacement opens the same conversation | `interactiveTurn_claudeOfferedFile_opensAndSavesAfterRestart` |
| Deferred tap retrieves, opens once, then long-press saves the retrieved file | Same scenario with original digest/read-grant assertions; controlled causal regression to be named in Revisions |
| Shared helper opens history-restored and interrupted/retried attachments | `interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload`, `interactiveTurn_interruptedRetrieval_retryLoadsThePeersFile` if shared helper/monitor changes |
| Fresh instrumentation and repeated restored IDs | Five consecutive focused executions, each through the managed launcher |

## Error handling

Missing click admission, incomplete/failed retrieval and missing monitor observation must remain failures. Capture evidence before scenario cleanup; preserve the original exception. No retries, skipped assertions or longer deadlines. Retain all historical and newly failed evidence.

## Testing strategy

Run the named method first in its existing restart/cold state. Add and execute a causal red/green regression, then five consecutive successful exact-method native executions. If the shared helper/monitor changes, run its two other attachment consumers. Native tests are justified by real Activity instrumentation, URI grants, MediaStore and app-private storage across graph restart. Preserve fresh XML counts, raw logcat, source revisions and a verified hash manifest in the agents evidence folder. macOS is the ticket operating system; other systems are outside scope. Dispatcher owns configured full UI acceptance, pending after builder checks.

Run focused JVM coverage if portable logic changes; lint, debug assembly, instrumented compilation and forced Spotless checks. Commit/push before final main integration assembly and `scripts/pre-verify.py --gradle`.

Estimate remains approximately 600 written lines, no new exported production types, at most three shared helper consumers, four acceptance criteria and no new state-machine rejection branches.

## Open Questions

- Which of click delivery, retrieval completion or intent interception explains the historical viewer timeout? Resolve with failing-phase evidence and a controlled regression; passing unchanged replays alone are insufficient.

## Revisions

### 2026-10-24 — isolate physical attachment placement

The unchanged exact method passed 1/1 on macOS; a diagnostic-only execution also passed 1/1. Neither is repair evidence. The original helper used a physical center tap after semantic list placement. That placement does not account for the header and composer drawn over the list. `AttachmentActionTargetTest.restoredDeferredOffer_behindHeader_receivesOneOpenRequest` reproduces a composed, clickable deferred offer whose physical tap produces no request (1 executed, 1 failed, 0 skipped). Extract the existing target selection into `attachmentActionTarget` so the same seam is exercised by the native open/save helper and regression; repair it with the existing `questionAnswerTarget` geometry correction before a single gesture. Add a composer/save control as the second regression. No app code, intent monitor, recording bytes, consumption checks or deadlines change.

The native controlled restart experiment and historical causal attribution remain pending at this revision. Keep pre-tap bounds and request/load/open phases, with resumed Activity and monitor counts sampled on timeout before cleanup. Restore the borrowed log sink on every exit. Raw evidence is retained under the agents repository's `logs/evidence/2526/builder-20261024/`.
