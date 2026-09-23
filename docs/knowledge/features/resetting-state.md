# Resetting state — the thread-observable Reset-phase signal

A per-conversation `ResetStatus?` the thread layer observes to learn which phase a daemon-driven
**Reset** is in: the wrap-up turn writing a handoff note, or the respawn of claude under a new
session. Landed in [#871](https://github.com/pyrycode/pyrycode-mobile/issues/871) (split from #630),
following the `compacting` decode (#596). Decode-only — rendering the phase in the thread status area is
the sibling ticket [#872](https://github.com/pyrycode/pyrycode-mobile/issues/872), shipped, folded into
[Resetting indicator](resetting-indicator.md).

This doc covers the **data layer only**: decode the inbound `resetting` envelope into observable
state. It renders nothing itself.

## The signal

```kotlin
// ConversationRepository (the interface every UI tier binds to)
fun observeResetting(conversationId: String): Flow<ResetStatus?> = flowOf(null)

data class ResetStatus(val phase: Phase, val handoff: Handoff) {
    enum class Phase { WrappingUp, Restarting }
    enum class Handoff { Pending, Written, Skipped }
}
```

- `null` means no reset is running. A `ResetStatus` on each rising edge, back to `null` on the falling
  edge or on that conversation's `session_transition`. Cold flow; re-emits only on change
  (`distinctUntilChanged`), so a reset on *another* conversation never wakes this collector.
- **Two closed enums, no `String`.** The wire's `phase` and `handoff` are closed sets while a reset
  runs, so the decode boundary narrows both to enums and drops a frame carrying any other token; the
  routing `conversation_id` stays a projection map key and never becomes a `ResetStatus` field. No
  daemon-supplied text reaches a consumer through this type — and none exists to reach it: the
  handoff note itself never crosses the wire, only whether one was made.
- **No "not resetting" member.** That is `null` at the flow, the [`ThinkingProgress`](thinking-progress-state.md)
  posture, not [`ApiRetryStatus`](api-retry-status.md)'s `NotRetrying` sealed member.
- **The two fields are carried independently.** The wire contract states a closed set per field, not a
  combination rule, so `wrapping_up` paired with `written` is not rejected at this boundary — nothing
  in the protocol says it can't happen.
- **On the interface, with a `flowOf(null)` default.** The thread ViewModel reaches this state through
  the [`StableConversationRepository`](stable-conversation-repository.md) facade it already holds, and
  the facade only delegates the `ConversationRepository` interface — a concrete-only capability would
  be stranded ([[post-352-connection-scoped-repo-behind-facade]]). The default is the cascade-escape
  valve: the [Fake](conversation-repository.md) and every inline test double inherit "never resetting"
  and need no override — the same lever as `observeStall`/`observeQueue`/`observeCompacting`. Only the
  facade and the [live remote repo](remote-conversation-repository.md) override it.

## Two rising edges, one reading — why a later rising edge replaces rather than stacks

The wire sends **two** rising edges before its single falling edge: `wrapping_up`/`pending` (the
wrap-up turn starts), then `restarting`/`written` or `restarting`/`skipped` (the handoff outcome is
resolved and claude is about to respawn), then `active: false` with both strings empty. A second
`active: true` is a **phase change of the same reset**, not a second reset — the state is replaced,
never counted or stacked.

This is why the projection is a payload-carrying `Map<String, ResetStatus>` — the
[`ThinkingProgress`](thinking-progress-state.md)/[`ApiRetryStatus`](api-retry-status.md) shape, not
[`stall`](stall-state.md)/[`compacting`](compacting-state.md)'s bare `Set<String>` membership — and why
`data class ResetStatus` matters structurally, not cosmetically: equality is what makes the
repository's `distinctUntilChanged` projection re-emit the `wrapping_up`/`pending` →
`restarting`/`written` transition (a genuinely different value) while suppressing a frame for another
conversation (an equal map, no re-emission).

## Why the falling edge does not run through the rising-edge mapper

`ResettingPayloadDto.toStatus()` is a **rising-edge-only** mapper: it narrows `phase`/`handoff` to
their enums and returns `null` when either token is outside its closed set, including the empty
strings a falling edge carries. The falling edge never reaches this mapper. `ResettingProjection.apply`
branches on `active` first — `if (!dto.active)` removes the map entry unconditionally, before any
field content is read — because the wire contract calls both strings **meaningless** once `active` is
`false`. Running the falling edge through the closed-set mapper instead (the way `toBoundary`-style
mappers do for other events) would leave a stuck reading whenever a falling edge happened to carry a
non-empty string that failed the closed-set check — a real trap, not a hypothetical one, and the
reason `resetting_fallingEdgeWithNonEmptyStrings_stillClears` exists as its own test case.

An **empty** `phase` or `handoff` on a *rising* edge is the opposite case: outside the closed set and
dropped, leaving any prior reading standing. Treating `""` as an "unknown" phase to surface would show
a reset with no phase, which is worse than showing the previous reading a beat longer.

## How it surfaces in the repository

The behaviour lives in [`RemoteConversationRepository`](remote-conversation-repository-thread-observables.md#observeresettingconversationid--the-thread-observable-reset-phase-reading-871)
on the **single existing** inbound collector — see that doc for the field, the demux arm, and the
`session_transition` clear. In short: a connection-scoped `MutableStateFlow<Map<String, ResetStatus>>`
in its own `ResettingProjection.kt` file (the [status-projection](remote-conversation-repository.md#status-projections-one-file-per-status-event)
pattern), written from **two** places on the repository's single inbound collector so neither races:
`ResettingProjection.apply` (rising edge replace / falling edge remove) and `ResettingProjection.clear`
(called from the `TYPE_SESSION_TRANSITION` arm, alongside the same-arm
[`ThinkingProgressProjection.clear`](thinking-progress-state.md)). The daemon emits `restarting` before
it respawns claude, so no rising edge can follow a transition — the clear only ever removes a reading,
never races one being written back in. Connection-scoped, in-memory: a fresh repo per connection (#351)
starts empty, so a reset reading **never survives a reconnect**.

## Capability gate (fail-closed)

The demux arm sits inside `CAPABILITY_INTERACTIVE in negotiatedCapabilities()` — the same gate
[`stall`](stall-state.md#capability-gate-fail-closed), [`api_retry`](api-retry-status.md#capability-gate-fail-closed)
and [`compacting`](compacting-state.md#capability-gate-fail-closed) use. The server already fans
`resetting` out only to phones that advertised `interactive`; the mobile gate is defence in depth — a
non-interactive phone that receives a spurious `resetting` from a buggy or hostile daemon never decodes
it and never surfaces it.

## Edge cases & limitations

- **Malformed payload dropped, collector survives.** A missing field, or one whose JSON shape cannot be
  read as its declared type (a number where `conversation_id` is a `String`, an object where `active`
  is a `Boolean`), fails the strict decode of `ResettingPayloadDto` → that one envelope is dropped, the
  lone inbound collector lives, a later valid frame still lands. A **quoted** primitive is not a usable
  malformed probe — kotlinx's tree decoder accepts it even at `isLenient = false` (the lesson carried
  from [Compacting state](compacting-state.md#edge-cases--limitations)); use a genuinely wrong-shaped
  value instead.
- **Not turn-scoped, not forward progress, and never a stall lever.** No `turn_id` on the wire, and
  this arm never opens, closes, or alters a turn. It also does **not** raise or clear an active
  [stall](stall-state.md) in either direction: a reset is the daemon's own routine, not claude making
  or failing to make forward progress; clearing a stall here would let a daemon suppress the phone's
  stall indicator by emitting `resetting` frames, and raising one would be simply wrong — a reset is
  expected daemon behaviour, not a failure to progress. It folds no thread row and emits nothing on the
  live-session stream.
- **No server-supplied free text.** `phase` and `handoff` are narrowed to closed enums at the decode
  boundary and `conversation_id` stays a map key, so no daemon-supplied string of any kind reaches a
  consumer through `ResetStatus`. The handoff note's own content never crosses the wire at all — only
  whether one was written.
- **A hostile or crashed daemon can leave the reading stuck** by sending a rising edge and never a
  falling one. Deliberately undefended, the same posture as [`compacting`](compacting-state.md#edge-cases--limitations):
  the wire contract states the falling edge **always** arrives, on success and on every error path, so
  no client-side timeout is owed; a `session_transition` and a reconnect both clear it regardless. The
  render ticket that consumes this state must not block interaction on it.

## Security

`security-sensitive`; builder self-review **PASS** (recorded in the plan,
[`docs/specs/architecture/871-resetting-decode.md`](../../specs/architecture/871-resetting-decode.md)).
One untrusted→trusted boundary — `ResettingProjection`'s private decoder through the single configured
`MobileJson`, behind the already-authenticated Noise channel and the `interactive` gate. Both wire
strings are narrowed to enums by `toStatus()` before anything is stored; the routing id stays a map
key. No daemon-supplied text, number, or id can reach a consumer through `ResetStatus`, and an
out-of-set token is dropped rather than carried as "unknown". No token, key, or credential is read,
stored, or generated; no persistence, no file paths, no IPC surface, no crypto primitive touched.
Nothing on this path logs the payload — the caught decode exception is discarded, since
kotlinx-serialization can quote the offending input in its message. Unbounded key growth from a
fabricated `conversation_id` is the pre-existing projection-family posture shared by every sibling map
(`stalledConversations`, `apiRetryByConversation`, `compactingConversations`, `thinkingProgressByConversation`);
a family-wide fix, if ever warranted, is its own ticket, not this one's.

## Related

- [Remote conversation repository — thread observables](remote-conversation-repository-thread-observables.md#observeresettingconversationid--the-thread-observable-reset-phase-reading-871) —
  hosts the demux arm and the `session_transition` clear; `ResettingProjection` holds the state, the
  decode, and the read.
- [Compacting state](compacting-state.md) (#596) — the projection pattern this ticket followed (own
  file, `apply`/`observe`, private decoder, drop idiom, no logging), and the source of the
  quoted-primitive test lesson.
- [Thinking-progress state](thinking-progress-state.md) (#801) — the payload-carrying `Map` shape and
  the "clear lives on `session_transition`" precedent this arm reuses.
- [API-retry status](api-retry-status.md) (#593) — the sibling that established the closed-set mapper
  drop idiom `toStatus()` follows.
- [ConversationRepository](conversation-repository.md) — the interface the defaulted
  `observeResetting` joins; [`StableConversationRepository`](stable-conversation-repository.md) — the
  facade that makes it reach the thread ViewModel.
- Consumer: [Resetting indicator](resetting-indicator.md) (#872) — renders the phase in the thread
  status area, between usage limit and compaction in the status-slot ladder.
- Server SSOT: pyrycode#2478, `docs/protocol-mobile.md § resetting`.
