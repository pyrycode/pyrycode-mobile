# Model reopen repair reconciliation (#2356)

## Files read

- `app/src/androidTest/assets/model-reopen-2351/investigation.txt`: historical branch/base failures, isolated diagnostic passes and saved-versus-applied limits.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_modelChange_roundTripsAndStaysPerConversation`, `openChatRowAfterCurrentQuery` and `openChatRow` preserve both reopens, assertions, restoration and the original acquisition budget.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/InitialAttentionQuery.kt`: `ThreadAcquisitionGate` fences the current query and distinct destination completion.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/ThreadQueryReadiness.kt`: `Receipt`, `wrap` and `ThreadQueryProgress` observe accepted requests and consumed terminals on the current physical source.
- `app/src/test/java/de/pyryco/mobile/e2e/ThreadQueryReadinessTest.kt`: cached-checkpoint, queued-reopening and observer-cleanup regressions.
- `app/src/test/java/de/pyryco/mobile/e2e/InitialAttentionQueryTest.kt`: before/after acquisition, ownership, deadline and fixture-retirement controls.
- `docs/recorded-tests.md`: capture/compatibility, replay limits, shared acquisition and accepted #2505/#2493 source/input evidence.
- `docs/knowledge/INDEX.md` and `docs/knowledge/features/development-verification.md`: verification ownership and evidence retention; repository documentation substitutes for unavailable QMD search.
- Accepted authoritative verifier records for #2505 (`2a5d3096ef22a1908f878ebb734ad4b050cae78d`) and #2493 (`07b3eaadbc8b20ca6a3fbe64987454a5fcbc9cee`): accepted configured counts and preserved failure dispositions.

## Design source

N/A: test reliability reconciliation; no application visual change.

## Change

Current base `ba8c54c3d` already includes #2505's repair and #2493's refreshed model recording. Reconcile their evidence with #2351 in a checked, content-free asset at `app/src/androidTest/assets/model-reopen-2356/reconciliation.txt`. Reuse the existing causal regressions and retain historical failures. The demonstrated mechanism is acquisition coalescing into an active query, followed by strict replay withholding later delivery behind the missing distinct request. This supports the historical hypothesis without establishing the exact original interleaving or a persistence defect. Do not change behavior or fixtures on the strength of a passing baseline. If focused execution demonstrates a residual defect, preserve it and reassess before expanding the causal seam.

The current helper waits for a consumed current query plus the same-generation certificate before real acquisition, then a distinct consumed destination query on that same source before Back. One monotonic 30-second budget includes row lookup, navigation, acquisition and completion. Exact X/Y saved model values, Y effort isolation, current-menu confirmation, applied-model distinctions, settings restoration, complete payload matching, causal delivery and full consumption remain intact. No diagnostic upstream read or collection is added. No fixture replacement or request-contract change is planned; historical reviewed CHANGED comparisons remain CHANGED.

## Scope assessment

One useful result: establish the existing model-reopen repair against current inputs. Forecast at most 250 written lines including this plan and evidence; zero production files, new exported types, signature migrations or new reject branches; three acceptance criteria. The existing repair and its regressions stay together. No in-flight branch overlaps the two proposed new paths. Shared scenario/helper files are read only.

## State transitions and identity reuse

No new state. Reuse `cachedCertificationCannotReleaseAcquisitionDuringTheCurrentQuery` for reopening during catch-up, `completedInitialQueryAcquiresWithoutAnotherWait` for reopening afterward, `queuedReopeningCannotSpendThePreviousCompletion` for queued distinct completion, and the existing observer-cleanup/source-retirement, generation and monotonic-deadline controls. Run both affected classes, preserving their executed counts.

## Testing strategy

- Inspect retained failing-before/passing-after #2505 controls and original #2351 failure diagnostics; record exact sources and evidence identities. Compare acquisition/readiness helpers and controls against the merged repair, and model source/fixture against accepted #2493. Old-input focused evidence cannot establish current-input focused acceptance.
- Run `ThreadQueryReadinessTest` and `InitialAttentionQueryTest` separately with `scripts/gradle-gate.py testDebugUnitTest --tests ...`, on macOS. No new logic requires a new duplicate regression.
- Run exactly `de.pyryco.mobile.recording.RecordedMobileSuiteTest#interactiveTurn_modelChange_roundTripsAndStaysPerConversation` with the focused `scripts/android-test-gate.py ui --tests ...` launcher. The existing test needs the native lifecycle, storage and replay scheduling; replay launches neither daemon nor Claude.
- Retain revision, fixture/index hashes, command, exit status, executed/passed/failed/error/skipped counts, raw XML, phone logs and reconstructed diagnostics in a private persistent agents evidence directory with a verified manifest. Commit only checked summaries under the new asset path.
- Run required lint, assembly, formatting and final `scripts/pre-verify.py --gradle` after integrating main; push before final checks. The configured dispatcher UI gate must execute/pass the exact model method on this candidate before verifier PASS. That later-stage acceptance stays pending in this builder's handoff. Linux is outside scope.

## Documentation handoff

Pending for the documentation stage: reconcile this ticket's diagnosis and acceptance in `docs/recorded-tests.md`, under “Shared current-query acquisition” and “Accepted shared-fixture evidence (#2505)”. Link the retained original failures and accepted repair evidence, state source/fixture differences and remaining historical uncertainty, and record counted focused/configured results with durable raw-artifact references. Documentation records supplied evidence; it does not produce missing acceptance.
