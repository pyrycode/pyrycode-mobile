# #588 — Default-workspace live reply recheck

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_pingPrompt_streamsPingReplyIntoThread`, `interactiveTurn_createWorkspaceFolder_usableAsLiveSessionWorkspace`, `pingNodeCount` — existing default/explicit workspace reply coverage.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/E2eTestApplication.kt` → `onCreate`, `tappedRelayRepositoryModule` — relay arguments select the real repository and pre-pair the test app.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` → `onEvent` — a short tap reads the preference; an explicit pick supplies its own workspace.
- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt` → `defaultWorkspace` — factory preferences resolve to `DEFAULT_SCRATCH_CWD`, not null.
- `scripts/e2e-emulator.sh` → `TEST_TARGET` in the LIVE branch — both named methods remain in the curated eight-case suite.
- `scripts/android-test-gate.py` → `main`, `claude_authenticated`, `fresh_reports`, `combine_reports` — isolated test identity, authentication preflight and fresh counted XML without private logs.
- `scripts/test_android_test_gate.py` → `AndroidGateTest` — existing rejection coverage for absent, skipped, stale, malformed and failing reports.
- `app/build.gradle.kts` → `android.testOptions.managedDevices` — Pixel 2, API 33, AOSP ATD device profile.
- `docs/knowledge/features/development-verification.md` → “Gradle and source checks”, “Emulator and real evidence” — compilation cannot substitute for executed live cases.
- `docs/knowledge/features/app-preferences.md` and `channel-list-viewmodel.md` → `defaultWorkspace`, `CreateDiscussionTapped` — do not normalize the scratch sentinel into an empty or null cwd.
- `docs/e2e-interactive-stream.md` → “What rung 3 is made of”, “Verification status” — current ladder and the expired-authentication baseline.

## Design source

N/A — evidence-only revalidation of existing UI flows; no visual changes.

## Change

Retain the two existing rung-3 scenarios and their LIVE selection without a speculative product patch or duplicate scenario. The ping scenario taps New discussion with factory preferences and no picker. The folder scenario explicitly creates/selects a unique workspace, checks its chip, sends a prompt and checks recents. Both currently wait for additional rendered ping content after sending. This ticket records the current daemon result for those flows; it does not investigate July's retired runner mechanism without a fresh failure.

Reviewed baseline: mobile `595a94723d9bcfea3b306b8bc19515d8b62d3833`, sibling daemon `dccd18286b8f80d113c055322e22df3125bd1735`. [PR #690](https://github.com/pyrycode/pyrycode-mobile/pull/690) reports eight executed live cases, four passes, three Claude-response failures from expired OAuth, and a subsequently fixed archive failure. That is environment-failure evidence, not a current workspace-routing diagnosis. No fresh live XML was supplied to this builder or found in its worktree; the canonical checkout currently has only scripted gate artifacts.

One deliverable: durable revalidation evidence. Estimated total written work remains within the refiner's approximately 150 lines, including this plan and the later compact evidence records. Boundary: 0 production files, 0 exported types, 0 consumer updates, 2 acceptance criteria, 0 new reject branches. The analogue #566 added 129 test/harness lines and 104 plan lines; this ticket reuses that work. Remote feature branches were refreshed and checked; none overlaps the three planned paths.

## Testing strategy

There is no new logic and no new RED/GREEN test to introduce. Retain the existing tests unchanged. Run formatting, lint and the debug build for the builder gate; instrumented compilation is required if a later evidence-driven adjustment changes instrumented sources. No JVM test classes are modified, so there is no scoped JVM test run.

The dispatcher owns `python3 scripts/android-test-gate.py live`, with authenticated Claude, a freshly provisioned managed device and the isolated test daemon. Preserve production daemon binaries/configuration. Keep `needs-real-claude`; add `needs-live-artifacts` before review so a successful capture returns to the builder for artifact commitment.

The return leg must inspect fresh XML for the exact class `de.pyryco.mobile.e2e.InteractiveStreamE2ETest` and both methods:

- `interactiveTurn_pingPrompt_streamsPingReplyIntoThread`
- `interactiveTurn_createWorkspaceFolder_usableAsLiveSessionWorkspace`

Each must occur once, execute without skip/failure/error, and pass. Check the full curated suite's eight executed cases and process exit as well. Missing authentication, missing/zero-count XML or compilation alone cannot establish a pass. If a case fails, triage environment versus product from the supplied run; identify the failing layer and file/link a bounded owning-repository fix as a native blocker before revalidation. Do not patch out-of-scope production code.

## Live artifact handoff

Pending builder return after the dispatcher live gate:

- `scripts/fixtures/default-workspace-live/588.xml` — commit the exact usable sanitized `dispatcher.xml` produced by the gate, preserving all case names and outcomes. Raw Logcat, credentials and daemon payloads stay out of the repository.
- `scripts/fixtures/default-workspace-live/588-context.json` — record the actual app and daemon revisions, UTC run time, device profile, command, process exit, executed count and XML SHA-256. Do not substitute the planning revisions for the actual tested revisions.
- Append the result and any changed decision under this plan's `Revisions`; validate XML counts/named outcomes and context linkage, then commit/push the records and remove only `needs-live-artifacts`. No coupled reader changes are planned.

Do not create placeholder evidence. Passing dispatcher execution alone does not finish the capture ticket; the artifact return leg is builder-owned.

## Documentation handoff

The ticket has no separate documentation acceptance criterion. Pending documentation stage: update `docs/e2e-interactive-stream.md`, section “Verification status”, with the accepted default/explicit-workspace live result and evidence links after capture/triage. The builder does not edit that shared document.

## Open questions

None about implementation. Whether the current authenticated daemon replies in both workspaces is the pending live acceptance result, not an assumed pass.
