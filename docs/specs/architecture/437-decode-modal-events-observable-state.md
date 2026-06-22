# Spec — #437: decode `modal_shown` + `modal_dismissed` into observable modal state

Decode the two v2 **binary → phone** modal envelopes (`modal_shown`, `modal_dismissed`) into one typed
in-process event family, surfaced as a hot flow on the concrete `RemoteConversationRepository`.
**Decode + surface seam only** — this slice does **not** send (the answer/cancel path is sibling #438),
does **not** render (the overlay is #439), and does **not** hold "which modal is current" as state
(that projection is the consumer's, #439). It produces observable events the UI can collect.

Inbound-decode slice of the permission-modal feature (split from #428 → **this** / #438 answer-send /
#439 render UI / #440 read-only mode). Greenfield — no mobile-side modal types exist yet. Follows the
established v2 decode-slice pattern (#385 `liveSessionEvents`, #395 `stall`, #316/#317/#318/#374).

Wire SSOT: pyrycode#701 (merged) — `internal/protocol/messaging.go` modal structs + `docs/protocol-mobile.md`
§ Modal (v2). The producer (modal control loop pyrycode#703) is merged; modals are surfaced and
resolutions emitted live. Field shapes are inlined in Design § 1 below — no need to fetch them.

---

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/network/InteractivePayloads.kt` (whole file, 111 lines) —
  **the closest precedent and the file you modify.** The five existing `@Serializable internal data class …PayloadDto`
  (snake_case `@SerialName`, required non-null fields) + their `toEvent()` mappers + the `String.toPhase()`
  private mapper. The two new modal DTOs + `ModalOptionDto` + two mappers land here, in this exact style.
  Note the file's header comment: it already documents "multiple top-level types ⇒ ktlint filename rule does
  not apply" and "DTOs stay `internal`."
- `app/src/main/java/de/pyryco/mobile/data/model/LiveSessionEvent.kt` (whole file, 95 lines) — the portable
  **sealed-family + nested-type** idiom `ModalEvent` mirrors: a sealed interface with a common field
  (`conversationId` there; `modalId` here), `data class` subtypes named for the wire types, verbatim string
  carriage, "no Android imports (CLAUDE.md portability)" header. **Key contrast:** every `LiveSessionEvent`
  subtype carries `conversationId` — modal payloads carry **none** (Design § 4 explains why modal events get
  their own family + own flow, not a sixth `LiveSessionEvent`).
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:185-208` —
  the `mutableLiveSessionEvents` / `liveSessionEvents` `MutableSharedFlow` declaration (`replay = 0`,
  `extraBufferCapacity = 64`, `BufferOverflow.DROP_OLDEST`, `asSharedFlow()`). The new `modalEvents` flow is a
  sibling field, copied verbatim with the modal type. Read the doc comment — it explains the non-blocking
  `tryEmit` invariant and the "on the concrete repo, not the interface" rationale (#359 precedent) you reuse.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:294-339` — the
  `TYPE_TURN_STATE, …` structured-event arm and the `TYPE_STALL` arm in `onInbound`'s `when (envelope.type)`.
  **The `TYPE_STALL` arm (327-339) is the exact template for the new modal arm**: capability-gated, decode-or-drop,
  emit, no row-folding. The modal arm sits beside it (before `else -> Unit`).
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:384-424` —
  `decodeLiveSessionEvent` and `decodeStall`: the `try { when(type) … } catch (IllegalArgumentException) { null }`
  drop idiom (`SerializationException ⊂ IllegalArgumentException`). The new `decodeModalEvent` copies this shape.
  Note both doc comments end with "**nothing here logs the payload**" — preserve that (Security § Logs).
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:1057-1083` — the
  companion `TYPE_*` constants (`TYPE_TURN_STATE = "turn_state"` … `TYPE_RESYNC = "resync"`). Two new constants
  (`TYPE_MODAL_SHOWN`, `TYPE_MODAL_DISMISSED`) join here in the same style.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt:29-34` — `MobileJson`
  (`ignoreUnknownKeys = true`). This is **why AC#3's "trailing/unknown fields tolerated" is automatic** — all
  decode goes through `MobileJson`; no per-DTO `@JsonIgnoreUnknownKeys` needed.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt` — the `FakeSessionPump`
  harness (`Channel`-backed `inbound`, `push()`), the `messageEnvelope` / `turnStateEnvelope`-style raw-JSON
  envelope builders, and the `negotiatedCapabilities = { setOf("interactive") }` construction + `runCurrent()`
  collect-into-list pattern. New tests extend this harness (add `modalShownEnvelope` / `modalDismissedEnvelope`
  builders). **Memory: use `runCurrent()`, not `advanceUntilIdle()`** for the push→demux→emit cascade
  (`remote-repo-test-runcurrent-not-advanceuntilidle`).
- Wire SSOT (read-only context, shapes inlined below): pyrycode `docs/protocol-mobile.md § Modal (v2)` and
  `internal/protocol/messaging.go` (`ModalShownPayload`, `ModalDismissedPayload`, `ModalOption`); spec
  `docs/specs/architecture/701-modal-wire-types.md` for the field semantics and the security contract.

---

## Context

Phase 3 of epic pyrycode#597 (ADR 025) brings interactive permission/choice modals to the phone. The
supervised `claude` surfaces a modal; the daemon describes it to the phone over the encrypted v2 wire; the
phone renders it, answers, and the daemon drives the answer back into `claude`. The lifecycle is
`modal_shown` → `modal_answer`/`modal_cancel` → `modal_dismissed`.

This ticket lands the **inbound (data-in) half**: the decode boundary turning the two untrusted
`Envelope.payload` JSON events into one typed event family, surfaced to in-process consumers. Everything
else — sending the answer/cancel (#438), rendering the overlay and the destructive second-confirm (#439),
the read-only-when-ungranted mode (#440), and folding events into a "current modal" UI state — is downstream
and out of scope.

`modal_id` is the **sole correlation key**: the payloads carry no `conversation_id`. The phone treats
`modal_id` as an opaque token to echo back (in #438), never as a routing key it asserts; the daemon validates
it against its own outstanding-modal state (pyrycode#701/#703/#706). This slice carries `modal_id` verbatim and
makes no routing decision on it.

---

## Design

### Surface summary

| Item | Location | New/Mod | Visibility |
|------|----------|---------|------------|
| `ModalEvent` sealed family (`Shown`/`Dismissed`) + `ModalOption` | `data/model/ModalEvent.kt` | new | public |
| `ModalShownPayloadDto` + `ModalDismissedPayloadDto` + `ModalOptionDto` + 2 `toEvent()` mappers | `data/network/InteractivePayloads.kt` | mod | `internal` |
| `modalEvents` flow + 1 demux arm + `decodeModalEvent` helper + 2 `TYPE_*` constants | `data/repository/RemoteConversationRepository.kt` | mod | mixed |

**Three production files (1 new, 2 modified), two new public types.** Both modified files are **append-only** —
a new flow field, a new `when` arm, a new private helper, two constants, three DTOs, two mappers. **No existing
signature changes ⇒ zero edit fan-out** (the new `modalEvents` flow and the new types have no existing
consumers; consumers are #438/#439). **No `RelayRepositoryCoordinator` change** — the modal arm reuses the
existing `negotiatedCapabilities` supplier already wired by #385 (Design § 5).

### 1. Payload DTOs — `data/network/InteractivePayloads.kt` (mod)

Three new `@Serializable internal data class` DTOs, every field a required non-null `String`/`List` (the server
emits all fields always — no `omitempty`, per pyrycode#701 § Design 2), snake_case wire names carried by
`@SerialName`. Decode through `MobileJson` only. Field contract (wire SSOT, pyrycode#701 `messaging.go`):

```
ModalOptionDto         { id: String; label: String }
ModalShownPayloadDto   { modal_id: String; class: String; title: String; prompt: String;
                         options: List<ModalOptionDto>; default_option_id: String }
ModalDismissedPayloadDto { modal_id: String; outcome: String; source: String }
```

Notes pinned by the SSOT:

- **No `conversation_id`** on either payload — `modal_id` is the sole correlation key.
- `options` is an **ordered** JSON array; array order **is** the canonical display/selection order
  (pyrycode#701). `kotlinx.serialization` preserves `List` order, so the DTO inherits this for free — the
  mapper must keep it (`options.map { … }` preserves order; AC#5 asserts it).
- `class` is a Kotlin keyword → the DTO property is named `modalClass` with `@SerialName("class")`.
- `default_option_id` carries a documented invariant: it MUST equal one of `options[].id`. **This slice does not
  enforce the invariant** — it carries the value verbatim (the daemon, the producer, owns the invariant; a decode
  seam asserting it would couple decode to producer correctness and could drop a forward-compat modal). The test
  asserts the *carried* value matches the fixture, not that decode rejects a violating modal.
- `class`, `source`, `outcome` are **plain `String`s, carried verbatim** — **not** mapped to a Kotlin enum. This is
  the deliberate AC#3 choice (see § 3); it matches the SSOT, which models all three as "plain string over a closed
  wire set, not a named enum."

### 2. Event family — `data/model/ModalEvent.kt` (new, portable)

One sealed interface with a common `modalId`, two `data class` subtypes named for the wire types, plus a
top-level `ModalOption`. Pure data, no Android imports (CLAUDE.md portability — `data/` is a Compose
Multiplatform walk-back surface). Contract sketch:

```kotlin
sealed interface ModalEvent {
    val modalId: String

    data class Shown(
        override val modalId: String,
        val modalClass: String,            // wire `class`, carried verbatim (AC#3)
        val title: String,
        val prompt: String,
        val options: List<ModalOption>,    // array order = canonical display order (AC#1/#5)
        val defaultOptionId: String,       // ∈ options[].id by producer invariant; carried, not enforced
    ) : ModalEvent

    data class Dismissed(
        override val modalId: String,
        val outcome: String,               // selected option id, or producer sentinel; verbatim (AC#2/#3)
        val source: String,                // {remote, local, timeout} closed set; verbatim (AC#2/#3)
    ) : ModalEvent
}

data class ModalOption(val id: String, val label: String)
```

**ktlint filename rule:** `ModalEvent.kt` carries **two** public top-level types (`ModalEvent`, `ModalOption`),
so the single-public-class filename rule does **not** fire (cf. `InteractivePayloads.kt` /`MobileWireModels.kt`;
memory `ktlint-filename-rule-single-class`). `ModalOption` is top-level (not nested under `Shown`) for consumer
ergonomics — #439 references it directly when rendering the option list.

### 3. Mappers — `data/network/InteractivePayloads.kt` (with the DTOs)

One `internal fun XxxDto.toEvent(): ModalEvent` per modal DTO (same `data/network` → `data/model` direction as
the existing `MessagePayloadDto.toMessage()` / `TurnStatePayloadDto.toEvent()`), both **total field copies** (no
nullable return):

- `ModalShownPayloadDto.toEvent()` → `ModalEvent.Shown(modalId, modalClass, title, prompt, options.map { ModalOption(it.id, it.label) }, defaultOptionId)`.
- `ModalDismissedPayloadDto.toEvent()` → `ModalEvent.Dismissed(modalId, outcome, source)`.

**Why no enum for `class`/`source`/`outcome` (AC#3, the load-bearing modal decision).** The existing
`turn_state` mapper maps `state` → a `Phase` enum and **drops** unrecognized values (returns `null`). The modal
ACs require the **opposite**: AC#3 says an unknown/forward-compat `class`/`source`/`outcome` "is preserved
verbatim rather than coerced to an enum that drops it." So these three stay plain `String` carried verbatim, and
both mappers are total (never `null`). AC#2's "distinguishing `remote`, `local`, `timeout`" is satisfied by the
distinct string values — the consumer (#439) matches `source == "timeout"` etc.; a typed projection, if ever
wanted, is a consumer concern (Open questions). This matches the SSOT (#701 models all three as plain strings)
and the #385 `stopReason` precedent (closed wire set kept as `String`, "consumers map it"). The only decode
*failure* path is therefore a malformed envelope (missing/wrong-typed required field → `SerializationException`),
handled in § 4 — there is no "unrecognized value" drop for modals.

### 4. Demux + surface — `RemoteConversationRepository.kt` (mod)

**New hot flow**, a verbatim sibling of `mutableLiveSessionEvents`:

```kotlin
private val mutableModalEvents =
    MutableSharedFlow<ModalEvent>(replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
val modalEvents: SharedFlow<ModalEvent> = mutableModalEvents.asSharedFlow()
```

`replay = 0` (events, not held state — holding "which modal is current" is the #439 consumer projection, mirroring
#385's "holding latest is a consumer concern"); bounded `extraBufferCapacity = 64` + `DROP_OLDEST` make `tryEmit`
**infallible and non-blocking**, so a slow modal consumer can never back-pressure the single shared `pump.inbound`
collector and stall the connection's `conversations`/`message`/`ack` processing. Modals are inherently low-rate
(one outstanding at a time, user-driven), so the bound is never realistically hit. Exposed on the **concrete**
repository only — **not** on the `ConversationRepository` interface (AC#4) — the exact `liveSessionEvents` / #359
`registerPushToken` posture: adding it to the interface would force `FakeConversationRepository` +
`StableConversationRepository` to plumb a flow this decode slice does not use (+2 files → trips the ≥5 gate).
Facade/coordinator reachability for the #439 consumer is downstream consumer-slice work.

**New demux arm** in `onInbound`'s `when (envelope.type)`, beside `TYPE_STALL`, before `else -> Unit`:

```kotlin
TYPE_MODAL_SHOWN, TYPE_MODAL_DISMISSED -> {
    if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {
        decodeModalEvent(envelope)?.let { mutableModalEvents.tryEmit(it) }
    }
}
```

This arm **only decodes-and-emits** — unlike the `TYPE_TURN_STATE …` arm it does **not** fold into
`messagesByConversation` (modals are not thread rows and carry no `conversation_id`) and does **not** clear
`stalledConversations` (a `modal_shown` means `claude` is *waiting* for input — it is not turn forward-progress, so
it must not clear a stall). Drop silently — **no logging**: `title`/`prompt`/option-`label` are operator content
(pyrycode#701 § Security "never log modal body text").

**Capability gate — the one judgment call.** The arm rides the existing `CAPABILITY_INTERACTIVE in
negotiatedCapabilities()` gate, identical to the `TYPE_STALL`/`TYPE_RESYNC`/`TYPE_TURN_STATE` siblings. This is
**reusing the gate that already exists, not adding a new capability** — the ticket's instruction is "decode
consistent with how the current structured events are handled; do not add new capability-gating beyond what
already exists." Every current structured arm gates; consistency ⇒ the modal arm gates the same way. ADR 025's
amendment retires capability negotiation ("every phone gets the full structured stream"), and #427 sweeps **all**
gates uniformly — keeping the modal arm on the same gate means #427's sweep removes it in one consistent pass
rather than leaving the modal arm as an odd ungated branch beside gated siblings. (If the operator prefers the
modal arm ungated ahead of #427, it is a one-line deletion of the `if` — flagged as Open question.)

**New private helper** `decodeModalEvent(envelope): ModalEvent?`, copying the `decodeStall` /
`decodeLiveSessionEvent` shape:

```kotlin
private fun decodeModalEvent(envelope: Envelope): ModalEvent? =
    try {
        when (envelope.type) {
            TYPE_MODAL_SHOWN -> MobileJson.decodeFromJsonElement<ModalShownPayloadDto>(envelope.payload).toEvent()
            TYPE_MODAL_DISMISSED -> MobileJson.decodeFromJsonElement<ModalDismissedPayloadDto>(envelope.payload).toEvent()
            else -> null
        }
    } catch (e: IllegalArgumentException) { null }
```

`SerializationException ⊂ IllegalArgumentException`, so a malformed payload (missing/wrong-typed field) yields
`null` → the one envelope is dropped, the lone inbound collector survives, the next envelope is processed normally.
**Nothing here logs the payload.**

**Two new `TYPE_*` constants** in the companion: `TYPE_MODAL_SHOWN = "modal_shown"`,
`TYPE_MODAL_DISMISSED = "modal_dismissed"` (the `modal_answer`/`modal_cancel` outbound constants belong to #438).

### 5. Why a separate `modalEvents` flow, not a sixth `LiveSessionEvent`

Modal events get their **own** `ModalEvent` family on their **own** `modalEvents` flow, deliberately **not**
added to `LiveSessionEvent`/`liveSessionEvents`:

- **Structural blocker.** `LiveSessionEvent` mandates `val conversationId: String` (every subtype overrides it; the
  structured arm routes on it — `stalledConversations.update { it - event.conversationId }`). Modal payloads carry
  **no `conversation_id`**. Forcing modal events into `LiveSessionEvent` would mean making `conversationId`
  nullable across all five existing subtypes + every consumer `when` + the stall-clearing fold — a wide,
  semantically-wrong cascade. A separate family is the minimal, honest shape.
- **Different handling.** Modal events don't fold thread rows and don't touch stalls; they only emit. Mixing them
  into the row-folding `TYPE_TURN_STATE …` arm would require carve-outs. A separate arm + flow keeps each concern
  clean and lets the #439 consumer collect `modalEvents` directly without filtering modal items out of the broader
  session stream.

### Data-flow diagram

```
pump.inbound ─┐  (single consumer, RemoteConversationRepository.init — unchanged)
              ▼
        onInbound(envelope)
              │  when(type)
   ┌──────────┴──────────────────────────────────────┐
   │ modal_shown / modal_dismissed                    │
   │   ├─ CAPABILITY_INTERACTIVE in negotiatedCaps()? │  no → ignore
   │   │      yes ▼                                    │
   │   ├─ decodeModalEvent(envelope)                  │  malformed → null → drop (stream survives)
   │   │      ModalEvent ▼                            │
   │   └─ mutableModalEvents.tryEmit(it)              │  non-blocking, DROP_OLDEST
   └──────────────────────────────────────────────────┘
              │
   modalEvents: SharedFlow<ModalEvent>  ──►  (consumer #439: folds Shown/Dismissed into current-modal UI state)
```

---

## State + concurrency model

- **One consumer, unchanged.** No new collector — the modal arm hangs off the existing single `pump.inbound`
  collector in `init`. No second subscription.
- **`SharedFlow`, not `StateFlow`.** Modal lifecycle is surfaced as a *stream* of `Shown`/`Dismissed` events;
  "which modal is current" is the consumer's projection (#439 folds it into a `StateFlow<…>`). `replay = 0` +
  bounded `extraBufferCapacity = 64` + `DROP_OLDEST`. Hot, fans out to N collectors off the one inbound consumer.
- **Non-blocking emission.** `tryEmit` from the non-suspending `onInbound`; with `DROP_OLDEST` it always succeeds
  (never rejects, never suspends) — the shared inbound collector is never stalled.
- **Lifecycle.** The flow lives on the connection-scoped repository (#351); it dies with the connection (rebuilt
  per connection by the coordinator). No process-global scope. Late subscribers get no history (`replay = 0`) — by
  design; the late-subscriber-while-a-modal-is-open case is the #439 consumer's lifecycle concern (Open questions).
- **Gate read.** `negotiatedCapabilities()` reads `pump.state.value` (a lock-free `StateFlow.value` volatile
  snapshot) per modal envelope; negligible cost at modal frequency.

## Error handling

| Failure | Layer | Result |
|---------|-------|--------|
| Malformed / partially-decodable modal payload (missing/wrong-typed required field) | `MobileJson.decodeFromJsonElement` → `SerializationException` (⊂ `IllegalArgumentException`) | caught in `decodeModalEvent` → `null` → envelope dropped, stream survives, next envelope processed |
| Unknown/forward-compat `class` / `source` / `outcome` value | — (carried verbatim as `String`, never mapped to an enum) | **not** a failure — surfaced verbatim (AC#3) |
| Trailing/unknown JSON fields | `MobileJson` `ignoreUnknownKeys = true` | tolerated — decode succeeds (AC#3) |
| Modal envelope without `interactive` negotiated | gate `if` | ignored, never decoded, never surfaced (consistent with siblings; #427 sweeps the gate) |
| Buffer overflow (extreme, unrealistic for modals) | `SharedFlow` `DROP_OLDEST` | oldest undelivered event dropped; collector never stalled |

No failure tears down the connection or the inbound collector — the seam fails closed and silent. **No `catch`
logs the payload** (modal body text is operator content).

## Testing strategy

Unit only (`./gradlew testDebugUnitTest`), JUnit4 + `runTest`, extending the existing `FakeSessionPump` harness in
`RemoteConversationRepositoryTest.kt`. Construct repo with `negotiatedCapabilities = { setOf("interactive") }`,
collect `modalEvents` into a list on `backgroundScope`, `pump.push(envelope)`, **`runCurrent()`** (not
`advanceUntilIdle()` — memory `remote-repo-test-runcurrent-not-advanceuntilidle`), assert. Add two raw-JSON
envelope-builder helpers (`modalShownEnvelope`, `modalDismissedEnvelope`) mirroring the existing
`turnStateEnvelope`/`messageEnvelope` helpers (payload via `MobileJson.parseToJsonElement`).

Scenarios (each bullet → one test; write bodies in the project idiom, not pre-written here):

- **`modal_shown` → `ModalEvent.Shown` (AC#1).** Push a `modal_shown` with `class`, `title`, `prompt`, **≥2 options**
  (e.g. `[{allow,Allow},{deny,Deny}]`), `default_option_id:"deny"`. Assert `modalId`, `modalClass`, `title`,
  `prompt`; assert `options` has size 2 with `options[0].id == "allow"`, `options[1].id == "deny"` (**order
  preserved**); assert `defaultOptionId == "deny"` (the carried invariant value).
- **`modal_dismissed` source `remote` (AC#2).** Push `source:"remote"`, `outcome` = an option id; assert
  `ModalEvent.Dismissed(modalId, outcome, "remote")`.
- **`modal_dismissed` source `local` (AC#2).** Same shape, `source:"local"`; assert `source == "local"`.
- **`modal_dismissed` source `timeout` (AC#2).** `source:"timeout"`; assert `source == "timeout"` (+ a sentinel
  `outcome`, e.g. `"cancelled"`, asserted verbatim).
- **Unknown `class` preserved verbatim (AC#3).** `modal_shown` with `class:"some_future_class"`; assert
  `Shown.modalClass == "some_future_class"` and the event **surfaces** (no drop).
- **Unknown `source`/`outcome` preserved verbatim (AC#3).** `modal_dismissed` with `source:"some_future_source"`,
  `outcome:"some_future_outcome"`; assert both carried verbatim and the event surfaces.
- **Trailing/unknown fields tolerated (AC#3).** `modal_shown` with an extra unknown key (e.g. `"deadline_ms":5000`);
  assert it decodes and surfaces unchanged (proves `ignoreUnknownKeys`).
- **Malformed envelope dropped, next survives.** Push a `modal_shown` missing a required field (e.g. no `title`),
  then a valid `modal_dismissed`; assert only the dismissal surfaces (the malformed one produced no emission, the
  stream was not torn down).
- **Capability gate blocks.** `negotiatedCapabilities = { emptySet() }`; push a well-formed `modal_shown`; assert
  **no** emission after `runCurrent()`. (Counterpart of the happy-path tests, which use `{ setOf("interactive") }`.)
- **Fan-out / interleave (optional, strengthens AC#1+#5).** Push `modal_shown` then `modal_dismissed` for the same
  `modal_id`; assert the surfaced list is the two typed events in push order; optionally a second collector receives
  the same stream (hot fan-out).

No instrumented tests (no Android, no UI in this slice). No coordinator test change — the modal arm reuses the
already-wired `negotiatedCapabilities` supplier; its correctness is covered structurally by the gate tests using the
same supplier shape.

## Open questions

- **`modalEvents` `replay`/held-state.** `replay = 0` mirrors #385 and keeps the seam decode-only; "which modal is
  currently open" is the #439 consumer's `StateFlow` projection. If #439 finds the late-subscriber-while-a-modal-is-open
  case awkward (e.g. a re-created ViewModel collecting after `modal_shown`), the cleanest fix is consumer-side
  (`stateIn` with a started policy, or a connection-scoped eager collector) — not widening this seam. Flagged so #439
  makes a deliberate choice. If it turns out the data layer should hold the current modal, revisit then.
- **Typed `source`/`class`/`outcome` projection.** Kept as verbatim `String` per AC#3. If a consumer wants a
  compile-time-exhaustive `DismissSource { Remote, Local, Timeout, Other(raw) }`, add it as a consumer-side mapper
  that preserves the raw string in `Other` — out of scope here (evidence-based: no observed need yet).
- **`default_option_id` invariant not enforced.** Carried verbatim; the producer (pyrycode#703) owns "MUST equal one
  of `options[].id`." If a consumer wants a defensive fallback (e.g. default-to-first when the id is unknown), that is
  a #439 render-time policy, not a decode concern.
- **Capability gate vs. #427.** The arm gates on the existing `CAPABILITY_INTERACTIVE` for sibling consistency; #427
  removes all such gates. If #437 lands after #427's sweep, the gate `if` should be omitted to match the
  then-current siblings — a one-line difference the developer reconciles against `onInbound`'s state at implementation
  time (read the sibling arms; match them).

---

## Security review

**Reviewer:** architect (self-review; `agents/architect/security-review.md` is not synced into this worktree —
performing the pass inline using the standard adversarial categories, per the #701/#487/#209 precedent).
**Date:** 2026-06-22
**Verdict:** PASS

This ticket is `security-sensitive`: it adds a new untrusted-input parse point (server JSON → typed modal events)
on a **high-consequence feature** — a modal is a permission/trust prompt whose answer (#438) injects a decision
into `claude`. Walked every applicable category adversarially, assuming the spec has holes.

**Findings:**

- **[Trust boundaries].** No MUST FIX. The slice adds **one** explicit boundary: `decodeModalEvent(envelope)`,
  decoding the untrusted `Envelope.payload` through the single configured `MobileJson` into `internal` DTOs, then
  mapping to the portable `ModalEvent`. Untrusted JSON never reaches a consumer un-decoded — consumers hold only
  typed `ModalEvent`s. The boundary runs **behind** the already-authenticated, AEAD-encrypted Noise channel
  (#298/#571), so the bytes are integrity-protected and from the paired daemon; decode is still strict (fail-closed,
  drop-on-malformed) as defense-in-depth against a buggy/compromised daemon. **This slice surfaces `modal_id`
  verbatim and asserts nothing on it** — it is an opaque token the outbound slice (#438) echoes back, and the daemon
  (not the phone) validates it against its own outstanding-modal state (pyrycode#701 § Security, #703/#706). No
  phone-asserted routing on `modal_id`; the cross-conversation-`modal_id`-confusion class pyrycode#701 forecloses on
  the wire is preserved here (the phone never pairs `modal_id` with a `conversation_id`).
- **[Untrusted content carried verbatim — hand-off to #439].** No MUST FIX in this slice, but **named for the
  consumer.** `title`, `prompt`, and option `label` are operator/session content carried **verbatim** (the seam does
  not trim, parse, sanitize, or interpret them). The rendering consumer (#439) **MUST treat these as inert data, not
  markup/HTML/markdown-with-active-content**, and own output-encoding at render time — exactly the hand-off
  `LiveSessionEvent` documents for `assistant_delta.text`/tool summaries. `modalClass`/`source`/`outcome` are
  likewise verbatim strings; #439 must not `eval`/reflect on them, only compare. Documented as a hand-off, not a gap
  — this slice neither renders nor logs the values.
- **[Error messages, logs, telemetry].** No MUST FIX — and **load-bearing** for a permission-prompt path.
  `title`/`prompt`/`label` can name a sensitive command (e.g. "Allow `claude` to run: `rm -rf build/`"), a path, or
  project content. The design's drop paths (`catch (e) { null }`, the gate `if`) **log nothing** — no `Log.*`, no
  payload in any exception message, mirroring the existing `decodeStall`/`decodeLiveSessionEvent`/`TYPE_MESSAGE`
  no-log posture (pyrycode#701 § Security: "never log modal body text"). MUST be preserved in implementation: **no
  diagnostic logging of decoded modal fields or raw payloads**, including in the malformed-drop branch. Code-review
  should verify zero `Log`/`println`/`print` calls touch payload-derived data in the new code.
- **[Tokens, secrets, credentials].** N/A — this slice handles no tokens/keys/credentials. `modal_id` is a
  correlation nonce (non-secret on this inbound, decode-only side; its unguessability is the daemon's minting
  property, pyrycode#703), `answer_token` is not present (it is minted on the outbound #438 side). The `interactive`
  capability string is non-secret (advertised in clear `hello`); `CAPABILITY_INTERACTIVE` membership is a feature
  gate, not an authz secret. No token compare, no storage.
- **[File / storage operations].** N/A — purely in-memory; no filesystem, no path construction, no persistence.
  `modal_id`/`outcome`/`option.id` from the wire are carried as opaque strings and never used to build a path or
  filename at this seam.
- **[Inter-process / Android attack surface].** N/A — no Intents, deep links, exported components, PendingIntents,
  ContentProviders, or WebView. `data/model` + `data/network` + `data/repository` only; no Android surface added
  (`ModalEvent.kt` is portable, no `android.*`).
- **[Cryptographic primitives].** N/A — no RNG, no hashing, no key handling. Runs above the Noise transport
  (#298/#303 own it); this slice introduces no crypto.
- **[Network & I/O — DoS resistance].** No MUST FIX. No new socket, no timeout surface (inherits the existing
  transport/pump). The `modalEvents` `SharedFlow` buffer is **bounded** (`extraBufferCapacity = 64`, `DROP_OLDEST`),
  and `tryEmit` never blocks the shared inbound collector — a hostile/buggy daemon flooding modal envelopes cannot
  grow unbounded memory at the seam nor stall `conversations`/`message`/`ack` processing. `options` is an **inbound**
  unbounded list, but it is **daemon-minted outbound→phone** (the daemon describes its own modal), AEAD-authenticated
  from the paired daemon, decoded once into a bounded in-memory `List` and never used to size another allocation or
  index an array at this seam — no amplification vector. (pyrycode#701 flags bounding `options` at the **producer**;
  not a phone-side concern for a decode that materializes the list once.)
- **[Concurrency].** No MUST FIX. No new coroutine/scope — the modal arm rides the existing single connection-scoped
  `pump.inbound` collector, cancelled by the coordinator on connection drop (#351 key-wipe + scope-cancel). The hot
  `SharedFlow` is connection-scoped (rebuilt per connection), so **no cross-connection leak**: a new connection gets a
  fresh repo + fresh flow; a prior connection's modal events cannot reach a new connection's collectors. No
  shared-state check-then-mutate (append-only `tryEmit`, no `StateFlow` CAS added; the modal arm deliberately does
  **not** touch `stalledConversations`). The gate's `pump.state.value` read is a lock-free volatile snapshot.
- **[Threat model alignment].** The relevant threat — a malformed or hostile modal envelope from a (possibly
  compromised) paired daemon — is addressed by fail-closed strict decode (drop, never crash, never tear down the
  stream) + the capability gate (defense-in-depth: a daemon ignoring the negotiated set cannot push modals to a
  non-`interactive` phone) + verbatim `modal_id` with **no phone-side trust** (the answer-gate/validation lives in
  the daemon, pyrycode#702/#703/#706, and the outbound slice #438). The **high-consequence answer action is not in
  this slice** — surfacing a modal for *viewing* is the decode half; *answering* (#438) and the per-device
  remote-permission gate (#440/pyrycode#702) own the authorization boundary. UI-surface threats (screenshot leakage
  of a rendered prompt, accessibility eavesdropping, destructive-action mis-tap) belong to the rendering slice (#439)
  — out of scope here, named for the consumer. This slice surfaces typed events to an in-process flow and renders
  nothing.
