# 412 — Record the structured-stream replay cursor (`event_id` on the inbound envelope)

**Ticket:** [#412](https://github.com/pyrycode/pyrycode-mobile/issues/412) · **Size:** S · **Labels:** `security-sensitive`
**Split from:** #402. **This is the wire-substrate slice.** The advertise-on-reconnect + resync slice is **#413** (`blockedBy #412`).

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt:37-44` — the `Envelope` data class. `event_id` is added here as a sibling of `inReplyTo`; copy that field's exact shape.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt:11-40` — `MobileJson` config (`encodeDefaults = true`, **`explicitNulls = false`**, `ignoreUnknownKeys = true`). This is **why** a `Long? = null` field omits-when-null on encode and tolerates-absent on decode. Do not introduce a different `Json` instance.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:198-328` — the **single inbound consumer** (`init` collector → `onInbound(envelope)` `when`). The cursor recording is added here, at the top of `onInbound`. Note the existing `negotiatedCapabilities()` gate on the `TYPE_TURN_STATE…TYPE_TURN_END` and `TYPE_STALL` arms — the recording mirrors that gate.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:82-106` — the constructor's existing **defaulted** params (`deviceName = ""`, `negotiatedCapabilities = { emptySet() }`). The new `replayCursor` param follows the same defaulted-so-existing-callers-compile pattern.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt:77-182` — the reconnect-spanning owner. It builds a **fresh** `RemoteConversationRepository` per connection in `onConnection`. The cursor lives as a coordinator field (spans reconnects) and is threaded into each per-connection repo here. See the class doc at `:57-69` — the coordinator is already "the layer that owns the connection-scoped pump," i.e. the junction of both the recorder (repo) and (for #413) the hello producer (pump).
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseIkSession.kt:297-313` — `buildHello()`, where #413 will read the cursor at handshake-build time. **Read-only context for this slice** — do not modify; it tells you *why* the cursor must be readable independent of any connection's inbound path.
- `app/src/test/java/de/pyryco/mobile/data/network/MobileWireCodecTest.kt:40-64` — `envelope_withInReplyTo_roundTrips` + the omit test. The `event_id` round-trip/omit tests mirror these exactly.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt:2519-2538` — `FakeSessionPump` (`push(envelope)` + `inbound`) and the `runCurrent()` drive idiom. The cursor-recording tests reuse this harness. ⚠ Drive with `runCurrent()`, **not** `advanceUntilIdle()` (no timers in this cascade — see lessons).

## Context

Every interactive structured-stream frame (`turn_state` / `assistant_delta` / `tool_use` / `tool_result` / `turn_end`) now carries an **envelope-level** `event_id` — a durable, per-conversation, strictly-increasing id that is stable across reconnects (distinct from the per-connection `id`, which resets each reconnect). The server producer is merged (pyrycode#649, an `omitempty *uint64`).

This slice does two things and nothing else:

1. Adds the `event_id` field to the inbound `Envelope` (decode-tolerant, absent on non-interactive frames).
2. Records the latest observed `event_id` as a strictly-advancing **high-water-mark cursor**, held in a place that **spans reconnects** and is readable at handshake-build time.

It does **not** advertise anything on reconnect and does **not** read the cursor into `hello` — that is #413. The cursor is the seam #413 consumes.

Wire SSOT: pyrycode `docs/protocol-mobile.md` § "Interactive events (v2, capability-gated)" → "Replay cursor (`event_id`)".

## Design

Three production additions plus one wiring change; four production files.

### 1. `Envelope.event_id` — `MobileWireModels.kt`

Add to the `Envelope` data class, modeled **identically to `inReplyTo`**:

```kotlin
@SerialName("event_id") val eventId: Long? = null
```

- **Decode:** absent → `null` (the default); a JSON number → `Long`; an explicit JSON `null` → `null`. A missing `event_id` never fails the decode of an otherwise-valid envelope (AC #1). `ignoreUnknownKeys = true` already tolerates the field on servers that don't emit it; the nullable default tolerates its absence on a per-frame basis.
- **Encode:** `explicitNulls = false` omits the property entirely when `null` (AC #1: "absent (omitted, not `0`/`null`)"; AC #5 omit). A present value emits `"event_id":<n>`.
- **Type:** the server field is `*uint64`. Kotlin `Long?` is the established model for wire integers (`Envelope.id` is `Long`). Realistic event counts are far below 2^63; a pathological `uint64 > 2^63` deserializes to a negative `Long` and is rejected by `ReplayCursor.record`'s positive guard (below) rather than poisoning the cursor.
- **Outbound encode is unchanged.** `Envelope` is also encoded when *sending* (every request, and the `hello` envelope at `NoiseIkSession.buildHello`). The new field is defaulted `null` and `explicitNulls = false` omits it, so every outbound frame — including `hello` — is byte-identical to today. The new param is defaulted, so `buildHello`'s `Envelope(id = 1L, type = "hello", …)` and every other construction compile unchanged. No sender-side wire regression.

No `toString`/redaction concern — `event_id` is a non-secret monotonic counter.

### 2. `ReplayCursor` — new file `data/network/ReplayCursor.kt`

A tiny, thread-safe, reconnect-spanning holder of the high-water mark. No Android imports (portable per CLAUDE.md). **Contract:**

- `val latest: Long?` — the latest recorded `event_id`, or `null` when nothing valid has ever been observed. A **synchronous** read, valid at any moment, including before a connection's inbound path is established (AC #4). On a fresh start it is `null` ("no cursor", omittable — never `0`).
- `fun record(eventId: Long)` — fold an observed value into the high-water mark:
  - **Ignores `eventId <= 0`** (fail-closed: a valid `uint64` cursor is ≥ 1; a `0`, a negative, or a wrapped-huge value is never recorded — protects AC #4's "not `0`" invariant and "never poison the cursor").
  - Otherwise advances **only** when strictly greater than the current mark (`null` counts as "below any value"); a smaller / equal / out-of-order / replayed value is a no-op. This is the "strictly-advancing high-water mark."

Backing: a `MutableStateFlow<Long?>(null)` with an atomic `update {}` max-fold gives lock-free thread safety (the recorder coroutine writes; #413's hello-build reads `latest` from a different coroutine). The flow is **not** exposed — only the `latest` read and `record` write are public. The behaviour invariant (monotonic, positive-guarded, null-initial) is asserted by `ReplayCursorTest`. Implementation body must stay under ~15 lines; do not over-build (no observation API — #413 needs a point read, not a stream).

### 3. Recording in the single inbound consumer — `RemoteConversationRepository.kt`

**Constructor:** add a fifth, **defaulted** param after `negotiatedCapabilities`:

```kotlin
private val replayCursor: ReplayCursor = ReplayCursor(),
```

Defaulted to a throwaway instance so every existing construction site and test compiles unchanged (same pattern as `deviceName` / `negotiatedCapabilities`). Production wires the coordinator's shared instance (§4).

**Recording:** add one private helper, called as the **first line** of `onInbound(envelope)` (before the `when`):

- `private fun recordReplayCursor(envelope: Envelope)` — gate on `CAPABILITY_INTERACTIVE in negotiatedCapabilities()`, then `envelope.eventId?.let { replayCursor.record(it) }`.

Three properties this gives, mapped to ACs:

- **Envelope-level, type-agnostic.** Recording reads `envelope.eventId` directly — it does not depend on whether the per-type *payload* decodes. A frame with a valid `event_id` but a malformed structured payload still advances the cursor (the durable event occurred; the cursor marks position, not decodability). This matches the ticket's "envelope-level, not a payload field."
- **AC #3 (non-interactive frame leaves the cursor unchanged):** a non-interactive frame carries no `event_id` → `eventId` is `null` → `record` is never called → no-op. The `interactive` gate is the belt; the null-check is the suspenders.
- **Defence-in-depth gate.** Gating on `interactive` (symmetric with the existing structured-stream and `stall` arms) means a buggy/hostile *authenticated* daemon that ignored the negotiated set and injected `event_id` on a frame to a phone that did **not** negotiate `interactive` does not advance a cursor the phone will never advertise (#413's advertise is itself `interactive`-gated). See Security review for why this is defence-in-depth rather than load-bearing.

The recording does not touch the `when` arms; the existing demux is unchanged.

### 4. Reconnect-spanning ownership — `RelayRepositoryCoordinator.kt`

The recording site (`RemoteConversationRepository`) is **rebuilt every reconnect** (`onConnection` constructs a fresh repo per live transport). The cursor therefore cannot live in the repo. It lives in the **coordinator**, which is a process-lifetime singleton (`createdAtStart`, started once) and already owns both the per-connection repo (recorder) and the per-connection pump (the hello producer #413 reads from).

- Add a coordinator field: `internal val replayCursor: ReplayCursor = ReplayCursor()`. `internal` (module-visible) so unit tests and #413 can read it without making it a public API surface — the same visibility the tested `internal fun PumpState?.toPyrycodeLinkStatus()` at `:246` uses. Survives reconnects because `teardownActive` (the per-connection churn path) does not touch it; only a full coordinator teardown ends the process-scoped object.
- In `onConnection`, thread it into the per-connection repo:

```kotlin
val repo = RemoteConversationRepository(
    pump, childScope, deviceName,
    negotiatedCapabilities = { (pump.state.value as? PumpState.Open)?.capabilities.orEmpty() },
    replayCursor = replayCursor,
)
```

This keeps `onConnection` non-suspending (the `:150` cancellation-atomicity invariant) — it is one extra named-arg, no new suspension point.

**Why the coordinator, not a singleton in `AppModule`.** The coordinator is the single layer that owns *both* the connection-scoped repo and the connection-scoped pump. #413 reads the cursor at `buildHello`, which is produced by the pump the coordinator's `createPump` lambda builds — so #413 can wire the read entirely inside the coordinator/pump path with no `AppModule` or `NoiseClientInfo` change, and no re-homing of the cursor. Holding the cursor here keeps this slice to four production files and gives #413 the cleaner seam. `AppModule` is **not** touched by this slice.

### Data flow

```
pump.inbound (single-consumer) ─▶ RemoteConversationRepository.onInbound(envelope)
                                     │  recordReplayCursor(envelope):
                                     │     if interactive: envelope.eventId?.let(coordinator.replayCursor::record)
                                     └─▶ existing when(type) demux (unchanged)

coordinator.replayCursor  ──(survives connection churn)──▶  read at next hello-build (#413)
```

## State + concurrency model

- **No new coroutines, scopes, or flows on the hot path.** Recording is a synchronous call inside the *existing* single inbound collector launched in the repo's `init`. There is no second subscription to `pump.inbound` (AC #2 explicit).
- **One writer, one reader, no shared-mutable race.** The cursor is written only from the repo's single inbound collector coroutine (one per live connection; connections never overlap — `teardownActive` cancels the old repo's collector before `onConnection` builds the new one). It is read by #413 from the hello-build coroutine of the *next* connection, which by construction runs after the previous connection's collector is gone. `ReplayCursor`'s `MutableStateFlow.update {}` provides atomic, lock-free safety regardless, so even a transient overlap is correct (TOCTOU-free max-fold, not check-then-set).
- **Lifetime.** The cursor is in-memory, coordinator-scoped (process lifetime). It survives transport reconnects; it is **not** persisted, so process death resets it to `null` — exactly AC #4's "fresh start ⇒ no cursor."
- **Dispatcher / shutdown:** unchanged. The recording is non-blocking and non-suspending; it adds nothing to teardown.
- **The recording path must be total (non-throwing).** It runs inside the single inbound collector (`scope.launch { pump.inbound.collect { onInbound(it) } }`); a throw there kills the collector and silently dead-ends the connection's entire inbound stream. The specified `recordReplayCursor` is throw-free by construction — it reads an already-decoded `Long?` (no decode step), does a set-membership check and a pure-arithmetic `update {}` fold. It needs **no** `try/catch` (unlike the decode arms), but the developer must not introduce one that can throw. This is an availability invariant, not just tidiness.

## Error handling / trust boundary

`event_id` is untrusted network input that has already crossed the authenticated Noise channel (the pump decrypts + MAC-verifies before any envelope reaches `onInbound`). Two failure modes, both fail-closed:

- **Malformed / wrong-typed `event_id`** (e.g. a JSON string where a number is expected): this fails the *envelope* decode in `NoiseSessionPump.onOpenFrame` (`decodeFromString<Envelope>`), exactly as a malformed `id`/`type`/`ts` does today — the existing pump posture tears the session down and the supervisor reconnects. The cursor is **untouched** by a teardown (it is coordinator-scoped, not pump-scoped), so it is never poisoned by a bad value, and the reconnect re-reads the intact high-water mark. This slice does **not** change the pump's structural-decode behaviour (out of scope; a crypto-adjacent file).
- **Out-of-range / out-of-order value** (`0`, negative, wrapped-huge, or a value below the current mark): rejected or ignored by `ReplayCursor.record` (positive guard + strict-greater fold). The cursor never moves backward and never records a non-positive sentinel.

Nothing logs the `event_id` or the payload (uniform with every other `onInbound` arm — payloads may carry message/tool content). `event_id` itself is non-secret, but logging is omitted for consistency.

## Testing strategy

All unit (`./gradlew testDebugUnitTest`); no instrumented tests. Use a single test class to verify a single FQCN: `./gradlew testDebugUnitTest --tests "<FQCN>"` (bare `test --tests` fails — see lessons).

**`MobileWireCodecTest.kt`** (mirror `envelope_withInReplyTo_roundTrips` / the omit test):
- An `Envelope` JSON with `"event_id":42` decodes to `eventId == 42L` and re-encodes containing `"event_id":42`.
- An `Envelope` JSON **without** `event_id` decodes to `eventId == null`.
- Encoding an `Envelope` with `eventId == null` produces output that does **not** contain `event_id` (omit, AC #5).

**`ReplayCursorTest.kt`** (new):
- Fresh cursor → `latest == null` (AC #4 "no cursor", not `0`).
- `record(5)` then `latest == 5`; `record(3)` (smaller) → still `5`; `record(5)` (equal) → still `5`; `record(9)` → `9` (strictly-advancing high-water mark).
- `record(0)` and `record(-1)` on a fresh cursor → `latest` stays `null` (positive guard; never records the `0` sentinel).
- `record(0)` after `record(7)` → still `7`.

**`RemoteConversationRepositoryTest.kt`** (reuse `FakeSessionPump` + `runCurrent()`):
- With `negotiatedCapabilities = { setOf("interactive") }` and an injected `ReplayCursor`: pushing a structured frame (e.g. `turn_state`) whose envelope has `eventId = 10` advances `cursor.latest` to `10`; a subsequent frame with `eventId = 20` advances to `20`; a frame with `eventId = 15` (out-of-order) leaves it at `20`.
- A non-interactive frame (e.g. a `conversations` envelope, no `event_id`) leaves `cursor.latest` unchanged (AC #3).
- With `negotiatedCapabilities = { emptySet() }` (interactive **not** negotiated): a frame carrying `eventId = 99` leaves `cursor.latest == null` (defence-in-depth gate).
- A structured frame with a valid `eventId` but a **malformed payload** (dropped by `decodeLiveSessionEvent`) still advances `cursor.latest` (envelope-level recording is independent of payload decode).

**AC #4 "survives reconnect"** — the cheapest faithful proof: construct **two** `RemoteConversationRepository` instances sharing **one** `ReplayCursor` (simulating the coordinator handing the same cursor to a fresh per-connection repo); record via the first repo's inbound, then assert the second repo (and the shared cursor) reads the high-water mark. If `RelayRepositoryCoordinatorTest` already has a reconnect-churn harness (new transport emission), additionally assert `coordinator.replayCursor.latest` persists across an `onConnection` churn; otherwise the shared-cursor repo test is sufficient and the coordinator wiring is a one-line named-arg covered by the existing coordinator construction tests.

## Open questions

- **Re-pair to a different server within one process lifetime.** The cursor is a single high-water mark for the currently-paired server's stream; a re-pair would make it stale for the new server. **Out of scope** — re-pairing is a heavy flow (new `PairedServer`) and the stale-cursor recovery is owned by #413 + the server resync marker (pyrycode#647). This slice records faithfully; it is not the place to detect a pairing change.
- **Whether `event_id` ever rides a non-structured interactive frame** (e.g. a future `stall` carrying it). The type-agnostic, `interactive`-gated recording records it automatically if so; no change needed. If the server contract later guarantees `event_id` only on the five structured types, the recording is still correct (it only acts when the field is present).

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No findings. `event_id` crosses untrusted→trusted at exactly one explicit, named point: `ReplayCursor.record` (positive guard + strict-greater fold). The only public read is `ReplayCursor.latest`, which can only ever hold a value that passed `record` — so #413 (the sole downstream consumer) holds a validated, monotonic, positive value, never a raw wire integer. The recording sits **behind** the already-authenticated Noise channel (the pump MAC-verifies + decrypts before any envelope reaches `onInbound`), so the source of `event_id` is the authenticated paired daemon, not an arbitrary MITM.
- **[Tokens / secrets]** Not applicable. `event_id` is a non-secret monotonic counter. No token/secret is generated, stored, compared, or logged in this slice; the pre-existing `hello` token redaction is untouched.
- **[File / storage]** Not applicable — and deliberately so. The cursor is **in-memory only**, never persisted (AC #4: fresh start ⇒ `null`). No path concatenation, no file I/O, no at-rest-encryption surface is introduced.
- **[IPC / Android surface]** Not applicable. Pure `data/` layer, no Android imports (CLAUDE.md portability), no Activity/Service/Receiver/deep-link/PendingIntent/ContentProvider/WebView. `replayCursor` is `internal` (module-visible, read-only `val`) — no exported/IPC surface.
- **[Cryptographic primitives]** Not applicable. No RNG, no crypto, no key/nonce. `MutableStateFlow` is not security-relevant randomness. The Noise channel that authenticates the `event_id` source is pre-existing and unmodified.
- **[Network & I/O]** OUT OF SCOPE for the watermark-mute exploit — **owned by #413 + server pyrycode#647** (a hostile authenticated daemon sending a giant `event_id` → cursor jumps → #413 advertises it → daemon replays nothing newer → silent live-stream drop). The **load-bearing verification this slice must satisfy** and does: the cursor recording is a **pure side-write with no feedback into delivery** — `recordReplayCursor` writes the cursor and returns; no `onInbound` arm, no `liveSessionEvents` emission, and no event delivery reads the cursor. So #412 cannot itself mute or drop a live event; the exploit only becomes reachable when #413 *advertises* + the server/decode-gate *acts on* the watermark. No new frame-size cap, timeout, or TLS surface (a single integer on an existing envelope). The memory-flagged #413 security gate (verify pyrycode#647's watermark-mute is resolved on pyrycode `main` before #413 advertises) stands; #412 does not pre-empt it.
- **[Error messages / logs]** No findings. The recording logs nothing — neither `event_id` nor the payload — uniform with every other `onInbound` arm (payloads may carry message/tool content). No new error message, Toast, or crash-reporter surface.
- **[Concurrency]** No findings. Single writer (the one inbound collector per connection; connections never overlap — `teardownActive` cancels the old collector before `onConnection` builds the new repo), read by #413 from the next connection's hello-build (strictly after). `ReplayCursor` folds via `MutableStateFlow.update {}` — atomic, lock-free, **TOCTOU-free** (max-fold, not check-then-set), correct even under a transient writer/reader overlap. No new coroutine, scope, or mutex. The recording is non-suspending, non-blocking, and **non-throwing** (see State + concurrency model) — it cannot back-pressure or kill the single inbound collector that also carries the connection's `conversations`/`message`/`ack` traffic.
- **[Threat model alignment]** Aligned with pyrycode `docs/protocol-mobile.md` § Replay cursor (`event_id`). The one replay-specific threat (untrusted watermark → mute) is named and routed to #413/pyrycode#647. No mobile-specific threat (screenshot/accessibility/overlay/deep-link/keyboard) applies — this slice has no UI and accepts no user input.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-06-09
