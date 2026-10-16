# Scripted reply read-confirmation target

## Problem and diagnosis

The Linux ping check receives the complete reply and asks for read checkpoint 12. The daemon confirms 9. Entry 9 carries the visible reply; completion and metadata follow it. The daemon deliberately clamps read marks to its latest displayable history entry. The test waits for a confirmation that this contract cannot return.

The instrumented baseline used Mobile `44a62447c8c54cb305a54166a2ebe92a3a943cf5` and daemon `0917625fc6e91eeb2a16556f09e5ba0bbf62aabf`. Both lifecycle and visibility qualification passed. The legacy route was selected. The run executed two cases, with one pass and the original read-confirmation timeout. Raw evidence is retained under the managed runner's private `logs/evidence/2198/manual-baseline-20261017` directory. Its only production diff is temporary content-free viewport logging; that diagnostic diff is excluded from this repair.

## Change

Add a test-only helper for the exact completed reply. First require the existing complete receipt and row-version proof. Then choose the highest displayable receipt bound to that reply. Hidden completion and metadata must not raise this confirmation target. One bounded history query inside the existing deadline supplies entry types. Reuse the existing unread classifier, since successful completion changes the row but still contributes no unread content. Keep the existing UI presentation, real daemon confirmation, 90000 ms assertion deadline and ping selection.

No production code, read admission, retry policy, timeout or content route changes. Existing nonvisual receipt coverage remains intact. The helper refuses metadata-only claims, wrong or unfinished replies, and unresolved receipt holes or barriers.

## Verification

Add regressions for the observed 9-through-12 receipt shape and negative proof cases. Run the affected receipt, retry, read-mark and viewport tests. Obtain fresh Linux ping and complete scripted-suite evidence on the same daemon revision. Retain counted XML, source revisions and phone logs. Ticket 2196 still needs fresh final review of its separate APK fixture repair.

## Security review

**Verdict:** PASS

**Findings:**

- Trust boundaries: a visible claim must belong to the exact reply. The existing receipt validator runs before any target is returned. Metadata alone cannot qualify.
- Tokens and credentials: the helper handles typed receipt facts only. It reads no credentials.
- Files and storage: no runtime paths or storage writes are introduced. Raw test evidence remains private on the managed runner.
- Android attack surface: no component, intent, permission or push behavior changes.
- Input validation: missing, unknown and incomplete receipt evidence still fails. The target cannot come from a list hint or a different reply.
- Network and I/O: existing authenticated daemon confirmation remains mandatory. No request or retry is added.
- Logs and telemetry: the committed helper logs no content or credentials. Temporary diagnostic logging is excluded from the repair.
- Concurrency: the helper is pure. It uses one immutable snapshot and creates no coroutine or retained sight state.
- Threat model: delayed or missing relay delivery cannot produce proof. Existing hole and exact-row checks withhold the target. This test change does not alter the application's Noise or storage protections.

**Reviewer:** Codex maintainer, self-review using builder/security-review.md.
**Date:** 2026-10-17.

## Results

The instrumented Linux baseline executed two cases: one passed and the original read-confirmation wait failed. A first row-change filter also failed on Linux because completion receipt 10 changes the row. Its failure remains retained under `logs/evidence/2198/manual-fixed-ping-20261017`.

Six focused regressions now use the real history reducer. Before the final classifier correction, four failed. They cover the exact 9-through-12 receipt sequence, metadata-only claims, missing displayable history, unresolved holes, incorrect replies and newer unseen content. Final counted validation is recorded on PR 2199 before merge.
