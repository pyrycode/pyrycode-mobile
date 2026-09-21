#!/usr/bin/env bash
#
# docs-guard.sh — bounds the size of the feature overviews under docs/knowledge/features,
# keeps their heading structure honest, and holds them to the formatting spotless applies.
#
#   scripts/docs-guard.sh
#
# Scans every .md file under docs/knowledge/features and exits non-zero on any of four
# faults: a file over the size cap; a line that markdown reads as a heading only because a
# wrapped paragraph put a ticket reference first; a line with trailing whitespace; and an
# end of file that is not exactly one newline. One run reports every problem it finds.
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
# Why the two formatting checks: format("misc") in the root build.gradle.kts covers every
# *.md in the repo with trimTrailingWhitespace() and endWithNewline(), so a trailing space
# or a blank line at EOF fails ./gradlew spotlessCheck — and because check fails fast at
# :spotlessMiscCheck, it aborts the whole gate chain ahead of the unit suite, lint,
# assembleDebug, the androidTest compile and both device gates. These overviews are written
# by the documentation stage, which runs after the verifier gate and after merge, so its own
# output is the one thing in the tree that no Gradle gate ever sees before it lands on main.
# Nor can any pre-merge stage repair it: these files are documentation-stage-owned and the
# builder and verifier prompts both forbid writing them. That leaves this script — the check
# the documentation stage runs before it commits — as the only gate standing in front of the
# only role allowed to fix the fault. Measured on 2026-09-22 (#754): the same trailing blank
# line was written into settings-screen-how-it-works.md on three separate runs, two builders
# correctly reverted the spotlessApply fix to preserve byte-identity with main, and four
# verifier passes spent budget proving the red was not theirs.
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
  # What trimTrailingWhitespace() would strip. grep hands each line over without its
  # newline, so [[:space:]]$ is the end of the line's own text. [[:space:]] rather than
  # [[:blank:]] deliberately: it also catches a stray CR, and this guard should err toward
  # a red that costs one edit over a green that deadlocks the pipeline behind spotless.
  while IFS=: read -r lineno _; do
    report "$path:$lineno: trailing whitespace — spotless would strip it; delete the spaces or tabs at the end of the line"
  done < <(grep -nE '[[:space:]]+$' "$path" || true)
  # What endWithNewline() would rewrite: it collapses every newline at EOF to exactly one,
  # and adds one where there is none. Command substitution strips those trailing newlines,
  # so the file's size minus the size of its stripped body counts them — no byte dump
  # needed, and the arithmetic agrees with spotless on the empty and newline-only files too.
  body_bytes=$(printf '%s' "$(cat "$path")" | wc -c | tr -d ' ')
  newlines=$((bytes - body_bytes))
  if [ "$newlines" -eq 0 ]; then
    report "$path: does not end in a newline — spotless would add one; put a single newline after the last line"
  elif [ "$newlines" -eq 2 ]; then
    report "$path: ends in a blank line — spotless would collapse it; delete the blank line so the file ends in exactly one newline"
  elif [ "$newlines" -gt 2 ]; then
    report "$path: ends in $newlines newlines — spotless would collapse them to one; delete the $((newlines - 1)) blank lines at the end of the file"
  fi
done < <(find "$FEATURES_DIR" -type f -name '*.md' | sort)

if [ "$problems" -gt 0 ]; then
  echo "docs-guard: $problems problem(s)" >&2
  exit 1
fi
