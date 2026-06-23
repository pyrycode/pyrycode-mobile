# Spec #455 — Layer 2c: deterministic tool-step rows (running → done, and failed)

Adds two deterministic emulator e2e scenarios on the **shipped #454 harness**: a tool step that
renders **running → done**, and a separate one that renders **failed**. Both exercise the
already-shipped, already-reviewed tool-row render path (#387 correlation fold + #388 tool-row status
UI) end to end — real app on a headless emulator + Noise/relay + the scripted `fakeclaude` backend,
**zero claude turns**, same pass on every re-run.

This is **test + fixtures + docs only — no production Kotlin.** It is a thin variant of the existing
`DeterministicInteractiveStreamE2ETest` (#431/#454): only the fixtures, two new `@Test` methods, two
new `SCENARIO` arms in the host script, and a docs update differ. Do **not** re-derive the harness or
touch the phone-side fold/render.

## Files to read first

- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt:95-144` —
  the `stream` and `spinner` `@Test` methods. **The spinner method (`:116-144`) is the template for
  the running → done scenario** (two-drop, present-then-absent). Mirror its shape exactly.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt:180-216` —
  the companion constants (`SEED_CHANNEL_NAME`, `SEND_PROMPT`, `SECOND_PROMPT`, timeouts). Add the new
  tool constants here.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt:69-73,
  146-178` — how `thinkingDescription` is read from a string resource and the `arriveInSeededThread()`
  / `typeAndSend()` / `awaitConnected()` helpers. Reuse them verbatim; read the tool CDs the same way.
- `scripts/e2e-emulator.sh:131-157` — the `SCENARIO` → `TEST_METHOD` + fixture(s) `case` block. Add
  two arms here (`tool`, `tool-failed`).
- `scripts/e2e-emulator.sh:305-345` — the fixture-drop watcher (single-drop vs two-drop is selected
  purely by whether `FIXTURE_FILE_2` is set — **no watcher change is needed**) and the
  `TEST_TARGET=class#method` resolution.
- `scripts/e2e-fixtures/ping.jsonl`, `stream.jsonl`, `spinner-open.jsonl`, `spinner-end.jsonl` — the
  fixture format to mirror (claude-format JSONL, one event per line, trailing newline, **no**
  `conversation_id`/`turn_id` — the producer attaches those).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ToolCallRow.kt:135-163` —
  `ToolCallStatusIcon`: the three states' rendered affordances and their content-descriptions. This is
  what the assertions key on. **Running** = `CircularProgressIndicator` with CD `cd_tool_running`
  ("Tool call running"); **Failed** = `ErrorOutline` icon with CD `cd_tool_failed` ("Tool call
  failed"); **Done** = the per-tool icon with `contentDescription = null` (no positive CD — see § Done
  assertion).
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:643-713` —
  `applyToolUse` / `applyToolResult`: the #387 correlation fold. Key facts that drive the fixtures:
  the row is keyed by `tool_use_id`; a `tool_result` with **no prior `tool_use` is dropped** (so the
  `tool_use` must arrive first); `is_error == true` → `Failed`, else `Done`; `toolName` is carried
  **verbatim** from the envelope `name`.
- `app/src/main/res/values/strings.xml:55,60-61` — `cd_thread_thinking`, `cd_tool_running`,
  `cd_tool_failed`. Source of truth for the CD strings the test reads.
- `docs/e2e-interactive-stream.md:174-229` — the fixture-format extension point and the "Scenarios
  (#454)" table + spinner narrative. This is the doc you extend (AC #4).

## Context

The mobile e2e ladder's Layer 2 (real app on a headless emulator + scripted backend) shipped its
deterministic harness in **#431** and was extended with the multi-delta `stream` render + the thinking
`spinner` scenario in **#454** (which added the `SCENARIO` selector and the two-drop causal fence).
This ticket adds the tool-step coverage on that same harness — the Layer-2 twin of the Layer-1
component case (#435).

The render path it exercises is **already shipped and reviewed**: `tool_use` → a running tool row,
`tool_result` → done, `tool_result` with `is_error` → failed (#387 correlation, #388 tool-row status
UI). This ticket **exercises** that path; it does not modify it.

## Why this is not security-sensitive (matches #431/#432/#435/#454)

No new untrusted-input parse point is introduced. The decode/correlation path (#385 decode-substrate,
#387 correlation) was the security surface when it was introduced and was reviewed then. The fixtures
are author-controlled test inputs; exercising an already-reviewed parser in a test is not a new trust
boundary. `security-sensitive` is correctly omitted.

## Why no `## Design source` (no Figma)

This is a test-harness ticket — it asserts against the **already Figma-anchored** tool rows (`16-28`,
shipped via #388). It introduces no new visuals, so there is no Figma section to echo. The
visual-fidelity check is intentionally not applicable here.

## Design

Two scenarios on the existing one-scenario-per-invocation model (each `SCENARIO` → exactly one `@Test`
method + its fixture(s), run via `-P…class=<class>#<method>`):

| `SCENARIO` | asserts | fixture(s) | drops |
| --- | --- | --- | --- |
| `tool` | a tool step shows **running** in flight, then **done** after the result | `tool-open.jsonl` + `tool-done.jsonl` | **two** |
| `tool-failed` | a failing tool step renders **failed** | `tool-failed.jsonl` | one |

### Running → done reuses #454's two-drop causal fence

Observing the **running** transient *without timing* is the same problem the thinking spinner had: a
single fixture carrying `tool_use` + `tool_result` back-to-back would race straight to **done** and only
the terminal state would be reliably catchable (false-green on the running step). So mirror the spinner
exactly:

- **Drop A** (`tool-open.jsonl`, fired on the **1st** `send_message.enqueued`): a lone `tool_use` with
  the turn held open (no `end_turn`). The producer emits the `tool_use` envelope → the fold opens a
  `Running` `Role.Tool` row, which **persists** (the row flips only on a correlated `tool_result`). The
  test asserts the running CD is shown.
- The test then sends a **2nd** message (its text is inert — the scripted backend ignores it; it only
  causally fences drop B).
- **Drop B** (`tool-done.jsonl`, fired on the **2nd** `send_message.enqueued`): the correlated success
  `tool_result` (`is_error: false`) + a turn-ending text line. The producer emits the `tool_result`
  envelope → the fold flips the row to `Done` and closes the turn.

Because drop B is gated on the 2nd enqueue — which happens only after the running-assert passed — the
running window is arbitrarily long. No timing dependency, no race. **Correlation is the load-bearing
detail:** drop A's `tool_use` block `id` MUST equal drop B's `tool_result` block `tool_use_id` (use the
same literal id in both files), or the fold drops the result and the row never resolves. Same-session →
same producer-attached `conversation_id`, so cross-drop correlation needs only the `tool_use_id` match,
which lives in the transcript you control.

### Failed needs no two-drop — one terminal drop

The **failed** end state is stable (it does not auto-resolve to anything else), so it needs no held-open
fence. A single fixture (`tool-failed.jsonl`) carries `tool_use` → error `tool_result` (`is_error:
true`) → a turn-ending text line, all in one drop. The fold renders the row `Running` (briefly) →
`Failed`; the test asserts only the terminal `Failed` state (tolerant, stable). The `tool_use` line
must precede the `tool_result` line in the file (line-delimited tail order) so they correlate; use a
distinct `tool_use_id` from the `tool` scenario's.

### Fixtures (exact content — inline these verbatim)

`fakeclaude` appends these claude-format lines to the live session JSONL; the pyrycode producer tails
them and derives the envelopes (`tool_use` block → `tool_use` envelope `{tool_use_id, name,
input_summary}`; `user` `tool_result` block → `tool_result` envelope `{tool_use_id, is_error,
result_summary}`). The tool `name` and `is_error` pass through verbatim; only `input`/`content` are
summarized server-side — so assertions must **not** depend on the exact `input_summary`/`result_summary`
text (see § Testing). One event per line, trailing newline on each file.

`scripts/e2e-fixtures/tool-open.jsonl` (drop A — held open, no `end_turn`):

```json
{"type":"assistant","message":{"id":"tool-1","content":[{"type":"tool_use","id":"toolu_e2e","name":"Bash","input":{"command":"echo hello"}}]}}
```

`scripts/e2e-fixtures/tool-done.jsonl` (drop B — success result + turn end):

```json
{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_e2e","content":"hello","is_error":false}]}}
{"type":"assistant","message":{"id":"tool-2","stop_reason":"end_turn","content":[{"type":"text","text":"ok"}]}}
```

`scripts/e2e-fixtures/tool-failed.jsonl` (single drop — use + error result + turn end):

```json
{"type":"assistant","message":{"id":"toolf-1","content":[{"type":"tool_use","id":"toolu_e2e_fail","name":"Bash","input":{"command":"false"}}]}}
{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_e2e_fail","content":"command failed","is_error":true}]}}
{"type":"assistant","message":{"id":"toolf-2","stop_reason":"end_turn","content":[{"type":"text","text":"oops"}]}}
```

The turn-ending text (`"ok"` / `"oops"`) is cosmetic — it closes the turn and is **not** asserted on.
The tool `name` is `"Bash"` (asserted; carried verbatim) and does not collide with the seeded channel
name `"e2e-seed"` rendered in the top bar.

### Host script changes (`scripts/e2e-emulator.sh`)

Two new arms in the `SCENARIO` `case` (mirror the existing `stream` / `spinner` arms at `:141-149`).
No change to the watcher (`:305-345`): the two-drop path is selected automatically by setting
`FIXTURE_FILE_2`; the single-drop path by leaving it unset.

```sh
    tool)
      TEST_METHOD="interactiveTurn_seededChannel_toolStepRunsThenCompletes"
      FIXTURE_FILE="${FIXTURE_FILE:-${FIXTURES_DIR}/tool-open.jsonl}"      # drop A: tool_use, held open
      FIXTURE_FILE_2="${FIXTURE_FILE_2:-${FIXTURES_DIR}/tool-done.jsonl}"  # drop B: tool_result(done) + turn_end
      ;;
    tool-failed)
      TEST_METHOD="interactiveTurn_seededChannel_failedToolStepRendersFailed"
      FIXTURE_FILE="${FIXTURE_FILE:-${FIXTURES_DIR}/tool-failed.jsonl}"    # single terminal drop
      ;;
```

Also update the `die` message at `:151` to list the new scenarios (`ping | stream | spinner | tool |
tool-failed`) and add `tool` / `tool-failed` example invocations to the usage header comment
(`:44-46`).

### Test methods (`DeterministicInteractiveStreamE2ETest.kt`)

Add two `@Test` methods + constants. Describe them as scenarios; write the bodies in the file's
existing idiom (reuse `arriveInSeededThread()`, `typeAndSend()`, `waitUntil`, the `onAllNodes` +
`hasContentDescription` selectors, the `REPLY_TIMEOUT_MS` timeout). Read the two tool CDs from string
resources exactly as `thinkingDescription` is read (`:69-73`).

**`interactiveTurn_seededChannel_toolStepRunsThenCompletes`** (`SCENARIO=tool`) — mirror the spinner
method:
- `arriveInSeededThread()`.
- `typeAndSend(SEND_PROMPT)` → drop A → `tool_use` → running row.
- `waitUntil(REPLY_TIMEOUT_MS)` that the **running** CD (`cd_tool_running`) node set is non-empty, then
  assert its first node `isDisplayed()`. (This is the "running while in flight" assertion.)
- `typeAndSend(SECOND_PROMPT)` → drop B → `tool_result(done)` + turn end.
- `waitUntil(REPLY_TIMEOUT_MS)` that the **running** CD node set is now **empty** (the row resolved),
  then assert the **failed** CD `assertDoesNotExist()` (it resolved to done, not failed) **and** the
  tool name `"Bash"` is still displayed (the row resolved in place, did not vanish).

**`interactiveTurn_seededChannel_failedToolStepRendersFailed`** (`SCENARIO=tool-failed`) — single drop:
- `arriveInSeededThread()`.
- `typeAndSend(SEND_PROMPT)` → the single fixture drops `tool_use` + error `tool_result` + turn end.
- `waitUntil(REPLY_TIMEOUT_MS)` that the **failed** CD (`cd_tool_failed`) node set is non-empty, then
  assert its first node `isDisplayed()`. (Terminal, stable — no two-drop needed.)

New companion constants (alongside `:180-216`): `TOOL_NAME = "Bash"` (the verbatim tool name asserted
in the done case). Read `cd_tool_running` / `cd_tool_failed` via `InstrumentationRegistry…getString(...)`
as instance fields, mirroring `thinkingDescription`.

### Done assertion — why it is indirect

The `Done` state's icon has `contentDescription = null` (`ToolCallRow.kt:148-154`) — there is **no
positive CD for done**. So "done" is asserted tolerantly as: the running CD that was present is now
**absent**, the failed CD **does not exist**, and the tool row is still on screen (tool name present).
That triad uniquely identifies a `Running → Done` resolution and never keys on timing. This mirrors the
spinner's "present → absent" shape and is the deliberate tolerant formulation; do not add a test tag or
a new CD to the production component (out of scope — no production change).

## State + concurrency model

Unchanged from #431/#454. The test drives the real app over the real Noise/relay path; the only seam
is the scripted `fakeclaude` backend and the host-side fixture-drop watcher. The two-drop watcher
remains a single background subshell counting `send_message.enqueued` occurrences (1st vs 2nd) — one
`kill` reaps it on teardown. `awaitConnected()` gates on `ConnectionState.Connected` before
interacting (reused verbatim).

## Error handling

This is a test. Failure modes surface as `waitUntil` timeouts (generous `REPLY_TIMEOUT_MS = 90_000`).
The dominant risk is a **mis-correlated fixture** (a `tool_use_id` mismatch between drop A and drop B,
or use-after-result ordering) — the fold silently drops the result and the row never resolves →
timeout. The exact-id requirement above prevents it. Assertions are tolerant (presence/absence of CDs +
the verbatim tool name; generous timeout) so harmless streaming/render variation does not flake.

## Testing strategy

- **Verified in this run (host JVM, no device):** `./gradlew compileDebugAndroidTestKotlin` compiles
  the two new methods (the [androidTest-not-compiled-by-mandatory-gates] lesson — `test`/`lint`/
  `assembleDebug` skip androidTest; compile it explicitly). `onAllNodes` / `onNode` /
  `hasContentDescription` / `assertDoesNotExist` are members (no import beyond what the file already
  has). `./gradlew test lint` stay green (no production change → they cannot regress, but run them to
  confirm nothing else moved).
- **Operator-run (needs infra):** `DETERMINISTIC=1 SCENARIO=tool …` and `DETERMINISTIC=1
  SCENARIO=tool-failed … bash scripts/e2e-emulator.sh`, each re-run back-to-back to confirm the same
  pass (the determinism guarantee). Expect to tune on first run — these are first-green prototypes, not
  hardened gates. Add the new scenarios to the doc's "Operator-run" verification list.
- The assertions intentionally do **not** check `input_summary`/`result_summary` text (producer-derived,
  may be summarized/truncated) — only status CDs + the verbatim tool name. Per the ladder doc's tolerant
  rule.

## Docs update (AC #4) — `docs/e2e-interactive-stream.md`

- Add the two rows to the **Scenarios** table (`:195-199`) and a short tool-step narrative after the
  spinner narrative (`:216-229`): the running → done two-drop reuse, and the failed single drop.
- Update the **Broaden** rung-5 line (`:26-27`): move "tool-use steps (`mobile#455`…)" from ticketed to
  **shipped (#455)**; reconnect/replay (`mobile#436`) stays ticketed.
- Update the **Follow-ups → Coverage** line (`:279-281`): "tool-use event assertion (`mobile#455`)"
  moves from ticketed to shipped.
- Update the fixture-format extension-point note (`:185-186`) to mention #455's tool fixtures landed.
- Add `tool` / `tool-failed` example invocations beside the existing `ping|stream|spinner` ones
  (`:201-205`).

## Open questions (confirm on first operator run — fold into the doc's "Assumptions" section)

1. **Lone `tool_use` opens + holds a turn.** Drop A is a bare `tool_use` line (no preceding `responding`
   text, no `end_turn`), mirroring how `spinner-open.jsonl` is a bare `thinking` line. The producer is
   expected to emit the `tool_use` envelope and leave the turn open. If the emitter instead requires the
   turn to be opened by a prior event, the running-assert times out — fix by prepending a `thinking` or
   short `text` line to `tool-open.jsonl` (it does not affect the tool-row assertion, which keys on the
   tool CD, not the turn state).
2. **Claude-format field names.** `tool_use` block `{id, name, input}` and `user` `tool_result` block
   `{tool_use_id, content, is_error}` are the standard Anthropic transcript shape and match pyrycode's
   tui-driver extractors (`ParseToolUse`/`ParseToolResult`; cf. pyrycode #382/#671). Confirm against the
   operator's pyrycode HEAD on first run; if a field name differs, adjust the fixtures only.
3. **`tool-failed` SCENARIO token.** The hyphen is fine in the `case` arm and on the CLI
   (`SCENARIO=tool-failed`). If a future operator prefers no hyphen, rename to `toolfail` in lockstep in
   the script `case` and the doc — the `@Test` method name is independent.
