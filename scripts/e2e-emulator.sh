#!/usr/bin/env bash
#
# e2e-emulator.sh — host orchestration for the mobile interactive-stream e2e prototype.
#
# This script drives two rungs of the e2e ladder (see docs/e2e-interactive-stream.md / ADR 025):
#
#   * rung 3 (default): the REAL app on a headless emulator → host pyry daemon → real claude →
#     assert "ping" renders. Semi-deterministic; burns one real claude turn. A LIVE=1 variant runs a
#     curated set of rung-3 scenarios (twenty-four methods, twenty-four real claude turns — listed at the LIVE
#     TEST_TARGET below) against the PRODUCTION relay over wss:// (TLS), so a pre-ship gate
#     catches the live-environment failure class a local relay cannot. See "LIVE mode" below.
#   * rung 4 (DETERMINISTIC=1): the same real app + Noise/relay path, but claude is swapped for the
#     scripted `fakeclaude` backend (pyrycode #642) that replays a fixed JSONL fixture. The daemon
#     spawns NO real claude and the run consumes ZERO claude turns, so it can run often and assert
#     exactly.
#
# Execution order: prepare an isolated scripted profile when needed, start the
# relay and daemons, build the app and test APKs, mint pairing against the running
# daemons, then run the real relay-backed app on a managed device. Minting after
# the build keeps build time out of the daemons' 15-minute redemption window (#993).
# Scripted replay uses stream-json stdout.
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
#     needs the image licence accepted. Android Studio can remain closed.
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
#   DETERMINISTIC=1 SCENARIO=tool-progress PYRYCODE_SRC=~/src/pyrycode bash scripts/e2e-emulator.sh # rung 4, running-tool label elapsed → gone
#   DETERMINISTIC=1 SCENARIO=reconnect   PYRYCODE_SRC=~/src/pyrycode bash scripts/e2e-emulator.sh   # rung 4, reconnect continuity
#   DETERMINISTIC=1 SCENARIO=replay-order PYRYCODE_SRC=~/src/pyrycode bash scripts/e2e-emulator.sh  # rung 4, post-reconnect replay ordering
#   DETERMINISTIC=1 INTERACTIVE_RUNNER=stream-json PYRYCODE_SRC=~/src/pyrycode bash scripts/e2e-emulator.sh  # rung 4, pinned runner
# Tunables (env):
#   PORT=<a free port>  DEVICE=pixel2Api33Atd  PAIR_NAME=e2e-emulator  PYRY_NAME=e2e-emulator
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
# A free port by default, so two harness runs on one host never share a relay. With the port
# fixed at 8888 the dispatcher (two tickets at once since 2026-09-22) and a builder's own scenario
# run could collide: the second run's relay died on the taken port, the health check below then
# passed against the FIRST run's relay, and whichever run finished first tore that relay down under
# the other. Set PORT to pin one; the guard before the relay start refuses a port already serving.
PORT="${PORT:-$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')}"
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
                                              # tool | tool-failed (#455) | tool-progress (#950) | reconnect (#476) |
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
DAEMON_B_PID=""
WATCHER_PID=""
ISO_HOME=""

# Two-host scenario (#847), rung 3 / LIVE only: a second isolated test daemon beside the first, each
# hosting one conversation under the SAME run-unique id with a different name. Instance names derive
# from PYRY_NAME / PAIR_NAME (the gate's e2e-auto-… identity), so both live under the real HOME's
# test instances and never under a production one — the guard in the preflight enforces that.
PYRY_NAME_B="${PYRY_NAME}-b"
PAIR_NAME_B="${PAIR_NAME}-b"
DAEMON_B_LOG="${WORK_DIR}/daemon-b.log"
PAIR_B_OUT="${WORK_DIR}/pair-b.out"
SERVER_ID_B=""
PAIR_CODE_B=""

# Second-client peer (#848), rung 3 / LIVE only: a second paired DEVICE on the first test daemon, with its own
# `pyry pair` token, standing in for the desktop. The test builds the peer itself from this token and a
# throwaway key; the phone's own token and key are never shared with it. It alone is paired with
# --allow-remote-permissions, so the #849 scenario can hold a turn open on a permission prompt and then
# allow it; the phone stays unprivileged, as every other scenario expects.
PAIR_NAME_PEER="${PAIR_NAME}-peer"
PAIR_PEER_OUT="${WORK_DIR}/pair-peer.out"
PEER_TOKEN=""

# Dedicated operator-bypass daemon (#687), rung 3 / LIVE only: a third test daemon under its OWN isolated
# HOME, whose config turns on the stdio permission prompt and whose claude children launch with
# `--dangerously-skip-permissions --permission-prompt-tool stdio`. Claude authenticates from
# CLAUDE_CODE_OAUTH_TOKEN (the live gate's route) or ANTHROPIC_API_KEY, inherited from this environment and
# never written anywhere. The phone pairs with it by code, unprivileged; a second peer device pairs with
# --allow-remote-permissions to answer its one prompt. An unmet prerequisite never aborts the run: it
# becomes BYPASS_UNMET, a static code the one method that needs this daemon fails with.
PYRY_NAME_BYPASS="${PYRY_NAME}-bypass"
PAIR_NAME_BYPASS="${PAIR_NAME}-bypass"
PAIR_NAME_BYPASS_PEER="${PAIR_NAME}-bypass-peer"
DAEMON_BYPASS_LOG="${WORK_DIR}/daemon-bypass.log"
PAIR_BYPASS_OUT="${WORK_DIR}/pair-bypass.out"
PAIR_BYPASS_PEER_OUT="${WORK_DIR}/pair-bypass-peer.out"
BYPASS_HOME=""
BYPASS_PID=""
BYPASS_UNMET=""
BYPASS_TOKEN_FILE=""
BYPASS_WITNESS=""
SERVER_ID_BYPASS=""
PAIR_CODE_BYPASS=""
BYPASS_PEER_TOKEN=""
BYPASS_PEER_SERVER_STATIC_PUBKEY=""

# Dedicated answer daemon (#966), rung 3 / LIVE only: a fourth test daemon under its OWN isolated HOME, in
# #687's shape but with no operator bypass, so claude's default mode asks. Its config turns on the stdio
# permission prompt, the one path that offers don't-ask-again. The phone pairs with it by code WITH
# --allow-remote-permissions, and with no other host: the phone's privilege exists only here, so every other
# scenario still sees an unprivileged phone. A peer pairs --allow-remote-permissions too. An unmet
# prerequisite becomes ANSWER_UNMET, a static code the two #966 methods fail with.
PYRY_NAME_ANSWER="${PYRY_NAME}-answer"
PAIR_NAME_ANSWER="${PAIR_NAME}-answer"
PAIR_NAME_ANSWER_PEER="${PAIR_NAME}-answer-peer"
DAEMON_ANSWER_LOG="${WORK_DIR}/daemon-answer.log"
PAIR_ANSWER_OUT="${WORK_DIR}/pair-answer.out"
PAIR_ANSWER_PEER_OUT="${WORK_DIR}/pair-answer-peer.out"
ANSWER_HOME=""
ANSWER_PID=""
ANSWER_UNMET=""
SERVER_ID_ANSWER=""
PAIR_CODE_ANSWER=""
ANSWER_PEER_TOKEN=""
ANSWER_PEER_SERVER_STATIC_PUBKEY=""

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

# two_host_name_ok <pyry-name> (#847)
#   Succeeds only for a test instance name: an `e2e-` prefix and the daemon's own sanitised charset
#   (pyrycode cmd/pyry/main.go sanitizeName), so the name is one literal path element under
#   <HOME>/.pyry/ — no separator, never a production instance. The two-host seed writes under the
#   REAL HOME, and this is the deterministic check that keeps it inside a test instance.
two_host_name_ok() {
  [[ "$1" =~ ^e2e-[A-Za-z0-9_.-]+$ ]]
}

# seed_collision_conversation <instance-dir> <conversation-id> <name> <cwd> (#847)
#   Merges ONE promoted, unbound conversation into <instance-dir>/conversations.json before the daemon
#   loads it (the daemon reads the registry once at startup). Merge, never overwrite: e2e-live and
#   e2e-emulator are reused across manual runs, so their other rows and top-level keys survive. A row
#   with the same id is replaced. No current_session_id — upstream's `omitempty` unbound state, which a
#   rename and a thread open both serve without spawning claude. Written 0600 via a temp file and
#   os.replace in a 0700 directory. An unreadable registry aborts WITHOUT echoing its content.
seed_collision_conversation() {
  python3 - "$@" <<'PY'
import datetime, json, os, sys, tempfile

instance, conv_id, name, cwd = sys.argv[1:5]
os.makedirs(instance, mode=0o700, exist_ok=True)
path = os.path.join(instance, "conversations.json")
try:
    with open(path) as f:
        doc = json.load(f)
except FileNotFoundError:
    doc = {}
except (OSError, ValueError):
    sys.exit("existing conversations.json could not be read as JSON; left untouched")
rows = (doc.get("conversations") or []) if isinstance(doc, dict) else None
if not isinstance(rows, list):
    sys.exit("existing conversations.json has an unexpected shape; left untouched")
rows = [row for row in rows if not (isinstance(row, dict) and row.get("id") == conv_id)]
# Now, so the seeded row sorts among the most recent in its workspace group and is drawn on screen.
now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
rows.append({"id": conv_id, "name": name, "cwd": cwd, "is_promoted": True, "last_used_at": now})
doc["conversations"] = rows
fd, tmp = tempfile.mkstemp(dir=instance, prefix=".conversations.", suffix=".tmp")  # created 0600
try:
    with os.fdopen(fd, "w") as f:
        json.dump(doc, f)
    os.replace(tmp, path)
except BaseException:
    if os.path.exists(tmp):
        os.unlink(tmp)
    raise
PY
}

# phone_pair_code <pair-out> <phone-relay-url> [<suffix>] (#847; <suffix> #687, default B)
#   The second host is paired through the app's own paste-a-code flow, so the phone needs the WHOLE
#   pairing code, not four fields. Finds the payload line the first host's parse accepts, replaces only
#   `relay` with the URL the phone can dial (the daemon's own is /v1/server-baked, and on the local-relay
#   rung a loopback address), and re-encodes it base64url without padding, as `pyry pair` prints it.
#   Prints exactly two shell assignments, SERVER_ID_<suffix> and PAIR_CODE_<suffix>, and nothing else: the
#   code carries the pairing token.
phone_pair_code() {
  python3 - "$@" <<'PY'
import base64, json, shlex, sys

def b64url(s):
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
    sys.exit("could not find the base64url pairing payload line in the second `pyry pair` output")
payload["relay"] = sys.argv[2]
suffix = sys.argv[3] if len(sys.argv) > 3 else "B"
code = base64.urlsafe_b64encode(json.dumps(payload, separators=(",", ":")).encode()).decode().rstrip("=")
print("SERVER_ID_" + suffix + "=" + shlex.quote(payload["server"]))
print("PAIR_CODE_" + suffix + "=" + shlex.quote(code))
PY
}

# pair_token <pair-out> [<prefix>] (#848; <prefix> #687)
#   The second-client peer dials host A as its own device, so it needs only its own token: the server id,
#   relay URL and server static key are host A's, already passed. Finds the payload line the first host's
#   parse accepts and prints exactly one shell assignment, PEER_TOKEN, and nothing else: the value is a
#   pairing token. With <prefix> it prints <prefix>_TOKEN and <prefix>_SERVER_STATIC_PUBKEY instead, for a
#   peer on a host whose key the harness has not otherwise read.
pair_token() {
  python3 - "$@" <<'PY'
import base64, json, shlex, sys

def b64url(s):
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))

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
            if len(sys.argv) > 2:
                print(sys.argv[2] + "_TOKEN=" + shlex.quote(obj["token"]))
                print(sys.argv[2] + "_SERVER_STATIC_PUBKEY=" + shlex.quote(obj["server_static_pubkey"]))
            else:
                print("PEER_TOKEN=" + shlex.quote(obj["token"]))
            sys.exit(0)
sys.exit("could not find the base64url pairing payload line in the peer's `pyry pair` output")
PY
}

# report_stale_pairing_codes (#993)
#   After a failed test task, names each harness daemon whose log shows a handshake rejected because its
#   pairing code outlived the daemon's 15-minute redemption window. Prints nothing when no log matches.
#   Reads the logs through a fixed-string match only and never echoes a log line: they hold pairing
#   material. Plain `if` statements, so the function cannot fail the run under `set -e`.
report_stale_pairing_codes() {
  local stale="" entry name logfile
  for entry in "${PYRY_NAME}:${DAEMON_LOG}" "${PYRY_NAME_B}:${DAEMON_B_LOG}" \
      "${PYRY_NAME_BYPASS}:${DAEMON_BYPASS_LOG}" "${PYRY_NAME_ANSWER}:${DAEMON_ANSWER_LOG}"; do
    name="${entry%%:*}"
    logfile="${entry#*:}"
    if [ -f "${logfile}" ] && grep -qF redemption_window_elapsed "${logfile}"; then
      stale="${stale:+${stale}, }${name} ($(basename "${logfile}"))"
    fi
  done
  if [ -n "${stale}" ]; then
    printf '\033[1;31m[e2e] ERROR:\033[0m pairing_codes_stale: %s rejected a handshake with redemption_window_elapsed; its pairing code outlived the 15-minute redemption window before the test redeemed it\n' "${stale}" >&2
  fi
}

# Copy claude's session transcripts from the operator-bypass HOME (#687) into WORK_DIR/bypass-transcripts,
# keeping their relative paths. `find -type f` skips symlinks.
keep_bypass_transcripts() {
  local src="${BYPASS_HOME}/.claude/projects" dst="${WORK_DIR}/bypass-transcripts" rel
  [ -d "${src}" ] || return 0
  while IFS= read -r -d '' rel; do
    mkdir -p "${dst}/$(dirname "${rel}")" && cp "${src}/${rel}" "${dst}/${rel}"
  done < <(cd "${src}" && find . -type f -name '*.jsonl' -print0)
  [ ! -d "${dst}" ] || log "claude transcripts from the operator-bypass HOME kept at ${dst}"
}

cleanup() {
  local code=$?
  log "tearing down…"
  [ -n "${WATCHER_PID}" ] && kill "${WATCHER_PID}" 2>/dev/null || true
  [ -n "${DAEMON_PID}" ] && kill "${DAEMON_PID}" 2>/dev/null || true
  [ -n "${DAEMON_B_PID:-}" ] && kill "${DAEMON_B_PID}" 2>/dev/null || true
  [ -n "${BYPASS_PID:-}" ] && kill "${BYPASS_PID}" 2>/dev/null || true
  [ -n "${ANSWER_PID:-}" ] && kill "${ANSWER_PID}" 2>/dev/null || true
  [ -n "${RELAY_PID}" ] && kill "${RELAY_PID}" 2>/dev/null || true
  wait 2>/dev/null || true
  # The operator-bypass HOME (#687) goes whatever the exit code: it holds a copy of ~/.claude.json and the
  # witness file. Only a path this script minted is removed. Its daemon log stays in WORK_DIR as the rest do.
  # A failed run first keeps claude's session transcripts (#977): regular *.jsonl files under
  # .claude/projects only, so no credential file and no symlink out of the HOME is ever copied.
  if [[ "${BYPASS_HOME:-}" == /tmp/pyry-e2e-byp.* ]]; then
    if [ "${code}" -ne 0 ]; then
      keep_bypass_transcripts || true
    fi
    [ -z "${BYPASS_TOKEN_FILE}" ] || rm -f "${BYPASS_TOKEN_FILE}"
    rm -rf "${BYPASS_HOME}"
  fi
  # The answer daemon's HOME (#966) goes too, whatever the exit code: it holds a copy of ~/.claude.json.
  if [[ "${ANSWER_HOME:-}" == /tmp/pyry-e2e-ans.* ]]; then
    rm -rf "${ANSWER_HOME}"
  fi
  if [ "${code}" -ne 0 ]; then
    log "logs kept at ${WORK_DIR} (relay.log, daemon.log, pair.out)"
    if [ -n "${ISO_HOME}" ]; then
      log "isolated HOME kept at ${ISO_HOME}"
    fi
  else
    rm -rf "${WORK_DIR}"
    if [ -n "${ISO_HOME}" ]; then
      rm -rf "${ISO_HOME}"
    fi
  fi
  return "${code}"
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

# The two-host seed (#847) writes conversations.json under the REAL HOME on these paths, so both instance
# names must be test instances. Checked before anything is spawned or written.
if [ -z "${DETERMINISTIC}" ]; then
  two_host_name_ok "${PYRY_NAME}" && two_host_name_ok "${PYRY_NAME_B}" \
    || die "PYRY_NAME=\"${PYRY_NAME}\" is not a test instance name (expected e2e-<[A-Za-z0-9_.-]>): the two-host scenario seeds
conversations under <HOME>/.pyry/<PYRY_NAME>/ and <PYRY_NAME>-b/ on this path. Nothing was spawned and nothing was written."
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
    tool-progress)
      TEST_METHOD="interactiveTurn_seededChannel_runningToolLabelShowsElapsedThenClears"
      FIXTURE_FILE="${FIXTURE_FILE:-${FIXTURES_DIR}/tool-progress-open.jsonl}"      # drop A: tool_use + heartbeat, held open
      FIXTURE_FILE_2="${FIXTURE_FILE_2:-${FIXTURES_DIR}/tool-progress-result.jsonl}"  # drop B: tool_result only, turn stays busy
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
      die "unknown SCENARIO='${SCENARIO}' (expected: ping | stream | spinner | tool | tool-failed | tool-progress | reconnect | replay-order)"
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
  # Refuse a port that already answers: that is another run's relay, and passing its health check
  # as our own is exactly the silent sharing the free-port default exists to prevent.
  if curl -fsS "http://127.0.0.1:${PORT}/healthz" >/dev/null 2>&1; then
    die "port ${PORT} already serves a relay (another harness run owns it) — unset PORT to pick a free one"
  fi
  log "starting relay on :${PORT} (plain ws, no TLS)…"
  "${RELAY_BIN}" --insecure-listen=":${PORT}" --metrics-listen= >"${RELAY_LOG}" 2>&1 &
  RELAY_PID=$!

  log "waiting for relay /healthz…"
  for _ in $(seq 1 30); do
    # Liveness first: a relay that lost the bind race is dead before anything answers on the port.
    kill -0 "${RELAY_PID}" 2>/dev/null || die "relay exited early — see ${RELAY_LOG}"
    if curl -fsS "http://127.0.0.1:${PORT}/healthz" >/dev/null 2>&1; then break; fi
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

# ---- 2c. seed the colliding conversation on both test instances (rung 3 / LIVE only, #847) -----
# Daemon-minted ids never collide by chance, so the collision is seeded: ONE run-unique id, a different
# name on each host. Per-INSTANCE path (<HOME>/.pyry/<name>/conversations.json), the same one the
# DETERMINISTIC seed above uses — never the per-user config.json one level up. Must precede both daemons.
if [ -z "${DETERMINISTIC}" ]; then
  COLLISION_ID="$(python3 -c 'import uuid; print(uuid.uuid4())')"
  COLLISION_STAMP="$(date +%s)"
  COLLISION_NAME_A="e2e847-a-${COLLISION_STAMP}"
  COLLISION_NAME_B="e2e847-b-${COLLISION_STAMP}"
  for seed in "${PYRY_NAME}:${COLLISION_NAME_A}" "${PYRY_NAME_B}:${COLLISION_NAME_B}"; do
    seed_collision_conversation "${HOME}/.pyry/${seed%%:*}" "${COLLISION_ID}" "${seed#*:}" "${HOME}" \
      || die "failed to seed ${HOME}/.pyry/${seed%%:*}/conversations.json"
  done
  log "seeded conversation ${COLLISION_ID} as '${COLLISION_NAME_A}' on ${PYRY_NAME} and '${COLLISION_NAME_B}' on ${PYRY_NAME_B}"
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
# The revisions under test (#850), always printed: a value that cannot be read is `unavailable`.
MOBILE_REVISION="$(git -C "${REPO_ROOT}" rev-parse HEAD 2>/dev/null || true)"
log "mobile revision: ${MOBILE_REVISION:-unavailable}"
DAEMON_REVISION=""
if command -v go >/dev/null 2>&1; then
  DAEMON_REVISION="$(go version -m "$(command -v "${PYRY_BIN}")" 2>/dev/null | sed -n 's/.*vcs.revision=//p' || true)"
fi
log "daemon revision: ${DAEMON_REVISION:-unavailable}; binary: ${PYRY_BIN}"
# Claude's own revision (#687), clamped to a plain charset: it is a binary's output, printed to the log.
if [ -n "${DETERMINISTIC}" ]; then
  log "claude revision: scripted fakeclaude (no real claude on this rung)"
else
  CLAUDE_REVISION="$(claude --version 2>/dev/null | head -n 1 | LC_ALL=C tr -cd 'A-Za-z0-9 ._()+-' | cut -c 1-80 || true)"
  log "claude revision: ${CLAUDE_REVISION:-unavailable}"
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
# The second test daemon (#847): same relay, same flags as this mode's first one, its own instance.
if [ -z "${DETERMINISTIC}" ]; then
  log "starting second pyry daemon (${PYRY_NAME_B}) → ${DAEMON_RELAY_URL}…"
  if [ -n "${LIVE}" ]; then
    PYRY_MOBILE_V2=1 PYRY_RELAY_URL="${DAEMON_RELAY_URL}" "${PYRY_BIN}" -pyry-name="${PYRY_NAME_B}" -pyry-workdir="${HOME}" \
      >"${DAEMON_B_LOG}" 2>&1 &
  else
    PYRY_ALLOW_INSECURE_RELAY=1 PYRY_MOBILE_V2=1 PYRY_RELAY_URL="${DAEMON_RELAY_URL}" "${PYRY_BIN}" -pyry-name="${PYRY_NAME_B}" -pyry-workdir="${HOME}" \
      >"${DAEMON_B_LOG}" 2>&1 &
  fi
  DAEMON_B_PID=$!
fi
# Wait until each daemon's control socket answers `pyry status`, the readiness pyrycode's own e2e
# harness polls for (internal/e2e/harness.go waitForReady), instead of the fixed three-second sleep this
# replaced: that cost three seconds on every one of the seven scripted scenarios per verifier pass.
if [ -n "${DETERMINISTIC}" ]; then DAEMON_HOME="${ISO_HOME}"; else DAEMON_HOME="${HOME}"; fi
wait_daemon_ready() {
  local name="$1" pid="$2" logfile="$3" label="$4" deadline=$((SECONDS + 15))
  until env "HOME=${DAEMON_HOME}" "${PYRY_BIN}" status -pyry-name="${name}" >/dev/null 2>&1; do
    kill -0 "${pid}" 2>/dev/null || die "${label} exited early; see private log ${logfile}"
    [ "${SECONDS}" -lt "${deadline}" ] || die "${label} not ready within 15s; see private log ${logfile}"
    sleep 0.1
  done
}
wait_daemon_ready "${PYRY_NAME}" "${DAEMON_PID}" "${DAEMON_LOG}" "daemon"
[ -z "${DAEMON_B_PID}" ] || wait_daemon_ready "${PYRY_NAME_B}" "${DAEMON_B_PID}" "${DAEMON_B_LOG}" "second daemon"
log "daemon up (the test waits for the relay session to open before sending)."

# ---- 4a. the dedicated operator-bypass daemon (rung 3 / LIVE only, #687) ------------------------
# See PYRY_NAME_BYPASS above. Each prerequisite that fails records ONE static code in BYPASS_UNMET and
# stops this section; the run carries on and only the #687 method fails, naming the code. Nothing here
# reads or writes the operator's ~/.pyry: the daemon's HOME is a fresh `mktemp -d` under /tmp (short, for
# the control socket's sun_path), and `cleanup` removes it whatever the outcome.
bypass_unmet() {
  BYPASS_UNMET="$1"
  [ -z "${BYPASS_PID}" ] || { kill "${BYPASS_PID}" 2>/dev/null || true; }
  log "operator-bypass daemon (#687) not available: ${1} — ${2}"
}

start_bypass_daemon() {
  local route="" deadline=0
  # 1. A credential for claude under the isolated HOME. The OAuth route also needs the operator's
  #    ~/.claude.json (copied, read-only on the source), as pyrycode's WithWorktreeAuthenticated does.
  if [ -n "${CLAUDE_CODE_OAUTH_TOKEN:-}" ]; then
    route="oauth"
    [ -r "${HOME}/.claude.json" ] \
      || { bypass_unmet claude_json_unreadable "CLAUDE_CODE_OAUTH_TOKEN is set but ~/.claude.json is not readable"; return 0; }
  elif [ -n "${ANTHROPIC_API_KEY:-}" ]; then
    route="api-key"
  else
    bypass_unmet no_credential "neither CLAUDE_CODE_OAUTH_TOKEN nor ANTHROPIC_API_KEY is set"
    return 0
  fi
  # 2. A daemon that contains pyrycode 475c406a (session_settings reports the confirmed permission mode).
  [[ "${DAEMON_REVISION}" =~ ^[0-9a-f]{7,64}$ ]] \
    || { bypass_unmet revision_unavailable "the daemon binary carries no vcs.revision"; return 0; }
  [ -n "${PYRYCODE_SRC}" ] \
    && git -C "${PYRYCODE_SRC}" cat-file -e "475c406a^{commit}" 2>/dev/null \
    && git -C "${PYRYCODE_SRC}" cat-file -e "${DAEMON_REVISION}^{commit}" 2>/dev/null \
    || { bypass_unmet revision_unverifiable "set PYRYCODE_SRC to a pyrycode checkout holding ${DAEMON_REVISION} and 475c406a"; return 0; }
  git -C "${PYRYCODE_SRC}" merge-base --is-ancestor 475c406a "${DAEMON_REVISION}" \
    || { bypass_unmet revision_lacks_475c406a "daemon revision ${DAEMON_REVISION} does not contain pyrycode 475c406a"; return 0; }
  # 3. Fixture capabilities.
  command -v claude >/dev/null 2>&1 || { bypass_unmet claude_missing "claude is not on PATH"; return 0; }
  two_host_name_ok "${PYRY_NAME_BYPASS}" \
    || { bypass_unmet instance_name "${PYRY_NAME_BYPASS} is not a test instance name"; return 0; }
  BYPASS_HOME="$(mktemp -d /tmp/pyry-e2e-byp.XXXXXX)" \
    || { bypass_unmet isolated_home "could not create the isolated HOME"; return 0; }
  BYPASS_WITNESS="$(python3 -c 'import secrets; print(secrets.token_hex(16))')" \
    && BYPASS_TOKEN_FILE="${BYPASS_HOME}/outside/e2e687-$(python3 -c 'import uuid; print(uuid.uuid4())').txt" \
    && (
      umask 077
      mkdir -p "${BYPASS_HOME}/.pyry" "${BYPASS_HOME}/work" "${BYPASS_HOME}/outside"
      printf '%s\n' '{"interactive_runner":"stream-json","stdio_permission_prompt":true}' >"${BYPASS_HOME}/.pyry/config.json"
      if [ "${route}" = "oauth" ]; then cp "${HOME}/.claude.json" "${BYPASS_HOME}/.claude.json"; fi
      printf '%s\n' "${BYPASS_WITNESS}" >"${BYPASS_TOKEN_FILE}"
    ) \
    || { bypass_unmet isolated_home "could not write the isolated HOME's config, credential file or witness file"; return 0; }
  log "operator-bypass daemon (#687): isolated HOME ${BYPASS_HOME}, credential route ${route}"
  report_interactive_runner "${BYPASS_HOME}/.pyry/config.json"

  # The operator-bypass launch: pyrycode's spawnPermissionDaemon operatorBypass argv. The daemon injects no
  # approval gate of its own for an operator bypass, so the stdio prompt tool is what keeps a downgraded
  # child's prompts reachable.
  local -a bypass_env=("HOME=${BYPASS_HOME}" PYRY_MOBILE_V2=1 "PYRY_RELAY_URL=${DAEMON_RELAY_URL}")
  [ -n "${LIVE}" ] || bypass_env+=(PYRY_ALLOW_INSECURE_RELAY=1)
  env "${bypass_env[@]}" "${PYRY_BIN}" -pyry-name="${PYRY_NAME_BYPASS}" -pyry-workdir="${BYPASS_HOME}/work" -pyry-idle-timeout=0 \
    -- --dangerously-skip-permissions --permission-prompt-tool stdio \
    >"${DAEMON_BYPASS_LOG}" 2>&1 &
  BYPASS_PID=$!
  deadline=$((SECONDS + 15))
  until env "HOME=${BYPASS_HOME}" "${PYRY_BIN}" status -pyry-name="${PYRY_NAME_BYPASS}" >/dev/null 2>&1; do
    if ! kill -0 "${BYPASS_PID}" 2>/dev/null || [ "${SECONDS}" -ge "${deadline}" ]; then
      bypass_unmet daemon_not_ready "not ready within 15s; see private log ${DAEMON_BYPASS_LOG}"
      return 0
    fi
    sleep 0.1
  done
  log "operator-bypass daemon (#687) ready; its codes are minted after the build"
}
if [ -z "${DETERMINISTIC}" ]; then
  start_bypass_daemon
fi

# ---- 4a'. the dedicated answer daemon (rung 3 / LIVE only, #966) --------------------------------
# See PYRY_NAME_ANSWER above. The shape of section 4a, with no revision gate and no operator bypass: each
# unmet prerequisite records ONE static code in ANSWER_UNMET, and only the two #966 methods fail on it.
answer_unmet() {
  ANSWER_UNMET="$1"
  [ -z "${ANSWER_PID}" ] || { kill "${ANSWER_PID}" 2>/dev/null || true; }
  log "answer daemon (#966) not available: ${1} — ${2}"
}

start_answer_daemon() {
  local route="" deadline=0
  if [ -n "${CLAUDE_CODE_OAUTH_TOKEN:-}" ]; then
    route="oauth"
    [ -r "${HOME}/.claude.json" ] \
      || { answer_unmet claude_json_unreadable "CLAUDE_CODE_OAUTH_TOKEN is set but ~/.claude.json is not readable"; return 0; }
  elif [ -n "${ANTHROPIC_API_KEY:-}" ]; then
    route="api-key"
  else
    answer_unmet no_credential "neither CLAUDE_CODE_OAUTH_TOKEN nor ANTHROPIC_API_KEY is set"
    return 0
  fi
  command -v claude >/dev/null 2>&1 || { answer_unmet claude_missing "claude is not on PATH"; return 0; }
  two_host_name_ok "${PYRY_NAME_ANSWER}" \
    || { answer_unmet instance_name "${PYRY_NAME_ANSWER} is not a test instance name"; return 0; }
  ANSWER_HOME="$(mktemp -d /tmp/pyry-e2e-ans.XXXXXX)" \
    || { answer_unmet isolated_home "could not create the isolated HOME"; return 0; }
  (
    umask 077
    mkdir -p "${ANSWER_HOME}/.pyry" "${ANSWER_HOME}/work"
    printf '%s\n' '{"interactive_runner":"stream-json","stdio_permission_prompt":true}' >"${ANSWER_HOME}/.pyry/config.json"
    if [ "${route}" = "oauth" ]; then cp "${HOME}/.claude.json" "${ANSWER_HOME}/.claude.json"; fi
  ) || { answer_unmet isolated_home "could not write the isolated HOME's config or credential file"; return 0; }
  log "answer daemon (#966): isolated HOME ${ANSWER_HOME}, credential route ${route}"
  report_interactive_runner "${ANSWER_HOME}/.pyry/config.json"

  local -a answer_env=("HOME=${ANSWER_HOME}" PYRY_MOBILE_V2=1 "PYRY_RELAY_URL=${DAEMON_RELAY_URL}")
  [ -n "${LIVE}" ] || answer_env+=(PYRY_ALLOW_INSECURE_RELAY=1)
  env "${answer_env[@]}" "${PYRY_BIN}" -pyry-name="${PYRY_NAME_ANSWER}" -pyry-workdir="${ANSWER_HOME}/work" -pyry-idle-timeout=0 \
    >"${DAEMON_ANSWER_LOG}" 2>&1 &
  ANSWER_PID=$!
  deadline=$((SECONDS + 15))
  until env "HOME=${ANSWER_HOME}" "${PYRY_BIN}" status -pyry-name="${PYRY_NAME_ANSWER}" >/dev/null 2>&1; do
    if ! kill -0 "${ANSWER_PID}" 2>/dev/null || [ "${SECONDS}" -ge "${deadline}" ]; then
      answer_unmet daemon_not_ready "not ready within 15s; see private log ${DAEMON_ANSWER_LOG}"
      return 0
    fi
    sleep 0.1
  done

  log "answer daemon (#966) ready; its codes are minted after the build"
}
if [ -z "${DETERMINISTIC}" ]; then
  start_answer_daemon
fi

# ---- build the app and test APKs before any pairing code exists (#993) -------------------------
# A code must be redeemed within the daemon's 15-minute window, and a slow or contended Gradle build once
# took 28 minutes. Building here leaves only the device boot and install inside the window: the test task
# below finds these APKs up to date. The -P build properties must match the test task's (-PuseRelayRepository
# feeds BuildConfig); scripts/test_e2e_emulator_gradle.py checks that they do.
log "building the app and test APKs before minting pairing codes…"
GRADLE_BUILD_ARGS=(-PuseRelayRepository=true)
"${GRADLEW}" -p "${REPO_ROOT}" assembleDebug assembleDebugAndroidTest "${GRADLE_BUILD_ARGS[@]}" \
  --console=plain \
  || die "the Gradle build of the app and test APKs failed (see the output above); no pairing code was minted"

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

# The second host (#847) is paired by the test itself, through the app's paste-a-code flow, so here we
# only mint its code. Never log PAIR_CODE_B: it carries the pairing token.
if [ -z "${DETERMINISTIC}" ]; then
  log "minting second pairing token (name='${PAIR_NAME_B}', pyry-name='${PYRY_NAME_B}')…"
  PYRY_RELAY_URL="${DAEMON_RELAY_URL}" "${PYRY_BIN}" pair -pyry-name="${PYRY_NAME_B}" --name="${PAIR_NAME_B}" \
    >"${PAIR_B_OUT}" 2>&1 || die "second pyry pair failed; see private log ${PAIR_B_OUT}"
  PARSED_B="$(phone_pair_code "${PAIR_B_OUT}" "${PHONE_RELAY_URL}")" \
    || die "failed to parse the second pairing payload; see private log ${PAIR_B_OUT}"
  eval "${PARSED_B}"
  [ -n "${SERVER_ID_B}" ] && [ -n "${PAIR_CODE_B}" ] || die "empty second pairing fields"
  log "second host minted: serverId=${SERVER_ID_B} (the test pairs it by code)"

  # The second-client peer's own device token on host A (#848). Never log PEER_TOKEN.
  log "minting second-client peer token (name='${PAIR_NAME_PEER}', pyry-name='${PYRY_NAME}')…"
  PYRY_RELAY_URL="${DAEMON_RELAY_URL}" "${PYRY_BIN}" pair -pyry-name="${PYRY_NAME}" --name="${PAIR_NAME_PEER}" --allow-remote-permissions \
    >"${PAIR_PEER_OUT}" 2>&1 || die "peer pyry pair failed; see private log ${PAIR_PEER_OUT}"
  PARSED_PEER="$(pair_token "${PAIR_PEER_OUT}")" \
    || die "failed to parse the peer pairing payload; see private log ${PAIR_PEER_OUT}"
  eval "${PARSED_PEER}"
  [ -n "${PEER_TOKEN}" ] || die "empty peer pairing token"
  log "second-client peer minted on serverId=${SERVER_ID}"
fi

# ---- 4c. mint the operator-bypass and answer daemons' codes (rung 3 / LIVE only, #687 / #966) ----
# After the build, like the host codes above (#993). A daemon with an unmet prerequisite mints nothing.
mint_bypass_pairing() {
  local parsed=""
  # The phone pairs unprivileged, by code; the peer pairs --allow-remote-permissions. Never log either value.
  env "HOME=${BYPASS_HOME}" "PYRY_RELAY_URL=${DAEMON_RELAY_URL}" "${PYRY_BIN}" pair -pyry-name="${PYRY_NAME_BYPASS}" --name="${PAIR_NAME_BYPASS}" \
    >"${PAIR_BYPASS_OUT}" 2>&1 \
    && parsed="$(phone_pair_code "${PAIR_BYPASS_OUT}" "${PHONE_RELAY_URL}" BYPASS)" \
    && eval "${parsed}" \
    && [ -n "${SERVER_ID_BYPASS}" ] && [ -n "${PAIR_CODE_BYPASS}" ] \
    || { bypass_unmet pairing "the phone pairing could not be minted; see private log ${PAIR_BYPASS_OUT}"; return 0; }
  env "HOME=${BYPASS_HOME}" "PYRY_RELAY_URL=${DAEMON_RELAY_URL}" "${PYRY_BIN}" pair -pyry-name="${PYRY_NAME_BYPASS}" --name="${PAIR_NAME_BYPASS_PEER}" --allow-remote-permissions \
    >"${PAIR_BYPASS_PEER_OUT}" 2>&1 \
    && parsed="$(pair_token "${PAIR_BYPASS_PEER_OUT}" BYPASS_PEER)" \
    && eval "${parsed}" \
    && [ -n "${BYPASS_PEER_TOKEN}" ] && [ -n "${BYPASS_PEER_SERVER_STATIC_PUBKEY}" ] \
    || { bypass_unmet peer_pairing "the peer pairing could not be minted; see private log ${PAIR_BYPASS_PEER_OUT}"; return 0; }
  log "operator-bypass daemon (#687) up: serverId=${SERVER_ID_BYPASS} (the test pairs it by code)"
}
mint_answer_pairing() {
  local parsed=""
  # Both the phone and the peer pair --allow-remote-permissions: the phone answers here. Never log either value.
  env "HOME=${ANSWER_HOME}" "PYRY_RELAY_URL=${DAEMON_RELAY_URL}" "${PYRY_BIN}" pair -pyry-name="${PYRY_NAME_ANSWER}" --name="${PAIR_NAME_ANSWER}" --allow-remote-permissions \
    >"${PAIR_ANSWER_OUT}" 2>&1 \
    && parsed="$(phone_pair_code "${PAIR_ANSWER_OUT}" "${PHONE_RELAY_URL}" ANSWER)" \
    && eval "${parsed}" \
    && [ -n "${SERVER_ID_ANSWER}" ] && [ -n "${PAIR_CODE_ANSWER}" ] \
    || { answer_unmet pairing "the phone pairing could not be minted; see private log ${PAIR_ANSWER_OUT}"; return 0; }
  env "HOME=${ANSWER_HOME}" "PYRY_RELAY_URL=${DAEMON_RELAY_URL}" "${PYRY_BIN}" pair -pyry-name="${PYRY_NAME_ANSWER}" --name="${PAIR_NAME_ANSWER_PEER}" --allow-remote-permissions \
    >"${PAIR_ANSWER_PEER_OUT}" 2>&1 \
    && parsed="$(pair_token "${PAIR_ANSWER_PEER_OUT}" ANSWER_PEER)" \
    && eval "${parsed}" \
    && [ -n "${ANSWER_PEER_TOKEN}" ] && [ -n "${ANSWER_PEER_SERVER_STATIC_PUBKEY}" ] \
    || { answer_unmet peer_pairing "the peer pairing could not be minted; see private log ${PAIR_ANSWER_PEER_OUT}"; return 0; }
  log "answer daemon (#966) up: serverId=${SERVER_ID_ANSWER} (the test pairs it by code)"
}
if [ -z "${DETERMINISTIC}" ] && [ -z "${BYPASS_UNMET}" ]; then
  mint_bypass_pairing
fi
if [ -z "${DETERMINISTIC}" ] && [ -z "${ANSWER_UNMET}" ]; then
  mint_answer_pairing
fi

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
# class; LIVE curates twenty-one real-claude methods (ping + create-workspace-folder + new-session +
# delete + archive-restore + change-workspace + rename + save-as-channel + list-archive-entry + two-host +
# the #848 peer-started turn + the #849 peer queue + the #850 offline read + the #891 running model +
# the #946 context usage + the four #545 settings scenarios + the #687 operator-bypass permission proof + the #950
# running-tool label, 17 turns — delete/rename/archive/unarchive/change-workspace/promote are daemon round-trips, the
# list-archive-entry arrival is pure navigation, and the two-host scenario (#847) is pairing, navigation,
# rename and link cycling, none of them claude turns) via a comma-separated class list — the full class' #481 tool-use test would spend an extra turn, so it stays
# excluded. The #965 stop method is added on top, spending two turns: the stopped turn and its follow-up ping.
if [ -n "${DETERMINISTIC}" ]; then
  TEST_TARGET="${TEST_CLASS}#${TEST_METHOD}"
elif [ -n "${LIVE}" ]; then
  # LIVE curates its real-claude turns: ping + create-workspace-folder + new-session + delete +
  # archive-restore + change-workspace + rename + save-as-channel + list-archive-entry + two-host +
  # peer-started turn + peer queue + offline read + running model + context usage + the four #545 settings
  # scenarios + the #687 operator-bypass permission proof + the #950 running-tool label (21 methods, 17 turns — the #848 peer's ping
  # is the fourth, the #849 peer's wait turn and the phone's drained ping the fifth and sixth, the #850 phone's
  # ping and the peer's offline turn the seventh and eighth, the #891 status-sheet ping the ninth, the #946 footer ping the tenth, the #545
  # inherited-effort and chosen-effort pings the eleventh and twelfth, the #545 recall's chat and channel pings
  # the thirteenth and fourteenth, the #687 tool-free ping and outside-workspace Read the fifteenth and sixteenth, the
  # #950 permission-held python3 command the seventeenth; delete, archive-restore, change-workspace, rename, save-as-channel,
  # list-archive-entry, two-host and the #545 model round trip spend none), passed as a comma-separated
  # class#method list. The class' #481 tool-use test stays excluded from LIVE for cost (it runs only in the
  # default whole-class rung-3 run).
  # #977: the #687 operator-bypass method is out of the list below until #981 fixes the missing reply,
  # so the list holds 20 methods and 15 turns for now. #981 puts it back and raises LIVE_MINIMUM to 21.
  TEST_TARGET="${TEST_CLASS}#interactiveTurn_pingPrompt_streamsPingReplyIntoThread,${TEST_CLASS}#interactiveTurn_createWorkspaceFolder_usableAsLiveSessionWorkspace,${TEST_CLASS}#interactiveTurn_newSession_rendersSessionBoundaryDelimiter,${TEST_CLASS}#interactiveTurn_deleteConversation_removesFromListAndClosesThread,${TEST_CLASS}#interactiveTurn_archiveRestore_roundTripsListMembership,${TEST_CLASS}#interactiveTurn_changeWorkspace_relabelsChipToNewWorkspace,${TEST_CLASS}#interactiveTurn_renameConversation_relabelsTopBarAndListRow,${TEST_CLASS}#interactiveTurn_saveAsChannel_promotesToChannelTier,${TEST_CLASS}#interactiveTurn_listArchiveEntry_opensArchived,${TEST_CLASS}#interactiveTurn_twoHostsCollidingConversationId_stayPerHost,${TEST_CLASS}#interactiveTurn_peerStartedTurn_continuesOnPhone,${TEST_CLASS}#interactiveTurn_peerQueue_staysConsistentAcrossClients,${TEST_CLASS}#interactiveTurn_offlineRead_reconcilesPeerTurnOnReconnect,${TEST_CLASS}#interactiveTurn_pingPrompt_statusSheetShowsRunningModel,${TEST_CLASS}#interactiveTurn_pingPrompt_footerShowsContextUsage,${TEST_CLASS}#interactiveTurn_modelChange_roundTripsAndStaysPerConversation,${TEST_CLASS}#interactiveTurn_inheritedEffort_footerShowsAppliedValueAfterTurn,${TEST_CLASS}#interactiveTurn_chosenEffort_appliesFromTheFirstTurn,${TEST_CLASS}#interactiveTurn_rememberedEffort_recalledAfterRestartIntoFreshChatAndChannel,${TEST_CLASS}#interactiveTurn_permissionHeldTool_statusAreaNamesRunningTool"
  # #965: the stop method joins the list, so it holds 21 methods and 17 turns while #687 stays out.
  TEST_TARGET="${TEST_TARGET},${TEST_CLASS}#interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain"
  # #981: the thread now shows the allowed Read's reply, so the #687 operator-bypass method is back on top:
  # 22 methods and 19 turns.
  TEST_TARGET="${TEST_TARGET},${TEST_CLASS}#interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild"
  # #966: the permission-answer method (three turns) and the question-answer method (two) join on the answer
  # daemon, so the list holds 24 methods and 24 turns.
  TEST_TARGET="${TEST_TARGET},${TEST_CLASS}#interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation"
  TEST_TARGET="${TEST_TARGET},${TEST_CLASS}#interactiveTurn_questionAnswer_reachesTheAskingConversation"
  # #967: the reconnect footer method (two turns), the reconnect slash-command and compaction method (two)
  # and the background-task method (one) join, so the list holds 27 methods and 29 turns.
  TEST_TARGET="${TEST_TARGET},${TEST_CLASS}#interactiveTurn_reconnect_footerReadingsAndModelChangeSurvive"
  TEST_TARGET="${TEST_TARGET},${TEST_CLASS}#interactiveTurn_reconnect_slashCommandsAndCompactStillWork"
  TEST_TARGET="${TEST_TARGET},${TEST_CLASS}#interactiveTurn_backgroundTask_countsInActionsMenuAndPanel"
  # #1021: the Edit channel mute round trip joins at no turn cost, so the list holds 28 methods and 29 turns.
  TEST_TARGET="${TEST_TARGET},${TEST_CLASS}#interactiveTurn_muteChannel_roundTripsThroughTheHost"
else
  TEST_TARGET="${TEST_CLASS}"
fi
log "running ${DEVICE}DebugAndroidTest (headless emulator: boot → install → ${TEST_TARGET} → teardown)…"
log "  phone relayUrl = ${PHONE_RELAY_URL}"
GRADLE_TEST_ARGS=(-PuseRelayRepository=true)
if [ "${PYRY_FORCE_TEST_RUN:-}" = "1" ]; then GRADLE_TEST_ARGS+=(--rerun); fi
# The second host and the seeded collision (#847), only on the paths that started a second daemon.
if [ -n "${SERVER_ID_B:-}" ]; then
  GRADLE_TEST_ARGS+=(
    -Pandroid.testInstrumentationRunnerArguments.serverIdB="${SERVER_ID_B}"
    -Pandroid.testInstrumentationRunnerArguments.pairCodeB="${PAIR_CODE_B}"
    -Pandroid.testInstrumentationRunnerArguments.collisionConversationId="${COLLISION_ID}"
    -Pandroid.testInstrumentationRunnerArguments.collisionNameA="${COLLISION_NAME_A}"
    -Pandroid.testInstrumentationRunnerArguments.collisionNameB="${COLLISION_NAME_B}"
  )
fi
# The second-client peer's token (#848), only on the paths that minted one.
if [ -n "${PEER_TOKEN:-}" ]; then
  GRADLE_TEST_ARGS+=(-Pandroid.testInstrumentationRunnerArguments.peerToken="${PEER_TOKEN}")
fi
# The operator-bypass daemon (#687): its unmet prerequisite, or the pairing, the peer and the witness.
if [ -n "${BYPASS_UNMET:-}" ]; then
  GRADLE_TEST_ARGS+=(-Pandroid.testInstrumentationRunnerArguments.bypassUnmet="${BYPASS_UNMET}")
elif [ -n "${BYPASS_PEER_TOKEN:-}" ]; then
  GRADLE_TEST_ARGS+=(
    -Pandroid.testInstrumentationRunnerArguments.bypassServerId="${SERVER_ID_BYPASS}"
    -Pandroid.testInstrumentationRunnerArguments.bypassPairCode="${PAIR_CODE_BYPASS}"
    -Pandroid.testInstrumentationRunnerArguments.bypassPeerToken="${BYPASS_PEER_TOKEN}"
    -Pandroid.testInstrumentationRunnerArguments.bypassServerStaticPublicKey="${BYPASS_PEER_SERVER_STATIC_PUBKEY}"
    -Pandroid.testInstrumentationRunnerArguments.bypassTokenFile="${BYPASS_TOKEN_FILE}"
    -Pandroid.testInstrumentationRunnerArguments.bypassToken="${BYPASS_WITNESS}"
  )
fi
# The answer daemon (#966): its unmet prerequisite, or the privileged phone code and the peer.
if [ -n "${ANSWER_UNMET:-}" ]; then
  GRADLE_TEST_ARGS+=(-Pandroid.testInstrumentationRunnerArguments.answerUnmet="${ANSWER_UNMET}")
elif [ -n "${ANSWER_PEER_TOKEN:-}" ]; then
  GRADLE_TEST_ARGS+=(
    -Pandroid.testInstrumentationRunnerArguments.answerServerId="${SERVER_ID_ANSWER}"
    -Pandroid.testInstrumentationRunnerArguments.answerPairCode="${PAIR_CODE_ANSWER}"
    -Pandroid.testInstrumentationRunnerArguments.answerPeerToken="${ANSWER_PEER_TOKEN}"
    -Pandroid.testInstrumentationRunnerArguments.answerServerStaticPublicKey="${ANSWER_PEER_SERVER_STATIC_PUBKEY}"
  )
fi
# Capture the status rather than let set -e exit, so a failure caused by an expired code is named (#993).
TEST_STATUS=0
"${GRADLEW}" -p "${REPO_ROOT}" "${DEVICE}DebugAndroidTest" \
  "${GRADLE_TEST_ARGS[@]}" \
  -Pandroid.testInstrumentationRunnerArguments.class="${TEST_TARGET}" \
  -Pandroid.testInstrumentationRunnerArguments.relayUrl="${PHONE_RELAY_URL}" \
  -Pandroid.testInstrumentationRunnerArguments.token="${TOKEN}" \
  -Pandroid.testInstrumentationRunnerArguments.serverId="${SERVER_ID}" \
  -Pandroid.testInstrumentationRunnerArguments.serverStaticPublicKey="${SERVER_STATIC_PUBKEY}" \
  --console=plain || TEST_STATUS=$?
if [ "${TEST_STATUS}" -ne 0 ]; then
  report_stale_pairing_codes
  exit "${TEST_STATUS}"
fi

if [ -n "${DETERMINISTIC}" ]; then
  log "PASS — scenario '${SCENARIO}' green: the emulator connected, sent the prompt, and the scripted reply rendered."
elif [ -n "${LIVE}" ]; then
  log "PASS — the headless emulator connected over the LIVE relay, sent the prompts, and the ping reply, the created-workspace flow, the new-session delimiter, the delete-conversation flow, the archive/restore round-trip, the change-workspace chip re-label, the rename top-bar/list re-label, the save-as-channel promote (top-bar re-label + channel tier), the list's archive entry reaching Archived, two hosts sharing one conversation id staying separate, a turn started from a second client continuing on the phone, phone replies, queued sends and drops staying consistent with that client, a conversation staying readable offline and catching up on reconnect, the Status sheet's running model and the footer's context usage after a real turn, and the model and effort settings round trips (per-conversation model change, applied effort after a turn, remembered effort across a restart), and a bypass child's permission control following only its confirmed mode through a manual-approval Read all rendered."
else
  log "PASS — the headless emulator connected, sent the prompt, and 'ping' rendered in the thread."
fi
