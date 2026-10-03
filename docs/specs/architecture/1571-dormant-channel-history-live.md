# #1571: live scenario for opening a dormant channel's history without a send

## Files read

- `scripts/e2e-emulator.sh`: `seed_collision_conversation` (the registry merge this seed reuses for its row),
  step "2c" (the rung-3/LIVE pre-launch seed block, guarded by `two_host_name_ok`), the LIVE `TEST_TARGET`
  selector, and the `GRADLE_TEST_ARGS` instrumentation-argument blocks.
- `scripts/test_e2e_emulator_two_host.py`: how the harness's shell functions are unit-tested by extracting
  them from the script; the new seed's tests sit beside `seed_collision_conversation`'s.
- `scripts/android-test-gate.py`: `curated_live_methods` / `LIVE_MINIMUM` count the selector, so the
  live floor rises with the new method without a hand edit.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `openRow`, `awaitChannelRow`,
  `twoHostArg`, `pullForOlderHistory` (deliberately not used), `offlineReplyMatcher` (the bubble-anchored
  text matcher shape), `interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload` (the analogue).
- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryPageReducer.kt`: `withHistoryEntry` folds a
  stored `send_message` ungated and `assistant_delta` / `turn_end` behind the interactive capability, which
  the live daemon advertises.
- `app/src/main/java/de/pyryco/mobile/data/network/InteractivePayloads.kt`: `AssistantDeltaPayloadDto`
  requires `turn_id` and `seq`, and `TurnEndPayloadDto` requires `stop_reason`, so the seeded payloads carry
  them even though the older `history_page*.json` fixtures' `assistant_delta` does not.
- pyrycode `internal/history/segment.go` and `log.go` (`segmentHeaderLine`, `segmentName`, `decodeSegment`,
  `Entry`, `resolveDir`), `cmd/pyry/main.go` `resolveInstanceDirPath`, and the protocol testdata
  `history_page*.json`, `assistant_delta.json`, `turn_end.json`, `send_message.json`: the on-disk format the
  seed writes.

Overlapping in-flight branches: #1487 and #1563 also touch `InteractiveStreamE2ETest.kt`; this change only
adds one method and companion constants.

## Context

#1569 and #1572 made an open thread request its newest history page by itself, proven only by
`ThreadViewModelTest`. The live suite cannot restart its daemon mid-run and has idle eviction off, so a
dormant channel with stored history has to exist before the daemon starts. A registry row bound to a session
id the daemon does not hold is the after-restart dormant state (see `seed_collision_conversation`'s comment);
a daemon-format history log beside it supplies the stored messages without spending a Claude turn. Only the
daemon-restart form of dormancy is covered. No decision record is needed: this is a harness seed.

## Design

**Harness.** A new shell function `seed_dormant_history <instance-dir> <conversation-id> <user-text>
<assistant-text>` writes `<instance>/conversations/<id>/history/segment-00000000000000000001.jsonl`: the
literal header `{"format":"pyrycode.history","version":1}`, then three compact `{"id","type","payload","ts"}`
lines with ids 1–3 and ascending UTC timestamps:

1. `send_message` `{conversation_id, message_id, text: <user-text>}`;
2. `assistant_delta` `{conversation_id, turn_id, seq: 0, text: <assistant-text>}`;
3. `turn_end` `{conversation_id, turn_id, stop_reason: "end_turn", outcome: "success"}`.

This is what a real interactive turn logs. Directories are created 0700 and the segment 0600; an existing
history directory for that id is refused rather than merged into, since the id is run-unique and the daemon
would otherwise read a mixed log. Other conversations' directories are not touched.

In step 2c, inside the existing non-DETERMINISTIC block, the harness mints `DORMANT_ID`, `DORMANT_SESSION`
and a stamp, seeds the row with `seed_collision_conversation "${HOME}/.pyry/${PYRY_NAME}" … "${DORMANT_SESSION}"`
(promoted, bound to a session the daemon does not hold), then calls `seed_dormant_history`. Names:
`e2e1571-<stamp>`; texts `e2e1571-ask-<stamp>` and `e2e1571-reply-<stamp>`. The id, name and reply text
reach the test as `dormantConversationId`, `dormantName` and `dormantReply` in their own `GRADLE_TEST_ARGS`
block, set whenever `DORMANT_ID` is.

**Test.** `interactiveTurn_dormantChannel_opensWithStoredHistoryWithoutSend`: read the three arguments with a
helper that fails naming the script; `awaitChannelList`, `awaitConnected`; assert the seeded reply is not on
screen; `openRow(name)`; wait (`REPLY_TIMEOUT_MS`) for the reply text anchored in a message bubble and assert
it is displayed. No `pullForOlderHistory`, no send. Zero Claude turns.

**Selector.** The method joins the LIVE `TEST_TARGET` list with a one-line comment.

## State and concurrency model

No production state. The seed runs synchronously before the daemon starts, which reads the registry once
and the log lazily on the first `request_history`.

## Error handling

A seed failure aborts the harness through `die` before any daemon spawns, as the collision seed does. The
seed never echoes file content. In the test, a missing argument fails with the script name; a missing reply
times out the wait.

## Testing strategy

- `scripts/test_e2e_emulator_two_host.py` gains tests for `seed_dormant_history`: the segment's name, header
  and three entries decode with the daemon's field names, ids 1–3 and the given texts; directory and file
  modes; another conversation's log survives; an existing history directory is refused. Run with
  `python3 -m unittest scripts/test_e2e_emulator_two_host.py`.
- The device method is a rung-3 live scenario (real daemon, relay and emulator), so it runs only in the
  dispatcher's live gate; the builder compiles it with `./gradlew compileDebugAndroidTestKotlin`.
- No rung-4 twin: the scripted path seeds nothing and the twin would prove only the reducer, which
  `ThreadViewModelTest` already covers.

## Documentation handoff

Pending for the documentation stage, in `docs/e2e-interactive-stream.md`:

- § "Live mode (rung 3, live relay)" and § "What rung 3 is made of": the new scenario and its dormant-history
  seed, including why it seeds a log rather than restarting the daemon.
- § "Verification status": the live result.
