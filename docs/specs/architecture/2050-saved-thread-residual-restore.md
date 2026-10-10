# Residual saved-thread first draw (#2050)

## Files read

- `SavedThreadFirstDrawDeviceTest.kt`: `Probe`, `repository`, `openAndMeasure`, `awaitDeviceReadiness`; cold timing, worker instrumentation and exact committed-frame endpoint.
- `FileConversationCache.kt`: `readDecodedThread`, `decodeThreadRecord`, `validatedRows`, `validatedHistory`, `cachedThreadRowProof`; mandatory validation and exact-byte reuse.
- `HistoryCoverage.kt`: `retainedBy`, `historyRowProofs`; content-bound coverage cannot be bypassed.
- `CachingConversationRepository.kt`: `observeThreadSnapshot`, `readHistoryPosition`; restore ordering and multiple consumers of one decoded document.
- `docs/knowledge/features/thread-screen-testing.md`: retain misses alongside passes and separate isolated proof from regular gates.
- `docs/knowledge/features/conversation-cache.md` and `conversation-cache-layout.md`: portable storage, immutable decode ownership, canonical persisted bytes and metadata independence.
- `docs/knowledge/features/caching-conversation-repository.md`: row/position ordering and cancellation ownership.
- `docs/specs/architecture/2039-saved-thread-residual-first-draw.md`: readiness and viewport guards remain baseline, earlier accepted sample remains counterevidence.

## Context

The build-hp-1 main comparison at `c88a07d6764c5528646eebeb3deb72a9124498c9` misses fragmented first opens at 1630/1388 ms. The supplied offline restore worker takes 904 ms wall / 851 ms CPU with zero queue delay; read/decode/validation/metadata/proofs take 5/496/28/39/331 ms. Connected restore takes 810 wall / 783 CPU, phases 7/414/23/40/320 ms. These samples establish substantial cold-path CPU cost rather than worker queue backlog, but do not yet identify its smallest sufficient repair. Earlier #2039 passes remain counterevidence. No decision record is anticipated.

## Design

Diagnose on build-hp-1's existing Mobile container and managed Pixel 2 API 33 ATD. Use the existing FIFO `device_hold` and `run_on_device` report collection, disabled animations and isolated selections. Preserve supplied comparisons and every new attempt. Compare one local cost at a time and record the established cause and selected repair under Revisions before implementing it. Temporary content-free diagnostics may isolate decode, proof and projection cost; remove diagnostic-only candidates before acceptance.

Preserve all eight ordinary/fragmented, offline/held-newest, first/reopen cases; 20 ordinary messages, 18000 displayed fragmented rows, 36000 durable entries, 18000 spans, exact rows and anchors, offline zero/connected one newest request per opening and the held response. The 1000 ms monotonic interval starts before cold construction and ends after exact newest text wholly in the viewport commits. Reopens keep only their mode's repository and use fresh ViewModel/composition. Preserve full persistence validation, history semantics and encoding. Do not integrate #2042's additive decoder.

Initial candidate surface is cache restore/proof processing, with at most three production files and local tests. The remote build directory is a test snapshot of this worktree, not a second implementation branch. No candidate-file overlap was found among fetched numeric feature branches. Forecast including plan, diagnostics and tests: about 700 lines, no exported types or consumer signature migration, four acceptance criteria and no new state-machine rejection branches. Reassess after diagnosis against the 1600-line ceiling.

## State and concurrency model

Existing IO reads remain under the cache mutex on the injected dispatcher. Repository work remains on its injected processing dispatcher. ViewModelStore clears each opening's jobs and composition disposes the observer. Diagnostic workers retain the queued Probe; no unowned scope, warming or work outside the measured interval is added.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| Fresh offline cache/repository | Existing named first-draw method, both fixtures |
| Fresh held-connected root | Same method, exact rows/anchors and one held request |
| Reopen with same repository | Same method with fresh ViewModel/composition in both modes |
| Opening cleared before next opening | Existing lifecycle logs and all eight cases |
| Delayed cold restore | `slowRestore_negativeControlRejectsTheSameFirstDrawBound`, unchanged 3000 ms delay |

| Empty live snapshot with metadata held | `readableRowsDoNotWaitForOptionalHistoryButLiveMergeDoes`; `savedRowsDoNotWaitForNewestResponse` holds history separately before asserting exact ViewModel rows |
| First live rows after early cache draw | `readableRowsDoNotWaitForOptionalHistoryButLiveMergeDoes` requires saved unsigned order before merging |
| Cancel pending metadata merge, then reopen | `repeatedRowsOnlyOpenKeepsSuppressionAndCancelsPendingOrderRead` |
| Same-size replacement, malformed replacement, removal/reused id | Existing `DecodedThreadRestoreTest` exact-byte invalidation cases |

Snapshot-local lazy history is shared only for exactly matching bytes. Mutation invalidates future reuse; an in-flight read owns its immutable captured snapshot and cannot republish it into the cache.

## Error handling

Preserve malformed row rejection, graceful reads and independent optional metadata rejection. Keep complete proofs and document freshness. Setup failures and zero-test runs are unverified, never timing passes. Retain all misses; a passing retry alone does not establish cause.

## Testing strategy

The supplied main red method supplies the initial regression; obtain a controlled baseline and candidate on the same build-hp-1 device. Add a focused regression or before/after proof exercising the established cause. Device-only reason: real IO, Android worker CPU/scheduling, GC and committed frames cannot be proved by Robolectric virtual time. Run affected existing cache/history unit tests when their production path changes.

After repair, run a fixed sample of five isolated first-draw methods, one isolated slow control and one whole class. Retain revision, command, exit, fresh XML, selected logcat, counts including errors/skips and all case phases for every attempt. Run focused checks, lint, assembly, Android-test compilation and forced Spotless. Commit/push before final assembly and `scripts/pre-verify.py --gradle` after the last main merge.

Dispatcher handoff: configured UI gate must execute/pass both named methods with nonzero method counts and suite counts. Configured scripted-all must pass with counts; it does not execute these methods. No real-Claude scenario is required.

## Open Questions

- Which cold-path CPU cost is causally established, and which smallest repair provides sufficient margin on build-hp-1? Resolve under Revisions with the retained controlled comparison.

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/thread-screen-testing.md`, “Allocation margin and retained evidence (#2018)”, with established residual cause, repair, retained misses/comparisons and counted builder/dispatcher evidence. Distinguish isolated evidence from regular gates. Documentation records results; it does not obtain device runs.

## Revisions

### 2026-10-10 — Narrow canonical timestamp candidate

The supplied restore measurements show 320–331 ms proof cost on build-hp-1. Compare only ordinary-message canonical timestamp formatting in `toRecord`, using a whole-second four-digit-year formatter and the unchanged original formatter for fractions/extended years. Full serialization, SHA-256, persistence validation and bytes remain required. `ThreadInstantTextTest` compares all 18000 fixture instants, calendar boundaries and fallback forms against the original formatter; its initial compile fails on the missing helper (zero executed). Existing hash compatibility tests compare encoded rows and independent hashes. This is a candidate, not an established repair, until the controlled device phases show its contribution. A temporary opt-in sampled trace of the original path is diagnostic only and must be removed before acceptance.

### 2026-10-10 — Reject timestamp candidate; compare digest providers

Fresh baseline at the plan commit fails one executed test (zero errors/skips), draws 917/551/259/504/2212/1246/2075/854 ms in the original case order. The diagnostic-only sampled trace also fails, draws 932/507/330/302/3095/954/2337/739 ms. Retain both under `/tmp/builder-2050/evidence/`; tracing perturbs execution and cannot prove acceptance. The trace attributes only 44 ms sampled CPU to timestamp formatting versus 432 ms in `historyRowProofs`, so remove the timestamp candidate instead of shipping an insufficient speculative retune. Its 23 compatibility/cache tests passed.

The same trace records 117/53/48 ms exclusive CPU in native digest final/update/init. Compare the existing vendored `SHA256MessageDigest` against the Android provider in a separate diagnostic device method before choosing a production provider. Keep exact SHA-256 and canonical bytes. A narrow factory adapts the vendored implementation's explicit-reset requirement to independent `MessageDigest` calls; test padding boundaries, incremental writes, output-buffer digest and repeat reuse against the platform oracle. The initial test compile fails on the missing factory (zero executed). No provider is integrated into restore until the device comparison establishes its benefit. No new dependency or crypto protocol change is involved.

Read-only host samples observe quota `800000 100000`, substantial concurrent load and periods of quota throttling while guest readiness passes. This remains an environmental contributor to investigate, not a proven explanation of all misses. Confirmed AVD config remains Pixel 2, API 33, google-atd, x86_64, two cores; the misleading `pixel8Api35` additional-output directory does not change the actual device.

### 2026-10-10 — Reject vendored digest; measure strict decimal parsing

The digest comparison passes one diagnostic method, but the platform takes 33–47 ms CPU versus 45–319 ms for vendored SHA-256 over 18000 operations at 32/136/512 bytes. Remove the unused adapter and diagnostic test. A quiet-host baseline still fails (one executed, zero errors/skips): draws 599/311/238/230/1711/803/1655/612 ms, with guest readiness passing and negligible worker queue delay. Host quota contention is not sufficient to explain the failure.

Compare a single-pass canonical unsigned decimal parser in `ReadMarkIdSerializer`. Preserve quoted-token rejection, digits only, no leading zeros, and the full uint64 range; the existing numeric representation and protocol remain unchanged. `ReadMarkDecimalTest` checks range boundaries, 10000 deterministic values and malformed/overflowing tokens. The initial compile fails on the missing parser (zero executed); the implementation and existing restore/hash compatibility tests pass. This remains a measured candidate until a fresh isolated device comparison establishes its effect. Remove opt-in tracing before that comparison.

### 2026-10-10 — Separate readable content from optional coverage work

The quiet baseline spends 899/991 ms wall and 875/904 ms CPU in cold restore before any cached snapshot. Row readability and optional coverage are independent contracts, but both `FileConversationCache.readThread` and `CachingConversationRepository.observeThreadSnapshot` currently wait for coverage decoding/proof verification. Compare a rows-first decode: validate the entire versioned document and all rows, retain the immutable exact bytes, and lazily decode/validate/bind optional history from those same bytes outside the row mutex. No claim is published until its original validation and proofs finish. Exact-byte freshness checks and mutation invalidation remain.

An empty live snapshot may render validated cached rows with its atomic suppression immediately; the first nonempty live merge still awaits saved order before merging. Coverage remains restored through the existing ViewModel seed. No early subscription, duplicate subscription, network ask, background scope, or trusted unvalidated metadata is introduced. The device test keeps its original committed-frame endpoint, then waits for the independent coverage postcondition before asserting all exact marker anchors. This wait is after the recorded draw and cannot turn a late draw into a pass.

`readableRowsDoNotWaitForOptionalHistoryButLiveMergeDoes` holds metadata pending, requires saved rows and suppression, then requires a live merge to await release. Existing worker cancellation/replay/suppression tests, decoded-cache replacement/malformed-row/metadata tests and `SavedThreadOpenTest` cover the surrounding contracts. New snapshot-local lazy work has no lifecycle job; cancellation still owns the calling coroutine, mutations discard only future reuse, and an in-flight read linearizes at the captured immutable bytes. Repeated/reopened reads share validation for identical bytes; changed/removed documents never reuse it.

### 2026-10-10 — Keep final candidate limited to cache and repository

Keep the parser-only and parser-plus-rows-first comparisons as diagnostics. The final candidate retains the original strict unsigned serializer and provider; only independent validated-row restoration changes. This isolates the causal dependency without combining a speculative numeric retune with the architectural repair. Remove the decimal helper and its diagnostic-only tests, retaining their source diff and passed results in evidence. Eliminate the unnecessary empty-order worker dispatch; real saved-order resolution and merges still run on the injected worker. The affected worker/ViewModel checks pass (10 executed, zero failed/errors/skipped).

The first implementation and last main merge are pushed. Focused checks have passed 36 and 76 tests respectively with zero failures/errors/skips; required lint, assembly and Android-test compile passed. Post-merge `pre-verify.py --gradle` passed, including forced formatting and all Kotlin source sets. Device comparisons remain queued; none is claimed as acceptance. Fixed acceptance must use the final rows-only candidate.

### 2026-10-10 — Controlled critical-path comparison

The parser-only comparison fails one executed method (zero errors/skips), draws 581/228/213/197/1800/799/1849/929 ms; its fragmented restore workers take 957/1116 ms wall and 914/1054 ms CPU with zero queue delay. The rows-first comparison passes one executed method (zero errors/skips), draws 762/262/294/277/726/529/551/555 ms. Fragmented rows restore at 407/315 ms, snapshots at 550/409 ms and complete content at 577/425 ms. Optional history continues for 1220/1089 ms wall and 1094/986 ms CPU on the restore worker, with all exact anchors checked afterward. This demonstrates removal of a blocking dependency rather than an incidental reduction in total worker cost.

The quiet-baseline offline miss has no app GC during its first-draw interval; the held-connected miss overlaps concurrent app collections with microsecond pauses. The rows-first pass also overlaps app GC, including a 28.7 ms young-collection pause on the offline first open. All eight install/clear events are present in both comparisons; no lifecycle failure, ANR, warmed timed construction or queue backlog explains away the retained misses. Host quota contention contributes variance, but the quiet miss and CPU/queue measurements establish product work on the critical path.

The final candidate removes the numeric experiment and retains only independent validated-row restoration and deferred saved ordering for live merges. Its production/timing source SHA-256 values match the dedicated final device snapshot. Final lint, assembly and post-merge `pre-verify.py --gradle` pass; minimal affected unit checks pass 35 tests with zero failures/errors/skips. The fixed acceptance sample is still required before claiming sufficient margin or opening the PR.

### 2026-10-10 — Retain the first fixed-sample miss

Acceptance attempt 1 on the rows-only candidate fails one executed method, zero errors/skips. Draws are 1271/352/390/359/844/596/550/494 ms: all fragmented cases meet the bound, but the first ordinary offline case does not. That case has rows decoded within the first 100 ms (46 ms worker wall / 11 ms CPU), yet the row-reader return records 715 ms, snapshot 801 ms, complete content 822 ms and draw 1271 ms; installation is at 119 ms. The new miss therefore requires a separate explanation from fragmented cold metadata cost. Continue and retain all seven fixed invocations; no passing retry erases this miss and sufficient margin remains unproven.

### 2026-10-10 — Fixed acceptance sample remains red

The final rows-only candidate at `f2b8c642f` completes all seven fixed invocations; none is discarded. First-draw attempts 1–5 execute one method each with failed/passed outcomes 1/0, 0/1, 0/1, 0/1, 0/1; all have zero errors/skips. Attempts 2–5 draw 557/217/207/190/722/572/543/470, 504/227/201/210/691/567/555/479, 532/249/213/174/683/554/527/491 and 521/216/209/278/754/591/575/512 ms respectively, in the original case order.

The isolated slow control executes one method and fails a 30000 ms readiness timeout before any Probe or installed opening, with zero errors/skips. Retained windows show 3–48% guest idle and concurrent Google-services activity; the control's 3000 ms restore never begins, so this is not a successful negative-control proof. The whole-class invocation executes and passes both methods with zero failures/errors/skips. Its control draws at 3544 ms and rejects the same bound; ordinary/fragmented draws are 272/216/208/196/671/613/573/511 ms. Its ordinary case follows the control, so it is not an isolated cold-process replacement for attempt 1.

Fresh XML, command, revision, exit, all case timings and selected logcat for every invocation are retained in the publishing evidence folder with XML SHA-256 values. `fixed-sample.json` records all seven exits. The ordinary miss includes a 34-frame skip after rows are decoded, and device package/Google-services work during the interval. That correlation does not distinguish main-thread app CPU from scheduling well enough to justify changing the test or ViewModel subscription contract. A separate diagnostic ordinary-main sampling trace is being collected; no final source/assertion changes or passing retries erase these failures. Acceptance criterion 4 is not met, and this branch must not be described as ready for verification solely because fragmented cases now pass.

### 2026-10-10 — Ordinary-main diagnostic does not establish the remaining miss

A separate snapshot of `f2b8c642f` adds sampled tracing only to the first ordinary offline open, without moving its Probe or endpoint. It executes and passes one first-draw method, zero failures/errors/skips; draws are 644/233/253/361/861/823/954/727 ms. The dual-clock trace has no overflow and captures 597 ms main-thread CPU over 754 ms wall (including teardown), primarily Compose test-clock/frame composition and layout; worker CPU is about 20 ms. The captured passing cold render is substantial main-thread work, but it is not a CPU/scheduling measurement of the retained 1271 ms miss. Do not infer that Google-services correlation or a passing retry establishes an environment defect, and do not change subscriptions, warm the composition, or relax readiness/bounds without such evidence.

The implementation, regressions and all retained comparisons are recoverable on `feature/2050`; final rows-only focused tests, lint, assembly, Kotlin compiles and post-merge pre-verification pass. The builder-owned fixed device proof is incomplete/red, so no successful PR handoff is claimed. Remaining work is to diagnose/repair the ordinary cold main-thread miss and readiness timeout, then obtain a green fixed sample while retaining these attempts. Configured dispatcher UI/scripted-all gates and documentation remain explicitly pending later-stage work.
