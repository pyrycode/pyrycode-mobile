# 417 — Handle the `resync` marker: reset the replay cursor + surface the gap

**Ticket:** pyrycode-mobile#417 · **Size:** S · **Security-sensitive:** yes (untrusted inbound control frame)
**Split from:** #413 · **Depends on:** #416 (advertise `last_event_id`) — merged on `main`

## Design source

N/A — pure data-layer slice. The gap is surfaced as an **observable signal a UI layer can later
render** (a `LiveSessionEvent`); no UI is drawn in this slice, so there is no Figma node. The visual
gap affordance lands in a later consumer ticket.

## Context

#416 (just merged) advertises the phone's recorded replay cursor as `hello.last_event_id` on every
reconnect. The daemon normally replays the missed in-ring tail (pyrycode#646) from that position. When
the advertised position has **aged out of the daemon's bounded ring**, gap-free in-ring replay is
impossible, and the daemon instead emits a **`resync` marker** — `type = "resync"`, binary → phone,
v2-only control, carrying an inline `{conversation_id}` and **no** `event_id` (wire SSOT: pyrycode
`docs/protocol-mobile.md` § "Interactive events (v2, capability-gated)" → the `resync` row).

This slice reacts to that marker by doing exactly two things, both on the existing single inbound path:

1. **Reset the replay cursor** (the `ReplayCursor` #412 owns, #416 reads) back to its no-cursor state,
   so the next reconnect's `hello`-build advertises a *fresh* position (omits `last_event_id`) rather
   than re-advertising the aged-out one and mis-resuming.
2. **Surface that a gap occurred** as a new event on the existing `liveSessionEvents` stream — an
   observable signal a future UI layer can render.

**Out of scope — deferred:** issuing a `backfill_since` full reload on resync. The daemon-side
full-reload handler the marker points at is **not built** (no daemon-side message-history store yet;
`internal/conversations` is metadata-only, `backfill_since` has no handler). The in-ring mid-turn
replay (#416) is the gap-free path; the full-reload fallback lands in a later ticket once the daemon
handler exists. On resync this slice resets the cursor and surfaces the gap — nothing more.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:217-344` —
  `onInbound`'s `when (envelope.type)` dispatch. **The new `resync` arm goes here.** Note `:218`
  (`recordReplayCursor` runs first, before the `when`) and the `else -> Unit` catch-all at `:342`.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:326-362` — the
  `stall` arm (`:326`) and `recordReplayCursor` (`:358`): **copy this exact `interactive`-gate +
  drop-silently idiom.** Both read `CAPABILITY_INTERACTIVE in negotiatedCapabilities()`; the resync
  arm uses the identical gate.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:390-404` —
  `decodeStall`: the precedent for reading a `{conversation_id}` inline. **Differs here:** the
  Technical Notes forbid a payload DTO for `resync`, so read the field directly off the `JsonObject`
  (see Design), not via a typed `decodeFromJsonElement<…Dto>`.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:972-1068` — the
  companion `TYPE_*` constants. **Add `TYPE_RESYNC = "resync"`** beside `TYPE_STALL` (`:1040`).
- `app/src/main/java/de/pyryco/mobile/data/network/ReplayCursor.kt:22-39` — the record-only cursor.
  **Add `reset()`.** `latest`/`record` and the lock-free `MutableStateFlow` backing are the posture
  the new method must stay consistent with.
- `app/src/main/java/de/pyryco/mobile/data/model/LiveSessionEvent.kt:1-78` — the sealed
  `LiveSessionEvent` family + its top-level KDoc (`:1-25`, "one of the five binary → phone … envelopes"
  and the verbatim/sensitive-text note). **Add the `ReplayGap` subtype** and extend the KDoc to
  acknowledge one control-derived signal beyond the five render events.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:273-283` and
  `:484-504` — the **two** exhaustive `when (event: LiveSessionEvent)` blocks (`thinkingTransition`
  and `reduceLive`). Adding a 6th subtype breaks both; each must gain an `is LiveSessionEvent.ReplayGap`
  case in its existing ignore-group (Design § ThreadViewModel). This is the only consumer fan-out.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt:46-54` — `Envelope`. Confirm:
  `type` is a free `String` and `payload` is a `JsonElement`, so a `resync` envelope deserializes via
  the generic `Envelope` with **no wire-model change**.
- `docs/specs/architecture/416-advertise-replay-cursor-last-event-id.md` — the advertise half. Its
  AC#2 wire test proves `last_event_id` is **omitted when the cursor is null** (`MobileJson` has
  `explicitNulls = false`). That is *why* `reset()` → `latest == null` makes the next `hello` omit it;
  the repository test asserts `latest == null` as the proxy for "next hello omits".
- `docs/specs/architecture/412-replay-cursor-event-id.md` — the cursor's design (process-scoped,
  reconnect-spanning, single-writer on the inbound collector, read at the next hello-build).
- `app/src/test/java/de/pyryco/mobile/data/network/ReplayCursorTest.kt` (whole file, 57 lines) — the
  unit-test idiom for the cursor; mirror it for the `reset()` tests.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt:1716-1799`
  (the `stall_*` tests) and `:2255-2307` (the `replayCursor_*` tests) — the exact fake-pump +
  `runCurrent()` harness, capability-gate fixtures, and real-`ReplayCursor` injection to extend.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt:2715-2770`
  — the `stallEnvelope` / `collectLiveEvents` / `collectStall` / `turnStateEnvelope` helpers. **Add a
  `resyncEnvelope(conversationId)` mirroring `stallEnvelope`**; reuse the others. `TS` is at `:2886`.
- `docs/lessons.md` (if present) — Phase-4 inbound-decode pitfalls.

## Design

Three production touches in `data/` plus one mechanical consumer fix in `ui/`. No new files, no
interface change, no wire-model change, no DI change.

### 1. `ReplayCursor.kt` — add a `reset()` affordance

Add a single method beside `record`:

- `fun reset()` — sets the backing mark back to `null` (`mark.value = null`), the no-cursor state. A
  direct atomic `MutableStateFlow` set, consistent with the existing lock-free backing.
- KDoc: clears the high-water mark so the *next* `hello`-build reads `null` and omits `last_event_id`
  (#416) — a fresh resume. Called only from the single inbound collector (the `resync` arm), the same
  writer as `record`, so reset/record never race within a connection; `latest`, read at the next
  connection's hello-build, sees the cleared value via the `StateFlow` happens-before.

Behaviour asserted by `ReplayCursorTest` (see Testing strategy), not by a code block here.

### 2. `LiveSessionEvent.kt` — add the `ReplayGap` subtype

Add a sixth subtype to the sealed interface:

- `data class ReplayGap(override val conversationId: String) : LiveSessionEvent` — carries **only**
  the conversation the gap concerns (for routing); no turn/event/text content.
- KDoc on the subtype: surfaced when the daemon emits a `resync` marker (#417) because the phone's
  advertised replay position aged out of the bounded ring, so gap-free in-ring replay was impossible.
  An observable signal a UI layer can later render (e.g. a "messages may be missing" affordance);
  this slice does not render it.
- Extend the file's top-level KDoc (`:1-25`) minimally: the family now also carries one
  control-derived signal (`ReplayGap`, from the `resync` marker) alongside the five binary→phone
  render envelopes — it is not itself a render event and carries no verbatim user/tool text.

`ReplayGap` is a `data class` with a single `String` field → **stable** for Compose; consumers that
later render it stay skippable.

### 3. `RemoteConversationRepository.kt` — the `resync` arm

Add `const val TYPE_RESYNC = "resync"` to the companion (beside `TYPE_STALL`, with a KDoc noting:
capability-gated control marker `{conversation_id}`, no `event_id`; daemon's signal that the advertised
`last_event_id` aged out of the ring — #417, pyrycode#646/#647).

Add a `when` arm to `onInbound`, gated identically to the `stall` / structured / `recordReplayCursor`
arms:

```
TYPE_RESYNC -> {
    if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
        replayCursor.reset()                              // unconditional: cursor is process-global
        resyncConversationId(envelope)?.let { id ->       // surface only if the id decodes
            mutableLiveSessionEvents.tryEmit(LiveSessionEvent.ReplayGap(id))
        }
    }
}
```

Key decisions, each with rationale:

- **Gate on `interactive`** — fail-closed, defence in depth, exactly like the `stall`, structured-event,
  and `recordReplayCursor` arms. A `resync` is the daemon's response to an advertised `last_event_id`,
  which the phone only advertises on an `interactive` connection (the cursor only advances under the
  same gate, so on a non-interactive connection `latest` is always `null`). A buggy/hostile daemon
  sending `resync` to a non-interactive phone is ignored — no reset (a no-op anyway), no surface.
- **Reset is unconditional on the type match.** The marker's *type* is the signal; the cursor is
  process-global and does **not** depend on which conversation the payload names (per Technical Notes
  — "reset it regardless"). So a malformed/absent `conversation_id` still resets the cursor.
- **Surface is conditional on a decodable `conversation_id`.** `ReplayGap` requires a `conversationId`
  to route; if the inline field is missing/non-string the gap surface is dropped (mirrors the
  drop-on-malformed idiom of every other `onInbound` arm), while the reset still happens. This split
  is deliberate: the safety action (reset → avoid mis-resuming) must not depend on payload shape.
- **No double-apply / ordering hazard with `recordReplayCursor`.** It runs first (`:218`) but a
  `resync` carries no `event_id`, so it records nothing for this envelope — no record-then-reset
  conflict.
- **Drop silently.** Like every sibling arm, the resync arm logs nothing (the `conversation_id` rides
  the same no-secrets-in-logs posture; see Security review).

Add a small throw-free helper (no DTO, per Technical Notes — mirrors the server's payload-less
inline-struct precedent):

- `private fun resyncConversationId(envelope: Envelope): String?` — reads `conversation_id` as a JSON
  string directly off `envelope.payload` cast to `JsonObject`; returns `null` if the payload is not a
  `JsonObject`, the field is absent, or it is not a JSON string. Pure structural access (no
  `decodeFromJsonElement`), so there is no decode-exception path to catch — it cannot throw and cannot
  kill the single inbound collector. New imports: `JsonObject` is already imported; add `JsonPrimitive`
  (and `jsonPrimitive` / `contentOrNull` or a `as? JsonPrimitive` + `isString` + `content` read).

### 4. `ThreadViewModel.kt` — keep the build green (compile-only, no behaviour)

`ThreadViewModel` collects `liveSessionEvents` and has two **exhaustive** `when (event)` over
`LiveSessionEvent` with no `else`. Adding the 6th subtype breaks both. The fix is mechanical: add
`is LiveSessionEvent.ReplayGap` to the existing ignore-group in each, because **this slice does not
render the gap** (rendering is a future UI ticket):

- `thinkingTransition` (`:275-282`) — add `ReplayGap` to the `AssistantDelta`/`ToolUse`/`ToolResult`
  group that maps to `null` (a gap is not a thinking transition).
- `reduceLive` (`:489-503`) — add `ReplayGap` to the `TurnState`/`ToolUse`/`ToolResult` group that maps
  to `this` (a gap does not change the thread fold in this slice).

Do **not** add an `else` branch — keeping the `when`s exhaustive preserves the Kotlin safety that
forces the future rendering consumer to consciously handle `ReplayGap`.

### Data flow

```
daemon  ──{type:"resync", payload:{conversation_id}, no event_id}──▶  pump.inbound (authenticated, post-Noise)
            │
RemoteConversationRepository.onInbound  (single inbound collector)
  ├─ recordReplayCursor(envelope)            → no-op (resync has no event_id)
  └─ when(type) == "resync" && interactive:
       ├─ replayCursor.reset()               → ReplayCursor.latest = null
       │      └─▶ next reconnect: NoiseIkSession.buildHello() reads null → omits last_event_id  (#416)
       └─ resyncConversationId? → mutableLiveSessionEvents.tryEmit(ReplayGap(id))
              └─▶ liveSessionEvents (SharedFlow) → future UI consumer (this slice: ThreadViewModel ignores)
```

## State + concurrency model

- **No new state, no new coroutines, no new scope.** The arm runs on the existing single inbound
  collector launched in `init` (`:212`).
- **`replayCursor.reset()`** is a single `MutableStateFlow.value = null` set on that one collector
  coroutine — the same single writer as `record()` (both reached only via `onInbound`), so reset and
  record never interleave within a connection. The cross-connection read (`latest` at the next
  `hello`-build, a different coroutine) is `StateFlow`-safe (happens-before, no torn read) — the
  property #412 was designed for.
- **`mutableLiveSessionEvents.tryEmit(...)`** is the existing non-blocking, `DROP_OLDEST`, bounded-buffer
  emit (`:201-207`): infallible, never back-pressures the inbound collector, never grows memory under a
  resync flood.
- **Dispatcher:** unchanged (the collector's existing context). No blocking work added.

## Error handling

| Failure mode | Result type | How surfaced |
|---|---|---|
| `resync` on a non-interactive connection (buggy/hostile daemon) | ignored | No reset (no-op), no `ReplayGap`. Fail-closed via the `interactive` gate. |
| `resync` with missing/non-string `conversation_id` | partial | Cursor **is** reset (unconditional); gap surface dropped (`resyncConversationId` → null). Collector survives (helper cannot throw). |
| `resync` with a valid `conversation_id` | success | Cursor reset + `ReplayGap(id)` emitted on `liveSessionEvents`. |
| Spurious / repeated `resync` (flood) | bounded | Reset is idempotent (null→null after the first); `tryEmit` drops under buffer pressure. No memory growth, no collector stall. |

No exceptions propagate out of the arm — the helper is structural (no decode), `reset()`/`tryEmit`
cannot throw. The single inbound collector is never at risk.

## Testing strategy

All unit (`./gradlew testDebugUnitTest`), pure-JVM, fake transport. Describe scenarios; write
assertions in the existing idiom of each file. Reuse the `stall_*` harness and the cursor fixtures —
do not introduce new fixtures beyond a `resyncEnvelope` builder mirroring `stallEnvelope`.

**`ReplayCursorTest.kt` — the `reset()` contract (covers AC#1's cursor-state half):**
- `reset` after `record(7)` → `latest == null`.
- `reset` on a fresh cursor → `latest == null` (idempotent no-op).
- `record(5)` → `reset()` → `record(8)` → `latest == 8` (post-resync the cursor re-advances on new
  frames — the next reconnect would advertise the *new* position).

**`RemoteConversationRepositoryTest.kt` — the `resync` arm (extends the `stall_*` / `replayCursor_*`
harness; inject a real `ReplayCursor` like `replayCursor_advancesOnInteractiveFrames…` at `:2260`):**
- **AC#1 (reset → next hello omits):** pre-advance the injected cursor via an interactive frame
  (`turnStateEnvelope("c1","thinking", eventId = 10)`; assert `cursor.latest == 10`), push
  `resyncEnvelope("c1")`, assert `cursor.latest == null`. Comment that null → omitted `last_event_id`
  is the #416 wire-tested consequence (`MobileWireCodecTest` proves omit-on-null), so this assertion
  is the proxy for "a subsequent `hello`-build omits `last_event_id`".
- **AC#2 (surface the gap):** `collectLiveEvents(repo)`, `runCurrent()`, push `resyncEnvelope("c1")`,
  assert the collected events contain `LiveSessionEvent.ReplayGap("c1")`.
- **Fail-closed gate:** with `negotiatedCapabilities = { emptySet() }`, push `resyncEnvelope("c1")` →
  no `ReplayGap` surfaced (and the injected cursor, never advanced under the closed gate, stays
  `null`). Mirror `stall_capabilityGateClosed_blocksOnset`.
- **Malformed `conversation_id` still resets, no surface:** pre-advance the cursor to 10, push a
  `resync` envelope with payload `{}` (and one with `{"conversation_id":123}`) → `cursor.latest ==
  null` (reset happened) **and** no `ReplayGap` surfaced; a later valid event still processes (the
  collector survived). Mirror `stall_malformed_droppedCollectorSurvives`.
- **Re-advance after resync (robustness):** push `resyncEnvelope("c1")` (cursor→null), then an
  interactive frame with `eventId = 30` → `cursor.latest == 30`.

**`ThreadViewModelTest.kt` — optional regression (not gating any AC):** pushing a `ReplayGap` through
`liveSessionEvents` leaves `isThinking` and the thread items unchanged (the compile-fix is inert). One
small test; skip if it duplicates existing coverage.

## Open questions

- None blocking. The `backfill_since` full reload the marker ultimately points at is intentionally
  out of scope (no daemon handler exists) and is owned by a later ticket once the daemon-side
  message-history store lands. This slice's contract ends at "reset the cursor + surface the gap".

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No MUST FIX. One new boundary: the `TYPE_RESYNC` arm in
  `RemoteConversationRepository.onInbound`. The `resync` frame arrives **post-`Noise_IK`** (authenticated
  as coming from the paired daemon) but is still treated as untrusted — a buggy/compromised daemon could
  emit a spurious `resync`. The inline `conversation_id` is read throw-free (`resyncConversationId`,
  structural `JsonObject` access, no `decodeFromJsonElement`) and used **only** to tag the surfaced
  `ReplayGap` for routing — never to index a filesystem path, never for an authorization decision, never
  trusted beyond "which conversation to tag." The cursor reset does **not** read the `conversation_id`
  (it is process-global, per the Technical Notes). **Blast radius of a spurious/forged `resync`:** a
  false gap-surface + a cursor reset → the next reconnect re-advertises a *fresh* position (omits
  `last_event_id`) and re-replays. Self-inflicted and bounded — no cross-party compromise, no data
  corruption. Crucially, the reset can only *lose* the in-ring tail-replay optimization (fall back to a
  fresh resume); it cannot suppress live frames (the historical caught-up-watermark suppression was the
  server-side concern, already clamped — re-verified in #416's spec § Server precondition on pyrycode
  `main`). Downstream `ReplayGap` consumers hold a typed event carrying only a conversation id they
  already know.
- **[Tokens / secrets]** N/A. `resync` carries no credential; `conversation_id` is not a secret (the
  phone already holds it). `ReplayGap` carries only that id. The cursor is a non-secret event ordinal.
  No token/secret touched, generated, stored, or compared on this path.
- **[File / storage]** N/A. `ReplayCursor` is in-RAM only (#412 — never persisted; a fresh process reads
  `null`). `reset()` is a `MutableStateFlow.value = null`. No path concatenation, no file read/write, no
  at-rest data, no backup surface.
- **[IPC / Android attack surface]** N/A. Pure `data/network` + `data/model` + a `ui/` compile-fix. No
  `Activity`/`Service`/`BroadcastReceiver`, no `<intent-filter>`/deep link, no `PendingIntent`, no
  `ContentProvider`, no `WebView`. The frame enters only through the already-established authenticated
  pump.
- **[Cryptographic primitives]** N/A. No new crypto, RNG, key handling, or comparison. The arm sits
  entirely behind the established `Noise_IK` channel; it adds no primitive and touches no key material.
- **[Network & I/O]** No findings. `resync` is **inbound-only** — this slice sends no new frame and adds
  no new frame type, size cap, timeout, or TLS setting. A `resync` flood is bounded: `reset()` is
  idempotent (null→null after the first) and `tryEmit` is the existing non-blocking `DROP_OLDEST`
  bounded-buffer emit (`:201-207`), so a flooding daemon can neither grow memory here nor back-pressure
  the single inbound collector (identical bound to the structured-event and `stall` arms).
- **[Error messages / logs]** No findings. The arm logs nothing — it follows the uniform
  drop-silently posture of every other `onInbound` arm; the `conversation_id` is never written to a log
  line, consistent with the no-secrets-in-logs posture (#291/#273). Developer must add **no** `Log`/
  `Timber` line printing the envelope or its payload.
- **[Concurrency]** No MUST FIX. `replayCursor.reset()` is a single non-suspending `MutableStateFlow`
  set on the **one** inbound collector coroutine — the same single writer as `record()` (both reached
  only via `onInbound`), so reset and record never interleave within a connection (no TOCTOU, no
  check-then-mutate). The cross-connection read (`latest` at the next `hello`-build) is `StateFlow`-safe
  (happens-before, no torn read). `recordReplayCursor` runs before the `when` but a `resync` carries no
  `event_id` → it records nothing, so there is no record-then-reset ordering hazard for the same
  envelope. `tryEmit` is lock-free. No new coroutine, scope, or mutex. Benign edge: a late `record` for
  a *new* post-resync frame re-advances the cursor — correct, since those are events the phone now
  genuinely holds and would legitimately advertise on the next reconnect.
- **[Threat-model alignment]** Reacts to the daemon's `resync` per the wire SSOT (`protocol-mobile.md`
  § "Interactive events (v2, capability-gated)" → the `resync` row). The fail-closed `interactive` gate
  (consistent with the `recordReplayCursor`, `stall`, and structured-event arms) ensures a non-interactive
  phone never resets or surfaces on a spurious `resync`. No mobile-specific surface (screenshot leak,
  deep link, overlay, accessibility eavesdrop) applies to an inbound control ordinal. **OUT OF SCOPE
  (named):** full reload via `backfill_since` — deferred until the daemon-side message-history store +
  handler exist (a later ticket).

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-06-17
