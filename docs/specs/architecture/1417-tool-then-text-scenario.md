# #1417 — `tool-then-text` scripted scenario

## Files read

- `scripts/e2e-fixtures/tool-failed.jsonl`, `tool-open.jsonl`, `tool-done.jsonl`: the single-fragment fixture shape to mirror; `tool_use.id` equals `tool_result.tool_use_id`.
- `scripts/e2e-emulator.sh`: the `SCENARIO` case in the deterministic preflight, the `tool-failed` arm, the usage header and the `SCENARIO` comment.
- `scripts/android-test-gate.py`: `SCENARIOS`, which `scripted-all` iterates and counts.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: `arriveInSeededThread`, `typeAndSend`, `interactiveTurn_seededChannel_failedToolStepRendersFailed`, the companion constants.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ToolRowFormat.kt`, `toolHeadline`: a `Bash` call carrying `command` or `description` leads with that field, not the tool name (#1315); only a `Bash` call with neither leads with `Bash`.
- `../pyrycode/internal/streamsup/parser.go`, `Parser.emitAssistant`: a non-streamed `text` block becomes a `TextChunk` (an `assistant_delta` on the wire) even when its `message.id` is later shared by a `tool_use` line; the suppression applies only to a single-block line closing an open `stream_event` text block, which this fixture never opens.

Overlaps: #1360 adds a `refusal` scenario to the same three files and #1337, #1397, #1410 touch `scripts/e2e-emulator.sh`. All are list-entry neighbours; edits here stay additive.

## Change

Add `scripts/e2e-fixtures/tool-then-text.jsonl`: an `assistant` line with id `ttt-1` holding text with the first marker `foxtrot`, an `assistant` line with the same id `ttt-1` holding a `Bash` `tool_use` with input `{}`, the correlated non-error `tool_result`, an `assistant` line with a new id `ttt-2` and `stop_reason: end_turn` holding text with the second marker `zulu`, then `result`/`success`. Wire `tool-then-text` as a single-drop arm beside `tool-failed` in `scripts/e2e-emulator.sh` and append it to `SCENARIOS`. Add `interactiveTurn_seededChannel_replyTextAfterToolRendersBelowIt`: send, wait until both markers render, the `Bash` row is on screen, and neither the running-tool nor the thinking content description remains, then assert by `boundsInRoot` that the first marker's node ends above the tool row's top and the second marker's node starts below its bottom, and that the two nodes differ and neither contains the other marker. No production code changes.

The tool input is `{}` so that the collapsed row's lead is the tool name `Bash`, which the acceptance criteria match on. A `command` would replace the name with the command (#1315). Markers `foxtrot` and `zulu` do not occur in `e2e-seed`, `hello`, `Bash`, each other or the tool row's text.

## Testing strategy

The new method is the test, run on the emulator through `python3 scripts/android-test-gate.py scripted tool-then-text`, which must report one executed test. It is device-only because the scripted ladder runs against a real daemon and relay. `scripts/test_android_test_gate.py` iterates `gate.SCENARIOS`, so its existing tests cover the added entry. `scripted-all` is the dispatcher's gate.

## Documentation handoff

- `docs/e2e-interactive-stream.md`, the rung-4 scenario table: add the `tool-then-text` (#1417) row (reply text after a tool step renders below it, `tool-then-text.jsonl`, one drop) and its `DETERMINISTIC=1 SCENARIO=tool-then-text …` command line. Pending for the documentation stage.
