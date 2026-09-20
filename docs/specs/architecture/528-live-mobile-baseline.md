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

The single deliverable is a reproducible two-profile live baseline. The harness seam is required to collect that baseline. Estimated total including plan, regression tests and returned evidence: approximately 300–400 lines; zero production Kotlin files, zero new exported app types, zero consumer signature changes, four AC and no app state machine/error branches. No UI design changes. Refiner estimate is consistent. Remote feature branches were refreshed and no overlap was found for the two script files.

## Testing strategy

Before implementation, add a regression that invokes `main` with a stubbed subprocess and authenticated preflight, producing realistic eight-case XML in temporary AGP directories. Exercise managed and connected selection independently, with fresh reports in the other path and stale reports in the selected path. Verify selected XML is sanitized and counted, no report or stale-only reports fail, and a nonzero process still fails despite passing XML. Existing focused tests retain missing/zero-count/failure rejection coverage.

Run the focused Python suite RED then GREEN, `spotlessApply`, `lint`, `assembleDebug`, and `compileDebugAndroidTestKotlin`. No JVM classes change. No device runs are claimed by these local checks.

## Live acceptance handoff — pending

Dispatcher runs, with authenticated real Claude, live relay and an isolated test daemon:

- `python3 scripts/android-test-gate.py live`: managed `pixel2Api33Atd`, Pixel 2 / API 33 / AOSP ATD.
- `DEVICE=connected python3 scripts/android-test-gate.py live`: only the intended `Pixel_8` API 35 emulator selected; installed image is `google_apis_playstore/arm64-v8a` (actual running image must be recorded).

Keep `needs-real-claude`. Both runs must execute and pass ping, create-workspace-folder, new-session, delete, archive/restore, change-workspace, rename and save-as-channel individually. Tool-use, spinner and negative controls remain outside this baseline. Installed images are present; their availability is not proof of bootability or successful live authentication.

On return, builder triages and commits `scripts/fixtures/live-mobile-baseline/528-api33.xml`, `528-api33-context.json`, `528-api35.xml` and `528-api35-context.json`. Each XML is sanitized dispatcher output. Each context records command/date, app and daemon revisions, Claude version, resolved interactive runner, actual device/API/image, process exit, per-method outcomes and executed/pass/fail/skip counts, XML checksum and evidence provenance. Never commit secrets, raw logs or pairing payloads. Missing prerequisites are environment blockers, not product failures. For product failures, identify owner, search existing issues and file/link a bounded owning-repo fix with a native blocker before revalidation. Production daemon and configuration remain untouched.

## Documentation handoff

Pending for the documentation stage, exact ticket requirement:

Documentation stage: update `docs/e2e-interactive-stream.md` under “How to run”, “Live mode (rung 3, live relay)” and “Verification status” with the instrumented-compilation gate, reproducible commands for both profiles, dated per-scenario outcomes and committed evidence links. State exactly which profile each result proves, retain historical failures as history, and distinguish unresolved environment/product blockers from passes.

## Open questions

None for implementation. Live results and their runtime metadata remain pending dispatcher execution and builder artifact return.

## Revisions

- 2026-09-20: The dispatcher's “Live artifact handoff” requires `needs-live-artifacts` alongside `needs-real-claude` before first review. Apply the artifact marker for the four pending files above; on return, commit both profiles' usable evidence before removing only that marker. No coupled reader changes are needed.

- 2026-09-20 artifact return: Dispatcher supplied only the managed API 33 run (eight passes, process exit 0). Preserve its exact sanitized XML and provenance now. Claude version and runtime image metadata were not retained; record these as unknown, without substituting the current host state. No connected API 35 evidence was supplied, so the two-profile baseline remains incomplete and both live markers remain. Complete the missing artifacts from dispatcher records before removing `needs-live-artifacts`.
