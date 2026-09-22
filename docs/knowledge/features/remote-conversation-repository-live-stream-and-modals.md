# Remote conversation repository — the Phase 4 `ConversationRepository` — live stream, modal seams and the replay cursor

Split out of [Remote conversation repository — the Phase 4 `ConversationRepository`](remote-conversation-repository.md) on 2026-09-05 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [Remote conversation repository — the Phase 4 `ConversationRepository`](remote-conversation-repository.md); see that document for what it does, its edge cases and its links.

## `liveSessionEvents` — the v2 structured-stream decode seam (#385)

A hot **`val liveSessionEvents: SharedFlow<LiveSessionEvent>`** (`RemoteConversationRepository.kt:174-180`)
that surfaces the five v2 **binary → phone** structured-stream envelopes — `turn_state`,
`assistant_delta`, `tool_use`, `tool_result`, `turn_end` — decoded into one typed
[`LiveSessionEvent`](live-session-events.md) family. The decode boundary itself (DTOs + mappers +
the gate + drop semantics) is documented in [Live-session events](live-session-events.md); this
section records only how it attaches to the repository.

- **Rides the single existing `pump.inbound` collector.** One new grouped arm joins the `onInbound`
  `when (envelope.type)` demux (`:265-275`) — **no second subscription** (ticket constraint). The
  arm gates on the negotiated `interactive` capability, then calls the private
  `decodeLiveSessionEvent(envelope): LiveSessionEvent?` helper (`:296`) and `tryEmit`s the result.
  Five `TYPE_*` constants join the companion (`:770`+). The same `.let { event -> … }` block also hosts
  the #395 stall-clear and the [#387 tool-call dispatch](remote-conversation-repository-thread-observables.md#live-tool-call-rows--applytooluse--applytoolresult-387)
  — three folds on one decoded event, one gate, one collector.
- **`SharedFlow`, not `StateFlow`** (`replay = 0`, `extraBufferCapacity = 64`, `DROP_OLDEST`): these
  are *events*, not current-value state. The bounded buffer + `DROP_OLDEST` make `tryEmit`
  **infallible and non-blocking** — load-bearing, so a slow live-event consumer never back-pressures
  the shared inbound collector and stalls `conversations`/`message`/`ack` processing.
- **Gated by a lazy capability supplier.** A new defaulted ctor param
  `negotiatedCapabilities: () -> Set<String> = { emptySet() }` (`:103`), read **per envelope**
  (`CAPABILITY_INTERACTIVE in negotiatedCapabilities()`, before decode, AC#2). It is a supplier, not
  a captured value: the repo is built while the pump is still `Handshaking`, but structured
  envelopes only arrive post-`Open`, so the lazy read always sees the final negotiated set. The
  [coordinator](relay-repository-coordinator.md) wires it from `(pump.state.value as?
  PumpState.Open)?.capabilities` — a non-suspending read that preserves `onConnection`
  cancellation-atomicity. The default `{ emptySet() }` ("gate closed") keeps the two-/three-arg
  constructions (tests, pre-wiring) compiling and surfacing no events — **zero edit fan-out**, the
  same defaulted-param discipline `deviceName` (#359) used.
- **On the concrete repo, not the interface** — the accepted `registerPushToken` (#359) pattern (see
  above): adding it to [`ConversationRepository`](conversation-repository.md) would force the
  [Fake](conversation-repository.md) + [facade](stable-conversation-repository.md) to plumb a flow
  this decode slice doesn't use. Facade/coordinator reachability for the UI consumers
  (#386/#387/#337) is downstream consumer-slice work.

`security-sensitive`, but the repository stays plain orchestration: the decode runs behind the
already-authenticated Noise channel, and **nothing in the new arm or the drop branch logs the
payload** (the text / tool-summary fields may carry sensitive session content). See
[Live-session events § Trust boundary](live-session-events.md#trust-boundary--no-payload-logging).

## `modalEvents` — the v2 permission/choice-modal decode seam (#437)

A second hot **`val modalEvents: SharedFlow<ModalEvent>`** (`RemoteConversationRepository.kt:230-236`), a
verbatim sibling of [`liveSessionEvents`](#livesessionevents--the-v2-structured-stream-decode-seam-385),
surfacing the two v2 **binary → phone** modal lifecycle envelopes — `modal_shown`, `modal_dismissed` —
decoded into the typed [`ModalEvent`](modal-events.md) `{ Shown, Dismissed }` family. The decode boundary
itself (DTOs + mappers + verbatim-string rationale) is documented in [Modal events](modal-events.md); this
section records only how it attaches to the repository.

- **Rides the same single existing `pump.inbound` collector** — **no** third subscription. A new arm joins
  the `onInbound` `when (envelope.type)` demux (`:368-381`), beside the [#395 `stall` arm](stall-state.md),
  gated **identically** on `CAPABILITY_INTERACTIVE in negotiatedCapabilities()` (the **reused** #385
  supplier — no new capability, no coordinator/DI change), then calls the private
  `decodeModalEvent(envelope): ModalEvent?` helper (`:479`) and `tryEmit`s the result. Two `TYPE_*`
  constants join the companion (`:1146`+).
- **Decode-and-emit only — two deliberate non-folds.** Unlike the `TYPE_TURN_STATE …` arm this arm does
  **not** fold a thread row (modals are not rows and carry **no `conversation_id`**) and does **not** clear
  a [stall](stall-state.md) — a `modal_shown` means `claude` is *waiting* for input, **not** turn
  forward-progress (the inverse of every `LiveSessionEvent`, which clears a stall). It is the cleanest of
  the interactive arms: one decode, one `tryEmit`, no side effects on `threadByConversation` /
  `stalledConversations`.
- **A separate flow + family, not a sixth `LiveSessionEvent`.** Forced by the wire: modal payloads carry
  no `conversation_id` (`modalId` is the sole key), whereas every `LiveSessionEvent` subtype mandates
  `conversationId` and the structured arm routes on it. Same `SharedFlow` shape (`replay = 0`,
  `extraBufferCapacity = 64`, `DROP_OLDEST` → infallible non-blocking `tryEmit`); **concrete repo only**,
  not the [`ConversationRepository`](conversation-repository.md) interface (the `liveSessionEvents` / #359
  `registerPushToken` posture; the #439 render consumer's facade/coordinator reachability is downstream).
- **`decodeModalEvent`** copies the `decodeStall` / `decodeLiveSessionEvent` `try { when(type) … } catch
  (IllegalArgumentException) { null }` drop idiom — a malformed payload yields `null`, the one envelope is
  dropped, the lone collector survives. Both `toEvent()` mappers are **total** (`class`/`source`/`outcome`
  carried verbatim, no enum drop) — see [Modal events § Verbatim strings](modal-events.md#verbatim-strings-no-enum-coercion-ac-3).

`security-sensitive` (high-consequence — the answer #438 injects a decision into `claude`), but the
repository stays plain orchestration: decode runs behind the authenticated Noise channel, and **nothing in
the new arm or the drop branch logs the payload** (`title`/`prompt`/option-`label` are operator content —
pyrycode#701 "never log modal body text"). See [Modal events § Trust boundary](modal-events.md#trust-boundary--no-payload-logging).

## `answerModal` / `cancelModal` — the v2 modal answer/cancel control-send (#438)

The **outbound (phone → binary) half** of the permission modal feature: the two control messages that
answer or cancel a modal the [`modalEvents`](#modalevents--the-v2-permissionchoice-modal-decode-seam-437)
seam (#437) surfaced. [#438](../codebase/438.md) implements them as **two concrete suspend methods** that
mirror [`registerPushToken`](remote-conversation-repository-workspace-and-push.md#registerpushtokentoken--the-device-concern-push-registration-359) (#359)
exactly — a pure request/reply over the **reused** `sendAndAwaitReply` (#346) primitive, with no new
correlation infra, no projection mutation, and no interface/facade/Fake touch:

```kotlin
suspend fun answerModal(modalId: String, optionId: String)   // modal_answer{modal_id, option_id, answer_token}
suspend fun cancelModal(modalId: String)                     // modal_cancel{modal_id}
```

Each builds an `Envelope(id = requestId.incrementAndGet(), type = TYPE_MODAL_ANSWER/_CANCEL, ts =
Clock.System.now()…, payload = MobileJson.encodeToJsonElement(dto))` and `sendAndAwaitReply`s it,
**discarding the empty `{}` ack** — success is simply "the call returned without throwing". The
[`ModalAnswerPayloadDto` / `ModalCancelPayloadDto`](mobile-protocol-v2-wire-layer-application-payloads.md#outbound-request-encoders--the-ackerror-correlated-reply-models-346)
encode DTOs (new `data/network/ModalOutboundPayloads.kt`) are the **encode mirror** of #437's decode DTOs.

- **`modalId`/`optionId` are echoed verbatim — never parsed or validated.** They are the opaque tokens
  the caller (#439, via the #437 decode) hands in; the daemon validates `modalId` against its own
  outstanding modal (first-answer-wins; a stale id is rejected) and maps `optionId` against its own
  recorded option list (pyrycode#701/#703/#706). The phone asserts only *which* modal and *which* offered
  option — never a conversation. A client-side check here would be security theatre that could diverge
  from the daemon.
- **`answer_token` — a deterministic, stateless idempotency key (the one real design decision).** Minted
  by a **pure** `private fun answerToken(modalId, optionId) = "${modalId.length}:$modalId:$optionId"` — no
  stored state, no random, no clock. Purity gives both AC#2 properties **by construction**: the same
  `(modalId, optionId)` always yields the same token, so a **resend of one logical answer carries the
  identical token** and the daemon collapses the replay to a no-op (stability with no remembered cache,
  no eviction lifecycle); **distinct answers always yield distinct tokens** (both ids participate — two
  different modals each answered with option id `"allow"` are distinct answers). The modal id is
  **length-prefixed** so opaque ids containing the `:` separator can't alias (`("a:b","c")` → `3:a:b:c`
  vs `("a","b:c")` → `1:a:b:c`). It is **not** authorization — that is `modalId` validity (#706) + the
  per-device answer gate (#702, default OFF); pyrycode#701 makes *secrecy an explicit non-goal*, so the
  predictable derivation is **safe** (a guessed token grants nothing) and *stronger* than a remembered
  UUID for the stability AC. (Rejected: random UUID + a cache keyed by `(modalId, optionId)` — buys
  nothing the pure derivation lacks while adding mutable shared state, thread-safety, and an eviction
  question coupling the slice to the inbound `modal_dismissed` stream.)
- **`modal_cancel` carries no token.** Cancel is not idempotency-keyed (pyrycode#701 shape `{modal_id}`);
  a re-cancel of an already-resolved modal is a stale-`modal_id` reject the daemon handles.
- **Failure surfaces deterministically (AC#4), nothing swallowed.** A not-`Open` session
  (`pump.send` → `false`) throws `IllegalStateException` synchronously (no hang); a server `error` —
  **including the ungranted-device reject** (pyrycode#702/#703) — propagates as
  [`RelayErrorException`](mobile-protocol-v2-wire-layer.md)`(code, retryable, message)`. This slice does
  **not** catch or interpret the reject: switching the modal to read-only on it is **#440's** concern.
  No new error type, no new mapping — inherited from `sendAndAwaitReply`/`mapError` verbatim.
- **`modal_dismissed` is not awaited here.** The `ack` confirms the daemon *received and will process*
  the answer; the modal's eventual *resolution* arrives asynchronously as the inbound `modal_dismissed`
  event on [`modalEvents`](#modalevents--the-v2-permissionchoice-modal-decode-seam-437) (#437). Two
  distinct signals — this slice owns only send + ack/error correlation.
- **Concrete-only, like `registerPushToken`/`liveSessionEvents`/`modalEvents`.** Modal answering is a
  device/control capability, not conversation CRUD, so it is **not** on the
  [`ConversationRepository`](conversation-repository.md) interface, the
  [facade](stable-conversation-repository.md), or the Fake (which would force a +2-file plumbing cascade
  for a method they never call). The #439 render consumer reaches these by fetching the concrete
  `RemoteConversationRepository` (the `registerPushToken` reachability story) — downstream consumer-slice
  work, not this slice's. Two `TYPE_MODAL_ANSWER`/`TYPE_MODAL_CANCEL` companion constants join the block.

`security-sensitive` (a high-consequence outbound control — the answer injects a permission decision into
`claude`), verdict **PASS** (architect self-review + code review): the design keeps the daemon the sole
authority (the payload carries only opaque ids + a non-authoritative dedup key), mints nothing
trust-bearing, and **never logs the payload** (the modal may name a sensitive command/path — e.g. "Allow
`rm -rf build/`"), mirroring `registerPushToken`'s never-log-the-token posture. See the
[#438 implementation notes](../codebase/438.md) for the line refs, the test matrix, and the *untested
length-prefix injectivity* lesson.

## `recordReplayCursor(envelope)` — the replay-cursor side-write (#412)

The **first line** of `onInbound` (`:218`), *before* the `when` demux, folds each interactive frame's
durable [`Envelope.eventId`](mobile-protocol-v2-wire-layer.md) into the reconnect-spanning
[`ReplayCursor`](replay-cursor.md) ([#412](../codebase/412.md)) — the high-water mark
[#413](https://github.com/pyrycode/pyrycode-mobile/issues/413) advertises as `last_event_id` on mid-turn
reconnect:

```kotlin
private fun recordReplayCursor(envelope: Envelope) {
    if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
        envelope.eventId?.let { replayCursor.record(it) }
    }
}
```

- **Rides the single existing inbound consumer** — no second `pump.inbound` subscription (the AC #2
  constraint, same as `liveSessionEvents`). The `replayCursor` is a fifth **defaulted** ctor param
  (`= ReplayCursor()`, `:117`); the [coordinator](relay-repository-coordinator.md) threads its shared,
  process-scoped instance in so the mark **survives connection churn** (this repo is rebuilt each
  reconnect — it cannot own the cursor).
- **Envelope-level, type-agnostic, pure side-write.** It reads `envelope.eventId` directly, independent
  of whether the per-type *payload* decodes — so a malformed-payload frame with a valid `event_id` still
  advances the cursor. It has **no feedback into delivery**: no `when` arm, no `liveSessionEvents`
  emission, and no delivery reads the cursor, so the recording alone cannot mute/drop a live event.
- **Gated on `interactive`** (defence-in-depth, symmetric with the structured-stream + `stall` arms);
  a non-interactive frame carries no `event_id` so nothing records (AC #3). **Throw-free** by
  construction (already-decoded `Long?` + set-membership + a pure max-fold), so it cannot kill the single
  inbound collector — see [Replay cursor](replay-cursor.md) for the holder, the fail-closed
  positive/strict-greater fold, and the trust boundary.

## The resync arm — reset the cursor + surface the gap (#417)

The **reaction** to the daemon's `resync` marker — `type = "resync"`, binary → phone, inline
`{conversation_id}`, **no** `event_id` (the daemon's signal that the position the phone advertised as
`hello.last_event_id` aged out of its bounded ring, so gap-free in-ring replay is impossible).
[#417](../codebase/417.md) adds a `TYPE_RESYNC` arm to `onInbound`'s `when (envelope.type)` demux
(`:340`), gated **identically** to the `recordReplayCursor` / `stall` / structured-event arms:

```kotlin
TYPE_RESYNC -> {
    if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
        replayCursor.reset()                              // unconditional on the type match
        resyncConversationId(envelope)?.let { id ->
            mutableLiveSessionEvents.tryEmit(LiveSessionEvent.ReplayGap(id))
        }
    }
}
```

- **Two effects, split by trust posture.** The **reset** ([`ReplayCursor.reset()`](replay-cursor.md),
  #412) is **unconditional** on the type match — the cursor is process-global (not per-conversation), so
  a malformed/absent `conversation_id` still clears it; the next reconnect then advertises a **fresh**
  position (omits `last_event_id`, #416) instead of mis-resuming. The **gap surface** — a
  [`LiveSessionEvent.ReplayGap`](live-session-events.md) on the **existing**
  [`liveSessionEvents`](#livesessionevents--the-v2-structured-stream-decode-seam-385) flow — is
  **conditional** on a decodable `conversation_id` (it needs an id to route). The principle: the safety
  action (avoid mis-resuming) must not depend on untrusted payload shape; only the routing-dependent part
  may.
- **No DTO, no decode — a throw-free structural read.** `resyncConversationId(envelope): String?`
  (`:434`) reads `conversation_id` directly off `envelope.payload` cast to `JsonObject` as a
  `JsonPrimitive` string (no `decodeFromJsonElement`, mirroring the server's payload-less inline-struct
  precedent), returning `null` when the payload is not a `JsonObject`, the field is absent, or it is not
  a JSON string. Because it is pure structural access it **cannot throw** — no `try/catch` needed
  (unlike `decodeStall` / `decodeLiveSessionEvent`), and it cannot kill the single inbound collector.
- **No record-then-reset conflict.** `recordReplayCursor` runs first (the first line of `onInbound`) but
  a `resync` carries **no** `event_id`, so it records nothing for this envelope — no ordering hazard with
  the `reset()` that follows.
- **Fail-closed `interactive` gate.** A non-interactive phone never advertised a cursor (the cursor only
  advances under the same gate, so `latest` is always `null` there), so a spurious `resync` from a
  buggy/hostile daemon is ignored — no reset (a no-op anyway), no surface.
- **`backfill_since` full reload is deferred** — no daemon-side message-history store / handler exists
  yet. This arm's contract ends at reset-the-cursor + surface-the-gap.

`security-sensitive`, but the repository stays plain orchestration: the `conversation_id` is read
throw-free and used **only** to tag the `ReplayGap` for routing (never a path, never an authz decision),
and **nothing on the arm logs** the envelope or its payload. Blast radius of a spurious/forged `resync`
is self-inflicted and bounded (a false gap-surface + a fresh re-advertise) — see
[#417 § Security](../codebase/417.md#security) and [Replay cursor § Reacting](replay-cursor.md#reacting--the-resync-marker-417).
