#!/usr/bin/env bash
#
# e2e-preship-gate.sh — the mobile pre-ship gate.
#
# Runs the live rung-3 real-claude e2e (the "ping" scenario against the PRODUCTION
# relay over wss:// (TLS), on the isolated e2e-live instance) by wrapping
# `LIVE=1 scripts/e2e-emulator.sh`. This is the mobile parallel of the daemon's
# `make e2e-realclaude`: the one command an operator runs so they are never the
# first real-stack execution.
#
# It bakes in ONLY LIVE=1 — the e2e-live PAIR_NAME/PYRY_NAME isolation is already
# the LIVE=1 default inside e2e-emulator.sh, so there is nothing else to set. The
# wrapper adds no behavior of its own: a down or stale relay still surfaces as the
# same live-gate red signal (the phone's connect timing out), which is the exact
# failure class this gate exists to catch. Operator overrides (e.g. LIVE_RELAY_HOST)
# flow through the exec untouched.
#
# When to run + cost: see README § Pre-ship gate and
# docs/e2e-interactive-stream.md § Pre-ship gate.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec env LIVE=1 bash "${HERE}/e2e-emulator.sh"
