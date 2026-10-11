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

### 2026-10-10 — Fresh evidence and refinement outcome

At tested revision `cc47cef876cc4429986e933fec5912e7de12fcf3`, unchanged scanner source from main `969d9c3c8847b8c99af3a8573750f8429a72a1eb` completed on `Juhanas-MacBook-Air.local`: enlarged method 1 executed/1 passed and class 3 executed/3 passed, both with 0 failed/errors/skipped. A separate three-method class comparison using the dispatcher's single-shard and animation-disabled flags also completed 3 executed/3 passed with 0 failed/errors/skipped. Each has completed instrumentation and fresh XML, not only a start event or exit status.

The bounded evidence and original XML are retained under `app/src/androidTest/assets/scanner-2051/`; complete local raw artifacts and timestamped host samples are in `/tmp/builder-2051/current-dark-20261010/`. The evidence names commands, clocks, revisions, host, method stages and hashes. Raw AGP results were copied before each subsequent device run. The scanner fixture, capture helper, configuration, assertions and production UI remain unchanged.

Cause and ownership remain unestablished: neither a scanner assertion failure, instrumentation crash nor emulator loss reproduced on this Mac host. These local passes do not establish an HP repair. Return for refinement under acceptance criterion 2, asking for correlated execution on the affected HP runner and its failing dispatcher suite context. Full dispatcher UI gate completion remains unverified. No Mobile repair or follow-up bug ownership is claimed, and no repair PR is appropriate for this investigation outcome.

### 2026-10-11 — Validate the installed HP renderer workaround

The refined contract and recovered HP artifacts supersede the earlier missing-artifact, unestablished-ownership and return-for-refinement conclusions. The controlled dark-only baseline at `969d9c3c8847b8c99af3a8573750f8429a72a1eb` captured emulator-process SIGSEGV during scanner execution with `auto-no-window`. Its instruction read an unmapped address. This establishes emulator loss and host rendering-path ownership; the exact native defect remains unproved. The operator installed SwANGLE in HP's emulator launcher/container environment. No Mobile source repair is proposed.

The only changes are this plan revision and bounded content-free evidence under `app/src/androidTest/assets/scanner-2051/`. Preserve the existing Mac report and XML as historical comparisons. Add an HP report, fresh focused raw/count-derived XML, the post-install class XML, and separate count-derived XML for both completed historical HP suites. Retain artifact paths, SHA-256 hashes, UTC windows, command and exit status, admission grant/completion and static instrumentation/device excerpts. Strip unrelated payloads, stack bodies and device logcat content from derived reports; preserve every case's identity and outcome in counted XML. Label each derived artifact and hash its original separately.

The operator's newly admitted standalone HP run is already complete at `2026-10-11T09:32:43Z`: exactly `ScannerFrameTest#compactEnlargedTextFrames`, 1 executed/passed and zero failures/errors/skips. Verify its original raw XML and grant through `build-hp-1-automation`; do not duplicate the run. Gradle exited 0 while the collector exited 1 because it received `class#method` instead of its class-only filter. Preserve that collector failure and independently assert exact method selection against the fresh raw XML and corrected counted output. Reuse the post-install 3/3 class result only after comparing scanner, capture, instrumentation-runner and managed-device/build sources with the current branch and comparing the two runs' execution flags. Refresh focused/class evidence if those inputs differ.

Preserve `checkFrame`'s current dark-only stub-camera contract: `fullSizeDarkFrames` 412×892 dp, `compactDarkFrames` 360×640 dp and `compactEnlargedTextFrames` 360×640 dp at 1.5 font scale, each with one Back and one Paste callback. Assertions, capture retries, time bounds and coverage remain unchanged. No new state, lifecycle transitions, error branches, dependencies, exported symbols or production files are introduced. This evidence validates the workaround; fixture coverage does not prove live CameraX or QR pairing.

Both historical SwANGLE suites recorded 259 cases: 257 passed, one saved-history assertion failure, zero errors and one existing rename skip. Retain all three passing scanner names in each. They establish suite completion without emulator loss, not a passing gate. Define recorded versus executed counts explicitly so the skipped case is not counted as executed. A current saved-history recurrence remains a separate failure, with #2050 / PR #2066 as prior repair context.

The installed launcher enforces `-gpu swangle`; retain its identity/hash and the rollout boot's ANGLE/SwiftShader renderer identification, attributing that identification to the separate boot rather than claiming a new scripted-suite pass. Source/configuration hash equality and identical single-shard, animation-disabled execution flags determine class reuse. The refreshed numeric in-flight branch check found no overlap in the scanner fixture, capture helper, build configuration or evidence directory.

Testing: independently parse the original and retained XML, require one exact focused case and all three class/scanner-suite names, compare per-case outcomes and original hashes, and inspect completed instrumentation/device stages. Run lint, assemble, Android-test Kotlin compilation, Spotless apply and forced check, then merge main, push and run final assemble plus `scripts/pre-verify.py --gradle`. There is no new logic needing a fabricated red test. No real-Claude scenario applies.

Dispatcher handoff: run the currently configured UI gate and ordinary scripted gates under their existing requirements. The selected UI suite must complete and execute/pass all three scanner methods; retain recorded/executed/passed/failed/error/skipped counts, revision, host and fresh artifact paths. This builder neither replays the historical full-suite diagnostics nor reports pending routine gates as passed. Mac ticket ownership is preserved.

All earlier Open Questions are resolved: emulator loss, host rendering ownership and content-free static evidence are established; exact native defect identification remains outside scope. Measured retained work is 655 lines including the plan and prior Mac evidence: one deliverable, three criteria, zero consumer migrations or exported types, and below the 1,600-line boundary.

### 2026-10-11 — Scripted-gate rework comparison

The verdict on `9b558e00a` accepted the scanner evidence and reported a completed current UI gate, but returned two 90-second coroutine timeouts in `DeterministicInteractiveStreamE2ETest`: `interactiveTurn_seededChannel_streamsMultiDeltaReplyIntoThread` and `interactiveTurn_seededChannel_reopenOngoingReplyShowsArrivedPrefixImmediately`. Its main-pass determination came from the dispatcher rather than an independently repeated comparison. Reproduce each through its normal focused scripted gate, then compare the same commands against a detached `origin/main` worktree with identical daemon, relay, harness and device inputs. Preserve fresh XML before any following run replaces AGP outputs.

Read the two named methods and `scripts/e2e-emulator.sh`'s second-fragment release fence. Both enqueue a follow-up while the held reply is open. Open issue #2078 already owns the legacy queued-echo/assistant-anchor repair; #1985 / PR #2076 owns the independent direct-release fixture change. If the unchanged main comparison reproduces both failures, link the established repair as a blocker and return `waiting_on_blocker`; do not change production projection, fixtures, assertions, deadlines or retries in this evidence-only ticket. A timeout alone does not prove the precise anchor defect.

Retain only case identity, outcome, timestamps, revisions, commands, paths, hashes and allowlisted static lifecycle events in bounded rework evidence under `app/src/androidTest/assets/scanner-2051/`. Raw logs remain outside the worktree. The current UI counts must be attributed to the verifier's posted verdict unless its original artifacts are independently available. Add a dated outcome here after the comparisons. This adds no production behavior, lifecycle state, exported type, consumer migration or reject branch; forecast remains under 800 written lines. The refreshed numeric branch check found no overlap in the evidence directory or this plan.

### 2026-10-11 — Scripted failures reproduced on unchanged main

Both normal focused commands reproduced the verdict's 90-second coroutine timeout at branch revision `9b558e00a3c48a0b3f3bb54ff7cc716ad7e0a2e9` and unchanged main `11ed225d27026e9e59abe634adc49c338dd41c51`. Each of the four runs completed one executed failing method, with zero passed/errors/skips and gate/Gradle exit 1. Scanner, capture, deterministic test, application/runner, IME, harness, fixture and build sources match by SHA-256; daemon, relay and all four fakeclaude binaries also match. The daemon revision is `a94775d2664b5a6c483e5d12aeb61f6a0bc5cb8e`. Both methods reached the second-fragment release after a busy follow-up enqueue. This establishes inherited failures rather than a scanner-evidence regression, while the exact timeout cause remains unproved.

`rework-evidence.txt`, `rework-hashes.txt` and four count-derived XML files retain the comparisons under the existing evidence directory. Raw XML/logcat copies are in `/tmp/builder-2051/rework-9b558e00a/`. Preserve the original failing tests unignored: no production projection, harness, deadline, assertion, retry or coverage change belongs here. Open #2078 owns the matching legacy queued-follow-up repair and is linked as a blocker; return `waiting_on_blocker`. After the repair lands, rerun both focused commands and hand off the ordinary dispatcher gates without claiming a repair pass now.

The posted verifier verdict reports the current UI gate completed at the reviewed revision on `Juhanas-MacBook-Air.local`: 259 recorded, 258 executed/passed, zero failures/errors and one existing rename skip, with all three scanner methods passing. This resolves the earlier pending UI completion at that revision; the report explicitly attributes these counts to the verdict because its worktree is no longer present for independent re-parsing. Documentation still belongs to the later stage. Measured retained work is 815 written lines including evidence and plan, with one deliverable, three criteria, no exported types, consumer migrations or reject branches, below the 1,600-line ceiling.

### 2026-10-11 — Upstream direct-release fixture landed during final merge

The required final fetch/merge brought in #1985 / PR #2076 at main `a6840a356e8d5f02e62f888e5d1961b273d93475`, producing tested merge `f8a08c1f8a5a56f22ce9788762c1e2ed463f553a`. Upstream changed the two display fixtures to release fragments directly through the isolated daemon controller, separating display checks from the queued-follow-up defect tracked by #2078. The retained failures and matching input hashes describe the earlier `9b558e00a`/`11ed225d2` trees; they do not describe the new merged harness. No scanner fixture, capture, instrumentation runner or managed-device configuration changed in this merge.

Rerun both normal focused scripted commands on this merged tree and retain exact method identities, executed/passed/failed/error/skipped counts, command/exit, revisions and original artifact hashes. If both pass, remove the temporary #2078 blocker and complete this evidence ticket with ordinary dispatcher gates pending. This supersedes the earlier waiting outcome only after counted passes; the separate legacy projection repair remains #2078's scope. Append the measured results before handoff. No #2051 production or fixture change is proposed.

## Documentation handoff

- Pending documentation stage: record the installed HP workaround and subsequent dispatcher UI/scripted results in `docs/knowledge/features/development-verification.md`, under its managed-device verification guidance, including host, revision, counts and evidence paths. Preserve the distinction between historical suite completion, a passing current gate and exact native defect repair.
- Pending documentation stage: reconcile `docs/knowledge/features/scanner-screen-edge-cases-and-testing.md`, section `Focused verification`, with the current dark-only fixture; preserve the separate live-camera acceptance boundary.
