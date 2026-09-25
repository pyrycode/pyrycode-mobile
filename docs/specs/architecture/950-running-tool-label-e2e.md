# #950 — Running-tool status label on the emulator harness

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt` → `interactiveTurn_seededChannel_toolStepRunsThenCompletes` — the `tool` scenario whose held-open window proves the label without elapsed; the two-drop idiom the new scenario copies.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_peerQueue_staysConsistentAcrossClients`, `WAIT_PROMPT`, `hostConversationIds`, `twoHostArg`, `sendFromPhone`, `interactiveTurn_toolPrompt_rendersToolStepInThread` — the permission-prompt lever that holds a real-claude tool call open with no timing, and the peer that allows it.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt` → `awaitPermissionModal`, `allowOnce`, `awaitFrame` — the privileged peer's API.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ThinkingIndicator.kt` → `ThinkingIndicator` — the merged node carrying `cd_thread_tool_running` / `cd_thread_tool_running_elapsed`; the only producer of those descriptions.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `openToolCall`, `ThreadStatusArea` — the label shows only while the turn is busy and the latest tool row is `Running`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ToolRowFormat.kt` → `formatToolElapsed` — `30` renders as `30s`.
- `scripts/e2e-emulator.sh` → the `SCENARIO` `case`, the LIVE `TEST_TARGET` list — scenario and live registration.
- `scripts/android-test-gate.py` → `SCENARIOS`, `LIVE_MINIMUM`; `scripts/test_android_test_gate.py` → `test_live_floor_matches_the_curated_list` — the floor must equal the curated list's size.
- `scripts/e2e-fixtures/tool-open.jsonl`, `tool-done.jsonl` — the fixture shape and the `toolu_e2e` correlation id.
- `../pyrycode/internal/e2e/realclaude/testdata/tool_progress_v2.1.259.json` and `consumeToolProgress` / `emitToolProgressHeartbeat` in `../pyrycode/internal/streamsup/parser.go` — the heartbeat line needs `"heartbeat":true` and a string `parent_tool_use_id`, else it is dropped silently.
- `docs/e2e-interactive-stream.md` § "Deterministic mode (rung 4)", § "Scenarios (#454)".

No in-flight feature branch touches these files.

## Design source

N/A — test-only; the visual is #897's.

## Context

#897 shipped the status label (`Running <tool>…`, plus `… 30s` once a `tool_progress` heartbeat arrived) with unit coverage only. This adds the rung-4 twin and the rung-3 proof, in the #481 / #482 shape. No production code changes.

## Design

### Rung 4 — two scenarios, two fenced drops each

The harness has two drops per scenario, so the three states are split across two scenarios:

1. **Label without elapsed — the existing `tool` scenario.** Its drop A is a lone `tool_use`, held open until the second send. Inside that window the test additionally asserts the exact description `cd_thread_tool_running` formatted with `Bash`, and after drop B that it is gone. No fixture or wiring change.
2. **New scenario `tool-progress`** → `interactiveTurn_seededChannel_runningToolLabelShowsElapsedThenClears`.
   - Drop A `tool-progress-open.jsonl`: the `tool_use` line of `tool-open.jsonl` (id `toolu_e2e`), then one heartbeat line in the captured shape: `type:"tool_progress"`, `tool_use_id:"toolu_e2e-heartbeat-0"`, `tool_name:"Bash"`, `parent_tool_use_id:"toolu_e2e"`, `elapsed_time_seconds:30`, `heartbeat:true`, the fixture session id and a fixed uuid. Held open.
   - The test waits for the exact description `cd_thread_tool_running_elapsed` formatted with `Bash`, `30s`, asserts it displayed, then sends the second message.
   - Drop B `tool-progress-result.jsonl`: **only** the correlated success `tool_result`, no turn end. The turn stays busy, so the label's disappearance can only come from the call closing, not from the turn ending. A regression that clears the label only at turn end reddens here; reusing `tool-done.jsonl` would hide it.
   - The test waits until neither label description exists and the tool row's `cd_tool_running` is gone.

Registration: `tool-progress` `case` arm in `scripts/e2e-emulator.sh` (plus the usage comment and the unknown-scenario message), and the `SCENARIOS` tuple in `scripts/android-test-gate.py`.

### Rung 3 — the permission prompt holds the call open

A tool call is open from its `tool_use` until its `tool_result`. Real claude's quick commands close too fast to observe without racing. The #849 lever holds it open without timing: a `python3` command is never auto-allowed, so claude's `tool_use` arrives and the call waits on a permission prompt that only the peer paired with `--allow-remote-permissions` can answer.

- Helper `holdToolOnPermission(peer, command): Pair<String, String>` (conversation id, modal id): list, connect, create a chat, find its id by diff as #849 does, open the peer, send the prompt from the phone, await the peer's permission modal.
- **Always-on** `interactiveTurn_permissionHeldTool_statusAreaNamesRunningTool`: command `python3 -c "print(950)"`. While the prompt is pending the test waits for the exact `cd_thread_tool_running` (`Bash`) and asserts it displayed; the peer allows once; after the peer sees `turn_end`, the description is gone. One claude turn. Added to the LIVE curated list; `LIVE_MINIMUM` 20 → 21 and the list comments' counts move by one.
- **`@Ignore`d** `interactiveTurn_longRunningTool_statusAreaShowsElapsed`: command `python3 -c "import time; time.sleep(45)"`. After the allow, wait for any `cd_thread_tool_running_elapsed` reading (matcher built from the resource with the elapsed slot as a wildcard), then `turn_end` and absence. KDoc reason: the first heartbeat is claude's, at 30 s, measured once (2.1.259); the observable window is the remaining ~15 s and depends on claude running the command in the foreground as asked. It cannot be made durable here and costs ≥45 s per run.

Constants: `RUNNING_TOOL_PROMPT`, `ELAPSED_TOOL_PROMPT` in the companion; descriptions read from resources like the existing ones.

## State + concurrency model

Test-only. Peer I/O runs in `runBlocking` inside the test, as in #849; the peer is closed in `finally`.

## Error handling

A missing permission prompt or heartbeat times out in `waitUntil` / `withTimeout` and fails the test, as the siblings do. Rung 3's peer args fail through `twoHostArg` with the script name.

## Testing strategy

- `./gradlew compileDebugAndroidTestKotlin`, `spotlessApply`, `lint`, `assembleDebug`.
- `python3 -m unittest scripts/test_android_test_gate.py` for the floor and scenario tuple.
- Focused: `python3 scripts/android-test-gate.py scripted tool-progress` and `scripted tool` if the environment allows; otherwise the dispatcher's `scripted-all` runs them before verifier.
- Rung 3 runs in the dispatcher's LIVE suite after verifier (`needs-real-claude`).

## Documentation handoff

Pending for the documentation stage: `docs/e2e-interactive-stream.md` § "Scenarios (#454)" — add the `tool-progress` row, its command line and a paragraph; note that `tool` now also asserts the status label; § "Live mode" / LIVE list counts (21 methods, 17 turns) and the rung-3 running-tool scenario with its `@Ignore`d elapsed half.

## Open questions

- Does the heartbeat reach the phone under `fakeclaude` exactly as under real claude? The parser path is the same stream-json parser, so yes by construction; the focused scripted run confirms it.
