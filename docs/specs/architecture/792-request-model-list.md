# #792 — ask for a model menu the connect burst did not cover

Send `request_model_list` for a conversation this connection holds no menu for, once, and apply the
answer through the retention #791 already built. **Renders nothing** — the composer's model and effort
controls are #649, which reads the retained menu.

## Files read

| Path | Symbol | Why it matters |
|---|---|---|
| `../pyrycode/docs/protocol-mobile.md` (sibling checkout at `Workspace/Projects/pyrycode`) | § *Asking for a model list on demand*, § `request_model_list` | Wire SSOT: the one-key payload, the no-request-id correlation, the two refusal codes and their retryability, the `interactive` inertness rule, and "the answer is a `model_list` — this frame, unchanged". Cited, never restated. |
| `app/src/main/java/de/pyryco/mobile/data/network/ModelListPayloads.kt` | `ModelListPayloadDto`, `toMenu` | #791's decode boundary, which this ticket's reply lands through unchanged. The new request DTO joins this file. |
| `app/src/main/java/de/pyryco/mobile/data/network/SessionSettingsPayloads.kt` | `RequestSessionSettingsPayloadDto` | The named precedent for the request payload: one always-present key, encode-only, no request-id key because correlation rides `Envelope.inReplyTo`. |
| `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt` | `ErrorPayload`, `RelayErrorException` | The refusal's decode shape. `code` is the field the branch reads; `message` is daemon-authored prose the branch must never pair with a conversation id. |
| `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` | `onInbound`'s `TYPE_MODEL_LIST` and `TYPE_ERROR` arms, `observeModelMenu`, `modelMenusByConversation`, `pendingRequests`, `sendAndAwaitReply`, `mapError`, `requestId`, `negotiatedCapabilities` | The whole surface this ticket edits. The two arms are where the asymmetry lives; `pendingRequests`/`sendAndAwaitReply` is the idiom that does **not** transfer, and the reason is recorded in Design. |
| `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` | `interrupt`, `requestDebugBundle` | The two fire-and-forget send shapes already in this class: `interrupt`'s bare `pump.send` and `requestDebugBundle`'s non-suspending, non-throwing, one-attempt-per-connection guard. This ticket's ask is the second shape. |
| `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` | `observeSessionSettings`, `sessionSettingsRead`, `readSessionSettings` | The precedent that a *reading* may issue its own request, and the precedent for gating that request fail-closed on `interactive` so it never suspends against a conn the daemon leaves inert. |
| `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` | `observeModelMenu`, `switchToLive` | Confirms a host switch re-subscribes the live reading, which is what makes "once per connection" the right scope for the one-shot ledger. |
| `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt` | `FakeSessionPump.sent`, `collectModelMenu`, `modelListEnvelope`, `errorEnvelope` | The fixtures this ticket's tests extend. `sent` is how "asked once" is asserted; `modelListEnvelope` needs an `inReplyTo` parameter it does not have yet. |
| `docs/knowledge/features/remote-conversation-repository-control-sends.md` | § `interrupt`, § `requestHistory`, § `dropQueuedMessage` | The control-send family's standing rules: fire-and-forget uses plain `pump.send` and never `sendAndAwaitReply`; a new correlated reply type unregistered on the success arm hangs its waiter forever; the never-log posture for every id and payload on these paths. |
| `docs/specs/architecture/791-model-list-retention.md` | § Design, § Security review | The retention this reply rides, and the finding that handed the render-boundary obligation to #649 — this ticket inherits that split and adds no render site. |
| `../pyrycode-desktop/src/renderer/src/store/modelListBridge.ts` | `requestModelList`, the file header | The reference the ticket names for the no-retry rule, and for the falsy-id guard: an unaddressable id is not sent rather than sent and refused. |

## Design source

**Figma:** N/A — this ticket renders nothing. It adds one outbound wire frame and the bookkeeping around
its two replies; no composable, drawable, theme token or screen is touched, so the visual-fidelity check
is intentionally not applicable. The render boundary for these strings stays with #649.

## Context

#791 consumes both of the daemon's unsolicited delivery paths — the live lane's per-spawn frame and the
connect-time reconcile burst — and retains what they carry per conversation. Between them sits a window
neither covers: a conversation created *after* the phone connected crosses no delivery edge at all, so
`observeModelMenu` reports it unavailable for the whole life of the connection with nothing to wait for.

`request_model_list` is the third and last way a client gets a menu and the only one it can trigger
itself. Its answer is a `model_list` frame — the same frame, the same payload source, correlated by
`in_reply_to` and carrying no `event_id` — so the reply needs no second decode, no second payload type
and no second application path. What is new is the ask, the discipline governing when it fires, and two
refusals worth telling apart.

No ADR is warranted. This follows the class's established control-send pattern; the one genuinely new
idea (a correlation ledger beside `pendingRequests`) is a local consequence of an existing asymmetry
rather than a new architectural position, and it is argued in place below.

## Design

### The asymmetry, and why the correlated-request idiom does not transfer

Every correlated verb in this class runs through `sendAndAwaitReply`: register a `CompletableDeferred`
in `pendingRequests` under the request's envelope id, send, await. The single inbound collector completes
it from the success arm (on a reply type listed there) or exceptionally from the `TYPE_ERROR` arm.

This verb's two replies do not both reach that machinery, and cannot be made to without cost:

- A **success** is a `model_list` envelope. It is handled by #791's own arm, which routes it by the
  payload's `conversation_id` and replaces that conversation's retained menu. It does not complete a
  waiter, and it must not start doing so conditionally on `in_reply_to` either: that arm serves the two
  unsolicited paths as its primary job, and teaching it to also settle a deferred would put a
  broadcast-shaped frame into the correlated-reply registry where a stale or duplicated one could land
  in a slot it was never addressed to.
- A **refusal** is an `error` envelope, correlated through `pendingRequests` by `in_reply_to`.

So `sendAndAwaitReply` here would suspend until the connection tore down on *every success*, which is
the one outcome the verb exists to produce. Two further reasons rule out awaiting at all:

1. **AC #4 forbids waiting on a reply that cannot come.** A conn that did not negotiate `interactive`
   is answered with nothing — no menu, no error, no signal.
2. **The ask fires from a reading's subscription** (below). A suspending ask there would withhold the
   reading's first emission until the daemon answered, turning a normal `null` into a stall.

**The design, therefore: a fire-and-forget send plus a purpose-built correlation ledger the inbound
collector consults.** The refusal is observed synchronously, inside the collector that already runs, with
no deferred, no waiter, no timeout and no coroutine to own. `pendingRequests` is left exactly as it is.

### Two pieces of connection-scoped bookkeeping — `RemoteConversationRepository.kt`

```kotlin
// conversationId -> this connection already sent an ask naming it
private val askedModelMenus = ConcurrentHashMap.newKeySet<String>()

// request envelope id -> the conversation that ask named; live only until its reply lands
private val modelListAsks = ConcurrentHashMap<Long, String>()
```

They have different lifetimes, which is why they are two and not one. `askedModelMenus` is the one-shot
ledger and outlives the exchange; `modelListAsks` is the correlation entry and is consumed by the reply.
Both are `java.util.concurrent` for the same reason `pendingRequests` is: written from arbitrary
collector coroutines (the ask) and read from the single inbound coroutine (the replies).

Neither is keyed by, or derived from, anything the daemon sends. The map key is the client-minted
`requestId` ordinal; the set holds ids the client already had.

### The ask — `askForModelMenu(conversationId)`

Non-suspending and non-throwing, the `requestDebugBundle` posture. Guards in order, each returning
without sending:

1. **`conversationId` is empty** — the empty string names nothing and is refused daemon-side, so it is
   the same failure spelled differently rather than a second case. Desktop's falsy guard, same reasoning.
2. **`interactive` was not negotiated** — AC #4. Nothing is sent; nothing is waited on.
3. **This connection already holds a menu for it** — AC #1's "a conversation that already holds a menu
   is not asked again", read off `modelMenusByConversation.value`.
4. **`askedModelMenus.add(conversationId)` returned `false`** — already asked. An atomic test-and-set,
   so two collectors starting at once still produce one ask rather than racing a check against a write.

Past the guards it mints `requestId.incrementAndGet()`, records `modelListAsks[id] = conversationId`
**before** sending (the `sendAndAwaitReply` no-lost-reply ordering), encodes
`RequestModelListPayloadDto` and calls `pump.send` inside a `try`/`catch`. A send that returns `false`
(not `Open`) or throws rolls **both** entries back, because an ask that never left is not an ask — that
is not a retry, since nothing re-sends; it only declines to burn the one shot on a frame the transport
refused.

The ask is **not** exposed on `ConversationRepository`, the facade or the fake. It is a property of the
live connection's reading, so nothing outside this class can trigger one, and no consumer call site
changes.

### The trigger — `observeModelMenu`'s subscription

```kotlin
override fun observeModelMenu(conversationId: String): Flow<ModelMenu?> =
    modelMenusByConversation.map { it[conversationId] }.distinctUntilChanged()
        .onStart { askForModelMenu(conversationId) }
```

Desktop fires its ask from conversation activation, "the only place the conversation to name is known".
Mobile's equivalent seam that exists today is this reading: subscribing to a conversation's menu *is*
wanting it. Putting the ask here rather than behind a new public method is what keeps the ticket
self-contained — #649 gets the ask by collecting the reading it already has to collect, with no second
wiring step to forget — and it is the `observeSessionSettings` precedent, where a reading issues its own
request.

The ask is non-suspending, so the first emission is not delayed; the reading still emits `null`
immediately for a conversation with no retained menu, exactly as it did before.

### The replies — two arms of the existing demux

**Success, `TYPE_MODEL_LIST`.** Inside the existing `interactive` gate and *before* the existing decode:
`envelope.inReplyTo?.let(modelListAsks::remove)`. It consumes the correlation entry and nothing else —
the retention write below it is untouched and stays routed by the payload's own `conversation_id`, never
by the correlation. The removal is unconditional on decode success: a malformed answer is still an
answer, and leaving the entry would leak it until the connection died. `askedModelMenus` is **not**
released here; the ask was answered.

**Refusal, `TYPE_ERROR`.** The arm keeps its `pendingRequests` completion verbatim and gains a second,
independent lookup against `modelListAsks`. The two maps are disjoint by construction — an ask registers
in one and never the other — so an id resolves in at most one of them and neither lookup can consume
the other's reply.

`onModelListRefusal(conversationId, payload)` decodes `ErrorPayload` through `MobileJson` and branches on
`code` alone:

| Code | Meaning | Effect |
|---|---|---|
| `model_list.unavailable` | The daemon hosts it but has no vocabulary to answer with yet | Release the `askedModelMenus` entry, so **a later trigger may ask again** |
| `conversation.not_found` | The daemon does not host what was named | Keep the entry — **terminal for that id** on this connection |
| any other code, or a payload that will not decode | Unknown | Keep the entry — fail closed, treated as terminal |

**Neither branch writes `modelMenusByConversation`**, so neither turns into an empty menu and both leave
the conversation unavailable (AC #2). The reading is untouched by a refusal.

`mapError` is deliberately **not** reused: it collapses `conversation.not_found` into an
`IllegalArgumentException` and so discards the very distinction this branch exists to draw. It stays
unchanged for every other verb.

### Why releasing on the retryable code is not a retry

AC #3 forbids a retry loop, and this design contains no loop to forbid: no timer, no backoff, no
scheduled re-send, no re-subscription, and nothing anywhere that reacts to a failure by sending again.
Releasing the one-shot entry on `model_list.unavailable` states the wire's own contract — *the same
request may succeed later* — by declining to suppress a future, externally-triggered ask. A refusal with
the collector still subscribed sends nothing further; only a **new** subscription asks again, and this
design creates no new subscriptions. A test asserts exactly that, because the distinction between "may
succeed later" and "retries" is the whole of this ticket's discipline.

A reply that never arrives, and a timeout, need no handling at all: there is no waiter to expire. The
conversation stays unavailable and the next connect's burst is what fills it.

### The request payload — `ModelListPayloads.kt`

```kotlin
@Serializable
internal data class RequestModelListPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
)
```

Encode-only, one always-present key, no request-id key — the `RequestSessionSettingsPayloadDto` /
`RequestHistoryPayloadDto` decision, since correlation rides `Envelope.inReplyTo`. `internal`, joining
its file's existing visibility rather than the public request DTOs elsewhere; the repository is in the
same module, so nothing needs widening.

`TYPE_REQUEST_MODEL_LIST = "request_model_list"` joins the companion registry beside `TYPE_MODEL_LIST`,
and `ERROR_MODEL_LIST_UNAVAILABLE = "model_list.unavailable"` joins it beside
`ERROR_CONVERSATION_NOT_FOUND`.

## State + concurrency model

Two new connection-scoped `java.util.concurrent` collections and **no new coroutine, scope, dispatcher,
job or cancellation path**. The ask runs inline on whichever collector coroutine subscribed, does no I/O
beyond the pump's non-suspending `send`, and completes before the flow's first emission. Both replies are
handled inside the single existing `init` inbound collector.

There is no check-then-act across a suspension point anywhere, because there is no suspension point on
either path. The one place two coroutines could race — two collectors subscribing to the same
conversation at once — is resolved by `Set.add`'s atomic test-and-set rather than by a read followed by a
write. The `modelMenusByConversation` guard is a plain snapshot read and is deliberately not atomic with
the `add`: the worst case is one redundant ask for a menu that landed in the same instant, which the
daemon answers idempotently.

Lifecycle is the connection's: `LifecycleConnectionDriver`'s background close destroys the repository and
both collections with it, and a fresh connection starts with an empty ledger and re-receives the
reconcile burst. That reset is the only one this state has, and it is what makes "once" mean "once per
connection".

## Error handling

| Failure | Result |
|---|---|
| Not `interactive` | Not sent. No wait, no error, no reading change. |
| Empty conversation id | Not sent. |
| Pump not `Open`, or `send` throws | Both ledger entries rolled back; the reading emits normally. Nothing propagates to the collector. |
| `conversation.not_found` | Terminal for that id on this connection. Reading unchanged (unavailable). |
| `model_list.unavailable` | One-shot released. Nothing re-sent. Reading unchanged (unavailable). |
| Any other code / undecodable `error` | Treated as terminal. Reading unchanged. |
| Reply never arrives | Nothing. No waiter exists to expire. |
| Malformed `model_list` reply | #791's existing decode-or-drop; the correlation entry is still consumed. |

Nothing on any of these paths throws into a collector, and nothing surfaces to the UI — this slice has no
UI. **Nothing logs on any branch**: the conversation id is a cross-conversation correlation key, and a
refusal's `message` is daemon-authored prose, so the pair is exactly what the ticket's security note
forbids putting in one line. The branch reads `code` and discards the rest of the payload.

## Testing strategy

Unit only (`./gradlew testDebugUnitTest`). No composable, no device behaviour, no operator-facing flow —
this renders nothing, so no Compose UI test, no emulator rung and no rung-3 scenario is owed. #649 carries
the operator-facing proof when the controls land.

**`ModelListPayloadsTest`** additions — the request shape:
- the encoded payload is exactly one key, `conversation_id`, carrying the id verbatim;
- an empty id still encodes the key rather than eliding it under `explicitNulls = false`.

**`RemoteConversationRepositoryTest`** additions — the ask and the two refusals. `modelListEnvelope`
gains a defaulted `inReplyTo` parameter so a fixture can be a correlated reply.
- subscribing to a conversation with no retained menu sends exactly one `request_model_list` naming it,
  and the payload is that one key;
- a second and third collector on the same conversation send nothing further (asked once);
- a conversation whose menu already arrived unsolicited is never asked;
- the correlated `model_list` reply is applied through the same retention path a broadcast frame takes,
  and a subsequent subscription does not re-ask;
- without `interactive`, nothing at all is sent;
- an empty conversation id sends nothing;
- a not-`Open` pump sends nothing that lands, and the reading still emits `null`;
- **`conversation.not_found`** leaves the reading unavailable and a later subscription does **not** ask
  again;
- **`model_list.unavailable`** leaves the reading unavailable, sends nothing further while the collector
  stays subscribed, and a later subscription **does** ask again;
- an unrecognised code and an undecodable `error` are both terminal;
- a refusal correlated to a `pendingRequests` waiter still fails that waiter, and a refusal correlated to
  an ask does not disturb any waiter — the two correlation paths do not interfere;
- the existing #791 retention assertions still hold with the ask wired in.

Fakes throughout (`FakeSessionPump`), no MockK.

## Open questions

1. **Should the ask instead be a public method on `ConversationRepository` for #649 to call?** Resolved
   at design time: no. It would put an easily-forgotten second wiring step in a later ticket and widen
   three types (interface, facade, fake) for a behaviour that is purely a property of the live
   connection. Revisit only if #649 finds it needs to ask at a moment when nothing is collecting the
   reading — and then in that ticket.
2. **Should an unrecognised refusal code release the one-shot instead of holding it?** Resolved: hold it.
   Fail closed — an unknown code is not a statement that asking again would help, and the next connect's
   burst is the recovery path the ticket names.

## Documentation handoff

Pending for the documentation stage; this ticket writes no `docs/knowledge/` file.

- `docs/knowledge/features/mobile-protocol-v2-wire-layer-application-payloads.md` — the
  `request_model_list` request payload beside the `model_list` family #791 documents, including the
  no-request-id correlation (the answer is the same `model_list` frame, correlated by `in_reply_to` and
  carrying no `event_id`).
- `docs/knowledge/features/remote-conversation-repository-control-sends.md` — the send, its one-shot
  triggering rule, the split success/refusal reply paths (why `sendAndAwaitReply` does not transfer and
  what the correlation ledger does instead), and why no retry exists.

## Sizing

Re-counted against this written plan: **2 production source files** (`ModelListPayloads.kt` and
`RemoteConversationRepository.kt`, both modified) — under the ceiling of 5. **0 new exported types**
(the request DTO is `internal`; no public signature changes). **0 consumer call sites** need a
simultaneous update — `observeModelMenu` keeps its signature and every existing caller compiles
unchanged. **4 acceptance criteria. 3 refusal branches** (`conversation.not_found`,
`model_list.unavailable`, other/undecodable) plus five non-sending guards. **Total written work ≈ 570
lines** including this plan and the tests — under the 800-line ceiling.

The refiner's estimate said ~320 lines across 4 production files. Files came out lower because the ask
needs no interface, facade or fake change; lines came out higher because the correlation ledger and the
refusal branch carry their own KDoc at this file's density. Both numbers sit inside the boundary, so the
ticket stands as one.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries] SHOULD FIX — the correlation must never become the retention's routing key, and
  the plan states that but an implementer is one line away from getting it wrong.** The boundary itself
  is explicit and singular: the inbound `error` crosses it at one named function, `onModelListRefusal`,
  which decodes `ErrorPayload` through `MobileJson` and reads **`code` only** — `message` and
  `retryable` are never read, never stored and never surfaced. The hazard is on the success arm. The
  tempting shape, `modelListAsks.remove(inReplyTo)?.let { askedId -> retain(askedId, menu) }`, would let
  a hostile or buggy daemon answer an ask naming A with a payload naming B and have B's rows land under
  A — a cross-conversation injection that #791 structurally foreclosed by routing on the payload's own
  `conversation_id` and nothing else. Phase B therefore consumes the correlation entry **and discards
  it**, keeping `decodeModelList`'s routing untouched, and lands an adversarial test: a correlated reply
  whose payload names a different conversation than the ask must retain under the **payload's** id, with
  the asked conversation left unavailable.
- **[Trust boundaries] The outbound id, and the bound on what the ledgers can hold.** `conversationId`
  crosses outward as a payload **value** only: never a path component, a filename, a cache lookup or a
  log field. It is a key in `askedModelMenus` and a value in `modelListAsks`, both in-memory maps whose
  keyspace is ids the client already holds from the daemon's own `conversations` snapshot — so a hostile
  daemon cannot grow either collection beyond the conversation set it already published, which the
  application-envelope cap already bounds, and both die with the connection. No new client-side length
  bound is added, deliberately: the id is the daemon's own registry key and the daemon re-validates it,
  so a cap invented here could only reject ids the daemon considers valid.
- **[Trust boundaries] No new text reaches Compose, and that is the whole of this ticket's render
  posture.** The reply is #791's frame decoded by #791's boundary; this ticket adds no render site, no
  new string-carrying inbound type and no consumer. The claude-authored strings stay exactly where #791
  left them, and the render obligation — inert text, never markup, a URL, an attribute or a log line —
  remains **#649's**, unchanged and undischarged here.
- **[Tokens, secrets, credentials] No findings.** Nothing is read, written, derived, stored or compared.
  The request envelope id is a monotonic `AtomicLong` ordinal, the existing `requestId` posture, and is
  deliberately **not** `SecureRandom`: it is not a capability and grants nothing. Guessing one confers no
  ability, because forging a correlated reply requires being inside the Noise session — and anything
  inside it is the daemon, which needs no forgery. Naming a conversation is not authorization; the
  daemon validates the id against its registry, exactly as the wire SSOT states.
- **[File / storage operations] No findings.** No path is constructed, canonicalised, read or written;
  no `File`, no DataStore, no preference, no cache entry. Nothing on this path is persisted at all, so
  there is no encryption-at-rest choice, no atomic-write concern, no TOCTOU window and no
  `allowBackup` surface. The conversation id never becomes a path component, mirroring the daemon's own
  rule for the same field.
- **[Inter-process / Android attack surface] No findings** — no Activity, Service, Receiver, intent
  filter, deep link, pending intent, content provider or WebView is touched or added. Stated rather than
  skipped because it is the standing MUST FIX for this family: a WebView rendering these rows would be
  one, and this ticket adds none.
- **[Cryptographic primitives] No findings.** No RNG, hash, KDF, constant-time comparison or key
  material is involved, and no part of the Noise handshake, key schedule, nonce sequence or AEAD framing
  is touched. The request ordinal is **not** a nonce and never enters the framing; the frame is sealed
  and unsealed by the existing `NoiseIkSession`.
- **[Network & I/O] No findings, and this is the category a new outbound verb most deserves.** One
  additional frame per (connection, conversation) at most, carrying one short key — no new socket, no
  client construction, no timeout, no TLS or pinning decision, no lifted size cap, and no change to the
  supervisor's capped-exponential backoff. **The spin analysis, in full:** there is no timer, no backoff
  and no scheduled re-send, so the only way to make this verb fire twice for one conversation is a new
  subscription. The `model_list.unavailable` release permits one, but it creates none — nothing in this
  design re-subscribes. A relay that forces rapid reconnects does produce one ask per reconnect per
  observed conversation, but that is true with or without the release (a reconnect builds a fresh
  repository with an empty ledger), it is bounded by the supervisor's existing backoff, and it is
  strictly smaller than the per-conversation reconcile burst the same reconnect already triggers. So the
  release adds no traffic an adversary did not already command.
- **[Error messages, logs, telemetry] No findings, and the discipline is binding rather than
  stylistic.** Not one `Log.*` call site is added on any branch. Two values make this mandatory: a
  refusal's `message` is daemon-authored prose, and the conversation id is a cross-conversation
  correlation key — the ticket's own security note forbids the pair in one line, and this design never
  holds them in the same expression, because only `code` is read. The `ErrorPayload` decode failure is
  **caught and discarded**, never logged or rethrown, since kotlinx-serialization messages can quote the
  offending input. `askForModelMenu` is non-throwing and authors no message at all — deliberately not
  the `check(pump.send(...)) { … }` idiom `interrupt` uses, so no failure text exists to leak. No
  telemetry, metric or crash-reporter surface is added, and nothing here is debug-gated because there is
  nothing to gate.
- **[Concurrency] No findings, with one race named rather than glossed.** No coroutine is launched, so
  no scope owns anything and there is nothing to cancel or leak; the ask runs inline on the subscribing
  collector's coroutine and both replies on the single existing inbound collector. There is no
  check-then-act across a suspension point because there is no suspension point. The one real race is
  the send-failure rollback: collector A takes the one-shot via `Set.add`, its `send` fails, and in that
  window collector B sees the entry and declines to ask before A rolls back — so neither asks. The
  outcome is benign and is stated as accepted: the window exists only while the pump is not `Open`, when
  B's own send would have failed identically, and the resting state it leaves — unavailable until the
  next connect's burst — is precisely what AC #3 prescribes. The alternative (holding a lock across the
  send) would put a transport call inside a critical section to buy nothing. `askedModelMenus.add` is an
  atomic test-and-set rather than a read-then-write, so the simultaneous-subscription case cannot
  double-send. The reading stays cold per-collector; the shared ledger holds ids only and no content, so
  nothing leaks across screens. A background close or process death drops both collections with the
  connection, which is the correct and only reset.
- **[Threat model alignment] Addressed — hostile daemon frame and malicious relay, the two that land
  here.** *Hostile daemon:* it can refuse an ask with either code, and both outcomes are bounded.
  `conversation.not_found` suppresses that id's ask for the connection, which denies a menu it could
  equally deny by staying silent; `model_list.unavailable` releases a one-shot, which permits at most
  one further ask on a later subscription. Neither writes a projection, neither crosses conversations
  (routing stays the payload's own id), neither crashes the collector (every decode is guarded and every
  branch non-throwing), and an `error` correlated to an id we never sent resolves in neither map and is
  a no-op. *Malicious relay:* content-blind and on-path — it can drop, delay, reorder or flood, and
  every one of those degrades to "no menu yet", a legal resting state by construction. It cannot forge a
  refusal inside the Noise session. **This is the threat the no-retry rule exists for**, in the desktop
  header's words: a client-side retry against a relay that withholds the reply is a self-inflicted spin
  driven by an on-path adversary, and this design contains no retry for it to drive. *Token theft from
  disk:* not applicable — nothing is persisted. *UI-side leakage* (screenshots, accessibility, overlays,
  keyboard logging): not applicable — nothing is drawn. *Prompt injection through claude-authored
  display text* remains the standing upstream threat (`protocol-mobile.md` § Security model, threat 1,
  `mitigation: partial`), unchanged by this ticket and owned at the render boundary by **#649**.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-22
