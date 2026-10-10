# #1896: diagnose and repair the held-per-conversation live permission scenario

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_permissionPrompts_heldPerConversation`, `answerChat`, `pairAnswerHost`, `tapInPrompt`, and the named question-answer steps establish the existing contract and diagnostic pattern.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `open`, `awaitFrame`, `awaitPermissionModal`, `awaitModalDismissed`, `allowOnce`, and `linkState` distinguish peer admission, upstream prompt arrival, and answer delivery.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/QuestionAnswerStage.kt` and `app/src/test/java/de/pyryco/mobile/e2e/QuestionAnswerStepTest.kt`: mirror the fixed-label, original-cause timeout diagnostic without changing shared helpers.
- `docs/knowledge/features/current-modal-state.md`, “The data path”: prompts are held process-wide and projected per conversation; the scenario must retain both outstanding identities.
- `docs/knowledge/features/permission-modal-overlay-testing.md`: preserve the armed phone answer and the peer-answered sibling prompt.
- `docs/knowledge/features/development-verification-emulator-evidence.md`: named failing waits, upstream think time, and the loss of artifacts when gate worktrees are removed.
- `docs/e2e-interactive-stream.md`, “What rung 3 is made of”: isolated real-Claude harness and live evidence ownership.
- Sibling `pyrycode/docs/protocol-mobile.md`, “Security model”: permission answers are remote control; diagnostics must disclose no decrypted content or pairing material.

## Context

The initial #1731 stdout summary has 64 executed, 13 failed, 0 skipped; its rerun has 13 executed, 1 failed, 0 skipped and this method passed. Surviving initial stderr contains this method's `TimeoutCancellationException: Timed out waiting for 30000 ms`, but only coroutine scheduler frames. The deleted gate worktree no longer supplies the original per-test logcat or detailed XML. The 30-second failure is not evidence of either 90-second upstream prompt wait failing. No repair cause is established yet; retain this red and diagnose rather than applying the #1480 timeout change speculatively. No decision record is needed.

Overlaps: #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1869, #1879, #1888, and #1900 edit other scenarios in `InteractiveStreamE2ETest`; this change stays local to the held-per-conversation method.

## Design

Add a test-only `PermissionIsolationStage` and `permissionIsolationStep` beside the existing question-answer diagnostic. Wrap each setup, send, upstream arrival, phone-render, arm/confirm, answer-dismissal, and turn-end operation with a distinct fixed label. A timeout becomes an assertion carrying the original exception as cause and lazily read content-free peer link state. The wrapper never retries, changes a deadline, swallows an assertion, or copies daemon-authored text into its message. Record progress using fixed stage labels only, so a surviving logcat can establish the last completed stage.

Run the focused method to obtain stage evidence. Make only the smallest repair supported by that evidence and document its contract and regression here under Revisions before handoff. If the cause is external, link its owning issue as a blocker instead of claiming a repair. Keep this method enabled and its existing two-prompt/armed-answer assertions intact. Do not await A's turn end: B's send moved follow-active to B.

## State and concurrency model

No production state, dispatcher, job, flow, or connection lifetime changes. The wrapper runs inline in the existing test; cancellation other than a deadline passes through unchanged. The scenario closes its peer and removes its exact paired host in `finally`. Peer link diagnostics are read only upon failure.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| Any setup or permission wait expires | `PermissionIsolationStepTest` checks all fixed stages, original deadline and retained cause. |
| Successful step or ordinary cancellation | `PermissionIsolationStepTest` verifies lazy diagnostics and transparent cancellation. |
| A and B raise distinct outstanding prompts; return to A | Existing live method retains both identities and shows A again. |
| Phone arms then confirms A; B is reopened | Existing live method requires A's matching dismissal with `source=remote`, `outcome=allow_once`, and B still visible. |
| Peer allows B | Existing live method requires B's dismissal and B's turn end. |

## Error handling

Only `TimeoutCancellationException` and `ComposeTimeoutException` acquire a fixed operation label; their causes remain intact. All other failures retain their original type and object. Do not publish raw semantics trees, frame content, prompts, keys, tokens, pairing codes, or authentication output. Commit sanitized counted XML and the relevant stack/stage evidence under test resources once obtained.

## Testing strategy

Write and run failing diagnostic unit tests first, then implement the wrapper and scenario instrumentation. Unit tests use virtual time to check deadlines and do not exercise Claude. Run focused modal fold/answer tests if the evidenced repair changes their path. The existing rung-3 method needs a device because it drives real relay and daemon I/O and a physical permission tap. A focused diagnostic execution is builder evidence; a fresh passing dispatcher full live gate is separately pending acceptance. No timeout increase, retry, or skipped assertion is planned. Run lint, assemble, instrumentation compilation, formatting and final pre-verify after merging main.

## Open Questions

- Which 30-second coroutine operation failed? Surviving stderr cannot answer; resolve through named-stage execution and preserve the evidence.
- Does the diagnosed condition belong to this scenario, Mobile production, or a sibling repository? Choose the smallest evidenced repair or external blocker after diagnosis.

## Security review

**Verdict:** PASS

- [Trust boundaries] Fixed enum labels and `SecondClientPeer.linkState` are the only added diagnostic inputs. Decrypted modal text stays solely in the existing typed decoder and UI matcher.
- [Tokens] No credential generation/storage changes. The gate alone retrieves its test login; diagnostics never read pairing arguments or token fields.
- [Files and storage] Sanitized evidence uses fixed repository paths under test resources; no wire data constructs filenames. Existing private key stores are unchanged.
- [Android attack surface] No component, intent, provider, capture policy or permission changes; instrumentation remains test-only.
- [Cryptography] Noise handshake, identity binding and nonce lifetime remain unchanged.
- [Network and I/O] All original deadlines and isolated harness identity remain. A delayed/malicious relay still fails a bounded wait rather than a retry masking failure.
- [Errors, logs and telemetry] MUST FIX avoided by design: emit only fixed stage labels, link counters, counts and sanitized stacks; never copy prompt/frame content into diagnostics or published evidence.
- [Concurrency] Inline wrapper creates no scope or job and preserves ordinary cancellation. Peer cleanup remains guaranteed on success and failure.
- [Threat model] Relay delay/drop is diagnosed without plaintext disclosure; hostile daemon text is not logged. Rooted-disk theft and UI-side screenshot/accessibility leakage retain existing Keystore/capture protections outside this test-only change. No new mitigation is claimed.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-10

## Revisions

- 2026-10-10: the instrumented isolated method passed (1/1) and a narrow shared-process selection with the original suite's earlier session-error and selection-copy methods passed (3/3). Neither establishes a repair cause. Add `PermissionIsolationPhoneProbeTest` under shared tests to exercise the scenario's original scroll/physical-two-tap sequence against a short thread and varied inert permission text lengths. This investigates whether a received/rendered permission can lose the confirmation tap behind thread chrome. Preserve any failing probe and its measured bounds; do not attribute the historical occurrence without evidence. No production change or timeout adjustment is authorized by these passing diagnostics.
- 2026-10-10: all three physical-tap probes passed. The controlled short-thread fixtures do not evidence a missed confirmation tap. Retain the probes as investigation coverage and retain the original physical sequence unchanged; it would be speculative to replace it with another helper as a flake repair.
- 2026-10-10: the direct original predecessor, `interactiveTurn_compactWithAttachment_compactsAndClearsTheStrip`, and the instrumented permission method both passed in the same process (2/2). Sanitized counted XML and fixed-stage logcat excerpts for all three live selections are retained under `app/src/test/resources/e2e/permission-isolation-1896/`. This method passed on all three diagnostic executions; no new failure was captured. The historical operation and cause remain unresolved, so no production fix, deadline change or flake-repair claim is made. A new failing occurrence with the named stage and per-test/daemon artifacts is still required before implementing a justified repair. Full dispatcher live validation also remains pending; these builder diagnostics do not fulfill that acceptance criterion.
