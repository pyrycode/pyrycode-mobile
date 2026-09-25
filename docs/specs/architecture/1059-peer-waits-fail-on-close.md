# #1059 — Second-client peer waits say why they ended

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt` → `awaitFrame`, `awaitDismissal`, `awaitQueue`, `uploadAttachment`, `retrieveAttachment`, `exchange`, `answerAwaitingDismissal`, `close`, `linkState` — the seven `withTimeout` waits this ticket routes through one helper.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/RedialingLink.kt` → `RedialingLink.describe`, `RedialingLink.request` — since #1036 a dropped link is replaced, and waits carry across the replacement by design.
- `app/src/test/java/de/pyryco/mobile/e2e/RedialingLinkTest.kt` — the JVM test pattern for sharedTest e2e helpers that need no relay.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_backgroundTurnEnd_pushPostsOneAlertThatOpensThread`, `peerStep` — the flaky test calls the peer waits bare, so its timeout named nothing.

## Context

The ticket was written against `6380a0d0`, where the peer dialed once and never redialed. #1036 (PR #1056, now on main) changed that: `SecondClientPeer` dials through `RedialingLink`, a link counts as up only once it answers `list_conversations`, and the daemon re-sends pending `modal_shown` to the new link. Every recorded flake of this test ran on a tree without #1036 (`afc5b3cde4`, `b21e25a8c4`).

So the AC's "when the peer's session closes, any pending wait fails at once" cannot mean "when one link drops": that is exactly the event #1036 now survives, and failing on it would bring back the flake. Read against the current code, the session closes for good only when the test closes the peer. The intent that survives — a wait that ends because the peer is gone says so and names its frame, instead of an anonymous `Timed out waiting for 90000 ms` — is met by two behaviours:

1. A wait still pending when the peer is closed fails at once, saying the peer's session closed and naming what it awaited.
2. A wait that runs out its timeout fails naming what it awaited and the peer's link state (`linkState()`: open on link N / replaced M×, or "no open session: link N ended (…); redialing"). A later occurrence of the relay drop then names itself even when the redial does not recover in time.

Waits on a peer that stays open return exactly as before; no timeout constant changes.

## Design

New file `app/src/sharedTest/java/de/pyryco/mobile/e2e/PeerWait.kt`, next to `RedialingLink` so a JVM test can drive it:

```kotlin
/** Run [block] for up to [timeoutMs]; fail at once when [closed] turns true, and name [what] either way. */
internal suspend fun <T> awaitPeer(
    what: String,
    timeoutMs: Long,
    closed: StateFlow<Boolean>,
    linkState: () -> String,
    block: suspend () -> T,
): T
```

- Inside `withTimeout(timeoutMs)`, a `coroutineScope` runs [block] alongside a watcher child that waits for `closed` to be true and then throws `AssertionError("peer session closed while awaiting $what")`. The child's failure cancels [block] and `coroutineScope` rethrows it.
- A `TimeoutCancellationException` from that `withTimeout` becomes `AssertionError("peer awaited $what for $timeoutMs ms; ${linkState()}", cause)`.
- [block]'s own failures (a `check` naming a refusal code, `RedialingLink.request`'s not-resent error) pass through unchanged.

`SecondClientPeer`:
- `closed` becomes a `MutableStateFlow<Boolean>`; `close()` flips it with `compareAndSet` before tearing down, so pending waits fail rather than hang.
- Each of the seven `withTimeout(timeoutMs) { … }` waits becomes `awaitPeer("<what>", timeoutMs, closed, ::linkState) { … }`. `what` names the frame type (and occurrence where > 1) or the request's reply (`reply to peer send_message`); never ids or payload text, matching the class's content-free failure rule.
- `open` is untouched: it already fails on a session that closes during the handshake.

`InteractiveStreamE2ETest` is not changed. `peerStep` still names the step for its `TimeoutCancellationException`s; a converted wait now throws an `AssertionError` that names its own frame and link state, which `peerStep` lets through.

## State + concurrency model

The watcher is a child of the caller's `coroutineScope`, cancelled when [block] returns; no job outlives the call. `closed` is a hot `StateFlow`, so a close before the wait starts fails it immediately.

## Error handling

Test-only code. Every wait ends in its value, the block's own error, "session closed while awaiting X", or "awaited X for N ms; <link state>".

## Testing strategy

JVM unit test `app/src/test/java/de/pyryco/mobile/e2e/PeerWaitTest.kt` (`runTest`, virtual time):
- a block that answers returns its value, and the watcher leaves nothing running;
- flipping `closed` mid-wait fails at once (no virtual time passes to the timeout) with an `AssertionError` naming the frame and "closed";
- `closed` already true fails before the block answers;
- a wait that runs out throws an `AssertionError` naming the frame, the timeout and the link-state text, caused by the timeout;
- a block's own failure passes through unchanged.

`compileDebugAndroidTestKotlin` proves the peer's call sites. The live proof (AC 4) is the dispatcher's post-verifier real-claude gate once pyrycode/pyrycode-relay#154 is deployed; no focused device run is possible without the live relay and claude.

## Documentation handoff

None named by the ticket.

## Open questions

None.
