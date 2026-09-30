# Modal events — the v2 permission/choice-modal decode seam

The **decode boundary** that turns the two v2 **binary → phone** modal lifecycle envelopes —
`modal_shown` and `modal_dismissed` — into one typed, in-process Kotlin event family the thread UI can
collect without ever touching wire bytes. Landed in [#437](../codebase/437.md) (split from #428), the
**inbound (data-in) half** of the permission/choice modal feature: Phase 3 of epic pyrycode#597
(ADR 025 — server SSOT pyrycode#701, producer pyrycode#703, both merged).

This is **decode + surface only** — wire → typed events on a hot flow. Everything else is downstream and
out of scope:

- **sending** the answer / cancel (`modal_answer` / `modal_cancel`) — sibling **#438** (**landed**:
  [`answerModal` / `cancelModal`](remote-conversation-repository-live-stream-and-modals.md#answermodal--cancelmodal--the-v2-modal-answercancel-control-send-438),
  [#438 notes](../codebase/438.md));
- **rendering** the overlay + the fail-safe-deny default highlight — the render slice **#446**
  (#439 split into the projection **#445** + the render overlay #446); answering / cancelling is **#444**;
- the **read-only-when-ungranted** mode — **#440**;
- folding `Shown`/`Dismissed` into a **"which modal is currently open"** projection — **landed** in
  [`currentModal`](current-modal-state.md) ([#445](../codebase/445.md)), and **hoisted to the
  process-scoped coordinator** in [#492](../codebase/492.md) (so it accumulates before any thread screen
  subscribes); deliberately **not** held here (see
  [Why a `SharedFlow`, not held state](#why-a-sharedflow-not-held-state)).

It follows the established v2 decode-slice pattern of [Live-session events](live-session-events.md)
([#385](../codebase/385.md)) — same three layers, same single-collector demux arm, same fail-closed
drop — with two deliberate departures called out below: a **separate event family on its own flow**
(`modal_id`, not `conversation_id`, is the correlation key every subtype mandates), and **verbatim
strings, no enum coercion** (forward-compat values must survive).

## What the daemon sends

When the supervised `claude` surfaces a permission/choice modal, the daemon describes it to an
`interactive` phone over the encrypted v2 wire and emits a resolution when it closes. The lifecycle is
`modal_shown` → `modal_answer`/`modal_cancel` (#438) → `modal_dismissed`. The two **binary → phone**
envelopes this seam decodes:

| Wire `type` | Payload fields | Meaning |
|---|---|---|
| `modal_shown` | `modal_id`, `class`, `title`, `prompt`, `options` (ordered `[{id, label}]`), `default_option_id` (all required, no `omitempty`); `conversation_id` (daemon #1065, outbound-only scoping stamp — see below); `reason`, `reason_type`, `blocked_path`, `description` (daemon #2346, optional decision context, omitted at their zero values — see below) | a surfaced permission/choice modal |
| `modal_dismissed` | `modal_id`, `outcome`, `source` (all required, no `omitempty`) | its resolution |

Pinned by the wire SSOT (pyrycode#701 `internal/protocol/messaging.go` + `docs/protocol-mobile.md`
§ Modal (v2)):

- **`modal_id` is the sole correlation key for answers**, on both payloads — an opaque nonce the phone
  echoes back in #438, **never** a routing key it asserts. The daemon validates it against its own
  outstanding-modal state (pyrycode#701/#703/#706); this seam carries it verbatim and makes **no** routing
  decision on it. (This is the structural reason modal events are their own family, not a sixth
  `LiveSessionEvent` — see [below](#why-a-separate-family-not-a-sixth-livesessionevent).)
- **`modal_shown`'s `conversation_id` is outbound-only (#1065).** It names the conversation whose session
  raised the modal, so the phone can choose which thread *displays* the prompt (#816) — it is never sent
  back on `modal_answer` / `modal_cancel`, and the daemon never trusts a phone-asserted conversation. A
  reconnect re-send of `modal_shown` (the same `modal_id`) carries the same `conversation_id` as the
  original. `modal_dismissed` carries no conversation at all; a resolution's conversation is known only
  because [the fold](current-modal-state.md) remembers which conversation raised the modal it resolves.
- **`options` is an ordered array**, and **array order IS the canonical display/selection order**.
  `kotlinx.serialization` preserves `List` order, so the DTO inherits it; the mapper's `options.map { … }`
  keeps it (AC #1/#5 assert it).
- **`default_option_id` MUST equal one of `options[].id`** — a producer invariant. This seam carries it
  **verbatim and does not enforce it** (the daemon/producer owns the invariant; a decode asserting it
  would couple decode to producer correctness and could drop a forward-compat modal).
- **`class`, `source`, `outcome` are plain strings over closed sets** (`class` e.g. `permission`;
  `source` ∈ `remote` | `local` | `timeout`; `outcome` = the selected `options[].id` when answered, or a
  producer-defined sentinel on cancel/timeout) — **carried verbatim, never mapped to a Kotlin enum**
  (see [Verbatim strings, no enum](#verbatim-strings-no-enum-coercion-ac-3)).

## The three layers

```
Envelope.payload: JsonElement   (untrusted, post-Noise-decrypt)
        │  MobileJson.decodeFromJsonElement<…PayloadDto>
        ▼
…PayloadDto    (data/network/InteractivePayloads.kt — @Serializable, internal, strict non-null)
        │  .toEvent()
        ▼
ModalEvent { Shown, Dismissed } + ModalOption    (data/model/ModalEvent.kt — public, portable, no Android)
        │  modalEvents.tryEmit(it)
        ▼
val modalEvents: SharedFlow<ModalEvent>   (RemoteConversationRepository, concrete)
        ▼
consumer: #445 currentModal folds Shown/Dismissed → ModalUiState — #492 hoists the fold to the coordinator (then #446 renders / #440 read-only)
```

### 1. DTOs — `data/network/InteractivePayloads.kt` (`internal`)

Three new `@Serializable internal data class` DTOs join the five live-session DTOs in the same file:
`ModalOptionDto` (`{id, label}`), `ModalShownPayloadDto`, `ModalDismissedPayloadDto`. Every field a
**required non-null** `String`/`List` (the server emits all fields always — no `omitempty`), snake_case
wire names mapped via `@SerialName`. `class` is a Kotlin keyword, so the property is `modalClass` with
`@SerialName("class")`. Decode always through the single configured
[`MobileJson`](mobile-protocol-v2-wire-layer.md) (`ignoreUnknownKeys = true` → AC #3's
trailing/unknown-field tolerance is **automatic**, no per-DTO annotation). The strict non-null shape is
the **fail-closed** posture: a missing/wrong-typed field fails the structural decode with a
`SerializationException` rather than `null`-punning, so the one malformed envelope is dropped and the
stream survives. The file already carries multiple top-level types, so the
[[ktlint-filename-rule-single-class]] does not constrain its name.

#### `conversation_id`: the one defaulted field (#816)

`ModalShownPayloadDto` gains `@SerialName("conversation_id") val conversationId: String = ""` — the
**only** defaulted field on either modal DTO; every other field stays required and the fail-closed posture
above is otherwise unchanged. A frame that omits the key still decodes, with `conversationId == ""`, which
[the fold's `scopedTo`](current-modal-state.md) treats as "matches no thread" rather than "matches every
thread" (a missing/blank scoping stamp fails closed to invisible, never to all-threads). An explicit `null`
for the key still fails the decode, exactly as for every other field — the contract says the field is a
string, and the default only covers a key that is absent. `ModalDismissedPayloadDto` gains no field; the
wire dismiss carries no conversation (see the table above).

#### The four decision-context fields (#817)

`ModalShownPayloadDto` also gains `reason`, `reason_type`, `blocked_path`, `description` (daemon #2346),
copied by the daemon from claude's own `can_use_tool` permission ask. These are the one deliberate exception
to the strict fail-closed posture above: each is optional, display-only, and decoded as a `JsonElement`
rather than a `String?`, so a wrong-typed value never fails the structural decode and drops the whole
prompt — a missing permission ask would otherwise leave the user nothing to answer until the daemon's
deny-on-timeout.

- **`reason` is non-null**, defaulting to `JsonPrimitive("")`, not `JsonElement? = null` like its three
  siblings. A `@Serializable(with = …)` custom serializer on a **nullable** `JsonElement?` property does not
  see the JSON `null` token — kotlinx short-circuits it to Kotlin `null` (absent) before the serializer
  runs — so a literal `"reason": null` (which the daemon does send; `permbridge` carries
  `decision_reason` as a Go `json.RawMessage`, which keeps `null` through `omitempty`) would otherwise be
  indistinguishable from an absent key. The non-null default sidesteps the short-circuit entirely: an
  explicit `null` decodes as `JsonNull` (present, later stringified to `"null"`), an absent key takes the
  empty string, and the wire already treats empty and absent as equivalent.
- **`toModalContext()`** (in `InteractivePayloads.kt`) maps `reason` to its string content when it is a
  JSON string primitive, or to its compact JSON text (`JsonElement.toString()`) otherwise — the desktop's
  `JSON.stringify` posture, which keeps `false`, `0` and `null` visible as the meaningful display text they
  are rather than dropping them. The three sibling fields keep only a JSON string primitive's content; any
  other JSON type (object, array, number, boolean, explicit null) decodes as absent, since they have no
  display-text precedent to fall back on.
- **Empty means absent for every field**, mirroring `conversation_id` above: a value is folded to `null`
  after extraction, so consumers ([`ModalContext`](../features/permission-modal-overlay.md#the-decision-context-817),
  data/model/ModalEvent.kt) never see `""`.
- **Length-clamped, not otherwise sanitised.** The protocol states no bound for these fields, so each value
  is clamped after extraction — 128 chars for `reason_type` (a category token), 2048 for the other three
  (prose) — surrogate-safe (never cutting on a lone high surrogate). The clamp bounds decoded state and the
  overlay's layout independent of the daemon; the relay frame already bounds the envelope. Nothing is
  trimmed or parsed beyond the string/JSON-text extraction above.
- **`default_to_no`, the fifth field #2346 added, is not decoded here.** It is a client-selection hint
  toward the deny choice; the mobile prompt already highlights the producer's `default_option_id`, which is
  always the deny option, so carrying the hint would change nothing a user can see.

#### The always-allow offer field (#818)

`ModalShownPayloadDto` also gains `always_allow` (daemon #2364) — unlike the four fields above it is
**always present** on the wire, but decoded through the same tolerant `JsonElement?` posture: a missing or
malformed value is no offer, never a dropped prompt. A private `JsonElement?.toAlwaysAllowRules(): List<String>`
returns the offered rules, in wire order, only when the value is a `JsonObject` whose `offered` is the JSON
boolean `true` (the quoted string `"true"` is rejected) and whose `rules` is a `JsonArray` of 1 to 16
non-empty JSON strings each at most 1024 UTF-8 bytes — the daemon's own bounds, applied here and not
widened. Any violation rejects the **whole** list, matching the daemon's own no-truncation rule. `toEvent()`
copies the result into `ModalEvent.Shown.alwaysAllowRules`. The design, the accept/grant state and the
render live in [Modal answer flow § The session-grant draft](modal-answer-flow.md#the-session-grant-draft-818-moved-to-process-lifetime-in-1306)
and [Permission-modal overlay § The always-allow offer](permission-modal-overlay.md#the-always-allow-offer-818);
this subsection covers only the decode.

### 2. Event family — `data/model/ModalEvent.kt` (new, public, portable)

One `sealed interface ModalEvent` with a common `val modalId: String` and two `data class` subtypes named
**1:1** for the wire `type` strings, plus a top-level `ModalOption`:

```kotlin
sealed interface ModalEvent {
    val modalId: String                                       // the sole correlation key — opaque, never routed on

    data class Shown(
        override val modalId: String,
        val modalClass: String,            // wire `class`, verbatim (AC #3)
        val title: String,
        val prompt: String,
        val options: List<ModalOption>,    // array order = canonical display order (AC #1/#5)
        val defaultOptionId: String,       // ∈ options[].id by producer invariant; carried, NOT enforced
        val conversationId: String = "",   // #816: outbound-only display-scoping stamp; "" = unscoped
        val context: ModalContext = ModalContext.None,   // #817: claude's optional decision context, display-only
        val alwaysAllowRules: List<String> = emptyList(),  // #818: the session-grant offer; empty = none available
    ) : ModalEvent

    data class Dismissed(
        override val modalId: String,
        val outcome: String,               // selected option id, or producer sentinel; verbatim (AC #2/#3)
        val source: String,                // {remote, local, timeout}; verbatim (AC #2/#3)
    ) : ModalEvent
}

data class ModalOption(val id: String, val label: String)
```

`ModalOption` is **top-level (not nested under `Shown`)** for consumer ergonomics — the projection
([#445](current-modal-state.md)) reuses it verbatim in `ModalUiState.Open`, and the render slice (#446)
references it directly when laying out the option list. The file carries **two** public top-level
types (`ModalEvent`, `ModalOption`), so the [[ktlint-filename-rule-single-class]] does not fire (cf.
[`LiveSessionEvent.kt`](live-session-events.md)'s contrasting choice to *nest* `Phase` to keep one public
type). Pure data, **zero Android imports** — `data/` stays portable per CLAUDE.md (Compose Multiplatform
walk-back surface). The free-form strings (`title`, `prompt`, option `label`) and verbatim strings
(`modalClass`, `outcome`, `source`) are carried **verbatim** — the seam never trims, parses, or
sanitizes them (see [Trust boundary](#trust-boundary--no-payload-logging)).

### 3. Mappers — `…PayloadDto.toEvent()` (in `InteractivePayloads.kt`)

One `internal fun XxxDto.toEvent(): ModalEvent` per modal DTO, same `data/network → data/model` direction
as `MessagePayloadDto.toMessage()` / `TurnStatePayloadDto.toEvent()`. **Both are total field copies**
returning a non-null event — `ModalShownPayloadDto.toEvent()` maps `options.map { ModalOption(it.id,
it.label) }` (order preserved); `ModalDismissedPayloadDto.toEvent()` copies `modalId`/`outcome`/`source`.
There is no nullable mapper here — contrast `TurnStatePayloadDto.toEvent()`, which maps `state → Phase`
and returns `null` on an unknown value. That difference is the load-bearing AC #3 decision:

#### Verbatim strings, no enum coercion (AC #3)

The `turn_state` mapper drops an unrecognized `state` (returns `null`). The modal ACs require the
**opposite**: AC #3 says an unknown/forward-compat `class`/`source`/`outcome` "is preserved verbatim
rather than coerced to an enum that drops it." So `modalClass`/`outcome`/`source` stay plain `String`
carried verbatim, and **both mappers are total** (never `null`). AC #2's "distinguishing `remote`,
`local`, `timeout`" is satisfied by the **distinct string values** — the consumer (#445's projection
carries `source` verbatim into `ModalUiState.Dismissed`; #446 matches `source == "timeout"` etc.); a
compile-time-exhaustive `DismissSource { Remote, Local, Timeout, Other(raw) }`, if
ever wanted, is a **consumer-side** mapper that preserves the raw string in `Other` (out of scope here,
evidence-based — no observed need). This matches the SSOT (pyrycode#701 models all three as plain strings)
and the #385 `stopReason` precedent (closed wire set kept as `String`, "consumers map it"). **The only
decode-failure path is therefore a structurally malformed envelope** (missing/wrong-typed required field
→ `SerializationException`) — there is no "unrecognized value" drop for modals.

## How it surfaces — the flow, the gate, the drop

The seam rides the **single existing** `pump.inbound` collector in
[`RemoteConversationRepository`](remote-conversation-repository.md) — **no second subscription**. A new
hot flow, a verbatim sibling of `mutableLiveSessionEvents`:

```kotlin
private val mutableModalEvents =
    MutableSharedFlow<ModalEvent>(replay = 0, extraBufferCapacity = 64, onBufferOverflow = DROP_OLDEST)
val modalEvents: SharedFlow<ModalEvent> = mutableModalEvents.asSharedFlow()
```

A new demux arm joins `onInbound`'s `when (envelope.type)`, beside the `stall` arm, before `else`:

```kotlin
TYPE_MODAL_SHOWN, TYPE_MODAL_DISMISSED -> {
    if (CAPABILITY_INTERACTIVE in negotiatedCapabilities()) {            // same gate as the siblings
        decodeModalEvent(envelope)?.let { mutableModalEvents.tryEmit(it) }
    }
}
```

- **Decode-and-emit only.** Unlike the `TYPE_TURN_STATE …` arm, this arm does **not** fold a thread row
  (modals are not rows, and this seam never routes on `Shown.conversationId` — see #816 below) and does
  **not** clear a stall — a `modal_shown`
  means `claude` is *waiting* for input, **not** turn forward-progress, so it must not clear an active
  [stall](stall-state.md). Two clean separations from the live-session arm.
- **Capability gate (the one judgment call).** The arm rides the **existing** `CAPABILITY_INTERACTIVE in
  negotiatedCapabilities()` gate, identical to the `stall`/`resync`/structured-stream siblings — **reusing
  the gate that already exists, not adding a new capability** (the ticket: "decode consistent with how the
  current structured events are handled; do not add new capability-gating beyond what already exists").
  ADR 025's 2026-06-22 amendment retires capability negotiation ("every phone gets the full structured
  stream"), and #427 sweeps **all** gates uniformly; keeping the modal arm on the same
  gate means that sweep removes it in one consistent pass rather than leaving an odd ungated branch beside
  gated siblings.
- **The drop (AC #3).** `decodeModalEvent(envelope): ModalEvent?` selects the DTO by `envelope.type`,
  decodes through `MobileJson`, calls `toEvent()`, all wrapped in one `try/catch
  (IllegalArgumentException)` (`SerializationException ⊂ IllegalArgumentException`). A
  malformed/partially-decodable payload yields `null`, the one envelope is dropped, the lone collector
  survives, the next envelope is processed normally. **Nothing here logs the payload.**
- **Two `TYPE_*` constants** join the companion: `TYPE_MODAL_SHOWN = "modal_shown"`,
  `TYPE_MODAL_DISMISSED = "modal_dismissed"` (the outbound `modal_answer`/`modal_cancel` constants belong
  to #438).

### Why a `SharedFlow`, not held state

`replay = 0`: these are **events**, not current-value state. "Which modal is currently open" is a downstream
projection — **landed** in [`currentModal`](current-modal-state.md) ([#445](../codebase/445.md)), which
folds `Shown`/`Dismissed` into a `StateFlow<ModalUiState>`, mirroring #385's "holding latest is a consumer
concern." The bounded `extraBufferCapacity = 64` + `DROP_OLDEST` make `tryEmit` **infallible and
non-blocking** — the load-bearing invariant is that a slow modal consumer can never back-pressure the
shared `pump.inbound` collector and stall the connection's `conversations`/`message`/`ack` processing.
Modals are inherently low-rate (one outstanding at a time, user-driven), so the bound is never
realistically hit. Late subscribers get no history (`replay = 0`), so **where** the fold's eager collector
lives decides which events it catches:

- #445 folded at the **screen-scoped ViewModel** with `stateIn(SharingStarted.Eagerly)` — the `scan`
  accumulator runs once for the VM's life and never re-emits `Hidden` over a retained `Open` on a
  *within-VM* resubscription.
- But because the VM's collector only starts when a thread screen mounts, a `modal_shown` fired **before any
  thread screen existed** was still dropped. [#492](../codebase/492.md) resolved *that* by **hoisting the
  eager collector to the process-scoped coordinator** — the fold now runs from coordinator construction,
  before any screen, so a genuinely-pre-subscriber modal is accumulated (`.value` holds it the moment a
  thread screen opens). The seam itself was **not** widened; this data layer still holds no *upstream*
  current-modal state — the projection is folded on the process-lived coordinator, downstream of this
  `replay = 0` seam.

### Why on the concrete repo, not the interface (AC #4)

`modalEvents` is on `RemoteConversationRepository` only — **not** on the
[`ConversationRepository`](conversation-repository.md) interface. Adding it there would force
[`FakeConversationRepository`](conversation-repository.md) to implement it and
[`StableConversationRepository`](stable-conversation-repository.md) to delegate it (+2 prod files,
tripping the ≥5 split gate) for plumbing this decode slice doesn't use. This is the accepted
[`liveSessionEvents`](live-session-events.md) (#385) / [`registerPushToken`](remote-conversation-repository.md)
(#359) posture: a non-interface capability on the concrete repo, reached by a downstream consumer through
a concrete handle ([the coordinator](relay-repository-coordinator.md) holds the repo). Facade/coordinator
reachability for the consumer was **downstream consumer-slice work** — exactly as `liveSessionEvents`' UI
reachability was deferred to (and realized in) [#406](../codebase/406.md), and now **realized for modals
in [#445](../codebase/445.md)** via the coordinator's [`modalEvents`](relay-repository-coordinator-seams-and-passthroughs.md#modal-event-seam-445-and-the-hoisted-currentmodal-fold-492)
passthrough seam (the byte-for-byte mirror of the `liveSessionEvents` seam; the fold over it was hoisted
into the coordinator in [#492](../codebase/492.md)).

> **Contrast with [Stall state](stall-state.md) (#395), which went on the interface.** A `stall` is
> *current-value state the thread needs through the facade*, so `observeStall` is an interface method with
> a `flowOf(false)` default. A modal is an *event the data layer doesn't itself consume*, surfaced for a
> dedicated render consumer to fold — like `liveSessionEvents`, concrete-only. The axis is
> **state-needed-through-the-facade** vs **events-a-downstream-slice-collects-directly**.

## Why a separate family, not a sixth `LiveSessionEvent`

Modal events get their **own** `ModalEvent` family on their **own** `modalEvents` flow, deliberately
**not** added to [`LiveSessionEvent`](live-session-events.md)/`liveSessionEvents`. Two reasons:

- **Structural blocker — `conversation_id` is a different kind of field.** Every `LiveSessionEvent`
  subtype mandates a non-null `val conversationId: String` that the structured arm **routes on**
  (`stalledConversations.update { it - event.conversationId }`) — a required correlation key. `modal_id`,
  not `conversationId`, is modal events' correlation key, and `Shown.conversationId` (#816) is a
  **defaulted, display-only scoping stamp** the phone never routes decode on — `""` means "no thread",
  not "route to every thread," and `Dismissed` carries none at all. Forcing modal events into
  `LiveSessionEvent` would still mean making `conversationId` mean two different things across the family
  (a mandatory routing key on five subtypes, an optional display hint on one) — a semantically-wrong
  cascade. A separate family stays the minimal, honest shape.
- **Different handling.** Modal events don't fold thread rows and don't touch stalls; they only emit.
  Mixing them into the row-folding `TYPE_TURN_STATE …` arm would require carve-outs. A separate arm + flow
  keeps each concern clean and lets the #445 consumer collect `modalEvents` directly without filtering
  modal items out of the broader session stream.

This is the inverse of the [Live tool-call](live-tool-call.md) (#387) decision (a tool row *is* a thread
row → fold into the message list) and parallels [Stall state](stall-state.md) (#395, state *beside* the
thread → its own projection): the shape follows what the data actually is.

## Trust boundary & no-payload-logging

`security-sensitive` — this seam adds a new untrusted-input parse point (server JSON → typed modal
events) on a **high-consequence feature**: a modal is a permission/trust prompt whose answer (#438)
injects a decision into `claude`. Architect self-review verdict **PASS**; code review **PASS**, zero
findings.

- **Single trust boundary.** `decodeModalEvent()`: untrusted `Envelope.payload` JSON → strict `internal`
  DTOs → typed `ModalEvent`. Consumers only ever hold typed events, never un-decoded payload. The boundary
  runs **behind** the already-authenticated, AEAD-encrypted Noise channel
  ([#298](../codebase/298.md)/[#303](../codebase/303.md)) — bytes are integrity-protected and from the paired daemon
  — but decode is still strict (fail-closed, drop-on-malformed) as defense-in-depth against a
  buggy/compromised daemon.
- **`modal_id` carried verbatim, asserted on nowhere.** It is an opaque token the outbound slice (#438)
  echoes back; the daemon (not the phone) validates it against its own outstanding-modal state. The phone
  never pairs `modal_id` with a `conversation_id`, so the cross-conversation-`modal_id`-confusion class
  pyrycode#701 forecloses on the wire is preserved here.
- **No payload logging — load-bearing for a permission path.** `title`, `prompt`, and option `label` are
  operator/session content that can name a sensitive command (e.g. "Allow `claude` to run: `rm -rf
  build/`"), a path, or project content (pyrycode#701 § Security: "never log modal body text").
  **Nothing in this seam logs decoded fields or raw payloads**, including the malformed-drop branch (a
  bare `catch { null }`) — mirroring the existing `decodeStall`/`decodeLiveSessionEvent`/`TYPE_MESSAGE`
  no-log posture. Any future change here must preserve this.
- **Rendering consumer owns output-encoding — hand-off to #446.** The strings are carried verbatim and
  uninterpreted through both this decode seam **and** the [#445 projection](current-modal-state.md) (which
  also carries them verbatim, never interpreting); the render slice (**#446**) **MUST treat them as inert
  data, not markup/HTML/markdown-with-active-content**, and own output-encoding at render time — the same
  hand-off `LiveSessionEvent` documents for `assistant_delta.text`/tool summaries.
  `modalClass`/`source`/`outcome` are likewise verbatim — #446 must not `eval`/reflect on them, only
  compare. Neither this slice nor #445 renders anything.
- **The four decision-context fields (#817) inherit the same hand-off**, with the same lenient-type
  posture applied one layer earlier: `reason`/`reasonType`/`blockedPath`/`description` are claude-authored,
  untrusted, display-only text — never decision authority, never a path this seam or any consumer opens,
  never logged — carried through `ModalContext` to the render slice
  ([permission-modal-overlay.md § The decision context](permission-modal-overlay.md#the-decision-context-817)),
  which renders every value as inert `Text`, matching the `title`/`prompt`/option `label` posture above.
- **DoS posture.** The `SharedFlow` buffer is **bounded** (`DROP_OLDEST`, capacity 64) and `tryEmit`
  never blocks — a hostile/buggy daemon flooding modal envelopes can neither grow unbounded memory at the
  seam nor stall the shared inbound collector. `options` is an inbound unbounded `List`, but it is
  daemon-minted, AEAD-authenticated, materialized once into a bounded in-memory list and **never sizes
  another allocation or indexes an array** at this seam (pyrycode#701 bounds it at the producer).
- **No cross-connection leak.** The flow is connection-scoped (the repo is rebuilt per connection by the
  [coordinator](relay-repository-coordinator.md), #351); a new connection gets a fresh repo + fresh flow,
  so prior-connection modal events cannot reach a new connection's collectors. No new coroutine/scope —
  the arm rides the existing connection-scoped collector.

## Scope boundary

In scope: wire → typed events, the capability gate, fail-closed strict decode, surface on a concrete hot
flow. Out of scope (named for consumers): sending the answer/cancel (#438, landed), folding events into a
"current modal" UI state (**landed** in [#445](current-modal-state.md)'s `currentModal`), rendering the
overlay + the fail-safe-deny default highlight (the render slice **#446**), answering / cancelling from
the UI (**#444**), the read-only-when-ungranted mode (#440), enforcing the `default_option_id ∈
options[].id` invariant (producer-owned; a #446 default-to-first render fallback if wanted), and a typed
`source`/`class`/`outcome` projection (a consumer-side `Other(raw)`-preserving mapper if ever needed).

## Related

- [#437 implementation notes](../codebase/437.md) — files, line refs, lessons.
- [Permission-modal overlay](permission-modal-overlay.md#the-decision-context-817) (#817) — decodes and
  renders the four `ModalContext` fields this seam adds; `PermissionContext` is the render consumer.
- [Live-session events](live-session-events.md) ([#385](../codebase/385.md)) — the **sibling decode seam**
  this mirrors (three layers, single-collector demux arm, fail-closed drop) and contrasts with (verbatim
  strings vs nullable enum mapper; separate family vs the `conversationId`-bearing family).
- [Remote conversation repository](remote-conversation-repository.md) — hosts the `modalEvents` flow + the
  demux arm.
- [Stall state](stall-state.md) ([#395](../codebase/395.md)) — the on-the-interface counterpart (state vs
  events); the modal arm deliberately does **not** clear a stall.
- [Live tool-call](live-tool-call.md) ([#387](../codebase/387.md)) — the fold-into-the-thread inverse
  (a tool row *is* a thread row; a modal is not).
- [Relay repository coordinator](relay-repository-coordinator.md) — wires the `negotiatedCapabilities`
  supplier the gate reuses; the [`modalEvents` passthrough seam + the hoisted `currentModal`
  fold](relay-repository-coordinator-seams-and-passthroughs.md#modal-event-seam-445-and-the-hoisted-currentmodal-fold-492)
  live here (seam in [#445](../codebase/445.md); fold hoisted in [#492](../codebase/492.md), which also
  demoted `modalEvents` to `private`).
- [Current-modal state](current-modal-state.md) ([#445](../codebase/445.md) / [#492](../codebase/492.md) /
  #816) — the projection that folds this stream into `currentModal`, hoisted in #492 to the process-scoped
  coordinator; since #816 each `ThreadViewModel` filters that host-level fold down to its own conversation
  via `ModalUiState.scopedTo`, consuming `Shown.conversationId` from this seam.
- [Mobile Protocol v2 wire layer](mobile-protocol-v2-wire-layer.md) — `MobileJson`, `Envelope`,
  `@SerialName` Go-interop.
- Sibling slices: **#438** answer/cancel send (**landed** — [`answerModal` / `cancelModal`](remote-conversation-repository-live-stream-and-modals.md#answermodal--cancelmodal--the-v2-modal-answercancel-control-send-438),
  [notes](../codebase/438.md)) · **#445** current-modal projection (**landed** — [Current-modal state](current-modal-state.md))
  · **#446** render overlay (blocked by #445) · **#444** answer/cancel from the UI · **#440**
  read-only-when-ungranted. (#439 was split into the projection #445 + the render overlay #446.)
- Server SSOT: pyrycode#701 (modal wire types + `modal_id` nonce + `answer_token` idempotency), #703
  (modal control loop / producer), #706 (two-heads ownership), #702 (per-device answer gate); ADR 025
  § Phase 3 modals, EPIC pyrycode#597.
