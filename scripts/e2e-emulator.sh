#!/usr/bin/env bash
#
# e2e-emulator.sh — host orchestration for the mobile interactive-stream e2e prototype.
#
# This script drives two rungs of the e2e ladder (see docs/e2e-interactive-stream.md / ADR 025):
#
#   * rung 3 (default): the REAL app on a headless emulator → host pyry daemon → real claude →
#     assert "ping" renders. Semi-deterministic; burns one real claude turn. A LIVE=1 variant runs a
#     curated trio of rung-3 scenarios (ping + create-workspace-folder + new-session, 3 turns) against
#     the PRODUCTION relay over wss:// (TLS), so a pre-ship gate catches the live-environment failure
#     class a local relay cannot. See "LIVE mode" below.
#   * rung 4 (DETERMINISTIC=1): the same real app + Noise/relay path, but claude is swapped for the
#     scripted `fakeclaude` backend (pyrycode #642) that replays a fixed JSONL fixture. The daemon
#     spawns NO real claude and the run consumes ZERO claude turns, so it can run often and assert
#     exactly.
#
# What it does, in order:
#   1. Start a local relay on plain ws:// (no TLS). (LIVE: skipped — the daemon dials the production relay.)
#   2. Mint a mobile device pairing token with `pyry pair` and parse the payload.
#   2b. (DETERMINISTIC) Pre-seed the scripted backend: build/locate fakeclaude, pre-create the
#       bootstrap session JSONL, and write one PROMOTED conversation bound to the bootstrap session id.
#   3. Start the pyry daemon (Mobile Protocol v2) pointed at the local relay (LIVE: the production
#      relay over wss://, with NO insecure-relay flag).
#   4. Run the Gradle Managed-Device instrumented test, injecting the pairing values as
#      instrumentation arguments. The custom runner (E2eInstrumentationRunner) sees `relayUrl` and
#      swaps in E2eTestApplication, which pre-pairs the app and binds the relay-backed repository.
#   4b. (DETERMINISTIC) A background watcher drops the JSONL fixture once the daemon logs the
#       `send_message.enqueued` cursor-stamp fence, so the scripted reply tails the real producer
#       mid-test. The `spinner`, `tool`, and `reconnect` scenarios drop twice — a 2nd, turn-ending
#       fixture on the 2nd enqueue — to hold the turn open long enough to observe the transient state
#       (thinking spinner / running tool row) or to span a mid-turn link drop (reconnect). The
#       `replay-order` scenario also drops twice but fences drop B on the relay logging the phone-leg
#       disconnect (a severed phone cannot send a 2nd enqueue), so the ordered sequence accrues in the
#       daemon's in-ring buffer entirely while the phone is offline, then replays in order on reconnect.
#   5. Tear everything down (trap on EXIT).
#
# Prerequisites (host):
#   * `pyrycode-relay` and `pyry` on PATH (override with RELAY_BIN / PYRY_BIN).
#   * rung 3 only: the operator's claude is authenticated on this host — the daemon spawns real claude.
#     The interactive path is Max-subscription covered, so this does NOT meter tokens.
#   * LIVE mode only: the operator's claude authenticated (as rung 3); NO relay binary needed (the daemon
#     dials the production relay); the emulator needs outbound internet + DNS + a system-trusted TLS cert
#     for the relay host. Mutually exclusive with DETERMINISTIC.
#   * rung 4 only: either FAKE_CLAUDE_BIN (a prebuilt fakeclaude) or PYRYCODE_SRC (a local pyrycode
#     checkout) + `go` to build it. No claude auth needed; no claude turns spent.
#   * Android SDK with the `aosp-atd` API 33 system image. AGP auto-provisions it on first run, which
#     needs cmdline-tools installed and the image licence accepted (`sdkmanager --licenses`).
#   * python3 (used only to decode the base64url pairing payload).
#
# rung 3 is semi-deterministic by nature (real claude); rung 4 is fully deterministic and re-running it
# back-to-back yields the same pass.
#
# Usage:
#   bash scripts/e2e-emulator.sh                 # rung 3 (real claude)
#   LIVE=1 bash scripts/e2e-emulator.sh          # rung 3 over the LIVE production relay (wss/TLS): ping + create-workspace-folder + new-session
#   DETERMINISTIC=1 PYRYCODE_SRC=~/src/pyrycode bash scripts/e2e-emulator.sh                 # rung 4, ping
#   DETERMINISTIC=1 SCENARIO=stream  PYRYCODE_SRC=~/src/pyrycode bash scripts/e2e-emulator.sh   # rung 4, stream
#   DETERMINISTIC=1 SCENARIO=spinner PYRYCODE_SRC=~/src/pyrycode bash scripts/e2e-emulator.sh   # rung 4, spinner
#   DETERMINISTIC=1 SCENARIO=tool        PYRYCODE_SRC=~/src/pyrycode bash scripts/e2e-emulator.sh   # rung 4, tool running→done
#   DETERMINISTIC=1 SCENARIO=tool-failed PYRYCODE_SRC=~/src/pyrycode bash scripts/e2e-emulator.sh   # rung 4, tool failed
#   DETERMINISTIC=1 SCENARIO=reconnect   PYRYCODE_SRC=~/src/pyrycode bash scripts/e2e-emulator.sh   # rung 4, reconnect continuity
#   DETERMINISTIC=1 SCENARIO=replay-order PYRYCODE_SRC=~/src/pyrycode bash scripts/e2e-emulator.sh  # rung 4, post-reconnect replay ordering
# Tunables (env):
#   PORT=8888  DEVICE=pixel2Api33Atd  PAIR_NAME=e2e-emulator  PYRY_NAME=e2e-emulator
#   PYRY_BIN=pyry  RELAY_BIN=pyrycode-relay
#   LIVE=  LIVE_RELAY_HOST=pyrycode-relay.pyryco.de   (LIVE=1 → PAIR_NAME/PYRY_NAME default to e2e-live)
#   DETERMINISTIC=  SCENARIO=ping  PYRYCODE_SRC=  FAKE_CLAUDE_BIN=  FIXTURE_FILE=  FIXTURE_FILE_2=
#   INITIAL_UUID=  CONV_UUID=  SEED_CHANNEL_NAME=e2e-seed
#   DISCONNECT_LOG=<relay.log>  DISCONNECT_TOKEN=disconnect  (replay-order only: where/what to watch for
#                                                             the phone-leg drop that fences drop B)

set -euo pipefail

# ---- config -----------------------------------------------------------------------------------
PORT="${PORT:-8888}"
DEVICE="${DEVICE:-pixel2Api33Atd}"            # matches the managedDevices block in app/build.gradle.kts
PYRY_BIN="${PYRY_BIN:-pyry}"
RELAY_BIN="${RELAY_BIN:-pyrycode-relay}"      # not needed on the LIVE path (no local relay)
# PAIR_NAME / PYRY_NAME defaults are LIVE-dependent — set in the relay-URL branch below.

# DETERMINISTIC mode (rung 4): scripted fakeclaude backend, no real claude, zero claude turns.
DETERMINISTIC="${DETERMINISTIC:-}"
if [ -n "${DETERMINISTIC}" ]; then
  TEST_CLASS="de.pyryco.mobile.e2e.DeterministicInteractiveStreamE2ETest"
else
  TEST_CLASS="de.pyryco.mobile.e2e.InteractiveStreamE2ETest"
fi

# LIVE mode (rung 3, live relay): the same real-claude interactive path as default rung 3, but the
# daemon dials the PRODUCTION relay over wss:// (TLS) with NO insecure-relay flag. Real vs scripted
# claude → mutually exclusive with DETERMINISTIC (guarded in preflight). See
# docs/e2e-interactive-stream.md § "Live mode (rung 3, live relay)".
LIVE="${LIVE:-}"

# Relay-URL asymmetry + instance-name defaults. The daemon needs /v1/server BAKED INTO the URL it dials
# (a base URL silently 404s into a dial-retry loop); the phone gets the BASE only and OkHttpRelayTransport
# appends /v1/client itself. On LIVE both URLs derive from a single LIVE_RELAY_HOST so an operator override
# cannot break the asymmetry (wss defaults to 443 → no PORT on the LIVE path); isolation there is by
# instance name (e2e-live) under the REAL HOME, because real claude needs the operator's ~/.claude auth.
if [ -n "${LIVE}" ]; then
  LIVE_RELAY_HOST="${LIVE_RELAY_HOST:-pyrycode-relay.pyryco.de}"  # production relay host (CLAUDE.md status)
  DAEMON_RELAY_URL="wss://${LIVE_RELAY_HOST}/v1/server"           # daemon: /v1/server baked in
  PHONE_RELAY_URL="wss://${LIVE_RELAY_HOST}"                      # phone: base only, appends /v1/client over TLS
  PAIR_NAME="${PAIR_NAME:-e2e-live}"     # distinct from default-mode e2e-emulator: obvious in `pyry pair list`
  PYRY_NAME="${PYRY_NAME:-e2e-live}"     # namespaces identity/devices.json under ~/.pyry/e2e-live/ (never prod)
else
  PAIR_NAME="${PAIR_NAME:-e2e-emulator}"        # device label shown in `pyry pair list`
  PYRY_NAME="${PYRY_NAME:-e2e-emulator}"        # namespaces the daemon socket/identity so it does NOT
                                                # clobber a production pyry daemon running on this host
  # The daemon (on the host, server side) reaches the relay over loopback. v0.14.0-era daemons do NOT
  # append the relay path themselves, so spell out /v1/server here.
  DAEMON_RELAY_URL="ws://127.0.0.1:${PORT}/v1/server"  # newer daemons append /v1/server automatically
  # … while the emulator reaches the host via the 10.0.2.2 alias as a bare origin (no path);
  # OkHttpRelayTransport appends /v1/client itself.
  PHONE_RELAY_URL="ws://10.0.2.2:${PORT}"
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLEW="${REPO_ROOT}/gradlew"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/pyry-e2e.XXXXXX")"
RELAY_LOG="${WORK_DIR}/relay.log"
DAEMON_LOG="${WORK_DIR}/daemon.log"
PAIR_OUT="${WORK_DIR}/pair.out"

# Deterministic-mode (rung 4) tunables / paths.
PYRYCODE_SRC="${PYRYCODE_SRC:-}"              # local pyrycode checkout (to build fakeclaude)
FAKE_CLAUDE_BIN="${FAKE_CLAUDE_BIN:-}"        # prebuilt fakeclaude path (overrides PYRYCODE_SRC build)
FIXTURES_DIR="${REPO_ROOT}/scripts/e2e-fixtures"
SCENARIO="${SCENARIO:-ping}"                  # which deterministic scenario: ping | stream | spinner (#454) |
                                              # tool | tool-failed (#455) | reconnect (#476) |
                                              # replay-order (#477). Resolved to a @Test method + fixture(s)
                                              # in the preflight below; bare DETERMINISTIC=1 (SCENARIO unset
                                              # → ping) keeps #431.

# replay-order (#477) only: drop B fences on the relay logging the phone-leg disconnect (a severed phone
# cannot send a 2nd send_message to fence on enqueue #2). The relay terminates the phone's WebSocket, so
# relay.log is the most reliable place to observe the drop; daemon.log is the fallback. The exact log
# token is the chief first-run unknown (relay/daemon source lives in pyrycode, not verifiable here) —
# override DISCONNECT_TOKEN / DISCONNECT_LOG and confirm on the first operator run. See docs.
DISCONNECT_LOG="${DISCONNECT_LOG:-${RELAY_LOG}}"
DISCONNECT_TOKEN="${DISCONNECT_TOKEN:-disconnect}"
INITIAL_UUID="${INITIAL_UUID:-43143143-4314-4314-8314-431431431431}"  # bootstrap session JSONL stem
CONV_UUID="${CONV_UUID:-c0a70431-0431-4031-8031-043104310431}"        # seeded channel id
SEED_CHANNEL_NAME="${SEED_CHANNEL_NAME:-e2e-seed}"  # MUST equal DeterministicInteractiveStreamE2ETest.SEED_CHANNEL_NAME
                                                    # Deliberately NOT containing "ping": the seeded channel name
                                                    # renders verbatim in the thread top bar, and the reply assert is a
                                                    # "ping" substring match — a "ping"-bearing name would false-green it.

RELAY_PID=""
DAEMON_PID=""
WATCHER_PID=""
ISO_HOME=""

log() { printf '\033[1;34m[e2e]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[e2e] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

cleanup() {
  local code=$?
  log "tearing down…"
  [ -n "${WATCHER_PID}" ] && kill "${WATCHER_PID}" 2>/dev/null || true
  [ -n "${DAEMON_PID}" ] && kill "${DAEMON_PID}" 2>/dev/null || true
  [ -n "${RELAY_PID}" ] && kill "${RELAY_PID}" 2>/dev/null || true
  wait 2>/dev/null || true
  if [ "${code}" -ne 0 ]; then
    log "logs kept at ${WORK_DIR} (relay.log, daemon.log, pair.out)"
    [ -n "${ISO_HOME}" ] && log "isolated HOME kept at ${ISO_HOME}"
  else
    rm -rf "${WORK_DIR}"
    [ -n "${ISO_HOME}" ] && rm -rf "${ISO_HOME}"
  fi
}
trap cleanup EXIT INT TERM

# ---- preflight --------------------------------------------------------------------------------
[ -n "${LIVE}" ] && [ -n "${DETERMINISTIC}" ] && die "LIVE=1 and DETERMINISTIC=1 are mutually exclusive (real vs scripted claude)"
# LIVE spawns no local relay (it dials the production relay), so the relay binary is not required there.
[ -n "${LIVE}" ] || command -v "${RELAY_BIN}" >/dev/null 2>&1 || die "relay binary '${RELAY_BIN}' not found (set RELAY_BIN)"
command -v "${PYRY_BIN}"  >/dev/null 2>&1 || die "pyry binary '${PYRY_BIN}' not found (set PYRY_BIN)"
command -v python3        >/dev/null 2>&1 || die "python3 not found (needed to decode the pairing payload)"
[ -x "${GRADLEW}" ] || die "gradlew not found/executable at ${GRADLEW}"

if [ -n "${DETERMINISTIC}" ]; then
  # Resolve SCENARIO → the single @Test method this invocation runs + its fixture(s) (#454). One
  # scenario per run, mirroring #431's one-fixture→one-turn→one-scenario model. The held-open two-drop
  # scenarios (`spinner`/`tool`/`reconnect`) set a 2nd (turn-ending) drop; FIXTURE_FILE / FIXTURE_FILE_2
  # stay env-overridable for first-run tuning.
  FIXTURE_FILE_2="${FIXTURE_FILE_2:-}"
  # How the watcher fences drop B (the 2nd drop): `enqueue` = the 2nd `send_message.enqueued` (the held-open
  # two-drop scenarios); `disconnect` = the relay logging the phone-leg drop (replay-order #477, whose phone
  # is offline when drop B must fire). Only the replay-order arm overrides it.
  DROP_B_FENCE="enqueue"
  case "${SCENARIO}" in
    ping)
      TEST_METHOD="interactiveTurn_seededChannel_streamsScriptedPingReplyIntoThread"
      FIXTURE_FILE="${FIXTURE_FILE:-${FIXTURES_DIR}/ping.jsonl}"
      ;;
    stream)
      TEST_METHOD="interactiveTurn_seededChannel_streamsMultiDeltaReplyIntoThread"
      FIXTURE_FILE="${FIXTURE_FILE:-${FIXTURES_DIR}/stream.jsonl}"
      ;;
    spinner)
      TEST_METHOD="interactiveTurn_seededChannel_showsThinkingSpinnerDuringTurn"
      FIXTURE_FILE="${FIXTURE_FILE:-${FIXTURES_DIR}/spinner-open.jsonl}"      # drop A: thinking, held open
      FIXTURE_FILE_2="${FIXTURE_FILE_2:-${FIXTURES_DIR}/spinner-end.jsonl}"  # drop B: ends the turn
      ;;
    tool)
      TEST_METHOD="interactiveTurn_seededChannel_toolStepRunsThenCompletes"
      FIXTURE_FILE="${FIXTURE_FILE:-${FIXTURES_DIR}/tool-open.jsonl}"      # drop A: tool_use, held open
      FIXTURE_FILE_2="${FIXTURE_FILE_2:-${FIXTURES_DIR}/tool-done.jsonl}"  # drop B: tool_result(done) + turn_end
      ;;
    tool-failed)
      TEST_METHOD="interactiveTurn_seededChannel_failedToolStepRendersFailed"
      FIXTURE_FILE="${FIXTURE_FILE:-${FIXTURES_DIR}/tool-failed.jsonl}"    # single terminal drop
      ;;
    reconnect)
      TEST_METHOD="interactiveTurn_seededChannel_replySurvivesMidTurnReconnect"
      FIXTURE_FILE="${FIXTURE_FILE:-${FIXTURES_DIR}/reconnect-open.jsonl}"      # drop A: thinking, held open across the drop
      FIXTURE_FILE_2="${FIXTURE_FILE_2:-${FIXTURES_DIR}/reconnect-done.jsonl}"  # drop B: complete reply + turn_end, post-reconnect
      ;;
    replay-order)
      TEST_METHOD="interactiveTurn_seededChannel_missedEventsReplayInOrderAfterReconnect"
      FIXTURE_FILE="${FIXTURE_FILE:-${FIXTURES_DIR}/replay-order-open.jsonl}"  # drop A: thinking, held open across the outage
      FIXTURE_FILE_2="${FIXTURE_FILE_2:-${FIXTURES_DIR}/replay-order.jsonl}"   # drop B: ordered sequence, produced while offline
      DROP_B_FENCE=disconnect                                                  # drop B fences on the phone-leg disconnect, not enqueue #2
      ;;
    *)
      die "unknown SCENARIO='${SCENARIO}' (expected: ping | stream | spinner | tool | tool-failed | reconnect | replay-order)"
      ;;
  esac
  log "deterministic scenario: ${SCENARIO} → ${TEST_METHOD}"
  [ -f "${FIXTURE_FILE}" ] || die "deterministic mode: fixture not found at ${FIXTURE_FILE} (set FIXTURE_FILE)"
  [ -z "${FIXTURE_FILE_2}" ] || [ -f "${FIXTURE_FILE_2}" ] \
    || die "deterministic mode: 2nd fixture not found at ${FIXTURE_FILE_2} (set FIXTURE_FILE_2)"
  if [ -n "${FAKE_CLAUDE_BIN}" ]; then
    [ -x "${FAKE_CLAUDE_BIN}" ] || die "FAKE_CLAUDE_BIN='${FAKE_CLAUDE_BIN}' is not an executable"
  elif [ -n "${PYRYCODE_SRC}" ]; then
    command -v go >/dev/null 2>&1 || die "deterministic mode: 'go' not found (needed to build fakeclaude from PYRYCODE_SRC)"
    [ -d "${PYRYCODE_SRC}/internal/e2e/internal/fakeclaude" ] \
      || die "PYRYCODE_SRC='${PYRYCODE_SRC}' has no internal/e2e/internal/fakeclaude"
  else
    die "deterministic mode needs FAKE_CLAUDE_BIN (prebuilt) or PYRYCODE_SRC (a pyrycode checkout to build fakeclaude)"
  fi
fi

# ---- deterministic setup (rung 4 only) --------------------------------------------------------
# Compute the isolated HOME and sessions dir, and build/locate fakeclaude, before pairing. The
# conversations.json seed lands later (after pairing creates .pyry/<name>/, before the daemon boots).
if [ -n "${DETERMINISTIC}" ]; then
  log "deterministic mode: scripted fakeclaude backend (no real claude, zero claude turns)."
  # Isolated, SHORT HOME under /tmp — NOT $TMPDIR. On macOS $TMPDIR is long and the daemon's unix
  # control socket path can exceed the ~104-char sun_path limit. os.UserHomeDir() reads $HOME, so this
  # redirects BOTH .pyry/<name>/ and .claude/projects/ for `pyry pair` and the daemon alike.
  ISO_HOME="$(mktemp -d /tmp/pyry-e2e-det.XXXXXX)"
  ROTATE_TRIGGER="${WORK_DIR}/rotate.never"             # required by fakeclaude (mustEnv); never created
  JSONL_TRIGGER="${WORK_DIR}/structured.jsonl.trigger"  # the fixture-drop path
  # The daemon tails <HOME>/.claude/projects/<encode(workdir)> with workdir == HOME (we pass
  # -pyry-workdir=<HOME>). encode replaces BOTH '/' and '.' with '-' (pyrycode reconcile.go
  # encodeWorkdir); replicate it byte-for-byte so our pre-created JSONL lands where the daemon looks.
  ENC="${ISO_HOME//\//-}"; ENC="${ENC//./-}"
  SESSIONS_DIR="${ISO_HOME}/.claude/projects/${ENC}"
  if [ -n "${FAKE_CLAUDE_BIN}" ]; then
    FAKE_BIN="${FAKE_CLAUDE_BIN}"
    log "  fakeclaude = ${FAKE_BIN} (prebuilt)"
  else
    FAKE_BIN="${WORK_DIR}/fakeclaude"
    log "  building fakeclaude from ${PYRYCODE_SRC}…"
    ( cd "${PYRYCODE_SRC}" && go build -o "${FAKE_BIN}" ./internal/e2e/internal/fakeclaude ) \
      || die "go build fakeclaude failed (see stderr above)"
  fi
  log "  isolated HOME = ${ISO_HOME}"
  log "  sessions dir  = ${SESSIONS_DIR}"
fi

# ---- 1. relay ---------------------------------------------------------------------------------
# LIVE dials the production relay directly (RELAY_PID stays empty → cleanup's kill guard no-ops); a down
# or stale relay surfaces later as the phone's connect timeout — the failure class this mode exists to catch.
if [ -z "${LIVE}" ]; then
  log "starting relay on :${PORT} (plain ws, no TLS)…"
  "${RELAY_BIN}" --insecure-listen=":${PORT}" --metrics-listen= >"${RELAY_LOG}" 2>&1 &
  RELAY_PID=$!

  log "waiting for relay /healthz…"
  for _ in $(seq 1 30); do
    if curl -fsS "http://127.0.0.1:${PORT}/healthz" >/dev/null 2>&1; then break; fi
    kill -0 "${RELAY_PID}" 2>/dev/null || die "relay exited early — see ${RELAY_LOG}"
    sleep 0.5
  done
  curl -fsS "http://127.0.0.1:${PORT}/healthz" >/dev/null 2>&1 || die "relay never became healthy — see ${RELAY_LOG}"
  log "relay healthy."
else
  log "LIVE mode: skipping local relay — dialing the production relay at wss://${LIVE_RELAY_HOST} (TLS)."
fi

# ---- 2. pair (mint a device token before the daemon starts so it loads on boot) ---------------
# `pyry pair` prints a QR plus one base64url-encoded JSON line: {server, relay, token,
# server_static_pubkey}. We parse that line and ignore its `relay` (the daemon's loopback URL); the
# phone must dial the 10.0.2.2 alias instead, so we override relayUrl below. In deterministic mode we
# pair under the isolated HOME so the device identity and conversations.json live in the scratch
# profile (and the daemon, also under that HOME, shares the same identity).
log "minting device pairing token (name='${PAIR_NAME}', pyry-name='${PYRY_NAME}')…"
if [ -n "${DETERMINISTIC}" ]; then
  env "HOME=${ISO_HOME}" PYRY_RELAY_URL="${DAEMON_RELAY_URL}" "${PYRY_BIN}" pair -pyry-name="${PYRY_NAME}" --name="${PAIR_NAME}" \
    >"${PAIR_OUT}" 2>&1 || { cat "${PAIR_OUT}" >&2; die "pyry pair failed"; }
else
  PYRY_RELAY_URL="${DAEMON_RELAY_URL}" "${PYRY_BIN}" pair -pyry-name="${PYRY_NAME}" --name="${PAIR_NAME}" \
    >"${PAIR_OUT}" 2>&1 || { cat "${PAIR_OUT}" >&2; die "pyry pair failed"; }
fi

# Parse the first line that base64url-decodes to a JSON object with the expected keys.
PARSED="$(python3 - "${PAIR_OUT}" <<'PY'
import sys, json, base64, shlex
def b64url(s):
    s = s.strip()
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))
payload = None
with open(sys.argv[1]) as f:
    for line in f:
        line = line.strip()
        if not line:
            continue
        try:
            obj = json.loads(b64url(line))
        except Exception:
            continue
        if isinstance(obj, dict) and {"server", "token", "server_static_pubkey"} <= obj.keys():
            payload = obj
            break
if payload is None:
    sys.stderr.write("could not find the base64url pairing payload line in `pyry pair` output\n")
    sys.exit(1)
print("SERVER_ID=" + shlex.quote(payload["server"]))
print("TOKEN=" + shlex.quote(payload["token"]))
print("SERVER_STATIC_PUBKEY=" + shlex.quote(payload["server_static_pubkey"]))
PY
)" || { cat "${PAIR_OUT}" >&2; die "failed to parse pairing payload"; }
eval "${PARSED}"
[ -n "${SERVER_ID:-}" ] && [ -n "${TOKEN:-}" ] && [ -n "${SERVER_STATIC_PUBKEY:-}" ] || die "empty pairing fields"
log "paired: serverId=${SERVER_ID}"

# ---- 2b. pre-seed the scripted backend (rung 4 only) ------------------------------------------
# Mirror pyrycode's #642 capstone (seedBoundConversation + pre-created <initialUUID>.jsonl), changed
# to a PROMOTED channel so it surfaces tappable on the launch channel list. One conversation, one
# session, one fixture file → the by-id producer tails exactly the file fakeclaude writes.
if [ -n "${DETERMINISTIC}" ]; then
  log "pre-seeding scripted backend (sessions dir, bootstrap JSONL, promoted conversation)…"
  mkdir -p "${SESSIONS_DIR}"
  # Pre-create <initialUUID>.jsonl BEFORE the daemon starts so the producer's first resolve succeeds
  # immediately at a tiny offset — avoids the cold-start race where a later resolve lands PAST the
  # fixture and the phone gets zero envelopes. "{}\n" maps to no structured event.
  printf '{}\n' >"${SESSIONS_DIR}/${INITIAL_UUID}.jsonl"
  # One promoted row bound to the bootstrap session id. current_session_id == INITIAL_UUID is
  # load-bearing: sessionRouter.Route rejects an empty current_session_id (#678), and the daemon binds
  # the bootstrap session to the most-recent (here: only) <uuid>.jsonl — INITIAL_UUID — so the by-id
  # tail matches fakeclaude's file. is_promoted:true lists it as a Channel; name is the test's tap target.
  CONV_DIR="${ISO_HOME}/.pyry/${PYRY_NAME}"
  [ -d "${CONV_DIR}" ] || die "expected ${CONV_DIR} (created by 'pyry pair') — did pairing run under HOME=${ISO_HOME}?"
  cat >"${CONV_DIR}/conversations.json" <<EOF
{"conversations":[{"id":"${CONV_UUID}","name":"${SEED_CHANNEL_NAME}","cwd":"${ISO_HOME}","current_session_id":"${INITIAL_UUID}","is_promoted":true,"last_used_at":"2026-01-01T00:00:00Z"}]}
EOF
fi

# ---- 3. daemon (Mobile Protocol v2, pointed at the relay) -------------------------------------
log "starting pyry daemon (PYRY_MOBILE_V2=1) → ${DAEMON_RELAY_URL}…"
if [ -n "${DETERMINISTIC}" ]; then
  # Scripted backend: -pyry-claude=<fakeclaude>, -pyry-workdir=<HOME>, isolated HOME, and the
  # PYRY_FAKE_CLAUDE_* env (inherited by the spawned child). TUI=1 emits the idle/thinking glyphs so
  # the daemon's WaitReady/commit path confirms the turn fast and the send_message ack is prompt.
  env "HOME=${ISO_HOME}" \
    PYRY_ALLOW_INSECURE_RELAY=1 PYRY_MOBILE_V2=1 PYRY_RELAY_URL="${DAEMON_RELAY_URL}" \
    PYRY_FAKE_CLAUDE_SESSIONS_DIR="${SESSIONS_DIR}" \
    PYRY_FAKE_CLAUDE_INITIAL_UUID="${INITIAL_UUID}" \
    PYRY_FAKE_CLAUDE_TRIGGER="${ROTATE_TRIGGER}" \
    PYRY_FAKE_CLAUDE_JSONL_TRIGGER="${JSONL_TRIGGER}" \
    PYRY_FAKE_CLAUDE_TUI=1 \
    "${PYRY_BIN}" -pyry-name="${PYRY_NAME}" -pyry-claude="${FAKE_BIN}" -pyry-workdir="${ISO_HOME}" \
    >"${DAEMON_LOG}" 2>&1 &
  DAEMON_PID=$!
elif [ -n "${LIVE}" ]; then
  # LIVE (rung 3): dial the PRODUCTION relay over wss:// (TLS). PYRY_ALLOW_INSECURE_RELAY is NEVER set on
  # this path — TLS-only transport is enforced by omitting the flag here, not by a runtime toggle. Runs
  # under the real HOME (real claude needs ~/.claude auth); isolation is by -pyry-name (~/.pyry/e2e-live/).
  PYRY_MOBILE_V2=1 PYRY_RELAY_URL="${DAEMON_RELAY_URL}" "${PYRY_BIN}" -pyry-name="${PYRY_NAME}" \
    >"${DAEMON_LOG}" 2>&1 &
  DAEMON_PID=$!
else
  PYRY_ALLOW_INSECURE_RELAY=1 PYRY_MOBILE_V2=1 PYRY_RELAY_URL="${DAEMON_RELAY_URL}" "${PYRY_BIN}" -pyry-name="${PYRY_NAME}" \
    >"${DAEMON_LOG}" 2>&1 &
  DAEMON_PID=$!
fi
sleep 3
kill -0 "${DAEMON_PID}" 2>/dev/null || { cat "${DAEMON_LOG}" >&2; die "daemon exited early — see ${DAEMON_LOG}"; }
log "daemon up (the test waits for the relay session to open before sending)."

# ---- 4b. fixture-drop watcher (rung 4 only) ---------------------------------------------------
# The fixture must drop AFTER the producer cursor is stamped: `router.Route` stamps it, then logs
# `send_message.enqueued` (pyrycode send_message.go); dropping earlier lets the producer tail PAST the
# fixture (cold-start race) → the phone gets zero envelopes. So the host-observable fence is the
# `send_message.enqueued` line in daemon.log (the post-#704/#721 token — confirm on first operator run
# if the daemon is older). Run as a background job so it can fire concurrently with the foreground
# gradle run (which sends the prompt mid-test). Poll the (tiny) log rather than `tail -F | grep` so a
# single kill of this subshell fully reaps the watcher on EXIT — no orphaned `tail` following a deleted
# file.
if [ -n "${DETERMINISTIC}" ]; then
  if [ -n "${FIXTURE_FILE_2}" ] && [ "${DROP_B_FENCE}" = "disconnect" ]; then
    # Straddle-the-outage scenario (replay-order #477): two causally-fenced drops, but the sever/restore
    # straddles drop B. Drop A on the 1st enqueue opens + HOLDS the turn (no end_turn); the test then severs
    # the phone link. Drop B must fire DURING the offline window — but a severed phone cannot send a 2nd
    # send_message, so it cannot fence on enqueue #2 (the reconnect path). Instead fence drop B on the relay
    # logging the phone-leg disconnect: the ordered sequence then accrues in the daemon's in-ring buffer
    # entirely while the phone is offline, and replays in order when the test restores the link. Baseline
    # the disconnect-token count AFTER drop A, then wait for it to INCREASE — a bare `grep -q` would
    # false-fire on a stale churn line from before the sever. (Robust `|| true` count idiom: `|| echo 0`
    # would append a 2nd "0" and break the integer compare on a zero-count existing file — see PR notes.)
    log "arming disconnect-fenced watcher (${FIXTURE_FILE##*/} on enqueue #1, ${FIXTURE_FILE_2##*/} on phone-leg disconnect)…"
    log "  drop-B disconnect fence: token '${DISCONNECT_TOKEN}' in ${DISCONNECT_LOG##*/} (override DISCONNECT_TOKEN / DISCONNECT_LOG; confirm on first operator run)"
    (
      while [ "$(grep -cF 'send_message.enqueued' "${DAEMON_LOG}" 2>/dev/null || true)" -lt 1 ]; do sleep 0.5; done
      cp "${FIXTURE_FILE}" "${JSONL_TRIGGER}"        # drop A: thinking, held open across the outage
      drop_b_base="$(grep -cF "${DISCONNECT_TOKEN}" "${DISCONNECT_LOG}" 2>/dev/null || true)"; drop_b_base="${drop_b_base:-0}"
      while [ "$(grep -cF "${DISCONNECT_TOKEN}" "${DISCONNECT_LOG}" 2>/dev/null || true)" -le "${drop_b_base}" ]; do sleep 0.5; done
      cp "${FIXTURE_FILE_2}" "${JSONL_TRIGGER}"      # drop B: ordered sequence, produced while the phone is offline
    ) &
    WATCHER_PID=$!
  elif [ -n "${FIXTURE_FILE_2}" ]; then
    # Two-drop scenarios (spinner #454, tool #455, reconnect #476): two causally-fenced drops. Drop A on
    # the 1st enqueue opens a turn and HOLDS it open (no end_turn) so the transient state is observable
    # (thinking spinner / running tool row) or so the turn is still streaming when the test severs the
    # phone link (reconnect); the test, after asserting that state / restoring the link, sends a 2nd
    # message whose enqueue triggers drop B, ending the turn and resolving the state. Count enqueues (not a
    # one-shot grep) to tell the 1st from the 2nd. Drop B waits for the 2nd enqueue — long after fakeclaude
    # consumed drop A's trigger — so it never clobbers an unconsumed A.
    log "arming two-drop watcher (${FIXTURE_FILE##*/} on enqueue #1, ${FIXTURE_FILE_2##*/} on #2)…"
    (
      while [ "$(grep -cF 'send_message.enqueued' "${DAEMON_LOG}" 2>/dev/null || echo 0)" -lt 1 ]; do sleep 0.5; done
      cp "${FIXTURE_FILE}" "${JSONL_TRIGGER}"        # drop A: open + held (thinking / tool_use)
      while [ "$(grep -cF 'send_message.enqueued' "${DAEMON_LOG}" 2>/dev/null || echo 0)" -lt 2 ]; do sleep 0.5; done
      cp "${FIXTURE_FILE_2}" "${JSONL_TRIGGER}"      # drop B: turn-ending (responding / tool_result), state resolves
    ) &
    WATCHER_PID=$!
  else
    log "arming fixture-drop watcher (waits for send_message.enqueued, then drops ${FIXTURE_FILE##*/})…"
    (
      while ! grep -qF 'send_message.enqueued' "${DAEMON_LOG}" 2>/dev/null; do
        sleep 0.5
      done
      cp "${FIXTURE_FILE}" "${JSONL_TRIGGER}"
    ) &
    WATCHER_PID=$!
  fi
fi

# ---- 4. run the managed-device instrumented test ----------------------------------------------
# Deterministic mode runs exactly the scenario's one method (class#method); default rung 3 runs the whole
# class; LIVE curates three real-claude methods (ping + create-workspace-folder + new-session = 3 turns)
# via a comma-separated class list — the full class' #481 tool-use test would spend a 4th, so it stays excluded.
if [ -n "${DETERMINISTIC}" ]; then
  TEST_TARGET="${TEST_CLASS}#${TEST_METHOD}"
elif [ -n "${LIVE}" ]; then
  # LIVE curates its real-claude turns: ping + create-workspace-folder + new-session (3 turns), passed
  # as a comma-separated class#method list. The class' #481 tool-use test stays excluded from LIVE for
  # cost (it runs only in the default whole-class rung-3 run).
  TEST_TARGET="${TEST_CLASS}#interactiveTurn_pingPrompt_streamsPingReplyIntoThread,${TEST_CLASS}#interactiveTurn_createWorkspaceFolder_usableAsLiveSessionWorkspace,${TEST_CLASS}#interactiveTurn_newSession_rendersSessionBoundaryDelimiter"
else
  TEST_TARGET="${TEST_CLASS}"
fi
log "running ${DEVICE}DebugAndroidTest (headless emulator: boot → install → ${TEST_TARGET} → teardown)…"
log "  phone relayUrl = ${PHONE_RELAY_URL}"
"${GRADLEW}" -p "${REPO_ROOT}" "${DEVICE}DebugAndroidTest" \
  -Pandroid.testInstrumentationRunnerArguments.class="${TEST_TARGET}" \
  -Pandroid.testInstrumentationRunnerArguments.relayUrl="${PHONE_RELAY_URL}" \
  -Pandroid.testInstrumentationRunnerArguments.token="${TOKEN}" \
  -Pandroid.testInstrumentationRunnerArguments.serverId="${SERVER_ID}" \
  -Pandroid.testInstrumentationRunnerArguments.serverStaticPublicKey="${SERVER_STATIC_PUBKEY}" \
  --console=plain

if [ -n "${DETERMINISTIC}" ]; then
  log "PASS — scenario '${SCENARIO}' green: the emulator connected, sent the prompt, and the scripted reply rendered."
elif [ -n "${LIVE}" ]; then
  log "PASS — the headless emulator connected over the LIVE relay, sent the prompts, and the ping reply, the created-workspace flow, and the new-session delimiter all rendered in the thread."
else
  log "PASS — the headless emulator connected, sent the prompt, and 'ping' rendered in the thread."
fi
