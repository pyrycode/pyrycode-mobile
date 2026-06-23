#!/usr/bin/env bash
#
# e2e-emulator.sh — host orchestration for the mobile interactive-stream e2e prototype.
#
# This script drives two rungs of the e2e ladder (see docs/e2e-interactive-stream.md / ADR 025):
#
#   * rung 3 (default): the REAL app on a headless emulator → host pyry daemon → real claude →
#     assert "ping" renders. Semi-deterministic; burns one real claude turn.
#   * rung 4 (DETERMINISTIC=1): the same real app + Noise/relay path, but claude is swapped for the
#     scripted `fakeclaude` backend (pyrycode #642) that replays a fixed JSONL fixture. The daemon
#     spawns NO real claude and the run consumes ZERO claude turns, so it can run often and assert
#     exactly.
#
# What it does, in order:
#   1. Start a local relay on plain ws:// (no TLS).
#   2. Mint a mobile device pairing token with `pyry pair` and parse the payload.
#   2b. (DETERMINISTIC) Pre-seed the scripted backend: build/locate fakeclaude, pre-create the
#       bootstrap session JSONL, and write one PROMOTED conversation bound to the bootstrap session id.
#   3. Start the pyry daemon (Mobile Protocol v2) pointed at the local relay.
#   4. Run the Gradle Managed-Device instrumented test, injecting the pairing values as
#      instrumentation arguments. The custom runner (E2eInstrumentationRunner) sees `relayUrl` and
#      swaps in E2eTestApplication, which pre-pairs the app and binds the relay-backed repository.
#   4b. (DETERMINISTIC) A background watcher drops the JSONL fixture once the daemon logs the
#       `send_message.ack` fence, so the scripted reply tails the real producer mid-test.
#   5. Tear everything down (trap on EXIT).
#
# Prerequisites (host):
#   * `pyrycode-relay` and `pyry` on PATH (override with RELAY_BIN / PYRY_BIN).
#   * rung 3 only: the operator's claude is authenticated on this host — the daemon spawns real claude.
#     The interactive path is Max-subscription covered, so this does NOT meter tokens.
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
#   DETERMINISTIC=1 PYRYCODE_SRC=~/src/pyrycode bash scripts/e2e-emulator.sh   # rung 4 (scripted)
# Tunables (env):
#   PORT=8888  DEVICE=pixel2Api33Atd  PAIR_NAME=e2e-emulator  PYRY_NAME=e2e-emulator
#   PYRY_BIN=pyry  RELAY_BIN=pyrycode-relay
#   DETERMINISTIC=  PYRYCODE_SRC=  FAKE_CLAUDE_BIN=  FIXTURE_FILE=  INITIAL_UUID=  CONV_UUID=
#   SEED_CHANNEL_NAME=e2e-ping

set -euo pipefail

# ---- config -----------------------------------------------------------------------------------
PORT="${PORT:-8888}"
DEVICE="${DEVICE:-pixel2Api33Atd}"            # matches the managedDevices block in app/build.gradle.kts
PAIR_NAME="${PAIR_NAME:-e2e-emulator}"        # device label shown in `pyry pair list`
PYRY_NAME="${PYRY_NAME:-e2e-emulator}"        # namespaces the daemon socket/identity so it does NOT
                                              # clobber a production pyry daemon running on this host
PYRY_BIN="${PYRY_BIN:-pyry}"
RELAY_BIN="${RELAY_BIN:-pyrycode-relay}"

# DETERMINISTIC mode (rung 4): scripted fakeclaude backend, no real claude, zero claude turns.
DETERMINISTIC="${DETERMINISTIC:-}"
if [ -n "${DETERMINISTIC}" ]; then
  TEST_CLASS="de.pyryco.mobile.e2e.DeterministicInteractiveStreamE2ETest"
else
  TEST_CLASS="de.pyryco.mobile.e2e.InteractiveStreamE2ETest"
fi

# The daemon (on the host, server side) reaches the relay over loopback. v0.14.0-era daemons do NOT
# append the relay path themselves, so spell out /v1/server here.
DAEMON_RELAY_URL="ws://127.0.0.1:${PORT}/v1/server"  # newer daemons append /v1/server automatically
# … while the emulator reaches the host via the 10.0.2.2 alias as a bare origin (no path);
# OkHttpRelayTransport appends /v1/client itself.
PHONE_RELAY_URL="ws://10.0.2.2:${PORT}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLEW="${REPO_ROOT}/gradlew"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/pyry-e2e.XXXXXX")"
RELAY_LOG="${WORK_DIR}/relay.log"
DAEMON_LOG="${WORK_DIR}/daemon.log"
PAIR_OUT="${WORK_DIR}/pair.out"

# Deterministic-mode (rung 4) tunables / paths.
PYRYCODE_SRC="${PYRYCODE_SRC:-}"              # local pyrycode checkout (to build fakeclaude)
FAKE_CLAUDE_BIN="${FAKE_CLAUDE_BIN:-}"        # prebuilt fakeclaude path (overrides PYRYCODE_SRC build)
FIXTURE_FILE="${FIXTURE_FILE:-${REPO_ROOT}/scripts/e2e-fixtures/ping.jsonl}"
INITIAL_UUID="${INITIAL_UUID:-43143143-4314-4314-8314-431431431431}"  # bootstrap session JSONL stem
CONV_UUID="${CONV_UUID:-c0a70431-0431-4031-8031-043104310431}"        # seeded channel id
SEED_CHANNEL_NAME="${SEED_CHANNEL_NAME:-e2e-ping}"  # MUST equal DeterministicInteractiveStreamE2ETest.SEED_CHANNEL_NAME

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
command -v "${RELAY_BIN}" >/dev/null 2>&1 || die "relay binary '${RELAY_BIN}' not found (set RELAY_BIN)"
command -v "${PYRY_BIN}"  >/dev/null 2>&1 || die "pyry binary '${PYRY_BIN}' not found (set PYRY_BIN)"
command -v python3        >/dev/null 2>&1 || die "python3 not found (needed to decode the pairing payload)"
[ -x "${GRADLEW}" ] || die "gradlew not found/executable at ${GRADLEW}"

if [ -n "${DETERMINISTIC}" ]; then
  [ -f "${FIXTURE_FILE}" ] || die "deterministic mode: fixture not found at ${FIXTURE_FILE} (set FIXTURE_FILE)"
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

# ---- 3. daemon (Mobile Protocol v2, pointed at the local relay) -------------------------------
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
else
  PYRY_ALLOW_INSECURE_RELAY=1 PYRY_MOBILE_V2=1 PYRY_RELAY_URL="${DAEMON_RELAY_URL}" "${PYRY_BIN}" -pyry-name="${PYRY_NAME}" \
    >"${DAEMON_LOG}" 2>&1 &
  DAEMON_PID=$!
fi
sleep 3
kill -0 "${DAEMON_PID}" 2>/dev/null || { cat "${DAEMON_LOG}" >&2; die "daemon exited early — see ${DAEMON_LOG}"; }
log "daemon up (the test waits for the relay session to open before sending)."

# ---- 4b. fixture-drop watcher (rung 4 only) ---------------------------------------------------
# The fixture must drop AFTER the turn is acked: the ack stamps the producer cursor; dropping earlier
# lets the producer tail PAST the fixture (cold-start race) → the phone gets zero envelopes. The
# host-observable fence is the `send_message.ack` line in daemon.log. Run as a background job so it can
# fire concurrently with the foreground gradle run (which sends the prompt mid-test). Poll the (tiny)
# log rather than `tail -F | grep` so a single kill of this subshell fully reaps the watcher on EXIT —
# no orphaned `tail` left following a deleted file.
if [ -n "${DETERMINISTIC}" ]; then
  log "arming fixture-drop watcher (waits for send_message.ack, then drops ${FIXTURE_FILE##*/})…"
  (
    while ! grep -q 'send_message.ack' "${DAEMON_LOG}" 2>/dev/null; do
      sleep 0.5
    done
    cp "${FIXTURE_FILE}" "${JSONL_TRIGGER}"
  ) &
  WATCHER_PID=$!
fi

# ---- 4. run the managed-device instrumented test ----------------------------------------------
log "running ${DEVICE}DebugAndroidTest (headless emulator: boot → install → ${TEST_CLASS} → teardown)…"
log "  phone relayUrl = ${PHONE_RELAY_URL}"
"${GRADLEW}" -p "${REPO_ROOT}" "${DEVICE}DebugAndroidTest" \
  -Pandroid.testInstrumentationRunnerArguments.class="${TEST_CLASS}" \
  -Pandroid.testInstrumentationRunnerArguments.relayUrl="${PHONE_RELAY_URL}" \
  -Pandroid.testInstrumentationRunnerArguments.token="${TOKEN}" \
  -Pandroid.testInstrumentationRunnerArguments.serverId="${SERVER_ID}" \
  -Pandroid.testInstrumentationRunnerArguments.serverStaticPublicKey="${SERVER_STATIC_PUBKEY}" \
  --console=plain

log "PASS — the headless emulator connected, sent the prompt, and 'ping' rendered in the thread."
