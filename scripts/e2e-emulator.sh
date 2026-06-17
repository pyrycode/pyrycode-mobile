#!/usr/bin/env bash
#
# e2e-emulator.sh — host orchestration for the mobile interactive-stream e2e prototype.
#
# This is rung 3 of the e2e ladder (see docs/e2e-interactive-stream.md / ADR 025, pyrycode #642):
# the REAL app on a headless emulator → host pyry daemon → real claude → assert "ping" renders.
#
# What it does, in order:
#   1. Start a local relay on plain ws:// (no TLS).
#   2. Mint a mobile device pairing token with `pyry pair` and parse the payload.
#   3. Start the pyry daemon (Mobile Protocol v2) pointed at the local relay.
#   4. Run the Gradle Managed-Device instrumented test, injecting the pairing values as
#      instrumentation arguments. The custom runner (E2eInstrumentationRunner) sees `relayUrl` and
#      swaps in E2eTestApplication, which pre-pairs the app and binds the relay-backed repository.
#   5. Tear everything down (trap on EXIT).
#
# Prerequisites (host):
#   * `pyrycode-relay` and `pyry` on PATH (override with RELAY_BIN / PYRY_BIN).
#   * The operator's claude is authenticated on this host — the daemon spawns real claude for the turn.
#     The interactive path is Max-subscription covered, so this does NOT meter tokens.
#   * Android SDK with the `aosp-atd` API 33 system image. AGP auto-provisions it on first run, which
#     needs cmdline-tools installed and the image licence accepted (`sdkmanager --licenses`).
#   * python3 (used only to decode the base64url pairing payload).
#
# Semi-deterministic by nature (real claude). Re-run a few times to gauge flakiness before deciding
# whether the fully-deterministic backend (rung 4, reusing #642's scripted JSONL harness) is worth it.
#
# Usage:
#   bash scripts/e2e-emulator.sh
# Tunables (env):
#   PORT=8888  DEVICE=pixel2Api33Atd  PAIR_NAME=e2e-emulator  PYRY_NAME=e2e-emulator
#   PYRY_BIN=pyry  RELAY_BIN=pyrycode-relay

set -euo pipefail

# ---- config -----------------------------------------------------------------------------------
PORT="${PORT:-8888}"
DEVICE="${DEVICE:-pixel2Api33Atd}"            # matches the managedDevices block in app/build.gradle.kts
PAIR_NAME="${PAIR_NAME:-e2e-emulator}"        # device label shown in `pyry pair list`
PYRY_NAME="${PYRY_NAME:-e2e-emulator}"        # namespaces the daemon socket/identity so it does NOT
                                              # clobber a production pyry daemon running on this host
PYRY_BIN="${PYRY_BIN:-pyry}"
RELAY_BIN="${RELAY_BIN:-pyrycode-relay}"
TEST_CLASS="de.pyryco.mobile.e2e.InteractiveStreamE2ETest"

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

RELAY_PID=""
DAEMON_PID=""

log() { printf '\033[1;34m[e2e]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[e2e] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

cleanup() {
  local code=$?
  log "tearing down…"
  [ -n "${DAEMON_PID}" ] && kill "${DAEMON_PID}" 2>/dev/null || true
  [ -n "${RELAY_PID}" ] && kill "${RELAY_PID}" 2>/dev/null || true
  wait 2>/dev/null || true
  if [ "${code}" -ne 0 ]; then
    log "logs kept at ${WORK_DIR} (relay.log, daemon.log, pair.out)"
  else
    rm -rf "${WORK_DIR}"
  fi
}
trap cleanup EXIT INT TERM

# ---- preflight --------------------------------------------------------------------------------
command -v "${RELAY_BIN}" >/dev/null 2>&1 || die "relay binary '${RELAY_BIN}' not found (set RELAY_BIN)"
command -v "${PYRY_BIN}"  >/dev/null 2>&1 || die "pyry binary '${PYRY_BIN}' not found (set PYRY_BIN)"
command -v python3        >/dev/null 2>&1 || die "python3 not found (needed to decode the pairing payload)"
[ -x "${GRADLEW}" ] || die "gradlew not found/executable at ${GRADLEW}"

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
# phone must dial the 10.0.2.2 alias instead, so we override relayUrl below.
log "minting device pairing token (name='${PAIR_NAME}', pyry-name='${PYRY_NAME}')…"
PYRY_RELAY_URL="${DAEMON_RELAY_URL}" "${PYRY_BIN}" pair -pyry-name="${PYRY_NAME}" --name="${PAIR_NAME}" \
  >"${PAIR_OUT}" 2>&1 || { cat "${PAIR_OUT}" >&2; die "pyry pair failed"; }

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

# ---- 3. daemon (Mobile Protocol v2, pointed at the local relay) -------------------------------
log "starting pyry daemon (PYRY_MOBILE_V2=1) → ${DAEMON_RELAY_URL}…"
PYRY_ALLOW_INSECURE_RELAY=1 PYRY_MOBILE_V2=1 PYRY_RELAY_URL="${DAEMON_RELAY_URL}" "${PYRY_BIN}" -pyry-name="${PYRY_NAME}" \
  >"${DAEMON_LOG}" 2>&1 &
DAEMON_PID=$!
sleep 3
kill -0 "${DAEMON_PID}" 2>/dev/null || { cat "${DAEMON_LOG}" >&2; die "daemon exited early — see ${DAEMON_LOG}"; }
log "daemon up (the test waits for the relay session to open before sending)."

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
