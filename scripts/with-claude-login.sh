#!/usr/bin/env bash
#
# with-claude-login.sh — run a command with the long-term Claude login when the environment has none.
#
#   scripts/with-claude-login.sh <command> [args...]
#   scripts/with-claude-login.sh pyry -pyry-name=relchk ...     # a throwaway test daemon started by hand
#
# The dispatcher's launcher sets CLAUDE_CODE_OAUTH_TOKEN through
# `automation-access op run --env-file=<agents repo>/.env`. A live test or a throwaway daemon started by
# hand skipped that launcher, fell back to the shell's own Claude login, and stalled when that was missing
# or expired (2026-09-20, 09-26, 10-02 and 10-04). When CLAUDE_CODE_OAUTH_TOKEN is unset, this reads the
# same 1Password item through automation-access and exports it to the command only. The value is never
# printed. When the fetch is unavailable the command runs as before, on the shell's own login.
#
# Inside the dispatcher, where AGENTS_REPO_PATH is set, it fetches nothing: agents get no Automation login,
# and a builder's restricted live-test login is fetched by scripts/android-test-gate.py.
# scripts/e2e-emulator.sh runs itself through this in LIVE mode.
set -euo pipefail

[ "$#" -gt 0 ] || { echo "usage: scripts/with-claude-login.sh <command> [args...]" >&2; exit 2; }

LOGIN_ITEM="op://Automation/Claude long term token/password"

if [ -z "${CLAUDE_CODE_OAUTH_TOKEN:-}" ] && [ -z "${AGENTS_REPO_PATH:-}" ] \
  && command -v automation-access >/dev/null 2>&1; then
  if login="$(automation-access op read --no-newline "${LOGIN_ITEM}" 2>/dev/null)" && [ -n "${login}" ]; then
    export CLAUDE_CODE_OAUTH_TOKEN="${login}"
    echo "with-claude-login: fetched the long-term Claude login through automation-access" >&2
  else
    echo "with-claude-login: could not fetch the long-term Claude login; using this shell's own" >&2
  fi
  unset login
fi

exec "$@"
