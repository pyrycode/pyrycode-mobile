# Replay cursor — the reconnect-spanning `event_id` high-water mark

The mobile half of ADR 025's **mid-turn replay** contract: a tiny, thread-safe holder of the latest
interactive structured-stream `event_id` the phone has observed, recorded as a strictly-advancing
high-water mark that **outlives connection churn**. On a mid-turn reconnect the phone advertises this
value as `hello.last_event_id` so the daemon can replay the events the phone missed before the live
stream resumes. Three slices: **recording** the cursor landed in [#412](../codebase/412.md) (split from
\#402); **advertising** it (`hello.last_event_id`, read live at `hello`-build) landed in
[#416](../codebase/416.md) (split from #413); **reacting** to the daemon's aged-out-of-ring `resync`
marker — `reset()` the cursor (next reconnect advertises fresh) + surface a `LiveSessionEvent.ReplayGap`
— landed in [#417](../codebase/417.md) (split from #413, `blockedBy #416`). Full reload via
`backfill_since` is deferred (no daemon handler yet).

Wire SSOT: pyrycode `docs/protocol-mobile.md` § "Interactive events (v2, capability-gated)" → "Replay
cursor (`event_id`)"; ADR 025 § Backpressure / replay. Server producer: pyrycode#649 (durable `event_id`
on the wire); server consumer of the advertised cursor: pyrycode#647 (`hello.last_event_id` → ring replay
/ resync marker).

## `event_id` is envelope-level, durable, and distinct from `id`

Every interactive structured-stream frame (`turn_state` / `assistant_delta` / `tool_use` /
`tool_result` / `turn_end`) carries an **envelope-level** `event_id` (a sibling of `id` / `type` /
`in_reply_to`, **not** a per-type payload field). Two ids ride the same envelope and mean different
things:

| Field | Scope | Lifetime |
|---|---|---|
| `id` | per-connection counter | **resets** each reconnect |
| `event_id` | per-conversation, strictly-increasing | **durable**, stable across reconnects |

`event_id` is modeled on [`Envelope`](mobile-protocol-v2-wire-layer.md#envelope--application-message-frame)
**identically to `inReplyTo`** — `@SerialName("event_id") val eventId: Long? = null` — so under
`MobileJson` (`explicitNulls = false`) it **omits when null** on encode (every outbound frame, including
`hello`, stays byte-identical to today) and the nullable default tolerates its per-frame absence on
decode (a missing `event_id` never fails the decode of an otherwise-valid envelope). It mirrors the
server's `omitempty *uint64`; a pathological `uint64 > 2^63` decodes to a negative `Long` and is rejected
downstream by the positive guard rather than poisoning the cursor.

## `ReplayCursor` — the high-water-mark holder

`data/network/ReplayCursor.kt` — portable (imports only `kotlinx.coroutines.flow`, zero Android imports
per the CLAUDE.md `data/` rule). The entire contract:

```kotlin
class ReplayCursor {
    val latest: Long?              // latest recorded event_id, or null if nothing valid ever observed
    fun record(eventId: Long)      // fold an observed value into the high-water mark
    fun reset()                    // clear the mark back to null (#417, on a resync marker)
}
```

- **`latest`** is a **synchronous point read** valid at any moment — including *before* a connection's
  inbound path exists, which is exactly when [#416](../codebase/416.md) reads it at `hello`-build. On a
  fresh process start it is `null` ("no cursor", omittable — **never `0`**), because the cursor is
  **in-memory only** and never persisted.
- **`record`** is fail-closed at the trust boundary: it **ignores `eventId <= 0`** (a valid `uint64`
  cursor is ≥ 1; a `0`, a negative, or a wrapped-huge value is never recorded), then advances **only** on
  a strictly-greater value (`null` counts as below any value), so a smaller / equal / out-of-order /
  replayed value is a no-op.
- **`reset`** ([#417](../codebase/417.md)) clears the mark back to `null` (`mark.value = null`) — the
  no-cursor state, so the *next* `hello`-build reads `null` and **omits** `last_event_id` (#416), a fresh
  resume. Called only on the daemon's **`resync` marker** (its signal that the advertised position aged
  out of the bounded ring, so gap-free in-ring replay is impossible). A direct atomic set, consistent
  with `record`'s lock-free backing; called from the **same single inbound collector** as `record`, so
  reset and record never race within a connection, and the strictly-greater fold simply re-advances from
  `null` on the next observed frame (post-resync the cursor re-fills with positions the phone now
  genuinely holds). See [§ Reacting](#reacting--the-resync-marker-417).
- Backing is a `MutableStateFlow<Long?>(null)` with an atomic `update {}` **max-fold** — lock-free and
  **TOCTOU-free** (max-fold, not check-then-set), so the recorder coroutine (the inbound collector) and
  the reader (#416's hello-build, a different coroutine) cannot race. The backing flow is **intentionally
  not exposed** — the advertise needs a point read, not an observable stream — so only `latest`,
  `record`, and `reset` are public.

## Recording — first line of the single inbound consumer

Recording happens inside the **existing** single inbound consumer of
[`RemoteConversationRepository`](remote-conversation-repository.md) — **no second subscription** to
`pump.inbound`. A private `recordReplayCursor(envelope)` is the **first line** of `onInbound`, *before*
the `when` demux:

```kotlin
private fun recordReplayCursor(envelope: Envelope) {
    if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
        envelope.eventId?.let { replayCursor.record(it) }
    }
}
```

Three properties, each mapped to an AC:

- **Envelope-level and type-agnostic.** It reads `envelope.eventId` directly, independent of whether the
  per-type structured *payload* decodes — so a frame with a valid `event_id` but a malformed payload still
  advances the cursor (the durable event occurred; the cursor marks position, not decodability).
- **A non-interactive frame leaves the cursor unchanged** (AC #3): it carries no `event_id`, so
  `eventId` is `null` and `record` is never called. The `interactive` gate is the belt; the null-check is
  the suspenders.
- **Defence-in-depth gate.** Gating on the negotiated `interactive` capability (symmetric with the
  [`liveSessionEvents`](live-session-events.md) and `stall` arms) means a buggy/hostile *authenticated*
  daemon that ignored the negotiated set and injected `event_id` to a phone that did **not** negotiate
  `interactive` cannot advance a cursor the phone will never advertise (#416's advertise is itself
  `interactive`-gated). This is defence-in-depth, not load-bearing — see § Trust boundary.

The recording is a **pure side-write with no feedback into delivery**: it writes the cursor and returns;
no `when` arm, no `liveSessionEvents` emission, and no event delivery reads the cursor. It is also
**throw-free** by construction (an already-decoded `Long?`, a set-membership check, a pure max-fold), so
it cannot kill the single inbound collector that also carries `conversations`/`message`/`ack` traffic.

## Reconnect-spanning ownership

The recording site (`RemoteConversationRepository`) is **rebuilt every reconnect**, so the cursor cannot
live there. It lives on the process-lifetime
[`RelayRepositoryCoordinator`](relay-repository-coordinator.md) — the single layer that owns *both* the
per-connection repo (the recorder) and the per-connection pump (the hello producer #416 reads from):

```kotlin
internal val replayCursor: ReplayCursor = ReplayCursor()   // survives connection churn
```

It is threaded into each per-connection repo in `onConnection` as one extra **defaulted** named arg —
keeping `onConnection` non-suspending (no new suspension point) and every existing construction/test
compiling unchanged (the same defaulted-param discipline as `deviceName` / `negotiatedCapabilities`).
`teardownActive` (the per-connection churn path) never touches it; only a full coordinator `close()` ends
it. It is `internal` (module-visible, read-only seam) so #416 and unit tests read it without a public API
surface or a new Koin binding — mirroring `toPyrycodeLinkStatus`'s visibility, and the third non-interface
surface the coordinator threads off the concrete repo/pump (after `liveSessionEvents` #406 and the
`pyrycodeStatus` derivation #392).

```
pump.inbound (single consumer) ─▶ RemoteConversationRepository.onInbound(envelope)
                                    │  recordReplayCursor(envelope):
                                    │     if interactive: envelope.eventId?.let(coordinator.replayCursor::record)
                                    └─▶ existing when(type) demux (unchanged)

coordinator.replayCursor ──(survives connection churn)──▶ read at next hello-build (#416)
```

## Advertising — read live at `hello`-build ([#416](../codebase/416.md))

The **advertise** half reads `replayCursor.latest` at the moment the next connection's `hello` is built
and sends it as `HelloClientPayload.lastEventId`. The read is **deferred through a `() -> Long?`
supplier**, not captured at construction, so each reconnect advertises the cursor as of its *own*
handshake — surviving the per-connection session rebuild and never baked into the static client
identity (AC#1). The supplier is threaded **session ← factory ← composition root**:

```
RelayRepositoryCoordinator.replayCursor (internal val, process-scoped)   ← #412
        │  read lazily as { coordinator.replayCursor.latest }
AppModule  ── () -> Long? supplier ──▶ NoiseSessionFactory (single)
        │  factory.create() forwards the supplier verbatim
NoiseIkSession(lastEventId)  ── buildHello() invokes lastEventId() ──▶ HelloClientPayload.lastEventId
        │  MobileJson explicitNulls=false omits a null value
hello on the wire: last_event_id present (value) or absent (fresh connection)
```

- **The `data/network` layer keeps zero repository dependency.** The factory holds only a `() -> Long?`
  lambda; the network → repository edge exists **only** as the `AppModule` lambda
  `{ get<RelayRepositoryCoordinator>().replayCursor.latest }`. No `createPump` / coordinator / pump
  signature change.
- **No Koin DI cycle.** Constructing the factory only *stores* the lambda; it resolves the coordinator
  **only when invoked at `hello`-build** (per connection, after the coordinator is constructed and
  started), by which point Koin returns the already-cached singleton. The coordinator single resolves
  the factory eagerly at its own construction, but the lambda is never invoked there.
- **`HelloClientPayload.lastEventId`** is modeled byte-for-byte like `Envelope.eventId`
  (`@SerialName("last_event_id") Long? = null`): a fresh connection (nothing observed) omits the field
  via `explicitNulls = false` — never `0`/`null` (AC#2) — while a positive value rides as the server's
  `omitempty *uint64`. It is the phone's **only** obligation; it never names a conversation (the daemon
  resolves that server-side from `currentConv`).
- **No double-apply** (AC#3): the daemon's ring-replayed tail (`event_id > last_event_id`) arrives on the
  **same single inbound path** as the live stream that follows; distinct `message_id`s yield one row
  each, and a defensive `message_id` overlap folds in place via the existing `appendMessages` dedup
  (#313). The advertise composes with the existing substrate; it adds no new delivery code.

## Reacting — the `resync` marker ([#417](../codebase/417.md))

When the position the phone advertised has **aged out of the daemon's bounded ring** (pyrycode#646),
gap-free in-ring replay is impossible, so the daemon emits a **`resync` marker** — `type = "resync"`,
binary → phone, inline `{conversation_id}`, **no** `event_id` (server producer: `emitResync` /
`TypeResync` in pyrycode `internal/relay`). [#417](../codebase/417.md) reacts on the **same single
inbound collector** with a `TYPE_RESYNC` arm in
[`RemoteConversationRepository.onInbound`](remote-conversation-repository.md), gated identically to the
`recordReplayCursor` / `stall` / structured-event arms:

```kotlin
TYPE_RESYNC -> {
    if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
        replayCursor.reset()                              // unconditional on the type match
        resyncConversationId(envelope)?.let { id ->       // surface only if the id decodes
            mutableLiveSessionEvents.tryEmit(LiveSessionEvent.ReplayGap(id))
        }
    }
}
```

- **Reset is unconditional on the type match; the surface is payload-conditional.** The cursor is
  **process-global** — it does not depend on which conversation the marker names — so a
  malformed/absent `conversation_id` still resets it. Only the
  [`LiveSessionEvent.ReplayGap`](live-session-events.md) surface (which needs an id to route) is dropped
  when the id does not decode. The safety action (avoid mis-resuming) must not depend on payload shape.
- **No record-then-reset conflict for the same envelope.** `recordReplayCursor` runs first (the first
  line of `onInbound`), but a `resync` carries **no** `event_id`, so it records nothing — there is no
  ordering hazard with the `reset()` that follows in the arm.
- **`backfill_since` full reload is deferred** — no daemon-side message-history store / handler exists
  yet. This slice's contract ends at reset-the-cursor + surface-the-gap.

See [Reacting → the resync arm](remote-conversation-repository-live-stream-and-modals.md#the-resync-arm--reset-the-cursor--surface-the-gap-417)
for the repository attachment and [`ReplayGap`](live-session-events.md) for the surfaced event.

## Trust boundary

`event_id` is untrusted network input that has already crossed the **authenticated** Noise channel (the
pump MAC-verifies + decrypts before any envelope reaches `onInbound`), so its source is the authenticated
paired daemon, not an arbitrary relay MITM. It crosses untrusted→trusted at exactly one named point —
`ReplayCursor.record` — after which the only public read (`latest`) can only ever return a validated,
monotonic, positive value, never a raw wire integer.

Two failure modes, both fail-closed:

- **Malformed / wrong-typed `event_id`** fails the *envelope* decode upstream in
  [`NoiseSessionPump`](noise-session-pump.md) (exactly as a bad `id`/`type`/`ts` does today); the pump
  tears the session down and the supervisor reconnects. The cursor is coordinator-scoped (not
  pump-scoped), so a teardown never touches it — the reconnect re-reads the intact mark.
- **Out-of-range / out-of-order value** (`0`, negative, wrapped-huge, or below the current mark) is
  rejected or ignored by `record`. The cursor never moves backward and never records a non-positive
  sentinel.

> **The watermark-mute exploit is a *server* concern — and it is now closed.** A hostile authenticated
> daemon sending a giant `event_id` → cursor jumps → advertise → daemon replays nothing newer → silent
> live-stream drop only becomes reachable once the cursor is **advertised** ([#416](../codebase/416.md))
> *and* the server acts on the watermark. Because recording is a pure side-write with no feedback into
> delivery, #412 alone cannot mute/drop an event. The advertise was **Inbox-gated** until the server
> stopped trusting `last_event_id` unboundedly — **closed by pyrycode#663** (the `min(afterID, newest)`
> clamp + bounded dedup guard, re-verified present on pyrycode `main` @ `0386940` at #416's architect
> time). Combined with the phone advertising only the *accurate* positive-guarded cursor it recorded, the
> caught-up-watermark suppression cannot trigger. (Don't be misled by pyrycode `codebase/647.md`'s stale
> "unresolved MUST FIX" note — the fix landed under #663; `codebase/663.md` is the current state.)
> Nothing logs `event_id` (a non-secret counter) or the payload, uniform with every `onInbound` arm.

## Edge cases / limitations

- **In-memory only — process death resets it to `null`.** A fresh start has no cursor; the phone
  re-derives thread state from the live stream (+ a re-`backfill_since`) on the next connection, exactly
  as the other connection-scoped projections do.
- **Single mark for the currently-paired server.** Re-pairing to a different server within one process
  lifetime would make the mark stale for the new server — **out of scope** here (re-pairing is a heavy
  `PairedServer` flow); stale-cursor recovery is owned by [#417](../codebase/417.md)'s `resync` reaction
  + the server resync marker (pyrycode#647). This slice records faithfully; it is not the place to detect
  a pairing change.
- **The cursor is conceptually per-conversation, but this slice names no conversation.** It records the
  latest *observed* value as a single global mark; the daemon resolves the conversation on its side
  (via `currentConv` on reconnect). Naming a conversation here would be wrong.

## Testing

`ReplayCursorTest.kt` pins the behaviour invariant (null-initial, positive-guarded, strictly-advancing).
`MobileWireCodecTest.kt` pins the `event_id` round-trip + omit, mirroring the `inReplyTo` tests.
`RemoteConversationRepositoryTest.kt` (over `FakeSessionPump` + `runCurrent()`, **not**
`advanceUntilIdle()`) covers cursor advance, out-of-order no-op, the non-interactive and gate-closed
no-records, the malformed-payload-still-advances case, reconnect survival via two repos sharing one
cursor, and ([#417](../codebase/417.md)) the `resync`-arm cases — reset → `latest == null`, the
gate-closed no-reset/no-surface, malformed-`conversation_id`-still-resets-no-surface, and re-advance
after resync. `ReplayCursorTest.kt` additionally pins `reset()` (clears after a record, idempotent on a
fresh cursor, re-advances on the next record). `RelayRepositoryCoordinatorTest.kt` asserts
`replayCursor.latest` persists across an `onConnection` churn. All unit; no instrumented coverage (pure
`data/` layer).

## Related

- Tickets: [#412](../codebase/412.md) (record; split from #402) + [#416](../codebase/416.md) (advertise;
  split from #413) + [#417](../codebase/417.md) (react to `resync`: `reset()` + surface the gap; split
  from #413, `blockedBy #416`) — files, line refs, patterns, lessons.
- Specs: `docs/specs/architecture/412-replay-cursor-event-id.md`,
  `docs/specs/architecture/416-advertise-replay-cursor-last-event-id.md`,
  `docs/specs/architecture/417-resync-reset-cursor-surface-gap.md`.
- Hosts the fields: [Mobile Protocol v2 wire layer](mobile-protocol-v2-wire-layer.md) — `Envelope.eventId`
  (recorded) + `HelloClientPayload.lastEventId` (advertised).
- Records it: [Remote conversation repository](remote-conversation-repository.md) (`recordReplayCursor`
  in the single inbound consumer) — alongside the [`liveSessionEvents`](live-session-events.md) decode
  seam ([#385](../codebase/385.md)) it shares the `onInbound` gate idiom with.
- Advertises it: [Noise_IK session](noise-ik-session.md) (`lastEventId` supplier read in `buildHello()`),
  wired off the [coordinator](relay-repository-coordinator.md)'s `replayCursor` at the composition root.
- Owns it across reconnects: [Relay repository coordinator](relay-repository-coordinator.md).
- Capability gate: [#401](../codebase/401.md) (`CAPABILITY_INTERACTIVE`, the negotiated-set surface).
- Wire/contract SSOT: pyrycode `docs/protocol-mobile.md` § Replay cursor; ADR 025 § Backpressure / replay;
  server pyrycode#649 (producer) / pyrycode#647 + pyrycode#663 (`last_event_id` consumer + clamp — what
  #416 advertises into).
- Reacts to the daemon's aged-out-of-ring `resync` marker: [#417](../codebase/417.md) (**landed**,
  `blockedBy #416`) — `reset()`s this cursor + surfaces a [`LiveSessionEvent.ReplayGap`](live-session-events.md);
  full reload via `backfill_since` deferred (no daemon handler yet).
</content>
