# Thinking-progress state — claude's mid-turn token-estimate proof of life

A per-conversation reading the thread layer can observe to learn **how far claude's current reasoning
has got** — its only mid-turn proof of life on the stream-json surface, since nothing else crosses the
wire during a long assistant turn. Landed in
[#801](../../specs/architecture/801-thinking-progress-decode.md) (split from #653, the data slice).
Rendering the reading in the status area is the **sibling render slice** of the same split,
[#803](thinking-indicator.md) — `ThinkingIndicator`'s optional `progress` param.

This doc covers the **data layer only**: decode the inbound `thinking_progress` envelope into
observable state. It renders nothing itself.

## The signal

```kotlin
// ConversationRepository (the interface every UI tier binds to)
fun observeThinkingProgress(conversationId: String): Flow<ThinkingProgress?> = flowOf(null)

// the portable element type, co-located with the interface (like ApiRetryStatus / SessionSettings)
data class ThinkingProgress(val estimatedTokens: Long, val estimatedTokensDelta: Long)
```

- **`null` is "no reading", never "claude is not thinking."** It covers no live connection, a
  connection without the `interactive` capability, a conversation no frame named, the window before the
  first frame, and the state after a clear. **Absence proves nothing**, for two reasons the wire
  contract owns: the PTY surface emits none of these frames at all, and the producer's rate bound means
  a quiet window may only be one where the accumulated delta hasn't yet crossed the threshold. Nothing
  may infer a stall from a gap here, and a frame must not clear one either — [`observeStall`](stall-state.md)
  is the separate signal for that, untouched by this one in both directions.
- **No sealed family, deliberately.** Unlike `ApiRetryStatus`, there is no `NotThinking` member: the
  wire has no falling edge of its own, so such a member would be a claim the daemon never makes. `null`
  at the flow is the single absent case.
- **Not a `LiveSessionEvent` subtype.** The frame carries no `turn_id` and opens or closes no turn — the
  turn's own thinking state is already [`LiveSessionEvent.TurnState`](turn-state-thinking-flag.md)'s. A
  new member of that sealed family would force every `when (event)` in the app to grow an arm for
  something that is state, not an event — the reason [`compacting`](compacting-state.md) and
  [`api_retry`](api-retry-status.md) both stayed off it too.
- **On the interface, with a `flowOf(null)` default.** The thread ViewModel reaches this state through
  the [`StableConversationRepository`](stable-conversation-repository.md) facade it already holds; the
  default is the cascade-escape valve — the [Fake](conversation-repository.md) and every inline test
  double inherit "no reading" and need no override, same lever as
  `observeCompacting`/`observeApiRetry`/`observeModelMenu`.

## The reading is not monotonic — carried verbatim, no exceptions

`estimatedTokens` restarts near zero at every inference-request boundary, several times inside one
turn. A reading that falls, repeats, or arrives as `0` is a **real** reading, not an absent one — `0`
in particular is a fresh restart and must never be read as "nothing to show." A consumer must never
clamp it with a running maximum and never subtract two readings expecting a non-negative result;
`estimatedTokensDelta` is a per-line increment, not an accumulator input — the deltas a client receives
do not sum to the turn's total, because the rate bound drops most of claude's lines and their
increments go with them.

**This is enforced by the shape of the write, not by a rule an implementer must remember.** The
projection write is a pure replace (`it + (conversationId to reading)`); the prior value is never read,
so there is structurally nowhere for a max guard, a difference between readings, or a truthiness gate
to live. A merge-shaped write is exactly where a well-meaning monotonicity guard would have appeared,
because the restart-to-near-zero behaviour looks like a bug until you've read the wire contract.

## The reading has no falling edge — its clears live on other arms

Unlike `compacting` (`active: false`) or `api_retry`, `thinking_progress` carries no edge at all — a
frame only ever asserts a reading. Its clears are therefore driven by **other** frames:

| Clear | Source | Why |
|---|---|---|
| Turn end | the live-session arm's `is LiveSessionEvent.TurnEnd` branch (`TYPE_TURN_STATE`/`TYPE_ASSISTANT_DELTA`/`TYPE_TOOL_USE`/`TYPE_TOOL_RESULT`/`TYPE_TURN_END`) | the turn whose reasoning it described has ended |
| Session transition | `TYPE_SESSION_TRANSITION` arm | the session whose reasoning it described has been replaced |
| Reconnect | none written — connection-scoped state | the daemon re-asserts no `thinking_progress` on connect; a held reading would report the depth of a think that has since finished |

Both written clears route by their own frame's decoded conversation id, so neither can clear another
conversation's reading; removal of an absent key is a no-op.

**Placement trap: the turn-end clear must live *inside* the `TurnEnd` branch, not beside the pre-`when`
stall clear.** The live-session arm already clears an active stall unconditionally before dispatching
on `event`'s type (any forward progress clears a stall). Hoisting the thinking-progress clear to sit
beside that stall clear reads as a natural sibling — but it would wipe the reading on **every** live
event (`assistant_delta`, `tool_use`), not just at turn end, making the reading close to useless while
still passing a naive "turn end clears it" test. The negative control that catches this is a
non-turn-end live event (e.g. `tool_use`) for the same conversation, asserted to **leave the reading
standing**; without that second test the trap ships green. Any future frame whose clears live on
someone else's arm needs the same pair of tests (a positive "the clearing event clears it" case and a
negative "a similar-looking but wrong event does not" case).

## How it surfaces in the repository

The behaviour lives in [`RemoteConversationRepository`](remote-conversation-repository-thread-observables.md#observethinkingprogressconversationid--the-thread-observable-thinking-progress-reading-801)
on the **single existing** inbound collector. In short: a connection-scoped
`MutableStateFlow<Map<String, ThinkingProgress>>` (a payload-carrying `Map`, `api_retry`/`model_list`'s
shape rather than `stall`/`compacting`'s bare `Set` — the wire carries a reading, and membership cannot
represent one), written from three places on that one collector so nothing races: the
`thinking_progress` arm replaces an entry, and the `turn_end`/`session_transition` arms each remove one.
`observeThinkingProgress` is a cold `.map { it[conversationId] }.distinctUntilChanged()` projection.
Connection-scoped, in-memory: a fresh repo per connection (#351) starts empty, so a reading **never
survives a reconnect** — the facade's `flatMapLatest` drops the previous connection's projection the
instant the connection changes, so the reconnect clear needs no code of its own.

`distinctUntilChanged` suppresses only value-*identical* re-emissions — a frame for **another**
conversation does not re-emit this flow, but a *lower* reading is a different `ThinkingProgress` value
and does reach the collector, and so does a reading of `0`.

**Test idiom: a `StateFlow` projection conflates frames pushed within one `runCurrent()`.** Two pushes
drained in the same turn coalesce to the latest value — the collector never observes the intermediate
one. A test asserting a multi-frame sequence through this (or any sibling) projection must interleave
`runCurrent()` between pushes, or it silently proves something weaker than it reads (three cases here
initially failed this way against a correct implementation). [`compacting`](compacting-state.md)'s
`compacting_roundTrip_fallingEdgeClears` already interleaves for the same reason.

## Capability gate (fail-closed)

The demux arm sits inside `CAPABILITY_INTERACTIVE in negotiatedCapabilities()` — the same gate
[`stall`](stall-state.md#capability-gate-fail-closed) and
[`api_retry`](api-retry-status.md#capability-gate-fail-closed) use. The server already fans
`thinking_progress` out only to phones that advertised `interactive`; the mobile gate is defence in
depth — a non-interactive phone that receives a spurious `thinking_progress` from a buggy or hostile
daemon never decodes it and never surfaces it.

## Edge cases & limitations

- **Malformed payload dropped, collector survives (AC #1).** A missing field or a wrong-typed one (a
  number where `conversation_id`'s `String` is declared, an object/array where `estimated_tokens`'
  `Long` is declared) fails the strict decode of `ThinkingProgressPayloadDto` → that one envelope is
  dropped, the lone inbound collector lives, a later valid frame still lands. **Nothing logs the
  payload** — a logged `conversation_id` is a cross-conversation correlation leak. No quoted-primitive
  probe in the tests: kotlinx's tree decoder accepts a quoted primitive even at `isLenient = false`
  (the [`api_retry`](api-retry-status.md) measured lesson), so that shape proves nothing about
  strictness.
- **`Long`, not `Int`, for both fields.** The Go field is a 64-bit `int`; an `Int` DTO would fail to
  decode a value the wire can legally express and silently drop the frame. This repo's other
  token-valued fields (`SessionSettings.usedTokens`/`windowTokens`) are already `Long` — the same width
  trap `QueuedMessage.id`'s KDoc records for `queued_msg_id`.
- **No lower-bound rejection.** A negative reading is carried verbatim, not rejected — rewriting server
  data at the decode boundary would diverge from every sibling's carry-verbatim posture, and the wire
  documents no lower bound to enforce.
- **Not turn-scoped by itself, and never a stall signal in either direction (AC #4).** No `turn_id` on
  the wire; the arm folds no thread row and touches `stalledConversations` in **neither** direction. Not
  raising one is the wire contract's explicit rule (the rate bound means a quiet window is not a stall,
  and the PTY surface emits none of these at all). Not clearing one is the `compacting` rule for the
  same reason it exists there: a reading is claude busy, not turn forward progress, and clearing a stall
  here would let a daemon suppress the phone's stall indicator by emitting these frames.
- **A hostile or crashed daemon can leave a reading stuck** by sending one frame and never a clearing
  event, standing until the next turn end, session transition, or reconnect. Deliberately undefended —
  a client-side timeout is precisely the "infer something from a gap" AC #4 forbids, and the shipped
  [`stall`](stall-state.md) (#395) and [`compacting`](compacting-state.md) (#596) arms have the
  identical property (worse, in `stall`'s case, with no wire clearing edge at all) without incident.
  **The rendering sibling must not block interaction on the reading and must tolerate a long-lived
  one.** [#803](thinking-indicator.md) meets both: nothing on the render path throws, logs, or blocks,
  and the interrupt control and composer stay live beside the arm regardless of what it shows (as, until
  [#883](../../specs/architecture/883-retire-literal-screen.md) retired it, did the stall promotion
  banner).

## Security

`security-sensitive`; builder self-review **PASS**. One untrusted→trusted boundary,
`decodeThinkingProgress(envelope): Pair<String, ThinkingProgress>?`, behind the already-authenticated
Noise channel. This is the **narrowest** payload in the conversation-status family: a routing id that
stays a map key plus two `Long`s, so no daemon-authored text of any kind can structurally reach Compose
through this arm. Flood-safe by shape — the write is a full replace keyed by conversation, so N frames
for one conversation cost O(1) memory rather than O(N). Nothing is persisted, nothing is logged (payload,
`conversation_id`, and the caught throwable are all discarded — kotlinx-serialization can quote the
offending input in its own message). Memory posture (unbounded key growth from a hostile-but-authenticated
daemon sending frames for fabricated conversation ids) is pre-existing and identical across every sibling
projection, not fixed per-arm. UI-side leakage (screenshot/overlay disclosing that a conversation is
active) belongs to the rendering sibling, exactly as [Compacting indicator](compacting-indicator.md)
(#597) inherited it from [Compacting state](compacting-state.md) (#596). [#803](thinking-indicator.md)
accepted the same disclosure rather than deferring it: the arm now also discloses roughly how deep the
reasoning has got, which is strictly narrower than the thread content already on screen above it.

## Related

- [Thinking indicator](thinking-indicator.md) (#803) — the render sibling: `ThinkingIndicator`'s optional
  `progress` param, the wording rules the wire contract forces, and the display sanity gate that declines
  an implausible reading rather than clamping it.
- [Remote conversation repository](remote-conversation-repository.md) — hosts the
  demux arm; `ThinkingProgressProjection` holds the `thinkingProgressByConversation` state, the decode and the read.
- [Compacting state](compacting-state.md) (#596) — the closest sibling in shape (no counter on the wire
  there, unlike this one) but with a real falling edge, unlike this one; both share the capability gate
  and malformed-drop idiom.
- [API-retry status](api-retry-status.md) (#593) — the payload-carrying `Map` shape this arm follows,
  because the wire carries a reading a bare `Set` cannot represent.
- [Stall state](stall-state.md) (#395) — the separate signal this arm may neither raise nor clear.
- [Turn-state thinking flag](turn-state-thinking-flag.md) (#406) — the coarse `thinking`/`responding`/
  `idle` phase this reading complements; `turn_state`, not this arm, opens and closes a turn's thinking
  state.
- [ConversationRepository](conversation-repository.md) — the interface the defaulted
  `observeThinkingProgress` joins; [`StableConversationRepository`](stable-conversation-repository.md)
  — the facade that makes it reach the thread ViewModel, and the mechanism that drops a reading across a
  reconnect.
- Server SSOT: `internal/protocol/interactive.go` (`ThinkingProgressPayload`), pyrycode#1386,
  `docs/protocol-mobile.md § thinking_progress`.
