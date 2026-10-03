# Scanner paste transition reliability (#1637)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `pairHostByCode` synchronizes the scanner paste lookup through Compose; the collision scenario retains row/thread, rename, reconnect, restart and cleanup checks.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt`: `PyryNavHost` owns scanner permission, state collection, camera mounting and paste navigation.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/CameraPreview.kt`: `CameraPreview` binds a real TextureView preview and analyzer to the destination lifecycle.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerScreen.kt`: `ScannerViewport` exposes the paste action and has static scan guides.
- `app/src/androidTest/java/de/pyryco/mobile/ScannerLivePreviewDeviceTest.kt`: real camera state inspection through the activity rather than Compose semantics.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/E2eTestApplication.kt`: fake-backed device launch and real-backed live launch share the production graph.
- `scripts/android-test-gate.py`: focused scripted execution and dispatcher-owned live evidence.
- `docs/e2e-interactive-stream.md`, “What rung 3 is made of”: existing two-host contract and harness ownership.
- `docs/knowledge/features/scanner-screen.md`, `scanner-screen-edge-cases-and-testing.md`, `camera-preview.md`, `navigation.md`: camera lifecycle, static scanner guides and manual-pairing navigation.
- `docs/knowledge/features/development-verification.md` and its test-scheduling topic: fresh execution counts and scoped verification.

## Context

The retained stderr from the #1581 gate identifies Compose's busy idling resource while `pairHostByCode` reads the paste link after opening the scanner. Five methods ran, one failed, none skipped; the collision method passed alone on the same tree. The post-test launcher focus does not establish the failing activity state. The artifact directory is gone, so fresh device evidence must distinguish camera/view idling from lifecycle loss before selecting a fix. No architecture decision record is needed.

## Design

Reproduce the production list → scanner → paste form transition with camera permission granted on the managed device and fake repositories. Inspect the activity lifecycle and CameraX preview without a Compose idle barrier when necessary. Keep diagnostics static and content-free. A device regression will enforce successful transition under the demonstrated problematic state, and share any repaired test-navigation helper with the live pairing path if that is where the cause lies.

Limit the repair to the demonstrated scanner-transition cause. Preserve the existing live collision scenario's host ownership, rename isolation, both reconnects, graph restart and host-B cleanup. Do not add retries, drop assertions or widen timeouts. Record the concrete cause, reproduction and final repair contract under Revisions once investigation settles them.

Overlap: #1642 adds a separate live scenario and a thread callback in `PyryNavHost`; local additive edits can merge independently.

Forecast: approximately 250 written lines, at most two production files, at most two new test/helper declarations, fewer than ten consumers, three acceptance criteria and no new state-machine reject branches. Recheck against the final repair.

## State and concurrency model

Use the existing activity-owned camera and destination-owned `ScannerViewModel`. Device inspection runs on the instrumentation/main thread boundary without creating a production coroutine or changing socket ownership. Test activities close before fixture cleanup. Live graph reconstruction and host removal retain their existing ordering.

## Error handling

A failed transition must fail the test with the failing phase, lifecycle and camera stream state, without codes, tokens, keys or input contents. Keep product error and pairing validation contracts unchanged unless reproduction specifically demonstrates a defect there.

## Testing strategy

Write and run the device regression against the current implementation first. It needs real CameraX view/lifecycle behavior, which Robolectric and an empty preview slot cannot reproduce. After repair rerun the regression and affected device class, relevant existing pairing unit tests, one focused scripted scenario, lint, debug assembly, instrumentation compilation and forced Spotless validation. No whole-project test sweep.

The dispatcher owns a fresh `python3 scripts/android-test-gate.py live` run. Its evidence must include `interactiveTurn_twoHostsCollidingConversationId_stayPerHost` with executed/failed/skipped counts. If `pairHostByCode` changes, list `all` in the PR's Live tests section because it is shared. Builder results do not claim this pending acceptance passed.

## Open Questions

- What concrete camera/view or activity state keeps Compose busy? Resolve with fresh reproduction before selecting a repair.
- Does the repair belong in shared test navigation or the production camera lifecycle? Record the demonstrated boundary in Revisions.

## Security review

**Verdict:** PASS

- [Trust boundaries] Keep code entry and QR parsing in the existing production routes; diagnostics never inspect payloads or promote fixture data to trusted credentials.
- [Tokens] SHOULD FIX: diagnostics must not print semantics trees once a pairing code is entered. Restrict evidence to static phase, activity lifecycle and camera stream state.
- [Files and storage] Test fixtures use the existing app-private paired store and are removed after activity close; no credential file is added.
- [Android attack surface] No exported component or permission contract changes are planned. The regression grants CAMERA only to the test target.
- [Cryptography] Existing Keystore wrapping and Noise handshake remain outside the repair surface; fixtures contain dummy values only.
- [Network and I/O] Device reproduction uses fake repositories. Live validation remains with the dispatcher and isolated test daemons.
- [Errors and telemetry] No payload, token, key, code field contents, daemon messages or full UI tree in diagnostic output; failures identify only the transition state.
- [Concurrency] Test activity ownership remains explicit; do not introduce asynchronous jobs without cleanup or allow a camera callback to outlive disposal if that proves causal.
- [Threat model] Relay compromise, token theft and hostile wire frames retain existing protocol protections; this repair does not alter them. Avoid new UI-side credential leakage from diagnostic screenshots or trees.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-03
