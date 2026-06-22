# End-to-end test for the interactive event stream

Automated end-to-end coverage for the phone receiving claude's live reply. Manual testing of this
path is cumbersome, so this builds it steadiest-layer-first and climbs toward the flakier full-UI
layer with Compose + Espresso. Canonical design: pyrycode ADR 025; capstone wire test pyrycode #642.

## The ladder (reliable → flaky)

1. **Wire-level Go test** (`pyrycode#642`) — no emulator. Daemon → relay → simulated phone receives the
   structured stream. The steadiest rung; the future deterministic backend for rung 3/4. Lives in
   pyrycode, not here.
2. **Compose render test** — component level, deterministic, no daemon. Feed the thread a streamed
   "ping" and assert it renders. (Ticketed; not built yet.)
3. **Emulator + host daemon + real constrained claude** ← **what this directory ships.** The real app
   on a headless emulator connects to a host `pyry` + relay, sends "reply with exactly: ping", and
   asserts "ping" renders. Semi-deterministic.
4. **Emulator + deterministic host** — swap real claude for #642's scripted structured backend for a
   fully-deterministic gate. (Ticketed.)
5. **Broaden** — tool-use event, thinking indicator, reconnect/replay (`mobile#402`). (Ticketed.)

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

## Verification status

- **Verified here (host JVM, no device):** the #337 fold (full `RemoteConversationRepositoryTest`
  suite is green), the whole unit suite stays green, and all androidTest sources compile
  (`compileDebugAndroidTestKotlin`). The pairing-payload parser is unit-checked against a synthetic
  payload.
- **Operator-run (needs your infra):** the actual headless-emulator + host-daemon + real-claude run.
  That is the point of rung 3 — prove the emulator↔host↔app chain end to end. Expect to tune on first
  run; this is a hand-built first-green prototype, not a hardened gate.
- **Negative control:** `InteractiveStreamE2ETest.negativeControl_wordClaudeNeverSays_isNeverDisplayed`
  is `@Ignore`d. Un-ignore it once to confirm the positive assertion can fail (it waits for a word
  claude is never asked to say, so it must time out). Re-ignore after, so it does not burn a turn.
- **Flakiness:** re-run 3–5 times to gauge it before deciding whether rung 4 (deterministic backend)
  is worth building next.

## Assumptions to confirm on first run

These are grounded in the source but unverified end to end:

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

## Follow-ups to ticket

- **Rung 4:** deterministic host backend (reuse/extend #642's scripted-JSONL harness) for a no-claude,
  fully-deterministic emulator gate.
- **Rung 2:** the cheap Compose render component test.
- **Coverage:** tool-use event assertion; thinking indicator (hardest, screen-sourced); reconnect /
  replay once `mobile#402` lands.
- **#337 full scope:** `seq`-based ordering and replay de-dup across reconnect (a #402 concern; this
  fold concatenates in arrival order, correct within a single connection); and a `make`/Gradle wrapper
  for the orchestration plus fork-sync of any shared `bin/` script per the org convention.
- **Recover `pyrycode#642`** (the parked wire-level run) for the pipeline, or note it there.

## Constraints

- Runs as a local Gradle/script command, **not** a GitHub CI gate (the org does not gate on Actions).
- Assert tolerantly (substring, trimmed, generous timeouts); never on delta counts or timing.
- Keep the test to the single structured path; do not assert the coarse `message` path. As of 2026-06-22 there is no old-app-version support: the operator controls both ends and ships the app and daemon together, so every phone gets the structured stream and the coarse path is dead code slated for removal. See the 2026-06-22 amendment in pyrycode ADR 025.
