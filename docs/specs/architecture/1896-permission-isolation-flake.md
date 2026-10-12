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

The maintainer recovery scope on 2026-10-10 is diagnostics only. This checkpoint reviews and verifies the existing test instrumentation and retained evidence in PR #2030; it does not diagnose or repair the intermittent failure. The original cause-and-repair requirements are preserved in #2047. Its [2026-10-12 maintainer closure decision](https://github.com/pyrycode/pyrycode-mobile/issues/2047#issuecomment-6131411309) superseded the keep-open requirement: closed as no longer reproducing, without attributing every historical failure or claiming the old causal criteria proved. Passing repetitions cannot establish a repair, and no further repetition to infer a cause is planned. No production files change.

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

The original diagnostic wrapper and scenario instrumentation were test-first. For this recovery, rerun and strengthen `PermissionIsolationStepTest` to check exact cause identity, all fixed stages, unchanged virtual deadlines, safe outer messages, lazy diagnostics, and transparent ordinary assertions/cancellation. Rerun `PermissionIsolationPhoneProbeTest`, `ModalUiStateTest`, and `ThreadScreenModalTest` to retain physical two-tap and conversation-isolation coverage. Unit tests use virtual time and do not exercise Claude. The existing rung-3 method needs a device because it drives real relay and daemon I/O and a physical permission tap. A focused diagnostic execution is builder evidence; the 2026-10-12 full dispatcher live run is separate evidence (65 executed/passed, 0 failed/errors/skipped). Review and live validation must run again after this evidence handoff. No timeout increase, retry, or skipped assertion is planned. Run lint, assemble, instrumentation compilation, formatting and final pre-verify after merging main.

## Evidence and dispatcher handoff

Safe retained evidence lives under `app/src/test/resources/e2e/permission-isolation-1896/`:

- `original-failure.txt`, `original-suite.xml`, and `original-rerun.xml`: scheduler-only 30-second timeout; 64 executed/13 failed/0 skipped, then 13 executed/1 failed/0 skipped. The method failed initially and passed on rerun.
- `focused-diagnostic-pass.{txt,xml}`: builder selection, 1 executed/1 passed/0 failed/0 skipped.
- `shared-process-diagnostic-pass.{txt,xml}`: builder selection, 3 executed/3 passed/0 failed/0 skipped.
- `direct-predecessor-diagnostic-pass.{txt,xml}`: builder selection, 2 executed/2 passed/0 failed/0 skipped.
- `dispatcher-full-live-pass.{txt,xml}`: 2026-10-12 dispatcher full suite, 65 executed/65 passed/0 failed/0 skipped; includes the named permission-isolation method.

The counted XML contains method names and outcomes only; excerpts contain fixed stages, revisions, counts and artifact locations only. Raw frame/prompt/clipboard content, keys, tokens and pairing codes are excluded. Original gate artifacts are gone; historical builder artifact paths in the excerpts are provenance, not a promise those temporary directories survive.

The 2026-10-12 dispatcher full live run passed: 65 executed/passed, 0 failed/errors/skipped, including `interactiveTurn_permissionPrompts_heldPerConversation`. Its durable stdout XML, stderr and metadata are under `$AGENTS_REPO_PATH/logs/2026-10-12T20-20-20-895Z_real-claude-gate_#1896.{log,stderr.log,meta.log}`. The exact usable records are retained as sanitized `dispatcher-full-live-pass.xml` (all counted method outcomes) and `dispatcher-full-live-pass.txt` (counts, revisions, source hashes and artifact availability), separate from builder selections.

The temporary `real-claude-gate-1896/build/dispatcher-tests/live-ki7gd6v9` directory and `/tmp/pyry-e2e-ans.Z7u8UW` answer-daemon directory no longer exist. Surviving stderr contains no `PermissionIsolation` stage lines; per-test XML/logcat and matching answer-daemon evidence cannot be recovered from these records. Do not reconstruct or claim those captures. No reader or schema consumes these resource files; retain the existing counted-JUnit format without changing a test or harness contract. Remove `needs-live-artifacts` only after the usable records are committed and pushed; preserve `needs-real-claude`, `## Live tests` = `all`, and the separate requirement for renewed dispatcher review/live validation. The next gate must preserve private XML, phone logs, matching daemon diagnostics and a hash manifest under `$AGENTS_REPO_PATH/logs/evidence/1896/<run>/` before worktree cleanup, with checked content-free summaries published separately.

Reopen/resume #2047 only when a new failing occurrence supplies the named failing stage, original cause/deadline, counted XML, retained per-test phone logcat and matching daemon evidence, with revision/provenance and safe published locations. This checkpoint does not change the maintainer closure decision or claim the old causal acceptance criteria proved.

## Documentation handoff

Pending documentation stage: describe fixed-stage diagnostics, original-cause/deadline preservation, diagnostics-only scope and the #2047 reopen condition in `docs/knowledge/features/permission-modal-overlay-testing.md`, the held-per-conversation rung-3 coverage, and `docs/knowledge/features/development-verification-emulator-evidence.md`, device-gate artifact retention. Explain durable private capture versus sanitized counted summaries and that unavailable temporary paths are provenance only.

## Open Questions

None within this diagnostic checkpoint. The historical failing operation and cause remain unresolved and preserved in the maintainer-closed #2047 under the reopen condition above.

## Security review

**Verdict:** PASS

- [Trust boundaries] Fixed enum labels and `SecondClientPeer.linkState` are the only added diagnostic inputs. Decrypted modal text stays solely in the existing typed decoder and UI matcher.
- [Tokens] No credential generation/storage changes. The gate alone retrieves its test login; diagnostics never read pairing arguments or token fields.
- [Files and storage] Sanitized evidence uses fixed repository paths under test resources; no wire data constructs filenames. Surviving dispatcher records are copied privately to the persistent agents volume with owner-only directory/file modes and a verified SHA-256 manifest. Raw stderr is never committed. Existing private key stores are unchanged.
- [Android attack surface] No component, intent, provider, capture policy or permission changes; instrumentation remains test-only.
- [Cryptography] Noise handshake, identity binding and nonce lifetime remain unchanged.
- [Network and I/O] All original deadlines and isolated harness identity remain. A delayed/malicious relay still fails a bounded wait rather than a retry masking failure.
- [Errors, logs and telemetry] MUST FIX avoided by design: emit only fixed stage labels, link counters, counts and sanitized stacks; never copy prompt/frame content into diagnostics or published evidence.
- [Concurrency] Inline wrapper creates no scope or job and preserves ordinary cancellation. Peer cleanup remains guaranteed on success and failure.
- [Threat model] Relay delay/drop is diagnosed without plaintext disclosure; hostile daemon text is not logged. Rooted-disk theft and UI-side screenshot/accessibility leakage retain existing Keystore/capture protections outside this test-only change. No new mitigation is claimed.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-12 (evidence-retention review; original diagnostic design reviewed 2026-10-10)

## Revisions

- 2026-10-10: the instrumented isolated method passed (1/1) and a narrow shared-process selection with the original suite's earlier session-error and selection-copy methods passed (3/3). Neither establishes a repair cause. Add `PermissionIsolationPhoneProbeTest` under shared tests to exercise the scenario's original scroll/physical-two-tap sequence against a short thread and varied inert permission text lengths. This investigates whether a received/rendered permission can lose the confirmation tap behind thread chrome. Preserve any failing probe and its measured bounds; do not attribute the historical occurrence without evidence. No production change or timeout adjustment is authorized by these passing diagnostics.
- 2026-10-10: all three physical-tap probes passed. The controlled short-thread fixtures do not evidence a missed confirmation tap. Retain the probes as investigation coverage and retain the original physical sequence unchanged; it would be speculative to replace it with another helper as a flake repair.
- 2026-10-10: the direct original predecessor, `interactiveTurn_compactWithAttachment_compactsAndClearsTheStrip`, and the instrumented permission method both passed in the same process (2/2). Sanitized counted XML and fixed-stage logcat excerpts for all three live selections are retained under `app/src/test/resources/e2e/permission-isolation-1896/`. This method passed on all three diagnostic executions; no new failure was captured. The historical operation and cause remain unresolved, so no production fix, deadline change or flake-repair claim is made. A new failing occurrence with the named stage and per-test/daemon artifacts is still required before implementing a justified repair. Full dispatcher live validation also remains pending; these builder diagnostics do not fulfill that acceptance criterion.

- 2026-10-10 (maintainer recovery): scope is diagnostics only per the updated #1896 contract; original cause-and-repair work remained open in #2047 at that time. Reviewed all setup/upstream/render/answer labels and unchanged live assertions. Strengthen exact original-cause tests, rerun focused coverage, and hand off fresh full live validation with separate counts and retained artifacts. No new live repetition or production repair is part of this recovery. Incremental edits are under 100 lines; the complete branch remains under 1600 written lines, with no production file, exported type, signature or consumer migration. No in-flight branch overlaps the recovery files.

- 2026-10-12 (live evidence handoff): reviewed the passing dispatcher report and exact durable XML/metadata. Retain 65 method outcomes and safe provenance from this run; no diagnostic, production or live assertion changes. Temporary per-test/daemon captures are unavailable, so record their absence rather than inventing stage evidence. This passing run supplies full-suite execution evidence but does not establish a repair or complete renewed review/live acceptance. Incremental work is under 150 lines; the full branch remains below 1600 written lines, with no new types or consumers and no in-flight overlap. Security review remains PASS: publish only allowlisted JUnit fields, fixed counts/revisions/paths and SHA-256 source hashes, never raw stderr or credential-bearing daemon captures.

- 2026-10-12 (tracking reconciliation): read the newer [maintainer closure decision for #2047](https://github.com/pyrycode/pyrycode-mobile/issues/2047#issuecomment-6131411309). It explicitly supersedes the earlier keep-open contract; reflect its actual closed state without changing that ticket or claiming the historical cause repaired. A fresh failing occurrence with revisions and retained stage/phone/daemon evidence is the reopen condition. This diagnostics-only PR supplies no independent repair attribution.
