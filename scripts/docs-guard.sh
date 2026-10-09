#!/bin/sh
set -eu
exec python3 "$(dirname "$0")/agent-tool.py" docs-guard.sh "$@"
