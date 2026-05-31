# Remote conversation repository — the Phase 4 `ConversationRepository`

The **live, server-backed implementation** of the [`ConversationRepository`](conversation-repository.md)
contract — the Phase 4 counterpart to the in-memory `FakeConversationRepository`. It reads real server
state over the [Mobile Protocol v2](mobile-protocol-v2-wire-layer.md) Noise session instead of an
in-process seed store, and is swapped in for the Fake via a Koin module (per CLAUDE.md: the Phase 4
backend swap is architectural — replace the binding, don't special-case the UI).

Package: `de.pyryco.mobile.data.repository` (`RemoteConversationRepository` + the consumer-defined
`SessionPump` interface), same package as the contract and the Fake. Built **slice by slice**: the
conversation-list read path landed in [#312](../codebase/312.md); the thread read path (#313), the
mutation path (#314), and the last-message preview (#329) extend the **same class** as they land. Portable,
`android.*`-free.

> **Ships dormant (no live binding yet).** #312 lands `RemoteConversationRepository` with **no Koin
> binding and no consumers** — the UI still binds to `FakeConversationRepository`. Wiring the live binding
> (and making the concrete pump satisfy `SessionPump`) is the DI/connection-coordinator slice's job
> ([#279](https://github.com/pyrycode/pyrycode-mobile/issues/279) / [#302](../codebase/302.md)), gated on
> paired state — see [Hand-off](#hand-off--the-live-binding-279--302). Adding a binding before then would
> be dead code.

## Where it sits in the Phase 4 stack

```
UI ViewModels  ◀── observeConversations(filter): Flow<List<Conversation>>   (binding-agnostic: Fake or Remote)
        ▲
RemoteConversationRepository (#312+)   ◀── this doc
        │  send(list_conversations) ; collect inbound conversations snapshots
        ▼  (over the SessionPump interface)
NoiseSessionPump (#309) ─ inbound: Flow<Envelope> / send(Envelope): Boolean
        ▼
RelayTransport (#306) ─ OkHttp WS ─ Noise_IK (#303)
```

The repository consumes the pump over the **portable `SessionPump` interface** — it never reaches below
the pump to the raw frame transport or the Noise session, and it never re-implements wire↔domain mapping
(that is the [#316](mobile-protocol-v2-wire-layer.md#application-payloads-decoded-on-top-of-envelope)
mapper's job). It runs **behind** the already-authenticated Noise channel: the internet-exposed frames +
crypto are the pump/transport's concern, and the untrusted-payload decode-and-validate boundary is the
#316 mapper — so the repository is plain orchestration over already-authenticated, delegated-decode data
(not `security-sensitive`).

## The `SessionPump` consumed contract

A minimal **consumer-defined** interface — the data layer's view of the Noise session pump:

```kotlin
interface SessionPump {
    val inbound: Flow<Envelope>            // hot, single-consumer, decrypted app envelopes
    fun send(envelope: Envelope): Boolean  // false if the session is not Open; never throws
}
```

It lives in `data/repository/` (next to its consumer), **not** in `data/network/` (where the concrete
`NoiseSessionPump` lives), mirroring the [`ConnectionStateSource`](connection-state.md) precedent: the
consumer defines the contract it needs; the real impl satisfies it a layer down. The two members match
`NoiseSessionPump`'s structurally, so the DI slice makes the concrete class conform with just
`: SessionPump` + two `override`s.

`inbound` is **hot and single-consumer** (the pump runs the one inbound collector regardless of
subscribers and surfaces each decrypted `Envelope` exactly once). `send` returns `false` (no throw) if
the session is not `Open`. Note the pump's consumer-awareness rule: a `false` return on an `Open` session
means the session is **spent** (a nonce was consumed) — do **not** re-send the same envelope on the same
session; the supervisor will reconnect with a fresh handshake. The repository does not buffer or retry —
see [Error handling](#error-handling).

## The repository — one projection, cold fan-out

```kotlin
class RemoteConversationRepository(
    private val pump: SessionPump,
    scope: CoroutineScope,   // connection-scoped (DI, #279/#302); tests pass runTest's backgroundScope
) : ConversationRepository
```

**Single source of state.** One `private val projection = MutableStateFlow<List<Conversation>?>(null)`
(`null` = list not yet loaded). It is the demuxed projection of the pump's inbound stream; every cold read
derives from it. No parallel mutable state.

**Single inbound consumer (the fan-out owner).** Because `pump.inbound` is hot and single-consumer, the
repository launches **exactly one** long-lived collector in `init` on the injected `scope`. That collector
demultiplexes each envelope by `Envelope.type`:

| `Envelope.type` | Handling |
|---|---|
| `"conversations"` | Decode `MobileJson.decodeFromJsonElement<ConversationsPayload>(payload).toConversations()` (#316) → assign to `projection`. A **full-list snapshot** — both the reply to our request and any unsolicited server change-push arrive this way, so re-emission needs **no `in_reply_to` correlation**. Decode is wrapped in a per-envelope `try/catch` (a malformed snapshot is dropped, the collector survives). |
| anything else | **No-op in this slice** (intentional `else`, not a bug). `messages` (#313), single-row `conversation_updated`/`conversation_created` deltas (#318 → #314), and last-message (#329) extend this `when` in their own slices. |

> **Why single-row deltas are not handled here.** `conversation_updated` / `conversation_created` are
> single-`Conversation` payloads mapped by [#318](mobile-protocol-v2-wire-layer.md#application-payloads-decoded-on-top-of-envelope)'s
> `ConversationResponseDto`, **not** #316's list mapper. Merging such a delta into the live list
> projection (so a promote/rename/archive made elsewhere re-emits without a full re-`list_conversations`)
> is owned by the mutation slice (#314), which depends on #318. This is exactly why the read path depends
> on **#316 only, not #318**. Until then, the production list refreshes on the next `conversations`
> snapshot (re-subscribe / reconnect).

## `observeConversations(filter)` — the live method

A **cold** `Flow`. On each collection it:

1. issues the request — `pump.send(listConversationsRequest())`, where the request is `Envelope(id =
   <AtomicLong>.incrementAndGet(), type = "list_conversations", ts = Clock.System.now().toString(),
   payload = JsonObject(emptyMap()))` (payload `{}` per protocol);
2. `emitAll`s the projection mapped through a pure `project(list, filter)` — `filterNotNull()` first, so a
   collector **blocks until the first snapshot loads**, then receives the current projection on
   subscription and every subsequent change.

`project(list, filter)` mirrors the Fake **exactly** so the UI behaves identically under either binding:

```
filter:  All        -> every row
         Channels   -> isPromoted && !archived
         Discussions-> !isPromoted && !archived
         Archived   -> archived
then:    sortedByDescending { lastUsedAt }
```

N concurrent collectors share the one projection (cold fan-out over a single hot inbound consumer) — the
multi-collector requirement is met with a single inbound consumer.

**send-on-each-subscribe is intentional.** Redundant `list_conversations` requests are absorbed by
`StateFlow` conflation (a value-equal snapshot does not re-emit), and re-subscribing (e.g. on lifecycle
resume) naturally re-issues the request — more robust than a send-once guard if an early send was dropped
pre-`Open`.

### List-tier placeholders (from #316)

The `conversations` wire summary does not carry full session/sleep/archive state, so the #316 mapper fills
four domain fields with documented list-tier defaults — `currentSessionId = ""`, `sessionHistory =
emptyList()`, `isSleeping = false`, `archived = false` — never `null`-punned. Full enrichment arrives via
the detail/message read paths (#313+), not here. (This is why `Archived` is empty under the pure list
path until a richer source lands: the list snapshot never carries `archived = true`.) The wire's
`last_message_ts` maps to **no** domain field — the last-message preview is #329's job over the message
read path, not derivable from this payload. See [[v2-app-payload-shapes-ssot]].

## Stubs — the full interface compiles; later slices replace what they own

Every method other than `observeConversations` throws `UnsupportedOperationException` with a message
naming the owning follow-up, so the class compiles the full interface today and each slice replaces only
the methods it owns:

| Method(s) | Owner |
|---|---|
| `observeMessages` | #313 (thread read path) |
| `observeLastMessage` | #329 (last-message via the message read path) |
| `sendMessage`, `createDiscussion`, `promote` | #314 (mutation path) |
| `archive`, `unarchive`, `rename`, `startNewSession`, `changeWorkspace` | follow-up (no v2 wire message defined yet) |

`delete`, `recentWorkspaces`, and `createWorkspaceFolder` are **not overridden** — they have interface
defaults (error / empty flow per the [contract](conversation-repository.md)) and are intentionally outside
this implementation's surface.

> **Stub shape caveat (for #313/#329).** The two `Flow`-returning stubs use an expression-body `throw`, so
> they throw **eagerly on call** rather than returning a `flow { throw … }` that throws on collection. This
> is a deliberate, test-asserted stub (no production consumer reaches them before #313/#329 land), but the
> owning slices should wire **real cold flows** that defer work to collection.

## State & concurrency model

- **One `StateFlow<List<Conversation>?>` projection; one inbound collector** launched on the injected
  connection `scope`. The scope (and thus the collector) is cancelled by its owner (#279/#302) when the
  connection ends; the pump completing `inbound` on teardown also ends the collector naturally.
- **Dispatcher inherited from the injected scope** (DI uses `Dispatchers.Default`; this is pure CPU/JSON
  work — the socket I/O is the transport's, below the pump). Not hard-coded.
- `observeConversations` is cold; N concurrent collectors share the one projection (fan-out).

## Error handling

| Failure mode | Result |
|---|---|
| Malformed `conversations` payload | `IllegalArgumentException` caught per-envelope (covers both #316 families — `MissingFieldException` ⊂ `SerializationException`, and the kotlinx-datetime bad-timestamp throw); envelope **dropped**; collector survives; projection unchanged |
| `pump.send` returns `false` (session not `Open`) | request silently not sent (no throw); projection stays `null` until a later subscribe succeeds or a push arrives |
| `pump.inbound` completes (teardown) | collector completes; last projection retained; live `StateFlow` collectors simply stop receiving updates (do not complete) |
| Unknown `Envelope.type` | no-op (owned by #313/#314/#329) |
| Stubbed method called | `UnsupportedOperationException` naming the owning follow-up |

**Why catch-and-drop:** the `ConversationRepository` flow type has no error channel and the Fake never
errors, so dropping is the only interface-consistent option. An uncaught decode throw would kill the
**single** inbound consumer, silently freezing **all** future conversation updates for the connection — a
severe failure against an untrusted (post-auth) server payload. The #316 mapper validates shape; the
repository keeps the consumer alive. Pre-`Open` send loss is **not** defended here (no buffering /
retry-on-`Open`): the coordinator wires the repository against an `Open` pump, and reconnect/re-request is
out of scope (#302).

## Hand-off — the live binding (#279 / #302)

The downstream DI / connection-coordinator slice must:

1. make the concrete pump conform — `class NoiseSessionPump(...) : SessionPump` + two `override`s (its
   members already match);
2. provide the connection-scoped `CoroutineScope` the repository's inbound collector runs on;
3. swap the Koin binding `ConversationRepository` from `FakeConversationRepository` to
   `RemoteConversationRepository` **when paired/connected** — gate the live binding on paired state per
   [[phase4-no-central-flag-gate-per-piece]] (there is no central Phase-4 flag).

Open hand-off items: **pre-`Open` request loss** (if a subscribe's `send` lands before the handshake
completes, the list stays empty until the next subscribe or a server push — the fix, if observed, is a
re-request on `PumpState.Open`, owned by #302); **`conversation_updated` delta-merge** (owned by the
#318-dependent #314); **`isSleeping`/session enrichment** in the list (arrives via the detail/message read
paths, not here).

## Testing

JVM unit only (`app/src/test/.../data/repository/RemoteConversationRepositoryTest.kt`, `./gradlew test`),
JUnit4 + `runTest` + a hand-written **fake `SessionPump`** — `inbound` backed by a
`Channel<Envelope>(UNLIMITED).receiveAsFlow()` (so test pushes are not lost before the collector attaches);
`send` records each envelope and returns `true`; a `push` helper feeds inbound. The repository is
constructed with `backgroundScope` so its collector auto-cancels at test end. `conversations` payloads are
built from the same object-wrapped-array fixture shape as `ConversationsPayloadTest` via
`MobileJson.parseToJsonElement(raw)`.

> **Test idiom (reusable across the sibling slices #313/#314/#329):** drive the push→demux→project→emit
> cascade with **`runCurrent()`, not `advanceUntilIdle()`**. With a `Channel.receiveAsFlow()` inbound
> feeding a single repository-internal collector on `backgroundScope`, `advanceUntilIdle()` does not
> deliver the buffered channel item to the background collector (there are no timers to elapse), leaving
> projection-dependent assertions empty; `runCurrent()` drains the whole current-time cascade
> deterministically. See [[remote-repo-test-runcurrent-not-advanceuntilidle]] and
> [`codebase/312.md`](../codebase/312.md) § Lessons learned.

## Related

- Contract + Phase 1 binding: [Conversation repository](conversation-repository.md)
  (`ConversationRepository` interface, `ConversationFilter`, `ThreadItem`; the in-memory
  `FakeConversationRepository` this is the live counterpart to).
- Consumes: [Noise session pump](noise-session-pump.md) ([#309](../codebase/309.md)) — the `inbound` /
  `send` surface, over the `SessionPump` interface. [Mobile Protocol v2 wire layer](mobile-protocol-v2-wire-layer.md)
  — `Envelope`, `MobileJson`, and the [#316](../codebase/316.md) `ConversationsPayload.toConversations()`
  decode-and-validate boundary. [Data model](data-model.md) — the domain `Conversation` it produces.
- Precedent: [Connection state](connection-state.md) — the consumer-defined-interface-in-`data/repository/`
  pattern `SessionPump` follows.
- Ticket notes: [`../codebase/312.md`](../codebase/312.md) (the list read path — files/line refs,
  patterns, lessons, verification).
- Spec: `docs/specs/architecture/312-remote-conversation-repository-observe-list.md`.
- Siblings (extend the same class + `onInbound` `when`): #313 (`observeMessages`, consumes
  [#317](../codebase/317.md)), #314 (mutations, consumes [#318](../codebase/318.md)), #329
  (`observeLastMessage`, consumes #317 + a per-conversation read).
- Hand-off: [#279](https://github.com/pyrycode/pyrycode-mobile/issues/279) / [#302](../codebase/302.md)
  (`NoiseSessionPump : SessionPump`, the connection scope, the paired-state Koin swap).
</content>
