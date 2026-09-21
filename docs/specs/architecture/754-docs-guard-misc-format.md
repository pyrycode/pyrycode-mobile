# 754 — docs-guard.sh catches what `format("misc")` would rewrite

## Files read

- `scripts/docs-guard.sh` — the whole script: `FEATURES_DIR`, `CAP_BYTES`, `report`, and the
  single `while` loop that already carries the size-cap and false-heading rules. The new rules
  join that loop so one run still reports everything.
- `build.gradle.kts` → the `format("misc")` block — `trimTrailingWhitespace()` and
  `endWithNewline()` are the two steps whose verdict this guard has to anticipate, and its
  `target`/`targetExclude` lists confirm `docs/knowledge/features/**.md` is covered.
- `scripts/test_e2e_emulator_gradle.py`, `scripts/test_e2e_emulator_cleanup.py` — the existing
  `scripts/` test idiom: plain `unittest`, `subprocess` against the real shell, `tempfile` for
  fixtures, no third-party runner. The new test follows it.
- `docs/knowledge/features/development-verification.md` § "Gradle and source checks" — lists
  `./scripts/docs-guard.sh` as the first gate; the documentation stage owns this file and the
  handoff below, not this ticket.
- `CLAUDE.md` § Documentation — its one-line description of the guard names only the size cap
  and the false headings. Noted for the documentation stage; not edited here.

## Context

`./gradlew spotlessCheck` is red on `main` over one byte: `settings-screen-how-it-works.md`
ends `.\n\n` where `endWithNewline()` wants `.\n`. Because `check` fails fast at
`:spotlessMiscCheck`, the whole gate chain aborts before the unit suite, lint,
`assembleDebug`, the androidTest compile and both device gates, so every branch cut from
`main` today reaches the verifier with no device evidence.

The byte is not the fix. `docs/knowledge/features/` is documentation-stage-owned, and that
stage writes it *after* the verifier gate and after merge — it is the only stage that writes
gate-covered files with no gate behind it. Builders are forbidden from repairing the file
(two already reverted the `spotlessApply` fix to keep byte-identity with `main`), so the
violation survives every cycle. `scripts/docs-guard.sh` is the check that stage runs before
it commits, so teaching it the two conditions `format("misc")` applies puts the enforcement
in front of the only role that can repair the fault.

Verified at `184da2d` with a byte-level scan of every `*.md` under
`docs/knowledge/features`: `settings-screen-how-it-works.md` is the only file that does not
end in exactly one newline, and no file in that tree carries trailing whitespace. The
trailing-whitespace rule therefore ships without a live example — it is the other half of the
same spotless target, and a guard that catches one condition and not the other leaves the
identical deadlock open for the next occurrence.

No ADR is warranted: this extends an existing guard rather than choosing an approach.

## Change

`scripts/docs-guard.sh` gains two rules inside its existing per-file `while` loop, reported
through the same `report` helper so a single run still names every problem and exits once at
the end:

1. **Trailing whitespace** — every line matching `[[:space:]]+$`, reported as `path:lineno`
   with the instruction to delete the spaces or tabs. Scanned with `grep -nE` in the same
   `while IFS=: read` shape the false-heading rule already uses.
2. **End of file** — the file must end in exactly one newline. Counted without a byte dump:
   command substitution strips every trailing newline, so `$bytes` minus the size of
   `$(cat "$path")` is the number of newline bytes at the end. Zero reports "does not end in
   a newline"; two or more reports how many blank lines to delete. This arithmetic matches
   `EndWithNewlineStep` exactly on every case, the empty file and the newline-only file
   included.

Both messages name the file and what to remove, per AC1. Nothing else in the script moves:
`FEATURES_DIR`, `CAP_BYTES`, the `report` counter, the exit contract and the two existing
rules are untouched. The header comment gains the two new conditions and a paragraph on the
ordering gap that motivates them; its existing "why code and not a prompt rule" argument
already covers the new rules for the same reason.

Nothing under `app/src/` changes, so no production Kotlin, no new dependency, no Gradle edit.

**This branch is expected to fail `./scripts/docs-guard.sh` and `./gradlew spotlessCheck`.**
The new rule reddens on the pre-existing `settings-screen-how-it-works.md` byte, which `main`
is already red on, and which this ticket's builder is forbidden to repair. That red is the
ticket's evidence, not a regression; it clears in the documentation commit below.

## Testing strategy

New `scripts/test_docs_guard.py`, run by `python3 -m unittest discover -s scripts` alongside
the two existing `scripts/` tests. It copies the real `scripts/docs-guard.sh` into a
temporary repo skeleton (`scripts/` + `docs/knowledge/features/`) and runs it against
fixtures, so the script under test is the shipped one and the repo's own tree is never read.

Scenarios:

- A fixture ending in exactly one newline, no trailing whitespace: exit 0, no output.
- A fixture ending in a blank line: exit 1, message names the file. (The two directions AC3
  asks for.)
- A fixture with no newline at all at EOF: exit 1.
- A fixture with a trailing space and a trailing tab: exit 1, message names `file:line` for
  each.
- One run over a tree carrying all four faults at once — oversized file, false heading,
  trailing whitespace, trailing blank line: exit 1, every one of them named, proving AC2's
  "reports every problem rather than exiting at the first" and that the two existing rules
  still fire.

No Kotlin source or test changes, so no `testDebugUnitTest` filter, no
`compileDebugAndroidTestKotlin`, and no device or scripted scenario applies. This ticket
ships no operator-facing flow, so no rung-3 scenario is owed. `./gradlew assembleDebug` is
run as the standing salvage gate.

## Documentation handoff

Pending for the documentation stage; not done by this builder.

- Run `scripts/docs-guard.sh` and repair everything it reports, which now includes
  `docs/knowledge/features/settings-screen-how-it-works.md`: delete the trailing blank line
  so the file ends `.\n`. After the documentation commit, `./gradlew spotlessCheck` is green
  on the branch.
- Record the ordering gap in the owning topic
  (`docs/knowledge/features/development-verification.md`): this stage writes
  `docs/knowledge/features/` after every mechanical gate has already run, which is why
  `scripts/docs-guard.sh` is the check that stands in for them, and why a fault it reports
  has to be repaired here rather than left for a builder.
- Also noted, outside the ticket's stated handoff: `CLAUDE.md` § Documentation describes the
  guard as keeping the overviews "under 50000 bytes and free of lines that markdown misreads
  as headings". That sentence now undercounts the guard's rules. The documentation stage owns
  whether to widen it.
