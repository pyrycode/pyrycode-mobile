# End-to-end test for the interactive event stream

Automated end-to-end coverage for the phone receiving claude's live reply. Manual testing of this
path is cumbersome, so this builds it steadiest-layer-first and climbs toward the flakier full-UI
layer with Compose + Espresso. Canonical design: pyrycode ADR 025; capstone wire test pyrycode #642.

## The ladder (reliable → flaky)

1. **Wire-level Go test** (`pyrycode#642`) — no emulator. Daemon → relay → simulated phone receives the
   structured stream. The steadiest rung; the future deterministic backend for rung 3/4. Lives in
   pyrycode, not here.
2. **Compose render test** — component level, deterministic, no daemon. Feed the thread a scripted
   structured-event stream and assert the assembled thread renders. **Layer 1a shipped (#432):** the
   reusable `ScriptedThreadHarness` drives the scripted stream through the **real**
   `RemoteConversationRepository` fold → `ThreadViewModel` → `ThreadScreen`, plus the first two render
   cases (text deltas → finalized message; `turn_state` → thinking spinner). Layer 1b (#435, split
   3-way, all riding the same harness) adds tool rows (**shipped #472**), the session divider (#473,
   blocked on the unshipped #336 fold), and the connection banner (#474, blocked on #472). See
   [Layer 1 — component render harness (rung 2)](#layer-1--component-render-harness-rung-2).
3. **Emulator + host daemon + real constrained claude** ← **what this directory ships.** The real app
   on a headless emulator connects to a host `pyry` + relay, sends "reply with exactly: ping", and
   asserts "ping" renders. Semi-deterministic.
4. **Emulator + deterministic host** ← **shipped (#431).** The same real app + Noise/relay path, but
   claude is swapped for #642's scripted `fakeclaude` backend replaying a fixed JSONL fixture. No real
   claude, **zero claude turns**; re-running back-to-back yields the same pass. Run it with
   `DETERMINISTIC=1` — see [Deterministic mode (rung 4)](#deterministic-mode-rung-4).
5. **Broaden** — multi-delta stream render + thinking indicator **shipped (#454)** and tool-use steps
   (running → done, and failed) **shipped (#455, Layer 2c)** on rung 4; reconnect/replay (`mobile#436`,
   Layer 2b) ticketed.

## Layer 1 — component render harness (rung 2)

The cheap rung: a Compose render test with **no network, daemon, or emulator-host**, fast enough to gate
every change. It joins the two halves that the rest of the suite covers separately — the **fold**
(scripted events → `List<ThreadItem>`, unit-tested in `RemoteConversationRepositoryTest`) and the
**render** (`ThreadScreen`, component-tested from a hand-built state in `ThinkingIndicatorTest`) — by
driving a scripted event stream through the real fold and rendering the result.

| Piece | File |
| --- | --- |
| Reusable harness — wires the real graph (`FakeSessionPump` → `RemoteConversationRepository` → `ThreadViewModel` → `ThreadScreen`), exposes `push*` scripting + `awaitReady()` | `app/src/androidTest/.../ui/conversations/thread/ScriptedThreadHarness.kt` (#432) |
| The two render cases (text concatenation→finalize; `turn_state`→spinner) | `app/src/androidTest/.../ui/conversations/thread/ScriptedThreadRenderTest.kt` (#432) |

Key facts (see [`codebase/432.md`](knowledge/codebase/432.md) for the full notes):

- **Drives the REAL repository fold, not the dormant ViewModel fold.** The #337 `assistant_delta` fold
  lives in both `RemoteConversationRepository` (canonical — the live app renders it) and
  `ThreadViewModel.threadItems` (suppressed by a `turnId` render guard whenever the repo already produced
  the row, which it always does live). A fake-repo harness would exercise only the dormant path, so the
  harness wires the **real** repo; the only seam is `FakeSessionPump`.
- **Capability gate open** — built with `negotiatedCapabilities = { setOf("interactive") }`, or the fold
  produces nothing.
- **Subscribe-before-push** — `liveSessionEvents` is `replay = 0`, and `isThinking` is sourced only from
  it, so `awaitReady()` blocks every live push until the VM's collectors have subscribed (proven by the
  seeded channel name rendering in the top bar). That seed name must not be a substring of any asserted
  text (the #431 `e2e-ping`→`e2e-seed` false-green lesson, applied here as `"Harness channel"`).
- **Tolerant asserts only** (substring / presence, generous `waitUntil`) per the Constraints below; the
  text case asserts after `turn_end` (the streaming body reveals progressively and carries the caret).

Layer 1b (**#435**, blocked on #432) extends the same harness with tool rows, the session divider, and
the connection banner — additive scripting methods, no re-wiring. #435 was split 3-way 2026-06-23:
**tool rows shipped (#472)** — `pushToolUse` / `pushToolResult` + `ScriptedToolRowTest`, asserting
running → done (the absence triad) and failed (see [`codebase/472.md`](knowledge/codebase/472.md)); the
session divider (#473, blocked on the unshipped #336 fold) and the connection banner (#474, blocked on
#472) are still ticketed.

| Piece | File |
| --- | --- |
| Tool-step rows (running → done; failed) — `pushToolUse` / `pushToolResult` scripting + builders | `app/src/androidTest/.../ui/conversations/thread/ScriptedThreadHarness.kt` (#472), `ScriptedToolRowTest.kt` (#472) |

## What rung 3 is made of

| Piece | File |
| --- | --- |
| Render fix (#337): fold `assistant_delta` into a streaming assistant row, finalize on `turn_end` | `app/.../data/repository/RemoteConversationRepository.kt` (`applyAssistantDelta`, `finalizeAssistantTurn`) + unit tests in `RemoteConversationRepositoryTest.kt` |
| Headless emulator via Gradle Managed Devices | `app/build.gradle.kts` (`testOptions.managedDevices`, device `pixel2Api33Atd`) |
| Test-only credential injection seam | `app/src/androidTest/.../e2e/E2eInstrumentationRunner.kt` + `E2eTestApplication.kt` |
| The instrumented test | `app/src/androidTest/.../e2e/InteractiveStreamE2ETest.kt` |
| Host orchestration | `scripts/e2e-emulator.sh` |

### The render gap fixed first (#337)

The test asserts streamed assistant *text* renders. Before this change there was nothing to assert:
the live stream was reduced only to the thinking indicator (`isThinking`, #406); `assistant_delta`
text was emitted on the events stream but never folded into a visible thread row. `RemoteConversationRepository`
now folds deltas into a `Role.Assistant` message keyed by `turnId` (mirroring how `tool_use`/`tool_result`
already fold), with `isStreaming = true`; `turn_end` flips it to `false`. `MessageBubble` already
renders that via its streaming-caret body, so no UI change was needed.

An interactive phone receives **only** the structured stream, never the whole-turn `message` for the
same turn (the server's fan-out is capability-exclusive: pyrycode `interactive_turn_v2` vs
`assistant_turn_v2`), so the folded row is the canonical reply — no de-dup against a `message` echo.

### The credential seam

`E2eInstrumentationRunner` is the module's `testInstrumentationRunner`. It installs `E2eTestApplication`
for every instrumented run. The branch happens in that app's `onCreate` (which runs *after* the
instrumentation registers its arguments — the runner's `newApplication` runs before, so it cannot read
them). With no e2e relay arguments it behaves exactly like the production app (default fake
repository), so existing component tests are unchanged. When the e2e relay arguments are present, it:

1. pre-writes a `PairedServer{serverId, token, relayUrl, serverStaticPublicKey}` (built from the
   instrumentation args) into the real credential store, so the app boots straight to the channel list
   (no QR scan) and dials the host relay at `ws://10.0.2.2:<port>`; and
2. binds the relay-backed repository (`conversationRepositoryModule(useRelay = true)`) — the runtime
   equivalent of flipping the compile-time `USE_RELAY_REPOSITORY`, scoped to the e2e run.

No secret is hardcoded: the token + keys are minted at run time by `pyry pair`.

## How to run

```bash
bash scripts/e2e-emulator.sh
```

Prerequisites on the host:

- `pyrycode-relay` and `pyry` on PATH (override with `RELAY_BIN` / `PYRY_BIN`).
- The operator's claude is authenticated on the host — the daemon spawns real claude. The interactive
  path is Max-subscription covered, so this does **not** meter tokens.
- Android SDK with the `aosp-atd` API 33 system image. AGP auto-provisions it on first run, which needs
  the SDK `cmdline-tools` installed and the image licence accepted (`sdkmanager --licenses`). On this
  machine `cmdline-tools` was absent at authoring time — install it before the first run.
- `python3` (decodes the base64url pairing payload).

The script: starts the relay → mints a device token with `pyry pair` and parses the payload → starts
the daemon (`PYRY_MOBILE_V2=1`, pointed at the loopback relay) → runs `pixel2Api33AtdDebugAndroidTest`
with the four values injected as instrumentation arguments → tears everything down.

## Deterministic mode (rung 4)

`DETERMINISTIC=1` swaps real claude for the scripted `fakeclaude` backend (pyrycode #642), which
replays a fixed claude-format JSONL fixture. The emulator app and the Noise/relay path stay real;
**only claude is scripted**, so the run spawns no real claude and consumes **zero claude turns** —
cheap enough to run often, and deterministic enough to re-run back-to-back for the same pass.

```bash
DETERMINISTIC=1 PYRYCODE_SRC=~/Workspace/Projects/pyrycode bash scripts/e2e-emulator.sh
```

Extra prerequisites (on top of the rung-3 list, minus the claude-auth one — rung 4 needs no claude):

- Either `PYRYCODE_SRC` (a local pyrycode checkout) **+ `go`** to build `fakeclaude` from
  `internal/e2e/internal/fakeclaude`, **or** `FAKE_CLAUDE_BIN` pointing at a prebuilt binary.
- The `ping` fixture at `scripts/e2e-fixtures/ping.jsonl` (override with `FIXTURE_FILE`).

### Why a seeded channel, not "New discussion"

The rung-3 test taps "New discussion" → `create_conversation`, which mints a **fresh** per-conversation
claude session (a new random UUID, passed to claude as `--session-id`). The daemon's structured-turn
producer then tails *that* conversation's transcript **by that id** (no latest-file fallback). Real
claude honours `--session-id` and writes `<freshId>.jsonl`, so rung 3 aligns — but `fakeclaude` is
**env-only**: it ignores `--session-id` and always writes `<PYRY_FAKE_CLAUDE_INITIAL_UUID>.jsonl`. A
created-discussion turn would land in a file the producer never tails, and the scripted reply would
never reach the phone.

So deterministic mode (mirroring #642's bootstrap-bound model through the real UI) pre-seeds **one
promoted conversation** (a Channel) bound to the bootstrap session id, and a thin test variant taps
**that seeded channel** instead of creating a discussion. One conversation, one session, one fixture
file → the producer tails exactly the file `fakeclaude` writes.

### What the host does (deterministic seams)

1. **Isolated HOME** — pairs and runs the daemon under a short `/tmp/pyry-e2e-det.*` HOME so the
   scripted `.pyry/<name>/` + `.claude/projects/` never touch the operator's real profile (and the
   daemon's unix control socket stays under the ~104-char `sun_path` limit).
2. **Pre-seed** (after `pyry pair`, before daemon start): build/locate `fakeclaude`; compute the
   sessions dir `<HOME>/.claude/projects/<encode(HOME)>` (the daemon's exact tail dir, `/` and `.`
   both → `-`); pre-create `<INITIAL_UUID>.jsonl` (avoids the cold-start tail race); write one
   promoted row to `conversations.json` with `current_session_id == INITIAL_UUID`,
   `is_promoted: true`, `name: "e2e-seed"` (deliberately **not** `"…ping…"`: the seeded channel name
   renders verbatim in the thread top bar, and the reply is asserted as a `"ping"` substring — a
   `"ping"`-bearing channel name would false-green the test on the title alone).
3. **Daemon** — adds `-pyry-claude=<fakeclaude>`, `-pyry-workdir=<HOME>`, and the
   `PYRY_FAKE_CLAUDE_*` env (`TUI=1`, `INITIAL_UUID`, `SESSIONS_DIR`, `JSONL_TRIGGER`).
4. **Fixture-drop watcher** — a background job waits for the `send_message.enqueued` line in
   `daemon.log` (the cursor-stamp fence: `router.Route` stamps the producer cursor, *then* logs
   `send_message.enqueued`), then copies the scenario's fixture onto the JSONL trigger. `fakeclaude`
   appends it verbatim to the live session JSONL; the real producer tails it → `turn_state(responding)`
   → `assistant_delta(…)` → `turn_end` → `turn_state(idle)` → the phone's #337 fold renders the reply.
   The `spinner` scenario drops **twice** (see [Scenarios](#scenarios-454)). (Older daemons may emit a
   different fence token — confirm on first operator run.)

### The fixture format (extension point for #454/#436)

`scripts/e2e-fixtures/*.jsonl` are real files (not inlined), so sibling scenarios can be added. One
claude-format line per turn-event, trailing newline; the daemon's structured producer tails it
line-delimited. The "ping" fixture is a single `assistant` line with `stop_reason: "end_turn"` and
non-empty text:

```json
{"type":"assistant","message":{"id":"ping-1","stop_reason":"end_turn","content":[{"type":"text","text":"ping"}]}}
```

`#454` added the `stream` + `spinner` fixtures below; `#455` added the `tool-open` / `tool-done` /
`tool-failed` tool-step fixtures (same shape, with `tool_use` / `tool_result` content blocks); `#436`
(reconnect + replay) builds its fixtures on this same shape.

### Scenarios (#454)

`DETERMINISTIC=1` runs **one scenario per invocation**, selected by `SCENARIO` (default `ping`, which
preserves #431 unchanged). Each scenario maps to a single `@Test` method in
`DeterministicInteractiveStreamE2ETest` and its own fixture(s); the script runs exactly that one method
(`-Pandroid.testInstrumentationRunnerArguments.class=<class>#<method>`):

| `SCENARIO` | asserts | fixture(s) | drops |
| --- | --- | --- | --- |
| `ping` (default) | a single-line reply renders | `ping.jsonl` | one |
| `stream` | a multi-`assistant_delta` reply assembles into **one** message | `stream.jsonl` | one |
| `spinner` | the thinking spinner shows mid-turn, then clears at turn end | `spinner-open.jsonl` + `spinner-end.jsonl` | **two** |
| `tool` (#455) | a tool step shows **running** in flight, then **done** after the result | `tool-open.jsonl` + `tool-done.jsonl` | **two** |
| `tool-failed` (#455) | a failing tool step renders **failed** | `tool-failed.jsonl` | one |

```bash
DETERMINISTIC=1 PYRYCODE_SRC=~/Workspace/Projects/pyrycode bash scripts/e2e-emulator.sh                      # ping
DETERMINISTIC=1 SCENARIO=stream      PYRYCODE_SRC=~/Workspace/Projects/pyrycode bash scripts/e2e-emulator.sh # stream
DETERMINISTIC=1 SCENARIO=spinner     PYRYCODE_SRC=~/Workspace/Projects/pyrycode bash scripts/e2e-emulator.sh # spinner
DETERMINISTIC=1 SCENARIO=tool        PYRYCODE_SRC=~/Workspace/Projects/pyrycode bash scripts/e2e-emulator.sh # tool running→done
DETERMINISTIC=1 SCENARIO=tool-failed PYRYCODE_SRC=~/Workspace/Projects/pyrycode bash scripts/e2e-emulator.sh # tool failed
```

**`stream`** — `stream.jsonl` is three `text` lines with **distinct** `message.id`s, so the producer
emits three `assistant_delta` envelopes (same `turn_id`, `seq` 0/1/2); the last line's
`stop_reason: end_turn` + non-empty text also yields `turn_end`. The phone's #337 fold concatenates the
three deltas (keyed by `turn_id`) into the single message `"Hello, streamed world"`. The test asserts
the cross-delta-boundary substring `"streamed world"`, present only if the deltas assembled into one
message (never on delta count or the streaming caret).

**`spinner`** — the thinking state is transient: a single fixture with `thinking` then `end_turn` would
flip `isThinking` true→false within one tail cycle, before Compose ever lays out the spinner — an
unobservable race. So the harness holds the turn open and ends it on a **causal** fence, with two drops:

1. **Drop A** (`spinner-open.jsonl`, a `thinking`-only line) fires on the **1st** `send_message.enqueued`
   → `turn_state(thinking)`, held → the spinner stays on indefinitely (no `end_turn`).
2. The test asserts the spinner is shown, then sends a **2nd** message.
3. **Drop B** (`spinner-end.jsonl`, a normal end-of-turn text line) fires on the **2nd**
   `send_message.enqueued` → `turn_state(responding)` (spinner clears) → `turn_end` → `turn_state(idle)`.

Because drop B is gated on the 2nd enqueue — which happens only after the presence-assert passed — the
thinking window is arbitrarily long. There is **no timing dependency and no fixed host delay**; a slow
phone cannot clear the spinner before the presence-assert catches it. The 2nd message's inert transcript
growth injects no event; only drop B's line ends the turn. The two-drop watcher stays a single
background subshell (counting `send_message.enqueued` occurrences to tell the 1st enqueue from the 2nd),
so one `kill` reaps it on teardown.

**`tool`** — a tool step must render **running** in flight and **done** after the result. "Running" is
transient (the fold flips the row to done the instant the correlated `tool_result` arrives), so it reuses
the spinner's **two-drop causal fence**:

1. **Drop A** (`tool-open.jsonl`, a lone `tool_use` line) fires on the **1st** `send_message.enqueued` →
   the fold opens a `Running` `Role.Tool` row keyed by the `tool_use` id, held open (no `end_turn`).
2. The test asserts the running content-description (`cd_tool_running`) is shown, then sends a **2nd**
   message.
3. **Drop B** (`tool-done.jsonl`, the correlated success `tool_result` (`is_error: false`) + a
   turn-ending text line) fires on the **2nd** `send_message.enqueued` → the fold flips the row to
   `Done` and closes the turn.

`Done` has **no positive content-description** (the resolved icon's `contentDescription` is `null`), so
"done" is asserted **indirectly**: the running CD that was present is now absent, the failed CD never
appears, and the tool row is still on screen (the verbatim tool name `"Bash"`). That triad uniquely
identifies a running → done resolution and never keys on timing. **Correlation is load-bearing:** drop
A's `tool_use` `id` must equal drop B's `tool_result` `tool_use_id` (same literal id in both files) or
the fold drops the result and the row never resolves.

**`tool-failed`** — a failing tool step must render **failed**. The failed end state is stable (it does
not auto-resolve), so it needs **no two-drop fence**: a single fixture (`tool-failed.jsonl`) carries
`tool_use` → an error `tool_result` (`is_error: true`) → a turn-ending text line, all in one drop. The
fold renders the row `Running` (briefly) → `Failed`; the test asserts only the terminal `cd_tool_failed`
content-description (tolerant, stable). The `tool_use` line must precede the `tool_result` line so they
correlate. Assertions never depend on the producer-derived `input_summary`/`result_summary` text — only
the status CDs and the verbatim tool name.

## Verification status

- **Verified here (host JVM, no device):** the #337 fold (full `RemoteConversationRepositoryTest`
  suite is green), the whole unit suite stays green, and all androidTest sources compile
  (`compileDebugAndroidTestKotlin`). The pairing-payload parser is unit-checked against a synthetic
  payload.
- **Operator-run (needs your infra):** the actual headless-emulator + host-daemon run — for rung 3
  with real claude (`bash scripts/e2e-emulator.sh`), and for rung 4 with the scripted backend, each of
  the five [scenarios](#scenarios-454) (`DETERMINISTIC=1 SCENARIO=ping|stream|spinner|tool|tool-failed …
  bash scripts/e2e-emulator.sh`, `DeterministicInteractiveStreamE2ETest`). That is the point of both
  rungs — prove the emulator↔host↔app chain end to end. Expect to tune on first run; these are
  hand-built first-green prototypes, not hardened gates. Re-running each rung-4 scenario back-to-back
  must yield the same pass — that determinism is the whole point and the thing to confirm on real infra.
- **Negative control:** `InteractiveStreamE2ETest.negativeControl_wordClaudeNeverSays_isNeverDisplayed`
  is `@Ignore`d. Un-ignore it once to confirm the positive assertion can fail (it waits for a word
  claude is never asked to say, so it must time out). Re-ignore after, so it does not burn a turn. Rung
  4 needs no negative control: the scripted backend makes the positive assertion deterministic.

## Assumptions to confirm on first run

These are grounded in the source but unverified end to end:

- **Fixture-drop fence token.** The watcher fences on the `send_message.enqueued` line in `daemon.log`
  (current pyrycode HEAD: `router.Route` stamps the producer cursor, then logs `send_message.enqueued`).
  If the operator runs an older daemon that logs a different token, the fixture never drops and the test
  times out — confirm the actual `daemon.log` token on first run and adjust the watcher grep.
- **`pyry pair` before daemon start.** The script mints the token before starting the daemon, so the
  daemon loads it on boot. If the daemon does not recognise the token, try pairing after the daemon is
  up, or restart the daemon after pairing.
- **`-pyry-name=e2e-emulator`** namespaces the daemon socket/identity so it does not clobber a
  production daemon on the host. `pyry pair` and the daemon must use the **same** name so they share
  identity (hence the same `server_static_pubkey`).
- **Connection readiness.** The test waits for `ConnectionState.Connected` before creating a
  conversation. If "Connected" precedes the Noise session being fully Open, `createDiscussion` could
  race; the symptom is the thread step timing out. Add a small settle or a pump-Open wait if so.
- **ATD image vs Play services.** The paired happy path never opens the QR scanner, so `aosp-atd`
  (no Play services) should suffice. If something needs Play services, switch `systemImageSource` to
  `google-atd` in `app/build.gradle.kts` (still headless).
- **Lone `tool_use` opens + holds a turn (#455 `tool` scenario).** Drop A (`tool-open.jsonl`) is a bare
  `tool_use` line (no preceding `responding` text, no `end_turn`), mirroring how `spinner-open.jsonl` is
  a bare `thinking` line. The producer is expected to emit the `tool_use` envelope and leave the turn
  open. If the emitter instead requires a prior event to open the turn, the running-assert times out —
  fix by prepending a `thinking` or short `text` line to `tool-open.jsonl` (it does not affect the
  tool-row assertion, which keys on the tool CD, not the turn state).
- **Claude-format tool field names (#455).** The `tool_use` block `{id, name, input}` and the `user`
  `tool_result` block `{tool_use_id, content, is_error}` are the standard Anthropic transcript shape and
  match pyrycode's tui-driver extractors (`ParseToolUse`/`ParseToolResult`; cf. pyrycode #382/#671).
  Confirm against the operator's pyrycode HEAD on first run; if a field name differs, adjust the fixtures
  only. Correlation is load-bearing: `tool-open.jsonl`'s `tool_use` `id` must equal `tool-done.jsonl`'s
  `tool_result` `tool_use_id` (`toolu_e2e` in both) or the fold drops the result and the row never
  resolves.
- **`tool-failed` SCENARIO token.** The hyphen is fine in the `case` arm and on the CLI
  (`SCENARIO=tool-failed`). If a future operator prefers no hyphen, rename to `toolfail` in lockstep in
  the script `case` and this doc — the `@Test` method name is independent.

## Follow-ups to ticket

- **Rung 4 (shipped, #431; extended #454, #455):** deterministic host backend via #642's scripted
  `fakeclaude` — see [Deterministic mode (rung 4)](#deterministic-mode-rung-4). #454 added the
  multi-delta `stream` render + the `spinner` scenario, and #455 added the `tool` / `tool-failed`
  tool-step scenarios (see [Scenarios](#scenarios-454)); `#436` (reconnect + replay, Layer 2b) extends
  the same fixture format.
- **Rung 2 (Layer 1a shipped, #432):** the cheap Compose render harness — see
  [Layer 1 — component render harness (rung 2)](#layer-1--component-render-harness-rung-2). Layer 1b
  (#435, rides the same harness) adds tool rows, the session divider, and the connection banner.
- **Coverage:** thinking indicator (hardest, screen-sourced) — **shipped (#454)**, alongside the
  multi-delta `stream`-render scenario; tool-use event assertion (running → done, and failed) —
  **shipped (#455, Layer 2c)**; reconnect / replay (`mobile#436`, Layer 2b) remains ticketed.
- **#337 full scope:** `seq`-based ordering and replay de-dup across reconnect (a #402 concern; this
  fold concatenates in arrival order, correct within a single connection); and a `make`/Gradle wrapper
  for the orchestration plus fork-sync of any shared `bin/` script per the org convention.
- **Recover `pyrycode#642`** (the parked wire-level run) for the pipeline, or note it there.

## Constraints

- Runs as a local Gradle/script command, **not** a GitHub CI gate (the org does not gate on Actions).
- Assert tolerantly (substring, trimmed, generous timeouts); never on delta counts or timing.
- Keep the test to the single structured path; do not assert the coarse `message` path. As of 2026-06-22 there is no old-app-version support: the operator controls both ends and ships the app and daemon together, so every phone gets the structured stream and the coarse path is dead code slated for removal. See the 2026-06-22 amendment in pyrycode ADR 025.
