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

- Which phone layer differs at the first Waiting timeout? Resolve using bounded stage diagnostics and a controlled reproduction.
- Which local prerequisite or observation repairs that condition? Record the controlled red/green and tested revision in Revisions.

## Documentation handoff

Pending for the documentation stage: update `docs/e2e-interactive-stream.md`, “Other-conversation attention pills (#1735)” and the related Verification status entry, with the diagnosed condition, repair, controlled regression evidence and dispatcher full-live executed/failed/error/skipped counts, revision, XML artifact path and explicit named-method result. Distinguish the first-Waiting timeout from the earlier expiry repair.
