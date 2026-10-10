# Saved-thread residual first-draw latency (#2039)

## Files read

- `SavedThreadFirstDrawDeviceTest.kt`: `Probe`, `repository`, `openAndMeasure`, `withFixture`, `withFixtureCopy`; cold construction, worker diagnostics and exact committed-frame endpoint.
- `FileConversationCache.kt`: `readDecodedThread`, `decodeThreadRecord`, `validatedRows`, `validatedHistory`; complete persistence validation and exact-byte decode reuse.
- `CachingConversationRepository.kt`: `observeThreadSnapshot`, `ThreadWriter`; worker projection, cold observation and cancellation flush.
- `HistoryCoverage.kt`: `retainedBy`; content proofs remain mandatory on restored metadata.
- `docs/knowledge/features/thread-screen-testing.md`: Allocation margin and retained evidence; retain isolated misses and distinguish them from dispatcher regular UI results.
- `docs/knowledge/features/conversation-cache.md`: per-instance locking, immutable decode ownership and app-private storage.
- `docs/knowledge/features/caching-conversation-repository-testing.md`: controlled workers and fresh-file regressions protect history and cancellation semantics.
- `docs/specs/architecture/2027-in-depth-saved-thread-first-draw.md`: fixture byte copies and emulator readiness are insufficient explanations for every residual miss.
- Retained `builder-1869/post-2027-evidence`: three main baseline passes, two main misses, and branch misses; baseline-5 restore worker used 970 ms wall / 504 ms CPU with concurrent app and system GC.

## Context

Main at `d3ecf87758e20f97e5226f5c52934c551c14a128` contains #2027 but still misses the unchanged 1000 ms first-draw bound. Neither a passing repeat nor a Bluetooth OFF snapshot identifies the residual cause. Diagnose the timed path through controlled comparison before choosing the smallest local repair. No decision record is expected.

## Design

Reuse the existing eight-case device method as the initial red regression. Correlate cumulative restore/snapshot/complete-content/committed-draw phases with worker queue/wall/CPU durations, app/system GC and opening lifecycle. Add content-free diagnostic measurements only where existing logs leave a causal gap. Compare one variable at a time and retain every pass and miss with command, revision, exit, fresh XML and selected logs. Record the established cause and selected repair under Revisions before implementing that repair.

Keep the source fixtures and independent byte copies, 20 ordinary messages, 18000 displayed fragmented rows, 36000 durable entries and 18000 spans. First open starts its monotonic timer before fresh cache/repository construction and restore. Reopening keeps only that opening mode's repository and creates a fresh ViewModel and composition. Require exact saved rows, all marker anchors, zero offline requests, one held connected newest-page request per opening and an uncompleted held response. Exact newest text wholly in the viewport through its committed frame remains the endpoint. No smaller fixture, warmed timed work, deadline increase, later timer, earlier endpoint, ignored assertion or discarded retry.

Prefer an allocation/work repair local to one production file or a demonstrated fixture/lifecycle repair local to test support. If diagnosis requires a broader contract or an agents-owned environment change, reassess scope rather than patching unrelated code. No in-flight feature branch overlaps either initial candidate file. Forecast: about 600 total written lines, at most one production file, 0–1 exported types, no consumer migration, four acceptance criteria and no new state-machine reject branches; recheck after diagnosis.

## State and concurrency model

Existing cache reads run on injected IO workers under the cache mutex; repository processing runs on injected Default workers. The existing ViewModel owns its jobs, cleared through `ViewModelStore`, and composition disposes its draw observer. Diagnostic dispatch wrappers retain the probe captured when work is queued. No new unowned scope or job is introduced.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| First offline open with fresh cache/repository | Existing named first-draw method, ordinary and fragmented cases |
| First held-connected open in independent root | Same method; exact rows/anchors and one held newest request |
| Reopen same repository, fresh ViewModel/composition | Same method, both fixtures and modes |
| Teardown followed by next opening | Opening lifecycle logs and same method; investigate any cross-opening work |
| Slow restore in the identical cold path | `slowRestore_negativeControlRejectsTheSameFirstDrawBound`, 3000 ms delay rejects 1000 ms bound |

Any repair adding lifecycle or identity state must add its controlled test here before handoff.

## Error handling

Preserve graceful malformed-cache handling and complete row/history/proof validation. Device setup or zero-test failures are environment evidence, never passes. Timing misses remain failing assertions after all eight cases are measured. Diagnostics contain static fixture labels, counts and durations only.

## Testing strategy

Device-only reason: real file I/O, Android worker scheduling, GC and committed frames cannot be established using Robolectric virtual time. Watch the existing method fail on a #2027-containing base before repair. Add a focused regression or controlled before/after proof exercising the diagnosed cause. Run relevant cache/history unit regressions if production restore changes.

After repair run a fixed sample of five isolated first-draw executions, one isolated negative control and one whole-class execution on the same configured Pixel 2 API 33 ATD with existing FIFO coordination and disabled animations. Retain every attempt, including failed comparisons, with nonzero executed/passed/failed/error/skipped counts and every cumulative case timing. The unchanged slow control must reject the bound. Run focused unit checks, lint, assembleDebug, Android-test compilation and forced formatting. Push before final assemble and `scripts/pre-verify.py --gradle` after the last merge of main.

Dispatcher handoff: the configured regular UI gate must separately execute and pass both named methods with nonzero method counts and suite executed/failed/skipped counts. No in-depth comparison substitutes for that gate. No daemon or Claude scenario is required.

## Open Questions

- Which residual timed cost is causally established, and does its smallest repair belong to production, fixture/lifecycle support or the environment? Resolve through controlled comparison under Revisions.

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/thread-screen-testing.md`, “Allocation margin and retained evidence (#2018)”, with the established residual cause, repair, retained misses and passing comparisons, and counted builder/dispatcher results. Distinguish configured regular UI results from in-depth evidence. Documentation records evidence; it does not obtain device runs.
