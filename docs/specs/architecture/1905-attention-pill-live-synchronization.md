# Stabilize the attention-pill live scenario (#1905)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_otherConversationAttentionPills_waitingAndFinished`, `answerHostPeer`, `pairAnswerHost`, `answerChat`, `peerStep`, and `openChatRow` establish setup and observation seams.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadAttention.kt`: `observeThreadAttention` aggregates every paired host's waiting conversations and suppresses Finished while Waiting exists.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt`: `launchAttention` folds repository modal state into host-scoped attention.
- `app/src/main/java/de/pyryco/mobile/data/crypto/PairedServerStore.kt`: `PairedServerCollectionStore` owns pairing cleanup.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadAttentionNoticeTest.kt`: controlled rendering coverage distinguishes single-target and count pills.
- `docs/knowledge/features/thread-top-overlay.md`: the single/count contract and virtual-clock expiry lesson govern the scenario.
- `docs/knowledge/features/development-verification-compose-evidence.md` and `development-verification-gates.md`: controlled regressions belong in shared tests where possible; device results require counted XML.
- `docs/e2e-interactive-stream.md`: attention-pill rung-3 coverage and historical verification evidence.
- `docs/specs/architecture/1793-attention-pill-live-flake-validation.md`: prior expiry repair does not diagnose the first-Waiting failure.
- Dispatcher retained `2026-10-07T08-34-36-084Z_real-claude-gate_#1854` log, stderr and metadata, and rerun companions: tested merge `f77f772c8d56251c3e5da12c1c125d887d2ed872`, first Waiting timeout, 64 executed / 4 failures / 0 errors / 0 skips; rerun 4 executed / 1 failure / 0 errors / 0 skips with the named method passing.

## Context

The first Waiting observation fails intermittently after the peer receives B's permission modal. Historical artifacts identify the stage but do not contain phone repository/attention/semantics facts that distinguish missing delivery from an aggregated label or wrong destination. A passing rerun is not a diagnosis. The earlier five-second expiry clock repair stays intact.

One deliverable: diagnose and repair this scenario's synchronization or isolation, preserving product behavior. Forecast approximately 450 written lines, no production changes, at most two test-support types, one scenario caller, four acceptance criteria and no production reject branches. Overlaps #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1879 and #1888 edit unrelated live methods; edits here stay local and additive.

## Design

First add bounded, content-free observations around the first Waiting stage: peer modal/turn frame counts, phone modal ownership, target attention, counts of other waiting conversations, target snapshot/name availability, current destination and matching/tagged semantics counts. Failure messages name the stage without dumping frames, semantics trees, credentials, names or message text. Run the named live method with this evidence before choosing a repair.

Investigate the scenario's unproved assumptions: a peer modal does not prove phone receipt; the pill aggregates all hosts; creating and opening A does not itself establish that no unrelated prompt remains. A repair must establish the diagnosed prerequisite through production observables, scoped to this scenario, without polling retries of the scenario, ignored assertions or longer blanket timeouts. Append the diagnosis and final contract under Revisions before committing the repair. If evidence establishes a product or sibling defect, file/link a separate blocker rather than editing production here.

Preserve A visible while B waits, single Waiting tap to B with the same modal still outstanding, Back to A with Waiting, peer-only answer, real completion and Finished, explicit virtual-clock expiry and no replay when reopening A. Keep the enabled method and curated selector. Preserve `finally` cleanup of the peer and isolated answer pairing.

## State and concurrency model

Only instrumentation/test-support code changes. Peer calls use existing bounded coroutine helpers; phone observations read existing host StateFlows. No application jobs or flow contracts change. Any new observation collection has a bounded lifetime. Main-clock advancement remains limited to the existing expiry proof.

## Error handling

Report the first failed stage with static codes, booleans and counts. Do not catch failures to continue a scenario. Cleanup runs after assertion, connection or setup failure. A regression must distinguish the diagnosed old-drive failure from the repaired drive under a controlled delayed/isolation condition.

## Testing strategy

Write and run a failing controlled regression before the repair, then observe it pass. Prefer shared Compose/JVM support for controlled scheduling or aggregation; use a device-only regression only if actual background I/O/transport is necessary. The existing live scenario requires real transport, peer, daemon and Claude and stays device-only. Run the focused named live method after repair and inspect fresh XML counts. Full fresh live acceptance belongs to the dispatcher after verification, including the explicit named-method result and XML path; do not claim it passed locally.

Run affected regressions and existing attention unit/render/navigation coverage, lint, assembleDebug, Android-test compilation, formatting and forced Spotless. Push before final whole unit/shared suite, assembly and `scripts/pre-verify.py --gradle` after the final main merge.

## Open Questions

- Resolved: the controlled first-Waiting failure holds B in the phone modal and attention folds, but an inherited primary-host prompt changes the pill to an aggregate count.
- Resolved: temporarily retain only the answer host in the phone pairing collection, wait for host-source isolation, and restore exact original records, names and order afterward.

## Documentation handoff

Pending for the documentation stage: update `docs/e2e-interactive-stream.md`, “Other-conversation attention pills (#1735)” and the related Verification status entry, with the diagnosed condition, repair, controlled regression evidence and dispatcher full-live executed/failed/error/skipped counts, revision, XML artifact path and explicit named-method result. Distinguish the first-Waiting timeout from the earlier expiry repair.

## Revisions

- 2026-10-08: fresh controlled negative at plan revision `50f0b9fad` plus diagnostic and inherited-prompt test edits reproduced the historical first-Waiting timeout. `build/dispatcher-tests/live-jcdicm6b/dispatcher.xml`: 1 executed, 0 passed, 1 failure, 0 errors, 0 skips. Its retained logcat/failed original XML report `phone_modal=true`, `target_waiting=true`, `waiting_count=2`, `other_host_waiting=1`, `target_name_matches=true`, `a_nodes=1`, `pill_nodes=1`, `expected_nodes=0`. No retry or increased timeout was used. The initial diagnostic-only run `live-vk4qho_z/dispatcher.xml` passed 1/1; it proves the uncontaminated drive, not the diagnosis.
- Historical corroboration: retained `/var/folders/k0/gc07w9ws319b07n0plnw6y8r0000gn/T/pyry-e2e.NTBfoP/daemon.log` records a primary-host permission wake at 11:45:24.463 +03:00 with no intervening decision before the answer-host B wake at 11:48:29.010 in `daemon-answer.log`. The next primary decision is at 11:51:19.095. This overlaps the historical timeout; the exact historical phone aggregate was not retained, so the controlled reproduction establishes the isolation defect without claiming an observed historical phone count.
- Final design: `withAttentionHostIsolation` in shared test support captures the phone's original pairing collection, temporarily removes other hosts and restores exact records, metadata and ordering in `finally`, including scenario/setup failure and cancellation. The live method waits for only the answer host in `HostConversationSource` before starting B. It leaves the real held prompt on the other daemon untouched. Production aggregation remains covered by the controlled regression driving `observeThreadAttention`: old setup yields two waiters, isolated setup yields exactly B. The scenario also checks the same modal identity after navigation and Back. Existing answer-host/peer cleanup still wraps the scope. No shared live helper or selector changes.
- Controlled repaired live drive, with the same inherited primary-host prompt still held: `build/dispatcher-tests/live-frx2a288/dispatcher.xml`, 1 executed/passed, 0 failures/errors/skips, exit 0. Daemon `6019328b378cad587f69b7bc94de37febbdf8556`, Claude 2.1.280; both controlled runs use parent `50f0b9fad` with the documented working-tree test drive. Removed the temporary primary-turn injection after this proof; the permanent controlled JVM regression drives the production fold without another real-Claude turn. Content-free counted XML copies are retained under `app/src/test/resources/e2e/attention-host-isolation/`; original live logcats remain in the named gate artifact folders.
- Controlled JVM red at the old no-isolation drive: 2 executed, 2 failures, 0 errors/skips; the aggregation test expected B's single target and actually observed two waiters. The repair first passed both cases, then passed four cases including cancellation and partial setup failure. Additional restoration order/metadata and error-preservation cases are included in final focused verification.
- Final focused counts: `AttentionHostIsolationTest` 6 passed, `ThreadAttentionTest` 14 passed, `ThreadAttentionNoticeTest` 10 passed, and correctly qualified `ThreadAttentionNavigationTest` 4 passed; every class has 0 failures/errors/skips. Lint, assembly and Android-test Kotlin compilation passed. Final written work remains approximately 500 lines including plan, regressions, diagnostics and retained XML, under every sizing boundary; no production files, caller migrations or new production error branches.
