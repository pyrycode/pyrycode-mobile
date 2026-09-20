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

## Builder verification — 2026-09-20

- Source checks confirm both named methods are present once, annotated `@Test` without `@Ignore`, and selected in LIVE. Instrumented sources and production code remain unchanged.
- `./gradlew spotlessApply lint assembleDebug --console=plain` passed using the Android SDK/JDK paths configured in the dispatcher's environment file. Lint reports zero errors and 59 warnings in unchanged files; reviewed warnings cover dependency versions, existing resource/API usage and Compose guidance. Formatting left the tracked tree unchanged.
- `scripts/docs-guard.sh` and `git diff --check` passed. No scoped JVM test or instrumented compile was needed because no test source changed.
- Live execution and both evidence files remain pending. The issue carries `needs-real-claude` and `needs-live-artifacts`; no live pass is claimed.

## Revisions

### 2026-09-20 — failed live capture and assertion blocker

- The dispatcher ran `python3 scripts/android-test-gate.py live` at `2026-09-20T12:43:59.263Z` against app `90de2cecdd16272612ee3170c5c8c2c00f28e714`, main `595a94723d9bcfea3b306b8bc19515d8b62d3833`, and daemon `dccd18286b8f80d113c055322e22df3125bd1735` on `pixel2Api33Atd`. Exit 1; eight executed, five passed, three failed, none skipped. Both required workspace cases and `interactiveTurn_newSession_rendersSessionBoundaryDelimiter` timed out in their reply-count waits after 90 seconds. [Dispatcher evidence](https://github.com/pyrycode/pyrycode-mobile/issues/588#issuecomment-5749901060).
- Preserve this failed capture in the planned `588.xml` and `588-context.json` paths. The detached gate worktree and original Android reports are gone; the retained dispatcher stdout contains the exact sanitized XML printed by `main` after writing `dispatcher.xml`. Copy its bytes unchanged and record that provenance, counts and SHA-256. These are failure artifacts, not the passing acceptance evidence still required.
- Correlating each `send_message.enqueued` conversation with the isolated daemon's retained history shows one expected four-character assistant reply, followed by `turn_end` with `outcome=success`, `is_error=false`, `terminal_reason=completed` and `stop_reason=end_turn` in all three cases. The startup `streamsup: no live child` warning recovered. The `rate_limited` events say `allowed_warning`; they did not prevent completion. This run is not the historical expired-OAuth failure, and it does not demonstrate a workspace-routing or Claude-generation defect.
- The remaining failure boundary is the mobile receive/render/assertion path. `pingNodeCount` counts substring matches in the title, optimistic user echo and `QueuedBacklog`, so removing queued text can offset the assistant reply. Source inspection establishes that the count-growth predicate is not a reliable reply oracle; without retained phone semantics or Logcat, it does not establish that this specific reply rendered. No production patch or asserted phone-side pass follows from daemon success.
- Filed [#694](https://github.com/pyrycode/pyrycode-mobile/issues/694), a bounded test-only repair with a deterministic queued-to-reply regression, on board 5 in Inbox and linked it as a native blocker of #588. Revalidate only after that fix. Both existing required scenarios and the LIVE selection remain unchanged.
- Keep `needs-real-claude` and `needs-live-artifacts`: a later passing dispatcher run still needs builder validation and artifact commitment. Documentation remains pending at `docs/e2e-interactive-stream.md` → “Verification status”; record this failed result and the blocker, then replace the pending acceptance note only after a passing capture.
- Rechecked overlap: no remote feature branch touches these three artifact/plan paths. No production, test or harness source changed; the prior builder/verifier compilation, formatting, lint, unit, UI and scripted results still apply to the identical source tree. Validate this artifact change with XML/context integrity checks, `scripts/docs-guard.sh` and `git diff --check`.
