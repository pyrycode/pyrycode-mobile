# Architecture: Noise_IK re-key triggers — 1-hour timer + inbound `rekey_request` dispatch (initiator) (#304)

Wires the two re-key entry points (a session-scoped 1-hour timer + an inbound `rekey_request`
envelope) into the **landed** Noise session pump (#309), driving the **landed** in-place re-key
mechanism (#303). This ticket invokes both; it re-implements neither. Pure `data/network`
orchestration glue — not UI-visible, so there is correctly no `## Design source` section (the ticket
body has no `## Figma`; same shape as #298 / #303 / #309).

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionPump.kt` (whole file, 219 lines) —
  **the primary file this ticket modifies.** Extract: `onOpenFrame` (:164-177) — the `when (frame.type)`
  dispatch whose `else` (:175) is the documented #304 seam; the `noise_msg` branch (:166-172) where the
  decrypted `Envelope` is produced and which this ticket extends to intercept `rekey_request`. `drive()`
  (:108-161) — the single inbound collector; `mutableState.value = PumpState.Open(connId)` (:148) is
  where the timer first arms. `send` + `outboundLock` (:84-100) — the AEAD-nonce-ordering invariant the
  re-key `noise_init` send is **outside of** (it carries no transport-AEAD payload). `teardown` (:185-192)
  — `scope.cancel()` (:191) cancels every child coroutine, so the timer/launched-rekey jobs leak nothing
  on close. Companion frame-type constants (:194-201): `TYPE_NOISE_INIT`/`TYPE_NOISE_RESP`/`TYPE_NOISE_MSG`.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseIkSession.kt:161-231` — **the mechanism this
  ticket drives, read the signatures EXACTLY.** `writeRekeyInit(s: ByteArray): ByteArray` (:171-189)
  takes the **device static private key `s`** (the caller re-loads it fresh and **MUST zero it** after the
  call — the KDoc :167-170 is load-bearing), NOT the server static. The pinned server static `rs` is held
  internally (`pinnedRemoteStatic` :81) and is the continuity anchor — there is **no** server-static
  parameter. `readRekeyResp(resp: ByteArray)` (:202-231): atomic CipherState swap on success; **RETAINS**
  the live keys and throws `NoiseSessionException` on MAC failure; both are `@Synchronized` (the swap
  cannot interleave with an in-flight `encrypt`/`decrypt`). `close()` (:262-274) wipes `pendingRekey`.
- `app/src/main/java/de/pyryco/mobile/data/network/NoiseSessionFactory.kt` (whole file, 58 lines) —
  `create()` (:26-53) shows the exact `pairedServerStore.load() → deviceStaticKeyStore.loadOrCreate(
  paired.serverId).privateKey → …fill(0)` sequence the new `reloadDeviceStaticKey()` helper mirrors. The
  factory already holds both stores; the pump holds the factory.
- `app/src/main/java/de/pyryco/mobile/data/crypto/DeviceStaticKeyStore.kt:12-37` —
  `loadOrCreate(serverId): DeviceStaticKeyPair` returns a **fresh, caller-owned** 32-byte private-key
  array each call (verified: `KeystoreDeviceStaticKeyStore.unwrap`/`generate` mint a new buffer), so the
  pump zeroing its copy is correct and safe.
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt:22-44` — `InnerFrameV2(v, type,
  data)` (the outer frame; the re-key `noise_init` is framed with `type = "noise_init"`) and `Envelope(id,
  type, ts, payload, inReplyTo)` (the decrypted envelope; the `rekey_request` is discriminated by
  `type`). `payload` is a required non-null `JsonElement` — **not decoded** on the re-key path (see Design).
- `app/src/main/java/de/pyryco/mobile/data/network/MobileWireCodec.kt:29-49` — `MobileJson`
  (`ignoreUnknownKeys = true` — the wire layer is already lenient-decode), `base64StdEncode` /
  `base64StdDecode`. The pump base64-wraps the re-key `noise_init` `data` with these.
- `app/src/test/java/de/pyryco/mobile/data/network/NoiseSessionPumpTest.kt` (whole file, 493 lines) —
  **the test file this ticket extends.** Reuse `Fixture` (:293-308), `openSession` (:318-339),
  `FakeRelayTransport` (:378-407), `FakeDeviceStaticKeyStore`/`FakePairedServerStore` (:440-454), and the
  `runTest` virtual-clock posture. The in-file `TestResponder` (:410-438) currently has **no** re-key leg
  — it must gain one (next entry).
- `app/src/test/java/de/pyryco/mobile/data/network/NoiseIkSessionTest.kt:343-434` — **copy the re-key
  responder leg from here into the pump test's `TestResponder`.** `TestResponder.rekey(init): RekeyLeg`
  (:415-426) + `RekeyLeg` (:430-434) drive a fresh responder handshake reusing the responder's **own**
  static (retain `staticPrivateKey` :379-380,386-387) so the pinned-`rs` continuity holds; `foreignRekeyResp()`
  (:348-354) is the **different-static** re-key `noise_resp` for the MITM/rotated-key fail test.
- `docs/specs/architecture/303-noise-ik-session-rekey.md` — the mechanism spec. Extract § *Re-obtaining
  the device static key `s`* (decision (B): re-load per re-key, never retain — the pump is the "future
  pump ticket" that open-question #1 names) and § *Testing strategy* (the re-key responder leg).
- `docs/specs/architecture/309-noise-session-pump.md` — the pump spec. Extract § *Open-state dispatch*
  (the `else` seam, named for #304) and § *State + concurrency model* (the single-collector contract +
  the `outboundLock` rationale this ticket must not violate).
- `pyrycode/docs/protocol-mobile.md` § Re-key (upstream, read-only — key facts inlined in § Context) and
  `pyrycode/docs/knowledge/codebase/450.md` (the Go initiator mirror — arm-on-open + re-arm-on-swap +
  coalesce; key facts inlined). Read only if cross-repo access is available; treat the inlined facts as
  authoritative.

## Context

Phase 4 chain. This is the **trigger** half of the mobile re-key loop, sitting on top of two merged
dependencies:

- **#309 `NoiseSessionPump`** — the running encrypted session. It drives the IK handshake, signals
  handshake completion via `state: StateFlow<PumpState>` reaching `PumpState.Open(connId)`, decrypts each
  open-state `noise_msg` to an `Envelope` on `inbound: Flow<Envelope>`, encrypts outbound via
  `send(envelope)`, and routes raw frames in `onOpenFrame`'s `when (frame.type)` — whose `else` branch is
  documented as *"the #304 seam (a re-key `noise_resp` branch goes here)."*
- **#303 `NoiseIkSession`** — the re-key mechanism: `writeRekeyInit(s)` (builds a fresh IK `noise_init`
  against the pinned `rs` from a freshly-supplied device static `s`; transport stays live on current keys)
  and `readRekeyResp(resp)` (atomic CipherState swap on success; fail-RETAIN on MAC failure).

The two entry points both make the **phone send a fresh `noise_init`** (IK requires the initiator — the
phone — to start; the server, when it wants a re-key, can only *nudge* via `rekey_request`):

1. **A session-scoped 1-hour timer**, started when the pump reaches `PumpState.Open`, re-based by each
   completed (re-)handshake. On fire → initiate a re-key.
2. **An inbound `rekey_request` envelope** (`{ "type": "rekey_request", "payload": { "reason":
   "scheduled" } }`, AEAD-sealed inside a `noise_msg`), which arrives **decrypted** in `onOpenFrame`'s
   `noise_msg` branch. On receipt → initiate the same re-key.

This mirrors the Go binary's initiator (`pyrycode` #450 / #454) riding on `V2SessionManager`'s open state;
the mobile equivalent rides #309's `PumpState.Open`.

### Two distinct surfaces — do not conflate (the trap the old body carried)

- The inbound **`rekey_request`** is a `noise_msg` → it is **decrypted** and parsed to an `Envelope` in
  `onOpenFrame`'s `TYPE_NOISE_MSG` branch (`Envelope.type == "rekey_request"`). It is a **control** message.
- The re-key **`noise_resp`** is a raw **handshake** frame → it arrives in `onOpenFrame`'s `when` as
  `TYPE_NOISE_RESP`, where the current `else` tears the session down. This ticket adds a branch that routes
  it to `readRekeyResp` instead.

Ordinary `noise_msg` app traffic may interleave between the re-key `noise_init` and its `noise_resp`;
that traffic stays valid on the **current** keys until `readRekeyResp` swaps them (#303 contract). The
pump is the **sole** collector of `RelayTransport.inbound` — this ticket does **not** add a second
collector; it extends the existing `onOpenFrame` dispatch.

### Wire contract — `protocol-mobile.md` § Re-key + Go mirror #450 (inlined; authoritative)

1. **Cadence:** time-based re-key fires **every 1 hour of session uptime, measured from handshake
   completion** (`PumpState.Open`). Either side may also request an immediate re-key via `rekey_request`.
   (Confirmed against `protocol-mobile.md` § Re-key — do not hard-code a value the doc contradicts; 1 h is
   correct.)
2. **Mechanism:** a full Noise_IK handshake re-run, **initiated by the phone**. The re-key `noise_init`
   and `noise_resp` both carry **empty** early-data; the `conn_id` is unchanged across re-key.
3. **Atomic switchover:** the first frame after the new handshake uses the new keys; the old CipherStates
   are zeroised (#303 `readRekeyResp`).
4. **No `rekey_ack`.** The next successful AEAD round-trip under the new keys is the implicit ack — the
   phone sends **no** ack on either trigger (Go #450: *"there is no rekey_ack envelope … the next
   successful AEAD round-trip under the new keys is the implicit ack."*).
5. **`reason` ∈ {`scheduled`, `manual`, `compromise`}** (Go #450 § Related). Informational only; the phone
   re-keys identically regardless of `reason` — it does **not** decode the payload (see Design).

## Design

### Where the device static key `s` comes from — new factory helper (DRYs #303 open-question #1)

`writeRekeyInit(s)` requires the **device static private key**, re-loaded fresh per re-key and zeroed by
the caller — #303 decision (B) deliberately keeps `s` out of the session's RAM except inside the pending
re-key handshake, to preserve #298's bounded device-key RAM window. The pump must therefore obtain `s` on
each re-key. The pump holds `NoiseSessionFactory` (which already owns both stores); #303 open-question #1
names *"a `suspend fun NoiseSessionFactory.reloadDeviceStaticKey(): ByteArray` helper"* as the seam — this
ticket adds it (it now has its first consumer):

```kotlin
// NoiseSessionFactory.kt — new method, mirrors create()'s load+wrap-failure handling.
suspend fun reloadDeviceStaticKey(): ByteArray   // runs on ioDispatcher; throws NoiseSessionException on
                                                 // "not paired" / "device static key unavailable"
```

Contract: `withContext(ioDispatcher) { pairedServerStore.load() ?: throw NoiseSessionException("not
paired"); deviceStaticKeyStore.loadOrCreate(paired.serverId).privateKey }` — wrapping
`DeviceStaticKeyException` as `NoiseSessionException("device static key unavailable", …)` exactly as
`create()` does. Returns only the private scalar (a fresh caller-owned array); the public key is unused.
**No store references are added to the pump** — it gains no constructor params for crypto stores.

### The pump's new surface — no new exported types, no DI change

All changes land **inside** `NoiseSessionPump.kt`. No new public class/interface; the only constructor
change is one defaulted parameter (zero fan-out — the sole current construction site is the test, and
#309 ships the pump dormant with no Koin binding, so #302 will pass the default):

```kotlin
class NoiseSessionPump(
    /* …existing params… */
    private val rekeyIntervalMs: Long = REKEY_INTERVAL_MS,   // 3_600_000 (1 h); injected small in tests
) {
    private val rekeyMutex = Mutex()          // serializes the two initiation paths (timer vs rekey_request)
    @Volatile private var rekeyInFlight = false
    private var rekeyTimerJob: Job? = null    // the one-shot 1-h timer; only the drive coroutine touches it
}
// companion: const val REKEY_INTERVAL_MS = 3_600_000L; const val TYPE_REKEY_REQUEST = "rekey_request"
```

### Initiation — `initiateRekey()` (the load-bearing concurrency contract)

Both triggers funnel into one `private suspend fun initiateRekey()`. The lock/flag/order discipline below
**is** the contract (it is what the [Concurrency] security finding turns on); the developer writes the
body to it:

```kotlin
private suspend fun initiateRekey() {
    if (mutableState.value !is PumpState.Open) return
    rekeyMutex.withLock {
        if (rekeyInFlight || mutableState.value !is PumpState.Open) return@withLock   // coalesce
        val session = this.session ?: return@withLock
        val s = try { sessionFactory.reloadDeviceStaticKey() }
                catch (e: NoiseSessionException) { return@withLock }   // can't re-load key → skip; transport unaffected
        try {
            val initBytes = session.writeRekeyInit(s)   // session.pendingRekey now set
            rekeyInFlight = true                         // set BEFORE the send; resp can only arrive after
            transport.send(InnerFrameV2(type = TYPE_NOISE_INIT, data = base64StdEncode(initBytes)))
        } catch (e: IllegalStateException) {
            // racing teardown closed the session, or a session-level re-key is already in flight — skip.
        } finally {
            s.fill(0)   // zero the device-static copy regardless of outcome (mirrors create())
        }
    }
}
```

Why each line: **(a)** the `rekeyMutex` makes the two initiation paths (timer coroutine vs the
`rekey_request` handler) mutually exclusive, so `writeRekeyInit`'s `check(pendingRekey == null)` can never
trip from a double-initiate; the second caller sees `rekeyInFlight == true` and coalesces — the mobile
analog of Go #450's `skipped_already_awaiting` branch. **(b)** `rekeyInFlight = true` is set under the
mutex and before the `noise_init` is sent, so by wire causality (the `noise_resp` cannot arrive before the
server has read the `noise_init`) plus `@Volatile` visibility, the open-state collector always observes
`true` when the matching `noise_resp` arrives. **(c)** there is **no suspension point** between
`reloadDeviceStaticKey()` returning and the `finally`, so cancellation (teardown) cannot skip `s.fill(0)`;
and `session.close()` in teardown independently wipes the session's own copy of `s` inside `pendingRekey`
(belt-and-suspenders). The re-key `noise_init` is **not** taken under `outboundLock`: it carries no
transport-AEAD payload, so it is outside the wire-order==nonce-order invariant `outboundLock` protects,
and holding that lock across the suspend `reloadDeviceStaticKey()` would needlessly couple it to outbound.

### Trigger 1 — the session-scoped 1-hour timer (one-shot, re-armed per handshake)

A `rebaseRekeyTimer()` cancels any existing timer job and launches a fresh one-shot delay in the pump
scope:

```kotlin
private fun rebaseRekeyTimer() {
    rekeyTimerJob?.cancel()
    rekeyTimerJob = scope.launch { delay(rekeyIntervalMs); initiateRekey() }
}
```

Called from exactly two points, both in the drive coroutine (so `rekeyTimerJob` is single-threaded):
- in `drive()`, immediately after `mutableState.value = PumpState.Open(connId)` (initial arm);
- in `onOpenFrame`, after a **successful** `readRekeyResp` (re-base from the swap moment).

This satisfies *"re-based by each completed (re-)handshake."* The timer is one-shot — it is re-armed only
by a completed handshake, mirroring Go #450's `armRekeyTimer` ⇄ `rekeyComplete`. Scope ownership
(`scope.cancel()` in `teardown`) cancels it on close → no leaked coroutine (AC 4).

### Trigger 2 — the inbound `rekey_request` (intercepted in `onOpenFrame`'s `noise_msg` branch)

Extend the `TYPE_NOISE_MSG` branch: after the existing decrypt + `Envelope` parse, discriminate on
`type`. A `rekey_request` is a **control** message — it is **not forwarded** to `inboundChannel` (the
domain consumer #278 must not see transport-control envelopes; and `inbound` is single-consumer, so the
interception happens at the producer, not via a second collector):

```kotlin
val envelope = MobileJson.decodeFromString<Envelope>(plaintext.decodeToString())
if (envelope.type == TYPE_REKEY_REQUEST) {
    scope.launch { initiateRekey() }   // non-blocking: don't stall the collector on the keystore re-load
} else {
    inboundChannel.send(envelope)
}
```

**The payload is intentionally not decoded.** Discriminating on `type` alone trivially satisfies AC 2's
forward-compat clause — an unrecognised, absent, or extra `reason` value cannot crash a code path that
never reads it (`MobileJson` is `ignoreUnknownKeys = true` anyway). Should a future ticket need
`reason`-specific behavior (e.g. `compromise` → force re-pair), it decodes tolerantly then; YAGNI here.

### Trigger 3 (completion, not a trigger) — routing the re-key `noise_resp` (the `else` seam)

Add a `TYPE_NOISE_RESP` branch to `onOpenFrame`'s `when`, **before** the `else`:

```kotlin
TYPE_NOISE_RESP -> {
    val session = this.session ?: throw NoiseSessionException("session is not available")
    if (!rekeyInFlight) throw NoiseSessionException("unexpected noise_resp with no re-key in flight")
    session.readRekeyResp(base64StdDecode(frame.data))   // MAC failure → NoiseSessionException → teardown
    rekeyInFlight = false
    rebaseRekeyTimer()
}
```

A `noise_resp` with no re-key in flight is a protocol violation → teardown (consistent with the existing
`else`). A `readRekeyResp` MAC failure (forged/rotated `rs` / MITM) throws `NoiseSessionException`, which
the `drive()` collector's existing `try/catch` routes to `teardown(e)` — see § Error handling for the
rationale. The `else` branch is **unchanged** (still tears down on any genuinely unknown type).

### Data flow

```
timer:        Open ─▶ rebaseRekeyTimer ─(delay 1h)─▶ initiateRekey ─[rekeyMutex]─▶ writeRekeyInit(s) ─▶ transport.send(noise_init)
rekey_request: relay ─(noise_msg)─▶ onOpenFrame decrypt ─▶ Envelope(type=rekey_request) ─▶ scope.launch{ initiateRekey } (NOT forwarded)
completion:   relay ─(noise_resp)─▶ onOpenFrame ─▶ readRekeyResp(swap+wipe old) ─▶ rekeyInFlight=false ─▶ rebaseRekeyTimer
```

## State + concurrency model

- **No new scope; no new `StateFlow`.** Every coroutine (the timer's one-shot delay, each launched
  `initiateRekey`) is a child of the pump's existing `CoroutineScope(SupervisorJob() + dispatcher)` and is
  cancelled by `teardown`'s `scope.cancel()`. Nothing outlives the connection (AC 4).
- **`rekeyMutex` is the only new lock; it does not nest with `outboundLock` or the session monitor.**
  `initiateRekey` holds `rekeyMutex` and calls `writeRekeyInit` (which takes the session's own
  `@Synchronized` monitor); no path takes these in the opposite order, and the re-key `noise_init` send is
  outside `outboundLock` — so no lock-ordering cycle exists. Single mutex, no ordering hazard.
- **`rekeyInFlight` access pattern (the invariant code-review must verify):** set `true` only inside
  `rekeyMutex`, only after `writeRekeyInit` succeeds, before the `noise_init` send; set `false` only in
  the drive coroutine on a completed `readRekeyResp`; read in `initiateRekey` (under the mutex) and in
  `onOpenFrame` (drive coroutine). The only multi-writer-of-`true` race (timer vs `rekey_request`) is
  fully serialized by the mutex. The completion read in `onOpenFrame` is safe by wire causality +
  `@Volatile`. A stale-`true` coalesce (a fresh trigger arriving as a re-key just completed) at worst
  skips one re-key — the re-based timer (and the server's symmetric nudge) re-cover it; no correctness loss.
- **The CipherState swap atomicity is #303's, not the pump's.** `readRekeyResp`, `encrypt`, `decrypt` are
  all `@Synchronized` on the session, so the swap cannot interleave with an in-flight same-direction AEAD
  op — there is no mixed-key / nonce-reuse window. The pump adds no locking around the swap. Inbound
  ordering across the swap is guaranteed structurally by the single sequential collector: all frames
  before the `noise_resp` decrypt on old keys, all after on new keys.
- **Shutdown safety.** A re-key abandoned mid-flight (process death / teardown between `writeRekeyInit`
  and `readRekeyResp`) leaks nothing: `teardown` → `session.close()` wipes `pendingRekey` (and its copy of
  `s`), and `scope.cancel()` cancels any launched `initiateRekey` and the timer. The pump persists
  nothing.

## Error handling

The pump continues to emit **no logs**; every fault surfaces only via `PumpState.Closed(cause)` with a
category-only `NoiseSessionException` message (#303/#309 posture). New messages this ticket mints
(`"unexpected noise_resp with no re-key in flight"`) stay category-only — never interpolate frame bytes,
plaintext, or `s`.

| Failure | Source | Result |
|---|---|---|
| Re-key `noise_resp` MAC failure (rotated `rs` / MITM / tampered) | `readRekeyResp` → `NoiseSessionException` | **teardown** → `Closed(cause)`; session wiped (see below) |
| `noise_resp` arrives with no re-key in flight | `if (!rekeyInFlight)` guard | **teardown** (protocol violation, like the `else`) |
| Device key re-load fails ("not paired" / key unavailable) | `reloadDeviceStaticKey` → `NoiseSessionException` | re-key **skipped**; transport continues on current keys; not fatal |
| `writeRekeyInit` throws (racing teardown / session-level re-key already in flight) | `IllegalStateException` | re-key **skipped**; no `rekeyInFlight` set |
| `rekey_request` with unknown/absent/extra `reason` | not decoded | re-key initiated normally; **cannot** crash (AC 2) |
| Pump closes mid-re-key | `teardown` / `scope.cancel()` | timer + launched job cancelled; `s` zeroed; `pendingRekey` wiped (AC 4) |

**Why teardown on a re-key `noise_resp` MAC failure (not retain-and-continue).** #303's `readRekeyResp`
fail-RETAIN guarantees the *session mechanism* does not corrupt itself (old keys intact, re-key
retryable) — it leaves the **policy** to the pump. In the open state the only legitimate `noise_resp` is
one completing the re-key we initiated against the pinned `rs`; a MAC failure means the responder is **not**
the pinned `rs` (rotated key or relay-operator MITM). Tearing down — letting #307 reconnect with a fresh
full handshake — is the safe response and mirrors the pump's existing "an undecryptable frame tears the
session down" rule (#309: the ordered encrypted stream cannot skip a frame). It is also the simplest
(no retry/backoff state). The fail-RETAIN is still load-bearing: it keeps the old keys valid right up to
`teardown`'s `session.close()` wipe, so no half-swapped state is ever observable.

**Why no phone-side re-key reply timeout** (unlike Go #450's 30 s window). The Go binary's reply window
covers its *different* role — awaiting the **phone's** `noise_init` after it *nudges* via `rekey_request`.
The phone is the initiator; after it sends a re-key `noise_init`, a missing `noise_resp` cannot strand the
session on a reliable ordered WS/TCP transport: either the connection stays up and the server answers
(swap completes) or closes on its own re-key failure (#453 closes at 4426) → `inbound` completes →
teardown → #307 reconnect; or a lost/late swap desyncs the keys and the **next** AEAD frame (in either
direction) MAC-fails → teardown → reconnect. All paths converge on teardown→reconnect without an explicit
timer. Adding one is deferred as unneeded absent an observed stuck-session (evidence-based fix selection);
noted in Open questions.

## Testing strategy

Pure-JVM unit tests (`./gradlew test`) extending `NoiseSessionPumpTest.kt` — same `runTest` virtual-clock
posture, `FakeRelayTransport`, store fakes, and real `TestResponder` as #309. No device/instrumented test.

**Harness extension (the bulk of the work):** give the pump test's `TestResponder` a re-key leg by
copying `rekey(init): RekeyLeg` + `RekeyLeg` from `NoiseIkSessionTest.kt:415-434` (retain
`staticPrivateKey` as that file does at :379-380,386-387 so the re-key handshake re-uses the responder's
own static and the pinned-`rs` continuity holds). Add a helper that produces a re-key `noise_resp` frame
(`InnerFrameV2(type = "noise_resp", data = base64StdEncode(rekeyLeg.resp))`) and one that mints a
**different-static** re-key `noise_resp` (the `foreignRekeyResp()` shape at `:348-354`) for the MITM test.
Inject a small `rekeyIntervalMs` (e.g. 20 ms) so `advanceTimeBy`/`advanceUntilIdle` drives the timer.

**Scenarios (bulleted; developer writes the bodies in the file's idiom):**

- *AC 1 — timer fires → re-key `noise_init`.* `openSession`; `advanceTimeBy(rekeyIntervalMs)` → exactly
  one new frame is sent, `type == "noise_init"`, and the responder's `rekey(...)` accepts it (recovering
  empty early-data). `state` stays `Open` (transport not paused).
- *AC 1 + 3 + 5 — timer re-key completes, round-trips on new keys.* After the timer fires and the
  responder produces the re-key `noise_resp`, push it → `readRekeyResp` swaps; then a `noise_msg` from the
  **new** responder pair decrypts to an `Envelope` on `inbound`, and `pump.send(env)` decrypts under the
  **new** responder receiver — proving traffic continues on the new CipherStates with no `Closed`.
- *AC 4 — timer is re-based by the completed re-key.* After a completed re-key, advancing by
  `rekeyIntervalMs` again triggers a **second** `noise_init`; advancing by less does not. (Pins
  "re-based by each completed handshake.")
- *AC 2 — inbound `rekey_request` triggers re-key.* `openSession`; push a responder-encrypted `noise_msg`
  whose plaintext is `Envelope(type = "rekey_request", payload = {"reason":"scheduled"})` → a `noise_init`
  is sent; the `rekey_request` envelope is **not** emitted on `pump.inbound` (assert the collector
  received nothing). Complete the re-key and assert a round-trip on new keys.
- *AC 2 — forward-compat `reason`.* A `rekey_request` with `reason` absent (`payload = {}`), an unknown
  value (`{"reason":"future"}`), and extra fields (`{"reason":"scheduled","x":1}`) each trigger re-key
  identically and never `Closed` — pins "read tolerantly, not fatal."
- *AC 3 — re-key `noise_resp` routed via the seam, not torn down.* After initiating a re-key, a valid
  re-key `noise_resp` completes it (`state` stays `Open`); **no** `rekey_ack`/any outbound envelope is
  sent in response (assert `sentFrames` gains only the `noise_init`, nothing after the `noise_resp`).
- *MITM / rotated `rs` → teardown, old keys never half-swapped.* After initiating a re-key, push a
  `foreignRekeyResp()`-shaped `noise_resp` → `state` is `Closed(cause is NoiseSessionException)`,
  transport closed, no crash.
- *Stray `noise_resp` (no re-key in flight) → teardown.* In `Open` with no re-key initiated, push a
  `noise_resp` → `Closed` (the guard branch; matches the pre-#304 behavior the `else` gave).
- *AC 4 — no leaked coroutine / key hygiene.* After `close()` (or transport `Down`) mid-re-key,
  `advanceUntilIdle()` leaves no active coroutine (the existing `collector.isCompleted` + a
  no-active-jobs check), and a subsequent `send` returns `false` (session wiped).

## Open questions

- **Phone-side re-key reply timeout.** Deliberately omitted (see Error handling). If a stuck re-key is
  ever observed in the field, a bounded `withTimeoutOrNull`-style re-key reply window (the mobile analog
  of Go #450's 30 s) is the fix — deferred until evidence exists.
- **`reason`-specific behavior.** `compromise` may eventually warrant more than a re-key (e.g. force
  re-pair / surface a security banner). Out of scope; the type-only discriminator leaves a clean seam to
  decode `reason` when a consumer needs it.
- **#302 wiring.** The pump still ships dormant; #302 (lifecycle coordinator) constructs it per
  connection and passes the default `rekeyIntervalMs`. Unchanged by this ticket.

## Sizing

**S — confirmed** (PO sized S; not overriding). Production source files modified: `NoiseSessionPump.kt`
+ `NoiseSessionFactory.kt` = **2** (< 5). New exported types / public classes / interfaces / composables:
**0** (new private fields + one public method on the existing factory + one defaulted ctor param + two
companion consts). Consumer call sites needing simultaneous update: **0** (the ctor param is defaulted;
the sole construction site is the test; `reloadDeviceStaticKey` is additive — confirmed via
`codegraph_callers NoiseSessionPump` → none, and the branch-overlap scan → none). Distinct
error/reject branches: **4** (`noise_resp`-no-rekey, `readRekeyResp` MAC fail, key-reload fail,
`writeRekeyInit` `IllegalStateException`) (< 10). Total written: ~50 production LOC (pump) + ~10
(factory) + ~180 test LOC (responder re-key leg + ~9 scenarios) ≈ **~240** (≤ ~600). No new dependency
(noise-java vendored; `kotlinx.coroutines.sync.Mutex` already in use). No DI change. Within every red
line; ships as one ticket.

## Security review

**Verdict:** PASS

The asset is the phone's encrypted transport across a re-key driven by *triggers*: the device static
private key `s` (re-obtained per re-key), the new/old transport CipherStates, and the confidentiality +
integrity of traffic across the rotation and across the trigger surfaces. An adversary's goals: widen the
`s` RAM window, MITM the re-key to swap in attacker keys, induce a nonce-reuse window during the swap,
weaponise the inbound `rekey_request` or the `noise_resp` seam, leak a coroutine, or freeze rotation. Each
category was walked adversarially against the final spec.

**Findings:**

- **[1 Trust boundaries]** No MUST FIX. This ticket adds **two** untrusted→trusted crossings, both
  explicit and single-function. (a) The re-key `noise_resp` bytes (`onOpenFrame` `TYPE_NOISE_RESP` →
  `session.readRekeyResp`) — attacker-controllable bytes from a possibly-hostile relay, authenticated by
  the pinned-`rs` IK handshake; a forged/rotated `noise_resp` MAC-fails → teardown (no swap to attacker
  keys). (b) The inbound `rekey_request` — but it arrives **already AEAD-authenticated** (#309 emits an
  `Envelope` only after `session.decrypt`), so only the trusted peer can nudge a re-key, and the action it
  nudges (re-key) is benign. The ticket also **reduces** the trust surface downstream: `rekey_request` is
  a control envelope intercepted at the producer and **not** forwarded to #278, so #278 never holds
  transport-control data. No finding.

- **[2 Tokens, secrets, credentials]** No MUST FIX — and this is the security-load-bearing design choice.
  `s` is re-loaded per re-key via `NoiseSessionFactory.reloadDeviceStaticKey()`, handed to `writeRekeyInit`
  (which copies it into the pending handshake), and **zeroed in a `finally`** with no suspension point
  between the load and the wipe — so cancellation/teardown cannot skip it, and `session.close()`
  independently wipes the session's copy inside `pendingRekey`. The bounded device-key RAM window #298/#303
  fought for is preserved across every hourly re-key (retaining `s` on the pump would have been the
  MUST-FIX this design avoids). **Crucially, the coalesce check precedes the keystore re-load** (`if
  (rekeyInFlight || …) return@withLock` is the first statement under the mutex), so a flood of coalesced
  `rekey_request` nudges never even loads `s` — no extra RAM exposure, no amplification. The hello token
  is untouched (re-key carries empty early-data); `reloadDeviceStaticKey` reads only `serverId` from the
  loaded `PairedServer` (whose `toString` redacts the token). *Code-review must verify the `finally {
  s.fill(0) }` and the coalesce-before-reload ordering.*

- **[3 File / storage]** N/A by design — this ticket performs no file/storage I/O. `reloadDeviceStaticKey`
  delegates to `DeviceStaticKeyStore` (#291, Keystore-wrapped, its own check-then-generate mutex) and
  `PairedServerStore` (#294), both separately reviewed. No new path construction, TOCTOU, at-rest
  artifact, or `allowBackup` surface.

- **[4 Inter-process / Android attack surface]** N/A by design — pure `data/network` orchestration over
  portable interfaces, **zero `android.*`**. No exported component, deep link, `PendingIntent`,
  `ContentProvider`, or `WebView`. The new factory method is `suspend` over existing portable store
  interfaces (the Keystore impls are the platform custodians behind those interfaces — the documented
  data-layer exception).

- **[5 Cryptographic primitives]** No MUST FIX — the ticket **hand-rolls no crypto**. The re-key is the
  spike-proven `Noise_IK_25519_ChaChaPoly_BLAKE2s` via #303 (`writeRekeyInit`/`readRekeyResp`): fresh
  `HandshakeState` per re-key, empty prologue, `ad = null`, fresh CipherStates with nonce counters reset
  to 0 — all inherited. The pump uses **no RNG**. Peer-static continuity is enforced *cryptographically*
  (re-key against the pinned `rs`; a different `rs` MAC-fails), so there is no attacker-value-vs-secret
  byte compare on this path and thus no constant-time-compare obligation. The one nonce-discipline subtlety
  this ticket introduces — sending the re-key `noise_init` **outside** `outboundLock` — is correct: the
  `noise_init` carries no transport-AEAD payload and so is outside the wire-order==nonce-order invariant
  `outboundLock` protects; the protocol explicitly permits app traffic to interleave with the re-key
  handshake. No finding.

- **[6 Network & I/O]** No MUST FIX — no socket opened; frame-size caps are #306's (inherited, not
  lifted); re-key frames are bounded by IK msg overhead. The `rekey_request`-nudge rate is bounded: the
  coalesce caps concurrency to one in-flight re-key, and a fresh re-key requires a full round-trip the
  server itself must complete (equal work, no amplification) — the mobile analog of Go #450's "the cadence
  + skip-already-awaiting naturally cap; no rate-limit needed." The deliberately-omitted phone-side re-key
  reply timeout (§ Error handling) is the one place a malicious authenticated server could *stall* a re-key
  to **freeze key rotation** (`rekeyInFlight` stuck true) — but that requires the already-trusted peer who
  **already holds the session keys**, so freezing rotation grants it no new capability; on any non-malicious
  stall the next AEAD frame (either direction) desyncs → teardown → #307 reconnect. The reply-timeout is
  therefore a tidiness item, not security-load-bearing; deferred (evidence-based). No finding.

- **[7 Error messages, logs, telemetry]** No MUST FIX — the pump still emits **no logs** and no telemetry.
  The one new minted message (`"unexpected noise_resp with no re-key in flight"`) is a constant
  category-string; `readRekeyResp`'s messages are #303's category-only strings; `reloadDeviceStaticKey`
  wraps failures as category-only `NoiseSessionException`. No path interpolates `frame.data`, decrypted
  plaintext, or `s` into a message. *Code-review must verify no new thrown message interpolates frame
  bytes / plaintext / key material, and that a caller later logging `cause` inherits the category-only
  guarantee.*

- **[8 Concurrency]** No MUST FIX — the category this ticket most stresses, walked exhaustively in § State
  + concurrency. Every coroutine (the one-shot timer, each launched `initiateRekey`) is a child of the
  pump's connection scope and is cancelled by `teardown`'s `scope.cancel()`; the AC-4 no-leaked-coroutine
  test pins it. `rekeyMutex` serialises the two initiation paths so `writeRekeyInit`'s
  `check(pendingRekey == null)` can never trip from a double-initiate; `rekeyInFlight` is set `true` under
  the mutex **before** the `noise_init` send, so the completion read in the (single, sequential) collector
  is safe by wire causality + `@Volatile`. There is **one** new lock and it does not nest with
  `outboundLock` or the session monitor → no lock-ordering hazard. The CipherState swap's atomicity (no
  mixed-key / nonce-reuse window) is #303's `@Synchronized`, not re-implemented here. The key-wipe is not
  behind a cancellable suspension. *Code-review must verify: the key-wipe `finally` has no preceding
  suspension; `rekeyInFlight` is set before the send; the coalesce precedes the reload.*

- **[9 Threat model alignment]** Aligned with `protocol-mobile.md` § Security model + ADR 024 § Re-key
  policy. **Relay-operator MITM on re-key** (high→low, cryptographic) — a `noise_resp` not from the pinned
  `rs` MAC-fails → teardown (the pump's teardown-on-rekey-failure policy *uses* #303's fail-RETAIN, which
  keeps the old keys valid right up to the `session.close()` wipe so no half-swapped state is observable).
  **Downgrade** — blocked (hardcoded suite, fresh handshake, no negotiation). **Replay** — fresh per-re-key
  keys + old-key-frame-MAC-fail (#303 AC4); a replayed authenticated `rekey_request` only triggers a
  bounded re-key, no gain. **Mobile-specific threats** (screenshot/overlay/accessibility/keyboard) — N/A,
  headless data-layer with no UI/input. **Out of scope, named:** re-key rate-limiting beyond coalescing
  (Go #450 posture); the phone-side reply timeout (deferred, not security-load-bearing); `reason`-specific
  handling incl. `compromise` (future); #302 lifecycle wiring; a `4421`/`4426`-carrying protocol-violation
  close code (transport-surface extension, #309 open question — the pump closes normally and #307
  reconnects).

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-05-31
