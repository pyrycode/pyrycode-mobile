# Recorded test inputs independent of build and emulator

## Scope

Fix two test setup dependencies from the recorded-suite rollout. No production code changes.

The APK rejection unit test supplies temporary built bytes and a deliberately different installed digest. It still requires the exact mismatch error before instrumentation. Real installed-package verification remains immediately before native replay and capture.

During replay, construct the phone client identity with the label already captured in that recording. Keep the current client version. Live capture retains the actual device label. This makes an ARM recording usable on an x86 runner without relaxing request matching or rewriting capture provenance. Peer identities remain separate. Recordings without phone registration need no label override.

## Validation

The existing missing-APK failure and Linux recorded-registration failures are retained. Five focused identity regressions were added before implementation. The eleven-case document test class had three failures before repair.

Run the script suite in a clean Linux checkout before APK assembly. Run the recording unit tests, compile the Android tests, and replay an affected recorded scenario on Linux. Then run the normal complete checks and review. Preserve exact payload matching, registration counts, server ownership and the no-producer replay requirement.

## Limits

The unrelated scripted ping read-confirmation repair remains on ticket 2198 and PR 2199. This change does not modify read targets, history requests, timeouts or scenario assertions.
