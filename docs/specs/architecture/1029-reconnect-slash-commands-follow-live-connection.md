# #1029 — reconnect_slashCommandsAndCompactStillWork follows the live connection

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_reconnect_slashCommandsAndCompactStillWork`, `hostRepository`, `cycleHostLink`, `setHostLink`, `answerChat`: the failing step and the helpers that snapshot one connection-scoped repository.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayConnectionSupervisor.kt` → `connect`, `close`, `runLoop`, `backoff`, `backoffBaseSeconds`, `jitteredBackoffMs`: the only automatic redial on the phone; a fresh loop's first drop waits 0.8–1.2 s, the second 1.6–2.4 s.
- `app/src/main/java/de/pyryco/mobile/lifecycle/LifecycleConnectionDriver.kt` → `onStart` / `onStop` / `onPushWake`: ruled out as the source; nothing flips the process lifecycle mid-test.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionRegistry.kt` → `reconcile`, `retry`, `retryHost`, `pairingStatus`: ruled out; a bundle is rebuilt only on a changed saved record, and production saves one only when pairing.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt` → `currentRepository`, `onConnection`, `teardownActive`: each connection gets its own repository; a drop replaces it.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` → `switchToLive`, `observeSlashCommandMenu`: what the app's screens use, and why the app itself follows a redial.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → `observeSlashCommandMenu` (a pure projection, no request) and the inbound collector's `finally` → `RelayRequests.failAllPending` (the #1021 18:22Z `connection torn down before reply`).
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionPump.kt` → `teardown`; `OkHttpRelayTransport` → `Listener.onClosed` / `onFailure`: the phone-side ways a fresh connection ends.
- `../pyrycode/internal/relay/v2session.go` / `v2session_handshake.go` → every `closeWith` site logs at Warn; `handlePeerClose` logs at Info. None appears in the failing windows.
- `../pyrycode-relay/internal/relay/phone_outbox.go` → `Enqueue` closes a phone with 1011, unlogged, when its 16-frame outbox overflows: one candidate for the drop, not confirmed.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/UnrecognizedRowSentinel.kt`: precedent for an e2e helper in `sharedTest`, compiled into both the JVM and device test sets.

## Design source

N/A — test-harness change; nothing the phone draws changes.

## Context

The failing step reads `hostRepository(serverId).observeSlashCommandMenu(chatId)`. `hostRepository` returns the host's current connection-scoped repository once. In all three kept failing windows the reconnect's fresh connection ends within about a second and the supervisor redials on its backoff schedule:

| Run | Reconnect handshake | Redial | Gap |
|---|---|---|---|
| #1016 18:00Z (`pyry-e2e.7fSiSf`) | 21:06:37.876 `a0148e6d` | 21:06:39.014 `e29b4f65` | 1.138 s |
| #1021 19:16Z (`pyry-e2e.WUdljT`) | 22:20:28.827 `ae92ace3` | 22:20:29.952 `ff8ac161` | 1.125 s |

The first connection serves nothing. The redial serves the open thread's history page (`in_reply_to=2`), so the app's screens followed it through `StableConversationRepository`. In the #1021 run the next test goes on to use `ff8ac161`, so it was healthy. The test stayed on the torn-down repository, whose menu projection never fills, for 30 s.

Quick 1 s and 2 s phone redials also appear in every passing run's log (`e9hWlW`, `fYQBzy`, `23LAsU`). A fresh connection dropping is routine on the live path. The daemon logged no close for any of them, so the phone or the production relay ended them. The kept artifacts cannot tell which: no phone logcat and no relay log are kept on the live path. The #1021 18:22Z variant, `connection torn down before reply`, is the same cause at an earlier step: `answerChat`'s one-shot requests were in flight on a connection that dropped (`pyry-e2e.jNywPr`, handshakes 21:28:52.059, 53.167, 55.630, the 1 s then 2 s schedule).

A user meets the drop as a one-second reconnect and recovers, because every screen reads through the switching facade. Only the harness holds a single connection's repository. The fix is in the harness.

## Design

A new file `app/src/sharedTest/java/de/pyryco/mobile/e2e/LiveConnectionReads.kt` with two generic helpers over a host's `StateFlow<R?>` of connection-scoped sources. They are generic in `R`, so the JVM test needs no `ConversationRepository` fake.

- `suspend fun <R : Any, T : Any> StateFlow<R?>.firstOnLive(select: (R) -> Flow<T?>, accept: (T) -> Boolean): T` returns the first non-null value `select` yields that `accept` takes, read from the source current when it arrives. It uses `flatMapLatest`, so a replaced source's flow is cancelled and the new one is read. A value is returned only if its source is still `value` at that moment. A value from a torn-down connection is never taken.
- `suspend fun <R : Any, T> StateFlow<R?>.callOnLive(replacementWaitMs: Long, block: suspend (R) -> T): T` runs `block` on the current source. When `block` throws, other than on cancellation, it waits up to `replacementWaitMs` for a different non-null source and runs again on it. If none arrives in time, it rethrows the original error. A failure on a connection that stays up still fails the test with its own cause.

Test edits, all in `InteractiveStreamE2ETest`:

- Step 2 of `interactiveTurn_reconnect_slashCommandsAndCompactStillWork` reads the menu with `bundle.coordinator.currentRepository.firstOnLive({ it.observeSlashCommandMenu(chatId) }) { it.rows.isNotEmpty() }` inside the same `withTimeout(THREAD_TIMEOUT_MS)` and the same `AssertionError` message. The assertion stays specific: the rows must come from the host's connection that is live when they arrive, after the reconnect. Every source the flow yields is post-reconnect, because `setHostLink(up = false)` waited for the pre-reconnect repository to go.
- `answerChat` runs its create-then-rename through `callOnLive(REDIAL_WAIT_MS)`. A retry after a dropped connection can leave one stray, unrenamed chat on the host. The scenario finds its own chat by the run-unique name, so the stray chat is harmless.
- `hostRepository` and its other callers are unchanged.

`REDIAL_WAIT_MS` is 10 s. That covers the supervisor's first three backoffs (1.2 + 2.4 + 4.8 s at most) plus a dial.

Overlapping in-flight branches: #955 and #1021 add scenarios to `InteractiveStreamE2ETest.kt`. The edits here are local to one step and one helper.

## State + concurrency model

Both helpers run on the caller's coroutine, the test's `runBlocking`, and are bounded by the caller's `withTimeout`. `flatMapLatest` cancels the previous inner collection. `callOnLive` rethrows `CancellationException`, including the outer timeout's, and never absorbs it. They launch no coroutines of their own.

## Error handling

- `firstOnLive` never throws on its own. A source that never publishes shows up as the caller's timeout, wrapped in the existing `AssertionError`.
- `callOnLive` rethrows the original exception when no replacement arrives within `replacementWaitMs`. A retry loop on a flapping host is bounded by the caller's `withTimeout`.

## Testing strategy

JVM unit test `app/src/test/java/de/pyryco/mobile/e2e/LiveConnectionReadsTest.kt` (`runTest`, plain `MutableStateFlow` sources). RED first: it does not compile until the helpers exist.

- `firstOnLive` follows a replaced source. The first source never publishes, the holder switches to a second source, and its accepted value is returned. This case is the flake: the old snapshot read hangs in exactly this setup.
- `firstOnLive` skips values `accept` rejects, and waits through a `null` holder.
- `callOnLive` retries on the replacement when the call fails and the source is replaced.
- `callOnLive` rethrows the original error when the source is not replaced within the wait (virtual time).

`./gradlew compileDebugAndroidTestKotlin` covers the edited scenario. The dispatcher's live gate (`needs-real-claude`) is the acceptance run. The drop is intermittent, so only live runs can show the flake is gone.

## Open questions

- What ends the fresh connection: the relay's unlogged outbox overflow close, a phone pump teardown, or an OkHttp failure? The kept logs cannot tell. Answering it needs phone logcat or the relay log kept on the live path. That is filed as #1039, not solved here.

## Documentation handoff

None named by the ticket.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. Menu rows still reach the test through the production `RemoteConversationRepository` projection. The helpers only pick which connection's projection to read and never parse daemon data. They live in `sharedTest`, which `app/build.gradle.kts` adds to the `test` and `androidTest` source sets only, never to `main`, so no release code changes.
- [Tokens, secrets] No findings. The helpers touch no pairing record, token or key. The `AssertionError` text is static.
- [File / storage] No findings. No file or storage access.
- [Android attack surface] No findings. No component, intent, deep link or push path is touched.
- [Crypto] No findings. The redial's fresh Noise handshake is the supervisor's, and it is unchanged. `callOnLive` never reuses a torn-down connection's session. It waits for a different source.
- [Network & I/O] SHOULD FIX (Phase B): `callOnLive` must retry only on a *different* source, never on the same one, so a failure on a live connection cannot become a request loop. The caller's `withTimeout` bounds retries on a flapping host. A retried `createDiscussion` can leave one stray test chat on the e2e host. That is harmless and named in the Design.
- [Logs] No findings. The helpers log nothing.
- [Concurrency] SHOULD FIX (Phase B): `callOnLive` catches `Exception`, so it must rethrow `CancellationException` first. Otherwise the outer `withTimeout`'s `TimeoutCancellationException` would be absorbed and the wait would outlive its bound. A unit case covers the rethrow of a non-replaced failure. `firstOnLive`'s identity check leaves a single-value TOCTOU: the source can be replaced just after its value is taken. The value still came from a connection that was live when it arrived, which is the assertion.
- [Threat model] OUT OF SCOPE: why the fresh connection drops, whether a hostile or overloaded relay's outbox close or a phone-side teardown, is #1039. The harness now tolerates the drop, as the app already did. #1039 keeps the drop visible and diagnosable instead of hidden.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-24
