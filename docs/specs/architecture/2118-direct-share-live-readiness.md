# Direct Share live readiness and failure evidence (#2118)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_directShareShortcut_arrivesAtPeerWithItsBytes`, `answerChat`, `awaitConnected`, `allowPromptsUntil`; existing delivery proof and deadlines.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `open`, `history`, `retrieveAttachment`, `linkState`, `recorded`; peer admission and content-free failure state.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/LiveConnectionReads.kt`: `callOnLive`; existing reconnect setup semantics, cancellation propagates.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/SendNowConnection.kt`: `awaitSendNowConnection`; established addressed-host combined readiness predicate.
- `app/src/test/java/de/pyryco/mobile/di/RelayConnectionFactoryTest.kt`: `Fixture`, `PeerTransport`, existing Send now readiness tests; real registry with controlled relay and Noise authentication.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionRegistry.kt`: `observe`, `connectionFor`, `selected`; compatibility selection differs from addressed identity.
- `docs/knowledge/INDEX.md`, `docs/knowledge/features/navigation.md`, `docs/knowledge/features/relay-reconnect-supervisor.md`: Direct Share identity and relay versus daemon readiness.
- `docs/knowledge/features/development-verification-emulator-evidence.md`: selected-host false readiness and authoritative repository generation lessons.
- `docs/e2e-interactive-stream.md`: Recent-conversation Direct Share and rung-3/rung-4 evidence boundaries.
- PR #2115 / branch `feature/2100`: `awaitHostConnection` and stage diagnostics; reuse its small helper without depending on its permission-tool scenario.
- `/tmp/builder-2117/evidence/`: digest-matched original #2057 full/rerun/base reports and stderr. Direct Share failed once then passed once; operation unknown.

## Context

The historical full suite executed 65 methods (27 passed, 38 failed), and rerun executed 38 (11 passed, 27 failed), with zero errors/skips. Direct Share failed with a bare 30000 ms coroutine timeout then passed. The base ran 27 methods and did not execute Direct Share. Mobile was `187cd0e213` merged with `cfc5911723`; daemon `7a1daa57bae0`. These artifacts cannot attribute that historical failure to a stage.

A separate controlled defect is actionable: this scenario names host X but `awaitConnected` observes the compatibility-selected relay only. Disconnected selected Y blocks healthy X; healthy Y admits unauthenticated X. Repair the scenario's readiness and make a future failure localizable. No product, UI, wire or daemon change, and no decision record is needed.

## Design

Add the identical `HostConnection.kt` helper from PR #2115, requiring both relay and daemon Connected for `connectionFor(serverId)` at the unchanged deadline. Only this scenario switches to it; compatibility selection is untouched.

Add an internal `DirectShareStage` enum and inline `directShareStep` shared test helper. Each stage emits fixed started/completed/failed records. On failure, capture a best-effort bounded content-free snapshot and retain the original throwable as the cause. Diagnostic failures cannot replace the operation failure. Stage wrappers surround peer admission, list/readiness, repository admission, separate X/Y creation and rename under their existing total deadline, shortcut publication, intent capture/staging, deletion/unreadability, unsent history, explicit Send, permission/turn completion, X/Y history and each retrieval. No new attempt retry is introduced.

The scenario tracks X/Y identities privately only to count peer frames, plus the active repository reference and numeric identity generation. Snapshots contain host-present/selected booleans, static relay/daemon class names, repository presence/current-attempt identity, numeric generations, fixture counts and peer link/count state. No identifiers, text, filenames, URIs, payloads or exception messages enter stage records. The failure snapshot occurs before the existing fixture deletion and peer close.

Retain the exact shortcut target pair, both staged attachments and text, no picker or automatic send, unreadable original sources, explicit Send, one message in X with both original SHA-256 digests and text, and no message in Y. Keep the live method enabled.

Overlaps: #2057, #2100, #2117 and #2119 change other methods in the same live class; all remote overlaps were inspected by file and these edits stay local to Direct Share. The generic helper is copied identically from #2100 to allow an ordinary merge. No real dependency is present.

## State and concurrency model

Test-only synchronous Compose operations and `runBlocking` wrappers retain their existing owning test. Real-registry regressions use `runTest` and the fixture's injected dispatcher. No new job, flow, production state or coroutine scope is created. A timeout diagnostic is an AssertionError with the original timeout as cause; it cannot be retried as a failed request. Inline stage wrappers can surround suspension inside the real asynchronous paths and cannot swallow cancellation; the original timeout remains in the cause chain.

## State transitions and identity reuse

| Event | Contract and regression |
| --- | --- |
| Healthy X, disconnected selected Y | `directShareReadinessIgnoresStoppedSelectedHost`: admit X, preserve selection. |
| Healthy selected Y, X still handshaking | `directShareReadinessWaitsForAddressedAuthentication`: no admission until X authenticates, preserve selection. |
| Host absent | `directShareReadinessRejectsMissingHost`: fail explicitly, never borrow Y. |
| Idle / deadline | `directShareReadinessKeepsDeadline`: fail exactly at 30000 ms, no late completion. |
| Asynchronous create/rename timeout inside outer deadline | `DirectShareStageTest`: capture inner failed stage and original timeout, before cleanup; no subsequent stage runs. |
| Nested peer timeout/assertion | `DirectShareStageTest`: keep the complete original cause chain; safe snapshot and failed marker. |
| Diagnostic or recorder fails | `DirectShareStageTest`: preserve original operation cause and fixed fallback. |
| Repeated successful stages | `DirectShareStageTest`: exactly one started/completed pair per operation and unchanged result. |

## Error handling

Missing addressed host remains an explicit setup failure. Both legs must connect within the original timeout. Stage failures attach a suppressed AssertionError with fixed stage and safe state to the original throwable. Cleanup remains guaranteed and runs after failure capture. Diagnostics are best effort and never initiate I/O, retry, upload or send.

## Testing strategy

Write real-registry counterexamples against the old readiness helper first and retain counted red XML, then repair and retain green XML. Test the stage helper against actual suspension and nested timeouts, including safe diagnostic fallback. Run the affected readiness methods and existing factory tests plus stage tests. The live method stays device-only because it exercises platform dynamic shortcuts, actual content URI deletion, a real daemon and peer retrieval. Run `scripted direct-share` and the named focused live method through ordinary admission, retaining fresh XML/logcat, correlated isolated daemon events, revisions and source hashes. A focused pass cannot settle ordering-dependent historical attribution. Dispatcher owns the configured full live gate after verification and must retain full-suite and named-method counts.

Run lint, assembleDebug, compileDebugAndroidTestKotlin, Spotless and final pre-verify after merging main. Forecast: about 600–750 written lines including plan, regressions and evidence; two internal test helper types at most, one scenario caller and four acceptance criteria. No production files.

## Open Questions

- Historical failing operation remains unknown; preserve this limit in retained evidence and hand the full ordering-dependent run to the dispatcher.
- Reuse/file a native owner-repository blocker only if new correlated evidence demonstrates an upstream defect.

## Documentation handoff

Pending documentation stage: update `docs/e2e-interactive-stream.md`, Recent-conversation Direct Share coverage, with established controlled readiness defect, repair, red/green regression evidence and eventual named configured full-live result. Keep historical attribution limits explicit.

## Security review

**Verdict:** PASS

- [Trust boundaries] `directShareStep` accepts only fixed stages and a scenario-authored snapshot. Shared content and daemon frames remain on existing validated paths; diagnostic code must not stringify those values.
- [Tokens] No credential lifecycle change. Peer identity stays inside the existing harness; stage logs omit tokens, host/chat IDs and keys.
- [Files and storage] Original files remain test-created MediaStore fixtures and are deleted before Send. Retained evidence contains only selected XML counts, fixed stages, hashes and provenance; raw potentially sensitive logs stay outside the repository.
- [Android attack surface] No exported component or intent parser changes. The scenario retains exact shortcut pair and no-picker assertions.
- [Cryptography] Existing vendored Noise IK and SHA-256 byte assertions are preserved; numeric repository generation is diagnostic identity only.
- [Network and I/O] Reuse existing combined readiness and 30-second deadline. No TLS, frame-size, reconnect or protocol changes; see sibling `pyrycode/docs/protocol-mobile.md`, Security model.
- [Errors and logs] SHOULD FIX: diagnostic/recording exceptions must not mask an operation failure. Implement fixed fallback and preserve original cause; prove asynchronous nested timeout paths.
- [Concurrency] No new scope or lifecycle owner. Snapshot is non-suspending and read before cleanup; stage catches retain cancellation causes and perform no retry.
- [Threat model] Malicious relay delay remains bounded by existing deadlines. Hostile daemon payloads never enter diagnostics. Disk token theft and UI screenshot/accessibility leakage are unaffected existing product boundaries; no new exposure is introduced.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-12

## Revisions

- 2026-10-12: asynchronous deadline regressions showed that coroutine recovery can discard suppressed diagnostics and a wrapping CancellationException at the owning withTimeout boundary. `directShareStep` wraps cancellation in an AssertionError with the original timeout as cause, ensuring the failed stage survives and cannot enter callOnLive's request-redial retry. Other failures retain their exception classification with a suppressed diagnostic. Tests require the original failure in the complete recovered cause chain, fixed failed markers and before-cleanup state.
