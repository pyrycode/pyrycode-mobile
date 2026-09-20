# Current live mobile baseline (#528)

## Files read

- `scripts/android-test-gate.py` → `main`, `fresh_reports`, `combine_reports`: the gate currently searches only managed reports; timestamp, count, class, duplicate and failure checks already exist.
- `scripts/test_android_test_gate.py` → `AndroidGateTest`: focused Python regression style and sanitization/count coverage.
- `scripts/e2e-emulator.sh` → `TEST_TARGET`, `DEVICE`, `report_interactive_runner`: LIVE selects eight methods and invokes either the managed task or `connectedDebugAndroidTest`.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → the eight `interactiveTurn_*` LIVE scenarios and `unrecognizedRowSentinel`: assertions and parser-gap coverage remain unchanged.
- `scripts/fixtures/default-workspace-live/588-context.json` and `588.xml`: historical API 33 evidence and provenance pattern, not evidence for this revision or API 35.
- `docs/knowledge/features/development-verification.md` → “Test scheduling and harnesses” and “Emulator and real evidence”: process and XML must both pass; sanitized evidence must identify its producer.
- `docs/e2e-interactive-stream.md` → “Live mode (rung 3, live relay)” and “Verification status”: current selection, previous assertion repairs and dispatcher ownership.
- Sibling `pyrycode/docs/protocol-mobile.md` → current v2 contract; consult again before diagnosing any live product failure.

## Change

Select reports in `main` from `app/build/outputs/androidTest-results/connected/debug` when `DEVICE=connected`, otherwise from `managedDevice/debug/<DEVICE>`. These paths match existing AGP artifacts in the canonical checkout. Keep `fresh_reports` timestamp filtering, sanitized report output, nonzero process rejection and all existing XML validation. Do not alter app code, daemon code/configuration, scenario selection, assertions or `UnrecognizedRowSentinel`.

The single deliverable is a reproducible API 33 live baseline. The harness seam is required to collect that baseline. Estimated total including plan, regression tests and returned evidence: approximately 300–400 lines; zero production Kotlin files, zero new exported app types, zero consumer signature changes, four AC and no app state machine/error branches. No UI design changes. Refiner estimate is consistent. Remote feature branches were refreshed and no overlap was found for the two script files.

## Testing strategy

Before implementation, add a regression that invokes `main` with a stubbed subprocess and authenticated preflight, producing realistic eight-case XML in temporary AGP directories. Exercise managed and connected selection independently, with fresh reports in the other path and stale reports in the selected path. Verify selected XML is sanitized and counted, no report or stale-only reports fail, and a nonzero process still fails despite passing XML. Existing focused tests retain missing/zero-count/failure rejection coverage.

Run the focused Python suite RED then GREEN, `spotlessApply`, `lint`, `assembleDebug`, and `compileDebugAndroidTestKotlin`. No JVM classes change. No device runs are claimed by these local checks.

## Live acceptance handoff

Operator decision on 2026-09-20: API 33 is the sole required Android version for the baseline and routine ticket gates for now. API 35 is deferred. Keep the connected-report fix and its regression coverage.

The recovery run used `python3 scripts/android-test-gate.py live` on managed `pixel2Api33Atd`, Pixel 2 / API 33 / AOSP ATD, through the existing dispatcher credential environment. It used real Claude, the live relay and an isolated test daemon. All eight methods executed and passed, with zero failures or skips and process exit zero. Production configuration was unchanged.

Committed `scripts/fixtures/live-mobile-baseline/528-api33.xml` and `528-api33-context.json` record the result, per-method outcomes, app and daemon revisions, Claude version, resolved runner, observed runtime device and image, timestamps and XML checksum. Device properties were captured with ADB during the run. Claude binary and source revisions were checked before and after execution.

Remove `needs-live-artifacts` after this evidence is pushed. Keep `needs-real-claude` for review and the normal final live gate. No API 35 run or evidence is required. Tool-use, spinner and negative controls remain outside this baseline.

## Documentation handoff

Update `docs/e2e-interactive-stream.md` under “How to run”, “Live mode” and “Verification status” with the instrumented-compilation check, reproducible API 33 command, dated per-scenario outcomes and committed evidence links. State that API 33 is the sole required version for now and API 35 is deferred. Retain historical failures as history. Include the runtime capture in the baseline evidence description.

## Open questions

None for implementation or API 33 evidence. Review, the final live gate and documentation remain with the pipeline.

## Revisions

- 2026-09-20: The initial plan required API 33 and API 35. The operator subsequently narrowed acceptance to API 33 only.
- 2026-09-20: The first returned API 33 result passed all eight tests but omitted Claude version and runtime image details. Its evidence remains in Git history.
- 2026-09-20: Reran API 33 while capturing the missing details. All eight tests passed. Replaced the incomplete baseline with that complete, revision-linked evidence.
