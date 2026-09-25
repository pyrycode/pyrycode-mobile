# #1063 — `assertPeerAnswers` labels a closed peer session

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `assertPeerAnswers`: catches only `TimeoutCancellationException` from `SecondClientPeer.history`. Its body moves to the helper below.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/PeerWait.kt` → `awaitPeer`: since #1059 a wait pending when the peer is closed throws `AssertionError("peer session closed while awaiting $what")`. This is the error shape the ticket says to match.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt` → `history`, `exchange`, `send`: `history` goes through `exchange`, which uses `awaitPeer`. It no longer calls `send`, the function that threw the bare `IllegalStateException: peer session is not open` on `feature/1017`.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/RedialingLink.kt` → `RedialingLink.request`: since #1036 a link the relay drops is redialed, and `request_history` (`resend = true`) is sent again on the new link.
- `app/src/test/java/de/pyryco/mobile/e2e/PeerWaitTest.kt`: the JVM test pattern, with virtual time.
- `docs/specs/architecture/1059-peer-waits-fail-on-close.md` § Revisions: a timeout stays a `TimeoutCancellationException` so that `assertPeerAnswers` can still label it.

## Design source

N/A. This is a test-only change with nothing visible to users.

## Context

The ticket was refined against `feature/1017`. On that branch the peer dialed once, and `history` sent through `send`, which threw a bare `IllegalStateException` when the relay had already dropped the only link. Main now has #1036 and #1059, which change both of those:

- **Relay drop.** When the relay drops a link, the peer redials and the request goes out again. If the peer never gets a working link again, `THREAD_TIMEOUT_MS` runs out and `assertPeerAnswers` already labels that as "a relay or daemon fault". The bare `IllegalStateException` can no longer come out of this call.
- **Closed peer.** The session closes for good only when `close()` is called on the peer. A wait on a closed peer now fails at once with `awaitPeer`'s `AssertionError`, whether the peer was closed before the wait or during it. `assertPeerAnswers` lets that error through unchanged, so the message does not say "relay or daemon fault". Acceptance criterion 2 asks for that wording.

## Change

`awaitPeer`'s close failure gets a named type, `PeerSessionClosedError : AssertionError`, in `PeerWait.kt`. Its message does not change. The new sharedTest helper `requirePeerAnswer(timeoutMs, request)` runs the request and converts two failures:

- `TimeoutCancellationException` becomes the existing `AssertionError("the peer's open session answered no request within $timeoutMs ms: a relay or daemon fault", e)`.
- `PeerSessionClosedError` becomes `AssertionError("the peer's session closed before it answered a request: a relay or daemon fault", e)`.

Any other failure is rethrown unchanged, including a `check` that names a refusal code. Catching the named type rather than every `AssertionError` means a different assertion is never mislabelled. `assertPeerAnswers` becomes `runBlocking { requirePeerAnswer(THREAD_TIMEOUT_MS) { peer.history(conversationId, THREAD_TIMEOUT_MS) } }`. No timeout constant changes. A peer that answers returns exactly as before.

Branch `feature/1085` also edits `InteractiveStreamE2ETest.kt`, but not `assertPeerAnswers`. A later merge may touch that file.

## Testing strategy

Add JVM cases to `PeerWaitTest` with `runTest` and virtual time. Each one goes through `awaitPeer`, so each exercises the real error shape:

- A peer that answers returns the answer, and no virtual time passes.
- A peer closed during the request fails at once with an `AssertionError`. Its message contains "session closed" and "a relay or daemon fault".
- A peer that was already closed fails the same way.
- A timeout becomes the existing message, still at exactly `timeoutMs`.
- A refusal passes through unchanged.

`compileDebugAndroidTestKotlin` checks the call site. Acceptance criterion 3 is the live proof: once pyrycode/pyrycode-relay#154 is deployed, the dispatcher's real-claude gate runs after the verifier. That run needs the live relay and claude, so it cannot be done as a focused device run here.

## Documentation handoff

The ticket names no documentation work.
