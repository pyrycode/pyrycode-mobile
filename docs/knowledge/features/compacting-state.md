# Compacting state — the thread-observable "claude is auto-compacting" signal

A per-conversation `Boolean` the thread layer observes to learn that the remote claude has gone
silent because it is **auto-compacting its context** — the daemon's only signal that something is
happening during the tens of seconds the content channel stays quiet — so the phone can say so
instead of looking like a frozen spinner. Landed in [#596](../codebase/596.md) (split from #583,
the data slice). The **visible** reaction is sibling [#597](../codebase/597.md) — the
[Compacting indicator](compacting-indicator.md), shipped.

This doc covers the **data layer only**: decode the inbound `compacting` envelope into observable
state. It renders nothing itself.

## The signal

```kotlin
// ConversationRepository (the interface every UI tier binds to)
fun observeCompacting(conversationId: String): Flow<Boolean> = flowOf(false)
```

- `true` on the rising edge (compaction started), `false` on the explicit falling edge (compaction
  finished). Cold flow; re-emits only on change (`distinctUntilChanged`), so a compaction on
  *another* conversation never wakes this collector.
- **Banner-only.** The upstream detector streams no compaction progress — no counter, percent, or
  ETA field exists on the wire, unlike [API-retry status](api-retry-status.md)'s `attempt N/M`. There
  is nothing to carry beyond the id and the edge bool, so the observable is a bare `Boolean` with no
  new domain type.
- **On the interface, with a `flowOf(false)` default.** The thread ViewModel reaches this state
  through the [`StableConversationRepository`](stable-conversation-repository.md) facade it already
  holds, and the facade only delegates the `ConversationRepository` interface — a concrete-only
  capability would be stranded ([[post-352-connection-scoped-repo-behind-facade]]). The default is
  the cascade-escape valve: the [Fake](conversation-repository.md) and every inline test double
  inherit "never compacting" and need no override — same lever as
  `observeStall`/`observeQueue`/`observeApiRetry`. Only the facade and the
  [live remote repo](remote-conversation-repository.md) override it.

## Why this clones `stall`'s shape, not `api_retry`'s

Compaction is a bare on/off edge with no payload, so the repository projection is a membership
`MutableStateFlow<Set<String>>` — [`stall`](stall-state.md)'s shape (#395) — not
[`api_retry`](api-retry-status.md)'s payload-carrying `Map<String, ApiRetryStatus>` (#593). A `Set`
would be wrong for `api_retry` because it cannot represent a counter; it is exactly right here
because there is nothing to represent beyond membership.

The one place `stall`'s shape does **not** transfer: `stall` is onset-only and its recovery is
*inferred* from the next forward-progress event, because tui-driver's `stall_detected` has no
clearing edge on the wire. `compacting` has a **real, explicit falling edge** — `active: false` — so
removal here is driven by the wire, not inferred:

| Edge | Source | Mechanism |
|---|---|---|
| **Onset** | an inbound `compacting` envelope, `active: true` | decode `{conversation_id, active}` → add the id to the compacting set |
| **Clearing** | an inbound `compacting` envelope, `active: false` | decode → remove the id from the compacting set |

Both edges are handled by the **same** `TYPE_COMPACTING` demux arm — there is no mapper to own the
edge semantics (unlike `api_retry`'s `toStatus()`, which exists precisely to collapse four wire
fields into a three-member domain type). The two wire fields already are the domain shape (a
`String` routing key and a `Boolean`), so the membership transition *is* the edge, expressed exactly
once: `it + id` on `active: true`, `it - id` on `active: false`. Removing an absent id, or adding an
already-present one, is a no-op — a falling edge with no prior onset is harmless, and a repeated
onset is idempotent.

`compacting` is the **third** conversation-level status arm after `stall` and `api_retry` — exactly
where a shared status-event abstraction starts looking tempting. It is deliberately **not**
introduced here, per the explicit note #593's spec left for this ticket: three similar arms is the
observation that would justify a status-event framework, not a mandate to build one. This clones the
`stall` arm; it does not generalize either.

## Name collision — not the `turn_state` phase value

`"compacting"` already appears in this repo as a `turn_state` *phase value* in
`RemoteConversationRepositoryTest.kt`, pushed as a deliberately **unrecognized** phase and asserted
to be dropped. That is unrelated and stays correct: the server's `turn_state.state` is a closed set
(`thinking` / `responding` / `idle`) and `compacting` was never a member of it. Compaction arrives as
its own top-level envelope `type`, dispatched by a different `when` arm — adding `TYPE_COMPACTING`
cannot affect those tests.

## How it surfaces in the repository

The behaviour lives in [`RemoteConversationRepository`](remote-conversation-repository-thread-observables.md#observecompactingconversationid--the-thread-observable-compaction-state-596)
on the **single existing** inbound collector — see that doc for the field, the demux arm, and the
projection. In short: a connection-scoped `MutableStateFlow<Set<String>>` (membership = compacting),
written **only** from `onInbound` (single writer → the rising and falling edges never race), with
`observeCompacting` a cold `.map { id in it }.distinctUntilChanged()` projection. Connection-scoped,
in-memory: a fresh repo per connection (#351) starts empty, so a compaction state **never survives a
reconnect** — it re-derives from the live stream. A transient "right now" condition, not durable
state.

The write is a genuine **read-modify-write** on the set (`it + id` / `it - id`), unlike
`api_retry`'s pure replace, so `MutableStateFlow.update {}` is load-bearing here rather than
stylistic — a `.value = … + id` formulation would open a real check-then-mutate window.

## Capability gate (fail-closed)

The demux arm sits inside `CAPABILITY_INTERACTIVE in negotiatedCapabilities()` — the same gate
[`stall`](stall-state.md#capability-gate-fail-closed) and
[`api_retry`](api-retry-status.md#capability-gate-fail-closed) use. The server already fans
`compacting` out only to phones that advertised `interactive`; the mobile gate is defence in depth —
a non-interactive phone that receives a spurious `compacting` from a buggy or hostile daemon never
decodes it and never surfaces it.

## Edge cases & limitations

- **Malformed payload dropped, collector survives (AC #5).** A missing field or a wrong-typed one (a
  number where the `conversation_id` `String` is declared, an object or array where the `active`
  `Boolean` is declared) fails the strict decode of `CompactingPayloadDto` → that one envelope is
  dropped, the lone inbound collector lives, a later valid frame still flips state. Same drop idiom
  as every sibling `onInbound` arm; **nothing logs the payload**. A **quoted** primitive
  (`{"active":"true"}`) is *not* a usable malformed probe — kotlinx's tree decoder accepts it even
  with `isLenient = false` (measured against `ApiRetryPayloadDto`, documented in-repo, and holds here
  too); use a genuinely wrong-shaped value instead.
- **Not turn-scoped, not forward progress.** No `turn_id` on the wire, and this arm never opens,
  closes, or alters a turn. It also does **not** clear an active [stall](stall-state.md) —
  compaction is claude busy elsewhere, not making forward progress; clearing a stall here would let a
  hostile daemon suppress the phone's stall indicator by emitting `compacting` frames. It folds no
  thread row.
- **No server-supplied free text or number.** `conversation_id` and a bool are the whole surface —
  strictly less daemon-supplied data than `api_retry` (two ints) or `queue_state` (user text). No
  daemon-supplied data of any kind can structurally reach the UI through this arm.
- **A hostile or crashed daemon can leave the state stuck active** by sending a rising edge and never
  a falling one. Deliberately undefended: the shipped `stall` arm has the identical property and
  worse (no wire clearing edge at all) and has been in production since #395 without incident; a
  client-side timeout would invent a policy the wire contract does not define; and a reconnect clears
  it. #597 should be aware the flag can be long-lived and must not block interaction on it.

## Security

`security-sensitive`; architect self-review **PASS**. One untrusted→trusted boundary —
`decodeCompacting(envelope): Pair<String, Boolean>?` through the single configured `MobileJson`,
behind the already-authenticated Noise channel. This is the **narrowest** of the four sibling arms
(`stall`, `queue_state`, `api_retry`, `compacting`): the payload's entire surface is a routing id
(which stays a `Set` element, never a value) plus a bool — no daemon-supplied text or number of any
kind can structurally reach the UI. No token, key, RNG, hash, file I/O, IPC surface, or persistence
is touched. Memory posture (unbounded key growth from a hostile-but-authenticated daemon sending
frames for fabricated conversation ids) is pre-existing and identical across every sibling projection
(`stalledConversations`, `queuedByConversation`, `apiRetryByConversation`, `threadByConversation`,
`lastMessages`), and this arm's per-key cost is the smallest of the family — not fixed per-arm; a
projection-family fix, if ever warranted, is a separate ticket. No payload logging. UI-leakage
threats (screenshot/overlay of a compaction banner) belong to #597.

## Related

- [#596 implementation notes](../codebase/596.md) — files, line refs, patterns, lessons.
- [Remote conversation repository](remote-conversation-repository.md) — hosts the
  demux arm; `CompactingProjection` holds the `compactingConversations` state, the decode and the read.
- [Stall state](stall-state.md) (#395) — the shape this arm clones (bare membership `Set`,
  capability gate, malformed-drop idiom); **not** cleared by a `compacting` frame (compaction is not
  forward progress).
- [API-retry status](api-retry-status.md) (#593) — the provenance relative (same daemon PR, same
  `active` edge bool, same capability gate) but a payload-carrying `Map`, because that arm has a
  counter to represent and this one doesn't.
- [ConversationRepository](conversation-repository.md) — the interface the defaulted
  `observeCompacting` joins; [`StableConversationRepository`](stable-conversation-repository.md) — the
  facade that makes it reach the thread ViewModel.
- Consumer: [Compacting indicator](compacting-indicator.md) ([#597](../codebase/597.md)) — the
  visible "Compacting conversation" status, natively blocked by this ticket and now shipped.
- Server SSOT: pyrycode#1074 (design, merged PR pyrycode#1160, 2026-07-21),
  `internal/protocol/interactive.go` (`CompactingPayload`, `TypeCompacting`),
  `docs/protocol-mobile.md § compacting`.
