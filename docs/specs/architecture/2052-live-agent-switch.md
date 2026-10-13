# #2052 — Live agent switching from Run configuration

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `hostRepository`, `freshSettings`, `prepareChannel`, `openRunConfiguration` and the model-change scenario establish host binding and real UI driving.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/ModelMenuExpectations.kt`: `renderedModelRows` supplies the merged rendering cap.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadAgentSwitchUiTest.kt`: confirmation, successor readings and agent-scoped effort already have deterministic UI coverage.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt`: `SessionSettings`, `ModelMenuRow`, channel creation and deletion contracts.
- `scripts/e2e-emulator.sh`: primary launch, curated selector, instrumentation arguments and EXIT cleanup.
- `scripts/test_e2e_emulator_effort_default.py`: extracted launch probes cover the live and deterministic command construction.
- `docs/e2e-interactive-stream.md`: The ladder, credential seam and Live mode define rung 3 and counted evidence.
- `docs/knowledge/features/development-verification-test-scheduling.md`: shell changes require executable probes; teardown must preserve the original result.
- `docs/knowledge/features/thread-composer-footer-effort-recall.md`: settings and effort are agent-scoped; held or predecessor readings cannot prove a switch.
- `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/internal/e2e/realclaude/codex_conversation_live_test.go`: `requireCodexCapture` and `startCodexConversationHarness` establish the dedicated-home symlink mechanism.
- `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/cmd/pyry/codex_runner.go` and `codex_home.go`: `startCheckedCodex` checks version/sign-in; `prepareCodexHome` overwrites config atomically without moving sign-in.
- `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/docs/protocol-mobile.md`: authoritative model-list, settings and agent-switch wire contracts; no wire changes here.

## Context

#1118 ships the model-picker flow without real-agent proof. Add one independently diagnosable rung-3 scenario, costing three real turns: Claude baseline, Codex reply, Claude successor reply. No production app or sibling changes. No decision record needed.

## Design

The existing isolated primary test instance gains Codex only when the live selector includes this scenario (the full selector includes it). A Python lease wrapper runs before daemon startup, validates explicit `PYRY_CODEX_CAPTURE_BIN` and `PYRY_CODEX_CAPTURE_HOME`, checks version against the daemon minimum 0.156.1 using an empty temporary Codex home, acquires a nonblocking kernel lock in the dedicated home, and checks sign-in through the binary with discarded output. It links only the test instance's `codex-home` to the canonical dedicated home, never reads/copies sign-in files, and passes the executable to `-pyry-codex` before Claude's passthrough arguments. The gate's Claude authentication remains inherited. Missing prerequisites become fixed instrumentation reason codes; the named method fails rather than skipping. Deterministic mode never enters provisioning.

The wrapper owns the link and lock through confirmed daemon exit. The resolved test-instance name is explicitly passed to it. It starts the shell in an isolated process group and passes a lease descriptor and lifetime-pipe writer through the shell, controller and primary daemon. The controller terminates, kills on timeout and joins its daemon; shell cleanup preserves a controller failure. If shell/controller failure leaves owners alive, the wrapper terminates that group, escalates to kill after a bounded grace period, and requires lifetime-pipe EOF before unlinking. An unconfirmed exit retains the link and fails; surviving inherited lease holders retain exclusivity even if the wrapper dies. Cleanup unlinks only the exact link it created and never deletes the source home. Direct use of the dedicated home by other tools must honour the same exclusive-use contract; Go live tests must not run concurrently.

The scenario creates and owns one fresh Claude channel. Private helpers select uncut, uniquely labelled, rendered rows from both agents; choices and effort expectations come from published metadata. Both model taps and Switch confirmations use Compose semantics in Run configuration opened by Tune. After each confirmation a host-bound repository observation requires the same channel, target agent, a distinct successor session and a fresh non-held reading matching that successor and model. The sheet's complete effort-radio set must equal the selected row's advertised levels. Three distinct test-authored markers identify finalized replies. After each switch, durable messages retain their original relative order and every earlier user/reply row is scrolled into view. Cleanup deletes only the created channel, preserving original failures if cleanup also fails.

Overlapping remote branches: #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1869, #1879, #1947, #2009, #2032, #2057, #2088, #2089, #2090, #2091, #2092, #2097, #2098, #2099, #2100, #2102, #2104, #2112, #2114, #2117, #2118, #2119 and #2121 touch shared harness files. Their changes concern other methods or cleanup/diagnostics; this design adds a method, private helpers, selector entry and local launch seam without restructuring those blocks.

Sizing: forecast 1100 written lines including Python lease/probes, Kotlin proof helpers/probes, scenario and plan; three new test classes and one private lease helper class, no production consumers, four criteria, fewer than ten distinct prerequisite rejection categories. One deliverable, within the hard limits.

## State and concurrency model

Existing app ViewModels and relay lifetimes remain unchanged. Test observations use bounded `runBlocking`/`withTimeout`, with no persistent test coroutine. The host Python wrapper owns one kernel lease and one shell child; shell EXIT cleanup joins its controller, and the wrapper independently fences all inheriting owners with lifetime-pipe EOF before release. Acquisition fails immediately when busy.

## State transitions and identity reuse

| Event | Proof |
| --- | --- |
| Baseline finishes, Claude switches to Codex | Named live scenario: distinct finalized reply, fresh successor identity/model, exact effort set and baseline history |
| Codex switches back to Claude | Named live scenario: another successor distinct from both predecessors; fresh settings, exact effort set and all earlier history |
| Stale predecessor or held settings arrive | `AgentSwitchProofTest`: admission rejects held, predecessor, wrong model/agent/channel readings |
| Reply marker is replayed or history reordered | `AgentSwitchProofTest`: proof requires each expected durable message exactly once in relative order |
| Concurrent use of canonical home via alias | Python lease tests: real second process cannot acquire the same lock |
| Prerequisite refusal, shell failure or termination | Python lease tests: static failure reason, source login unchanged, link removed and lease reacquirable |

## Error handling

Only fixed prerequisite codes reach instrumentation; binary output, credentials and source-home paths never do. Missing published rows fail within the normal bounded model-menu deadline. Switch, reply or history failures use static assertion descriptions. Cleanup failures fail a green test or are suppressed on its original exception. No ignored assertion or manual gating of durable proof.

## Testing strategy

Write failing Python lease/launch tests and Kotlin proof tests first. Python probes use fake binaries and private temporary homes, include real cross-process contention and failure cleanup, and never require live authentication. Run focused JVM tests for the new proof helpers and existing agent-switch UI/ViewModel coverage. The live scenario is device-only because it drives the real daemon/relay, IME and background network; compilation alone supplies no live proof. Run lint, assembleDebug, compileDebugAndroidTestKotlin, Spotless and final pre-verify. No new rung-4 scenario: existing #1118 deterministic UI coverage proves the UI contract; real authenticated hand-over is the gap this ticket owns. The full fresh live suite and its named executed/pass/count/revision/XML/log evidence remain dispatcher-owned acceptance.

## Open Questions

None. Runtime model choice is derived from published rows rather than display-name constants.

## Documentation handoff

Pending documentation stage: `docs/e2e-interactive-stream.md`, The ladder, Live mode (rung 3, live relay), Verification status: add the scenario, dedicated authenticated Codex-home prerequisite and exclusive-use requirement, three-turn cost, and fresh counted evidence paths. Live proof stays pending until dispatcher execution explicitly records the named method, full executed/passed/failed/skipped counts, mobile/daemon/agent revisions and XML/log paths.

## Security review

**Verdict:** PASS

- Trust boundaries: explicit environment configuration crosses `CodexHomeLease` validation; canonical paths must exclude personal Codex, current CODEX_HOME and all production instance roots. A dedicated home is operator-provisioned, never inferred from personal configuration. Published rows remain inert UI text.
- Tokens/secrets: SHOULD FIX implemented by discarding authentication stdout/stderr, never opening sign-in files, and never sending home paths or credentials to instrumentation. Ambient OpenAI/Codex API credentials and endpoint overrides are removed from the test child; gate-owned Claude authentication is reused. Source login remains in place, including legitimate token renewal by Codex.
- Files/storage: SHOULD FIX use owner-only lock file with no-follow open, owner validation, canonical-home lock identity, guarded test-instance name and exclusive symlink creation. Unlink only the owned link after daemon teardown. No source-home artifact copy or recursive deletion.
- Android surface: no new exported component, intent, provider or manifest changes; only a fixed static instrumentation reason.
- Cryptography: existing Noise and pairing remain unchanged; no new cryptographic primitive or secret comparison.
- Network/I/O: bounded version/sign-in subprocesses, bounded settings/reply waits and existing TLS live relay; no new unauthenticated network endpoint.
- Errors/logs: fixed failure codes only; authentication process output discarded. No credential or decrypted-body logging added. Existing private daemon evidence is retained by the dispatcher without source-home contents.
- Concurrency: MUST FIX addressed in design: lease outlives shell and all owned daemon cleanup, the controller explicitly passes the lease and lifetime descriptors to its daemon; kill-and-join handles ignored termination, and process-group fallback plus lifetime EOF fences shell/controller failure. Unconfirmed exit retains the owned link and fails; an inheriting live owner keeps the kernel lease on wrapper death. Kernel release permits retries after confirmed failure cleanup. Test observations cannot admit held or predecessor settings.
- Threat model: malicious relay delays fail bounded waits under existing Noise; disk credential theft is outside this test-only change and remains governed by app Keystore storage; hostile frame rendering uses existing bounded decoders; screenshots/accessibility see only authored fixtures and existing UI, never sign-in data.

## Revisions

- 2026-10-13: Keep the live drive in `AgentSwitchScenario.kt` with private helpers and a single entry function, leaving shared `InteractiveStreamE2ETest` edits additive. Prerequisite admission happens before connection waits. Independently recorded successful `turn_end` frames matched to each finalized reply’s turn identity and finalized repository history are both required; hand-over completions cannot satisfy the reply proof; viewport checks compare adjacent authored rows after scrolling. The source-home wrapper records only the validated numeric Codex version for dispatcher evidence. Measured implementation remains within the sizing limits.

- 2026-10-13 (verifier rework): Pass the resolved default instance name explicitly and exercise the real wrapper. Replace shell-exit ownership assumptions with controller kill-and-join, preserved cleanup failures, inherited daemon lease/lifetime descriptors and wrapper process-group fallback fenced by pipe EOF. Credential-free probes cover ignored daemon termination, shell/controller failure, wrapper termination/spawn failure, contention during teardown and subsequent reuse. Admit one finalized marker-bearing reply with surrounding text, retain its actual body for exact durable history comparisons, and identify rendered assistant rows by the existing completed-reply tag. Authored-turn `turn_end`, duplicate/streaming/replay and relative-order requirements remain unchanged. Security review updated for the ownership finding. Rework measures about 1210 inserted/deleted lines including the plan, below the 1600-line boundary; no new production types or consumers. Full dispatcher deterministic rerun and counted live acceptance remain pending.
