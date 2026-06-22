# Spec #431 — Deterministic scripted backend for the emulator e2e (rung 4)

## Files to read first

Read these before writing anything. Mobile-side paths are in this repo; pyrycode-side paths
are in the local checkout at `~/Workspace/Projects/pyrycode` (read-only reference — **no pyrycode
change ships from this ticket**).

- `scripts/e2e-emulator.sh` (whole file, 159 lines) — the **rung-3** host orchestration you extend.
  Reuse its relay-start/healthz, `pyry pair` + payload-parse, gradle managed-device invocation,
  phone relay URL, and the `cleanup` EXIT trap verbatim. The deterministic mode is a branch on this.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt:46-150` — the rung-3
  test you clone into a thin variant. Only the conversation-entry step changes (tap a seeded channel
  vs. tap "New discussion"); `awaitConnected()` + the tolerant assert pattern carry over.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/E2eTestApplication.kt:34-88` — the credential seam.
  **Unchanged and reused**: the deterministic mode passes the same `relayUrl`/`token`/`serverId`/
  `serverStaticPublicKey` instrumentation args, so the app boots paired + relay-backed exactly as in
  rung 3.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/E2eInstrumentationRunner.kt` — the runner. Unchanged.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationRow.kt:36-97` — how a
  channel row renders: the `name` is a plain `Text(displayName)` and the merged-semantics row is
  `role = Role.Button`. Your tap selector keys off the channel **name** text node.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:757-776` —
  `observeConversations` + the `ConversationFilter.Channels` filter (`isPromoted && !archived`). This
  is why a seeded **promoted** row surfaces on the launch channel list.
- `docs/e2e-interactive-stream.md:1-128` — the ladder doc. You update the rung-4 entry (line 17-18)
  from "Ticketed" to shipped and add a "How to run (deterministic mode)" subsection.
- pyrycode `internal/e2e/relay_two_phone_structured_test.go:102-269` — the **#642 capstone**. This
  is the in-process Go analog of what your host script does out-of-process: seed → pre-create
  `<initialUUID>.jsonl` → start daemon → drive the turn → **await the ack fence** → drop the JSONL
  fixture. Mirror this sequence on the host.
- pyrycode `internal/e2e/harness.go:362-381` — `seedBoundConversation`: the exact one-row
  `conversations.json` JSON shape. Yours differs only in `is_promoted:true` + a `name`.
- pyrycode `internal/e2e/internal/fakeclaude/main.go:55-77` (env contract) and `:193-218`
  (`emitStructuredJSONLIfTriggered`) — fakeclaude is **env-only**, ignores argv/`--session-id`,
  always writes `<PYRY_FAKE_CLAUDE_INITIAL_UUID>.jsonl`, and appends the JSONL-trigger file's
  contents verbatim to that file then fsyncs+removes the trigger.
- pyrycode `internal/sessions/reconcile.go:29-49` — `encodeWorkdir`: the **production** sessions-dir
  encoding your script must replicate byte-for-byte (`strings.NewReplacer("/", "-", ".", "-")`).
- pyrycode `internal/turnbridge/mapper.go:20-75` + `cmd/pyry/interactive_turn_v2.go:140-194` — the
  fixture→envelope mapping oracle. A single `assistant` line with `stop_reason:"end_turn"` + non-empty
  text yields `turn_state(responding)` → `assistant_delta(<text>)` → `turn_end` → `turn_state(idle)`.
- pyrycode `internal/relay/handlers/send_message.go:167-174` — the `send_message ack` Info log
  (`event=send_message.ack`). This handler runs **in the daemon** (it calls `WriteUserTurn`), so the
  line lands in `daemon.log`. It is the host-observable fence for the fixture drop.

## Context

`scripts/e2e-emulator.sh` ships **rung 3**: real app on a headless emulator → host `pyry` daemon →
**real claude** → assert "ping" renders. It is semi-deterministic and burns a real claude turn, so it
can't run often.

This ticket adds **rung 4**: swap real claude for the scripted `fakeclaude` backend (pyrycode #642),
which replays a fixed JSONL fixture. The emulator app and the Noise/relay path stay real; **only
claude is scripted**. Foundation for #433 (stream render + spinner + tool steps) and #436
(reconnect + replay), both `blockedBy` this.

**The structural constraint (already settled by PO; do not re-litigate).** The rung-3 test taps
**"New discussion"** → `create_conversation` mints a **fresh** session UUID, and the daemon's
structured-turn producer tails *that* conversation's transcript **by that id** with no latest-file
fallback. But `fakeclaude` ignores `--session-id` and always writes `<initialUUID>.jsonl` — so a
created-discussion turn lands in a file the producer never tails, and the scripted reply never reaches
the phone. The resolution (**no pyrycode change**) is **Path B**: pre-seed one **promoted**
conversation bound to the bootstrap session id, and tap **that seeded channel**. One conversation,
one session, one fixture file → the producer tails exactly the file `fakeclaude` writes.

## Design

Three artifacts, all exercising already-shipped, already-reviewed phone code:

1. **A deterministic mode on `scripts/e2e-emulator.sh`** (host harness).
2. **A thin androidTest variant** that taps the seeded channel instead of creating a discussion.
3. **A one-line "ping" JSONL fixture** the host drops after the ack fence.

Plus a docs update to `docs/e2e-interactive-stream.md`.

### Decision: flag on the existing script (not a sibling script)

Add a **`DETERMINISTIC=1`** env mode (matching the script's existing env-tunable convention:
`PORT=`, `DEVICE=`, `PYRY_NAME=` …), branching at three localized seams. Rationale: relay-start,
`pyry pair` + payload-parse, the gradle invocation, and the teardown trap are **identical** between
the two rungs and substantial (~50 lines). The rung-3 script is explicitly an operator-tuned
prototype ("expect to tune on first run") — it will keep changing, and one source of truth for the
shared scaffolding avoids fixing two copies. A sibling script is an acceptable fallback **only** if
the branching turns out to be unwieldy; if you take it, it must `source` the shared steps, not
copy-paste them.

The three divergent seams:

- **Pre-seed block** (new, runs only in deterministic mode, **after `pyry pair`, before daemon
  start**): build/locate `fakeclaude`, compute the sessions dir, pre-create `<initialUUID>.jsonl`,
  write the seeded `conversations.json`.
- **Daemon-start block** (branch): deterministic mode adds `-pyry-claude=<fakeBin>`,
  `-pyry-workdir=<HOME>`, an isolated `HOME`, and the `PYRY_FAKE_CLAUDE_*` env; real mode is unchanged.
- **Fixture-drop watcher** (new, runs only in deterministic mode, **around the gradle invocation**):
  a background job that waits for `send_message.ack` in `daemon.log`, then drops the fixture.

`TEST_CLASS` is selected by mode: real → `InteractiveStreamE2ETest`, deterministic → the new variant.

### Isolated HOME (load-bearing)

Deterministic mode runs `pyry pair` **and** the daemon with `HOME=<isolatedHome>` so seeding and the
scripted `.claude/projects/` never touch the operator's real profile. Create it with a **short**
prefix under `/tmp` (e.g. `mktemp -d /tmp/pyry-e2e-det.XXXXXX`), explicitly **not** under `$TMPDIR`
— on macOS `$TMPDIR` is long and the daemon's unix control socket can exceed the ~104-char limit
(this is why pyrycode's `shortHome` uses a short prefix). `os.UserHomeDir()` reads `$HOME`, so the
override redirects both `.pyry/<name>/` and `.claude/projects/` for pair and daemon alike.

### Sessions-dir computation (must match the daemon exactly)

The daemon tails `<HOME>/.claude/projects/<encode(workdir)>/` where `workdir == HOME` (we pass
`-pyry-workdir=<HOME>`) and `encode` replaces **both** `/` and `.` with `-` (see reconcile.go). In
bash:

```bash
ENC="${ISO_HOME//\//-}"; ENC="${ENC//./-}"
SESSIONS_DIR="${ISO_HOME}/.claude/projects/${ENC}"
```

`mkdir -p "$SESSIONS_DIR"`, then pre-create `"${SESSIONS_DIR}/${INITIAL_UUID}.jsonl"` containing
`{}\n` **before the daemon starts** — this seeds the producer's resolve at a tiny offset and avoids
the cold-start race (an empty dir makes the first resolve retry ~500 ms later, possibly *past* the
fixture). `INITIAL_UUID` is any fixed canonical UUIDv4 string (e.g.
`43143143-4314-4314-8314-431431431431`).

### Seeded conversation (`conversations.json`)

Write one promoted row to `<ISO_HOME>/.pyry/<PYRY_NAME>/conversations.json` (the dir exists because
`pyry pair` ran first), **before the daemon starts** (the daemon loads it once at boot, no reload).
Mirror `seedBoundConversation`, changed to a Channel:

```json
{"conversations":[{"id":"<convUUID>","name":"e2e-ping","cwd":"<ISO_HOME>","current_session_id":"<INITIAL_UUID>","is_promoted":true,"last_used_at":"2026-01-01T00:00:00Z"}]}
```

`current_session_id == INITIAL_UUID` is load-bearing: `sessionRouter.Route` rejects an empty
`current_session_id` (#678), and reconciliation binds the bootstrap session to the most-recent (here:
only) `<uuid>.jsonl`, which is `<INITIAL_UUID>`. `is_promoted:true` makes it list as a **Channel** on
first launch; `name:"e2e-ping"` is the test's tap target. `convUUID` is any distinct fixed UUID. The
daemon's `list_conversations` projects `id`/`name`/`is_promoted`/`cwd` into the `ConversationSummary`
the app's `observeConversations` consumes — so the row surfaces with that name.

### fakeclaude env (set on the daemon process; inherited by the spawned child)

| Env var | Value | Why |
| --- | --- | --- |
| `PYRY_FAKE_CLAUDE_SESSIONS_DIR` | `$SESSIONS_DIR` | where fakeclaude writes `<initialUUID>.jsonl` (must equal the daemon's computed tail dir) |
| `PYRY_FAKE_CLAUDE_INITIAL_UUID` | `$INITIAL_UUID` | the JSONL stem; must equal the seeded `current_session_id` |
| `PYRY_FAKE_CLAUDE_TRIGGER` | a never-created path (e.g. `$WORK/rotate.never`) | **required** (`mustEnv`); rotation is unused here |
| `PYRY_FAKE_CLAUDE_JSONL_TRIGGER` | `$WORK/structured.jsonl.trigger` | the fixture-drop path; fakeclaude appends its contents to the live JSONL |
| `PYRY_FAKE_CLAUDE_TUI` | `1` | emits the idle/thinking glyphs so the daemon's WaitReady/commit path confirms a turn fast and the ack is prompt |
| `PYRY_ALLOW_INSECURE_RELAY` | `1` | already set in rung 3 for the `ws://` relay |
| `PYRY_MOBILE_V2` | `1` | already set in rung 3 |

Reuse rung-3's relay wiring (`PYRY_RELAY_URL`, the `/v1/server`–vs–`/v2/server` path, `PHONE_RELAY_URL`
bare origin) **verbatim** — the relay/pair/phone path is unchanged from rung 3; only the claude backend
and seeding change. Do not re-derive the relay path.

`fakeclaude` binary: prefer `FAKE_CLAUDE_BIN` (a prebuilt path) if set; otherwise
`go build -o <tmp>/fakeclaude ./internal/e2e/internal/fakeclaude` from `${PYRYCODE_SRC}` (a new
prerequisite env naming the local pyrycode checkout). `-pyry-claude=<that path>`.

### The fixture-drop fence (the one genuinely concurrent piece)

The fixture must drop **after** the turn is acked — the ack stamps the producer cursor; drop too early
and the producer tails past the fixture and the phone gets **zero** envelopes. The host-observable
fence is the `event=send_message.ack` line in `daemon.log`. Because the gradle test (which sends the
prompt mid-run) and the fence are in different processes, the host watches in the background and drops
the fixture when the line appears, concurrently with the foreground gradle run:

```bash
( tail -F "$DAEMON_LOG" 2>/dev/null | grep -q -m1 'send_message.ack' \
    && cp "$FIXTURE_FILE" "$JSONL_TRIGGER" ) &
WATCHER_PID=$!
# … foreground: gradlew <device>DebugAndroidTest … (as in rung 3, new TEST_CLASS) …
```

Add `WATCHER_PID` to the `cleanup` trap (`kill` it on EXIT). `grep -m1` fires once; fakeclaude
consumes the trigger once (reads → appends → fsyncs → removes). Match on the event **value**
(`send_message.ack`), robust to the slog handler's exact key=value rendering.

### The "ping" JSONL fixture

A single claude-format line (with trailing newline — fakeclaude appends verbatim and the producer
tails line-delimited):

```
{"type":"assistant","message":{"id":"ping-1","stop_reason":"end_turn","content":[{"type":"text","text":"ping"}]}}
```

Per the mapper/producer oracle this yields `turn_state(responding)` → `assistant_delta("ping")` →
`turn_end` → `turn_state(idle)`; the phone's #337 fold renders "ping" as a streaming assistant row,
finalized on `turn_end`. **This file is the extension point #433/#436 build on** — a real file, not
inlined, so sibling fixtures can be added. Recommended location: `scripts/e2e-fixtures/ping.jsonl`
(the script reads `$FIXTURE_FILE` from there). If you discover the single line doesn't emit
`turn_state(responding)` (it should — `startTurnIfNeeded` runs on the first `TextChunk`), fall back to
the #642 two-line shape: a text-only line, then an `end_turn` line.

### The thin androidTest variant

New class `DeterministicInteractiveStreamE2ETest` in `app/src/androidTest/java/de/pyryco/mobile/e2e/`
(package `de.pyryco.mobile.e2e`). A near-clone of `InteractiveStreamE2ETest` with **one** changed
step. **Do not refactor `InteractiveStreamE2ETest`** to share code — the small duplication of
`awaitConnected()` + a few constants is intentional (Simplicity-First; don't touch the shipped
rung-3 test). Single `@Test`:

- Wait (`LIST_TIMEOUT`) for the seeded channel row to appear — i.e. a text node `"e2e-ping"`
  (`onAllNodesWithText(SEED_CHANNEL_NAME)` non-empty). This implicitly waits for the connection +
  `list_conversations` response, since the list populates from the daemon.
- `awaitConnected()` (copied from rung 3) — explicit gate before sending.
- Tap the seeded row: `onAllNodesWithText(SEED_CHANNEL_NAME).onFirst().performClick()` → wait
  (`THREAD_TIMEOUT`) for the send button (`hasContentDescription("Send message")`) to confirm the
  thread opened.
- Type a **non-"ping"** prompt (e.g. `"hello"`) into the editable field and click send. The scripted
  backend replies "ping" regardless of prompt, so a non-"ping" prompt keeps the only on-screen "ping"
  the scripted reply — no baseline/+1 dance needed.
- Assert tolerantly: wait (`REPLY_TIMEOUT`, keep it generous, ~90 s) until a node with text "ping"
  (`substring = true, ignoreCase = true`) exists, then `.onFirst().assertIsDisplayed()`.

Constants: `SEED_CHANNEL_NAME = "e2e-ping"` (must equal the seeded `name`), `SEND_PROMPT = "hello"`,
plus the timeouts and the `CD_SEND_MESSAGE = "Send message"` selector copied from rung 3. No negative
control needed (the scripted backend makes the positive assertion deterministic; the rung-3
`@Ignore`d negative control already documents the falsifiability argument).

## State + concurrency model

No Kotlin app state changes — the production decode/fold path (#337/#385) is exercised, not modified.
Concurrency lives entirely in the host script: one **background** watcher (`tail -F | grep -m1` →
`cp`) runs concurrently with the **foreground** gradle managed-device task; both are children of the
script and reaped by the EXIT trap. Strict ordering the script must enforce:

1. relay up → `pyry pair` (creates `.pyry/<name>/`) → **pre-seed** (`fakeclaude` built, sessions dir +
   `<initialUUID>.jsonl` created, `conversations.json` written) → **daemon start** (loads
   `conversations.json` once) → spawn watcher → gradle.
2. In-test: list appears → connected → tap channel → send → (host watcher sees `send_message.ack` →
   drops fixture) → producer tails fixture → structured stream → phone renders "ping".

## Error handling

Host-script failures surface as a non-zero exit with logs kept at `$WORK_DIR` (rung-3 `cleanup`
already does this). Add deterministic-mode preflight: fail fast if neither `FAKE_CLAUDE_BIN` nor
`PYRYCODE_SRC` is set/usable, and if the `go build` fails (print stderr). The test asserts tolerantly
with generous timeouts; a timeout on the "ping" wait is the expected failure signal if the
chain breaks (the kept `daemon.log`/`relay.log` are the diagnostics). No new in-app error surfaces.

## Testing strategy

The deliverable **is** the test. There is no unit test of the harness; its single proving run is the
green deterministic e2e (instrumented, `pixel2Api33AtdDebugAndroidTest`, device/emulator required —
operator-run, like rung 3). Before handing off, verify what's checkable on the host JVM without a
device:

- `./gradlew compileDebugAndroidTestKotlin` — the new test class compiles.
- `./gradlew spotlessCheck` (or `ktlintCheck`) — the new `.kt` file passes formatting; note the
  filename rule (a single public top-level class must match the filename).
- `bash -n scripts/e2e-emulator.sh` — the script parses.
- Sanity-read the fixture line as valid JSON.

The actual emulator run is operator-run; document it as such (do not claim a green e2e you can't run
without a device). Re-running it back-to-back must yield the same pass — that determinism is the
whole point and the thing to confirm once on real infra.

## Design source

N/A — test-harness + docs ticket. No `## Figma` section in the body and none needed: this exercises
already-shipped, already-Figma-anchored thread UI (#337/#385/#386) and adds no new UI surface. The
visual-fidelity check is intentionally not applicable.

## Security

`security-sensitive` is correctly omitted (the body justifies it; I concur after review): a
test-harness + docs ticket exercising the already-shipped, already-security-reviewed Noise/decode
path. No new untrusted-input parse point (the fixture is host-authored and host-dropped, not attacker
input), no credential/crypto/auth change (pairing reuses rung-3's mint-at-runtime flow under an
isolated HOME), and a single interactive phone — so the two-phone capability-gate exclusion invariant
(#642's security point) is not in play here. No security-review pass required.

## Open questions

- **Relay server path (`/v1/server` vs `/v2/server`).** Rung 3 uses `/v1/server`; the #642 Go test
  uses `/v2/server`. Reuse rung-3's value unchanged — rung 3 already receives the v2 structured stream
  over it, so it works as authored. If the deterministic run shows the daemon never registering,
  that's a shared rung-3 tuning item, not new scope here.
- **`send_message.ack` log line format.** Confirmed the line exists and is logged at Info from the
  daemon-side handler. The exact key=value rendering depends on the slog handler; matching the event
  value `send_message.ack` (not a fixed full-line format) is the robust choice. Confirm on first run.
- **List-populate timing.** The seeded channel should appear shortly after the connection opens
  (`list_conversations` round-trip). The `LIST_TIMEOUT` wait on the `"e2e-ping"` node absorbs it; if
  it's flaky, the gate is connection-readiness, not the row — bump the timeout, don't poll counts.
