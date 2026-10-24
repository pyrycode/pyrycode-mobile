# Saved-file compatibility fixture memory

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/SavedThreadFirstDrawDeviceTest.kt`: `measureAuthoritativeFiles`, `withAuthoritativeFileFixture`, `measureAuthoritativeFileOpening` and `installHost` own fixture generation, eight openings and destination disposal.
- `app/src/sharedTest/java/de/pyryco/mobile/di/SavedAuthoritativeDestination.kt`: `close` disposes the registry and bundle; each opening already clears its ViewModel owner and joins cancellation.
- `app/src/main/java/de/pyryco/mobile/data/cache/FileConversationCache.kt`: `readAuthoritativeThread` builds a separate owned decode, while writes consume the input list through snapshot validation.
- `app/src/main/java/de/pyryco/mobile/data/cache/SavedAuthoritativeThread.kt`: `ownedValidated` retains raw facts and their validated typed snapshot.
- `docs/knowledge/features/authoritative-reader-paging.md`: Current DB and file acceptance scope preserves exact file compatibility independently of positive file latency.
- `docs/knowledge/features/thread-screen-testing-saved-first-draw-comparisons.md`: earlier allocation work requires active-fixture-only preparation, real cold reads and unchanged frame endpoints.

## Context

The original #2505 gate on `12aeedf5a0` completed four ordinary arms in each named method, then exhausted the 192 MiB heap during the first fragmented offline restore. Suppressed OOM stacks identify `decodeReplacement` and `validThreadJson` beneath `ownedValidated`; both timed out waiting for the committed draw. The same-tree rerun completed eight arms per method. Original phone evidence is in verifier-2505's `ui-9u2sw_hq`; rerun evidence is in `ui-4l1wpkxh`. This is one memory-ownership repair with its regression, not two independent test failures.

The harness keeps all 36000 parsed input facts reachable through the expected `facts` list across fixture writing and every cold restore. The production file reader must simultaneously own another complete raw/typed snapshot. Eliminate that redundant retained graph without reducing any fixture or changing product ownership. A native retention control must confirm this suspected owner before repair, with heap diagnostics on both sides. No decision record is needed.

Remote overlap: historical branches #2009 (CPU readiness) and #2266 (legacy DB opening) touch this file; their changes do not restructure the current authoritative fixture generator, so there is no dependency.

## Design

Keep the expected list's exact size/order and indexed equality contract, but generate one deterministic fact per access instead of storing all parsed objects. Existing writers still validate and serialize all facts through production APIs. Every comparison still checks each restored raw fact, with one temporary expected object at a time. Repeated accesses must preserve numeric spelling and field values. No production change, exported type, dependency, cache reuse or destination lifecycle change is planned.

Add a focused native retention regression that holds the expected list and the persisted fixture exactly as the opening loop does. Sample weak references to its endpoint facts in a separate helper scope, establish a GC cycle with an independent collectible sentinel, then assert the samples are collectible while the list remains usable. Log heap usage and sampled retention without content. The eager baseline must fail this control before changing generation. This is a lifetime check outside all first-draw timing, not GC preparation for the measured openings.

## State and concurrency model

No new coroutine or lifecycle state. Existing fresh cache/repository/registry/ViewModel construction, worker dispatchers, cancellation, join and idle barriers remain intact. The generator is immutable and has no cached values.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| Fixture generation followed by restore with the expected list still live | `authoritativeFileFixture_doesNotRetainDecodedFactsAcrossOpenings` retains the list but rejects retained decoded endpoint objects. |
| Offline/held-newest first open and reopen, ordinary then fragmented | Both existing `authoritativeFileCompatibility` methods retain all eight arms, exact facts/rows/islands/read/request checks and fresh owners. |

## Error handling

No product failure branches change. The retention control reports retained sample count and heap use; existing assertion and draw failures remain failures. No retries, heap increase, fixture reduction or relaxed timeout/assertion is introduced.

## Testing strategy

Run the native retention control red against eager generation, then green after repair. Run the whole affected native class on macOS, preserving raw XML, per-method phone logs, source identity and counted results. Real ART heap behavior, disk I/O, background workers and frame commits require device execution. Preserve 36000 facts / 18000 islands / 18000 visible messages, numeric/ownership checks, zero retired API reads, exact read/request counts, CPU readiness, 20-second draw wait, DB timing bounds and actual slow-read controls. Full configured UI acceptance belongs to the dispatcher; Linux is outside this ticket's verification scope.

Run lint, assembly, instrumented compilation, formatting and final `pre-verify.py --gradle`. Forecast: approximately 180 written lines including this plan and tests, one source file, zero new exported types or consumer migrations, four acceptance criteria, no new state-machine rejection branches; within the refined 450-line estimate and sizing limits.

## Open Questions

The retention control and native heap diagnostics must establish the suspected fixture owner. If removing it is insufficient under focused evidence, investigate the remaining allocation/owner and record the revised contract before further repair. Submit a wider investigation only when focused evidence cannot establish the remaining cause.

## Revisions

2026-10-23: The native causal control on `c8f4156fb6c0a9c72cc4942231fac072f83db1b3` executed once and failed at the intended retention assertion: both sampled facts survived collection while the expected list remained live. Heap use after seeding was 65048296 bytes against a 201326592-byte limit. This identifies the harness's eager expected list as a retained owner; the repair follows the planned on-demand list design. Raw XML, phone diagnostics and source are retained in `$AGENTS_REPO_PATH/logs/evidence/2508/builder-20261023/red-c8f4156/`, manifest SHA-256 `c65aec97040a17ee4e49d7c2a8547d6b183a507eccf4d25b8f4c17e31838855c`. Original #2505 failures and rerun remain separately retained under `original/`. The repaired control and complete affected-class result remain to be read before handoff.

2026-10-23: Resolved the remaining memory question on repaired source `0c82cc9f3fc6c733740b00b8527276bf667652b2`. Native `ui-igw818xs` executed/passed all 8 methods, zero failures/errors/skips. The control retained zero samples and used 4289976 bytes after seeding under the unchanged 201326592-byte heap limit. Both compatibility methods completed all eight arms, retaining exact 36000 facts / 18000 islands / 18000 visible rows, read/request checks and no OOM. Normal DB committed frames were 116/143/130/123 ms; both actual 3000 ms slow-read controls rejected the unchanged 2000 ms SwiftShader bound. File latency remained `required=false`, with one 3092 ms file frame correctly outside the independent #2456 positive benchmark contract. No production repair or wider investigation is needed from the focused evidence. Raw XML, all phone logs, exact source and APK hashes are in `$AGENTS_REPO_PATH/logs/evidence/2508/builder-20261023/green-0c82cc9/`, verified 13-file manifest SHA-256 `a0ace4c19cf75558ebfddea1f6b606868c509168852cb72233064ff16cabbd86`. Complete configured UI acceptance remains dispatcher-owned and pending.
