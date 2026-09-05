#!/usr/bin/env bash
#
# docs-guard.sh — bounds the size of the feature overviews under docs/knowledge/features
# and keeps their heading structure honest.
#
#   scripts/docs-guard.sh
#
# Scans every .md file under docs/knowledge/features and exits non-zero on either of
# two faults: a file over the size cap, and a line that markdown reads as a heading only
# because a wrapped paragraph put a ticket reference first.
#
# Ported rule-for-rule from pyrycode's cmd/docs-guard (Go, in `make check`) and desktop's
# scripts/docs-guard.mjs (`npm run check:docs`) on 2026-09-05. Keep the three in step:
# the cap and the heading pattern are the same numbers and the same regex, and the
# dispatcher's src/docs-size.ts flags the same files to the documentation agent before
# it writes. This one is shell because the repo has no Go or Node toolchain, and the
# dispatcher runs it as the first entry of PYRY_VERIFIER_GATES so it fails fast ahead of
# the Gradle gates.
#
# Why the size cap: QMD is the search surface every agent uses. It cuts a document into
# roughly 900-token chunks and prefers to break at a heading, but it only looks for that
# boundary inside a narrow window around each cut point. When a document's sections run
# much larger than one chunk, no heading falls inside the window, the cut lands on a
# paragraph break, and the chunk carries no heading with it. Measured on pyrycode
# 2026-08-31: a 315KB overview was not returned by semantic, hybrid or keyword search
# for a topic whose canonical home was one of its own sections.
#
# Why the heading check: a paragraph line that wraps with a ticket reference first,
# "#623 pages a conversation's history...", is a top-level heading as far as markdown is
# concerned. It corrupts the document outline and moves the boundaries the chunker
# prefers to cut on.
#
# Why code and not a rule in a prompt: both faults are produced by the documentation
# phase, which already carries a prose rule against them. A prose rule is advisory. A
# safety net for one must be a different fabric: deterministic code, not a second rule
# that shares the first one's blind spot.
set -u

# The only directory scanned. Decisions and the frozen per-ticket archive are
# deliberately out of scope: a decision record is written once and read by the ticket
# that owns it, and the archive is closed to writes, so flagging it would report a fault
# nobody is allowed to fix.
FEATURES_DIR="docs/knowledge/features"

# The largest acceptable overview. Must agree with FEATURE_DOCS_CAP_BYTES in the
# dispatcher's src/docs-size.ts, which flags the same files to the documentation agent
# before it writes.
CAP_BYTES=50000

cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1

problems=0
report() { problems=$((problems + 1)); printf '  %s\n' "$1" >&2; }

if [ ! -d "$FEATURES_DIR" ]; then
  echo "docs-guard: $FEATURES_DIR not found" >&2
  exit 1
fi

while IFS= read -r path; do
  bytes=$(wc -c < "$path" | tr -d ' ')
  if [ "$bytes" -gt "$CAP_BYTES" ]; then
    report "$path: $bytes bytes, over the $CAP_BYTES-byte cap — split it at its ## headings, keeping the parent as a map"
  fi
  # A real heading always has a space after its hashes, so ^#[0-9] cannot match one.
  while IFS=: read -r lineno _; do
    report "$path:$lineno: parses as a heading because it opens with a ticket reference — join it to the line above, or escape the hash"
  done < <(grep -nE '^#[0-9]' "$path" || true)
done < <(find "$FEATURES_DIR" -type f -name '*.md' | sort)

if [ "$problems" -gt 0 ]; then
  echo "docs-guard: $problems problem(s)" >&2
  exit 1
fi
