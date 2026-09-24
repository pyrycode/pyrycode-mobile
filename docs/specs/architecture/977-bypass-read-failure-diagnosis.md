# #977 — Diagnose the #687 operator-bypass Read's missing token

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild` step 5, `awaitReadPrompt`, `READ_PROMPT_TEMPLATE`, `REPLY_TIMEOUT_MS` — the step this ticket instruments.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt` → `awaitFrame`, `allowOnce`, the `received` recorder, `payloadField` — the peer already records every frame; it needs one read-only snapshot accessor.
- `app/src/main/java/de/pyryco/mobile/data/network/InteractivePayloads.kt` → `TurnEndPayloadDto`, `ToolResultPayloadDto`, `ToolUsePayloadDto` — the decoders for `turn_end` (`stop_reason`, `outcome`, `is_error`), `tool_result` (`is_error`) and `tool_use` (`name`).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt` → `MESSAGE_BUBBLE_TEST_TAG` — how the step finds bubbles.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `inert` — bounds and strips control characters from the daemon-authored enum-like fields before they reach the message.
- `scripts/e2e-emulator.sh` → `cleanup`, `start_bypass_daemon`, `BYPASS_HOME` — where the bypass HOME is minted and deleted.
- `docs/specs/architecture/687-bypass-permission-live-proof.md` — the method's own plan.

Overlap: `feature/965` also edits `InteractiveStreamE2ETest.kt` (a new method) and `scripts/e2e-emulator.sh` (LIVE-list comments). Different blocks; edits here stay local to step 5, the peer and `cleanup`.

## Design source

N/A — test and script only; nothing visible changes.

## Context

Step 5 has failed twice in three live runs with "the allowed Read's reply never carried the file's token" after 90 s. Four causes fit: a slow turn, a failed Read answered `blocked`, a paraphrased reply, or a phone that did not render the reply. The bypass HOME, holding claude's transcript, is deleted on every exit. This ticket makes the next failure name its cause; it changes neither what the method proves nor any production source.

## Design

### Step 5 (test)

1. Before `allowOnce`, take `mark = peer.recorded(chat.id).size` — the count of frames recorded for the chat so far. Frames at index ≥ `mark` are "after the allow".
2. After `allowOnce` returns, wait for the chat's `turn_end` with `peer.awaitFrame(chat.id, "turn_end", REPLY_TIMEOUT_MS)`. The peer opened before the Read was sent and after the ping turn, so the first recorded `turn_end` is the Read's. On `TimeoutCancellationException`: `AssertionError("the allowed Read's turn never ended: no turn_end for the chat within 90000 ms of the allow")`.
3. Then wait on the phone for a bubble carrying the token with a short budget, `PHONE_TRAIL_MS = 15_000L`. On `ComposeTimeoutException`: `AssertionError("the allowed Read's reply never carried the file's token: " + diagnosis)`, keeping the original cause.

The diagnosis is built by one private helper, `readReplyDiagnosis(frames: List<Envelope>, token: String, turnEnd: Envelope, endedAfterMs: Long): String`, from:

- **phone**: `bubbles=<n>`, `bubbleLengths=[…]` (the text length of each `MESSAGE_BUBBLE_TEST_TAG` node, the concatenated `Text` of its unmerged descendants), `anyBlocked=<bool>` (a bubble whose trimmed text equals `blocked`, ignoring case).
- **peer, after the mark**: `readResultIsError=<true|false|absent>` — the `tool_result` whose `tool_use_id` matches a `tool_use` named `Read`; `peerFramesWithToken=<bool>` — any frame's serialised payload contains the token (a UUID, so no JSON escaping).
- **turn**: `stop_reason`, `outcome`, `is_error` from `TurnEndPayloadDto`, the strings passed through `inert()`; `endedAfterMs` — allow-return to `turn_end`-seen.

Booleans, counts and those enum-like fields only; never bubble text, payload text or the token.

### `SecondClientPeer`

One new accessor: `internal fun recorded(conversationId: String): List<Envelope>` — a snapshot of the frames recorded so far that name the conversation, in arrival order. Doc: callers must not report payload text. No other change.

### `scripts/e2e-emulator.sh`

In `cleanup`, before the bypass HOME is deleted and only when `code` is non-zero, copy each regular file matching `*.jsonl` under `${BYPASS_HOME}/.claude/projects/` into `${WORK_DIR}/bypass-transcripts/`, keeping relative paths (`find -type f`, so symlinks are skipped). A failed copy never stops cleanup (`|| true`). The HOME deletion that follows is unchanged and unconditional. Nothing outside `.claude/projects/` is copied, so neither `~/.claude.json` nor `.claude/.credentials.json` nor the witness file can leave.

## State + concurrency model

Unchanged: `runBlocking` in the test around the peer's suspend calls, as the method already does. `recorded` reads the peer's `MutableStateFlow` value — a consistent immutable snapshot.

## Error handling

Two distinct `AssertionError` messages as above. Decode of a malformed `turn_end` or `tool_result` inside the diagnosis must not mask the real failure: decode with `runCatching`, reporting `undecodable` for a field it cannot read.

## Testing strategy

Live-only code path; no unit harness reaches it. Proof: `./gradlew compileDebugAndroidTestKotlin`, `lint`, `assembleDebug`, and `bash -n scripts/e2e-emulator.sh`. The cleanup copy is exercised by a scratch run of the copy function against a fake HOME with a `.jsonl`, a `.credentials.json` and a symlink, confirming only the `.jsonl` lands. AC-4 (`python3 scripts/android-test-gate.py live`) is the dispatcher's post-verifier live gate for `needs-real-claude`.

## Documentation handoff

Pending for the documentation stage: in `docs/e2e-interactive-stream.md`, where the #687 operator-bypass daemon is described, say that a failed run keeps claude's transcript from the bypass HOME in the kept `WORK_DIR` (`bypass-transcripts/`), and that the HOME itself, with its credential copy, is always deleted.

## Open questions

- Does the daemon emit the `tool_use` frame with `name` exactly `Read`? If not, `readResultIsError` reports `absent`; the transcript then answers it.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — daemon-authored `stop_reason` / `outcome` go into an assertion message only after `inert()` (control characters stripped, length-bounded); bubble text and payload text never enter a message, only lengths and booleans computed in `readReplyDiagnosis`.
- [Tokens, secrets] No findings — the witness token is compared, never printed; `peerFramesWithToken` is a boolean. The bypass HOME's credential material (`~/.claude.json` copy, any `.claude/.credentials.json`) is outside `.claude/projects/` and cannot be copied; `CLAUDE_CODE_OAUTH_TOKEN` is an env var, not written by the script. The copied transcripts hold the per-run witness token and prompt — accepted by the ticket; the witness is per-run and meaningless afterwards.
- [File / storage] Symlink escape — a `*.jsonl` symlink under `projects/` pointing at the credential copy would be followed by `cp`. Addressed in design: `find -type f` skips symlinks. Destination `WORK_DIR` is `mktemp -d` (mode 0700). The `BYPASS_HOME` path guard (`/tmp/pyry-e2e-byp.*`) still gates both the copy and the delete. Deletion of the HOME remains unconditional and after the copy.
- [Inter-process / Android] No findings — no manifest, intent or exported surface touched.
- [Crypto] No findings — no primitive touched; the peer's Noise session is unchanged.
- [Network & I/O] No findings — no new frame, the peer only reads what it already records.
- [Errors, logs] No findings — the failure messages carry counts, booleans and bounded enum-like strings; the script logs only the destination directory, not file contents.
- [Concurrency] No findings — `recorded` reads an immutable snapshot of a `StateFlow`.
- [Threat model] OUT OF SCOPE — the transcript copy lives as long as the operator keeps `WORK_DIR`; retention of kept logs is the operator's existing practice for `daemon-bypass.log`.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-24

## Revisions

- 2026-09-24 (build): `readResultIsError` looks up the Read's `tool_use` and `tool_result` across every frame the peer recorded for the chat, not only those after the mark. The `tool_use` is emitted before the permission prompt, so it can precede the mark; restricting it to after the mark would report `absent` for a Read that did run. The peer opens just before the Read is sent, so every recorded frame is that turn's. `peerFramesWithToken` still counts only frames after the mark. `readReplyDiagnosis` takes the chat's frames and the mark instead of the after-mark slice.
