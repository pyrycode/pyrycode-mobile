# #966 — Live proof: permission and clarification answers reach the right conversation

## Files read

- `scripts/e2e-emulator.sh` → `start_bypass_daemon`, `phone_pair_code`, `pair_token`, `cleanup`, the LIVE `TEST_TARGET` — the #687 isolated-HOME daemon is the template for the new privileged daemon.
- `scripts/android-test-gate.py` → `LIVE_MINIMUM`; `scripts/test_android_test_gate.py` asserts it equals the `#interactiveTurn_` count in `TEST_TARGET`.
- `app/src/androidTest/.../e2e/SecondClientPeer.kt` → `awaitPermissionModal`, `allowOnce`, `recorded` — gains question helpers.
- `app/src/androidTest/.../e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild` (pairs a dedicated host by code, removes it in `finally`), `awaitReadPrompt` (dialog-scoped matcher), `pairHostByCode`, `hostRepository`, `openChatRow`, `leaveThread`, `sendFromPhone`, `WAIT_PROMPT`.
- `app/src/main/.../ui/conversations/thread/ThreadPermissionModal.kt` → `PermissionModalOverlay`, `PermissionContext`, `AlwaysAllowOffer`, `ModalOptionButton` (a non-default option arms on the first tap and answers on the second).
- `app/src/main/.../ui/conversations/thread/QuestionBatchModal.kt` → `QuestionBatchModal` (option rows, Continue).
- `app/src/main/.../data/network/QuestionPayloads.kt` → `QuestionShownPayloadDto`, `QuestionAnswerPayloadDto`, `QuestionAnswerEntryDto`; `InteractivePayloads.kt` → `AssistantDeltaPayloadDto`.
- `../pyrycode/docs/protocol-mobile.md` § Modal (v2), § Question (v2) at daemon `8591b0b1`; daemon `cmd/pyry/question_resolve_v2.go` → `questionResolverV2.ResolveAnswer`; `internal/config/config.go` → `StdioPermissionPrompt`.
- `docs/e2e-interactive-stream.md` § Live mode; issue #981 (open).

## Design source

**Figma:** N/A — test, peer and harness only; no UI changes.

## Context

Findings that shape the design, all checked against daemon source at `8591b0b1`:

1. **The phone needs `--allow-remote-permissions` for both answers.** `questionResolverV2.ResolveAnswer` gates a `question_answer` on `MayAnswerRemotePermission`, the same per-device bit `modal_answer` uses (#1986 is wired; the protocol doc's "resolved by nothing yet" paragraph is stale).
2. **Don't-ask-again needs the stdio prompt path.** `always_allow.offered` is true only on a stdio permission ask; the approval-MCP path always carries `{offered:false}`. Host A runs under the operator's real HOME, whose `~/.pyry/config.json` has no `stdio_permission_prompt`, so host A can never offer it. The per-user config cannot be edited (the harness refuses to write the real HOME).
3. **#981 is open**: a short, whole reply after an allowed stdio prompt may not be composed in the thread. Every "reply contains the token" check below therefore reads claude's reply from the `assistant_delta` frames the peer records for that conversation, which is the daemon's copy of claude's words. The phone-side bubble is #981's proof, not this ticket's.

So the scenario gets its own **answer daemon**: a fourth test daemon in the #687 shape (isolated `/tmp` HOME, config `{"interactive_runner":"stream-json","stdio_permission_prompt":true}`, credential from `CLAUDE_CODE_OAUTH_TOKEN` or `ANTHROPIC_API_KEY`), but with **no operator bypass**, so claude's default mode asks. The phone's pairing code for it is minted `--allow-remote-permissions`; nothing else pairs the phone with that host, so every other method still sees an unprivileged phone. A peer token is minted `--allow-remote-permissions` on the same host.

Overlapping in-flight branches: none.

## Design

### Harness (`scripts/e2e-emulator.sh`)

- `start_answer_daemon`, a sibling of `start_bypass_daemon` (not a refactor of it), run on the same non-DETERMINISTIC paths. Prerequisites map to one static `ANSWER_UNMET` code each: `no_credential`, `claude_json_unreadable`, `claude_missing`, `instance_name`, `isolated_home`, `daemon_not_ready`, `pairing`, `peer_pairing`. No revision gate: a daemon without the answer path fails the methods with a named timeout.
- Instance `${PYRY_NAME}-answer`, HOME `mktemp -d /tmp/pyry-e2e-ans.XXXXXX`, argv `-pyry-name … -pyry-workdir=<home>/work -pyry-idle-timeout=0` with no `--` claude flags.
- Phone code via `phone_pair_code … ANSWER`; peer token via `pair_token … ANSWER_PEER`. Neither value is logged.
- Instrumentation args: `answerUnmet`, or `answerServerId`, `answerPairCode`, `answerPeerToken`, `answerServerStaticPublicKey`.
- `cleanup` kills `ANSWER_PID` and removes a HOME matching `/tmp/pyry-e2e-ans.*`.
- LIVE `TEST_TARGET` gains both methods; header and PASS comments updated. `LIVE_MINIMUM` 21 → 23.

### Peer (`SecondClientPeer`)

- `awaitQuestion(conversationId, timeoutMs, occurrence = 1): String` — the batch id of that conversation's nth `question_shown`.
- `answerQuestion(batchId, questionIndex, value, timeoutMs)` — sends `question_answer` with one entry, then waits for `question_dismissed` of that batch with `outcome == "answered"` and `source == "remote"`.
- `awaitModalDismissed(modalId, timeoutMs): Envelope` and `awaitQuestionDismissed(batchId, timeoutMs): Envelope` — waits used both by the answer helpers and to prove a phone answer resolved remotely.
- `allowOnce` keeps its contract; it reuses `awaitModalDismissed`.

### Test methods (`InteractiveStreamE2ETest`)

**`interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation`** — three real-claude turns:
1. Fail with the `answerUnmet` reason if set. Pair the answer host by code, create chats A and B with `createDiscussion` + `rename`, open the peer (frame recorder).
2. In A, send `ANSWER_PERMISSION_PROMPT` (a `python3` command printing a number the prompt text never contains).
3. The peer's `modal_shown` for A names A; the phone's dialog shows in A. Decision context: every context field the frame carries (`reason`/`reason_type`, `description`, `blocked_path`) has its local label drawn in the dialog; a frame carrying none fails naming it. `always_allow.offered` must be true.
4. Leave, open B: no dialog. Back to A: the dialog again.
5. Tick don't-ask-again, tap the `allow_once` option twice (arm, confirm). The peer sees the modal dismissed `allow_once`/`remote`, then A's `turn_end`; A's assistant text contains the token; the dialog is gone.
6. AC2: send the same prompt in A. A's second `turn_end` arrives, still one `modal_shown` for A, and the new assistant text contains the token.
7. AC4: open B, send the same prompt (B's session holds no grant). The phone draws the dialog; the peer allows it; the dialog closes with no phone tap; B's `turn_end` arrives.

**`interactiveTurn_questionAnswer_reachesTheAskingConversation`** — two real-claude turns:
1. Guard, pair, create chat Q, open the peer.
2. Send `QUESTION_PROMPT` (ask one AskUserQuestion with the two labels `pyrycyan` / `pyrymagenta`, then reply with exactly the chosen label).
3. The peer's `question_shown` names Q; the phone shows the question modal. Tap `pyrymagenta`'s row in the dialog, then Continue. The peer sees `question_dismissed` `answered`/`remote`, then `turn_end`; the assistant text after the answer contains `pyrymagenta` and not `pyrycyan`.
4. AC4: send `QUESTION_PROMPT` again. The phone shows the modal; the peer answers `pyrycyan`; the modal closes with no phone tap; the second `turn_end` arrives.

Both methods remove the answer host from `PairedServerCollectionStore` and close the peer in `finally`.

## State + concurrency model

Test-only. The peer's recorder is its existing `SupervisorJob` + `Dispatchers.IO` scope, cancelled in `close`. Every wait is `withTimeout`-bounded; `runBlocking` stays confined to the instrumentation thread, as in the existing methods.

## Error handling

Every failure message names a step and counts, booleans or static codes. No message carries payload, assistant or prompt text, a token or a pairing code. An unmet daemon prerequisite fails both methods with its code, never skips them.

## Testing strategy

- Both methods are rung 3 (real claude), run by the dispatcher's `python3 scripts/android-test-gate.py live`; the builder cannot run them. Locally: `./gradlew compileDebugAndroidTestKotlin`, `python3 -m unittest scripts/test_android_test_gate.py` (checks `LIVE_MINIMUM` against `TEST_TARGET`), `bash -n scripts/e2e-emulator.sh`, `lint`, `assembleDebug`.
- No rung-4 twin: `fakeclaude` cannot script a `can_use_tool` for AskUserQuestion from a fixture, and the scripted suite's scenario list is dispatcher-owned. If the live run shows real claude does not call AskUserQuestion reliably, the question method becomes the follow-up the ticket's technical notes allow; the permission method stays live either way.

## Open questions

1. Does claude supply decision context for a default-mode `python3` ask? The method fails with a named message if the frame carries none; resolved on the first live run.
2. Does claude's permission ask for the command carry an `addRules` suggestion, so don't-ask-again is offered? Same resolution.
3. Does a phone pairing code survive reuse across the two methods? Host A's peer token is reused by four methods, so reuse is expected to hold.

## Documentation handoff

Pending for the documentation stage: `docs/e2e-interactive-stream.md` § Live mode — add both methods to the curated list; raise the method count to 23 and the real-claude turn count by five; describe the answer daemon and that the phone is paired `--allow-remote-permissions` with it alone, next to the paragraph on the peer's `--allow-remote-permissions` pairing.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — no production code changes. Claude-authored strings (prompt, context, labels, assistant text) are only compared inside the test; failure messages carry counts and static codes, never their content.
- [Tokens] No findings — the privileged phone code and peer token are minted per run, read by `phone_pair_code` / `pair_token` which print only shell assignments, and passed as instrumentation arguments, the existing #687/#847 route. Neither is logged. The isolated HOME holding the daemon's identity and a copy of `~/.claude.json` is `mktemp -d` under `/tmp` and removed by `cleanup` on every exit.
- [Privilege scope] No findings — the ticket's core requirement. The phone's `--allow-remote-permissions` pairing exists only on the dedicated answer daemon, a separate instance under its own HOME that no other method pairs with; both methods remove it from the phone in `finally`. Host A, host B and the bypass daemon keep unprivileged phone pairings, so every other scenario's expectation holds.
- [File / storage] No findings — the only writes are the isolated HOME's config, created 0600 inside a `umask 077` subshell as `start_bypass_daemon` does; the real `~/.pyry` is never written for this daemon.
- [Production daemon] No findings — the daemon is an `e2e-` test instance (checked with `two_host_name_ok`) with its own HOME; the operator's daemon and config are untouched.
- [Concurrency] No findings — test-scoped peer scope, cancelled in `close`; all waits bounded.
- [Threat model] OUT OF SCOPE — the reply-render gap after an allowed prompt is #981.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-24

## Revisions

- 2026-09-24 (implementation): `SecondClientPeer` also gains `field(envelope, name)`, so the test can compare a dismissal's daemon-asserted `outcome` and `source` sentinels; the private `payloadField` stays private. The three waits share one private `awaitDismissal`. No contract change.
- 2026-09-24 (rework 1, verifier MUST FIX on PR #991): the reply token alone does not prove the command ran, since claude can compute `966 * 7` or repeat it from A's earlier turn. Permission method steps 5 and 6 now also require, in the frames the peer recorded for A in that turn, a `Bash` `tool_use` and a `tool_result` for its id with `is_error == false` (private `assertBashRan`; failure names counts and booleans only). AC2's proof is that successful call plus still one `modal_shown` for A. The token check stays as proof that the reply reports the output. The `ANSWER_PERMISSION_TOKEN` comment is corrected, and the `answerQuestion` KDoc names the bare timeout (verifier nit).
