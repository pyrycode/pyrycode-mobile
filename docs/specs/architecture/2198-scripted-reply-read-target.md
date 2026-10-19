# Scripted reply read-confirmation target

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: the ping delivery proof, classification query and daemon confirmation.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/ScriptedReplyReadCheckpoint.kt`: exact-row receipt validation and displayable-history classification.
- `app/src/test/java/de/pyryco/mobile/e2e/ScriptedReplyReadCheckpointTest.kt`: real history-reducer fixtures and negative proof cases.
- `docs/knowledge/features/thread-screen.md`, Foreground read checkpoints: receipt, exact sight and confirmed reads are separate facts.

Overlapping branches #1766, #2091 and #2104 edit other methods or Compose rule setup in the device test; these local ping edits do not require their changes.

## Problem and diagnosis

The Linux ping check receives the complete reply and asks for read checkpoint 12. The daemon confirms 9. Entry 9 carries the visible reply; completion and metadata follow it. The daemon deliberately clamps read marks to its latest displayable history entry. The test waits for a confirmation that this contract cannot return.

The instrumented baseline used Mobile `44a62447c8c54cb305a54166a2ebe92a3a943cf5` and daemon `0917625fc6e91eeb2a16556f09e5ba0bbf62aabf`. Both lifecycle and visibility qualification passed. The legacy route was selected. The run executed two cases, with one pass and the original read-confirmation timeout. Raw evidence is retained under the managed runner's private `logs/evidence/2198/manual-baseline-20261017` directory. Its only production diff is temporary content-free viewport logging; that diagnostic diff is excluded from this repair.

## Change

Add a test-only helper for the exact completed reply. First require the existing complete receipt and row-version proof from `threadSnapshots`, retaining that snapshot and exact reply before calling `requestHistory`. Only then query classification data and choose the highest displayable receipt bound to the retained reply. The query merges history into the observed projection, so it must neither run before qualification nor replace the retained proof with a later snapshot. Hidden completion and metadata must not raise this confirmation target. One bounded history query inside the existing deadline supplies entry types. Reuse the existing unread classifier, since successful completion changes the row but still contributes no unread content. Keep the existing UI presentation, real daemon confirmation, 90000 ms assertion deadline and ping selection.

The later peer-read assertion has the same error. Once history proves receipt of the exact owned durable post, confirm that post's id rather than the maximum bookkeeping id. The real peer confirmation and phone's unread-to-idle assertion remain mandatory.

No production code, read admission, retry policy, timeout or content route changes. Existing nonvisual receipt coverage remains intact. The helper refuses metadata-only claims, wrong or unfinished replies, and unresolved receipt holes or barriers.

## Verification

Add regressions for the observed 9-through-12 receipt shape and negative proof cases. Run the affected receipt, retry, read-mark and viewport tests. Obtain fresh Linux ping and complete scripted-suite evidence on the same daemon revision. Retain counted XML, source revisions and phone logs. This change includes ticket 2196's isolated APK fixture repair from PR 2197.

## Testing strategy

The shared suspend helper `awaitScriptedReplyReadCheckpoint` owns the ordering used by the ping scenario. Add `runTest` regressions with an observed `MutableStateFlow` and a history callback that repairs its missing receipts or streaming row using the real history reducer. Before qualifying live evidence arrives, the callback must remain uncalled and no target may return. After qualification it runs once. A further test replaces the observed snapshot during classification, proving the target still uses retained proof. Watch the ordering regressions fail with the previous fetch-first order before repairing it.

Run `ScriptedReplyReadCheckpointTest`, the existing viewport/candidate, receipt, read-mark and retry coverage, lint, assembly, Android-test compilation and forced Spotless. The existing device scenario needs real authenticated daemon/relay delivery and background work, so its Linux `scripted ping` gate remains required. The dispatcher owns the complete UI and scripted-all gates; these are pending until counted evidence exists. macOS is outside this ticket's verification scope.

## State transitions and identity reuse

No production state changes. Missing receipt and unfinished reply each withhold the classification query (`classificationCannotRepairMissingLiveReceipts`, `classificationCannotFinishTheLiveReply`). Once an exact completed reply qualifies, a projection update during the query cannot change its retained proof (`classificationUsesTheRetainedReplyProof`). Existing production lifecycle, obscuring-surface and host/conversation ownership tests remain unchanged.

## Security review

**Verdict:** PASS

**Findings:**

- Trust boundaries: MUST FIX from verifier finding 1, addressed by the revised design. Validation only before returning a target was insufficient: `requestHistory` can repair live evidence. `awaitScriptedReplyReadCheckpoint` must validate and retain the exact completed reply and complete receipt snapshot before invoking that query. Metadata alone cannot qualify. The ordering regressions enforce this boundary.
- Tokens and credentials: the helper handles typed receipt facts only. It reads no credentials.
- Files and storage: no runtime paths or storage writes are introduced. Raw test evidence remains private on the managed runner.
- Android attack surface: no component, intent, permission or push behavior changes.
- Input validation: missing, unknown and incomplete receipt evidence still fails. The target cannot come from a list hint or a different reply.
- Network and I/O: existing authenticated daemon confirmation remains mandatory. One bounded history read supplies the reply's entry types. No write or retry is added.
- Logs and telemetry: the committed helper logs no content or credentials. Temporary diagnostic logging is excluded from the repair.
- Concurrency: the classifier is pure. The suspend orchestration is owned by the existing scenario timeout, awaits qualification before I/O, then uses only the retained snapshot/reply. It creates no job or production sight state. Later projection updates cannot supply missing proof retroactively.
- Threat model: delayed or missing relay delivery cannot produce proof. Existing hole and exact-row checks withhold the target. This test change does not alter the application's Noise or storage protections.

**Reviewer:** builder, revised self-review using `builder/security-review.md` after verifier finding 1.
**Date:** 2026-10-16 (UTC; earlier runs used local 2026-10-17 dates).

## Results

The instrumented Linux baseline executed two cases: one passed and the original read-confirmation wait failed. A first row-change filter also failed on Linux because completion receipt 10 changes the row. Its failure remains retained under `logs/evidence/2198/manual-fixed-ping-20261017`.

Six focused regressions now use the real history reducer. Before the final classifier correction, four failed. They cover the exact 9-through-12 receipt sequence, metadata-only claims, missing displayable history, unresolved holes, incorrect replies and newer unseen content. Final counted validation is recorded on PR 2199 before merge.

The next Linux run passed reply confirmation and failed at the later peer-read assertion. It executed two cases, one passed and one failed, with no skips. Its evidence is retained under `logs/evidence/2198/manual-final-ping-20261017`. The peer target now names the exact durable post already required by that assertion.

Rework on Linux: both new ordering regressions failed with the extracted fetch-first behavior (2 executed, 2 failed, 0 skipped), each because the query ran before live qualification. With the retained-proof ordering, the focused receipt/read/viewport suite executed and passed 79 tests, including all nine classifier/orchestration regressions, with 0 failures/errors/skips. Fresh ping and final build evidence are recorded in PR #2199 after the pushed candidate runs; the dispatcher still owns complete UI and scripted-all verification.

## Revisions

- 2026-10-16, initial implementation: [50ab68573179fce3ab8413adddff791b766e3776](https://github.com/pyrycode/pyrycode-mobile/commit/50ab68573179fce3ab8413adddff791b766e3776) introduced this plan in the same commit as the helper and tests. It did **not** satisfy plan-before-code. No earlier committed target-repair design has been identified. [37a24662000feeddda80b95e9225396e13f67505](https://github.com/pyrycode/pyrycode-mobile/commit/37a24662000feeddda80b95e9225396e13f67505) is genuine earlier evidence of the inherited timeout in the #2196 plan, not a design for this repair. This chronology is retrospective and does not repair the original sequencing violation.
- 2026-10-16, classifier correction: [166f48f91af509972cf1350eee1bf4f250c37411](https://github.com/pyrycode/pyrycode-mobile/commit/166f48f91af509972cf1350eee1bf4f250c37411) added the history query and unread classifier after the row-change filter incorrectly included successful completion. The contract became complete receipt proof plus the exact reply's highest displayable history receipt. Change was rewritten without a revision entry; this entry records that omission. Its fetch-first integration still allowed history to repair the proof, as finding 1 establishes.
- 2026-10-16, peer correction: [09077e89a16f413c3fb3fddd1efefbc43af86a36](https://github.com/pyrycode/pyrycode-mobile/commit/09077e89a16f413c3fb3fddd1efefbc43af86a36) changed the later peer confirmation from the maximum bookkeeping id to the exact owned durable post after the next Linux run exposed the same target mismatch there. It also updated the security review for the history request, again without a revision entry. The exact-post receipt, real confirmation and phone unread-to-idle assertions remain required.
- 2026-10-16, verifier rework (planned before rework implementation): retain qualified live reply/receipt proof before any classification query; never re-read the projection to establish that proof after the query. Add ordering regressions at the shared orchestration actually used by the device scenario and reconcile the security review above. This is a test-only change, approximately 180 additional written lines across these three test files and this plan; the complete branch remains below the sizing limits, with no production API migration.

## Documentation handoff

- Pending documentation stage: `docs/knowledge/features/thread-screen.md`, Foreground read checkpoints, and `docs/e2e-interactive-stream.md`, scripted ping guidance: distinguish complete live receipt proof from displayable confirmation targets, require proof before mutating history classification, and document the exact peer-post target and final counted Linux evidence.
- Pending documentation stage: `docs/knowledge/features/development-verification-gates.md`, script/fixture verification guidance: carry forward #2196's controlled built-byte/installed-digest negative control, independent of real APK assembly outputs.
