# #1410 — Ask for the context reading when a chat opens and when its host returns

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `observeContextUsage` (KDoc still says the phone never asks), `refreshSessionSettings` (the fire-and-forget no-op-default shape this ticket copies).
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` → `refreshSessionSettings` — routes through `currentRepository.value`, so no live connection is a silent no-op; the new method mirrors it.
- `app/src/main/java/de/pyryco/mobile/data/repository/CachingConversationRepository.kt` → `CachingConversationRepository` is `ConversationRepository by delegate`, so a new interface method forwards to the facade without an edit.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → `onInbound`'s `TYPE_CONTEXT_USAGE` arm (already applies a reply with or without `in_reply_to`), the `TYPE_ERROR` arm (an unmatched `in_reply_to` is a no-op in both `RelayRequests` and `ModelMenuProjection.applyRefusal`), `TYPE_REQUEST_CONTEXT_USAGE` and `ERROR_CONTEXT_USAGE_UNAVAILABLE` (KDoc says the phone does not send).
- `app/src/main/java/de/pyryco/mobile/data/repository/ModelMenuProjection.kt` → `askForModelMenu` — the existing fire-and-forget, never-logging, non-throwing outbound ask whose guards (empty id, `interactive` gate, try/catch around `send`) this ticket reuses.
- `app/src/main/java/de/pyryco/mobile/data/repository/ContextUsageProjection.kt` → class KDoc "The phone does not send `request_context_usage` (#946)" goes stale. The projection lives in `HostReadings` (pairing-scoped), so it must not hold the connection-scoped `send`.
- `app/src/main/java/de/pyryco/mobile/data/network/ContextUsagePayloads.kt` → `RequestContextUsagePayloadDto` (exists, never sent; KDoc stale).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `init`'s `repositoryAvailable` collector (#778/#861 walk restart, `drop(1)`), `rereadRunSettings` (#1309, static-reason `RelayLog` line).
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → `thread` — `repositoryAvailable = coordinator.currentRepository.map { it != null }`, `flowOf(false)` with no bundle.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryContextUsageTest.kt` → fake pump, `subscription_sendsNothing`, `reconnect_throughTheFacade_dropsTheOldReading_andAsksNothing`.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelSettingsRereadTest.kt` → the `rig()` + counting-repo pattern with a `MutableStateFlow<Boolean>` availability.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_pingPrompt_footerShowsContextUsage` (the `Cxt: N%` matcher on `CONTEXT_USAGE_TEST_TAG`), `interactiveTurn_offlineRead_reconcilesPeerTurnOnReconnect` (`SecondClientPeer`, `setHostLink`), `openChatRow`, `leaveThread`, `hostRepository`.
- `scripts/e2e-emulator.sh` (`LIVE=1` `TEST_TARGET` list), `scripts/android-test-gate.py` (`LIVE_MINIMUM`).
- pyrycode `docs/protocol-mobile.md` § "Asking for a context usage reading on demand" and § "Reconnect replay & resync" — the wire SSOT; not restated here.

## Design source

**Figma:** N/A — no visual change. The footer's existing `Cxt:` segment renders the reading; this ticket only makes the reading arrive earlier.

## Context

Mobile never sends `request_context_usage`, so a chat reads `Cxt: n/a` until a turn ends on it. #946 held the ask back because a mid-turn ask stalled the connection; pyrycode#2563 fixed that (the deferred ask no longer delays other frames). #1317 keeps pushed readings across a reconnect, so only the ask is missing. Desktop's `requestConversationConfig` asks whenever a chat opens on a connected host.

Overlap: #1343 (in flight) adds MCP siblings beside `observeContextUsage` in the same three repository files; this ticket's additions sit beside `refreshSessionSettings` instead, so a later merge stays mechanical. #1311 and others touch `ThreadViewModel.kt` / `InteractiveStreamE2ETest.kt` elsewhere.

## Design

### Repository contract

```kotlin
// ConversationRepository
fun requestContextUsage(conversationId: String) = Unit
```

Fire-and-forget, non-suspending, non-throwing, in the shape of `refreshSessionSettings`. The answer lands on the `observeContextUsage` flow the caller already collects; there is no return value. Default no-op so the fake and inline doubles need nothing.

- **`StableConversationRepository.requestContextUsage`** — `currentRepository.value?.requestContextUsage(id)`; no connection → silent no-op.
- **`CachingConversationRepository`** — no edit; `by delegate` forwards.
- **`RemoteConversationRepository.requestContextUsage`** — guards then one send, the `askForModelMenu` posture:
  1. empty id → return (names nothing; the daemon refuses it anyway);
  2. `CAPABILITY_INTERACTIVE !in negotiatedCapabilities()` → return (daemon answers such a conn with nothing);
  3. build `Envelope(id = relayRequests.nextRequestId(), type = TYPE_REQUEST_CONTEXT_USAGE, payload = RequestContextUsagePayloadDto(id))`, `pump.send` inside `try/catch (Exception)`; a refused send is dropped, not retried.

  No correlation ledger and no `RelayRequests` waiter: the success reply is a `context_usage` the existing arm already applies by its payload's own `conversation_id`, and a refusal (`conversation.not_found`, `context_usage.unavailable`) is an `error` whose `in_reply_to` matches no waiter and no model-list ask, so it is a no-op — the reading stays as it was and nothing surfaces. Never logs (the id is a cross-conversation correlation key).

### ThreadViewModel trigger

A new `init` collector:

```kotlin
repositoryAvailable.distinctUntilChanged().filter { it }.collect { askForContextUsage(reason) }
```

- The opening value, when `true`, sends the first ask (unlike the walk restart's `drop(1)`: the open's walk is started separately, but nothing else sends the open's ask).
- Each later `false → true` edge sends exactly one more.
- An opening `false` sends nothing until the repository arrives; that arrival is the first ask.
- `askForContextUsage` logs `RelayLog.d { "event=context_usage_ask reason=<open|reconnect>" }` — static codes only, never the id — then calls `repository.requestContextUsage(conversationId)`.
- The reason is `open` for the first `true` the collector sees and `reconnect` after; computed with a local flag inside the collector.

### KDoc updates

`observeContextUsage` (interface), `RequestContextUsagePayloadDto`, `TYPE_REQUEST_CONTEXT_USAGE`, `ERROR_CONTEXT_USAGE_UNAVAILABLE` and `ContextUsageProjection`'s class KDoc each lose the "the phone does not send it (#946)" claim and say the open thread asks on open and on the repository's return (#1410), and that a refusal is ignored.

## State + concurrency model

- One `viewModelScope.launch` collector, cancelled with the ViewModel. No new `StateFlow`.
- `requestContextUsage` is synchronous: `pump.send` is a non-suspending enqueue. It runs on the collector's dispatcher (Main), as `refreshSessionSettings` already does.
- Mid-turn ask: since pyrycode#2563 the daemon defers the answer to turn end without holding other frames; the reply is matched by its payload's `conversation_id`, so reply order against later frames does not matter. Two asks close together collapse daemon-side. At most 4 deferred asks per connection; a fifth is refused with `context_usage.unavailable`, which is ignored.
- Reconnect: `repositoryAvailable` derives from the coordinator's published repository (after the Noise handshake), so the ask goes to a repository whose pump is open. A reply arriving after a further disconnect is lost with that connection; the next return asks again.

## Error handling

| Failure | Result |
|---|---|
| No live connection | Stable no-op; VM sends nothing because availability is `false` |
| `interactive` not negotiated | Remote returns without sending |
| Transport refuses the send / throws | Swallowed; no retry; reading unchanged |
| `conversation.not_found` / `context_usage.unavailable` | No-op in the `error` arm; reading unchanged; nothing in the UI |
| Malformed reply | Existing decode-or-drop in `ContextUsageProjection.apply` |

## Testing strategy

Unit tests (`./gradlew testDebugUnitTest --tests ...`):

- **`RemoteConversationRepositoryContextUsageTest`** (fake pump):
  - `requestContextUsage` sends exactly one `request_context_usage` whose payload is `{"conversation_id":"c1"}`, with a fresh envelope id; subscribing still sends nothing.
  - an empty id sends nothing; a repository without `interactive` sends nothing.
  - the correlated `context_usage` reply (with `in_reply_to` = the ask's id) replaces the reading.
  - an `error` reply with `context_usage.unavailable`, then one with `conversation.not_found`, both correlated to the ask, leave the prior reading unchanged and the collector alive (a later frame still applies).
  - a pump whose `send` throws does not propagate.
  - class KDoc updated from "sends no request".
- **`StableConversationRepositoryTest`**: `requestContextUsage` delegates to the live repo; while absent it is a silent no-op.
- **New `ThreadViewModelContextUsageAskTest`** (the #1309 rig shape with a counting repo and `MutableStateFlow` availability):
  - opening with availability `true` asks exactly once for the open conversation;
  - `true → false → true` asks exactly once more; a repeated `true` asks nothing;
  - opening with `false` asks nothing, and the first `true` asks once;
  - the log lines are `event=context_usage_ask reason=open` then `reason=reconnect`, never the id.

Rung-3 (`InteractiveStreamE2ETest`, new method `interactiveTurn_reopenAfterReconnect_footerShowsContextUsageBeforeAnyTurn`):

1. Peer opens; phone creates and renames a chat, leaves it.
2. Phone cuts its link (`setHostLink(down)`).
3. The peer sends `PING_PROMPT` in that chat and waits for its `turn_end` and its `context_usage`, so the daemon holds a reading the phone never received. The phone's replay cursor cannot name this chat (it had no ring events before the cut), and replay is scoped to the cursor's own conversation, so the reconnect does not replay the push.
4. Phone restores its link, waits on the channel list, and asserts the facade's `observeContextUsage(chat)` is still `null` — the non-vacuity guard: without the ask, nothing could fill it.
5. Phone opens the chat by name; the footer's `CONTEXT_USAGE_TEST_TAG` node reaches `Cxt: N%` with no phone message sent.

One real-claude turn (the peer's ping) plus one on-demand reading. Added to the `LIVE=1` list in `scripts/e2e-emulator.sh`; `LIVE_MINIMUM += 1` in `scripts/android-test-gate.py` with a `#1410` comment. No rung-4 twin: the scripted `fakeclaude` path has no on-demand context querier to answer the ask with a fixture, and the live suite runs this after verifier for `needs-real-claude`. The live result (counts, new method passing, `interactiveTurn_pingPrompt_footerShowsContextUsage` still passing) comes from the dispatcher's post-verifier live run.

## Open questions

- Does the `fakeclaude` deterministic path answer `request_context_usage`? Assumed no (above); if it does, a rung-4 twin can follow as its own ticket.

## Documentation handoff

Pending for the documentation stage: the thread / context-usage feature overview (the topic that documents the footer's `Cxt:` segment and #946's "no ask" rule) should record that the open thread asks on open and on each repository return (#1410), that refusals are ignored, and that `docs/e2e-interactive-stream.md`'s coverage list gains the new rung-3 method.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — the only inbound data is the existing `context_usage` reply, decoded by `ContextUsageProjection.decodeContextUsage` behind the `interactive` gate; only the four scalars are declared, so claude-authored `model` and inventory strings never cross. The reply routes by its daemon-authored `conversation_id`, never by `in_reply_to`, so a forged or replayed correlation cannot move a reading to another chat. A refusal `error` adds no new decode: an unmatched `in_reply_to` is a no-op in both `RelayRequests` and `ModelMenuProjection.applyRefusal`, so no daemon text from `error.message` reaches the UI or a log.
- [Tokens] No findings — the ask carries only a conversation id over the existing Noise session; no token, key or credential is created, stored or sent.
- [File / storage] No findings — nothing is written to disk; the reading stays in the pairing-scoped in-memory `HostReadings`.
- [Android surface] No findings — no intent, deep link, push, provider or WebView change.
- [Crypto] No findings — the frame rides the existing `NoiseIkSession` through `SessionPump.send`; no new primitive, no nonce handling.
- [Network & I/O — outbound volume] SHOULD FIX — a ViewModel ask per open plus one per repository return could, with rapid open/close of threads, send many asks. Bounded in practice: one ask per thread open, the daemon collapses same-conversation asks and refuses past 4 deferred per connection with a retryable code the phone ignores (no retry loop on the phone side). Phase B must keep it non-retrying: no timer, no re-send on refusal or failed send; the tests assert exactly one ask per edge.
- [Network & I/O — inbound] No findings — the reply size is bounded by the existing v2 envelope cap and decode-or-drop; no new frame type is decoded.
- [Logs] SHOULD FIX — the ViewModel's `RelayLog` line must carry static reason codes only (`open` / `reconnect`), never the conversation id; the repository send path logs nothing on any branch, and a caught send exception is discarded without its message. Asserted by the ViewModel log test.
- [Concurrency] No findings — one collector on `viewModelScope`, cancelled on clear; `requestContextUsage` is non-suspending and touches no shared mutable state (the envelope id comes from `RelayRequests.nextRequestId`, already atomic). A send racing a teardown either enqueues on a closing pump (the reply is lost with the connection, and the next return asks again) or is refused and swallowed.
- [Threat model — malicious relay] No findings — content-blind; it can drop or delay the ask or reply, leaving the reading absent or stale exactly as today, never wrong.
- [Threat model — hostile daemon frame] No findings — a daemon answering with a reading for another conversation lands under that conversation only, as the existing `reply_routesByThePayloadsConversationId_notByInReplyTo` test pins.
- [Threat model — mid-turn stall] OUT OF SCOPE — the connection-wide stall a mid-turn ask caused is fixed daemon-side by pyrycode#2563 (closed 2026-09-24); this ticket relies on it rather than re-guarding on the phone.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-01
