# Diagnose and repair the host-prompt live timeout (#2058)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_hostSystemPrompt_editsResetsAndCancels`, `hostRepository`, `awaitConnected` and `setHostLink` expose anonymous repository/reply waits and finally failure replacement.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/PeerWait.kt`: `withTimeoutDiagnostic` provides the existing content-free timeout convention.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/WithClearedHostInstructions.kt`: `withClearedHostInstructions` preserves primary failures and confirms exact restoration; mirror its cleanup contract without clearing before this scenario.
- `app/src/test/java/de/pyryco/mobile/e2e/WithClearedHostInstructionsTest.kt`: callback-driven failure and restoration coverage supplies the regression pattern.
- `app/src/main/java/de/pyryco/mobile/data/repository/SessionSettingsCommands.kt`: `hostSystemPromptCommand` distinguishes sent, succeeded, cancelled and refused outcomes without logging text.
- `app/src/main/java/de/pyryco/mobile/ui/host/HostEditor.kt`: `HostEditorController.onPromptEvent` uses acknowledged state and captured host identity.
- `docs/knowledge/features/host-editor.md`: Testing requires unmerged preview lookup and exact original restoration, not reset to default.
- `docs/knowledge/features/development-verification-test-scheduling.md`: coroutine stacks and post-teardown launcher focus alone cannot localize setup failures.
- Sibling `pyrycode/docs/protocol-mobile.md`: Daemon-wide host system prompt and Security model own the unchanged authenticated wire/storage contract.
- Historical #1896 original/rerun reports and retained `/tmp/pyry-e2e.0QHQTw/daemon.log`: 65/19/0 then 19/4/0 executed/failed/skipped; the original timeout has no operation identity. No host-prompt event is present in the daemon log, and the original device-artifact directory no longer exists.

## Context

The named enabled live method flaked on an unchanged merged tree. The existing trace cannot establish whether an initial read, a verification read, repository availability, or restoration expired. A restoration exception also replaces any earlier scenario failure. First retain distinguishable evidence, then repair the demonstrated cause; a passing rerun is not diagnosis. No decision record or production redesign is currently justified.

Overlap: #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1869, #1879 and #1896 edit other methods in the same live class. Their diffs do not restructure this method or its dependencies; keep edits local.

Sizing: one deliverable, approximately 350–500 written lines including this plan and regressions; zero new production types, at most two test-only declarations, one consumer, four criteria and fewer than ten error categories. Both sketch and written plan satisfy the builder limits.

## Design

Add a local/test-only restoration seam that captures the original once, protects all mutations, restores on success or failure and keeps an earlier failure primary when cleanup also fails. Confirm exact restoration through a fresh daemon read under the existing bounded cleanup deadline. An unread original must never trigger mutation or guessed restoration.

Give this method's waits static phase and operation identities, separating setup/repository availability, fresh daemon reads, UI steps and restoration. Retain content-free phase outcomes before teardown and report repository presence and connection lifecycle using static codes/booleans only. Resolve the captured host per operation; avoid nested blocking repository lookup inside a coroutine deadline. Keep deadlines unchanged, with no retries. Use the evidence from a focused instrumented attempt or deterministic reproduction to settle the behavioural repair and append the result under Revisions before handoff.

Keep the real UI drive: multiline custom save, independent fresh read, reopened unmerged preview, Reset then Cancel with unchanged storage, and Reset then OK compared with the daemon-returned default. Do not disable the method, remove assertions or change shared live setup.

## State and concurrency model

The guard owns only the exact original in scenario-local memory. Instrumentation drives UI synchronously; bounded reads/writes use runBlocking on its test thread. Repository acquisition and reply wait must cooperate with the same deadline. Cleanup is independently bounded and does not retry mutations. No production scopes, StateFlows, background ownership or persisted phone state change.

## State transitions and identity reuse

| Event | Regression |
| --- | --- |
| Initial read fails | unread original prevents mutation/restoration |
| Scenario succeeds or throws | exact original restored for empty/custom/whitespace values |
| Mutation succeeds but acknowledgement/readback fails | restoration still runs |
| Scenario and restoration both fail | primary failure retained with suppressed cleanup failure |
| Restoration fails after success | cleanup failure remains a test failure |
| Repository unavailable or reply stalls | static operation identity and unchanged deadline, focused reproduction |

## Error handling

Never print current, default, draft, tokens or raw payloads. Static phase labels distinguish timeout sources; no UI state object or semantics dump enters diagnostics. Both primary and cleanup failures must remain inspectable. Failed restoration confirmation fails the method. An external-repository cause requires its own linked blocking issue rather than an unrelated mobile patch.

## Testing strategy

Write the restoration regression first and execute it red against the old plain-finally behavior, then implement and rerun green. Exercise combined failures and exact original preservation, including an acknowledged write followed by a failed confirmation. Run existing HostPromptControllerTest and shared HostPromptEditorTest. Instrumentation remains device-only because this method uses the authenticated real daemon/relay and real Edit host controls. Run the focused named method for diagnosis if necessary; full live acceptance belongs to the dispatcher. Run lint, assembleDebug, androidTest Kotlin compilation, formatting and final pre-verify after the last main merge.

Pending dispatcher: a fresh passing full live gate must include the named method executed and passed, with retained XML and executed/failed/skipped counts. Compilation and a focused pass do not establish that acceptance criterion.

## Open Questions

- Which operation actually expired in the historical occurrence? Its retained trace cannot say; obtain phase-specific evidence before claiming a root cause or completed repair.

## Documentation handoff

- Pending documentation stage: `docs/knowledge/features/host-editor.md`, Testing: record the demonstrated timeout cause and restoration/diagnostic regression contract.
- Pending documentation stage: `docs/e2e-interactive-stream.md`, Verification status: record dispatcher-owned fresh full live evidence and counts for the named enabled method.

## Security review

**Verdict:** PASS

- [Trust boundaries] Prompt text stays in typed readings and plain existing controls; comparisons use booleans with static assertions. No new parsing or production rendering boundary.
- [Tokens] No credential lookup, storage or logging change. Harness authentication remains gate-owned; callbacks must never report pairing records.
- [Files/storage] Exact original exists in memory only; no prompt persistence or filename derived from it. Evidence contains only static operation labels/outcomes.
- [Android attack surface] No components, intent handling, permissions or exported surface change. Existing editor/keyboard exposure remains the operator's intended flow.
- [Cryptography] Existing Noise_IK and Keystore identities are unchanged; no key, nonce or cipher-state modification.
- [Network/I/O] Keep deadlines and correlated replies. Repository acquisition must suspend within the operation's deadline rather than nesting an independent runBlocking. Delayed/dropped relay replies fail visibly without retries.
- [Errors/logs] SHOULD FIX: finally currently hides a primary error. Preserve both failures, label operations, and never concatenate prompt state, raw daemon errors or payload text into diagnostics.
- [Concurrency] Capture before mutation, restore after every protected exit and freshly verify the exact original. No uncancelled job survives the scenario. Read failure leaves host untouched.
- [Threat model] Malicious relay delay/drop is bounded; hostile frames retain existing codec/byte-limit checks. Rooted-disk token theft has no new storage surface. UI-side exposure remains existing plain text editing, with no diagnostic screenshots or semantics dumps added.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-10
