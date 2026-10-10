# #2051 — Diagnose scanner managed-device interruption

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/ScannerFrameTest.kt`: `checkFrame` covers both themes, geometry, callbacks and screenshots; the enlarged method is independent.
- `app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/ScreenshotCapture.kt`: `saveScreenshot` uses the existing bounded `captureWithRetry`; Compose timeouts alone do not diagnose emulator disappearance.
- `app/build.gradle.kts`: managed `pixel2Api33Atd` uses API 33 Google ATD and the custom instrumentation runner.
- `gradle.properties`: existing Gradle memory and cache configuration.
- `scripts/android-test-gate.py`: compatibility entry point delegates execution to the agents repository.
- `docs/knowledge/features/scanner-screen.md` and `scanner-screen-edge-cases-and-testing.md`: scanner geometry, separate live-camera acceptance and independent callback coverage.
- `docs/knowledge/features/development-verification-compose-evidence.md`: capture timeout history establishes a different failure mode; no additional retries are justified here.
- `$AGENTS_REPO_PATH/builder/device-tests.md`: selected-method/class commands and XML-based execution evidence.

## Context

Historical runs on PR #2021 and its merge base lost the emulator after the enlarged scanner method started. Their artifacts no longer exist. This ticket diagnoses ownership from fresh evidence before choosing any repair. No production appearance or pairing behavior changes are authorized. No decision record is needed.

## Design

Run the unchanged selected method on the required managed profile. Preserve the revision, timestamped Gradle output, instrumentation output, UTP results and device logcat before another run replaces them. Observe emulator process identity and host crash reports over the same interval. Correlate the last completed instrumentation/test stage with emulator termination evidence, distinguishing an assertion, instrumentation process crash and loss of the emulator itself.

If the existing logs cannot locate the last stage, add only static test-stage diagnostics around composition, per-theme assertions, screenshot capture and callbacks. They must not change synchronization, bounds, assertions or retries. A Mobile-owned repair will be specified in a dated revision after the evidence identifies it, before implementing the repair. If ownership lies in the agents runner or host, retain the exact evidence and return for refinement without changing that repository.

Scope forecast: one deliverable, approximately 450 written lines including tests, plan and bounded evidence; no exported types, no production files, no signature migration and no added state-machine reject branches. No overlapping remote feature branch modifies the four candidate test/configuration files at planning time.

## State and concurrency model

Production state and coroutine ownership are unchanged. `checkFrame` owns its Compose theme state and callback counters within one test. Observational host diagnostics live outside the worktree and do not control the device lifecycle.

## State transitions and identity reuse

None: this ticket introduces no lifecycle or identity state. Existing dark-to-light recomposition and two callback invocations remain covered by each of the three scanner methods.

## Error handling

Preserve existing capture timeout behavior. Do not catch assertion failures, instrumentation crashes or emulator loss. Evidence must distinguish incomplete execution from a completed failed assertion; an empty XML failure is not a diagnosis or a pass.

## Testing strategy

- First obtain an unchanged failing selected-method run and diagnose the failure before repair. If it passes, do not manufacture a failure or claim a repair.
- After a Mobile-owned repair, run `ScannerFrameTest#compactEnlargedTextFrames` freshly and require one executed/passed with zero failures, errors and skips. Run the whole class and require three executed/passed with zero failures, errors and skips.
- These tests stay device-only because they capture real device pixels. Preserve both themes, 360×640 dp, 1.5 font scale, time bounds and every assertion.
- Run focused compile/build, lint and forced Spotless checks for touched files, then the required final assemble and `scripts/pre-verify.py --gradle` checks after merging main and pushing.
- Dispatcher owns the full UI gate; hand off its selected-suite completion and enlarged-method passing result explicitly. No real-Claude scenario or scripted scenario applies to this infrastructure-only ticket.

## Open Questions

- What terminates: assertion, instrumentation process or emulator process? Resolve using correlated fresh logs, not historical empty XML.
- Does the evidenced cause belong to Mobile, the external agents runner or the host? Only Mobile-owned causes may be repaired in this branch.
- What bounded evidence can be retained without secrets? Scanner-only logs and sanitized timestamped process/crash excerpts are candidates; preserve counts and revision identifiers alongside artifacts.

## Revisions

### 2026-10-10 — Current dark-only fixture after refinement

The refined issue supersedes the historical dual-theme contract after merged PR #2062. `checkFrame` now covers dark only with one Back and one Paste callback in each method: `fullSizeDarkFrames` at 412×892 dp, `compactDarkFrames` at 360×640 dp, and `compactEnlargedTextFrames` at 360×640 dp with font scale 1.5. Preserve all current assertions, time bounds and capture retries; restoring light coverage is outside scope. This revision supersedes the both-theme and dark-to-light statements above.

Begin with the unchanged enlarged method; if it completes, investigate the whole current class and the reported suite context without manufacturing a failure. This dispatch runs on `Juhanas-MacBook-Air.local` (Darwin arm64); the refinement identifies HP as the historically affected runner. A Mac pass alone cannot diagnose or establish repair of HP emulator loss. Preserve revision and host identity with any fresh result, then route unestablished or external ownership for refinement as the issue requires.

The refreshed remote-branch overlap check found no numeric feature branch changing the scanner fixture, screenshot helper, app build configuration or Gradle properties. Sizing remains one investigation deliverable, roughly 450 written lines, no new exported types or consumer migrations, three acceptance criteria and no new reject branches. No production UI change or documentation-stage requirement is introduced.
