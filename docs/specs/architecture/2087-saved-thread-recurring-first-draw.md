# Saved-thread recurring first-draw timing (#2087)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/SavedThreadFirstDrawDeviceTest.kt`: `Probe`, `repository`, `openAndMeasure`, `awaitDeviceReadiness`; real disk, workers and committed-frame contract.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/FragmentedHistoryFixture.kt`: `fragmentedHistoryFixture`; complete 18000-row/36000-entry/18000-span fixture.
- `app/src/main/java/de/pyryco/mobile/data/cache/FileConversationCache.kt`: `readDecodedThread`, `DecodedThread`, `readHistoryPosition`; row decode and independent lazy coverage validation.
- `app/src/main/java/de/pyryco/mobile/data/repository/CachingConversationRepository.kt`: `observeThreadSnapshot`, `readHistoryPosition`; row publication, optional ordering and independent readers.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`: `historySeed`, `threadItems`, `threadContent`; concurrent restoration, fold and frame pacing.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadContentScheduling.kt`: `paceThreadContent`; production worker/frame scheduling.
- `docs/knowledge/features/thread-screen-testing-saved-first-draw.md`: previous misses and passing comparisons survive; neither readiness nor a focused pass proves a cause.
- `docs/knowledge/features/conversation-cache-layout.md`: exact bytes and path govern immutable decode reuse; captured history remains independent of future cache mutations.
- `docs/knowledge/features/caching-conversation-repository.md`: atomic suppression and saved unsigned order must survive a rows-first publication.
- `docs/specs/architecture/2050-saved-thread-residual-restore.md`: optional history dependency already removed; do not reimplement the prior repair.

## Design source

N/A: performance investigation with no visual criteria. The refiner explicitly confirms this exemption in the refinement-evidence comment on #2087. Preserve the shipped dark-fixture screen.

## Context

The retained full UI miss at `85a054e743711dd621b206f7369e84d6ba1a1948` drew all eight cases at 158/135/130/138/1051/1258/945/684 ms. It executed 258 tests, passed 257 and failed one, with zero errors and one skip; the delayed control passed. Its phase logcats are unavailable. Focused branch/main passes are counterevidence, not a diagnosis. Current source is `ea3ab23e7e14aac63e59a3d05d28473e3905323f`. No decision record is anticipated.

## Design

Run the existing affected class with disabled animations on the configured managed Android 13 device. Retain fresh XML, counted outcomes, revision/source identity and bounded content-free worker/readiness/phase logcats before another run overwrites them. Follow the missed interval into only the implicated existing saved-history code. Record the measured cause and smallest repair under Revisions before implementing production changes. A controlled regression must isolate that condition and fail before/pass after the repair; unmeasured contention, GC or lifecycle guesses cannot justify a correction.

Keep the timer before cold cache/repository and ViewModel construction, and its endpoint at the exact newest text wholly inside the viewport through frame commit. Keep every row/marker and request postcondition, all eight cases and the 3000 ms delayed control rejecting 1000 ms. No warmup, fixture reduction, retry acceptance, new storage, ordering change or visual redesign. Forecast including plan, temporary diagnostics and tests: about 650 written lines, zero new exported types, no signature migration, four criteria and no new reject-state machine.

Only #2085 overlaps the candidate `ThreadViewModel` and `ThreadScreen` files; its shown-version sight changes do not restructure the saved-history restore block. Keep any necessary edits local and additive.

## State and concurrency model

Retain injected IO/processing/projection dispatchers, mutex freshness checks, atomic snapshot suppression and existing ViewModel scope ownership. Each opening creates a new ViewModelStore; clearing it cancels restoration/fold/pacing and composition removes its draw observer. Diagnostics capture their opening Probe. No independent background scope is added.

## State transitions and identity reuse

| Event | Required regression |
| --- | --- |
| Cold ordinary/fragmented offline open | Existing eight-case first-draw method; exact rows, markers and zero asks |
| Reopen same repository and conversation | Existing eight-case method, fresh ViewModel/composition and committed frame |
| Connected first/reopen with held newest response | Existing eight-case method; one newest request per open, response still held |
| Delayed cold restore | Existing `slowRestore_negativeControlRejectsTheSameFirstDrawBound` |
| Diagnosed recurring condition | Add a controlled regression named for the established cause; document it under Revisions |
| Exit during pending work | Existing scope cancellation tests covering the implicated path, selected after diagnosis |

## Error handling

Preserve existing classified cache failure handling and independent metadata rejection. Evidence contains counts, static case names and timing/CPU fields only; never saved content or daemon text. Keep every failed sample alongside passing comparisons.

## Testing strategy

The baseline affected class and the eventual repaired class must actually execute both named methods, with fresh counted XML. Device-only reason: real disk, concurrent Android workers, GC and committed frames are not represented by Robolectric virtual time. Write and run the diagnosed regression red before production repair, then green; run affected existing focused unit classes, lint, assembleDebug, Android test compilation and forced Spotless. After the final main merge and push run assembleDebug and `scripts/pre-verify.py --gradle` against the reviewable PR body.

The fresh full configured UI gate is dispatcher-owned acceptance: both named methods executed/passed, command outcome, tested commit, fresh XML/counts and eight timings. A builder focused pass cannot replace it. No live/daemon scenario is needed for this investigation.

## Open Questions

- Which measured contributor accounts for the recurring missed interval? Resolve from a failure or controlled reproducer before selecting production changes.
- Which local regression isolates that condition without weakening the original first-draw proof? Resolve under Revisions.

## Documentation handoff

Pending documentation stage: `docs/knowledge/features/thread-screen-testing-saved-first-draw.md`, saved-thread first-draw evidence — record the diagnosed cause, correction, retained misses and passing comparisons, and fresh full UI-gate evidence. Builder and dispatcher produce runs; documentation only records them.

## Revisions

### 2026-10-11 — Controlled ready-coverage projection investigation

The baseline class at `d038af89f57ebe4b21816b32fe54aac729296797` exits 0, two executed/passed, zero failed/errors/skipped. Draw vector: 237/219/215/199/713/531/563/450 ms; delayed control 3481 ms. Evidence is retained in `/tmp/builder-2087/evidence/baseline-class/`. Fragmented reopens spend 234/217 ms from snapshot to complete content, versus 35/25 ms on first opens. Cold optional history continues after initial content; already-ready coverage instead enters blocking `threadContent` display projection. These are measured contributors, not proof of the original full-suite cause.

Add `readyCoverageCannotHoldFirstSavedRowsBehindProjection_firstOpenAndReopen` beside `savedRowsDoNotWaitForNewestResponse`. It restores the full fixture coverage before rows, holds only the existing injected projection dispatcher, and requires readable rows before release with exact markers afterward. It exercises both connection modes and repeated new ViewModels. If it fails at the row-publication assertion, compare a local first-content publication before optional projection, retaining evidence withholding and complete projected state afterward. Preserve projection on every later update and reset any first-publication state per collection.

The device twin `delayedOptionalProjection_cannotHoldFirstNewestDraw_firstOpenAndReopen` reuses the exact eight-case fixture/open/assertion helper, adding a cancellable 1100 ms delay only to the injected display-projection dispatcher. No cache/repository/ViewModel work is warmed or moved out of the timer. It records the deliberate hold separately from queue/wall/CPU time and retains all original requests, rows, anchors and frame/bound assertions. Run it red before changing production, then the complete class green. It proves the controlled projection-readiness dependency, not a contention or GC cause for the historical miss.

The controlled unit test executed/failed once at the expected row-publication assertion before production changes (also retained a bounded assertion rerun: one executed/failed). The first isolated device twin instead failed readiness before any Probe or projection; XML has one executed/failed, zero errors/skips, timestamp 2026-10-11T16:09:16. Retain this environmental precondition failure in `red-device-readiness/`; it proves no first-draw cause and is not accepted as a latency regression. Run the affected class for the controlled timing comparison, preserving all outcomes.
