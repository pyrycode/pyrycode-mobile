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

## Revisions

### 2026-10-24: bounded configured-gate recovery

The orchestrator returned the interrupted configured gate at `07bb54768067089f9cd7433b8472fb3c184c1ba3` for focused recovery. Its exact model method passed, but the reconnect-footer method timed out with replacement-source catch-up request 8/checkpoint 14 unmatched after request 4/checkpoint 0. Ping has no completed result. Preserve both failures and all earlier model evidence. Current repair base `cc381adc5397298adfbeda1a5a4a11b5073dd992` merges main without changing these scenarios, acquisition helpers, IME rule or recordings.

Additional files read: `InteractiveStreamE2ETest.interactiveTurn_reopenAfterReconnect_footerShowsContextUsageBeforeAnyTurn` still uses ungated `openChatRow`; `TestImeRule.select` performs shell reads and `waitForIdleSync`; `RecordedMobileSuiteTest` delegates the exact native methods. Existing acquisition controls demonstrate coalescing while a query is active and strict completion after it. QMD is unavailable; the recorded-test contract and repository feature topics supply the documentation search fallback.

First reproduce the exact reconnect method unchanged. If the missing request and reading timeout share the existing acquisition mechanism, reuse `openChatRowAfterCurrentQuery` for this one reopen. Keep replacement repository identity assertions, genuine context readings, all request payloads and causal delivery intact. Reuse its single monotonic 30-second navigation/acquisition/completion budget. Add a focused replacement-source control for acquisition during versus after consumed completion; retiring an old fixture must not certify its replacement. Do not change production coalescing or recording bytes. A different causal seam requires scope reassessment.

Investigate ping separately with its exact selector. Add content-free stage diagnostics and a single bounded delayed instrumentation/main-thread stack and IME-state sample around `TestImeRule.select`, without altering its work or deadlines. The diagnostic coroutine belongs to the call and is cancelled when selection returns. Do not repair IME or lifecycle behavior without a reproduced boundary. If isolation passes, request assessment of the retained interrupted-retrieval-to-ping sequence, preserving the original unfinished result. Wider execution and complete UI/scripted acceptance belong to the dispatcher.

Scope forecast: 194 existing written lines plus at most 300 additional lines of test-only repair, controls, diagnostics and evidence. No production file, exported type, signature migration or new application reject branch. This is the bounded same-mechanism recovery authorized by the orchestrator; an independently useful different repair returns for assessment. Remote branches overlap `InteractiveStreamE2ETest` (#1682, #1689, #1690, #1691, #1693, #1695, #1766, #1869, #1879, #1947, #2009, #2032, #2052, #2057, #2088–#2092, #2098–#2100, #2102, #2104, #2112, #2114, #2117–#2119, #2138, #2139, #2175, #2311, #2462, #2490); keep edits local to the named scenario.

Additional state transitions and checks:

| Event | Focused check |
| --- | --- |
| Replacement source acquires during/after its query | Replacement acquisition control, existing before/after controls, exact reconnect native method |
| Old source retires after replacement registration | Existing `oldFixtureTeardownPreservesReadyReplacement` and new replacement control |
| IME selection starts, blocks or returns after recreation | Exact ping method with one delayed stack/IME sample and stage logs; no acceptance retry assumption |

Run affected readiness/acquisition unit classes, the exact reconnect and ping native methods, and the model method after a shared scenario change. Retain all attempts with source/fixture hashes, raw XML/logcat, counted outcomes and cleanup results. Required lint/build/format and final pre-verify remain mandatory. Extend the pending documentation handoff with this interrupted configured result, the separate model pass, recovery evidence and any pending sequence assessment.

The exact unrepaired reconnect replay passed 1/1; it is not repair evidence. `replacementSourceAcquiresDuringAndAfterItsConsumedTerminal` then failed 1/1 through the unfenced provider path at the assertion that acquisition must wait for consumed completion. The adopted path wraps that same provider with the existing gate and checks both schedules, strict requests/full consumption, distinct downstream completion and old-source retirement. The native scenario now uses the existing helper at its one reopen, with passive content-free before/after tape diagnostics. No new readiness algorithm is introduced. Inspection of the retained native exception stack locates its primary timeout in `SuiteConnections.control` through `LiveSessionCleanupRule.endWhileActivityAlive`, after `terminal_preparation_started`; the body had already returned. The missing request explains blocked test-end preparation, not a separately demonstrated context-reading timeout. Keep this correction and the limits of historical attribution in the evidence handoff.

Focused acquisition/readiness controls passed 18/18 and 12/12. Isolated ping passed 1/1 and IME selection completed in under one second; the delayed blocked-state probe was cancelled. No IME behavior repair is justified. Request shared assessment of the retained interrupted-retrieval-to-ping sequence on the pushed source candidate; preserve that question and complete configured coverage as pending. Do not infer a cleared transient cause or retry the complete suite from this focused pass.

Clean source `4574645e3a2227ffcef12f74ca912ae42c51ac8f` passed the repaired reconnect method 1/1 and exact model method 1/1. The reconnect diagnostics establish initially false readiness followed by consumed request 8/checkpoint 14 and no pending query; all seven final model diagnostic records are complete. No further executable change was needed. The final tracked diff is below 450 written lines; including private retention/publishing helpers remains below 650, within every sizing boundary. Preserve the controlled old-path red, unrepaired native baseline and original incomplete gate separately. Complete configured acceptance and the narrow sequence assessment remain pending; final checks and request identity are recorded in the PR.

### 2026-10-24: admitted interrupted-retrieval → ping sequence

Recovery verified the preserved evidence and authorized only the two named methods in one admitted single-shard instrumentation invocation. Current base `26dfc30b0` preserves candidate `5395dc663` and merges documentation-only main changes. The original ping remains unfinished; the isolated pass establishes neither a repair nor a transient cause. Reconnect's cleanup timeout stays separate.

`TestImeRule.select` currently starts diagnostics after the settings/service reads and cancels them before the first Compose wait. Extend this existing diagnostic seam before running the sequence. Start its owned coroutine before either read, retain it until restoration finishes, and mark settings/service reads, shell submission/completion, idle synchronization, first Compose wait and restoration. Include monotonic timing and process identity. Emit at most four instrumentation/main-thread stack samples, each at most 24 frames per thread, after a phase has remained unchanged for five seconds. Diagnostic sampling performs no extra shell, settings or service reads that could itself stall. Cancel the coroutine in the rule's final cleanup, including exceptional restoration. No waits, assertions, fixture bytes or request behavior change.

Wrap ping's existing first `awaitChannelList` with the diagnostic phase observer. This helper has one consumer and stays with this investigation; all other IME callers keep their behavior. Native execution is required for the real IME, shell and instrumentation/main-thread synchronization. Verify phase coverage, actual method order and one instrumentation PID from raw logs, and retain fresh XML/logcat, commands, exit/counts, source/fixture identities and cleanup. No additional application repair is justified without a reproduced causal boundary. If the pair passes, submit the still-unresolved sequence/load question for assessment without widening execution.

Completed work remains below 650 written lines including private retention helpers; this bounded diagnostic/evidence extension forecasts at most 300 more, below all numeric limits. No production changes, exported type, consumer migration or application reject branch. Preserve existing controls and focused model/reconnect evidence after explicit source/fixture equivalence checks. Re-run affected controls, lint, assembly, native compilation, format and final pre-verify. Complete configured UI/scripted acceptance, independent review and the documentation handoff remain pending. Add this sequence's limits and counted outcome to the handoff.
