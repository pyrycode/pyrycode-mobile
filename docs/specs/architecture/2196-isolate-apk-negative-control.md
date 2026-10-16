# Isolate the installed APK negative control (#2196)

## Files read

- `scripts/test_recorded_mobile_suite.py`: `SuiteDriverTest.fixture` and `test_wrong_installed_apk_is_rejected_before_native_execution` supply the validated replay input and negative control.
- `scripts/recorded-mobile-suite.py`: `installed_apks` compares installed hashes to built bytes; `replay` performs that check before native execution.
- `docs/knowledge/features/development-verification-gates.md`: script checks need counted execution and source revision evidence.

## Change

Replace the negative control's dependency on checkout build outputs with a temporary root containing controlled app APK bytes. Supply a different installed digest through mocked adb, drive `replay` with the existing validated fixture, and assert the specific digest-mismatch error, the expected adb queries, and no instrumentation call. Keep runtime APK validation unchanged. No in-flight feature branch overlaps the test file. This is one test-fixture deliverable, approximately 50 written lines including this plan, with no new exported types or consumer changes.

## Testing strategy

The original named test reproduced `FileNotFoundError` without checkout APKs (1 executed, 1 error). After the fixture repair, run the named test and complete verbose script discovery before any assembly on Linux, retaining revision, raw output and counted results under `/tmp/builder-2196/` and reporting them in the PR. Run the required focused scope checks and final `assembleDebug` / `pre-verify.py --gradle` gates after the last merge of main. No emulator, adb process, device test or recorded scenario changes are needed.

After this repair merges, the dispatcher owns fresh complete deterministic gates for #1947 / PR #2136; this ticket does not claim those gates have passed.
