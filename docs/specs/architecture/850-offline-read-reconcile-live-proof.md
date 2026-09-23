# #850 — Live proof: a loaded conversation stays readable offline and reconciles on reconnect

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_peerStartedTurn_continuesOnPhone`, `interactiveTurn_peerQueue_staysConsistentAcrossClients` — the #848/#849 peer scenarios this one mirrors (create, rename, peer setup, `inThreadList` counts); `cycleHostLink` — the phone-side cut/restore drive, readiness keyed on `currentRepository`, not `ConnectionState`; `hostConversationIds`, `renameOpenThread`, `scrollListTo`, `awaitChannelList`, `awaitConnected`.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt` → `open`, `sendMessage`, `awaitFrame(occurrence)` — frames are recorded from `open`, so `turn_end` occurrences count turns seen since then, whichever device started them.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/PingReplyAssertions.kt` → `pingReplyMatcher`, `awaitDisplayedPingReply` — reply detection anchored on `MESSAGE_BUBBLE_TEST_TAG`; the new reply matcher follows the same anchor.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/QueuedBacklogTest.kt` → the `boundsInRoot.top` comparison — precedent for asserting on-screen order.
- `scripts/e2e-emulator.sh` → the `daemon revision:` log in step 3 (only printed when `go` is on PATH) and the `LIVE` `TEST_TARGET` list and its PASS line.
- `scripts/android-test-gate.py` → `LIVE_MINIMUM`, the live gate's executed-test floor tied to the curated list.
- `docs/knowledge/features/caching-conversation-repository.md` § "The merge base moves at a connection boundary" — a disconnect keeps drawn *settled* rows; an in-flight streaming row is dropped. So the cut must follow the phone turn's `turn_end`, not merely the reply's first render.
- `docs/knowledge/features/conversation-cache.md` — list (#796) and thread (#797) restore; a thread is written to disk once its rows settle.
- `docs/e2e-interactive-stream.md` — rung vocabulary; § Live mode curated list and § Pre-ship gate (documentation stage edits these).

## Design source

N/A — test and harness only; no UI change.

## Context

#795–#798 made loaded content survive in the host-keyed cache and deferred their live proof to #673, which was split; this is that proof on rung 3. No production code changes. No ADR warranted.

## Design

One new `@Test` on `InteractiveStreamE2ETest`: `interactiveTurn_offlineRead_reconcilesPeerTurnOnReconnect`. **Two real-claude turns**: the phone's ping and the peer's offline turn.

1. **Setup (as #848).** Build a `SecondClientPeer` from the same instrumentation args and `open` it first, so every later `turn_end` is recorded. Phone: `awaitChannelList`, `awaitConnected`, `createChat`, find the new conversation id via `hostConversationIds`, rename to `OFFLINE_CHAT_NAME_PREFIX + timestamp` (`e2e850-`), a conversation the scenario creates.
2. **Load history.** Phone sends `PING_PROMPT` (`sendFromPhone`), `awaitDisplayedPingReply`, and the peer awaits `turn_end` occurrence 1 — the turn has ended and its rows are settled (and cached) before the cut.
3. **Cut phone-side.** A new helper `setHostLink(serverId, up: Boolean)` split out of `cycleHostLink`'s two halves: `supervisor.close()` then `currentRepository.first { it == null }` (and the reverse for `up`). `cycleHostLink` becomes the two calls — same behaviour, no duplicated body.
4. **AC-1 offline, three reads.**
   - In the still-open thread: `PING_PROMPT` and the ping reply each count exactly 1 in the thread list.
   - Back to the list: the chat row (`TREE_CHAT_ROW_TEST_TAG` + name) is reachable by scroll.
   - Reopen that row offline: the thread draws `PING_PROMPT` and the ping reply again — this read comes from the on-disk thread restore, not the in-memory rebase.
5. **AC-2, peer acts while phone is offline.** Peer `sendMessage(conversationId, OFFLINE_PROMPT)`, then `awaitFrame(conversationId, "turn_end", WAIT_TURN_TIMEOUT_MS, occurrence = 2)`. Then assert the phone draws **neither** `OFFLINE_PROMPT` nor the offline reply — the negative that shows the phone really was offline.
6. **Reconnect** (`setHostLink(serverId, up = true)`), thread still open. Wait for the offline reply bubble to display. Then after `waitForIdle`:
   - counts: `PING_PROMPT`, ping reply, `OFFLINE_PROMPT`, offline reply — each exactly 1 in the thread list;
   - order: `boundsInRoot.top` of ping reply < `OFFLINE_PROMPT` < offline reply.
7. `finally { peer.close() }`, as #848/#849. The link is up again on the success path; no extra restore on a failure path, as `cycleHostLink`'s callers have none either.

New constants: `OFFLINE_PROMPT = "Reply with exactly: pyryoffline"`, `OFFLINE_REPLY = "pyryoffline"`, `OFFLINE_CHAT_NAME_PREFIX = "e2e850-"`. The offline reply matcher: exact text (ignore case) with a `MESSAGE_BUBBLE_TEST_TAG` ancestor, as `pingReplyMatcher` — the prompt's text is not exactly the token, so it cannot match.

### Harness (AC-3, AC-4)

- `scripts/e2e-emulator.sh` step 3: always log `mobile revision: <git -C REPO_ROOT rev-parse HEAD | unavailable>` and `daemon revision: <vcs.revision | unavailable>`; the `go` check narrows to computing the value instead of gating the line. Applies in every mode.
- `scripts/e2e-emulator.sh`: append the method to the `LIVE` `TEST_TARGET`, update the counts in the header and the list comment (thirteen methods, eight turns), and the PASS line.
- `scripts/android-test-gate.py`: `LIVE_MINIMUM = 13`.

## State + concurrency model

Test-thread only: `runBlocking` + `withTimeout` around suspend reads, as the rest of the class. No production state touched.

## Error handling

Timeouts fail the test with the waiting step's name in the stack. Reconnect may replay the event ring or re-fetch history; the checks are on what is drawn, either way.

## Testing strategy

This is the test. Compile via `./gradlew compileDebugAndroidTestKotlin`; `bash -n` on the script; `python3 -c` import check on the gate. The rung-3 run needs real claude and the peer token, so it runs in the dispatcher's post-verifier live gate (`needs-real-claude`), not here. No rung-4 twin: the offline turn is started by a second device, and the scripted harness has no peer.

## Documentation handoff (pending — documentation stage)

- `docs/e2e-interactive-stream.md`: add the scenario to the rung-3 list and § Live mode's curated list; § Pre-ship gate's executed-test count (13) and turn cost (8); say the run prints `mobile revision:` and `daemon revision:` in step 3 of `scripts/e2e-emulator.sh`, `unavailable` when unknown.
- `docs/knowledge/features/conversation-cache.md` (and the #673 mentions in `caching-conversation-repository.md`): point the live proof of offline reading at #850.

## Open questions

- Does the thread screen stay composed across the cut, or does an offline banner displace the list? Resolved in Phase B by the checks themselves; record in Revisions if it changes the drive.

## Revisions

### 2026-09-23 — cut on the phone's own settled reply (verifier MUST FIX, PR #860)

**Finding.** Step 2 waited only on the peer's `turn_end`. The daemon sends that frame to each interactive connection separately, so the peer's copy can arrive before the phone's; `HistoryPageReducer.withFinalizedTurn` settles the phone's row only on the phone's copy. A cut in between drops the still-streaming reply (`settledThreadRows`), and the offline reads find nothing.

**New contract.** After the peer's `turn_end` (kept: step 5's `occurrence = 2` count depends on it), step 2 waits in a new `awaitCachedAssistantReply(serverId, conversationId)` until the phone's `ConversationCache.readThread` holds an assistant `ThreadItem.MessageItem`. The cache holds only settled rows, and `CachingConversationRepository.observeMessages` writes them only after it has recorded the drawn rows it rebases on at a disconnect, so this one signal covers both the in-memory offline read and the reopened thread's disk restore. The verifier suggested waiting on the host repository's `observeMessages` instead; the cache is chosen because the thread collector's `StateFlow` input is conflated, so a settled store value does not prove the thread's collector saw it before the cut's empty emission. Polls every `CACHE_POLL_MS` inside `THREAD_TIMEOUT_MS`.

### Open question resolved

Not resolved by a run yet: the drive assumes the thread stays composed across the cut, and the offline reads in steps 3–4 fail directly if it does not. The post-verifier live run is the first to answer it.

### 2026-09-23 — count after reconnect only once the prompt is drawn too (live gate FAIL, 13 executed / 1 failed)

**Finding.** The first live run passed every offline read, then failed step 6: `inThreadList(OFFLINE_PROMPT)` counted 0 right after the offline reply displayed. The reply and the prompt reach the phone by different paths. The daemon's ring replay on the new connection carries the reply, but no live frame carries another device's message text (the #848 finding). The prompt arrives only through the newest-page `request_history` that `ThreadViewModel` re-issues when the connection returns. In the run's `daemon.log`, the phone's new connection completed its handshake at 07:09:59.4, and the next test began at 07:10:01. No history page was served for the scenario's conversation after the reconnect. Earlier in the same run, a history page took about 0.9–1.3 s after a handshake, so the assertion ran before the history ask was answered.

**New contract.** Step 6 waits, inside `REPLY_TIMEOUT_MS`, until the offline reply is displayed **and** `OFFLINE_PROMPT` is drawn in the thread list, then runs the unchanged exact-once counts and the order check. If the reconnect never re-asks for history, the failure is now this wait timing out, which would be a product finding rather than a test race.

**Also.** `scripts/e2e-emulator.sh` now ends the `go version -m` pipeline in `|| true`, so a failing `go` prints `daemon revision: unavailable` instead of stopping the script under `set -e` (a non-blocking verifier note).

### Open question resolved (2026-09-23, first live run)

The thread stays composed across the cut. The first live run passed steps 3–5 (open-thread read, list row, reopened thread from the disk restore, offline negative), so the drive holds as written.
