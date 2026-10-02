# #1528 — permission-held tool test proves the label after the allow

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_permissionHeldTool_statusAreaNamesRunningTool` (the method to repair), `holdToolOnPermission`, `runningToolLabel`, the companion's `RUNNING_TOOL_PROMPT` / `ELAPSED_TOOL_PROMPT`, and the two push tests that also send `RUNNING_TOOL_PROMPT` (`interactiveTurn_backgroundTurnEnd_pushPostsOneAlertThatOpensThread`, `interactiveTurn_backgroundPrompt_pushPostsExactlyOneAlertAcrossReconnect`).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: `ThreadStatusArea` (since #1483 an open permission request reads `thread_status_waiting_for_permission` in place of the arms) and `STATUS_READING_TEST_TAG`.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `allowOnce` returns once `modal_dismissed` arrives.

## Change

The test still holds claude's Bash call on the peer-only permission prompt. During the hold it now asserts that the status band's reading box shows "Waiting for permission" and that no node carries the `cd_thread_tool_running` label. Then the peer allows the call once. The held command is a new companion constant, `HELD_TOOL_PROMPT`, whose `python3` sleeps 10 s before printing. That keeps the allowed call open long enough to observe and well under claude's ~30 s first `tool_progress` heartbeat. The sleep is inside `python3` for the same reason as `ELAPSED_TOOL_PROMPT`. While the command runs, the test waits for the exact no-elapsed `runningToolLabel` and asserts it is displayed. After `turn_end` it waits for the label to be gone. `RUNNING_TOOL_PROMPT` is not changed, so the two push tests keep their quick command. No production code moves, because #1483's behaviour is the locked design.

## Testing strategy

The repaired method is itself the test. It is a rung-3 live scenario, so its proof is the live gate's run of it and of the two push tests. Locally, `compileDebugAndroidTestKotlin` and `spotlessCheck` cover the edit.

## Documentation handoff

Pending for the documentation stage:

- `docs/knowledge/features/thinking-indicator.md`, in the #950 paragraph ("holds a real tool call open on a permission prompt … and proves the label"): state that since #1483 the held phase reads "Waiting for permission" and the label is proven after the peer allows the command, while it runs.
- `docs/e2e-interactive-stream.md` § "What rung 3 is made of": the same correction to the rung-3 mechanics.
