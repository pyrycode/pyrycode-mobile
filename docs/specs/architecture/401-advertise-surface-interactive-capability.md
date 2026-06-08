# Spec #401 — advertise + surface the negotiated `interactive` capability on the v2 hello handshake

**Ticket:** pyrycode/pyrycode-mobile#401 · **Size:** S · **Labels:** `security-sensitive`
**Split from:** #369 (interactive-capability / reconnect-replay split).

## Context

ADR 025 (Phase 2 structured-streaming exit gate) defines an optional `capabilities: []string`
field on both legs of the Noise_IK handshake early-data: the phone advertises the features it
understands in `hello`; the daemon echoes the **intersection** with its own supported set in
`hello_ack`. A phone that does not advertise `interactive` — or whose `interactive` is not echoed
back — keeps receiving only the coarse v1 `message` fan-out and never sees the structured stream.

The server side is fully live (pyrycode#607 wire fields, #626 negotiation/intersect/echo,
#616/#628/#632 capability-gated fan-out). This ticket is the **mobile opt-in**: advertise
`interactive` in `hello` and make the negotiated set **readable** on the open-session signal the
data layer already observes (`PumpState.Open`). It is a **surfacing-only** slice — it gates
nothing. The decode gate (#385) and the stall gate (#395) consume the surfaced set later.

Wire SSOT: pyrycode `docs/protocol-mobile.md` § "Capability negotiation (v2)". Field key is
`capabilities` on both `hello` (phone → daemon) and `hello_ack` (daemon → phone, `omitempty`);
`CapabilityInteractive = "interactive"`.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt:54-73` — `HelloClientPayload`
  (add advertised `capabilities`; note the token-redacting `toString` you must preserve) and
  `HelloAckPayload` (add echoed `capabilities`). The two data classes this slice extends.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt:29-34` — `MobileJson`:
  `encodeDefaults = true` (a constructor-default list **is** emitted on the wire — the same
  mechanism that emits `protocol_versions`) and `ignoreUnknownKeys = true` (an absent/extra
  `capabilities` decodes cleanly). Load-bearing for AC#1 and AC#3.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseIkSession.kt:102-104` — the `connId`
  property pattern (throws until established); mirror it for the negotiated set.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseIkSession.kt:122-159` — `readResp`: the
  authenticated boundary (MAC verified **before** `parseHelloAck` runs) and where `connId` is
  surfaced today.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseIkSession.kt:286-322` — `buildHello`
  (constructs `HelloClientPayload`; gets the new default for free) and `parseHelloAck` (decodes
  `HelloAckPayload`; the single private decode point to extend).
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionPump.kt:155-165` — `drive()`:
  `readResp` → `PumpState.Open(connId)`. The one production construction of `Open`.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionPump.kt:287-301` — `PumpState`
  sealed interface and `Open`. The field you add here is the surfacing target.
- `app/src/main/java/de/pyryco/mobile/data/repository/SessionPump.kt:44-54` — `ManagedSessionPump.state:
  StateFlow<PumpState>`. This **is** the observable open-session signal; confirm **no interface
  change** is needed — the set rides on `Open`, which is already exposed through `state`.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt:205-218` —
  `toPyrycodeLinkStatus()` pattern-matches `is PumpState.Open` (does not construct it and discards
  `connId`). Adding a field to `Open` does **not** touch this `when`. Confirms zero fan-out here.
- `app/src/test/java/de/pyryco/mobile/data/network/MobileWireCodecTest.kt:118-129` —
  `defaults_areEmittedOnEncode`: the home for AC#1 (assert `capabilities` is on the wire).
- `app/src/test/java/de/pyryco/mobile/data/network/MobileWireCodecTest.kt:214-228` —
  `token_isRedactedInToStringButNotOnTheWire`: the redaction invariant AC#3 must not regress.
- `app/src/test/java/de/pyryco/mobile/data/network/NoiseIkSessionTest.kt:356-369` — `ackEnvelope`
  helper; thread a `capabilities` parameter through it for the granted / not-granted cases.
- `app/src/test/java/de/pyryco/mobile/data/network/NoiseSessionPumpTest.kt:55-70, 760-770` — the
  `Open` assertion (`assertEquals(PumpState.Open("conn-xyz"), …)`) and the `ackEnvelope` helper;
  these stay green untouched because the new `Open` field defaults to `emptySet()`.

## Design

Three production files; no new exported type; the negotiated set is surfaced on the existing
`PumpState.Open`, observable through the already-exposed `ManagedSessionPump.state`.

### 1. Wire models — `MobileWireModels.kt`

Add the `capabilities` field to both handshake payloads. The wire key is a single lowercase word
(`capabilities`), so it needs **no** `@SerialName` (matches the `role` / `token` precedent).

- `HelloClientPayload` — add `val capabilities: List<String> = listOf(CAPABILITY_INTERACTIVE)`.
  With `encodeDefaults = true` this is emitted on every `hello` (AC#1). Add `capabilities` to the
  overridden `toString` (non-secret — it improves debuggability); **keep `token=***`** unchanged.
- `HelloAckPayload` — add `val capabilities: List<String> = emptyList()`. The server emits it
  `omitempty`, so an absent field decodes to the empty default (AC#3). Adding a defaulted field
  does not break the two existing construction sites in tests.
- Add a top-level `internal const val CAPABILITY_INTERACTIVE = "interactive"` (the wire token from
  the server SSOT) and reference it from the `HelloClientPayload` default. This removes magic-string
  drift and gives #385 a symbol to import for its membership check. It is a constant, not a type —
  no new exported surface. (`MobileWireModels.kt` already holds multiple top-level declarations, so
  the ktlint single-class filename rule does not apply — see `[[ktlint-filename-rule-single-class]]`.)

### 2. Session — `NoiseIkSession.kt`

Surface the negotiated set alongside `connId`, mirroring the existing `connId` property so the
public `readResp(): String` contract (and its existing test assertions) is unchanged.

- `parseHelloAck` — change the private return type from `String` to `HelloAckPayload` (return the
  decoded payload rather than just `.connId`). The existing malformed-`hello_ack` try/catch and the
  `type != "hello_ack"` guard are unchanged.
- `readResp` — extract both `connId` and `capabilities.toSet()` from the decoded payload; store the
  set in a new backing field next to `establishedConnId`; **return `connId` as before**.
- New property, mirroring `connId:104`:
  `val negotiatedCapabilities: Set<String>` — returns the stored set; throws `IllegalStateException`
  until the handshake completes (the pump only reads it after a successful `readResp`, so it is
  always established at the read).
- `buildHello` needs **no change** — it constructs `HelloClientPayload` without `capabilities`, so
  the `listOf(CAPABILITY_INTERACTIVE)` default applies and serialises. (Confirm via test, per the
  ticket's "don't assume" note.)

**Wire `List` → surface `Set`.** The payload models the wire shape (`List<String>`). The surfaced
type is `Set<String>` — capabilities are a membership set ("is `interactive` granted?"), and the
set dedups/normalises a daemon that repeats an entry. Convert once at the `readResp` boundary.

### 3. Pump — `NoiseSessionPump.kt`

- `PumpState.Open` — add `val capabilities: Set<String> = emptySet()` as a **defaulted** second
  field. Defaulting is deliberate: the four existing `PumpState.Open(...)` construction/assertion
  sites (3 in `NoiseSessionPumpTest`, 1 in `RelayRepositoryCoordinatorTest`) keep compiling and stay
  green (an existing no-capability `hello_ack` → empty set → `Open("conn")` still equals
  `Open("conn", emptySet())`). Same defaulted-param pattern as #398's `makeVm`.
- `drive()` (`NoiseSessionPump.kt:164`) — construct
  `PumpState.Open(connId, session.negotiatedCapabilities)`. This is the **only** production
  construction; it always passes the real set.

### Surfacing target — why `PumpState.Open` and nothing more

AC#2/AC#4: the negotiated set must be readable by "consumers of the session … the open-session
signal the repository already observes" without re-parsing wire bytes. That signal is
`ManagedSessionPump.state: StateFlow<PumpState>` — and `Open` is the open-session case. Carrying the
set on `Open` makes it readable through the already-exposed `state` flow with **no** change to the
`SessionPump` / `ManagedSessionPump` interfaces. A consumer determines grant with
`CAPABILITY_INTERACTIVE in open.capabilities` (AC#4). This slice deliberately does **not** thread
the set further into the repository projection or add a convenience boolean — #385 owns reading it.

### Data flow

```
hello (out):   buildHello → HelloClientPayload(capabilities=["interactive"])
               → MobileJson(encodeDefaults) → noise_init early-data        [AC#1]

hello_ack (in): noise_resp → readResp (MAC verified) → parseHelloAck
               → HelloAckPayload.capabilities (List) → .toSet()
               → session.negotiatedCapabilities (Set)
               → PumpState.Open(connId, capabilities)                      [AC#2/#3]
               → ManagedSessionPump.state (already observed)               [AC#4]
```

## State + concurrency model

No new flows, scopes, dispatchers, or jobs. `negotiatedCapabilities` is written exactly once, on the
drive coroutine inside `readResp`, before `mutableState.value = PumpState.Open(...)` publishes — same
happens-before edge that already guards `establishedConnId`. `PumpState.Open` remains an immutable
`data class`; its new `Set<String>` field is read-only. No additional synchronisation is required:
the set is published through the existing `MutableStateFlow` write, which carries the established
fields with it.

## Error handling

- **Malformed `hello_ack`** (undecodable envelope, wrong `type`, or `capabilities` that is not a JSON
  array — e.g. `"capabilities":"interactive"`): the existing `parseHelloAck` try/catch turns the
  `SerializationException` into `NoiseSessionException("malformed hello_ack")`; `drive()` tears the
  session down to `PumpState.Closed`. Fail-closed, no crash, no behaviour change.
- **Absent `capabilities`** (daemon does not echo): decodes to the `emptyList()` default → empty set
  → not granted (AC#3). No error.
- **`conn_id` parsing / token redaction**: untouched. `parseHelloAck` still reads `connId`; the
  `HelloClientPayload.toString` still emits `token=***`. AC#3's "no regression" holds.
- The pump's no-logs contract is preserved — nothing about capabilities is logged.

## Testing strategy

Unit tests only (`./gradlew testDebugUnitTest`; the aggregate `test` task also works — note
`--tests` requires `testDebugUnitTest`, per `[[gradle-single-test-class-task]]`). No instrumented
test. Drive the real `NoiseIkSession` / `NoiseSessionPump` against the in-test `TestResponder`
already in those suites; no MockK. Thread a `capabilities` parameter through the existing
`ackEnvelope` helpers to inject the echoed set.

Scenarios (bullets — developer writes them in the suite's idiom):

- **AC#1 — hello advertises interactive (`MobileWireCodecTest`).** Encode a `HelloClientPayload`
  built with no explicit `capabilities`; assert the serialized JSON contains
  `"capabilities":["interactive"]`. Fold into `defaults_areEmittedOnEncode` or add a sibling.
- **AC#1 (end-to-end, optional, `NoiseIkSessionTest`).** Have the `TestResponder` capture the
  decoded `hello` early-data; assert its `capabilities` contains `interactive`. Proves the default
  rides through `buildHello` → `writeInit`, not just direct encode.
- **AC#2 — hello_ack echoes interactive → granted (`NoiseIkSessionTest`).** Responder returns a
  `hello_ack` with `capabilities=["interactive"]`; after `readResp`, assert
  `session.negotiatedCapabilities == setOf("interactive")`.
- **AC#3 — hello_ack omits capabilities → not granted (`NoiseIkSessionTest`).** Responder returns a
  `hello_ack` with no `capabilities`; after `readResp`, assert `session.negotiatedCapabilities`
  is empty and `connId` still parses (no regression).
- **AC#3 — malformed capabilities fails closed (`NoiseIkSessionTest`).** A `hello_ack` whose
  `capabilities` is a JSON string (not array) → `readResp` throws `NoiseSessionException`; session
  state is `CLOSED`. Confirms the untrusted-parse boundary holds.
- **AC#2/#4 — set reaches `PumpState.Open` (`NoiseSessionPumpTest`).** Drive the pump with a
  responder echoing `["interactive"]`; assert `pump.state.value == PumpState.Open("conn-xyz",
  setOf("interactive"))` and that `"interactive" in (state as Open).capabilities`.
- **AC#5 — existing handshake tests still pass.** The no-capability path: existing
  `assertEquals(PumpState.Open("conn-xyz"), …)` assertions remain green because `Open.capabilities`
  defaults to `emptySet()` and the existing `ackEnvelope` helpers emit no `capabilities`.
- **Redaction unchanged (`MobileWireCodecTest`).** `token_isRedactedInToStringButNotOnTheWire`
  continues to pass; optionally assert the new `toString` still contains `***` and no token.

## Open questions

- **Convenience accessor.** AC#4 is satisfied by `CAPABILITY_INTERACTIVE in open.capabilities`. A
  `PumpState.Open.interactiveGranted: Boolean` would be sugar; deferred to #385 if it wants it. Not
  added here to keep the surface minimal per the ticket.
- **`CAPABILITY_INTERACTIVE` visibility.** Specced `internal` (single-module app; #385 is in the same
  module). If #385 ends up cross-module later, widen then — not now.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries] No MUST FIX.** The untrusted→trusted crossing is the `hello_ack` early-data
  (relay/daemon → process), decoded at the single private function `NoiseIkSession.parseHelloAck`.
  This boundary sits **after** authentication: `readResp` runs `hs.readMessage` (Noise MAC
  verification against the pinned server static `rs`, `NoiseIkSession.kt:135`) and the `SPLIT` check
  (`:141`) *before* `parseHelloAck` (`:142`). A relay-operator MITM has no private half of the
  pinned `rs`, so a forged/tampered `hello_ack` fails the MAC and throws `NoiseSessionException`
  before any `capabilities` byte is parsed. Therefore the surfaced `Set<String>` is exactly as
  trustworthy as the pinned-key handshake. Downstream consumers hold the parsed `Set`, not raw
  bytes. The boundary is explicit and single-point.
- **[Tokens, secrets, credentials] No MUST FIX.** No new tokens/secrets. The `capabilities` lists
  are non-secret capability identifiers. The existing `HelloClientPayload.toString` token redaction
  (`token=***`) is explicitly preserved; only the non-secret `capabilities` is added to `toString`.
  AC#3 and the existing `token_isRedactedInToStringButNotOnTheWire` test guard the no-regression.
- **[File / storage operations] N/A.** This slice touches no filesystem or persistent storage. The
  negotiated set lives only in RAM on `PumpState.Open`; nothing is written to disk, cached, or
  backed up.
- **[Inter-process / Android attack surface] N/A.** No Intents, deep links, exported components,
  `PendingIntent`s, content providers, or WebView. Code stays in portable `data/` with no `android.*`
  imports (CLAUDE.md). No new attack surface.
- **[Cryptographic primitives] N/A (no new crypto).** The slice introduces no RNG, key storage, or
  secret comparison. It rides on the existing `Noise_IK_25519_ChaChaPoly_BLAKE2s` handshake; the
  capability parse happens after `hs.split()`. The `CAPABILITY_INTERACTIVE in set` membership test
  compares public capability identifiers, not secrets — no constant-time requirement.
- **[Network & I/O] No MUST FIX.** No new network calls, sockets, timeouts, or frame-size policy.
  The `hello_ack` is the existing `noise_resp` early-data, a single frame already size-bounded by
  the WS client (#276); `parseHelloAck` allocates proportional to that already-capped frame. A
  daemon echoing a large `capabilities` array is bounded by the inherited frame cap — this slice
  adds no new unboundedness.
- **[Error messages, logs, telemetry] No MUST FIX.** The pump emits no logs (its documented
  contract). `NoiseSessionException` messages are category-only (`"malformed hello_ack"`) and carry
  no capability values, key material, or secrets. No telemetry is added. The new `toString` keeps
  the token redacted and adds only non-secret capabilities.
- **[Concurrency] No MUST FIX.** `negotiatedCapabilities` is written exactly once, on the drive
  coroutine, before the `mutableState.value = PumpState.Open(...)` publish — the same happens-before
  edge that already carries `establishedConnId`/`connId`. `PumpState.Open` stays an immutable
  `data class`; the `Set<String>` is read-only. No new scope/job/mutex; no check-then-mutate on the
  `StateFlow` (the established fields ride the single state write). No TOCTOU.
- **[Threat model alignment] SHOULD FIX (hand-off to #385), not gated here.** `protocol-mobile.md`
  § Capability negotiation specifies the daemon echoes the **intersection** (never a blind mirror),
  so a well-behaved server cannot grant a capability the phone did not advertise. This slice
  surfaces the daemon's echo **verbatim** (per the ticket's "surface the set the daemon agrees to"
  + "keep the surface minimal") and deliberately does not re-derive the intersection client-side.
  Because this slice **gates nothing**, a misbehaving/compromised authenticated daemon echoing an
  unadvertised capability confers no authority. The consuming gate slice (#385) MUST treat the
  surfaced set as *server-asserted* and is the correct place to decide whether to trust it (e.g.
  intersect with the phone's own advertised set before acting). Recorded as a hand-off, not a MUST
  FIX, since no authority is conferred at this layer.
- **[Threat model — mobile-specific] N/A.** No UI, no user text entry, no new persisted/visible
  data — screenshot leakage, accessibility eavesdropping, screen-overlay, deep-link, and keyboard-
  logging threats do not apply to this data-layer-only slice.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-06-08
