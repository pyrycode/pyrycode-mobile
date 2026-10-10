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

Any repair introducing retained state must add its invalidation/reuse regression before handoff.

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
