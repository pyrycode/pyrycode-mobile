# #454 — Layer 2a: deterministic stream render + thinking spinner

**Size:** S (verified — 0 production source files; 3 new fixtures + 3 modified files; operator-run, no device test-debug loop).
**Security review:** not `security-sensitive` (see § Security note).
**Design source:** N/A — test-harness ticket, no UI change. The asserted thread UI is already Figma-anchored (`16-8`) and shipped (#337/#385 fold, #406 spinner). This ticket *exercises* it; it draws nothing new.

## Files to read first

- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt` (whole, ~130 lines) — **the template to extend.** Add two `@Test` methods + a few constants. Reuse its `awaitConnected()`, the seeded-channel tap, and the tolerant-assert idiom verbatim.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedThreadRenderTest.kt:54-100` — the **#432 Layer-1 twin** whose two cases (`text_deltasConcatenateAndFinalizeOnTurnEnd`, `spinner_shownWhileThinking_goneAfterTurnEnd`) this ticket reproduces on the emulator. Copy the thinking content-description lookup pattern (`R.string.cd_thread_thinking` via `InstrumentationRegistry…targetContext.getString`).
- `scripts/e2e-emulator.sh:60-66` (DETERMINISTIC flag → `TEST_CLASS`), `:82-96` (fixture/uuid/seed tunables), `:273-302` (the fixture-drop watcher + the gradle managed-device invocation) — the three regions to extend for scenario selection + the two-drop watcher.
- `scripts/e2e-fixtures/ping.jsonl` — the one-line fixture format the new fixtures mirror (one claude-format JSONL line per turn-event, trailing newline).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:90-97,260` — `isThinking: Boolean` param and `ThinkingIndicator(isThinking = …)` placement; the content-description source for the spinner assertion. No change here.
- `app/src/main/res/values/strings.xml:55` — `cd_thread_thinking = "Agent is thinking"` (the spinner's content-description).
- `docs/e2e-interactive-stream.md:119-181` (Deterministic mode + fixture format) and `:217-236` (Follow-ups / Coverage list to update).
- **pyrycode (reference — authority for *why* each fixture produces its events; the developer also needs this checkout to build `fakeclaude`):**
  - `internal/turnbridge/mapper.go:45-106` — `assistant` line with a `text` block → `TextChunk`; with a `thinking` block → `ThoughtChunk`; `EventKindJsonlEndOfTurn` fires only when `assistant + stop_reason=="end_turn" + non-empty text`.
  - `cmd/pyry/interactive_turn_v2.go:130-211` — `Handle`: `ThoughtChunk` → `turn_state(thinking)`; `TextChunk` → `turn_state(responding)` + coalesced `assistant_delta`; `TurnEnd` → `turn_end` + `turn_state(idle)`. Turn id is minted once at turn start and is constant across all chunks of the turn.
  - `internal/relay/handlers/send_message.go:114-166` — **the fence.** `router.Route` stamps the producer cursor (line 125-132), *then* `send_message.enqueued` is logged (line 159-160). See § The fence correction.

## Context

The mobile e2e ladder, **Layer 2a** (`docs/e2e-interactive-stream.md`, rung 4). #431 shipped the deterministic emulator harness (`DETERMINISTIC=1 bash scripts/e2e-emulator.sh`): the real app on a headless emulator over the real Noise/relay path, with `fakeclaude` (pyrycode #642) replaying a fixed JSONL fixture through the daemon's real structured-turn producer — zero claude turns, same pass on every re-run. #431 proved a single `"ping"` line renders.

This ticket adds the next two scenarios on that harness — the emulator twin of #432 (Layer 1a):

1. **Stream render** — a reply that arrives over several `assistant_delta` chunks and renders as one assembled assistant message.
2. **Thinking spinner** — the indicator shows while the turn is active and clears after it ends.

It changes **no production code.** The phone-side fold (#337/#385) and spinner (#406) are shipped + reviewed; this exercises them.

## Design

### Harness model: one scenario per invocation

#431 established **one `FIXTURE_FILE` → one turn → one scenario per script invocation** (single `TEST_CLASS`, single fixture drop fenced on the cursor-stamp log line). This ticket keeps that model and adds a `SCENARIO` selector so each invocation runs exactly one test method with the right fixture(s):

| `SCENARIO` (env) | test method (in `DeterministicInteractiveStreamE2ETest`) | fixture(s) | watcher |
| --- | --- | --- | --- |
| `ping` (default — preserves #431) | `interactiveTurn_seededChannel_streamsScriptedPingReplyIntoThread` | `ping.jsonl` | single-drop |
| `stream` | `interactiveTurn_seededChannel_streamsMultiDeltaReplyIntoThread` | `stream.jsonl` | single-drop |
| `spinner` | `interactiveTurn_seededChannel_showsThinkingSpinnerDuringTurn` | `spinner-open.jsonl` + `spinner-end.jsonl` | **two-drop** |

The bare `DETERMINISTIC=1 bash scripts/e2e-emulator.sh` (no `SCENARIO`) must still run the #431 ping scenario unchanged. The script change: a `SCENARIO="${SCENARIO:-ping}"` default + a small case block resolving `TEST_METHOD` / `FIXTURE_FILE` / (`FIXTURE_FILE_2` for spinner), and the gradle `…runnerArguments.class` argument becomes `${TEST_CLASS}#${TEST_METHOD}` so one method runs per invocation.

### Fixtures (inlined — author them verbatim)

Each line is one claude-format JSONL turn-event; **trailing newline required** (the producer tails line-delimited, like `ping.jsonl`). Why each produces the events it does is in the pyrycode `mapper.go` / `interactive_turn_v2.go` refs above.

**`scripts/e2e-fixtures/stream.jsonl`** — three text chunks, one turn. **Distinct `message.id` per line** forces a message-boundary flush, so the producer emits **three** `assistant_delta` envelopes (same `turn_id`, `seq` 0/1/2); the final line's `stop_reason:end_turn` + non-empty text also yields `turn_end`. The phone folds the three deltas (keyed by `turn_id`, #337) into **one** assembled assistant message `"Hello, streamed world"`.

```json
{"type":"assistant","message":{"id":"stream-1","content":[{"type":"text","text":"Hello, "}]}}
{"type":"assistant","message":{"id":"stream-2","content":[{"type":"text","text":"streamed "}]}}
{"type":"assistant","message":{"id":"stream-3","stop_reason":"end_turn","content":[{"type":"text","text":"world"}]}}
```

**`scripts/e2e-fixtures/spinner-open.jsonl`** — a thinking-only line. Maps to `ThoughtChunk` → `turn_state(thinking)`. No text and no `end_turn`, so the turn **stays open** and `isThinking` stays `true` (the spinner is held on — this is what makes it observable; see § The spinner). Thought *text* is never forwarded over the wire, only the state transition.

```json
{"type":"assistant","message":{"id":"think-1","content":[{"type":"thinking","thinking":"pondering"}]}}
```

**`scripts/e2e-fixtures/spinner-end.jsonl`** — a normal end-of-turn text line. The `TextChunk` drives `turn_state(responding)` (→ `isThinking=false`, spinner clears); then `turn_end` + `turn_state(idle)`.

```json
{"type":"assistant","message":{"id":"end-1","stop_reason":"end_turn","content":[{"type":"text","text":"done"}]}}
```

Pick text that never collides with always-present chrome: the seeded channel name `"e2e-seed"` renders verbatim in the thread top bar, and tolerant substring matches must not false-green on it (#431's `e2e-ping`→`e2e-seed` lesson). `"Hello, streamed world"` and `"done"` are safe.

### The fence correction (`send_message.ack` → `send_message.enqueued`)

The fixture must drop **after** the producer cursor is stamped, or the producer tails past the fixture (cold-start race) and the phone gets zero envelopes. The host-observable fence is a `daemon.log` line.

#431's watcher greps `send_message.ack` — **that token does not exist in the current daemon.** Post-#704/#721 the send-path enqueues; the only `send_message.*` log line is `send_message.enqueued` (`send_message.go:159`), and `router.Route` — which stamps the cursor (the fence's whole purpose, #687) — runs *before* that log line (`send_message.go:125-164`). So **`send_message.enqueued` is the correct, current fence.** Use it for every drop, and update the existing single-drop watcher's grep token to match (this is one token in the same watcher block #454 already edits — not a scope expansion; leaving two different fence tokens in one script would be incoherent). Flag it to confirm against the operator's daemon on first run (operator-run harness, "expect to tune on first run").

### The spinner (the hard case) — two-drop, causally fenced

The thinking state is transient: a single fixture containing `thinking` then `end_turn` would emit `turn_state(thinking)` and `turn_state(responding)` within milliseconds (both lines tail in one cycle), so the phone's `isThinking` flips `true`→`false` before Compose ever lays out the spinner — an unobservable race. **Never** assert on timing or insert a fixed host delay (a slow phone would clear the spinner before the presence-assert catches it).

Instead, hold the turn open and end it on a **causal** fence — a second user `send_message`, which the test issues only *after* it has asserted the spinner is shown:

- Drop A (`spinner-open.jsonl`) fires on the **1st** `send_message.enqueued` → `turn_state(thinking)`, held → spinner stays on indefinitely.
- The test asserts the spinner is shown, then sends a **2nd** message.
- Drop B (`spinner-end.jsonl`) fires on the **2nd** `send_message.enqueued` → `responding`/`turn_end` → spinner clears.

Because drop B is gated on the 2nd enqueue, which happens only after the presence-assert passed, the thinking window is arbitrarily long — no race, no timing dependency. The 2nd message's inert transcript growth (`{}\n`, mapped to no event) injects nothing; only drop B's line ends the turn.

Two-drop watcher contract (the one genuinely-novel host piece — keep it ≤20 lines, mirror #431's `grep`-poll-not-`tail -F` reaping discipline):

```bash
# spinner scenario: drop A on the 1st enqueue, drop B on the 2nd.
(
  while [ "$(grep -cF 'send_message.enqueued' "${DAEMON_LOG}" 2>/dev/null || echo 0)" -lt 1 ]; do sleep 0.5; done
  cp "${FIXTURE_FILE}" "${JSONL_TRIGGER}"        # drop A: turn_state(thinking), held
  while [ "$(grep -cF 'send_message.enqueued' "${DAEMON_LOG}" 2>/dev/null || echo 0)" -lt 2 ]; do sleep 0.5; done
  cp "${FIXTURE_FILE_2}" "${JSONL_TRIGGER}"       # drop B: responding + turn_end, spinner clears
) &
WATCHER_PID=$!
```

The single-drop scenarios (`ping`, `stream`) keep #431's existing one-shot watcher, only swapping the fence token. `cp` to `${JSONL_TRIGGER}` is safe to re-issue: `fakeclaude` removes the trigger after appending (`main.go:emitStructuredJSONLIfTriggered`), and drop B waits for the 2nd enqueue — long after `fakeclaude` consumed drop A's trigger — so B never clobbers an unconsumed A.

### Test methods (scenarios, not full bodies — write them in the project idiom)

Both extend `DeterministicInteractiveStreamE2ETest`, reusing its setup (wait for the seeded channel text → `awaitConnected()` → tap the channel → wait for the send button) and its tolerant-assert constants. The scripted reply is independent of the prompt, so prompts are arbitrary non-colliding text.

**`interactiveTurn_seededChannel_streamsMultiDeltaReplyIntoThread`** (single-drop):
- After arriving in the thread, type a neutral prompt (e.g. `"go"`) and send.
- `waitUntil(REPLY_TIMEOUT_MS)` a node whose text contains the cross-delta-boundary substring `"streamed world"` exists; then `assertIsDisplayed`. Asserting a substring that spans two deltas proves they concatenated into one message. Tolerant (substring, generous timeout); never assert delta count or the streaming caret.

**`interactiveTurn_seededChannel_showsThinkingSpinnerDuringTurn`** (two-drop):
- Add a `thinkingDescription` field = `R.string.cd_thread_thinking` via `InstrumentationRegistry…targetContext.getString` (copy from `ScriptedThreadRenderTest`).
- Arrive in the thread, send message #1 (e.g. `"hello"`).
- `waitUntil` the thinking content-description is present (`onAllNodesWithContentDescription(thinkingDescription)…isNotEmpty()`); `assertIsDisplayed` — **spinner shown.**
- Send message #2 (e.g. `"bye"`).
- `waitUntil` the thinking content-description is absent (`…isEmpty()`); `assertDoesNotExist` — **spinner gone.** Tolerant (presence→absence, generous timeout); never on timing.

### Docs (AC#4)

Update `docs/e2e-interactive-stream.md`: document the `SCENARIO` selector + the three fixtures in the Deterministic-mode section; describe the two-drop spinner mechanism; and in the ladder/coverage list move "thinking indicator (hardest, screen-sourced)" from ticketed follow-up to shipped (Layer 2a). Note the corrected fence token. Per the #420/#431 precedent, this is the only doc the developer touches — **no `docs/knowledge/codebase/454.md`** (the documentation phase owns that after merge).

## State + concurrency model

- **Phone-side:** unchanged. `isThinking: StateFlow<Boolean>` (#406) sourced from `turn_state`; `assistant_delta` folds into a `turn_id`-keyed streaming row, finalized on `turn_end` (#337). The interactive capability is negotiated in the emulator path already (#385/#401), so the fold gate is open — no work.
- **Host-side:** the fixture-drop watcher is a background subshell (`&`), reaped by the existing `cleanup`/`trap` via `WATCHER_PID`. The two-drop variant adds a second `grep`-poll + `cp`; it stays a single subshell so one `kill` reaps it (no orphaned `tail`). It runs concurrently with the foreground gradle managed-device run.

## Error handling

- **Wrong fence token** → fixture never drops → test times out. Mitigated by `send_message.enqueued`; confirm on first operator run.
- **Spinner unobservable (transient)** → solved structurally by the held-open thinking fixture + causal 2nd-send fence; not a timing tune.
- **Drop B clobbers an unconsumed drop A** → impossible: B is gated on the 2nd enqueue, which is causally after A was consumed, rendered, and asserted.
- **Malformed fixture JSON / missing trailing newline** → producer emits nothing → timeout. Guard at authoring time (see Testing).
- Test failures surface as the standard managed-device `AndroidTest` red; the script's `set -euo pipefail` + `trap` keep logs on non-zero exit.

## Testing strategy

**Verifiable on the host without a device (the developer's gates):**
- `bash -n scripts/e2e-emulator.sh` and `shellcheck scripts/e2e-emulator.sh` clean.
- `./gradlew compileDebugAndroidTestKotlin` — androidTest sources compile. (Mandatory gates `test`/`lint`/`assembleDebug` do **not** compile androidTest; run this explicitly — see project memory.) Also `./gradlew spotlessCheck`.
- Each fixture line is valid JSON (e.g. a `python3 -c 'import json,sys;[json.loads(l) for l in open(sys.argv[1]) if l.strip()]'` check per file), and files end with a newline.

**Operator-run (device + host `pyry`/`pyrycode-relay`/`fakeclaude`):** the three scenarios —
`DETERMINISTIC=1 SCENARIO=ping …` (regression of #431), `… SCENARIO=stream …`, `… SCENARIO=spinner …` — each green, and re-run back-to-back yielding the same pass (the determinism #431 established). No claude auth, zero claude turns.

## Security note

Not `security-sensitive`, matching #431/#432. This is a test harness + fixtures + docs exercising the already-shipped, already-reviewed Noise/decode/fold path (#337/#385/#406). No new untrusted-input parse point, no credential/crypto/auth change, single interactive phone. The structured-stream parse was the security surface when it was *introduced* (#385), not when a test *exercises* it.

## Open questions

- **Fence token on the operator's daemon.** The spec uses `send_message.enqueued` (current pyrycode HEAD). If the operator runs an older daemon, confirm the actual `daemon.log` token on first run and adjust. (The whole rung is operator-run and expected to tune on first run.)
- **Multi-delta fixture: distinct vs shared `message.id`.** Distinct ids (specified) force ≥3 `assistant_delta` envelopes, genuinely exercising concatenation; a shared id would coalesce to one. The tolerant assembled-text assertion passes either way — distinct is the belt-and-suspenders choice.
