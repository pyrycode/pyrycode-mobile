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

## Documentation handoff

- Pending documentation stage: `docs/recorded-tests.md`, replay contract and verified evidence: describe replay-only captured phone labels, current client versions, unchanged live identity and strict matching; link failed and repaired Linux evidence and the final disposition of #2198.
