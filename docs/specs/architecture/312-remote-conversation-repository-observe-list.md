# Architecture: `RemoteConversationRepository` + `observeConversations` (Mobile Protocol v2) — #312

## Context

Phase 4 chain, `data/`-layer slice. The conversation list still reads from the in-memory
`FakeConversationRepository`. This slice introduces `RemoteConversationRepository :
ConversationRepository` and wires the **conversation-list read path** (`observeConversations`)
live over the Mobile Protocol v2 Noise session pump (#309). Every other interface method ships
as a clearly-marked not-yet-implemented stub that later slices replace (#313 thread reads, #314
mutations, #329 `observeLastMessage`).

The repository drives a `list_conversations` request and consumes decrypted application
`Envelope`s over the pump's inbound stream, mapping `conversations` payloads to domain
`Conversation` values via the #316 mapping layer (`ConversationsPayload.toConversations()`). It
does **not** re-implement wire↔domain mapping, and it does **not** reach below the pump to the
raw frame transport or the Noise session.

This slice is the foundation for its siblings: **#313 and #314 are `blockedBy` #312** (verified
2026-05-31) and add their methods to the *same* `RemoteConversationRepository.kt` after it lands.
No sibling feature branch is in flight — zero file-overlap risk at architect time.

## Design source

N/A — pure `data/`-layer orchestration; no UI, no Figma. The "design source" is the byte
contract: `protocol-mobile.md § list_conversations → conversations` (server repo) and the #316
mapper that is the typed decode boundary.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:20-122` — the
  **full interface to implement** + `ConversationFilter { All, Channels, Discussions, Archived }`.
  Note `delete`, `recentWorkspaces`, `createWorkspaceFolder` already have interface **defaults** —
  do **not** override them (see Design).
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:60-74` — the
  **observable contract to match**: `observeConversations` filters per `ConversationFilter` then
  `sortedByDescending { lastUsedAt }`. The Remote must produce the same shape so the UI behaves
  identically under either binding. (`isSleeping` derivation is fake-only; the list payload does
  not carry it — see #316 placeholder note.)
- `app/src/main/java/de/pyryco/mobile/data/network/ConversationsPayload.kt:23-79` — the #316
  mapper you consume: `MobileJson.decodeFromJsonElement<ConversationsPayload>(payload)` then
  `.toConversations()`. Do **not** re-implement; the mapper is the single decode-and-validate
  boundary and is pure/total over a validly-decoded payload.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionPump.kt:49-115` — the pump you
  consume: `val inbound: Flow<Envelope>` (hot, **single-consumer**, lossless, completes on
  teardown) and `fun send(envelope: Envelope): Boolean` (returns `false` if not `Open` — no
  throw). You depend on the new `SessionPump` interface, not this class directly.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt:37-44` — `Envelope(id:
  Long, type: String, ts: String, payload: JsonElement, inReplyTo: Long?)`; build the request and
  read responses against this exact shape.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt:21-27` — the `MobileJson`
  singleton; decode payloads through it, never a default `Json`.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConnectionStateSource.kt` +
  `FakeConnectionStateSource.kt` — the **precedent** for a consumer-defined interface living in
  `data/repository/` whose real impl lives in `data/network/`. `SessionPump` follows this pattern.
- `app/src/test/java/de/pyryco/mobile/data/network/NoiseSessionPumpTest.kt:1-35` — the **test
  idiom**: JUnit4 (`org.junit.Test`, `org.junit.Assert.*`), `runTest`, Turbine
  (`app.cash.turbine.test`), a hand-written fake transport (`MutableSharedFlow` inbound + a
  recorded list of sent frames). Mirror this for the fake `SessionPump`.
- `app/src/test/java/de/pyryco/mobile/data/network/ConversationsPayloadTest.kt` — how a
  `conversations` payload `JsonElement` fixture is built (`MobileJson.parseToJsonElement(raw)`);
  reuse the same object-wrapped-array fixture shape for the repository round-trip test.
- `gradle/libs.versions.toml` — confirm `kotlinx-coroutines`, `kotlinx-serialization-json`,
  `kotlinx-datetime`, Turbine, MockK already present. Add **no** dependency.
- Protocol SSOT (server repo): `docs/protocol-mobile.md § list_conversations → conversations` —
  request `list_conversations` (payload none/`{}`), response/push `conversations`
  (`{conversations:[…]}`, also **unsolicited on change**). The five no-message methods
  (`archive`/`unarchive`/`rename`/`startNewSession`/`changeWorkspace`) have no v2 wire type.

## Design

### Files (2 production + 1 test, all new — zero edits to existing files)

| File | Kind | Contents |
|---|---|---|
| `app/src/main/java/de/pyryco/mobile/data/repository/SessionPump.kt` | production | the consumer interface `SessionPump` |
| `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` | production | the repository class |
| `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt` | test | fake `SessionPump` + round-trip / filter / push / multi-collector / resilience / stub tests |

**Deliberately not modified:** `NoiseSessionPump.kt`. Making the real pump implement
`SessionPump` (`class NoiseSessionPump(...) : SessionPump`, two `override`s) is wiring that
belongs to the DI/connection-coordinator slice (#279 / #302), alongside providing the
repository's `CoroutineScope` and the live binding. Keeping that edit out of #312 holds the
production footprint to two new files and removes any overlap with crypto-adjacent code. **Open
question / hand-off below** flags this for the downstream slice.

### `SessionPump` — the consumed contract

Minimal consumer interface, structurally matching `NoiseSessionPump`'s two members so #279/#302
adds only `: SessionPump` + `override`. Lives in `data/repository/` per the `ConnectionStateSource`
precedent (consumer-defined; real impl in `data/network/`).

```kotlin
interface SessionPump {
    val inbound: Flow<Envelope>            // hot, single-consumer, decrypted app envelopes
    fun send(envelope: Envelope): Boolean  // false if the session is not Open; never throws
}
```

### `RemoteConversationRepository`

```kotlin
class RemoteConversationRepository(
    private val pump: SessionPump,
    scope: CoroutineScope,   // connection-scoped (DI, #279); tests pass runTest's backgroundScope
) : ConversationRepository
```

**Single source of state.** One private `MutableStateFlow<List<Conversation>?>` (`null` = list
not yet loaded). It is the demuxed projection of the pump's inbound stream; `observeConversations`
fans out from it. No parallel mutable state.

**Single inbound consumer (the fan-out owner).** `pump.inbound` is hot and single-consumer, so
the repository starts **one** long-lived collector in `init` on the injected `scope`. The
collector demultiplexes by `Envelope.type`:

- `type == "conversations"` → `MobileJson.decodeFromJsonElement<ConversationsPayload>(envelope
  .payload).toConversations()` → assign to the projection `StateFlow`. The `conversations`
  envelope is a **full list snapshot** (`{conversations:[…]}`). The protocol reply to our
  `list_conversations` request is `type: "conversations"` with `in_reply_to` set; #312 treats
  **any** inbound `conversations` snapshot as the list source of truth and re-assigns the
  projection — so re-emission needs no `inReplyTo` correlation (AC #3). `StateFlow` conflation
  means a value-equal snapshot does not re-emit.
- any other `type` → no-op in this slice (intentional `else`, not a bug). In particular,
  **`conversation_updated` / `conversation_created` are NOT handled here**: those are
  single-`Conversation` payloads mapped by #318's `ConversationResponseDto`, not #316's list
  mapper, and merging such a delta into the list projection is owned by the mutation/push slice
  that depends on #318 (#314). This is exactly why #312 depends on **#316 only, not #318**. #313
  (`messages`) and #329 (last-message) likewise extend this `when` in their own slices.

The decode is wrapped in a per-envelope `try/catch` (`SerializationException` /
`IllegalArgumentException`): a malformed `conversations` payload is **dropped** and the collector
**survives** (see Error handling). The `ConversationRepository` flow type has no error channel
and the Fake never errors, so dropping is the only interface-consistent option.

**`observeConversations(filter)`** — cold flow. On each collection it (1) issues the request via
`pump.send(listConversationsRequest())`, then (2) `emitAll` of the projection mapped through a
pure `project(list, filter)`:

```kotlin
override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>>
// flow { pump.send(listConversationsRequest()); emitAll(projection.filterNotNull().map { project(it, filter) }) }
```

`project(list, filter)` mirrors the Fake exactly: `when (filter) { All -> true; Channels ->
isPromoted && !archived; Discussions -> !isPromoted && !archived; Archived -> archived }` then
`sortedByDescending { lastUsedAt }`. `filterNotNull()` makes a collector block until the first
snapshot loads, then receive every subsequent change — satisfying "current projection on
subscription + every change" (AC #4). `StateFlow` conflation means a duplicate response (e.g.
from a second collector's redundant request) that is value-equal does not spuriously re-emit.

**`listConversationsRequest()`** — `Envelope(id = requestId.incrementAndGet(), type =
"list_conversations", ts = Clock.System.now().toString(), payload = JsonObject(emptyMap()))`,
where `requestId` is a private `AtomicLong`. Payload is `{}` per protocol (none/`{}`).

### Stubs (compile the full interface; later slices replace the ones they own)

Throw `UnsupportedOperationException` with a message naming the owning follow-up:

| Method(s) | Reason in the message | Owner |
|---|---|---|
| `observeMessages` | thread read path not yet wired | #313 |
| `observeLastMessage` | last-message via message read path | #329 |
| `sendMessage`, `createDiscussion`, `promote` | mutation path not yet wired | #314 |
| `archive`, `unarchive`, `rename`, `startNewSession`, `changeWorkspace` | no v2 wire message defined | follow-up specs the wire contract |

**Do not override** `delete`, `recentWorkspaces`, `createWorkspaceFolder` — they have interface
defaults (throw / empty flow) and are intentionally outside this slice's AC. Inheriting the
defaults is correct and keeps the surface minimal.

## State + concurrency model

- **One `StateFlow<List<Conversation>?>`** projection; **one** `viewModel`-independent collector
  of `pump.inbound` launched on the injected connection `scope`. The scope (and thus the
  collector) is cancelled by its owner (#279/#302) when the connection ends; the pump completing
  `inbound` on teardown also ends the collector naturally.
- Dispatcher: inherit from the injected `scope` (DI uses `Dispatchers.Default`; this is pure
  CPU/JSON work, no blocking I/O — the socket I/O is the transport's, below the pump). Do not
  hard-code a dispatcher.
- `observeConversations` is cold; N concurrent collectors share the one projection (fan-out), so
  multiple subscribers are supported with a single inbound consumer (AC #4).
- **send-on-each-subscribe** is intentional: redundant `list_conversations` requests are absorbed
  by `StateFlow` conflation, and re-subscribing (e.g. on lifecycle resume) naturally re-issues the
  request — more robust than a send-once guard if an early send was dropped pre-`Open`.

## Error handling

| Failure mode | Where | Result |
|---|---|---|
| Malformed `conversations` payload | inbound collector decode | `SerializationException` caught per-envelope; envelope **dropped**; collector survives; projection unchanged |
| `pump.send` returns `false` (session not `Open`) | request issue | request silently not sent (no throw); projection stays `null` until a later subscribe succeeds or a push arrives |
| `pump.inbound` completes (teardown) | collector | collector completes; last projection retained; live `StateFlow` collectors simply stop receiving updates (do not complete) |
| Unknown `Envelope.type` | collector `when` | no-op (owned by #313/#314/#329) |
| Stubbed method called | the stub | `UnsupportedOperationException` with the message above |

Rationale for catch-and-drop: an uncaught decode throw would kill the **single** inbound consumer,
silently freezing **all** future conversation updates for the connection — a severe, plausible
failure against an untrusted (post-auth) server payload. The mapper validates shape (#316); the
repository keeps the consumer alive. The pre-`Open` send loss is **not** defended here (no
buffering/retry-on-`Open`): the connection coordinator wires the repository against an `Open`
pump, and reconnect/re-request is explicitly out of scope (#302).

## Testing strategy

Unit only (`./gradlew test`), JUnit4 + `runTest` + Turbine, mirroring `NoiseSessionPumpTest`.
Write a hand-written **fake `SessionPump`**: `inbound` backed by a `MutableSharedFlow<Envelope>`
(use `extraBufferCapacity`/`replay` so test emits are not lost before the collector attaches);
`send` records each envelope in a list and returns `true`; expose a helper to push an inbound
envelope. Construct the repository with `backgroundScope` so the collector auto-cancels at test
end. Build `conversations` envelope payloads from the same object-wrapped-array fixture shape as
`ConversationsPayloadTest` via `MobileJson.parseToJsonElement(raw)`.

Scenarios (bullets, not full bodies — write in the project idiom):

- **Subscription issues `list_conversations` (AC #2).** Collect `observeConversations(All)`;
  assert the fake recorded exactly one sent envelope with `type == "list_conversations"` and a
  `{}` payload.
- **Seeding a `conversations` response surfaces the mapped list (AC #2, #5).** Push a 2-row
  `conversations` envelope; assert `observeConversations(All)` emits two mapped `Conversation`s,
  `lastUsedAt`-descending, with `id`/`name`/`cwd`/`isPromoted` from the fixture and the four
  list-tier placeholder fields at their #316 defaults.
- **Filter applied (AC #2).** With a fixture mixing promoted, unpromoted, and archived rows:
  `Channels` → promoted && !archived only; `Discussions` → unpromoted && !archived only;
  `Archived` → archived only; `All` → every row. Assert ordering is `lastUsedAt`-desc in each.
- **Server push re-emits (AC #3).** Via Turbine, push one snapshot, await the first emission,
  push a second (changed) snapshot, await the second emission. Assert two distinct lists.
- **Multiple concurrent collectors (AC #4).** Two simultaneous `observeConversations` collectors;
  push one snapshot; assert both receive it (shared fan-out, single inbound consumer).
- **Malformed payload is dropped, collector survives (Error handling).** Push a `conversations`
  envelope with a row missing a required field, then a valid snapshot; assert only the valid
  snapshot surfaces and the flow keeps working (collector did not die).
- **Unknown type is ignored.** Push an envelope of an unrelated `type` (e.g. `messages`); assert
  no emission/no crash.
- **Stubs throw.** `assertThrows(UnsupportedOperationException)` for a representative set —
  `observeMessages`, `observeLastMessage`, `sendMessage`, `createDiscussion`, `promote`,
  `archive`, `rename` — confirming the message names the owning follow-up.

## Open questions / hand-off

- **`NoiseSessionPump : SessionPump` + live DI binding (#279 / #302).** This slice defines
  `SessionPump` and consumes it; the downstream wiring slice must (a) add `: SessionPump` + two
  `override`s to `NoiseSessionPump`, (b) provide the connection-scoped `CoroutineScope`, and (c)
  swap the Koin binding `ConversationRepository` from `FakeConversationRepository` to
  `RemoteConversationRepository` when paired/connected. Gate the live binding on paired state per
  [[phase4-no-central-flag-gate-per-piece]]; there is no central Phase-4 flag.
- **Pre-`Open` request loss.** If a subscription's `send` lands before the handshake completes,
  the request is dropped and the list stays empty until the next subscribe or a server push.
  Acceptable for this slice (the coordinator wires against an `Open` pump); if observed in
  practice, the fix is a re-request on `PumpState.Open`, owned by #302.
- **`isSleeping` / session detail in the list.** The list payload carries none; #316 maps these
  to defaults. Per-conversation sleep/session enrichment arrives via the detail/message read
  paths (#313+), not here.
- **`conversation_updated` single-row delta-merge.** The v2 server's documented per-conversation
  push (`conversation_updated`, binary → phone, mapped by #318's `ConversationResponseDto`) is a
  single-`Conversation` delta, not a full `conversations` snapshot. #312 demuxes it to no-op.
  Merging a delta into the live list projection (so a promote/rename/archive made elsewhere
  re-emits without a full re-`list_conversations`) is a follow-up owned by the #318-dependent
  slice (#314). Until then, in production the list refreshes on the next `conversations` snapshot
  (re-subscribe / reconnect); AC #3's re-emit is verified against a `conversations`-snapshot push
  via the test double.
