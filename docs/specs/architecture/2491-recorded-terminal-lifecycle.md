# Recorded terminal lifecycle (#2491)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/recording/RecordedMobileSuiteTest.kt`: recorded rule nesting currently destroys the Activity before session cleanup.
- `app/src/androidTest/java/de/pyryco/mobile/recording/RecordedSuiteNative.kt`: `beforePhoneTeardown`, `checkpoint`, and `finish` own replay preparation and restoration.
- `app/src/sharedTest/java/de/pyryco/mobile/recording/RecordedActivityRule.kt`: successful pre-destruction callback; failures bypass preparation.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/LiveSessionCleanupRule.kt`: begin/end ownership and replacement Activity cleanup.
- `app/src/sharedTest/java/de/pyryco/mobile/recording/SuiteConnections.kt`: strict control sends, delivery barriers, close consumption fence.
- `app/src/sharedTest/java/de/pyryco/mobile/recording/RecordedSuiteTape.kt`: observation fences, required requests, terminal readiness.
- `app/src/test/java/de/pyryco/mobile/recording/RecordedActivityRuleTest.kt`: controlled Activity lifetime regression pattern.
- `app/src/test/java/de/pyryco/mobile/e2e/LiveSessionCleanupRuleTest.kt`: original-failure and mandatory-cleanup contracts.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `backgroundAgentReplyProof` preserves child attribution, visibility, uniqueness, and separate main reply.
- `docs/recorded-tests.md`, Complete native suite / Observation checkpoints: test-end and scenario-complete are distinct; no work may cross either boundary.
- `docs/knowledge/features/lifecycle-connection-driver.md`: delayed process stop closes connections; changing production lifecycle would hide the harness defect.

## Context

One behaviour: finish a successful recorded scenario's required terminal work before destroying its request-producing Activity. The focused main failure at `a48e9ced0e9bc7e9193445d576afb6fb498a7b99` first reports an early phone close at matched step 32, with test-end and phone queries pending. Delivery errors from outer finish are secondary. The source-matched #2462 verifier bundle is `18ddeeede50af3312b066b5571ec64d526f549729171adc788b23a62599b240c`.

The committed Agent recording has test-end, additional phone updates, a catch-up/reset, list query, fresh catch-up/replies, scenario-complete, then phone close. Keeping the Activity only through test-end cannot satisfy it. No production, visual, fixture, or fresh provider-contract change is intended; no decision record is needed.

Overlaps: #2311 and #2351 touch other native adapter functions; #2325 and #2462 change separate tape matching; #2360 adds the pre-test-end foreground fence. Preserve that fence when main incorporates it. None supplies a required dependency for this terminal-boundary repair.

## Design

Keep begin outside Activity launch. Add an explicit successful early-end entry point to `LiveSessionCleanupRule`, so recorded preparation can invoke the real end action once while the Activity remains alive. Its ordinary outer cleanup still runs end after failures and always releases replacement Activities. No duplicate recorded end is suppressed by the tape.

The recorded Activity callback first retains existing foreground preparation, then runs session end, then prepares the recorded terminal prefix. When scenario-complete precedes phone closure, emit that exact checkpoint while the Activity remains alive, after all required earlier work drains. When phone closure precedes scenario-complete, retain that order: drain the pre-close prefix while alive and leave checkpoint emission to outer finish. Native session state records only a successfully emitted early checkpoint so finish does not send it twice. Captures and older recordings retain their existing paths.

No request, reply, owner, payload, correlation, deadline, or compatibility policy changes. The existing tape decides readiness and validates all actual controls. Terminal preparation cannot manufacture queries, consume required repeats, or move replies across observations.

## State and concurrency model

JUnit's instrumentation thread sequences successful preparation before Activity destruction. Existing hub coroutine scope and delivery queue continue routing replies; request-producing application jobs remain owned by their original scopes. Terminal preparation shares one existing 30-second budget across its close prefix, observation and downstream consumption; crossing a phase cannot restart it. Native replay state is session-local and restored/disposed by existing finish cleanup.

## State transitions and identity reuse

| Event | Required proof |
| --- | --- |
| Successful return with delayed queries after test-end | `RecordedTerminalLifecycleTest.successfulReturnKeepsRepeatedPostEndQueriesAndRepliesBeforeCompleteAndProcessStop` retains actual requests, correlated replies, and scenario-complete. |
| Activity destruction followed by delayed process stop | The same regression closes only after the terminal prefix; the old rule order fails early-close matching. |
| Close recorded before final checkpoint | `RecordedTerminalLifecycleTest.aRecordedCloseBeforeCompleteStillPrecedesTheObservation` leaves checkpoint emission to finish. |
| Repeated query payload / foreign owner / observation | The success regression requires both correlated replies; `terminalQueriesCannotBorrowAnotherHostOrGenerationOrCrossTheObservation` rejects substitutions and early queries. |
| Missing required work | `RecordedTerminalLifecycleTest.missingTerminalWorkTimesOutAndEarlyCloseStillFails` retains the deadline and strict close failure. |
| Observation followed by delayed consumption | `RecordedTerminalLifecycleTest.terminalPreparationSharesOneDeadlineAcrossObservationAndConsumption` retains one 30-second budget and leaves later work on its recorded side of the observation. |
| Body/end/preparation failure | `RecordedTerminalFailureTest.earlyEndAndPreparationFailuresStayPrimaryAndReleaseEveryActivityExactlyOnce` preserves primary errors and cleanup. |
| Repeated evaluation/new session | `RecordedTerminalFailureTest.anotherEvaluationCannotReuseThePreviousEarlyEnd` resets cleanup ownership; `activityRuleCannotReuseAPreviousPrimaryFailure` prevents a prior body error replacing a later teardown error. |

## Error handling

Strict tape mismatch and timeout remain failures. Cleanup preserves the first scenario/preparation failure and attaches later cleanup failures. Successful preparation is recorded only after the actual checkpoint send; failures still run ordinary graph restoration and disposal.

## Testing strategy

Write controlled JVM regressions first using the real tape, hub, Activity rule, and session-cleanup rule, with deterministic gates for terminal queries and process-stop timing. Retain a red result before implementing. Probe missing/repeated work, observation fences and owner/correlation checks with the affected existing tape/hub suites. Run affected cleanup/rule/consumption classes, lint, APK assembly, instrumented compilation and forced formatting checks on macOS.

Run the exact unchanged recorded Agent method using `scripts/android-test-gate.py ui --tests`; device execution is necessary for real Activity destruction, process lifecycle and background dispatcher delivery. Retain counted XML, phone/replay logs, source/APK hashes, exit status and cleanup receipts in durable private evidence. Full configured UI execution remains dispatcher-owned; its completed acceptance is recorded below. No Linux run or new Claude capture is required.

## Open Questions

The controlled probe and focused native run must verify the boundary and whether end/close ordering needs any adjustment. Record any design change under Revisions. Wider sequence or load investigation is needed only if narrow controlled and native evidence cannot explain the failure.

## Scope assessment

One independently useful lifecycle repair with its existing causal controls. The old line/file, consumer and failure-branch forecasts are obsolete; continuation is integration, acceptance and documentation without a new production change.

## Revisions

2026-10-23: The two controlled lifecycle probes both reproduced the old early-close mismatch with test-end and repeated queries pending (2 executed, 0 passed, 2 failed, 0 skipped). Temporary test-local API bridges retained the old callback's no-op terminal preparation; their source and raw XML are retained privately and the bridges are removed from the repair. Two cleanup probes also failed before implementation. The fresh native baseline at plan revision `7835ac552` independently executed once and failed once after test-end, with the three phone queries and final checkpoint still pending. It is distinct from the retained focused-main failure before test-end and the older full-gate checkpoint-only variant. This confirms both sides of the terminal boundary without a preceding-test or load sweep.

The explicit entry points are `LiveSessionCleanupRule.endWhileActivityAlive` and `SuiteConnections.completeBeforePhoneTeardown`. `RecordedSuiteTape.scenarioCompletesBeforePhoneClose` only inspects placement; existing `checkpoint` and `beforeClose` still validate and drain every required exchange, and the consumption fence runs before destruction. `RecordedActivityRule` additionally retains a body/preparation error when the underlying Activity rule throws during destruction; outer session cleanup still releases replacement Activities. Capture and legacy-policy paths remain unchanged.

2026-10-23: Open questions resolved by the controlled probes and fresh macOS native evidence. All six new regression methods passed after repair. The exact recorded Agent method passed 1/1, with zero failures/errors/skips and exit 0 on pushed source `e6cd03c62227ea99e18f012559afd57d3e4471ce`. The phone trace records terminal preparation completing with `early_checkpoint=true`; strict finish consumed the tape. Raw XML, phone logs, effective APK hashes, source identity, exit status, cleanup phases, and verified manifests are retained under `$AGENTS_REPO_PATH/logs/evidence/2491/builder-20261023-macos/`, including every failed attempt. The recording SHA256 remains `40b136aa8f28f08091e179f8e7ba3ae2be644d357554db0f87e862691d9be786`.

The final main merge includes #2483's independent owned-run reveal helper. Its original Agent ownership, collapsed/expanded visibility, uniqueness, and separate main-lane reply assertions remain in place; this ticket does not edit the scenario. The focused pass therefore covers their current main integration as well as terminal cleanup. At this revision, the configured dispatcher UI gate still had to execute and pass this method before verifier PASS; see final acceptance below. Linux is outside this ticket's verification scope.

Final scope measurement before this evidence entry: 478 inserted + 13 deleted lines across nine files, including the plan, with no production edits and no new exported production type. This is a historical size measurement, not a completion ceiling. No split or wider diagnostic is needed.

The final failure probes also repeat the same Activity statement after a body failure, proving that the wrapper cannot reuse its previous primary error. This adds coverage for the wrapper's session-local state without changing implementation or native inputs.

2026-10-23: Final deadline review found that composing individually bounded waits could restart the preparation budget after scenario-complete. The controlled probe delivers one response after 20 seconds and retains another 20-second delivery after the observation; it failed before the correction (5 executed, 4 passed, 1 failed, 0 skipped). `completeBeforePhoneTeardown` now shares one outer 30-second timeout across all its phases. The existing end/checkpoint/close deadlines and original scenario deadlines are unchanged; required later work is neither released early nor discarded to succeed.

## Manual continuation — 2026-10-23

Central revision 36 accepted exact-ticket manual custody. No active worker/support task or
process using the clean retained verifier-2491 checkout remained. The old verifier found no
implementation defect and a current-head native pass, but stopped after the independent
scripts-gate readiness failure #2500. That ticket is now Done and its documentation commit
`b319cc9e3` is an ancestor of clean main integration `33015f5e5b8d03eca56a09a4c92da94d95a87cb9`.
The PR-relative lifecycle implementation and captured recording remain unchanged.

Remove obsolete numeric size forecasts. Keep all replay, ownership, observation, deadline,
cleanup and configured acceptance requirements. Reuse valid causal/95-JVM/native evidence
with its original source identities; the integrated candidate still requires complete normal
configured acceptance and independent re-review. No old focused pass is relabelled as that result.

The retained diagnosis, early-end/captured-close ordering, shared terminal deadline and evidence
limits are now written in `docs/recorded-tests.md` and the owning verification scheduling topic.
At manual continuation, the documentation recorded configured acceptance as pending. Release the obsolete manual
hold after publishing so the normal verifier can collect the required gates and review; keep
this ticket selected through its eventual documentation handoff. No merge/release is claimed.

## Final accepted verification — 2026-10-23

Independent review accepted `eb99f99da89e217aff4ed11365b536576c24a827`, integrating
main `f1f9c3adb2b733c28247ea81d0cdd4353b607f1a`, with no findings on macOS arm64.
Configured acceptance is complete: 220/220 script tests, 5411/5411 JVM tests
(including all five lifecycle and three failure regressions), and 24/24 scripted
tests passed, each with zero failures/errors/skips and exit 0. Build/static checks passed.

The original complete UI gate executed 333: 327 passed, six failed, zero errors,
with one additional ignored `RenameDialogCaptureTest#renameAtFigmaViewport`, exit 1.
The six unrelated failures passed the exact same-tree rerun: 6 executed/passed,
zero failures/errors/skips, exit 0. The dispatcher accepted this gate under its
flaky-rerun policy; historical failures are preserved. The exact
`RecordedMobileSuiteTest#interactiveTurn_backgroundAgent_replyStaysUnderAgent`
passed in the original full run: 1 executed/passed, zero failures/errors/skips,
8.877 seconds. Its phone trace confirmed early checkpoint completion and finished cleanup.

This fresh integrated result is distinct from the retained focused pass at
`00055df3565663f87e0ec378fd5b3bc793a9b833`; its original source/APK identities and
all failed attempts remain recorded in
[terminal preparation and counted evidence](../../recorded-tests.md#recorded-terminal-preparation-2491).
That section also records the authoritative accepted fleet run and evidence limits.
No recording, Agent assertions, strict replay rules or production behavior changed.
No new provider capture was needed; Linux remains outside this ticket's scope.
