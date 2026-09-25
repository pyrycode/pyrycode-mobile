# #1107 — Re-enable #1076's live background-task progress scenario

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_backgroundAgentProgress_showsOnRunningCard` — carries the `@Ignore` and the KDoc paragraph about pyrycode/pyrycode#2658.
- `scripts/e2e-emulator.sh` → the `LIVE=1` `TEST_TARGET` curated list, whose last entry is #1090's attention-dot method (44 methods, 43 turns).
- `scripts/android-test-gate.py` → `LIVE_MINIMUM`, raised one comment-and-increment line per ticket.
- `scripts/test_android_test_gate.py` → the test asserting `LIVE_MINIMUM` equals the count of `#interactiveTurn_` entries in the script's targets; it keeps the two edits in step.

## Design source

Figma: N/A — test and gate-script change, no UI.

## Change

pyrycode/pyrycode#2658 closed on 2026-09-25 via pyrycode/pyrycode#2661 (merge `b733a68`), which drops a subagent's delegated prompt instead of surfacing it as an `unrecognized_message`. That merge is an ancestor of `main` in the gate's `PYRYCODE_SRC` checkout, so the gate's daemon no longer trips `UnrecognizedRowSentinel` on this scenario. Remove the `@Ignore` (and its import if nothing else uses it) and the KDoc's blocked-on paragraph, keeping its hold and one-turn notes. Append the method to the `LIVE=1` list with a `#1107` comment: one turn, so the list holds 45 methods and 44 turns. Add `# #1107 re-adds #1076's background-task progress method.` and `LIVE_MINIMUM += 1` to the gate script. Nothing else moves.

## Testing strategy

`scripts/test_android_test_gate.py` checks that the floor and the list agree; `compileDebugAndroidTestKotlin` checks the test compiles without the annotation. The acceptance proof is the dispatcher's post-verifier `LIVE=1` real-claude run passing the method with the sentinel green; no builder-run live suite.

## Documentation handoff

Pending for the documentation stage: `docs/e2e-interactive-stream.md` — move `interactiveTurn_backgroundAgentProgress_showsOnRunningCard` from the ignored scenarios to the always-on `LIVE=1` list, with the new floor (`LIVE_MINIMUM` +1) and turn count (44).
