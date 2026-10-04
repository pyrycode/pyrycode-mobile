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

## Revisions

### 2026-10-03 — exact failure phase and controlled initialization regression

The helper in the failing `70aee9838d` revision maps the retained stack to the code-field lookup after Paste was clicked. The issue's initial paste-link interpretation was incorrect. `CameraPreview` disposes during this scanner → form transition and currently calls the provider future's blocking `get()` on main. The fresh device regression passed five entries once CameraX was STREAMING, with MainActivity RESUMED and focused; this does not establish that pending initialization is safe.

Extract the existing provider listener/disposal behavior into an internal `CameraPreviewBinding<T>` beside the camera composable, preserving behavior first. Its generic provider handle permits a small controlled future fake without a new mocking dependency or a real camera in the JVM regression. Tests will demonstrate whether disposal reads a pending future and whether a late listener binds after exit. The repair, if those fail, must make disposal nonblocking, retain only a successfully bound provider for unbinding, and ignore initialization completion after disposal. All listener/disposal access stays on the existing main executor. Keep the composable signature and its single production caller unchanged.

The JVM lifecycle test belongs in `app/src/test`; it exercises the binding owner and controlled future, not screen geometry. The device regression continues to prove real camera list → scanner → paste → cancel → Back transitions. Production files are limited to `CameraPreview` and its binding owner. Forecast remains below 500 written lines, with one internal production type and no changed consumer signature.

Security review addendum: disposing while initialization is pending must not wait for I/O on main, and late completion must not bind a camera or publish an error to a departed scanner. Tests cover both. No code, credential, frame or decoded text is included in diagnostics. The protocol's Security model was read in the sibling pyrycode checkout; no wire or endpoint trust contract changes.

### 2026-10-03 — red lifecycle checks and real cold-start device control

The behavior-preserving extraction ran five JVM tests: three failed. They show a pending `get()` during disposal, a late bind after disposal, and a late error after disposal. The two mounted-state checks passed. The device regression now also configures CameraX with a held camera executor before entering the real scanner, requires its provider future to remain pending, and requires the real code form to open while initialization is still held. A 30-second cleanup fuse releases the executor solely to avoid hanging a broken run; any use of that fuse fails the assertion. It is not a retry or a passing timeout. After explicit release, the same test retains its five STREAMING-camera transitions. CameraX is shut down before and after the fixture so suite order does not change this cold-start condition.

### 2026-10-03 — reproduced scanner-exit stall and final repair contract

The real-device cold-start test ran once and failed with “scanner exit waited for camera initialization”. Its pre-Paste record shows MainActivity RESUMED, focused, with initialization pending. The code-field lookup returned only after the 30-second cleanup fuse released CameraX's executor. The post-test focus record names EmptyHomeActivity with `anr=none`, matching the historical observation because ActivityScenario cleanup precedes the failure listener. This demonstrates a camera-initialization race at the actual retained failure phase: scanner disposal calls blocking `get()` on main. A rerun can pass when initialization finishes before disposal; the five STREAMING-camera baseline transitions passed.

`CameraPreviewBinding` now records a resolved provider before binding, so a partial bind failure still receives the old unbind cleanup. Disposal marks this composition departed and unbinds only that recorded provider, without reading or cancelling the shared future. A queued completion after departure returns without binding or surfacing an error. Listener and disposal run on main as before. Added tests cover a completion queued before disposal and cleanup after bind failure. Both Open Questions are resolved; the fix belongs in the production camera lifecycle, and the existing live helper/assertions remain unchanged.

Security review: PASS. The asynchronous completion guard prevents camera activation and error callbacks after scanner exit. Disposal performs no wait on initialization. Existing error text stays static, no payload logging is added, and the shared process provider is not cancelled or shut down by product disposal. Device fixture shutdown is test-only.

### 2026-10-04 — live-gate attribution and guaranteed fixture cleanup

The #1637 full live report executed 53 tests, failed 20 and skipped zero; the collision method passed. The offered-file method failed eighth in that run but passed first in the 19-test base comparison. Both trees still used `SecondClientPeer.ThrowawayDeviceKeyStore`, creating a new key for the same suite pairing token. The retained main-daemon logs (`pyry-e2e.Cf5gl9/daemon.log` and `pyry-e2e.tWgsL4/daemon.log`) contain respectively 102 and 90 `v2.handshake.reject.static_key_mismatch` events, with `bound_to_other_key`. This establishes a shared authentication defect and explains why changing the selected methods/order changes which peer passes; the timeout stack alone does not identify its suspension point. All of those rejections name the harness’s main-host peer device; the base run accepted that peer before the later rejections. The camera is never mounted by the offered-file scenario, and this branch does not change peer setup or transport.

The shared identity repair from #1686/#1698 is already merged into this worktree. The dispatcher’s subsequent #1686 full live report executed 53, failed zero and skipped zero, passing both offered-file and collision methods in their original eighth/fifth positions. Reuse that landed repair and its `PeerDeviceKeyStoreTest` / `PeerIdentityLifecycleTest` regressions; do not duplicate it or weaken the offered-file scenario. A fresh full live run of this candidate remains pending.

Address the verifier's SHOULD FIX in `ScannerPasteTransitionDeviceTest`: enclose setup, including the dummy host save, in a cleanup owner. Add a test-only `ScannerTransitionCleanup` under `app/src/sharedTest/java/de/pyryco/mobile/` that registers teardown on acquisition, executes all actions in reverse acquisition order, and preserves the first cleanup failure with subsequent failures suppressed. Kotlin `use` preserves a setup/transition failure when teardown also fails. Register host removal first, executor release/closure next, CameraX shutdown before starting its future, and safety-scope cancellation last. ActivityScenario still closes before those actions, and the successful device path asserts the fixture host was removed. Camera shutdown, executor closure and host removal must each run even if an earlier action fails.

Write JVM regressions before the cleanup owner: failures during setup still remove the host; camera shutdown failure still closes the executor and removes the host; transition failure remains primary with cleanup failure suppressed; multiple cleanup failures retain every failure. Run these with existing camera/pairing and peer identity tests, the affected device class, one scripted ping, lint, assembly, instrumentation compilation and forced formatting. This adds one internal test type and approximately 150 lines, remaining below 700 total written lines and all sizing limits. No production or live assertion changes are planned.

Security review addendum: PASS. The cleanup owner holds only callbacks; dummy pairing removal remains app-private, every acquired executor/scope has guaranteed teardown, and failures use static fixture labels. No credentials, key material, payload text or semantics tree is logged. No wire, component, camera permission or cryptographic contract changes.

## Documentation handoff

Pending for the documentation stage, as requested in the verifier verdict:

- `docs/knowledge/features/camera-preview.md`, disposal / limitations: describe nonblocking disposal, retained-provider cleanup and ignored completion/error callbacks after exit; replace the obsolete accepted-blocking example and lifecycle-testing claim.
- `docs/knowledge/features/scanner-screen-edge-cases-and-testing.md`, testing guidance: document the held-initialization device regression and seven lifecycle checks; explain why warm STREAMING runs miss the race, why fuse release cannot count as success and why post-test launcher focus is not the failing activity state.
- `docs/e2e-interactive-stream.md`, verification evidence: reconcile the original failure phase with the code-field lookup after Paste, link the published causal/red-green evidence, and record this candidate's fresh full live counts and named-method result once the dispatcher supplies them.
