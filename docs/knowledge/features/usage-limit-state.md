# Usage-limit state — what claude last said about its usage-limit window

A per-conversation reading the thread layer can observe to learn **what claude last reported about its
usage-limit window**, so a turn that stalls on a limit can say why. Landed in
[#802](../../specs/architecture/802-decode-rate-limited-usage-limit-state.md) (split from #653, the data
slice). Rendering the reading in the status area is sibling slice
[#804](https://github.com/pyrycode/pyrycode-mobile/issues/804), shipped — see
[Usage-limit indicator](usage-limit-indicator.md).

This doc covers the **data layer only**: decode the inbound `rate_limited` envelope into observable
state. It renders nothing itself. Wire SSOT: pyrycode `docs/protocol-mobile.md § rate_limited` +
`internal/protocol/interactive.go` (`RateLimitedPayload`) — cited here, not restated.

## The signal

```kotlin
// ConversationRepository (the interface every UI tier binds to)
fun observeUsageLimit(conversationId: String): Flow<UsageLimitReading?> = flowOf(null)

// the portable element type, co-located with the interface (like ApiRetryStatus / ThinkingProgress)
data class UsageLimitReading(
    val status: String,
    val limitType: String,
    val resetsAt: Long,
    val utilization: Double?,
    val truncatedFields: List<String>?,
)
```

- **A frame is not proof that a turn was blocked.** The one measured non-benign value is
  `allowed_warning`, seen on an account whose turns all ran normally. Neither this data layer nor the
  render sibling may claim the turn was blocked from a `rate_limited` frame alone.
- **`status` is an open string; exactly one comparison is legitimate.** The mapper compares it against
  the single benign literal (`"allowed"`) to tell a falling edge from a warning; every other value —
  `limit_type` included — is an opaque label to render, never a case to branch on. The value set beyond
  the benign one is almost entirely unmeasured, so narrowing it would drop the first real limit that
  fires. `utilization` is treated the same way: a report, never a threshold to compare against.
- **`null` at the flow covers three upstream facts a consumer does not have to tell apart**: nothing has
  arrived yet, a benign frame cleared the entry, or the reported window has passed. A *present but
  degenerate* reading (`status = ""`) is a real reading the daemon emitted and is **not** collapsed to
  `null`.
- **A `data class`, not a sealed family — unlike `ApiRetryStatus`.** That one is sealed because its wire
  shape collapses into three meanings a `when` must cover exhaustively; this payload has one meaning
  with five fields. `data` is load-bearing for the `ModelMenu` reason: structural equality is what makes
  a consumer's `distinctUntilChanged` behave.
- **`conversation_id` is deliberately not a field on `UsageLimitReading`.** It stays the projection's map
  key and never reaches the value a render consumer holds and draws from — the rule `ApiRetryStatus`
  already states, and here it is the stronger one, since a daemon-asserted id inside the value would be
  one copy-paste away from a sink.
- **On the interface, with a `flowOf(null)` default.** The thread ViewModel reaches this state through
  the [`StableConversationRepository`](stable-conversation-repository.md) facade it already holds; the
  default is the cascade-escape valve — the [Fake](conversation-repository.md) and every inline test
  double inherit "nothing to read" and need no override, same lever as
  `observeCompacting`/`observeApiRetry`/`observeThinkingProgress`.

## `null` and `0` are different facts, and neither field is validated

`utilization` is `null` on the common case — every observed benign report omits the key — and a real
`0.0` is a different fact from no reading at all; nothing clamps it to `0..1`. `resetsAt` is claude's
number in unix seconds and is **never validated in either direction**: `0` means claude reported no
reset, not the epoch, and negative or absurd (year-40000-scale) values are representable and none is
rejected. Both fields cross the decode **verbatim**, matching every sibling arm's carry-verbatim
posture; a client-side clamp or range check would diverge from the wire, which enforces none either.

**This is why `resetsAt` is a `Long`, not an `Int`, and that choice is load-bearing.** The Go field is
`int64` and the contract admits values an order of magnitude past `Int32`. An `Int` DTO field would fail
the *structural* decode on exactly the out-of-range value the acceptance criteria require be carried,
turning a carry-verbatim rule into a silent drop through a type choice — the same width trap
`QueuedMessage.id`'s KDoc records for `queued_msg_id`, and `ThinkingProgress`'s fields for the same Go
`int` width.

## The clearing edge names a different `limit_type` than the warning it clears

Every benign reading on record carries `limit_type = "five_hour"` against `"seven_day"` on every
warning it follows, so pairing the clear to `limit_type` never matches. The clear is paired to
**`conversation_id`** instead — `limit_type` is read by no control-flow path in the decode or the
projection, so pairing the clear to it is structurally not expressible, not merely avoided by
convention.

## The clear is session-scoped — the expiry is the reading's second way down

Unlike every prior arm in this family, `rate_limited` has **two** ways a reading stops being readable:

| Path | Mechanism |
|---|---|
| Benign frame | `toReading()` maps a benign `status` to `null`; the projection's `apply` removes that conversation's entry |
| Read-time expiry | `observe(conversationId)` compares `UsageLimitReading.resetsAt` against the current wall clock on every read |

The wire's clearing edge is **session-scoped**: a warning raised before a `/clear` or a session
eviction is **never** followed by a clearing frame, so the state needs a way down that does not depend
on the daemon sending one. Desktop's `usageLimitStore.ts` is the reference for the rule (read for the
rule, not the store shape): an entry stops being readable once its `resets_at` has passed, and an entry
whose `resets_at` is `0` has no time to expire at and stays readable forever.

**The expiry is one comparison performed when a reader asks, and there is deliberately no timer** — the
same posture [Thinking-progress state](thinking-progress-state.md) documents for the identical reason:
`resetsAt` is claude's unvalidated number, so a delay computed from it could be negative (firing
immediately, and spinning if a handler re-armed) or past a timer's clamp (also firing immediately rather
than never). The rule, in precedence order:

```
resetsAt == 0                    → readable   claude reported NO reset
now().epochSeconds < resetsAt    → readable   inside the window claude reported
now().epochSeconds >= resetsAt   → hidden     the window claude reported has passed
```

The zero test comes first because `0` means "no reset reported," not the epoch; folded into the
comparison it would read as "expired in 1970" and hide every unreported reading the moment it landed.
The boundary is exclusive, so a reading is hidden *at* `resetsAt` as well as after it. **Expiry is not
eviction** — an expired entry stays in the projection's map and merely stops being readable, which is
what keeps the read free of a timer; no consumer can observe the difference between "expired" and
"removed."

One consequence worth naming: comparing against a **wall** clock means a device clock moved backwards
can make an expired reading readable again. That is inherent — `resetsAt` is a wall-clock unix instant,
so a monotonic clock would be the wrong comparand — and desktop's `selectUsageLimitFor` carries the
identical property. Blast radius is one stale row the next frame corrects.

## How it surfaces in the repository

The behaviour lives in `UsageLimitProjection`
(`data/repository/UsageLimitProjection.kt`), one of the [status
projections](remote-conversation-repository.md#status-projections-one-file-per-status-event) beside
`RemoteConversationRepository`, on the **single existing** inbound collector. In short: a
connection-scoped `MutableStateFlow<Map<String, UsageLimitReading>>` (a payload-carrying `Map`, the
`ApiRetryProjection` shape rather than `CompactingProjection`'s membership `Set`, since this frame
carries five wire fields rather than an edge bool), written only from the repository's `TYPE_RATE_LIMITED`
arm: a non-benign reading **replaces** that conversation's entry and a benign one **removes** it, leaving
every other conversation untouched. `observeUsageLimit` is a cold
`.map { it[conversationId]?.takeIf(::isReadable) }.distinctUntilChanged()` projection — the expiry lives
here and only here, so a consumer must not re-derive the rule.

Connection-scoped, in-memory: a fresh repository per connection (#351) starts empty, which doubles as the
**pairing-scoped clear** — a usage-limit posture belongs to an account, and nothing re-asserts a reading
after a reconnect, so a reading can never be attributed to the next account. Nothing is persisted; the
projection holds no reference to `ConversationCache`.

`distinctUntilChanged` suppresses only value-*identical* re-emissions — a frame for **another**
conversation does not re-emit this flow, while a genuinely changed reading is a different
`UsageLimitReading` value and does reach the collector.

**Test idiom carried from [Thinking-progress state](thinking-progress-state.md#the-reading-has-no-falling-edge--its-clears-live-on-other-arms):
a `StateFlow` projection conflates frames pushed within one `runCurrent()`.** Two of #802's new cases
initially pushed a raising and a clearing frame inside one batch and asserted nothing about either edge
— caught only because one case asserted an emission *count*. Both now run each edge in its own
`runCurrent()` batch with the intermediate state asserted.

## Capability gate (fail-closed)

The demux arm sits inside `CAPABILITY_INTERACTIVE in negotiatedCapabilities()` — the same gate
[`stall`](stall-state.md#capability-gate-fail-closed),
[`api_retry`](api-retry-status.md#capability-gate-fail-closed) and
[thinking-progress](thinking-progress-state.md#capability-gate-fail-closed) use. The server already
fans `rate_limited` out only to phones that advertised `interactive`; the mobile gate is defence in
depth — a phone that never negotiated the capability decodes none, even from a daemon that ignored the
server-side fan-out gate.

## Edge cases & limitations

- **Malformed payload dropped, collector survives (AC #1).** A missing or wrong-typed required field —
  or a `resets_at` whose JSON shape cannot be read as a `Long` — fails the strict decode of
  `RateLimitedPayloadDto` → that one envelope is dropped, the lone inbound collector lives, a later valid
  frame still lands. Nothing logs the payload: `status`/`limit_type` are claude-authored text the daemon
  does not sanitize, and a logged `conversation_id` is a cross-conversation correlation leak.
- **`truncated_fields: []` stays `emptyList()`, never punned to `null`.** The daemon always emits both
  nullable keys, so `MobileJson`'s `explicitNulls = false` collapsing an omitted key with an explicit
  `null` is correct for both `utilization` and `truncatedFields` — but an out-of-contract empty array on
  `truncatedFields` decodes to an empty list rather than being folded into the same "nothing there" case
  as absence.
- **Never folds a thread row and never touches `stalledConversations` in either direction (AC #5).** A
  usage-limit report is neither a stall nor forward progress; clearing a stall here would hand a daemon
  a lever for suppressing the stall indicator by emitting `rate_limited` frames.
- **A hostile or crashed daemon can leave a reading stuck past its `resets_at`** only if `resets_at` is
  `0` — otherwise the read-time expiry is the reading's own way down with no dependency on another frame
  arriving. This is the property the expiry rule exists to guarantee, unlike
  [Thinking-progress state](thinking-progress-state.md#edge-cases--limitations)'s undefended stuck-reading
  case, which has no expiry at all.

## Security

`security-sensitive`; builder self-review **PASS** (full pass recorded in the
[architecture plan](../../specs/architecture/802-decode-rate-limited-usage-limit-state.md#security-review)).
This is the **first** arm in the conversation-status family carrying daemon-authored text and
unvalidated numbers into a domain type — `stall`/`queue_state`/`compacting`/`thinking_progress` carry
none. Three rules hold as a result: `conversation_id` stays the map key and is structurally absent from
`UsageLimitReading`; `status`/`limit_type` cross verbatim with no normalising, trimming, lower-casing,
allow-listing or client-side length cap (the daemon bounds both at construction and the transport's frame
contract bounds the envelope ahead of any parse); and no behaviour may branch on `status` beyond the
single benign comparison, nor on `utilization` at all — both are reports, never control inputs. `resets_at`
is never a scheduling input: the expiry is one comparison at read time, nothing schedules or iterates from
it. Nothing is persisted, nothing is logged (payload, `conversation_id`, and the caught throwable are all
discarded). Render-boundary obligations — inert-text rendering, defensive date formatting, copy that
claims neither blocking nor lifting — are discharged by the render sibling,
[Usage-limit indicator](usage-limit-indicator.md) (#804), not here; this slice has no UI sink.

## Related

- [Usage-limit indicator](usage-limit-indicator.md) (#804) — the render sibling: the `ThreadStatusArea`
  arm this reading drives, its three render-or-decline wording helpers, and the 30 s re-read ticker that
  takes a displayed reading down at its `resets_at` without this layer's expiry rule being re-derived.
- [Remote conversation repository](remote-conversation-repository.md) — hosts the
  `TYPE_RATE_LIMITED` demux arm; `UsageLimitProjection` holds the `usageLimitsByConversation` state, the
  decoder and the expiry-applying read.
- [Thinking-progress state](thinking-progress-state.md) (#801) — the closest sibling in shape (a
  payload-carrying `Map`, the no-timer read-time-only posture) but with no expiry of its own and clears
  routed through *other* frames' arms, unlike this one's self-contained benign-frame clear plus
  independent expiry.
- [API-retry status](api-retry-status.md) (#593) — the payload-carrying `Map` shape this arm follows,
  because the wire carries a reading a bare `Set` cannot represent.
- [Compacting state](compacting-state.md) (#596) — the capability gate and malformed-drop idiom this arm
  shares; unlike this arm, carries no daemon-authored text or number of any kind.
- [Stall state](stall-state.md) (#395) — the separate signal this arm may neither raise nor clear.
- [ConversationRepository](conversation-repository.md) — the interface the defaulted `observeUsageLimit`
  joins; [`StableConversationRepository`](stable-conversation-repository.md) — the facade that makes it
  reach the thread ViewModel, and the mechanism that drops a reading across a reconnect (the pairing-scoped
  clear).
- Server SSOT: `internal/protocol/interactive.go` (`RateLimitedPayload`), pyrycode#1405/#1410,
  `docs/protocol-mobile.md § rate_limited`.
- Reference (read for the expiry/clear rules, not the store shape): pyrycode-desktop
  `src/renderer/src/store/usageLimitStore.ts` (`selectUsageLimitFor`, `clearUsageLimitFor`).
