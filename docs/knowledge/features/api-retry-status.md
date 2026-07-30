# API-retry status — the thread-observable "claude is stuck retrying an API error" signal

A per-conversation status the thread layer observes to learn that the remote claude has hit an API
error and is **retrying**, so the phone can say so — "Retrying — attempt N/M" — instead of showing an
indefinite thinking spinner. Landed in [#593](../codebase/593.md) (split from #582, the data slice).
The **visible** reaction is sibling **[#594](../codebase/594.md)** — shipped; see
[API-retry indicator](api-retry-indicator.md) for the `ApiRetryIndicator` component and the
`ThreadScreen`/`ThreadViewModel` wiring that consumes this projection.

This doc covers the **data layer only**: decode the inbound `api_retry` envelope into observable state.
It renders nothing itself — for the rendered "Retrying — attempt N/M" status, see
[API-retry indicator](api-retry-indicator.md).

## The signal

```kotlin
// ConversationRepository (the interface every UI tier binds to)
fun observeApiRetry(conversationId: String): Flow<ApiRetryStatus> = flowOf(ApiRetryStatus.NotRetrying)

// the portable element type, co-located with the interface (like ThreadItem / QueuedMessage)
sealed interface ApiRetryStatus {
    data object NotRetrying : ApiRetryStatus
    data object AttemptUnknown : ApiRetryStatus
    data class Attempt(val current: Int, val total: Int) : ApiRetryStatus
}
```

- A flat, 3-member sealed type — one member per observable state, not `Retrying(attempt: Attempt?)`.
  The flat shape avoids a nested nullable and gives #594 an exhaustive 3-arm `when` with no null
  branch.
- `NotRetrying` until the first `active: true` frame; `Attempt(current, total)` while retrying with a
  parsed counter; `AttemptUnknown` while retrying but the daemon could not parse claude's on-screen
  `attempt N/M` counter (wire `{current: 0, total: 0}`) — a legitimate state, not an error.
- **Rising edge re-fires on a counter climb.** `3/10` → `4/10` arrives as another `active: true`
  frame; each re-fire is a counter *update*, not a redundant onset. The daemon re-fires only on an
  actual count change (no per-tick flood), and the mobile side does no additional dedup beyond
  value-identity — see § Onset, climb, and clearing.
- **Falling edge clears unconditionally.** `active: false` maps to `NotRetrying` regardless of what
  `current`/`total` carry — the daemon copies the last-known counter onto that frame so its own final
  render stays coherent, but a client **ignores** it. `toStatus()` is the single place this rule is
  enforced.
- **On the interface, with an `ApiRetryStatus.NotRetrying` default.** The thread ViewModel reaches
  this state through the [`StableConversationRepository`](stable-conversation-repository.md) facade it
  already holds, and the facade only delegates the `ConversationRepository` interface — a
  concrete-only capability would be stranded ([[post-352-connection-scoped-repo-behind-facade]]). The
  default is the cascade-escape valve: the [Fake](conversation-repository.md) and every inline test
  double inherit "never retrying" and need no override — same lever as `observeStall`/`observeQueue`.
  Only the facade and the [live remote repo](remote-conversation-repository.md) override it.

## Why this follows `queue_state`'s shape, not `stall`'s

Two wire differences from the shipped `stall` arm (#395) drove the type choice: `api_retry` has an
explicit **falling edge**, and it **carries a counter**. A `Set<String>` membership model (stall's
shape) cannot represent a counter — it would pin the first `attempt N/M` forever and collapse the
documented climb, silently violating the "reaches the observer as a new emission" requirement. So the
repository projection is a payload-carrying `Map<String, ApiRetryStatus>`, structurally the same shape
as [`queue_state`](queued-backlog.md) (#460)'s `queuedByConversation`, not `stall`'s bare `Set`.

`compacting`, a banner-only twin from the same daemon PR (pyrycode#1074), landed mobile-side as
[#596](../codebase/596.md) — see [Compacting state](compacting-state.md). It clones this arm's
capability gate and malformed-drop idiom but not its `Map` shape: with no counter to carry, it uses
`stall`'s bare `Set` instead. Nothing here is generalised into a shared status-event abstraction.
Two samples are not a pattern.

## Onset, climb, and clearing

| Edge | Wire | Result |
|---|---|---|
| **Onset** | `active: true`, counter parsed | `Attempt(current, total)` |
| **Onset, counter unparsed** | `active: true`, `{0, 0}` | `AttemptUnknown` |
| **Climb** | another `active: true`, counter increased | new `Attempt(current, total)` — reaches the observer as a fresh emission, not collapsed into "still retrying" |
| **Falling edge** | `active: false` | `NotRetrying` — `current`/`total` on this frame are ignored, even though the daemon carries the last-known counter |

Unlike `stall`, both edges are explicit on the wire — there is no forward-progress inference. The
demux arm performs one unconditional replace per frame and never branches on `active`; `toStatus()`
is the sole owner of edge semantics (§ How it surfaces below).

`distinctUntilChanged()` on the observing flow is what makes "no dedup on the wire" and "no
per-collector flood" both true at once: it suppresses only value-*identical* re-emissions. Because
`ApiRetryStatus.Attempt` is a `data class`, `Attempt(3,10) != Attempt(4,10)` — a climbed counter
structurally differs and passes through, while a value-identical re-fire (which the daemon does not
send) or a frame for another conversation would not re-emit. A membership `Set<String>` could not make
this distinction: `true → true` collapses under any equality model.

## How it surfaces in the repository

The behaviour lives in [`RemoteConversationRepository`](remote-conversation-repository.md#observeapiretryconversationid--the-thread-observable-api-retry-state-593)
on the **single existing** inbound collector — see that doc for the field, the demux arm, and the
projection. In short: a connection-scoped `MutableStateFlow<Map<String, ApiRetryStatus>>`, written
**only** from `onInbound` (single writer → edges never race), with `observeApiRetry` a cold
`.map { it[id] ?: NotRetrying }.distinctUntilChanged()` projection. Connection-scoped, in-memory: a
fresh repo per connection (#351) starts empty, so a retry state **never survives a reconnect** — it
re-derives from the live stream. A retry is a transient "right now" condition, not durable state.

## Capability gate (fail-closed)

The demux arm sits inside `CAPABILITY_INTERACTIVE in negotiatedCapabilities()` — the same gate
[`stall`](stall-state.md#capability-gate-fail-closed) and [`queue_state`](queued-backlog.md) use. The
server already fans `api_retry` out only to phones that advertised `interactive`; the mobile gate is
defence in depth — a non-interactive phone that receives a spurious `api_retry` from a buggy or
hostile daemon never decodes it and never surfaces it.

## Edge cases & limitations

- **Malformed payload dropped, collector survives (AC #3).** A missing field or a wrong-typed one (a
  number where a `String` is declared, a non-integral or out-of-`Int32` counter) fails the strict
  decode of `ApiRetryPayloadDto` → that one envelope is dropped, the lone inbound collector lives, a
  later valid frame still lands. Same drop idiom as every sibling `onInbound` arm; **nothing logs the
  payload** — `current`/`total` are screen-derived data crossing the tui-driver substrate seal, so the
  uniform no-log rule keeps them out of Logcat in any build variant.
- **The mapper is total, not nullable.** Unlike `TurnStatePayloadDto.toEvent()`, an undocumented
  active-counter shape (`{3, 0}`, a negative) does not drop the envelope — it maps to
  `AttemptUnknown`. Dropping would discard a real retry onset and leave the thread on the indefinite
  spinner this feature exists to fix; there is no unrecognized *value* here to reject, only an
  unrecognized shape the decoder already structurally rejects.
- **Not turn-scoped, not forward progress.** No `turn_id` on the wire, and this arm never opens,
  closes, or alters a turn. It also does **not** clear an active [stall](stall-state.md) — claude
  retrying is stuck, not making progress; clearing a stall here would be a real bug. It folds no
  thread row.
- **No clamping at the decode boundary.** `current`/`total` carry verbatim, even an implausible or
  incoherent pair (`9/3`, `2147483647`). Bounding for display is #594's concern — clamping here would
  silently rewrite server data and diverge from every sibling mapper's carry-verbatim posture.
- **Never observed in production today.** The daemon emits this only from the PTY-runner detector
  family; production runs the stream-json interactive runner, which has no emitter. The client is
  indifferent to never receiving it — this is an enhancement layered on the existing thinking state,
  never a precondition for it.

## Security

`security-sensitive`; architect self-review **PASS**. One untrusted→trusted boundary —
`decodeApiRetry(envelope): Pair<String, ApiRetryStatus>?` through the single configured `MobileJson`,
behind the already-authenticated Noise channel. `ApiRetryStatus` is a **closed sealed type carrying
two `Int`s and no `String`** — the routing `conversationId` stays a map key and never reaches the
value — so no daemon-supplied text can structurally reach the UI through this arm, the same property
[stall](stall-state.md#security) and [queued backlog](queued-backlog.md#security) share. No token,
key, RNG, hash, file I/O, IPC surface, or persistence is touched. Memory posture (unbounded key growth
from a hostile-but-authenticated daemon sending frames for fabricated conversation ids) is
pre-existing and identical across every sibling projection (`stalledConversations`,
`queuedByConversation`, `threadByConversation`, `lastMessages`), not fixed per-arm; a projection-family
fix, if ever warranted, is a separate ticket. No payload logging. UI-leakage threats (screenshot/
overlay of a retry banner) belong to #594.

## Related

- [#593 implementation notes](../codebase/593.md) — files, line refs, patterns, lessons.
- [Remote conversation repository](remote-conversation-repository.md) — hosts the
  `apiRetryByConversation` projection, the demux arm, and the decode.
- [Stall state](stall-state.md) (#395) — the capability-gate + malformed-drop precedent; **not**
  cleared by an `api_retry` (a retry is not forward progress).
- [Queued backlog](queued-backlog.md) (#460) — the structural template: the same
  payload-carrying-`Map` projection shape, full-replace-on-every-frame idiom, and defaulted-interface
  cascade-avoidance lever.
- [ConversationRepository](conversation-repository.md) — the interface the defaulted `observeApiRetry`
  joins; [`StableConversationRepository`](stable-conversation-repository.md) — the facade that makes it
  reach the thread ViewModel.
- Consumer (shipped): **[#594](../codebase/594.md)** — [API-retry indicator](api-retry-indicator.md),
  the visible "Retrying — attempt N/M" render.
- Sibling (shipped): **[#596](../codebase/596.md)** — [Compacting state](compacting-state.md), the
  `compacting` banner-only twin from the same daemon PR, cloning this arm's gate and drop idiom over
  `stall`'s `Set` shape rather than this one's `Map`.
- Server SSOT: pyrycode#1074 (design, merged PR pyrycode#1160, 2026-07-21),
  `internal/protocol/interactive.go:99` (`ApiRetryPayload`), `docs/protocol-mobile.md § api_retry`.
