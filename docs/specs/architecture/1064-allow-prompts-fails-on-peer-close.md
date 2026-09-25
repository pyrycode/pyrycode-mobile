# #1064 — `allowPromptsUntil` fails at once when the peer closes

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `allowPromptsUntil` and its ten callers. It polls `SecondClientPeer.recorded` inside a bare `withTimeout`, so a peer that can no longer answer is found only after the full timeout.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt` → `awaiting`, `closed`, `linkState`, `recorded`. #1059 added `awaiting`, the peer's close-aware wait, as a private helper over `awaitPeer`.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/PeerWait.kt` → `awaitPeer`, the fail-fast itself. A timeout stays a `TimeoutCancellationException` (#1059's Revisions), so `allowPromptsUntil`'s catch still names its step.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/RedialingLink.kt` → `describe`. The link-state text the timeout now carries.
- `app/src/test/java/de/pyryco/mobile/e2e/PeerWaitTest.kt` → the JVM proof of `awaitPeer`.
- `docs/specs/architecture/1059-peer-waits-fail-on-close.md` → Context and Revisions. The peer's session closes for good only when the test closes it; a dropped link redials (#1036), and the daemon re-sends pending `modal_shown` to the new link.
- Overlap: #1085 also edits `InteractiveStreamE2ETest.kt` (a new scenario, not `allowPromptsUntil`). A later merge may touch the file.

## Design source

N/A: test-harness change, nothing UI-visible.

## Context

Both recorded flakes of `interactiveTurn_backgroundTask_countsInActionsMenuAndPanel` ran on trees without #1036 (`b21e25a8c4` for #1017 and #1020, `5ff587db7f` for #1067; checked with `git merge-base --is-ancestor`). On those trees the peer dialed once, so a relay drop 33 ms after the handshake left it dead. Since #1036 the peer redials, so that drop should now recover rather than time out. The relay fix (pyrycode/pyrycode-relay#154) removes the drop itself.

What is left for this ticket is AC 2. As #1059 established, "the peer's session closes" means the peer was closed (`SecondClientPeer.close`), not that one link dropped. Failing on a single link drop would bring the flake back.

## Change

`SecondClientPeer.awaiting` goes from `private` to `internal`, so the scenario can reuse #1059's closed-session signal instead of adding a second one. `allowPromptsUntil` runs its poll loop through `peer.awaiting(frame, timeoutMs) { … }` instead of `withTimeout(timeoutMs) { … }`. A new parameter, `frame: String = "turn_end"`, names the frame the caller waits for. Eight callers wait for `turn_end` and keep the default. The two background-task calls pass `"background_task_started"` and `"background_task_updated"`. A peer closed during the wait now fails at once with `peer session closed while awaiting <frame>`. A peer that stays open behaves as before. The same timeout values apply, and a timeout is still caught and rethrown with `failure`. That timeout message also gains `peer.linkState()`, so if the drop comes back it says whether the peer was redialing. No timeout constant changes.

## Testing strategy

`PeerWaitTest` already proves the fail-fast, the unchanged timeout and pass-through. One added case covers the loop `allowPromptsUntil` runs: a block that polls a snapshot and `delay`s between polls. When the peer closes, it fails at once and names the frame. `compileDebugAndroidTestKotlin` checks the wiring. AC 3, the live pass with relay#154 deployed, is the dispatcher's post-verifier real-claude gate. A focused device run needs the live relay and claude, so it is not possible here.

## Documentation handoff

None named by the ticket.
