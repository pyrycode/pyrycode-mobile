# #1896: retain permission-isolation failing-stage diagnostics

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

The maintainer recovery scope on 2026-10-10 is diagnostics only. This checkpoint reviews and verifies the existing test instrumentation and retained evidence in PR #2030; it does not diagnose or repair the intermittent failure. Cause-and-repair work remains open in #2047. Passing repetitions cannot establish a repair, and no further repetition to infer a cause is planned. No production files change.

The initial #1731 stdout summary has 64 executed, 13 failed, 0 skipped; its rerun has 13 executed, 1 failed, 0 skipped and this method passed. Surviving initial stderr contains this method's `TimeoutCancellationException: Timed out waiting for 30000 ms`, but only coroutine scheduler frames. The deleted gate worktree no longer supplies the original per-test logcat or detailed XML. The 30-second failure is not evidence of either 90-second upstream prompt wait failing. No repair cause is established yet; retain this red as evidence without applying the #1480 timeout change speculatively. No decision record is needed.

Overlaps: #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1869, #1879, #1888, and #1900 edit other scenarios in `InteractiveStreamE2ETest`; this change stays local to the held-per-conversation method.

## Design

Add a test-only `PermissionIsolationStage` and `permissionIsolationStep` beside the existing question-answer diagnostic. Wrap each setup, send, upstream arrival, phone-render, arm/confirm, answer-dismissal, and turn-end operation with a distinct fixed label. A timeout becomes an assertion carrying the original exception as cause and lazily read content-free peer link state. The wrapper never retries, changes a deadline, swallows an assertion, or copies daemon-authored text into its message. Record progress using fixed stage labels only, so a surviving logcat can establish the last completed stage.

Review the existing stages and strengthen diagnostic tests to assert exact coroutine timeout object identity and Compose timeout preservation at every stage. Keep this method enabled and its existing two-prompt/armed-answer assertions intact. Preserve every deadline and the original phone scroll/two-tap sequence. Do not await A's turn end: B's send moved follow-active to B. Repairs belong to #2047 after its resume condition is met.

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

The original diagnostic wrapper and scenario instrumentation were test-first. For this recovery, rerun and strengthen `PermissionIsolationStepTest` to check exact cause identity, all fixed stages, unchanged virtual deadlines, safe outer messages, lazy diagnostics, and transparent ordinary assertions/cancellation. Rerun `PermissionIsolationPhoneProbeTest`, `ModalUiStateTest`, and `ThreadScreenModalTest` to retain physical two-tap and conversation-isolation coverage. Unit tests use virtual time and do not exercise Claude. The existing rung-3 method needs a device because it drives real relay and daemon I/O and a physical permission tap. A focused diagnostic execution is builder evidence; a fresh passing dispatcher full live gate is separately pending acceptance. No timeout increase, retry, or skipped assertion is planned. Run lint, assemble, instrumentation compilation, formatting and final pre-verify after merging main.

## Evidence and dispatcher handoff

Safe retained evidence lives under `app/src/test/resources/e2e/permission-isolation-1896/`:

- `original-failure.txt`, `original-suite.xml`, and `original-rerun.xml`: scheduler-only 30-second timeout; 64 executed/13 failed/0 skipped, then 13 executed/1 failed/0 skipped. The method failed initially and passed on rerun.
- `focused-diagnostic-pass.{txt,xml}`: builder selection, 1 executed/1 passed/0 failed/0 skipped.
- `shared-process-diagnostic-pass.{txt,xml}`: builder selection, 3 executed/3 passed/0 failed/0 skipped.
- `direct-predecessor-diagnostic-pass.{txt,xml}`: builder selection, 2 executed/2 passed/0 failed/0 skipped.

The counted XML contains method names and outcomes only; excerpts contain fixed stages, revisions, counts and artifact locations only. Raw frame/prompt/clipboard content, keys, tokens and pairing codes are excluded. Original gate artifacts are gone; historical builder artifact paths in the excerpts are provenance, not a promise those temporary directories survive.

Pending dispatcher-owned acceptance: run a fresh full live gate with `interactiveTurn_permissionPrompts_heldPerConversation` enabled (`## Live tests` is `all`; preserve `needs-real-claude` and add `needs-live-artifacts` for the pending committed records). Record executed, passed, failed and skipped counts plus this method's result separately from builder results. Retain the gate's fresh `dispatcher.xml`, per-test XML/logcat and matching daemon evidence in its `build/dispatcher-tests/live-*` artifact directory. On return from that gate, the builder must commit sanitized `dispatcher-full-live-pass.xml` and `dispatcher-full-live-pass.txt` here with revisions, counts and artifact locations; those files are pending, not present or passed. Inspect and sanitize before committing any excerpt; do not copy raw logcat/daemon output.

Resume #2047 only when a new failing occurrence supplies the named failing stage, original cause/deadline, counted XML, retained per-test phone logcat and matching daemon evidence, with revision/provenance and safe published locations. Passing selections and this checkpoint do not close #2047.

## Open Questions

None within this diagnostic checkpoint. The historical failing operation and cause remain unresolved and explicitly deferred to #2047 under the resume condition above.

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

- 2026-10-10 (maintainer recovery): scope is diagnostics only per the updated #1896 contract; original cause-and-repair work remains open in #2047. Reviewed all setup/upstream/render/answer labels and unchanged live assertions. Strengthen exact original-cause tests, rerun focused coverage, and hand off fresh full live validation with separate counts and retained artifacts. No new live repetition or production repair is part of this recovery. Incremental edits are under 100 lines; the complete branch remains under 1600 written lines, with no production file, exported type, signature or consumer migration. No in-flight branch overlaps the recovery files.
