# Saved-thread initial snapshot cost (#2153)

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/CachingConversationRepository.kt`: `observeThreadSnapshot` reconciles already-validated saved rows with an empty first delegate snapshot before publishing; `ThreadWriter` already defers cache policy work until delivery.
- `app/src/main/java/de/pyryco/mobile/data/cache/ConversationCache.kt`: `readThread` guarantees settled rows with unique renderer keys.
- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryPageReducer.kt`: `mergeUnsignedCachedRows` performs attribution, atomization, insertion and renderer-key reconciliation even when only validated cache rows exist.
- `app/src/test/java/de/pyryco/mobile/data/repository/CachedThreadWorkerTest.kt`: held-worker traversal, suppression, disconnect, ordering and cancellation probes.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/SavedThreadOpenTest.kt`: saved rows must publish before optional coverage and held newest-page completion.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/SavedThreadFirstDrawDeviceTest.kt`: real persisted bytes and elapsed restore/snapshot/content/committed-frame phases; unchanged eight-case fixture and both negative controls.
- `docs/knowledge/features/thread-screen-testing-saved-first-draw.md` and its comparisons companion: isolated passes do not diagnose a historical suite miss; retain failed comparisons and measurement boundaries.

## Context

The reported in-depth run at `1fb92599b2df99b0380f48c1be8156bf0ffdfc75` executed 1609 cases, passed 1608 and failed the projection-hold method on a 1048 ms fragmented offline first draw. Original raw sweep logs are absent on this host. Obtain an unchanged comparison using the original selection in an isolated worktree, and use fresh worker/phase evidence to identify the bottleneck before choosing a repair. A passing comparison alone cannot establish historical causation. No decision record is required.

Overlap: #1947 changes a separate ViewModel settings block; #2009 carries identical readiness changes already reused on main. Neither is a dependency.

## Design source

N/A: performance repair only, with no change to visual design or the established committed-frame endpoint.

## Design

Candidate seam, subject to phase evidence: on the first delegate snapshot of each `observeThreadSnapshot` collection, an empty live list with no suppression can publish the immutable validated cache list directly. Keep the existing processing-dispatch boundary, snapshot metadata, generation allocation, writer ordering and deferred persistence. Every later snapshot and every initial nonempty or suppressed snapshot keeps the existing reconciliation path. The flag belongs to the collection, not the repository or conversation ID. No API, wire, serializer, storage, ViewModel or UI shape changes.

Confirm that snapshot preparation is material in the fresh comparison before implementing this candidate; record the demonstrated diagnosis and final contract in Revisions. Preserve exact row order, renderer keys, optional history validation and all marker anchors. The optimization removes redundant reconstruction of validated rows; it does not defer validation, truncate history or warm measured work.

## State and concurrency model

The initial-snapshot flag is local to the cold flow's sequential collector. Cache restoration stays on the injected IO dispatcher; preparation and later reconciliation stay on the injected processing dispatcher. Cancellation cannot publish from a held worker after exit. `ThreadWriter` remains collection-owned and flushes under its existing non-cancellable mutation/bookkeeping contract.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| Cold cache-only first snapshot and repeated collection for the same conversation | Traversal/identity regression in `CachedThreadWorkerTest`; all eight device cases |
| Initial suppression or initial nonempty live rows | Existing suppression and held-generation worker probes |
| Nonempty live snapshot followed by empty disconnect and reconnect | Existing `boundaryInvariant_suppressionAndDisconnectKeepUnsignedOrder` and identity overlap/replay probe |
| Held worker canceled during first or later preparation | Existing cancellation probe |
| Optional coverage/projection delayed, first open and fresh ViewModel on reopen | Both first-draw methods and `SavedThreadOpenTest` |

## Error handling

No new failure mode. Preserve cache read classification, row rejection, metadata validation and live reconciliation. Readiness exhaustion and draw misses remain failures. Preserve the cancellable 1100 ms projection hold and the 1000 ms committed-frame bound; the 3000 ms restore control must reject that identical bound.

## Testing strategy

Use a held-worker list-access probe to fail before the repair if initial validated cache rows are traversed/reconstructed before delivery, while proving that deferred writer traversal still runs on the worker. Cover repeated collections and exact objects/keys; retain the existing initial suppression, nonempty live merge, disconnect, unsigned order and cancellation tests. Run affected repository and saved-open/projection tests with fresh XML counts.

Run the complete four-method `SavedThreadFirstDrawDeviceTest`, inspecting executed counts and all eight phases for both draw methods. Device-only reason: real filesystem IO, worker CPU/queue delay and committed Android frames cannot be proved by Robolectric. Retain the unchanged in-depth comparison and run the identical `UI_GATE_FULL=1 UI_DEVICE_ALL=1 python3 scripts/android-test-gate.py ui` selection after repair. Preserve failures alongside passes, revision/configuration/source hashes, fresh XML and raw phone logs on the persistent agents evidence volume; commit only checked content-free summaries under test assets. No live-Claude or scripted scenario is needed for this internal saved-content preparation repair.

Run lint, assembly, Android-test compilation and formatting. Push before final post-main-merge assembly and `scripts/pre-verify.py --gradle`. The configured regular UI gate and later post-merge main sweep remain dispatcher-owned.

## Open Questions

- Does fresh phase evidence demonstrate a material snapshot bottleneck? Resolve before implementation; do not substitute a later pass for diagnosis.
- Did the same work cause the original 1048 ms miss? Its unavailable raw phase evidence may leave this historical question unresolved; retain that limit explicitly.

## Documentation handoff

Pending documentation stage: `docs/knowledge/features/thread-screen-testing-saved-first-draw.md`, saved-thread first draw: record the observed miss, diagnosis, repair and counted before/after evidence. Preserve previous misses and explicitly identify any unresolved historical cause. Consume retained builder/dispatcher artifacts without running comparisons.

## Revisions

2026-10-14: The unchanged in-depth comparison's delayed-projection method completed all eight cases within the bound, with fragmented restore/snapshot/content/draw tuples **369/522/538/641**, **51/126/145/222**, **316/385/409/487** and **44/201/228/318 ms**. Snapshot preparation contributes **69–157 ms** after readable rows; the first offline processing worker takes **161 wall / 103 CPU / 0 queue ms**, and the connected reopen worker takes **155 wall / 59 CPU / 0 queue ms**. This demonstrates redundant initial reconciliation as a material critical-path cost, without establishing the cause of the unavailable historical 1048 ms sample. No readiness or timing-harness defect is demonstrated.

The two focused unit probes executed and failed against unchanged production: the guarded 100000-row cache recorded **200000 accesses before delivery**, and the recollection probe detected a reconstructed list. Adopt the planned collection-local first-snapshot guard. It returns the validated cache list through the existing worker boundary only for an empty unsuppressed initial delegate snapshot, preserves that snapshot's unsigned order/receipt metadata, and leaves all later/nonempty/suppressed reconciliation and writer traversal intact. The historical-cause question remains unresolved; the measured-cost question is resolved by fresh phase evidence and the controlled traversal failure. Planned written work remains below 650 lines, with no exported API or consumer migration.

The unchanged full selection finished **1609 executed/passed, zero failures/errors, two existing skips**; all four saved-thread methods passed. The repair's affected nine-class unit selection passed **148/148**, including the two controlled probes. Its first candidate unit run had three failures because the added debug lifecycle event reached the Android log stub in a plain JVM fixture; the test now installs/restores the existing logger sink. That failed comparison remains retained alongside the original two regression failures.

The complete focused device class passed **4/4**, zero failures/errors/skips. Fragmented first-open row-to-snapshot intervals fell to **0–2 ms**; all 16 original/twin draws passed, with maxima **430/578 ms**. The slow restore control rejected the unchanged bound at **3480 ms**. No fixture, timer, threshold, projection hold or device test source changed. Raw evidence and hashes are retained under `$AGENTS_REPO_PATH/logs/evidence/2153/`, with checked summaries in `app/src/androidTest/assets/saved-first-draw-2153/`. The same two-shard repaired comparison and regular dispatcher gates remain pending at this point.

2026-10-14, final comparison: pushed candidate `6eb138928073c30ef41c5a064854bef7776b5dd5` passed the identical two-shard in-depth command with **1609 executed/passed, zero failures/errors, two existing skips**. All four named saved-thread methods executed once and passed. Original/twin maxima were **406/538 ms**; the twin's fragmented tuples were **419/420/444/538**, **44/60/73/154**, **294/295/309/399** and **46/56/86/158 ms**, reducing row-to-snapshot time from **69–157 ms to 1–16 ms**. Its direct initial publication worker takes 0 ms; the old first offline worker took 161 wall / 103 CPU / 0 queue ms. The slow control rejects the identical bound at **3080 ms**. All original/twin phases, raw XML, readiness/worker logs and verified hashes remain retained, without changing the device-test source. Final worker controls passed **10/10** with both cache-only/live cancellation and nonempty unsigned-order metadata on recollection. The final `origin/main` merge was already up to date at `3761e4da16953375a579c9b82aafb17c70a3b3b8`. The configured regular UI gate, later main sweep and knowledge documentation remain later-stage handoffs; the unavailable historical cause remains unresolved.
