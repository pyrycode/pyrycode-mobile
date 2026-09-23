# #849 — phone replies, queued sends and drops stay consistent with another client

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_peerStartedTurn_continuesOnPhone` (the #848 setup this scenario copies: create, resolve id via `hostConversationIds`, `renameOpenThread` before any message), `TOOL_PROMPT` (the #481 shell-tool lever), `scrollListTo`, `twoHostArg`, `ARG_PEER_TOKEN`.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt` → `sendMessage`, `awaitFrame`, the recorded `received` list — the peer gains a queue read, a dequeue and an Nth-frame wait.
- `app/src/main/java/de/pyryco/mobile/data/network/InteractivePayloads.kt` → `QueueStatePayloadDto`, `QueuedMessageDto` — the peer decodes `queue_state` with the app's own DTOs.
- `app/src/main/java/de/pyryco/mobile/data/network/DequeueMessagePayloadDto.kt` → `DequeueMessagePayloadDto` — the peer's `dequeue_message` payload.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → `dropQueuedMessage` — the daemon acks a dequeue, and the phone removes the echo only on that ack; the scenario's "disappears from the phone" relies on it.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/QueuedMessageRow.kt` → `QueuedMessageRow` — merged row carrying `stateDescription = thread_queued_state_desc` ("Waiting to send"); the drop `IconButton` is its own node with `cd_thread_queued_drop`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadRow.kt` → `foldQueuedRows` — a matched phone send draws once in place; another device's item draws as an unmatched plain queued row at the tail.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadInputBar.kt` → `ThreadInputBar` — while busy with an empty composer the button is Stop; it becomes Send only once text is typed, so the scenario types first and waits for Send.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/QueuedBacklogTest.kt` → how existing device tests address the drop control.
- `scripts/e2e-emulator.sh` → LIVE curated `TEST_TARGET` and its comments; `scripts/android-test-gate.py` → `LIVE_MINIMUM`; `scripts/test_android_test_gate.py` asserts `LIVE_MINIMUM` equals the curated method count.
- `../pyrycode/docs/protocol-mobile.md` § Queue (v2) (`queue_state` fan-out to every interactive connection, multi-device `message_id` rule, `dequeue_message`), § Interactive events (`tool_use`, `turn_end` carry `conversation_id`).
- `docs/specs/architecture/848-second-client-peer.md` — the peer's design and its security review (token never logged, in-memory stores); unchanged here.

## Design source

**Figma:** N/A — test infrastructure only; no UI changes.

## Context

#848 proved a turn a second client starts continues on the phone. This ticket proves the reverse traffic and the shared backlog: the phone replying in a peer-started conversation, queueing behind a running turn, and dropping a queued message, with the peer's view agreeing at each step — plus the peer's own queued item seen and cleared on the phone. Zero production files change.

## Design

### Peer additions (`SecondClientPeer`)

```kotlin
suspend fun awaitFrame(conversationId: String, type: String, timeoutMs: Long, occurrence: Int = 1): Envelope
suspend fun awaitQueue(conversationId: String, timeoutMs: Long, ready: (List<QueuedMessageDto>) -> Boolean): List<QueuedMessageDto>
suspend fun dequeueMessage(conversationId: String, queuedMsgId: Long, timeoutMs: Long)
```

- `awaitFrame` gains `occurrence` (default 1, so #848's call is unchanged): waits for the Nth recorded frame of that type for the conversation.
- `awaitQueue` decodes the **latest** recorded `queue_state` for the conversation with `QueueStatePayloadDto` (null `queued` → empty) and waits until `ready` holds. Latest, not any: a snapshot is full state, so an older one would answer for a backlog that has since changed.
- `dequeueMessage` sends `dequeue_message` with `DequeueMessagePayloadDto` and awaits the `ack` by `in_reply_to`; an `error` throws naming only its `code`. The send-and-await-reply step is shared with `sendMessage` through one private helper.

### Scenario: `interactiveTurn_peerQueue_staysConsistentAcrossClients`

1. Setup as #848: channel list, connected, `createChat`, resolve the new id, rename to `e2e849-<millis>`, open the peer.
2. Peer sends `WAIT_PROMPT` (run `sleep 90` in the foreground with the shell tool, then reply exactly `pyrywait`) — the peer starts the conversation. Peer awaits the conversation's `tool_use`: turn 1 is now inside the sleep, the window the queue steps run in.
3. **AC2 queue.** Phone types `PING_PROMPT`, waits for Send (not Stop), sends. Phone shows a queued row (`hasText(PING_PROMPT)` + state description "Waiting to send"); peer `awaitQueue` holds an item with that text.
4. **AC3 phone drop.** Phone sends `DROP_PROMPT` ("Reply with exactly: pyrydropped"); queued on phone and in the peer snapshot. Phone taps the drop control under that row. Phone: no node with `DROP_PROMPT` text in the thread list; peer: latest snapshot lacks it and still holds the ping item.
5. **AC3 peer drop.** Peer sends `PEER_QUEUED_PROMPT` ("Reply with exactly: pyrypeerdropped"); peer snapshot yields its `queued_msg_id`. Phone shows it as a queued row. Peer `dequeueMessage`; phone: the text is gone from the thread list; peer snapshot lacks it.
6. **AC1 + AC2 drain.** Peer awaits the second `turn_end` (turn 1 ended, the ping drained and ran) and a latest snapshot that is empty. Phone: ping reply displayed, exactly one ping reply, exactly one `PING_PROMPT` node in the list, no queued row for it.
7. **No reply to the drops.** The backlog is empty after the drain, so nothing dropped can reach claude; the phone also carries no node with exact text `pyrydropped` or `pyrypeerdropped`.
8. `finally`: `peer.close()`.

The prompt count uses #848's list matcher (`hasText(...) and hasAnyAncestor(hasScrollToNodeAction())`, unmerged tree) so a message drawn once as a bubble and once as a queued row would count twice. The queued-row matcher reads the merged tree, where the row's text and state description sit on one node. The drop control is `hasContentDescription(drop) and hasAnyAncestor(queuedRow(DROP_PROMPT))`, scrolled to before the click.

**Two real Claude turns**: the peer's wait turn and the phone's drained ping. The sleep length (90 s) is the window for steps 3–5; waits on turn 1's end use a longer `WAIT_TURN_TIMEOUT_MS` (240 s).

### Harness

- `scripts/e2e-emulator.sh`: append the method to the LIVE curated `TEST_TARGET` (12 methods, 6 turns); refresh the adjacent comments and header count.
- `scripts/android-test-gate.py`: `LIVE_MINIMUM = 12`. `test_android_test_gate.py` already ties the floor to the curated count, so it stays green without edits.

## State + concurrency model

No production state. The peer's recorder scope is unchanged; every new wait runs under `withTimeout`, called from the test inside `runBlocking`, the class's idiom.

## Error handling

Peer failures stay category-only (`IllegalStateException` with an error `code`); waits fail as `TimeoutCancellationException`. A red on a phone-side assertion is a product finding to file, not a harness fix.

## Testing strategy

- `./gradlew compileDebugAndroidTestKotlin` proves peer and scenario compile.
- `python3 -m unittest scripts/test_android_test_gate.py scripts/test_e2e_emulator_gradle.py` proves the floor matches the curated list.
- The scenario spends real Claude turns, so it executes only in the dispatcher's post-verifier `python3 scripts/android-test-gate.py live` (`needs-real-claude`). No rung-4 twin: the scripted `fakeclaude` harness has no second-token seam (#848's finding), and the ticket asks for none.

## Documentation handoff (pending — documentation stage)

`docs/e2e-interactive-stream.md`: add the scenario to the rung-3 list and § Live mode's curated list; update § Pre-ship gate's executed-test count (12, `LIVE_MINIMUM` 12) and turn cost (six turns: four pings, the peer wait turn and the drained ping — see the script comment); change the sentence that points phone-reply continuity at #673 so it names #849.

## Open questions

- Whether real claude runs `sleep 90` in the foreground without a permission modal on the operator's live config: #481's shell tool passes live, so expected yes; a modal or a backgrounded sleep is a live-environment finding.

## Revisions

### 2026-09-23 — the wait is not a bare `sleep` (verifier MUST FIX, PR #858)

The Open Question resolved in the negative. Claude Code's Bash tool refuses a command whose leading segment is `sleep N` with N ≥ 25 unless it runs in the background. The check runs in input validation, before permission checks, whenever the Monitor feature is on, and the daemon does not turn background tasks off. A bare `sleep 90` would end the peer's turn at once or background it, and the queue window of steps 3–5 would be gone.

New contract: `WAIT_PROMPT` asks claude to run `python3 -c "import time; time.sleep(90)"` in the foreground, not in the background, then reply exactly `pyrywait`. The window stays 90 s, the turn count stays two, and every other step is unchanged. The constant's comment names why the command is not a bare `sleep`. The verifier's NIT on the peer methods' `internal` stays as is: `awaitQueue` exposes the app's `internal` `QueuedMessageDto`, so a public signature does not compile, and `dequeueMessage` keeps the same visibility beside it.

### 2026-09-23 — `dequeue_message` has no reply (live gate FAIL, bfa1cf4)

The first live-gate run failed at step 4: after the phone's drop, the dropped text stayed in the thread list for 30 s. The daemon log shows the dequeue removed within 100 ms. The daemon never replies to `dequeue_message`: `handleDequeueMessage` says "no reply and no broadcast", and convergence is the next `queue_state`. The phone's `dropQueuedMessage` removes its own echo only after an `ack`, so the echo stays as a delivered bubble. That is a product bug, filed as #859 and out of this ticket's scope.

New contract:
- `SecondClientPeer.dequeueMessage(conversationId, queuedMsgId)` is fire-and-forget and non-suspending, with no timeout parameter. Callers observe the removal through `awaitQueue`. `sendMessage` and `dequeueMessage` share one private `send` helper.
- Step 4 waits until the phone has no queued row for `DROP_PROMPT`, instead of waiting until the thread list has no node with that text. Step 7 asserts a zero queued-row count for `DROP_PROMPT` instead of a zero `inThreadList` count. A comment in step 4 names #859 and the two assertions to restore once it lands. The phone-side "disappears" half of AC3 is therefore proven for the queued row only until #859 is fixed. Every other assertion is unchanged.

The same run also failed `interactiveTurn_newSession_rendersSessionBoundaryDelimiter`. `awaitDisplayedSessionBoundary` resolved `onNode(hasScrollAction())` while the overflow menu's scrollable was still in the tree after its item was tapped, so it found two nodes. The helper now matches `hasScrollToNodeAction()`, which only the thread's lazy list carries. This is a one-line fix to a shared test helper, not a production change.

### 2026-09-23 — a permission prompt holds the turn, and the peer allows it (live gate FAIL, e80b86a)

The second live-gate run passed steps 3–5 and then timed out in step 6, waiting 240 s for the second `turn_end`. Claude's transcript shows the `python3` sleep's `tool_use` and no result until the daemon shut down, when the approval MCP resolved it as a deny. The command had raised a permission prompt. The harness pairs every device without `--allow-remote-permissions`, so no device could answer it, and the peer's turn never ended. The queue steps passed only because the pending prompt held the turn open.

New contract:
- The pending permission prompt is the hold, not the command's run time. `WAIT_PROMPT` asks for a quick `python3 -c "print(849)"`. A `python3` command is never auto-allowed, and the operator's claude settings carry no allow rule for it.
- `scripts/e2e-emulator.sh` pairs the peer alone with `--allow-remote-permissions`. The phone stays unprivileged, as every other scenario expects. The token is still never logged.
- `SecondClientPeer.awaitPermissionModal(conversationId, timeoutMs): String` decodes the conversation's first `modal_shown` with `ModalShownPayloadDto`, checks for class `permission` and an `allow_once` option, and returns the `modal_id`.
- `SecondClientPeer.allowOnce(modalId, timeoutMs)` sends `modal_answer` with `ModalAnswerPayloadDto` (`allow_once`, a fresh UUID answer token) and waits for a `modal_dismissed` naming that id, which must carry outcome `allow_once` and source `remote`. The daemon sends no reply to `modal_answer` and silently ignores one from an unprivileged device, so the dismissal is the only confirmation.
- Step 2 awaits the permission modal instead of `tool_use`. Step 6 starts with `allowOnce`, then waits for the second `turn_end` as before. Every other step and assertion is unchanged, and the scenario still spends two real Claude turns.
