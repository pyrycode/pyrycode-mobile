# #1581: live scenario for a reply that lands while another channel is open

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`:
  - `interactiveTurn_offlineRead_reconcilesPeerTurnOnReconnect`: the cut-and-restore, `assertDrawnOnce` and the
    top-to-bottom order check this mirrors.
  - `interactiveTurn_permissionHeldTool_statusAreaNamesRunningTool`, `runningToolPeer`: the #849 lever on the first
    test daemon. A `python3` command is never auto-allowed, and only the peer paired with `--allow-remote-permissions`
    can answer it, so the turn waits until the test releases it.
  - `interactiveTurn_peerQueue_staysConsistentAcrossClients`: `WAIT_PROMPT` and its exact-text reply matcher on
    `WAIT_REPLY` (#1558), which the prompt's own bubble never matches.
  - `interactiveTurn_permissionPrompts_heldPerConversation`: the daemon streams turn frames only for the conversation
    a message was last routed to. The phone sends nothing in B, so A's frames keep reaching it.
  - `answerChat`, `openChatRow`, `leaveThread`, `setHostLink`, `awaitCachedAssistantReply`, `awaitTurnEnd`,
    `assertRowOpensOwnThread`: reused as they are.
- `app/src/main/java/de/pyryco/mobile/data/repository/CachingConversationRepository.kt` (`observeMessages`): the
  thread cache is written only while a thread collects, so a reply that lands while A is closed is never cached.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` (`observeMessages`): collecting
  it sends `backfill_since`, so the test must not read the live projection that way. It would put A's history into
  the projection by request.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt` (`launchAttention`) and `ConversationAttention.kt`
  (`HostAttentionState.completed`): each `turn_end` the phone folds records its `turn_id` as the conversation's
  `ReadPosition.completedTurnId`. `readTurnId` moves with it only when the conversation is viewed. The positions are
  persisted through `ConversationCache.writeReadPositions`.
- `docs/specs/architecture/1572-open-thread-asks-newest-page-on-every-host-arrival.md`: the behaviour under guard.
- `scripts/e2e-emulator.sh` (the `LIVE` branch's `TEST_TARGET` list), `scripts/android-test-gate.py`
  (`curated_live_methods`, `LIVE_MINIMUM`) and `scripts/test_android_test_gate.py`
  (`test_live_curated_list_matches_the_runnable_methods`).

In-flight overlaps on `InteractiveStreamE2ETest.kt` are #1487, #1563 and #1571, and #1571 also overlaps on
`scripts/e2e-emulator.sh`. All of them edit other methods or append to the list, so this change stays additive.

## Context

#1572 makes an open thread ask for the newest history page each time it gains its host. Its proof is unit-level. This
is the rung-3 counterpart on real Claude: a reply that ends while its thread is off screen sits only in the
connection-scoped `ThreadProjection`. A reconnect discards it and the replay cursor is already past it, so only the
open's ask can bring it back.

## Design

One new always-on method, `interactiveTurn_offscreenReply_survivesReconnectThroughNewestPageAsk`, on the first test
daemon with the #849 peer:

1. Create chats A and B through `answerChat`, so no message routes to B. Open the peer.
2. Open A. Send `PING_PROMPT`, then wait for the drawn reply, the peer's first `turn_end` and the phone's cached
   assistant row.
3. Send `WAIT_PROMPT`. The peer waits for its permission modal, so the turn is held. Assert that A does not show the
   `WAIT_REPLY` bubble, then leave A and open B.
4. Assert that B is on screen, meaning B's name is shown and A's is not. The peer allows the prompt. Await the peer's
   second `turn_end` in A and keep its `turn_id`.
5. Wait until the phone's stored `ReadPosition` for A has that `turn_id` as `completedTurnId`, and assert that it is
   unread. This is the phone's own record that it folded the `turn_end`, and every frame before it on the ordered
   inbound stream, while A was not viewed. Assert that B is still on screen and that no `WAIT_REPLY` bubble exists.
6. Cut and restore the link with `setHostLink`.
7. Read `ConversationCache.readThread` for A. It must hold the ping's assistant row, which shows that the read is
   keyed right, and no assistant row whose text is `WAIT_REPLY`. The cache never shrinks, so this one read, taken just
   before the reopen, also covers the time before the cut.
8. Leave B and open A, with no other gesture. Wait for the `WAIT_REPLY` bubble. Each of the ping prompt, ping reply,
   `WAIT_PROMPT` and `WAIT_REPLY` must be drawn once, top to bottom in that order.

New private helpers, kept next to the method:

- `awaitUnreadCompletion(serverId, conversationId, turnId)`: polls `readReadPositions` until `completedTurnId` matches
  the id, then asserts that the position is unread.
- `assertNoCachedReply(serverId, conversationId, reply)`: the cache check in step 7.
- `assertShowingThread(name, other)`: the top-bar identity check from `assertRowOpensOwnThread`, without the
  navigation.

New constant: `OFFSCREEN_CHAT_NAME_PREFIX = "e2e1581-"`. The method joins the `LIVE` list in `scripts/e2e-emulator.sh`
with one comment line. `LIVE_MINIMUM` is counted from that list, so it rises with no further edit.

**Two real-Claude turns:** A's ping and A's held `python3` command.

## State and concurrency model

Test code only. The only timing that matters is that the cut comes after the phone has folded A's `turn_end`. Step 5
waits for the phone's own state, not the peer's copy, because the peer's `turn_end` can arrive first. If the cut came
before the phone had the turn, the reconnect's ring replay would deliver it into the new projection, and the test
would pass without #1572.

## Error handling

Each wait has a timeout that fails with a message naming its step, with no conversation text. The `finally` closes
the peer.

## Testing strategy

This ticket is the test. The device-only reason is that it needs real Claude, a host daemon and the emulator, which is
rung 3 by definition. No rung-4 twin is added. #1572's unit tests already prove the ask deterministically, and the
ticket asks only for the live counterpart.

Non-vacuity, by step:

- **Step 3:** the reply cannot be drawn before the leave, because the turn is held until the peer answers.
- **Steps 4 and 5:** B is shown when the prompt is answered and when the turn ends, and the phone recorded the
  completion as unread.
- **Step 7:** the cache does not hold the reply.

Without #1572, step 8 sees only the cached rows and times out.

Focused checks:

- `python3 -m unittest scripts/test_android_test_gate.py`, for the list and runnable-method equality.
- `./gradlew compileDebugAndroidTestKotlin`, `assembleDebug`, `lint`, `spotlessCheck`.

The live run itself belongs to the dispatcher's live gate. The PR lists the method under `## Live tests`.

## Documentation handoff

Pending for the documentation stage:

- `docs/e2e-interactive-stream.md`, beside the offline-read-reconcile entry: what the scenario proves (an off-screen
  reply survives a reconnect through the newest-page ask, #1572), its two real-Claude turns, and the live result that
  verified it.
