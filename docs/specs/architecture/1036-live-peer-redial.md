# #1036 — Live e2e: the second client redials, and the peer scenarios name their waits

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt` → `SecondClientPeer` (`open`, `request`, `exchange`, `send`, `allowOnce`, `answerQuestion`, `awaitFrame`, `awaitPermissionModal`) — dials one `OkHttpRelayTransport` + `NoiseSessionPump` and never redials; every wait reads the shared `received` recorder.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_peerStartedTurn_continuesOnPhone`, `interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain`, `hostConversationIds`, `holdToolOnPermission`, `assertPeerAnswers`, `awaitTurnEnd`, `allowPromptsUntil` — the two flaky scenarios, the one-connection phone read, and the callers that catch the peer's `TimeoutCancellationException` (their behaviour must not change).
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/LiveConnectionReads.kt` → `firstOnLive`, `callOnLive` — #1029's follow-the-current-connection reads; `hostConversationIds` moves onto `firstOnLive`.
- `app/src/test/java/de/pyryco/mobile/e2e/LiveConnectionReadsTest.kt` — the JVM test shape the new helper's test mirrors.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionPump.kt` → `NoiseSessionPump.send` (returns `false` unless `Open`), `teardown` (safe before `start`), `PumpState.Closed` — what the peer observes when its link ends.
- `app/src/main/java/de/pyryco/mobile/data/network/OkHttpRelayTransport.kt` → `close` — single-use transport; a redial needs a fresh transport and pump.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → `listConversationsRequest` — `list_conversations` with an empty payload, the read the peer uses as its settle probe.
- `../pyrycode/internal/relay/v2session_handshake.go` → the handshake-accept tail calling `reconcileModals`, `reconcileQuestions`, `reconcileQueues` — a new interactive connection gets every still-pending `modal_shown` (original `modal_id`), `question_shown` and `queue_state`. No resume cursor is sent by the peer, so `replayMissed` does not re-send a missed `turn_end`.
- `../pyrycode/docs/protocol-mobile.md` § `modal_answer` security note (`answer_token` collapses a replayed answer), § `queue_state` `message_id` ("no daemon path … dedupes" `send_message`) — decides which requests may be resent.
- `docs/knowledge/features/relay-repository-coordinator.md` § the #1029 lesson — "a consumer of `currentRepository` across an await/timeout should use `firstOnLive`/`callOnLive`".

## Context

Evidence (kept logs `$TMPDIR/pyry-e2e.r5Aq2q/daemon.log`, gate run for #1029 at 2026-09-24T21:17Z, times +03:00):

- `peerStartedTurn`: peer `…-1c8cfa6b` accepted 00:23:00.985, peer-close teardown (code 1001) at 00:23:01.024 — 39 ms later. No `send_message` from it. The phone's connection closes at 00:23:31.748, the end of the 30 s ack wait.
- `stopRunningTurn`: peer `…-f77c59d5` accepted 00:24:55.161, closed (1000) at 00:24:55.206. The phone's message is enqueued at 00:24:55.439; the 90 s `awaitPermissionModal` runs out.
- `attachmentsFromPhone` in the same run: peer `…-b731c25c` closed 48 ms after its handshake, and the test failed with `peer session is not open` from `SecondClientPeer.send` — the pump reported `Closed`. So the peer's session **closes**; it does not stay open and silent.

The drop itself is #1039's (the phone's fresh connections end the same way; candidate: the relay's 16-frame phone outbox overflowing on the connect-time reconcile burst). The flake is ours: `SecondClientPeer.open` dials once and a peer whose first link dies stays dead. A connection that has answered a request has not been seen to drop in any kept log.

## Design

### `RedialingLink<L>` (new, `app/src/sharedTest/java/de/pyryco/mobile/e2e/RedialingLink.kt`)

Generic over the link type so the JVM test needs no relay, like `LiveConnectionReads`.

```kotlin
class RedialingLink<L : Any>(
    private val scope: CoroutineScope,
    private val dial: suspend () -> L?,        // one attempt: a settled link, or null (the attempt cleaned up)
    private val awaitEnd: suspend (L) -> String, // suspends until the link ends; returns a content-free label
    private val release: (L) -> Unit,          // closes a link on shutdown
)
val current: StateFlow<L?>                     // the settled live link; null while redialing
suspend fun start()                            // dial until settled (caller bounds it), then supervise in scope
suspend fun <T> request(what: String, resend: Boolean, attempt: suspend (L) -> Attempt<T>): T
fun describe(): String                         // "session open (link n, replaced r×)" / "no open session: link n ended (label); redialing"
fun close()
sealed interface Attempt<out T> { Answered(value), NotSent, Ended }
```

- **Dial loop:** attempts until one settles, backoff 1 s, 2 s, 4 s, capped at 8 s, reset after a settled link.
- **Supervision:** one job in `scope` awaits `awaitEnd(current)`, records the label, sets `current` to null, redials, publishes the new link, and repeats until `close`.
- **`request`:** runs `attempt` on the current link. `Answered` returns. `NotSent` (the pump refused, so nothing reached the wire) waits for a different link and retries, always. `Ended` (the frame went out, then the link ended before the reply) retries on the next link only when `resend`. Otherwise it throws `AssertionError("$what: the link ended before the reply, and it is not resent")`. The caller's `withTimeout` bounds it.
- **`close`:** cancels supervision, releases the current link. Idempotent.

### `SecondClientPeer` changes

- One link is `transport + pump + its recorder job`. `dial` builds a fresh `OkHttpRelayTransport` and `NoiseSessionPump` (same throwaway key and pairing), connects, starts the pump and waits for `Open`. It starts a collector appending to the shared `received`, then **settles**: it sends `list_conversations` and waits for the correlated reply or the pump's `Closed`. One attempt is bounded at 15 s. An attempt that does not settle closes its pump (in `finally`, so cancellation cleans up too) and returns null. `awaitEnd` waits for `PumpState.Closed` and returns `"clean close"` or the cause's class simple name.
- `open(timeoutMs)` = `withTimeout { link.start() }`. The signature is unchanged, and so is the `TimeoutCancellationException` on timeout.
- **Recording:** the connect-time reconcile re-sends a pending prompt. A `modal_shown` whose `modal_id`, or a `question_shown` whose `question_batch_id`, is already recorded is not recorded again, the protocol's "never double-shows" rule. Recorded counts (`recorded(...).count { modal_shown }`, `awaitQuestion(occurrence)`) then stay true across a redial.
- **Requests** go through `link.request`:
  - `send_message`: `resend = false`, because the daemon does not dedupe it.
  - `request_history` and the settle probe: `resend = true`, because both are reads.
  - `modal_answer` (`allowOnce`) and `question_answer` (`answerQuestion`): `resend = true` with the same `answer_token`, which the daemon collapses. They wait for the matching dismissal rather than an `in_reply_to`.
- `uploadAttachment`, `retrieveAttachment` and `dequeueMessage` keep a single send on the current link. `send` throws `peer session is not open` when no link is live, as today. #1016 owns those scenarios.
- New `fun linkState(): String` = `link.describe()`, for failure messages. It names no token, key or payload.
- Every wait keeps reading `received`, so a wait that began on one link is satisfied by a frame from the next.

### `InteractiveStreamE2ETest` changes

- `hostConversationIds` is split into `hostConversationIds(serverId): Set<String>` and `newHostConversationId(serverId, before): String`. Both are non-suspending and read through `currentRepository.firstOnLive({ it.observeConversations(All) … })`, bounded by `LIST_TIMEOUT_MS`. A timeout throws an `AssertionError` naming the phone read. The 10 call sites in the five peer scenarios and `holdToolOnPermission` drop their own `runBlocking { withTimeout(…) }` wrappers.
- New `peerStep(peer, step) { … }` runs a suspending peer call in `runBlocking` and turns a `TimeoutCancellationException` into `AssertionError("peer step '<step>' timed out; <peer.linkState()>")`. The two flaky scenarios wrap every peer call in it. No assertion is removed or changed.

## State + concurrency model

- The peer's `scope` (`SupervisorJob + Dispatchers.IO`) owns supervision and every link's recorder job. `close()` cancels it and releases the live link. Dial attempts in flight are cancelled with the scope, and their `finally` closes their pump synchronously.
- `current` is a `MutableStateFlow` written only by `start`/supervision. `request` reads it and waits on `current.first { it != null && it !== used }` for a replacement.
- `received` stays a `MutableStateFlow<List<Envelope>>` mutated with `update {}`. The dedupe check runs inside the `update` lambda, so a check and its append are one atomic step.

## Error handling

- Peer waits time out with `TimeoutCancellationException` exactly as today. `assertPeerAnswers`, `awaitTurnEnd`, `allowPromptsUntil` and the other catchers see no change. The two scenarios name the step through `peerStep`.
- A non-resendable request whose link ended fails with a named `AssertionError`. This case was previously an unnamed timeout.
- Failure text carries type names, step names, link counts and exception class names only.

## Testing strategy

- **JVM** `app/src/test/java/de/pyryco/mobile/e2e/RedialingLinkTest.kt` (`runTest`, fake links whose end is a `CompletableDeferred<String>`):
  - `start` dials past attempts that do not settle and publishes the first settled link.
  - A settled link that ends is replaced, and `describe` names the link count and the replacement.
  - `request` with `resend = true` retries on the replacement after `Ended`.
  - `request` with `resend = false` fails with an `AssertionError` naming `what` after `Ended`.
  - `request` retries after `NotSent` even with `resend = false`.
  - `close` releases the current link and stops redialing.
- **Compile:** `compileDebugAndroidTestKotlin` for the peer and scenario changes.
- **Live proof:** the dispatcher's `python3 scripts/android-test-gate.py live` (the ticket is `needs-real-claude`). No focused managed-device run: these are rung-3 real-claude scenarios, which the builder does not run (the dispatcher owns the live suite).

## Open questions

- Is `list_conversations` answered on every settled connection before any other reply, so the probe cannot be starved by the reconcile burst? It is a correlated reply and the collector records everything, so ordering does not matter. It is resolved by design; I will confirm it in the live gate.

## Documentation handoff

None named by the ticket. Pending for the documentation stage: `docs/knowledge/features/development-verification.md` (or the live e2e overview it points to) could record that `SecondClientPeer` redials and settles each link on a `list_conversations` probe, and that the connection drop itself is #1039's.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. The change is test-harness only (`androidTest`, `sharedTest`, `test`). Daemon frames still enter only through `NoiseSessionPump.inbound` (authenticated decrypt) into `received`. The new dedupe reads `modal_id` / `question_batch_id` with the existing `payloadField`, compares them with each other and never renders them.
- [Tokens] No findings. A redial reuses the in-memory `PairedServer` and `ThrowawayDeviceKeyStore`, and `NoiseSessionFactory` zeroes the private-key copy it gets on each dial, as it does today. `linkState`/`describe` and every new failure message carry counts, step names, frame type names and exception class simple names, never the token, the key, a payload or an exception message.
- [File / storage] No findings. Nothing touches disk.
- [Android surface] No findings. No component, intent or manifest change.
- [Crypto] No findings. Each link is a fresh `NoiseSessionPump` handshake (fresh ephemerals, fresh transport ciphers). No `(key, nonce)` pair carries across links, because a pump is single-use and a resend is re-encrypted on the new session. No hand-rolled crypto.
- [Network & I/O] No findings. The transport comes from the unchanged `OkHttpRelayTransport.defaultClient()`. Redialing is bounded by the backoff schedule (≥ 1 s, capped at 8 s), a 15 s per-attempt bound, the caller's timeout and `close()`. A token the daemon rejects does not spin: each failed attempt waits out its backoff, and the scenario's own timeout ends the loop.
- [Logs] No findings. The peer still logs nothing. Failure messages carry no content, as listed above.
- [Concurrency] No findings. Supervision and recorders live in the peer's own scope, and `close` cancels it. A pump that never settles is closed in its attempt's `finally`. At most one settled link is published at a time. A superseded link has already ended, so it cannot deliver frames after its replacement is dialed. The dedupe runs inside `received.update`.
- [Threat model] OUT OF SCOPE: the relay dropping fresh connections (the hostile or faulty relay case) is #1039's to diagnose, and a fix goes to its owning repo. This ticket only makes the harness tolerate it, as the app already does.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-25
