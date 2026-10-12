# Attention-pill measurement investigation and repair (#2097)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_otherConversationAttentionPills_waitingAndFinished` and its local `evidence`; three synchronizing semantics queries precede the first Waiting wait.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/AttentionHostIsolation.kt`: `withAttentionHostIsolation` preserves restoration and the original failure, including cancellation.
- `scripts/e2e-emulator.sh`: `cleanup` owns capture before temporary reports and isolated homes disappear.
- `scripts/test_e2e_emulator_cleanup.py`: existing teardown status checks must continue to run.
- `scripts/e2e-evidence.py` and `scripts/test_e2e_evidence.py` on `origin/feature/2088`: reusable private exporter and retention tests, absent from main.
- `docs/knowledge/features/development-verification-test-scheduling.md`: the worker-resumption and forced-scroll precedents are hypotheses for this occurrence, not diagnoses.
- `docs/e2e-interactive-stream.md`: Other-conversation attention pills defines the existing live contract and explicit virtual-clock expiry proof.

## Context

The original Mac run reportedly executed 59 tests, with 57 passes, two failures and no skips. Its attention method failed during `evidence("peer_received")`, before the first Waiting wait, with reentrant Compose measurement. Neither the original nested stack nor its logcat is available in this worktree. Earlier Linux selections passed this method or failed before the probe; they do not establish the mechanism.

The recovery brief requires reliable capture before another reproduction. #2088 is now closed by the maintainer without merging its exporter. Reuse that published implementation rather than create a second export system; do not import its unrelated attachment scenario changes. The same exporter exists on the closed #2090 branch. Other feature branches share the live class; edits remain local to the attention method and capture hooks. No shared causal dependency has been established.

## Design

Adopt #2088's exporter and harness integration, retaining its allowlisted metadata, private stable run archive, atomic manifest publication and hashes. Extend the exporter to preserve bounded exception classes, static stack frames, cause and suppressed relationships from original device XML. Exclude exception messages and arbitrary log text. Missing, malformed, omitted and truncated observations must be explicit; a sanitized report is not a substitute for a missing original stack.

Add content-free records before and after each of the existing three semantics queries, with a fixed query identity, stage, thread ID, Looper classification and virtual-clock time. Separately timestamp each StateFlow sample; do not present samples across synchronization as an atomic snapshot. Capture a query's failure before rethrowing the same Throwable. Diagnostic-emitter failures must not replace an observation failure or prevent host/pairing cleanup.

Use an internal shared-test helper for probe emission so controlled JVM tests exercise ordering, failure identity and diagnostic-failure handling without requiring a live daemon. The three queries and existing assertions stay enabled and unchanged. This capture work does not establish or repair the measurement cause.

After a retained failure establishes the continuation/layout path, append a revision with the demonstrated mechanism, the smallest repair and the controlled failing-before/passing-after device or shared regression. Do not change dispatcher selection, remove the probe, add retries or alter product layout on the basis of a passing reproduction.

## State and concurrency model

The existing instrumentation-thread `runBlocking` isolation scope and Compose rule remain unchanged during capture. Probe records own no jobs and do not synchronize Compose themselves. Each semantics query retains its existing synchronization. Export runs synchronously during harness teardown, before and after owned processes stop, preserving the original command status on capture failure and cancellation. No new production lifecycle state is introduced.

## State transitions and identity reuse

| Event | Controlled coverage |
| --- | --- |
| Query starts, succeeds or throws | Probe helper tests assert ordered records and unchanged return/failure identity. |
| Diagnostic emitter itself throws | Probe helper tests preserve query execution and the original failure. |
| Harness fails or receives INT/TERM | Existing exporter retention tests preserve status and archive. |
| Temporary reports are removed after capture | Exporter tests reread the persistent archive and verify hashes. |
| Repeated capture before/after teardown | Existing stable-destination tests prevent a new archive or loss of captured data. |
| Nested cause/suppressed exceptions or oversized reports | New exporter tests retain relationships and mark bounded omissions. |

Host restoration and pairing/peer cleanup retain the existing isolation tests. A demonstrated measurement mechanism may require additional transition rows, recorded with its repair revision.

## Error handling

Capture is best-effort diagnostics: the original scenario failure and cleanup always take precedence. Unsupported stack text and unknown/missing probe fields become explicit unavailable/omitted markers. The exporter never retains credentials, exception messages, daemon-authored text or raw pairing outputs in its published metadata. Private raw XML/logcat used for diagnosis must also be retained with a verified hash manifest under the persistent agents evidence volume.

## Testing strategy

First execute the inherited exporter tests against current teardown to demonstrate the missing retention contract; then reuse the implementation. Add failing exporter tests for nested-stack/probe retention, bounds, credential exclusion, cancellation and removal of temporary reports before implementing the extension. Add JVM probe tests before instrumenting the live method. Run affected Python script tests, focused probe/isolation and existing attention unit/shared screen classes, lint, assembleDebug, Android-test compilation and forced Spotless checks.

A controlled measurement regression belongs on a device only if the retained causal path requires real Espresso/Android measurement. The existing live scenario remains curated and enabled, preserving A while B waits, navigation with the same held prompt, peer-only answering, real completion, Finished, 5,100 ms clock advancement and no replay.

Attempt the source-matched predecessor reproduction only after capture is proven. Record actual selection/order, Mobile/daemon revisions, Claude version, host/tool substitutions, counted results and durable artifacts. The previously cited `/tmp/builder-2097/prefix-selection.json` is absent on this fresh host and must be recovered or reconstructed explicitly. Fresh full-live acceptance remains dispatcher-owned after repair; diagnostic selections cannot substitute for it.

## Open Questions

- Can the original Mac XML and method logcat be recovered through an available authenticated route? No equivalent nested stack has yet been recovered.
- What continuation/layout operation was already measuring when the probe synchronized? This must be answered from a retained failing stack before choosing the repair.
- Does the controlled regression require a device? Decide from the demonstrated mechanism, not from the existing scenario's location.

## Size

Forecast: approximately 1,250 total written lines including the reused exporter/tests, capture extension, probe/helper tests, causal repair/regression and this plan; no public production types or signature migration. Three original acceptance criteria. Reassess before a causal change expands this slice. The capture infrastructure supports the existing scenario's diagnosis and is not a closing reliability claim.
