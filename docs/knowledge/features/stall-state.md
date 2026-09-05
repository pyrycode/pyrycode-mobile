# Stall state — the thread-observable "remote claude has stalled" signal

A per-conversation `Boolean` the thread layer observes to learn that the remote claude has **stopped
making forward progress** — PTY quiet while not idle, no JSONL progress, typically a screen-parser
break — so the phone can react instead of silently appearing to hang. Landed in
[#395](../codebase/395.md) (split from #373, the data slice). The **visible** reaction (promoting the
always-available screen-snapshot action when stalled) shipped in sibling **[#396](../codebase/396.md)** —
the [stall promotion banner](stall-promotion-banner.md), which consumes this through
`ThreadViewModel.isStalled`.

This is the **data layer only**: decode the inbound `stall` envelope into observable state and infer
recovery. It renders nothing.

## The signal

```kotlin
// ConversationRepository (the interface every UI tier binds to)
fun observeStall(conversationId: String): Flow<Boolean> = flowOf(false)
```

- `true` on stall **onset**, `false` once recovered. Cold flow; re-emits only on change
  (`distinctUntilChanged`), so a stall to *another* conversation never wakes this collector.
- **On the interface, with a `flowOf(false)` default.** AC #4 requires the thread ViewModel to reach
  the stall state through the [`StableConversationRepository`](stable-conversation-repository.md) facade
  it already holds — and the facade only delegates the `ConversationRepository` interface, so a
  concrete-only capability would be stranded ([[post-352-connection-scoped-repo-behind-facade]]). The
  default body is the cascade-escape valve: the [Fake](conversation-repository.md) and every inline test
  double inherit "never stalled" and need no override (same lever as `delete` / `requestScreenSnapshot`
  / `recentWorkspaces`). Only the facade and the [live remote repo](remote-conversation-repository.md)
  override it.

> **The deliberate counterpoint to `liveSessionEvents` (#385).** Both ride the same single
> `pump.inbound` collector, but they surface oppositely. [Live-session events](live-session-events.md)
> are `replay=0` *events* on the **concrete** repo, off the interface (a late subscriber gets no
> history). Stall is current-value **state** on the **interface**, facade-reachable. The deciding
> question is *does the consumer need current-value state through the connection-churn facade?* — yes
> for stall, no for the event stream.

## Onset and clearing

The decisive wire fact (server SSOT pyrycode#638; tui-driver #141 v1.3.0): the `stall` payload is
`{conversation_id}` **only** — the peer of `turn_state` minus `state` — and tui-driver's `stall_detected`
**has no clearing edge**. There is no `stall_cleared` wire type and there will not be one. So:

| Edge | Source | Mechanism |
|---|---|---|
| **Onset** | an inbound `stall` envelope | decode `{conversation_id}` → add the id to the stalled set |
| **Clearing** | the **next forward-progress event** | any *successfully decoded* [`LiveSessionEvent`](live-session-events.md) for the conversation → remove the id |

Recovery is therefore **inferred mobile-side** — exactly ADR-025's model ("the phone infers 'responding'
from delta arrival"). All five live-session event types clear, including `turn_state: idle` and
`turn_end`: both mean the quiet-while-not-idle stall condition no longer holds. The clearing hook reads
`conversationId` off the **already-decoded** event — no second decode — and removing an absent id is a
no-op, so clearing rides every live event harmlessly.

### What does *not* clear (deliberately conservative)

- A **malformed** live-session envelope decodes to `null` → no trustworthy `conversationId` to route a
  clear → the stall persists. We do not attribute forward progress we couldn't parse.
- An **unrecognized** `turn_state` value (e.g. a future `"compacting"`) maps to `null` via
  `TurnStatePayloadDto.toEvent()` → does not clear. The stall persists until a *recognized*
  forward-progress event arrives, rather than guessing an unknown state's semantics.
- The `stall` envelope carries no `state`/recovery flag, so there is no "self-clearing" stall variant —
  re-receipt of `stall` for an already-stalled conversation is an **idempotent** set add.

## How it surfaces in the repository

All of the behaviour lives in [`RemoteConversationRepository`](remote-conversation-repository-thread-observables.md#observestallconversationid--the-thread-observable-stall-state-395)
on the **single existing** inbound collector — see that doc for the field, the demux arms, and the
projection. In short: a connection-scoped `MutableStateFlow<Set<String>>` (membership = stalled),
written **only** from `onInbound` (single writer → onset and clearing never race), with `observeStall`
a cold `map { id in it }.distinctUntilChanged()` projection. Connection-scoped, in-memory: a fresh repo
per connection (#351) starts empty, so a stall **never survives a reconnect** — it re-derives from the
live stream. A stall is a transient "right now" condition, not durable state.

## Capability gate (fail-closed)

Both the onset arm and the clearing hook sit inside `CAPABILITY_INTERACTIVE in negotiatedCapabilities()`
— the same gate [#385](../codebase/385.md) uses. The server already fans `stall` out **only** to phones
that advertised `interactive` (#401), but the mobile gate is **defence in depth**: a non-interactive
phone that receives a spurious `stall` from a buggy/hostile daemon never decodes it and never surfaces
it. Onset and clearing are symmetric — both gated.

## Edge cases & limitations

- **Malformed `stall` dropped, collector survives (AC #3).** A missing/wrong-typed `conversation_id`
  fails the strict decode of `StallPayloadDto` (the required-`String` field is the fail-closed posture)
  → that one envelope is dropped, the lone inbound collector lives, a later valid `stall` still flips
  state. Same drop idiom as every other `onInbound` arm; **nothing logs the payload**.
- **Not durable.** Lost on connection drop / process death; re-derived from the live stream on
  reconnect. The facade's `whenAbsent = false` reports "not stalled" between connections.
- **No "finished" vs "recovered mid-turn" distinction at this layer.** Any forward-progress event clears.
  [#396](../codebase/396.md) consumed this as a single boolean and deliberately did **not** add that
  distinction (resolving the open question below); were it ever wanted it is a UI-layer derivation over
  `observeStall` + `liveSessionEvents`, not a data-layer change.

## Security

`security-sensitive`; architect self-review **PASS**. One untrusted→trusted boundary —
`decodeStall(envelope): String?` through the single configured `MobileJson`, behind the already-
authenticated Noise channel. It is the **narrowest** of the interactive boundaries: the payload carries
no free-form text (no `text`/`summary`/`stop_reason`), so there is no verbatim-sensitive-content surface
to mishandle, and the data layer surfaces only a `Boolean`. Memory posture is a `Set<String>` of
conversation ids (membership, not accumulation) — the same per-conversation-id growth posture
`lastMessages`/`threadByConversation` already accept under the paired-daemon threat model, with the
lightest footprint. No payload logging; UI-leakage threats (screenshot/overlay of a stall banner) belong
to #396 — and the [stall promotion banner](stall-promotion-banner.md) it shipped carries **no**
sensitive content (only fixed copy + a CTA into the existing snapshot action), so there is nothing to
leak at the banner itself; the snapshot text's confidentiality (FLAG_SECURE, no clipboard) is handled
where it is actually rendered, in [`LiteralScreenSurface`](literal-screen-surface.md) (#381).

## Related

- [#395 implementation notes](../codebase/395.md) — files, line refs, lessons, verification.
- [Remote conversation repository](remote-conversation-repository.md) — hosts the `stalledConversations`
  projection, the onset arm, and the clearing hook.
- [Live-session events](live-session-events.md) (#385) — every decoded event is the forward-progress
  signal that clears a stall; the gate + single-collector substrate this reuses.
- [Queued backlog](queued-backlog.md) (#460) — the structural twin: the same decode→state→observe shape
  and `interactive` gate, but a **full-snapshot** ordered list with no onset/clearing edge. A stalled
  conversation will typically also have a non-empty queue; the two states are exposed **independently**
  (any combined "stalled with N waiting" view is a UI derivation, not a data-layer concern).
- [API-retry status](api-retry-status.md) (#593) — a different-shaped cousin: gated the same way, but
  with both an explicit falling edge and a counter, so it follows `queue_state`'s payload-carrying `Map`
  shape rather than this arm's bare `Set`. Does **not** clear a stall — claude retrying is stuck, not
  making forward progress — and a retry does not fold a thread row, symmetric with this arm's own
  isolation from the queue projection.
- [Compacting state](compacting-state.md) (#596) — clones **this** arm's bare `Set` shape (no counter
  to carry), but diverges on the one point `stall`'s model doesn't transfer: it has an explicit wire
  falling edge, so clearing is driven by the frame rather than inferred from forward progress. Also
  does **not** clear a stall — compaction is claude busy elsewhere, not forward progress.
- [ConversationRepository](conversation-repository.md) — the interface the defaulted `observeStall`
  joins; [`StableConversationRepository`](stable-conversation-repository.md) — the facade that makes it
  reach the thread ViewModel.
- Consumer (shipped): **[#396](../codebase/396.md)** — the [stall promotion banner](stall-promotion-banner.md),
  which renders this flag as a prominent screen-snapshot CTA at the top of the thread.
- Server SSOT: pyrycode#624 (stall transport), #638 (`stall` wire vocabulary, `{conversation_id}`,
  onset-only), #639 (bridge + interactive-only fan-out), tui-driver #141 v1.3.0 (`stall_detected`),
  ADR-025 § Safe degradation.
