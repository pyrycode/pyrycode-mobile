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
| Deferred tap retrieves, opens once, then long-press saves the retrieved file | Same scenario with original digest/read-grant assertions; controlled native composer-placement red/green and `AttachmentActionTargetTest` |
| A composed restored file starts behind the header or composer | `restoredDeferredOffer_behindHeader_receivesOneOpenRequest`, `restoredDeferredOffer_behindComposer_receivesOneSaveRequest` |
| Shared helper opens history-restored and interrupted/retried attachments | `interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload`, `interactiveTurn_interruptedRetrieval_retryLoadsThePeersFile` if shared helper/monitor changes |
| Fresh instrumentation and repeated restored IDs | Five consecutive focused executions, each through the managed launcher |

## Error handling

Missing click admission, incomplete/failed retrieval and missing monitor observation must remain failures. Capture evidence before scenario cleanup; preserve the original exception. No retries, skipped assertions or longer deadlines. Retain all historical and newly failed evidence.

## Testing strategy

Run the named method first in its existing restart/cold state. Add and execute a causal red/green regression, then five consecutive successful exact-method native executions. If the shared helper/monitor changes, run its two other attachment consumers. Native tests are justified by real Activity instrumentation, URI grants, MediaStore and app-private storage across graph restart. Preserve fresh XML counts, raw logcat, source revisions and a verified hash manifest in the agents evidence folder. macOS is the ticket operating system; other systems are outside scope. Dispatcher owns configured full UI acceptance, pending after builder checks.

Run focused JVM coverage if portable logic changes; lint, debug assembly, instrumented compilation and forced Spotless checks. Commit/push before final main integration assembly and `scripts/pre-verify.py --gradle`.

Estimate remains approximately 600 written lines, no new exported production types, at most three shared helper consumers, four acceptance criteria and no new state-machine rejection branches.

## Open Questions

- Resolved for the reproduced defect: physical click delivery is lost behind thread chrome, before retrieval or intent dispatch. The historical bundles lack pre-cleanup phase telemetry and cannot uniquely attribute those earlier failures; the controlled native restart reproduction and its repair establish the causal helper defect without treating passing unchanged runs as proof.

## Revisions

### 2026-10-24 — isolate physical attachment placement

The unchanged exact method passed 1/1 on macOS; a diagnostic-only execution also passed 1/1. Neither is repair evidence. The original helper used a physical center tap after semantic list placement. That placement does not account for the header and composer drawn over the list. `AttachmentActionTargetTest.restoredDeferredOffer_behindHeader_receivesOneOpenRequest` reproduces a composed, clickable deferred offer whose physical tap produces no request (1 executed, 1 failed, 0 skipped). Extract the existing target selection into `attachmentActionTarget` so the same seam is exercised by the native open/save helper and regression; repair it with the existing `questionAnswerTarget` geometry correction before a single gesture. Add a composer/save control as the second regression. No app code, intent monitor, recording bytes, consumption checks or deadlines change.

The native controlled restart experiment and historical causal attribution remain pending at this revision. Keep pre-tap bounds and request/load/open phases, with resumed Activity and monitor counts sampled on timeout before cleanup. Restore the borrowed log sink on every exit. Raw evidence is retained under the agents repository's `logs/evidence/2526/builder-20261024/`.

### 2026-10-24 — confirm native cause and repair

The original restarted recorded scenario, with only a controlled scroll placing the restored row behind the composer, reproduced the exact 90-second viewer wait: 1 executed, 1 failed, 0 skipped. Before the gesture its bounds were `(106,1450)-(585,1608)` and the composer began at 1489; its center was behind the composer. At timeout, before cleanup, one Activity was RESUMED, the monitor had answered zero intents and the captured request/load/open phases were empty. The later launcher-focus snapshot showed both Activities destroyed, independently confirming that this teardown snapshot cannot diagnose the earlier loss. This reproduction distinguishes a missing physical row tap from a stalled retrieval or missed intent observation.

Keeping that identical native placement control and changing only `attachmentActionTarget` to use `questionAnswerTarget` passed 1/1, failed/skipped 0, with request, retrieved, opened and saved events and all original URI, read-grant, digest, restored-row uniqueness and strict-consumption assertions. Both shared screen controls passed (2 executed, 0 failed/skipped). The forced native placement is an evidence-only experiment retained in the source snapshots; it is removed from the final scenario. The permanent tests reproduce the obscured initial geometry. The final helper corrects geometry before one physical gesture, with no gesture retries, longer deadline or omitted assertion. Retain the content-free geometry callback and timeout phase probe for any recurrence.

Final scope remains one attachment-helper repair, about 250 written lines including tests and plan, zero production files, no exported production types, three scenario consumers, four acceptance criteria and one test-only timeout diagnostic branch. Dispatcher-owned configured acceptance remains pending after builder-focused evidence; other operating systems are outside this macOS ticket.


### 2026-10-24 — accepted verification and documentation completion

Independent verifier PASS at `87e5eb82008794fdf2459cb78faf5b3fb2e91cbe` supersedes the builder's configured-acceptance-pending statements above. The named method passed in the original configured UI sweep; original250/248/2/0 and exact unrelated2/2 rerun remain separate. Scripted24 retained4 accepted inherited failures (#2521/#2469), reproduced on main76822ac. The verifier authenticated683 evidence-manifest entries, causal native1-red/1-green, five fresh focused passes, both other consumers2/2 and two permanent screen regressions2/2. Historical #2491/#2507 attribution remains unavailable; the unexecuted wider-investigation request is not a missing acceptance gate or passing coverage.

Juhana explicitly requested documentation and completion after this accepted review. Manual documentation pickup used the protected exact-ticket hold40→41 with no2526 worker running. The owning replay guide and Compose verification topic now describe physical targets clear of chrome, pre-teardown phase evidence, accepted counts and historical limits. No app/test/recording/deadline change or repeated green test was made. The original investigation and failure evidence remain retained. Documentation guard and whitespace checks accompany the normal workflow completion handoff.
