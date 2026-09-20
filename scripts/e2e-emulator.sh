#!/usr/bin/env bash
#
# e2e-emulator.sh — host orchestration for the mobile interactive-stream e2e prototype.
#
# This script drives two rungs of the e2e ladder (see docs/e2e-interactive-stream.md / ADR 025):
#
#   * rung 3 (default): the REAL app on a headless emulator → host pyry daemon → real claude →
#     assert "ping" renders. Semi-deterministic; burns one real claude turn. A LIVE=1 variant runs a
#     curated octet of rung-3 scenarios (ping + create-workspace-folder + new-session + delete +
#     archive-restore + change-workspace + rename + save-as-channel, still 3 turns — delete,
#     archive-restore, change-workspace, rename, and save-as-channel spend none) against the PRODUCTION relay
#     over wss:// (TLS), so a pre-ship gate
#     catches the live-environment failure class a local relay cannot. See "LIVE mode" below.
#   * rung 4 (DETERMINISTIC=1): the same real app + Noise/relay path, but claude is swapped for the
#     scripted `fakeclaude` backend (pyrycode #642) that replays a fixed JSONL fixture. The daemon
#     spawns NO real claude and the run consumes ZERO claude turns, so it can run often and assert
#     exactly.
#
# Execution order: prepare an isolated scripted profile when needed, start the
# relay and daemon, mint pairing against the running daemon, then run the real
# relay-backed app on a managed device. Scripted replay uses stream-json stdout.
# A first user envelope releases fragment one. A queued second message or phone
# disconnect releases fragment two. The EXIT trap stops the owned processes.
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
#   LIVE=1 bash scripts/e2e-emulator.sh          # rung 3 over the LIVE production relay (wss/TLS): ping + create-workspace-folder + new-session + delete + archive-restore + change-workspace + rename
#   DETERMINISTIC=1 PYRYCODE_SRC=~/src/pyrycode bash scripts/e2e-emulator.sh                 # rung 4, ping
#   DETERMINISTIC=1 SCENARIO=stream  PYRYCODE_SRC=~/src/pyrycode bash scripts/e2e-emulator.sh   # rung 4, stream
#   DETERMINISTIC=1 SCENARIO=spinner PYRYCODE_SRC=~/src/pyrycode bash scripts/e2e-emulator.sh   # rung 4, spinner
#   DETERMINISTIC=1 SCENARIO=tool        PYRYCODE_SRC=~/src/pyrycode bash scripts/e2e-emulator.sh   # rung 4, tool running→done
#   DETERMINISTIC=1 SCENARIO=tool-failed PYRYCODE_SRC=~/src/pyrycode bash scripts/e2e-emulator.sh   # rung 4, tool failed
#   DETERMINISTIC=1 SCENARIO=reconnect   PYRYCODE_SRC=~/src/pyrycode bash scripts/e2e-emulator.sh   # rung 4, reconnect continuity
#   DETERMINISTIC=1 SCENARIO=replay-order PYRYCODE_SRC=~/src/pyrycode bash scripts/e2e-emulator.sh  # rung 4, post-reconnect replay ordering
#   DETERMINISTIC=1 INTERACTIVE_RUNNER=stream-json PYRYCODE_SRC=~/src/pyrycode bash scripts/e2e-emulator.sh  # rung 4, pinned runner
# Tunables (env):
#   PORT=8888  DEVICE=pixel2Api33Atd  PAIR_NAME=e2e-emulator  PYRY_NAME=e2e-emulator
#   PYRY_BIN=pyry  RELAY_BIN=pyrycode-relay
#   LIVE=  LIVE_RELAY_HOST=pyrycode-relay.pyryco.de   (LIVE=1 → PAIR_NAME/PYRY_NAME default to e2e-live)
#   DETERMINISTIC=  SCENARIO=ping  PYRYCODE_SRC=  FAKE_CLAUDE_BIN=  FIXTURE_FILE=  FIXTURE_FILE_2=
#   INTERACTIVE_RUNNER=  (stream-json; DETERMINISTIC only — pins the daemon's interactive runner.
#                         Unset = the daemon's own default, unchanged. A real-HOME run (LIVE / default
#                         rung 3) REFUSES the request rather than edit the operator's ~/.pyry/config.json;
#                         every mode still REPORTS the runner its daemon will use. See #614 and
#                         docs/e2e-interactive-stream.md § "Interactive runner selection".)
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

# Current daemons default to stream-json. Optional pinning writes only the
# isolated scripted profile; real-HOME runs only read the operator's config.
INTERACTIVE_RUNNER="${INTERACTIVE_RUNNER:-}"

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
DISCONNECT_TOKEN="${DISCONNECT_TOKEN:-phone_unregistered}"
INITIAL_UUID="${INITIAL_UUID:-43143143-4314-4314-8314-431431431431}"  # seeded session id
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

# resolve_runner_from_config <config-path> (#614)
#   Echoes exactly one line, "<runner>\t<reason>". NEVER writes. NEVER exits non-zero — on the
#   real-HOME paths this reads a file the harness does not own, and a config we cannot read is
#   something to REPORT, not to abort on (that would make read-only reporting a new failure mode for
#   modes that work today). `set -e` would kill the script on a non-zero command substitution, so the
#   python side catches everything and always exits 0 with one line.
#   runner is one of: stream-json | unrecognised | unknown.
#   SECURITY (spec § Security review): report the ONE field and a fixed reason token — never the file
#   body (the same file holds relay_url and is the natural home for future secrets) and never an
#   interpolated parser exception (its text can carry a fragment of the document). Echoing the raw
#   value only when it matches the accepted set also clamps terminal escapes out of an operator's
#   config: anything else reports the bare token `unrecognised`.
resolve_runner_from_config() {
  python3 - "$1" <<'PY'
import json, sys


def resolve(path):
    try:
        with open(path, "r") as f:
            raw = f.read()
    except FileNotFoundError:
        # Upstream config.Load treats a missing file as DefaultConfig() with NO error, and
        # InteractiveRunner has no DefaultConfig entry — so absent file == the daemon's default.
        return ("stream-json", "daemon default — no config file at " + path)
    except OSError:
        return ("unknown", "could not read " + path)
    try:
        obj = json.loads(raw)
    except Exception:
        return ("unknown", "could not parse " + path + " (not valid JSON)")
    if not isinstance(obj, dict):
        return ("unknown", "could not parse " + path + " (not a JSON object)")
    value = obj.get("interactive_runner", "")
    if not isinstance(value, str):
        # Upstream decodes into a string field, so a non-string here fails config.Load outright —
        # that is a parse failure, NOT the default. Reporting it as "stream-json (daemon default)" would
        # name a runner the daemon will never reach.
        return ("unknown",
                "could not parse " + path
                + " (interactive_runner is not a string — the daemon will refuse to start)")
    if value == "":
        return ("stream-json", "daemon default — interactive_runner unset in " + path)
    if value == "stream-json":
        return (value, "from " + path)
    return ("unrecognised",
            "value in " + path + " is outside the accepted set — the daemon will refuse to start")


try:
    runner, reason = resolve(sys.argv[1])
except BaseException:
    runner, reason = ("unknown", "could not read " + sys.argv[1])
sys.stdout.write(runner + "\t" + reason + "\n")
PY
}

# report_interactive_runner <config-path> (#614)
#   Names, on stdout, the interactive runner the daemon is about to use. Always called BEFORE the
#   daemon spawns, in every mode. It reports by READING BACK the very file the daemon will read —
#   never by echoing what was requested — so a pin that landed at the wrong path reports the daemon
#   default and the operator can see on stdout that the pin did not take.
report_interactive_runner() {
  local config_path="$1" line runner reason
  line="$(resolve_runner_from_config "${config_path}" || true)"
  # Belt for a resolver that somehow produced nothing at all. NOT `|| echo <token>` on the
  # substitution itself: that appends a SECOND line and breaks the tab split (same trap as the
  # `|| echo 0` count idiom noted at the disconnect-fenced watcher below).
  [ -n "${line}" ] || line="$(printf 'unknown\tcould not read %s' "${config_path}")"
  runner="${line%%$'\t'*}"
  reason="${line#*$'\t'}"
  log "interactive runner: ${runner}  (${reason})"
}

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

# INTERACTIVE_RUNNER guards (#614). Both are pure argument checks, so they run FIRST — ahead of the
# host-binary lookups below, before the relay starts, before ISO_HOME exists, before pairing, and
# therefore before anything is spawned or any file is written.
#
# Order between the two is load-bearing: validate the VALUE, then the MODE. The typo check is
# unconditional, so `INTERACTIVE_RUNNER=ptty LIVE=1` must surface the typo — the cheaper problem —
# rather than reporting the mode error, sending the operator to switch modes and re-run, and only
# THEN revealing the typo.
if [ -n "${INTERACTIVE_RUNNER}" ]; then
  # The daemon's own accepted set, with no silent fallback: any other value aborts daemon startup
  # (pyrycode cmd/pyry/main.go selectInteractiveRunner). Catching it here is what keeps a typo from
  # reaching the operator as an opaque "daemon exited early — see daemon.log" three seconds later;
  # the wording mirrors upstream's so the two messages agree.
  case "${INTERACTIVE_RUNNER}" in
    stream-json) ;;
    pty) die "INTERACTIVE_RUNNER=pty was removed upstream; use stream-json" ;;
    *) die "INTERACTIVE_RUNNER=\"${INTERACTIVE_RUNNER}\" not recognized (accepted: \"stream-json\")" ;;
  esac
  # Pinning writes <HOME>/.pyry/config.json. That path is per-USER, so on a real-HOME run it IS the
  # operator's production configuration — refuse rather than edit it. Only DETERMINISTIC runs under
  # an isolated HOME. `scripts/e2e-preship-gate.sh` execs `env LIVE=1 bash …` and so inherits the
  # caller's environment: an operator with INTERACTIVE_RUNNER exported gets this abort and its
  # explanation instead of a pre-ship gate silently measuring the other runner. That is intended —
  # do NOT scrub the variable there.
  if [ -z "${DETERMINISTIC}" ]; then
    die "INTERACTIVE_RUNNER=\"${INTERACTIVE_RUNNER}\" is supported on the DETERMINISTIC path only — this run uses the REAL HOME.
The daemon reads interactive_runner from <HOME>/.pyry/config.json, which is per-user and not per-instance (-pyry-name does
not namespace it), so honouring the request here would edit your production ~/.pyry/config.json. Re-run with DETERMINISTIC=1
to pin it, or unset INTERACTIVE_RUNNER — either way the harness reports which runner the daemon will use.
Nothing was spawned and nothing was written."
  fi
fi

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
# Create the isolated HOME and build/locate fakeclaude before daemon startup.
# Seed the promoted channel before the daemon loads its conversation store.
if [ -n "${DETERMINISTIC}" ]; then
  log "deterministic mode: scripted fakeclaude backend (no real claude, zero claude turns)."
  # Isolated, SHORT HOME under /tmp — NOT $TMPDIR. On macOS $TMPDIR is long and the daemon's unix
  # control socket path can exceed the ~104-char sun_path limit. os.UserHomeDir() reads $HOME, so this
  # redirects BOTH .pyry/<name>/ and .claude/projects/ for `pyry pair` and the daemon alike.
  ISO_HOME="$(mktemp -d /tmp/pyry-e2e-det.XXXXXX)"
  REPLAY_RELEASE="${WORK_DIR}/release-second-fragment"
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

# ---- 2b. pre-seed the scripted backend (rung 4 only) ------------------------------------------
# A promoted channel gives the app a stable entry point. Stream replay runs over
# fakeclaude stdout; no transcript file or tailing cursor participates.
if [ -n "${DETERMINISTIC}" ]; then
  log "pre-seeding scripted channel for stream-json replay…"
  CONV_DIR="${ISO_HOME}/.pyry/${PYRY_NAME}"
  ( umask 077; mkdir -p "${CONV_DIR}" )
  cat >"${CONV_DIR}/conversations.json" <<EOF
{"conversations":[{"id":"${CONV_UUID}","name":"${SEED_CHANNEL_NAME}","cwd":"${ISO_HOME}","current_session_id":"${INITIAL_UUID}","is_promoted":true,"last_used_at":"2026-01-01T00:00:00Z"}]}
EOF
  # Pin the interactive runner (#614) — isolated HOME only, and only when asked for. Leaving
  # INTERACTIVE_RUNNER unset must write NOTHING: today this path has no config file at all, and that
  # byte-for-byte sameness is the point. Do not write {"interactive_runner":""} as a "harmless" default.
  #
  # PATH TRAP — read this before touching the path. The conversations.json seed just above goes to
  # CONV_DIR, which is PER-INSTANCE (<ISO_HOME>/.pyry/<PYRY_NAME>/). The config is PER-USER, one level
  # ABOVE it:
  #     correct  <ISO_HOME>/.pyry/config.json               ← what resolveConfigPath() returns
  #     wrong    <ISO_HOME>/.pyry/<PYRY_NAME>/config.json   ← copying the adjacent line's path
  # The wrong path is silent: config.Load reads a missing file as DefaultConfig() with NO error, so the
  # run goes green on the default runner and the pin does nothing. The pre-spawn report below reads the
  # config back from the daemon's own path, so a misplaced seed shows up as "stream-json (daemon default)".
  #
  # A partial file is legal — config.Load overlays it onto DefaultConfig(), so every other field keeps
  # its default. relay_url specifically is safe to leave defaulted here because this path already passes
  # PYRY_RELAY_URL, which wins over the config (upstream resolveRelayURL: flag → env → config); today's
  # unseeded deterministic run proves it, reaching the local relay with no config file at all.
  #
  # Shape and permissions mirror pyrycode's internal/e2e/harness.go StartStreamInteractiveWithRelay
  # (MkdirAll 0700 + WriteFile 0600, before spawn). The umask subshell gives the FILE 0600 with no
  # create-then-chmod window; like upstream's MkdirAll it does not re-mode an existing .pyry, which
  # `pyry pair` has already created 0700 (pyrycode internal/keys/store.go). Either way the whole tree
  # sits inside ISO_HOME, itself a private `mktemp -d`.
  if [ -n "${INTERACTIVE_RUNNER}" ]; then
    (
      umask 077
      mkdir -p "${ISO_HOME}/.pyry"
      printf '{"interactive_runner":"%s"}\n' "${INTERACTIVE_RUNNER}" >"${ISO_HOME}/.pyry/config.json"
    ) || die "failed to seed ${ISO_HOME}/.pyry/config.json"
    log "  pinned interactive_runner=${INTERACTIVE_RUNNER} → ${ISO_HOME}/.pyry/config.json"
  fi
fi

# ---- 3. daemon (Mobile Protocol v2, pointed at the relay) -------------------------------------
# Name the interactive runner this daemon will use BEFORE it spawns (#614) — one call site, all three
# modes, so a gate's result stays a property of the test rather than of whatever ~/.pyry/config.json
# happens to say. DETERMINISTIC reads back the isolated HOME (seeded or not); LIVE and default rung 3
# read the operator's own config STRICTLY READ-ONLY. The resolver never writes and never aborts.
if [ -n "${DETERMINISTIC}" ]; then
  DAEMON_CONFIG_PATH="${ISO_HOME}/.pyry/config.json"
else
  DAEMON_CONFIG_PATH="${HOME:-}/.pyry/config.json"   # real HOME: read, never written
fi
report_interactive_runner "${DAEMON_CONFIG_PATH}"
log "app mode: E2eTestApplication with real relay repository; device: ${DEVICE}"
if command -v go >/dev/null 2>&1; then
  DAEMON_REVISION="$(go version -m "$(command -v "${PYRY_BIN}")" 2>/dev/null | sed -n 's/.*vcs.revision=//p')"
  log "daemon revision: ${DAEMON_REVISION:-unavailable}; binary: ${PYRY_BIN}"
fi
log "starting pyry daemon (PYRY_MOBILE_V2=1) → ${DAEMON_RELAY_URL}…"
if [ -n "${DETERMINISTIC}" ]; then
  # The fake speaks the current runner protocol and replays the first fragment
  # on its first user envelope. A second fragment waits for our release signal.
  REPLAY_ENV=(PYRY_FAKE_CLAUDE_STREAM_JSON=1
    "PYRY_FAKE_CLAUDE_STREAM_REPLAY_FIRST=${FIXTURE_FILE}")
  if [ -n "${FIXTURE_FILE_2}" ]; then
    REPLAY_ENV+=("PYRY_FAKE_CLAUDE_STREAM_REPLAY_SECOND=${FIXTURE_FILE_2}"
      "PYRY_FAKE_CLAUDE_STREAM_REPLAY_RELEASE=${REPLAY_RELEASE}")
  fi
  env "HOME=${ISO_HOME}" \
    PYRY_ALLOW_INSECURE_RELAY=1 PYRY_MOBILE_V2=1 PYRY_RELAY_URL="${DAEMON_RELAY_URL}" \
    "${REPLAY_ENV[@]}" \
    "${PYRY_BIN}" -pyry-name="${PYRY_NAME}" -pyry-claude="${FAKE_BIN}" -pyry-workdir="${ISO_HOME}" \
    >"${DAEMON_LOG}" 2>&1 &
  DAEMON_PID=$!
elif [ -n "${LIVE}" ]; then
  # LIVE (rung 3): dial the PRODUCTION relay over wss:// (TLS). PYRY_ALLOW_INSECURE_RELAY is NEVER set on
  # this path — TLS-only transport is enforced by omitting the flag here, not by a runtime toggle. Runs
  # under the real HOME (real claude needs ~/.claude auth); isolation is by -pyry-name (~/.pyry/e2e-live/).
  PYRY_MOBILE_V2=1 PYRY_RELAY_URL="${DAEMON_RELAY_URL}" "${PYRY_BIN}" -pyry-name="${PYRY_NAME}" -pyry-workdir="${HOME}" \
    >"${DAEMON_LOG}" 2>&1 &
  DAEMON_PID=$!
else
  PYRY_ALLOW_INSECURE_RELAY=1 PYRY_MOBILE_V2=1 PYRY_RELAY_URL="${DAEMON_RELAY_URL}" "${PYRY_BIN}" -pyry-name="${PYRY_NAME}" -pyry-workdir="${HOME}" \
    >"${DAEMON_LOG}" 2>&1 &
  DAEMON_PID=$!
fi
sleep 3
kill -0 "${DAEMON_PID}" 2>/dev/null || die "daemon exited early; see private log ${DAEMON_LOG}"
log "daemon up (the test waits for the relay session to open before sending)."

# ---- pair against the running test daemon ---------------
# `pyry pair` prints a QR plus one base64url-encoded JSON line: {server, relay, token,
# server_static_pubkey}. We parse that line and ignore its `relay` (the daemon's loopback URL); the
# phone must dial the 10.0.2.2 alias instead, so we override relayUrl below. In deterministic mode we
# pair under the isolated HOME so the device identity and conversations.json live in the scratch
# profile (and the daemon, also under that HOME, shares the same identity).
log "minting device pairing token (name='${PAIR_NAME}', pyry-name='${PYRY_NAME}')…"
if [ -n "${DETERMINISTIC}" ]; then
  env "HOME=${ISO_HOME}" PYRY_RELAY_URL="${DAEMON_RELAY_URL}" "${PYRY_BIN}" pair -pyry-name="${PYRY_NAME}" --name="${PAIR_NAME}" \
    >"${PAIR_OUT}" 2>&1 || die "pyry pair failed; see private log ${PAIR_OUT}"
else
  PYRY_RELAY_URL="${DAEMON_RELAY_URL}" "${PYRY_BIN}" pair -pyry-name="${PYRY_NAME}" --name="${PAIR_NAME}" \
    >"${PAIR_OUT}" 2>&1 || die "pyry pair failed; see private log ${PAIR_OUT}"
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
)" || die "failed to parse pairing payload; see private log ${PAIR_OUT}"
eval "${PARSED}"
[ -n "${SERVER_ID:-}" ] && [ -n "${TOKEN:-}" ] && [ -n "${SERVER_STATIC_PUBKEY:-}" ] || die "empty pairing fields"
log "paired: serverId=${SERVER_ID}"

# ---- 4b. release a held stream fragment after an explicit test action ---------
# First-fragment replay belongs to fakeclaude's user-envelope handler. Only the
# second fragment needs a host signal: enqueue #2, or a phone disconnect while
# replay-order deliberately keeps the phone offline.
if [ -n "${DETERMINISTIC}" ] && [ -n "${FIXTURE_FILE_2}" ]; then
  log "arming second-fragment release: ${DROP_B_FENCE}"
  (
    if [ "${DROP_B_FENCE}" = "disconnect" ]; then
      disconnect_base="$(grep -cF "${DISCONNECT_TOKEN}" "${DISCONNECT_LOG}" 2>/dev/null || true)"
      disconnect_base="${disconnect_base:-0}"
      while [ "$(grep -cF "${DISCONNECT_TOKEN}" "${DISCONNECT_LOG}" 2>/dev/null || true)" -le "${disconnect_base}" ]; do sleep 0.2; done
    else
      while [ "$(grep -cF 'send_message.enqueued' "${DAEMON_LOG}" 2>/dev/null || true)" -lt 2 ]; do sleep 0.2; done
    fi
    touch "${REPLAY_RELEASE}"
    log "released second stream fragment"
  ) &
  WATCHER_PID=$!
fi

# ---- 4. run the managed-device instrumented test ----------------------------------------------
# Deterministic mode runs exactly the scenario's one method (class#method); default rung 3 runs the whole
# class; LIVE curates an octet of real-claude methods (ping + create-workspace-folder + new-session +
# delete + archive-restore + change-workspace + rename + save-as-channel = 8 methods, still 3 turns —
# delete/rename/archive/unarchive/change-workspace/promote are daemon round-trips, not claude turns) via a
# comma-separated class list — the full class' #481 tool-use test would spend an extra turn, so it stays
# excluded.
if [ -n "${DETERMINISTIC}" ]; then
  TEST_TARGET="${TEST_CLASS}#${TEST_METHOD}"
elif [ -n "${LIVE}" ]; then
  # LIVE curates its real-claude turns: ping + create-workspace-folder + new-session + delete +
  # archive-restore + change-workspace + rename + save-as-channel (8 methods, still 3 turns — delete,
  # archive-restore, change-workspace, rename, and save-as-channel spend none), passed as a comma-separated
  # class#method list. The class' #481 tool-use test stays excluded from LIVE for cost (it runs only in the
  # default whole-class rung-3 run).
  TEST_TARGET="${TEST_CLASS}#interactiveTurn_pingPrompt_streamsPingReplyIntoThread,${TEST_CLASS}#interactiveTurn_createWorkspaceFolder_usableAsLiveSessionWorkspace,${TEST_CLASS}#interactiveTurn_newSession_rendersSessionBoundaryDelimiter,${TEST_CLASS}#interactiveTurn_deleteConversation_removesFromListAndClosesThread,${TEST_CLASS}#interactiveTurn_archiveRestore_roundTripsListMembership,${TEST_CLASS}#interactiveTurn_changeWorkspace_relabelsChipToNewWorkspace,${TEST_CLASS}#interactiveTurn_renameConversation_relabelsTopBarAndListRow,${TEST_CLASS}#interactiveTurn_saveAsChannel_promotesToChannelTier"
else
  TEST_TARGET="${TEST_CLASS}"
fi
log "running ${DEVICE}DebugAndroidTest (headless emulator: boot → install → ${TEST_TARGET} → teardown)…"
log "  phone relayUrl = ${PHONE_RELAY_URL}"
GRADLE_TEST_ARGS=()
if [ "${PYRY_FORCE_TEST_RUN:-}" = "1" ]; then GRADLE_TEST_ARGS+=(--rerun); fi
"${GRADLEW}" -p "${REPO_ROOT}" "${DEVICE}DebugAndroidTest" \
  "${GRADLE_TEST_ARGS[@]}" \
  -Pandroid.testInstrumentationRunnerArguments.class="${TEST_TARGET}" \
  -Pandroid.testInstrumentationRunnerArguments.relayUrl="${PHONE_RELAY_URL}" \
  -Pandroid.testInstrumentationRunnerArguments.token="${TOKEN}" \
  -Pandroid.testInstrumentationRunnerArguments.serverId="${SERVER_ID}" \
  -Pandroid.testInstrumentationRunnerArguments.serverStaticPublicKey="${SERVER_STATIC_PUBKEY}" \
  --console=plain

if [ -n "${DETERMINISTIC}" ]; then
  log "PASS — scenario '${SCENARIO}' green: the emulator connected, sent the prompt, and the scripted reply rendered."
elif [ -n "${LIVE}" ]; then
  log "PASS — the headless emulator connected over the LIVE relay, sent the prompts, and the ping reply, the created-workspace flow, the new-session delimiter, the delete-conversation flow, the archive/restore round-trip, the change-workspace chip re-label, the rename top-bar/list re-label, and the save-as-channel promote (top-bar re-label + channel tier) all rendered in the thread."
else
  log "PASS — the headless emulator connected, sent the prompt, and 'ping' rendered in the thread."
fi
