# Background progress live setup (#1695)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_backgroundAgentProgress_showsOnRunningCard`, `awaitConnected`, `answerChat`, `peerStep`, `progressFrames` and the unrecognized-row sentinel distinguish setup from progress and rendering.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `open`, `dialLink` and `linkState`; opening requires an authenticated handshake and a settling request.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/PeerWait.kt`: existing bounded wait helpers and their timeout contract; the setup diagnostic belongs beside them.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/LiveConnectionReads.kt`: `callOnLive` already follows repository replacement during chat creation.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/PeerDeviceStaticKeyStore.kt` and `app/src/test/java/de/pyryco/mobile/e2e/PeerDeviceStaticKeyStoreTest.kt`: merged #1698 retains exact host/token identity with defensive copies; reuse this repair.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/PeerIdentityLifecycleTest.kt`: checks sequential peers across close and graph rebuild, including graph cleanup.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/BackgroundTaskPanelTest.kt`: existing running-card activity and tools-count coverage.
- `docs/knowledge/INDEX.md` and `docs/knowledge/features/development-verification-test-scheduling.md`: process identity, copy safety and lifecycle-plus-binding regression evidence.
- `docs/e2e-interactive-stream.md`: background-task-progress scenario, one real turn, live selection and absence of scripted background-task frames.
- `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/docs/protocol-mobile.md`: canonical token/static-key binding and Security model; do not weaken it.

## Design source

N/A: test setup diagnostics only; no product UI or visual changes. Preserve the existing card assertions.

## Context

The four retained #1631/#1637 branch/base reports all show the target failing with a bare 30-second coroutine timeout. Branch reports each executed 53 tests with 20 failures and no skips. Base XML actually contains focused runs: 19 executed with 17 failures for #1631 and 18 for #1637, no skips. The bare stack cannot independently identify which setup await expired.

Their matching daemon logs contain 102/84/102/90 `static_key_mismatch` rejections, all `bound_to_other_key`. The old peer generated a fresh static key for a reused token. That prevents `SecondClientPeer.open` settling, before a Claude turn can start. Connection readiness and chat creation are separate phone operations; `answerChat` already uses `callOnLive`. The progress wait is 180 seconds and names task/frame counts; card rendering uses a Compose timeout with its own assertion. Neither matches this recorded 30-second coroutine failure.

The shared cause is already repaired by merged #1698 / PR #1701 (`50a59ffe`, `52b646ac`, merge `d63e304b`), present in this checkout. Its dispatcher report `2026-10-03T23-52-08-061Z_real-claude-gate_#1698.log` executes this method and passes it: full run 53 executed, 52 passed, 1 unrelated question-answer failure, 0 skipped. The corresponding daemon log has zero static-key mismatch rejections. The unrelated method passed its focused rerun. This is evidence for the shared opening defect, not a fresh live acceptance run of #1695.

No duplicate identity repair or architecture decision is needed. In-flight #1631 and #1642 touch `InteractiveStreamE2ETest` in other methods; keep this edit local and build through the overlap.

## Design

Add `LiveSetupStage` and an inline `liveSetupStep` boundary to `PeerWait.kt`. The three enum labels are fixed: phone connection readiness, chat creation, peer opening. The wrapper returns the original result; a `TimeoutCancellationException` becomes an assertion identifying that stage and retaining its cause. Other exceptions and ordinary cancellation pass through. This wrapper surrounds existing blocking test-worker operations; it introduces no deadline, retry or coroutine owner.

Use it only around this scenario's `awaitConnected`, `answerChat` and peer-open call. Keep `peerStep` inside the opening boundary so its existing content-free link-state diagnostic remains available. Do not alter other scenarios, timeout constants, live selection, prompt, permission handling, progress wait, same-card recorded-activity/tools-count matcher, or `UnrecognizedRowSentinel`.

## State and concurrency model

No new state or jobs. Existing per-operation deadlines and `runBlocking` test-worker boundaries remain. `SecondClientPeer` owns its IO scope and is closed in the existing `finally`; the phone's lifecycle driver keeps its foreground/background ownership. Inline setup classification adds no suspended work or shared mutable state. Normal cancellation is not converted into an assertion.

## Error handling

Setup timeout assertions carry fixed stage labels. Peer opening retains `peerStep`'s session-state assertion; progress arrival retains task/frame counts; card rendering retains its tools/frame diagnostics. Non-timeout errors keep their original type and identity. Neither diagnostics nor tests print tokens, keys, pairing records, chat names, ids, payloads or daemon-authored activity text.

## Testing strategy

Write `LiveSetupStepTest` first, observing the original bare timeout fail stage assertions. Use virtual-time stalls for connection and chat creation, and the existing `RedialingLink` retry path for a peer that cannot authenticate/settle. Prove the named stage, original cause, unchanged deadline, successful result and propagation of non-timeout failure and ordinary cancellation. The peer-opening scenario must remain before progress or rendering work.

Run existing `PeerDeviceStaticKeyStoreTest`, `RedialingLinkTest`, `PeerWaitTest` and `BackgroundTaskPanelTest` alongside new regression coverage. Run the existing lifecycle device regression followed by `RepositoryBindingInstrumentedTest` in one process: real Android graph/lifecycle ownership requires instrumentation. Run the deterministic reconnect scenario (zero Claude turns). No background-progress rung-4 twin is available because scripted fakeclaude emits no `background_task_*` frames.

Run lint, assembleDebug, compileDebugAndroidTestKotlin, spotlessApply and forced spotlessCheck. The dispatcher owns a fresh full live suite (`## Live tests`: `all`); retain executed/failed/skipped counts, explicitly confirm the target ran/passed, and compare binding diagnostics. Until then live acceptance remains pending.

## Open Questions

None. The bare historical stack's exact call site is unrecoverable; the shared binding cause and repaired target PASS are directly recorded. New stage diagnostics prevent the same ambiguity from recurring.

## Documentation handoff

- Pending documentation stage: `docs/e2e-interactive-stream.md`, background-task-progress scenario: record the separate connection-readiness/chat-creation/peer-opening diagnostics and the #1698 shared key-binding repair. Preserve the one-turn hold, sentinel and same-card assertions.
- Pending documentation stage: in that scenario's verification evidence, record the fresh #1695 full live counts, named target result and daemon binding diagnostics supplied by the dispatcher. Prior #1698 evidence is not a fresh #1695 pass.

## Security review

**Verdict:** PASS

- [Trust boundaries] `liveSetupStep` receives a fixed `LiveSetupStage`, never daemon text. Existing protocol decoding and inert, bounded activity rendering stay in place; no new inbound frame path.
- [Tokens and credentials] The merged `PeerDeviceStaticKeyStore` retains peer identity in process memory and returns key copies. No credential generation, persistence, rotation or revocation change; token-to-key binding remains enforced by the daemon.
- [Files and storage] No file reads/writes added at runtime, no input-derived paths, and no secret storage or backup changes. Evidence includes counts and static codes only.
- [Android attack surface] No manifest, exported component, intent, provider, keyboard or screenshot change. The sentinel stays enabled.
- [Cryptography] Reuse fresh per-dial Noise state and the established key store; no primitive, cipher, nonce or TLS change. The retained static identity is not a retained cipher session.
- [Network and I/O] Existing timeout and redial bounds remain. A malicious relay can deny service but cannot make setup wait indefinitely or produce a progress/card PASS without the recorded frame and rendered card.
- [Errors and telemetry] Fixed stage labels and `peerStep`'s link status contain no ids, tokens, keys or frame contents. Existing causes are retained; the classifier never interpolates their messages into a new diagnostic.
- [Concurrency] Inline classification owns no job or mutable state and creates no retry. Peer closure remains guaranteed by the scenario's `finally`; ordinary cancellation propagates.
- [Threat model] Protocol Security model checked: compromised-relay delay/drop remains bounded by current deadlines; token theft/key extraction is not expanded because no storage changes; hostile daemon frames still use existing decoding and text bounds; UI leakage is not expanded because no new UI or captures. Existing residual protocol risks remain with their owning repository, outside this diagnostic repair.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-04

## Revisions

### 2026-10-04 — opening diagnostic boundary

The opening boundary calls `peer.open` directly and reads `peer.linkState` only on timeout, mirroring `peerStep` without nesting its assertion conversion inside `liveSetupStep`. Nesting would hide the fixed peer-opening label because non-timeout assertions deliberately pass through. The new regression uses the real `RedialingLink` retry loop with rejected/unsettled dials and proves that no progress/rendering work follows, while preserving the existing 30-second deadline and content-free link status. The first run executed 6 tests with 3 failures and no skips: all three setup stages lacked the required diagnostic; successful results, refusals and ordinary cancellation already passed.
