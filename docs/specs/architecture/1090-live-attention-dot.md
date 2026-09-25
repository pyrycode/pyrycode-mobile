# #1090 — rung 3: a conversation's attention dot follows a real turn

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`
  - `interactiveTurn_peerStartedTurn_continuesOnPhone` (#848) — a `SecondClientPeer` sends the ping, so the phone never has to open the thread.
  - `interactiveTurn_permissionAnswer_reachesOnlyTheAskingConversation` (#966) — the answer daemon and its peer: `answerHostPeer`, `pairAnswerHost`, `answerChat`, `awaitTurnEnd`, `ANSWER_PERMISSION_PROMPT`, `SCOPE_SETTLE_MS`, and the `finally` that closes the peer and removes the answer host.
  - Helpers reused as they are: `openChatRow`, `leaveThread`, `scrollListTo`, `string`, `peerStep`.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt` → `sendMessage`, `awaitPermissionModal`, `allowOnce`, `awaitModalDismissed`, `awaitFrame` — every peer step the scenario needs already exists.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRows.kt` → `TreeConversationRow`, `ConversationStatusDot`, `descriptionRes` — the dot sets its state's description with `clearAndSetSemantics`, and the row's `selectable` merges it with the name, so a row's merged node carries both.
- `app/src/main/java/de/pyryco/mobile/di/ConversationAttention.kt` → `HostAttentionState.onEvent`, `completed`, `opened`, `resolve` — Unread comes from a `turn_end` counted while the conversation is not viewed; opening it reads it; WaitingForAnswer comes from the host's open modal's `conversation_id`, and outranks everything else.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt` → `HostConversationSource` — the modal is the host connection's, not the open thread's, so a prompt for an unopened chat still marks its row.
- `../pyrycode/docs/protocol-mobile.md` § Modal — `modal_shown` is delivered to every attached client, and viewing it is ungated.
- `scripts/e2e-emulator.sh` (LIVE `TEST_TARGET`) and `scripts/android-test-gate.py` (`LIVE_MINIMUM`) — where the method registers.
- `docs/knowledge/features/channel-list-screen-tree-and-controls.md` § Attention dot — the state-to-description table the scenario reads.

In-flight overlap: #1076 appends a live method to the same test class and both scripts, and #1102 edits the test class. Neither is a dependency; my edits are appends, so a later merge may touch those lines.

## Design source

N/A — test and script only; no UI changes.

## Context

#877 derives each conversation's attention and #878 draws it as the tree row's dot, both proven only against fakes. This adds one live rung-3 scenario, split from #676. No ADR needed.

## Design

### Scenario: `interactiveTurn_attentionDot_followsARealTurn`

The whole scenario runs on the #966 answer daemon, with its peer. That gives one host and one peer for both halves: the peer's ping and the peer-held prompt. The phone never opens a thread except to read A.

1. **Setup.** `answerHostPeer`, `pairAnswerHost`; two chats from `answerChat` with the prefix `ATTENTION_CHAT_NAME_PREFIX` (`e2e1090-`, no "ping"), A and B; open the peer. The phone is on the list.
2. **Both start Idle.** Wait for A's and B's rows to read Idle. Take a snapshot of every composed tree row as (name, state) pairs.
3. **AC-1: a completed turn marks only its row.** The peer sends `PING_PROMPT` into A and waits for A's `turn_end`. A's row reads Unread. A second snapshot differs from the first only in A's row: for every other name present in both, the states are the same. B is among them and must read Idle.
4. **AC-1: opening reads it.** `openChatRow(A)`, `leaveThread`, and A's row reads Idle.
5. **AC-2: a held prompt marks its row Waiting.** The peer sends `ANSWER_PERMISSION_PROMPT` into B and waits for its `modal_shown` (`awaitPermissionModal`). B's row reads "Waiting for your answer". After `SCOPE_SETTLE_MS` with nothing answered, B still reads Waiting and A still reads Idle.
6. **AC-2: answering ends it.** The peer allows once and waits for the dismissal and B's `turn_end`. B's row reads Unread, and no longer Waiting.

`finally`: close the peer and remove the answer host, as #966 does.

Running is never asserted. It is transient on a ping, like the thinking spinner. Every state is read from the dot's content description, never from its colour.

### Helpers (private, in the test class)

- `attentionRow(name, state)` — a matcher: `TREE_CHAT_ROW_TEST_TAG`, the name as a substring, and the state's description.
- `awaitRowAttention(name, state, what)` — scrolls to the row and waits until it reads `state`; on timeout it fails naming `what` and the state the row does read.
- `treeRowAttention()` — the (name, state) pairs of every composed Channels and Chats tree row. A row's state is whichever of the five descriptions its merged node carries.

Names are compared as-is and repeated names are grouped, so two rows with one name on different hosts still compare as a multiset of states.

### Harness

- `scripts/e2e-emulator.sh`: append the method to the LIVE `TEST_TARGET` list with a comment naming its two turns.
- `scripts/android-test-gate.py`: `LIVE_MINIMUM += 1` with a comment naming #1090.

## State + concurrency model

Test code only. Peer calls run in `runBlocking` or `peerStep` with explicit timeouts, as in the existing methods.

## Error handling

Every wait fails with a named step. A row that never reaches the expected state reports the state it does show. An unmet answer-daemon prerequisite fails in `answerHostPeer` with its name, never a skip.

## Testing strategy

The scenario is the test. It runs on rung 3 under `LIVE=1` and in `python3 scripts/android-test-gate.py live`, which the dispatcher runs after verification. I compile it with `./gradlew compileDebugAndroidTestKotlin`. It spends **two real-claude turns**: A's ping and B's allowed command. No rung-4 twin: the answer daemon's stdio prompt surface has no scripted counterpart, and the shared Compose tests already cover each state's description.

## Open questions

- Does a `turn_end` for an unopened conversation on the answer daemon carry a turn id? It must, or Unread never lands. #685's background push proof says it does; the live run confirms it.

## Documentation handoff

Pending for the documentation stage:

- `docs/e2e-interactive-stream.md` § Live mode (rung 3, live relay) and § Pre-ship gate: add the scenario and its two turns to the LIVE inventory.
- `docs/knowledge/features/channel-list-screen-tree-and-controls.md` § Attention dot: point the Scope line at `interactiveTurn_attentionDot_followsARealTurn`.
