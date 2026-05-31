# Architecture: `RemoteConversationRepository.observeLastMessage` via live v2 `message` stream — #329

## Context

Phase 4 chain, `data/`-layer slice. Carved out of #312 during its rework. #312 shipped
`RemoteConversationRepository : ConversationRepository` with the conversation-list read path
(`observeConversations`) live and **`observeLastMessage` as a throwing stub**
(`RemoteConversationRepository.kt:127-128`). This slice replaces that one stub with a live
implementation so the channel list shows real last-message previews instead of nothing.

The v2 `conversations` summary payload (#316) carries no message content — only a
`last_message_ts` that maps to no domain field. So the preview cannot come from the
conversation-list read; it must come from the **message-content read path**: source `message`
payloads over the pump, map each to a domain `Message` via #317, surface the most-recent per
conversation.

**Wire shape — resolved by the ticket; no protocol to invent.** v2 has no "latest message"
request. Per the ticket, *"the simplest implementation here can ride the live inbound `message`
stream"* and *"riding the connection-wide inbound `message`/`message_chunk` stream is also valid;
the architect picks."* **This spec rides the live `message` stream only** (Design A) — it issues
no `backfill_since` request and does not parse `message_chunk` arrays. Rationale and the #313
hand-off are in [Design](#design) and [Open questions](#open-questions--hand-off). **Do not add a
new wire message type.**

Depends on #312 (the class + pump wiring) and #317 (`message` → domain `Message` mapping); both
are merged on `main`. No sibling feature branch touches `RemoteConversationRepository.kt` at
architect time — `feature/313`/`feature/314` do not exist yet, and the branch-overlap scan over
in-flight branches found nothing. Whichever of #313/#329 lands first establishes the live message
plumbing in this file; this slice lands the `message`-demux branch and the `lastMessages`
projection, and #313's architect reads the merged code rather than duplicating it.

## Design source

N/A — pure `data/`-layer orchestration; no UI, no Figma. The last-message preview is rendered by
the already-shipped channel-list UI (`ChannelListViewModel.kt` → `DiscussionPreviewRow`, #158/#162);
this slice only feeds it data. The "design source" is the byte contract: the #317 `message` mapper
and `protocol-mobile.md § Application message types / § Backfill semantics`.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:45-172` —
  the **class you extend**: constructor `(pump: SessionPump, scope: CoroutineScope)`, the single
  `init` inbound collector (`scope.launch { pump.inbound.collect { onInbound(it) } }`), the
  `onInbound(envelope)` `when`-demux (extend it), the `projection` `StateFlow` pattern to mirror
  for `lastMessages`, the `observeLastMessage` stub at **127-128** to replace, and the `companion`
  (add `TYPE_MESSAGE`).
- `app/src/main/java/de/pyryco/mobile/data/network/MessagePayload.kt:31-103` — the **#317 mapper
  you consume, do not re-implement**: `MessagePayloadDto(conversation_id, message_id, role: WireRole,
  text)` and `fun MessagePayloadDto.toMessage(envelope, sessionId): Message`. Note: `timestamp`
  comes from `Instant.parse(envelope.ts)` (throws `IllegalArgumentException` on a bad ts);
  `sessionId` is **caller-supplied** (this slice passes `""` — see Design); `WireRole` accepts only
  `user`/`assistant` and rejects `system`/unknown at decode.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:25-30` — the
  **contract to honor**: "Emits the most-recent `Message` (by `Message.timestamp`) … or `null` if
  the conversation has no messages or is unknown. Cold flow, re-emits on every state change."
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:82-85` — the
  **behavioral precedent to match**: `state.map { records -> records[id]?.messages?.maxByOrNull
  { it.timestamp } }`. The Remote must yield the same "most-recent-by-timestamp, else null" shape
  so the UI behaves identically under either binding.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt:62-86` — the
  **only consumer**: `repository.observeLastMessage(id).map { id to msg }` per recent discussion,
  `combine`d into `Map<String, Message>`, re-subscribed via `flatMapLatest` when the id set changes;
  null messages are dropped from the map. Confirms the signature is unchanged (zero edits here) and
  that a re-subscription must immediately yield the current value (Design A satisfies this).
- `app/src/main/java/de/pyryco/mobile/data/repository/SessionPump.kt:17-30` — the consumed pump:
  `val inbound: Flow<Envelope>` (hot, **single-consumer**, lossless, completes on teardown) and
  `fun send(envelope: Envelope): Boolean`. This slice **does not call `send`** (rides the live
  stream).
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt:38-44` — `Envelope(id: Long,
  type: String, ts: String, payload: JsonElement, inReplyTo: Long?)`; read `message` envelopes
  against this shape.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt:29` — the `MobileJson`
  singleton; decode payloads through it (`MobileJson.decodeFromJsonElement<MessagePayloadDto>(…)`),
  never a default `Json`.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt:30-313` —
  the **test idiom and the in-file `FakeSessionPump` to reuse** (Channel(UNLIMITED)-backed inbound,
  `push()`, `send(): Boolean`), JUnit4 + `runTest` + `runCurrent()` + `backgroundScope`. Reuse it;
  add a `message`-envelope helper. **Update** `stubMethods_throwUnsupportedOperationNamingTheFollowUp`
  (lines 226-247): delete the `observeLastMessage` throws-assertion (233-235) — it's now implemented;
  keep the `observeMessages`/mutation/no-wire assertions.
- `docs/specs/architecture/312-remote-conversation-repository-observe-list.md` — the sibling spec;
  this slice mirrors its single-inbound-consumer + projection-`StateFlow` + catch-and-drop pattern.
- Memory: drive the push→demux→project→emit cascade with **`runCurrent()`, not
  `advanceUntilIdle()`** (the latter does not deliver the emission — there are no timers to elapse).

## Design

### Files (1 production modified + 1 test modified — zero new files)

| File | Kind | Change |
|---|---|---|
| `…/data/repository/RemoteConversationRepository.kt` | production | add `lastMessages` `StateFlow`; add a `message` branch to `onInbound`; replace the `observeLastMessage` stub; add `TYPE_MESSAGE` |
| `…/data/repository/RemoteConversationRepositoryTest.kt` | test | add `observeLastMessage` scenarios + a `message`-envelope helper; drop the stub-throws assertion for `observeLastMessage` |

No new exported types. No DI change (the class is already constructed by #279/#302's wiring). No
new dependency (`kotlinx-coroutines`, `-serialization-json`, `-datetime` already present).

### Why Design A (live `message` stream only)

- **Simplicity First + ticket-sanctioned.** The ticket explicitly blesses riding the live
  `message` stream as the simplest valid implementation.
- **Do not solve it twice.** Backfill (`backfill_since` → `message_chunk` → `backfill_done`) and
  the per-row timestamp reconciliation for `message_chunk` arrays (each row carries its own ts, so
  the #317 single-envelope-`ts` model does not apply to chunks) are explicitly *"shared with #313 —
  leave it to the architect, do not solve it twice."* #313 (thread read) genuinely needs full
  history and will establish that plumbing; #329 does not.
- **Evidence-based.** The AC requires only: most-recent live message, re-emit on newer, `null` on
  unknown/empty. Cold-start historical population is not required here and is deferred (see Open
  questions). #329 is a strict improvement: before it, the preview shows nothing; after it, it
  shows real content for any conversation with live traffic this connection.

### `lastMessages` — the last-message projection (single source of state)

```kotlin
// conversationId -> most-recent Message seen on this connection's live `message` stream.
private val lastMessages = MutableStateFlow<Map<String, Message>>(emptyMap())
```

Mirrors the existing `projection` field: written **only** by the one `init` inbound collector
(sequential single writer); `observeLastMessage` fans out from it. No parallel mutable state.

### `onInbound` — add a `message` branch

Extend the existing `when (envelope.type)` with `TYPE_MESSAGE`. Behavior (contract, not body):

1. Decode + map through #317 inside a per-envelope `try/catch (IllegalArgumentException) { return }`
   — `MobileJson.decodeFromJsonElement<MessagePayloadDto>(envelope.payload)` then
   `dto.toMessage(envelope, sessionId = "")`. The catch covers both the decode
   (`SerializationException`, a subtype) and the `Instant.parse(envelope.ts)` failure; a malformed
   `message` is **dropped** so the single inbound consumer survives (same posture as the
   `conversations` branch).
2. Fold into `lastMessages` via `update { }` (atomic check-then-replace): for key
   `dto.conversationId`, **replace iff the incoming `timestamp` is strictly greater** than the
   stored entry's (or there is none). Strictly-greater means out-of-order older arrivals are
   ignored and a re-delivered duplicate is a no-op — matching the Fake's `maxByOrNull { timestamp }`
   "most-recent-by-timestamp" semantics (tie-break is not behaviorally load-bearing for previews).

**`sessionId = ""`.** The `message` wire payload carries no session id, and the last-message
preview (`ChannelListViewModel` → `DiscussionPreviewRow`) renders only `content` + `timestamp`,
never `sessionId`. `""` is a defined, non-null placeholder — the same list-tier posture #312 uses
for `currentSessionId` — **not** null-punning. Do not plumb a fabricated session id; when #313
lands session-aware thread mapping, this preview is unaffected because it never reads `sessionId`.

**Keying.** `dto.conversationId` (wire `conversation_id`) is read from the decoded DTO *before*
mapping (the domain `Message` has no conversation id). This is the map key.

### `observeLastMessage` — cold derivation

```kotlin
override fun observeLastMessage(conversationId: String): Flow<Message?> =
    lastMessages.map { it[conversationId] }.distinctUntilChanged()
```

Pure projection of the shared `StateFlow`. **No `pump.send`** (rides the live stream; no request).
A `StateFlow` always has a current value, so a collector — including a `flatMapLatest`
re-subscription from `ChannelListViewModel` — immediately receives the current most-recent (or
`null`) on subscription (AC #4), re-emits only on change via `distinctUntilChanged` (AC #3), and
supports unlimited concurrent collectors off the one inbound consumer (AC #4). A `null` is emitted
whenever the conversation is absent from the map (AC #2).

### companion

Add `const val TYPE_MESSAGE = "message"` (the singular live/echo `message` type per #317; distinct
from the plural `messages` thread-read response owned by #313, which this `when` still ignores).

## State + concurrency model

- **One added `StateFlow<Map<String, Message>>`**, fed by the **existing** single `init` collector
  of `pump.inbound` on the injected connection `scope`. No new coroutine, no new scope; the
  collector is still the sole consumer of the hot single-consumer inbound stream.
- **TOCTOU-safe fold.** The fold reads-then-writes `lastMessages`; although there is a single
  sequential writer, use `MutableStateFlow.update { }` so the check-and-replace is atomic by
  construction (deterministic safety net, not a convention — security review §8).
- **Hot vs cold (intentional).** `lastMessages` is hot/shared (subscriber-independent, fed by the
  always-on collector); `observeLastMessage` is a cold per-collector view. Same split as
  `projection`/`observeConversations`. The map is connection-scoped in-memory state in one trust
  domain — no cross-screen data leak.
- **Dispatcher:** inherited from the injected `scope` (DI: `Dispatchers.Default`; pure CPU/JSON —
  socket I/O is the transport's, below the pump). Do not hard-code a dispatcher.
- **Shutdown/cancellation:** the scope (and the collector) is cancelled by its owner (#279/#302) on
  connection end; `pump.inbound` completing on teardown also ends it. `lastMessages` is in-memory —
  process death loses it; it re-derives from the live stream on reconnect (the cold-start gap is the
  documented #313 hand-off).

## Error handling

| Failure mode | Where | Result |
|---|---|---|
| Malformed `message` payload (missing field, or unmappable role e.g. `system`) | inbound collector decode | `SerializationException` (⊂ `IllegalArgumentException`) caught per-envelope; envelope **dropped**; collector survives; `lastMessages` unchanged |
| Bad `envelope.ts` on a `message` | `toMessage` → `Instant.parse` | `IllegalArgumentException` caught; envelope dropped; collector survives |
| Out-of-order older `message` | fold | ignored (strictly-greater-ts replaces); no preview downgrade |
| Unknown / never-seen `conversationId` | `observeLastMessage` | emits `null` (map miss) |
| `pump.inbound` completes (teardown) | collector | collector completes; `lastMessages` retained; live `StateFlow` collectors stop receiving (do not complete) |

**Drop silently — do not log payload content.** Message content may be sensitive; the drop path
must add **no** `Log`/exception text containing the payload. If any diagnostic is ever added, log
only `envelope.type` and a static reason, never the body. (Security review §7.) Rationale for
catch-and-drop: an uncaught throw would kill the single inbound consumer and silently freeze all
future conversation **and** last-message updates for the connection.

## Testing strategy

Unit only (`./gradlew test`), JUnit4 + `runTest` + **`runCurrent()`** (not `advanceUntilIdle()`),
reusing the in-file `FakeSessionPump`. Add a helper that builds a `message` `Envelope`
(`type = "message"`, given `ts`, `payload = MobileJson.parseToJsonElement(<MessagePayloadDto json>)`).
Construct the repo with `backgroundScope` so the collector auto-cancels. Scenarios (bullets — write
in the project idiom, not as full bodies):

- **Unknown/empty conversation → `null` (AC #2).** Subscribe before any `message`; assert the first
  emission is `null`.
- **Seed one `message` → mapped most-recent (AC #1, #5).** Push a `message` for `c1`; assert
  `observeLastMessage("c1")` emits a `Message` with `id`/`role`/`content` from the payload,
  `timestamp` from `envelope.ts`, and `sessionId == ""`.
- **Newer `message` re-emits (AC #3, #5).** After seeding, push a later-`ts` `message` for `c1`;
  assert re-emit with the newer message.
- **Older `message` does not replace (AC #1 most-recent invariant).** After a newer message, push an
  earlier-`ts` `message` for `c1`; assert no re-emit (still the newer).
- **Per-conversation keying.** Interleave `c1` and `c2` messages; assert each
  `observeLastMessage(id)` reflects only its own conversation's latest.
- **Late subscriber gets current value (AC #4).** Push a `message` for `c1`, `runCurrent()`, then
  subscribe; assert it immediately receives the current most-recent.
- **Multiple concurrent collectors (AC #4).** Two collectors of `observeLastMessage("c1")`; push one
  `message`; assert both receive it (single inbound consumer, shared fan-out).
- **Malformed `message` dropped, consumer survives (Error handling).** Push a `message` missing a
  required field (or role `"system"`); assert no emission for it, then a valid `message` surfaces.
- **Bad `ts` dropped (Error handling).** Push a `message` with a non-parseable `ts`; assert dropped,
  consumer survives.
- **Stub test updated.** In `stubMethods_throwUnsupportedOperationNamingTheFollowUp`, remove the
  `observeLastMessage` throws-assertion (now implemented); keep the others.

## Open questions / hand-off

- **Cold-start historical population is deferred to #313's backfill.** With Design A the preview is
  empty for a conversation until a live `message` arrives this connection; quiescent conversations
  show nothing until backfill runs. This is the conscious slice boundary. When #313 establishes the
  backfill plumbing (`backfill_since` → `message_chunk` → `backfill_done`), a small follow-up can
  fold `message_chunk` rows into `lastMessages` (using #313's per-row-`ts` reconciliation, **not**
  re-derived here) so previews populate from history on connect. Tracked by the #313 coordination;
  do not pre-build `message_chunk` handling in this slice.
- **Map growth.** `lastMessages` grows by distinct `conversation_id` seen on the connection (one
  `Message` per key). Bounded in practice by the paired server's conversation set; see Security
  review (DoS, SHOULD FIX/deferred).

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No finding. The network→process boundary for message content is the single
  typed #317 decode (`MessagePayload.kt:31-82`), running behind the authenticated Noise channel
  (transport below the pump). #329 consumes that one boundary — it adds **no** second decode site —
  and wraps it in catch-and-drop so an untrusted (post-auth) payload cannot kill the inbound
  consumer. Downstream holds a validated domain `Message`; the `conversationId` map key is read from
  the post-decode DTO.
- **[Tokens/secrets]** N/A — #329 handles no tokens or credentials.
- **[File/storage]** N/A — no filesystem or persistence I/O; `lastMessages` is in-memory and dies
  with the connection scope. No backup/`allowBackup` surface.
- **[IPC/Android surface]** N/A — pure data layer; no Activity/Service/Receiver/Intent/deep-link/WebView.
- **[Cryptographic primitives]** N/A — runs above the Noise transport (#298/#306); no RNG, no keys.
  The `sessionId = ""` placeholder is not security-relevant.
- **[Network & I/O]** No finding. #329 adds no network I/O (no `pump.send`). Inbound `message` size
  is bounded by the transport's read limit **below** the pump (cf. upstream pyrycode #303's 1 MiB
  inbound cap); #329 neither lifts nor bypasses it. Timeouts/TLS/pinning are the transport's (#306).
- **[Error messages/logs]** SHOULD FIX (guidance, honored by the spec). Message content may be
  sensitive; the drop path adds **no** logging and must not log the payload — see Error handling.
  Developer/code-review must ensure no `Log.*(envelope.payload)` is introduced in the drop branch.
- **[Concurrency]** No finding. No new coroutine/scope (reuses the #279/#302-owned collector); the
  check-then-replace fold uses `MutableStateFlow.update { }` for atomicity; hot/cold split is
  intentional and connection-scoped (no cross-screen leak); in-memory state is safely lost on
  process death and re-derived from the live stream.
- **[Threat model — DoS]** SHOULD FIX / deferred (evidence-based). A hostile/buggy post-auth server
  could emit `message`s for unboundedly many distinct `conversation_id`s, growing `lastMessages`.
  Low risk: bounded by the single paired server's conversation set, the same unboundedness already
  present in #312's list projection, and the threat model trusts the post-handshake server for
  content (it *is* the message source). Not defended now (unobserved, matches #312's posture); if
  observed, cap to the known-conversation set or LRU-evict in a follow-up.
- **[Threat model — screenshot leakage]** OUT OF SCOPE. The preview renders message content on the
  channel list, which could appear in app-switcher snapshots / screen recordings. `FLAG_SECURE` /
  screenshot policy is a UI-layer concern, not this data-layer slice; deferred to a future UI
  hardening ticket.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-06-01
