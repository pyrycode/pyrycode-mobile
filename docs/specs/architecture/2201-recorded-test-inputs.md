# Recorded test inputs independent of build and emulator

## Scope

Fix two test setup dependencies from the recorded-suite rollout. No production code changes.

The APK rejection unit test supplies temporary built bytes and a deliberately different installed digest. It still requires the exact mismatch error before instrumentation. Real installed-package verification remains immediately before native replay and capture.

During replay, construct the phone client identity with the label already captured in that recording. Keep the current client version. Live capture retains the actual device label. This makes an ARM recording usable on an x86 runner without relaxing request matching or rewriting capture provenance. Peer identities remain separate. Recordings without phone registration need no label override.

## Validation

The original author reported an eleven-case document test run with three failures before repair. Its raw results and exact source identity have not been recovered; that report alone is not retained failure evidence. Five identity regressions and the implementation were committed together in `589bf92393bf262d1487fdcb4fc3e623d3ea24a4`.

Run the script suite in a clean Linux checkout before APK assembly. Run the recording unit tests, compile the Android tests, and replay an affected recorded scenario on Linux. Then run the normal complete checks and review. Preserve exact payload matching, registration counts, server ownership and the no-producer replay requirement.

## Limits

The unrelated scripted ping read-confirmation repair remains on ticket 2198 and PR 2199. This change does not modify read targets, history requests, timeouts or scenario assertions.

## Files read

- `app/src/sharedTest/java/de/pyryco/mobile/recording/RecordedSuiteDocument.kt`: `replayClientInfo` selects only the captured phone label.
- `app/src/androidTest/java/de/pyryco/mobile/recording/RecordedSuiteNative.kt`: `bindings` applies that input only for replay.
- `app/src/test/java/de/pyryco/mobile/recording/RecordedSuiteDocumentTest.kt`: the five identity regressions and strict tape checks.
- `docs/recorded-tests.md`: preserve raw capture provenance and complete request matching.
- PR #2204 verifier verdict: chronology and raw pre-repair evidence are missing; all 71 Linux recordings passed, but scripted-all failed on inherited #2198.

## Revisions

### 2026-10-16: truthful chronology and evidence recovery (review findings 1–2)

The phone-identity plan was late: this document first appeared in `589bf92393bf262d1487fdcb4fc3e623d3ea24a4` alongside its implementation and tests. No preceding committed or independently reviewed phone-identity design has been located. The architecture document supplied on re-entry is this same text, not evidence of prior review. This revision records the plan-before-code violation; it cannot retroactively satisfy that ordering.

The earlier standalone APK plan was committed as `f4cba36f6` at `docs/specs/architecture/2196-isolate-apk-negative-control.md`. It belongs to #2196 and does not establish prior design review for phone identity. PR #2204 was subsequently merged by explicit operator instruction as `ac4c43f7795d19fe535b2ace092013a75563b184`; that merge did not establish verifier acceptance.

Before new test edits, this revision plans a controlled Linux reproduction. Move the identity equality assertion after the tape request in `replayUsesCapturedPhoneNameWhileKeepingCurrentVersionAndExactTape`, so the unrepaired input reaches the real strict matcher. Temporarily use the exact `RecordedSuiteDocument` and `RecordedSuiteNative` files from pre-repair parent `50ba564df`, retaining the current regression tests. A test-source extension returning the supplied `NoiseClientInfo` only bridges the API absent in that parent; it represents the unchanged runner identity passed by its native factory. It does not change the tape or recordings. Preserve the patch, source hashes, commands, raw XML and counts. Also reproduce the affected native method against that exact pre-repair native factory, then restore the repaired files and remove the temporary extension before validation and commit.

Run the focused document class and recording tests after restoration, compile the native sources, and replay the affected method. Retain failed and repaired raw results in private persistent `logs/evidence/2201/` storage with verified hashes; commit only a content-free evidence index under test assets. Existing full recorded-suite Linux evidence remains valid for the unchanged implementation. A fresh complete configured dispatcher gate is still required after the separate #2198 repair; no focused result replaces it.

There are no new exported types, signatures, lifecycle state or failure branches. The rework is bounded to this plan, one test assertion's order and its evidence index, well below 1600 written lines. No in-flight branch overlaps these edits.

### 2026-10-16: retained failed and repaired Linux results

The controlled pre-repair run executed the same eleven document cases with three failures, including the actual strict `register_push_token` mismatch. Its exact pre-repair native factory also failed the affected method (one executed, one failed). Restoring the merged implementation passed all 85 recording unit tests, including all eleven document cases and wrong-payload rejection. Pushed source `f402e59722c7dae8b1e828ee2be4feba2b4cb9a4` then passed all 71 native recorded scenarios, including `interactiveTurn_pingPrompt_footerShowsContextUsage`, with zero failures, errors, skips or producer invocations. These are fresh reproductions, not recovery of the original reported run.

Raw XML, phone logs, commands, source snapshots, the reproduction patch and verified `SHA256SUMS` are retained owner-only at `/work/Projects/pyrycode-mobile-agents/logs/evidence/2201/rework-20261016-identity/`. The content-free index is `app/src/androidTest/assets/recorded-2201/evidence.json`. All 73 recording/index JSON files retain their original hashes. The implementation files match `50ba564df` for failure and `589bf9239` for repair; the temporary unit API bridge is retained only in the private failed-source snapshot. The initial device admission timeout is explicitly recorded as zero executed and not a test result.

Review finding 3 remains a separate gate dependency: #2198 is linked as a blocker. A fresh complete green scripted-all dispatcher result after its repair remains required; this ticket's 71-case recorded replay does not substitute for it.

### 2026-10-17: inherited scripted gate resolved (review finding 3)

#2198 is closed after PR #2199 merged as `a0c7db693`; merge `8ddba7509` brings that separate repair into this branch. No read handling, scenario assertion or timeout is changed by #2201.

The post-repair #2198 dispatcher run at `894bc402a13f6c66c2b5f11af76453ed3dbd27af` passed all eight configured Linux gates at `2026-10-16T23:53:14.328Z`. Its complete `scripted-all` XML reports 22 executed/passed, zero failures, errors or skips, including both ping methods. This supplies the complete post-repair run requested by finding 3; it does not replace this candidate's normal dispatcher verification.

Retrieved evidence bundle `cfd334f0e38c9dd9ff98cd8990af82409a20d64befdc2bcf8a017ef70dd8635e` is retained owner-only at `/work/Projects/pyrycode-mobile-agents/logs/evidence/2201/resolved-2198-20261017/`. All 128 bundle-file hashes verified. `logs/verifier-gate__2198_8.log` contains the complete counted XML; its adjacent stderr log retains scenario diagnostics and source identities. The pass marker and verifier verdict remain alongside them. The evidence index records the XML and manifest hashes. Earlier failed results remain preserved.

This re-entry changes only the plan and evidence index, with no new types, signatures, lifecycle state or failure branches. No other in-flight feature branch touches either file. Rerun the recording unit selection and native recorded class, including the affected method, then the builder's final format, assembly and compile checks on the merged branch. The native class also covers main's intervening #2206 negative-observation setup change, which was absent from the earlier 71-case run. The dispatcher owns this candidate's full UI and scripted-all reruns; documentation remains pending with the documentation stage.

## Documentation handoff

- Pending documentation stage: `docs/recorded-tests.md`, replay contract and verified evidence: describe replay-only captured phone labels, current client versions, unchanged live identity and strict matching; link failed and repaired Linux evidence and the final disposition of #2198.
