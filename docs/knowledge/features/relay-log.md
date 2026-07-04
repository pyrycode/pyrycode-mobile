# Relay diagnostic log (`RelayLog`)

A **debug-gated, redaction-safe diagnostic logger for the relay transport**
(`data/network/RelayLog.kt`, [#521](../codebase/521.md), split from #500). It exists so the next
connection-diagnosis session doesn't repeat the "add raw `Log.i`/`Log.w` → diagnose → remember to
strip" churn. During the 2026-07-03 cross-repo review, ad-hoc `PYRYDBG` logs on
`wip/mobile-connection-diagnosis` nearly shipped the relay host, live `conn_id`, close cause/reason,
pump state, and negotiated capabilities to **production** logcat — because R8 strips `Log.d`/`Log.v`
from release but **keeps `Log.i`/`Log.w`**, which the near-miss reached for. `RelayLog` is the
**safe-by-construction** replacement: a tool that *cannot* reach release output and *cannot* assemble
a sensitive value in release, so the guarantee doesn't depend on anyone remembering to revert.

This ticket ships the **facility only**. It does **not** instrument
[`RelayConnectionSupervisor`](relay-reconnect-supervisor.md) /
[`RelayRepositoryCoordinator`](relay-repository-coordinator.md) /
[`NoiseSessionPump`](noise-session-pump.md) / [`OkHttpRelayTransport`](relay-ws-transport.md), which
keep their **"Emits no logs"** contract. There is no observed need for standing diagnostic logging
(Evidence-Based Fix Selection) — the facility is *ready-to-use, not pre-installed*. A future diagnosis
session or follow-up ticket adopts it at the points it needs.

## What it does

```kotlin
object RelayLog {
    fun d(message: () -> String)   // DEBUG priority
    fun i(message: () -> String)   // INFO  priority
    fun w(message: () -> String)   // WARN  priority
    fun redactConnId(connId: String): String   // 8-char hex correlation token
}
```

One top-level type (`object RelayLog`) — ktlint `standard:filename` compliant (`redactConnId` and the
`enabled`/`sink` seams are **members**, not second top-level declarations). Zero consumer changes, no
new dependency (reuses the already-vendored `Blake2sMessageDigest`; `BuildConfig` already generated
for this module). Synchronous static helper: no coroutine, `Flow`, scope, or dispatcher — nothing to
cancel or shut down, no failure surface (`redactConnId` is pure and total for any `String`).

## How it works — two safety axes

Each maps to an acceptance criterion:

1. **Cannot reach release output.** Emission is guarded by an explicit `if (enabled)` where
   `enabled` defaults to the compile-time `BuildConfig.DEBUG` constant — `false` in a release build,
   so the branch is never taken. The gate is a **real build check**; it deliberately does **not**
   rely on R8 stripping `Log.d`/`Log.v` (which the `Log.i`/`Log.w` near-miss defeated). There is no
   UI toggle and no production setter, so nothing flips it on in a release build.
2. **Cannot assemble a sensitive value in release.** The message is a `() -> String` lambda invoked
   only *inside* the `if (enabled)` branch — in release the string is never built and no
   potentially-sensitive value is ever assembled.

`d` / `i` / `w` give the next diagnosis the same `Log.i`/`Log.w` vocabulary through the safe gate; the
level only sets the logcat priority (safety is uniform — all three gate identically over one shared
code path).

### Seams (test-only) and the deliberate non-`inline` choice

```kotlin
internal var enabled: Boolean = BuildConfig.DEBUG                 // the gate
internal var sink: (Int, String, String) -> Unit =              // emission seam
    { priority, tag, message -> android.util.Log.println(priority, tag, message) }
```

- The methods are **non-`inline` on purpose.** A public `inline fun` would force the `internal` seams
  to `@PublishedApi internal`; keeping them non-inline leaves the seams clean and still gives the full
  "string-not-built-when-disabled" guarantee — the lambda object is a cheap capture that is discarded
  unless invoked. **The runtime gate, not inlining, is the load-bearing guarantee.** Do not add
  `inline` (Simplicity First).
- `enabled`/`sink` are `object`-level mutable vars used **purely as test seams**. In production they
  are written once at class init and never reassigned → effectively final at runtime, safe publication
  via class init, no data race, **no `@Volatile` needed**. Only single-threaded unit tests mutate them.
- `android.util.Log` in `data/` is sanctioned here (this is the platform-logging custodian; the
  `data/`-portability MUST-FIX trigger is `Context`-shaped only).

## Redaction — `redactConnId`

```kotlin
// full 256-bit BLAKE2s digest of the UTF-8 bytes, first REDACT_BYTES (=4) as lowercase hex
Blake2sMessageDigest().digest(connId.toByteArray(Charsets.UTF_8)).toHexString(0, REDACT_BYTES)
```

Mirrors the digest-then-truncate idiom of [`staticKeyFingerprint`](static-key-fingerprint.md).
`REDACT_BYTES = 4` → an **8-char** lowercase-hex token (`^[0-9a-f]{8}$`).

- **Deterministic** — same `conn_id` → same token, so log lines from one connection correlate within
  a diagnosis session.
- **Structurally never equals the input** — a fixed-width digest of an arbitrary-length string can't
  equal a real `conn_id`. This satisfies "not the full input" for **every** input, including short or
  empty ones (**no edge-case guard needed** — the reason to prefer hashing over `connId.take(8)`
  prefix-truncation, which reveals real bytes *and* returns the whole value for a short id).
- **Reveals zero bytes of the live `conn_id`** — the daemon-issued id is a session-correlatable
  identifier we don't want scrapable from logcat; preimage recovery from 32 bits is infeasible.

**Width rationale — read before flagging.** The 4-byte truncation is a *correlation token*, **not**
the MITM static-key fingerprint (`staticKeyFingerprint`, which is load-bearingly **≥8 bytes** for
brute-force resistance). That is an *authentication* requirement; no adversary is forging a `conn_id`
token here. A correlation token only needs enough width to avoid casual collisions across the handful
of connections in one debug session — 2³² is ample. **Do not "align with the fingerprint" and bump
this to 8 bytes.** (See [ADR 0004](../decisions/0004-vendor-noise-java-crypto.md) for the digest
source and the fingerprint's separate width contract.)

## MUST-NOT-log contract (KDoc on `RelayLog`)

The object's KDoc carries, as a first-class contract, what a caller must **never** pass into a message
lambda: the relay **host / URL**, the **pairing token**, **raw key material** (static/ephemeral keys,
handshake transcript), the **full `conn_id`** (use `redactConnId`), or **message payloads / bodies**.
**Safe to log:** event type, `redactConnId` token, pump state *name*, close code, capability *names*.
This promotes the existing "a stray `Log.d($secret)` must not leak this" comments on
`PairedServer.toString` and `HelloClientPayload.toString` into the logger's own doc, visible at every
future call site. Statically preventing a caller from interpolating a secret into a free-form lambda
is not code-enforceable — code-review of the **adoption** ticket is the belt to this documented
suspenders.

## Usage

Not yet wired anywhere (adoption is a future ticket — see Scope above). When a diagnosis session
adopts it:

```kotlin
RelayLog.i { "relay open ${RelayLog.redactConnId(connId)} caps=${caps.joinToString()}" }
```

To view: `adb logcat -s RelayLog` (the tag is a fixed constant, never derived from any value). No
runtime enable path exists by design — a diagnosing engineer runs a **debug** build, where
`enabled` is already `true`.

## Testing

Unit only — `./gradlew testDebugUnitTest --tests "de.pyryco.mobile.data.network.RelayLogTest"`
(bare `./gradlew test` is an aggregate that rejects `--tests`). The **`sink` seam is what makes the
enabled path testable on plain JVM**: the module has no Robolectric and no `unitTests.returnDefaultValues`,
so real `android.util.Log.*` throws `"not mocked"`; tests install a capturing lambda instead.
`BuildConfig.DEBUG == true` on the `testDebugUnitTest` classpath, so the disabled-path test flips
`enabled = false` explicitly, and `@After` restores both seams so `object`-global state can't leak
across tests. Referencing the `Log.DEBUG`/`INFO`/`WARN` `int` constants is safe — the compiler inlines
them, so the `Log` class is never loaded.

## Edge cases and limitations

- **Free-form lambda can still carry a secret if a caller writes one.** Not statically preventable;
  mitigated by the MUST-NOT-log KDoc + adoption-ticket code review. The facility itself never
  assembles or writes a secret, and the *release* leak (the actual near-miss) is fully closed by the
  gate.
- **If a crash reporter (Crashlytics/Sentry) is added later, it must honor the same gate.** No crash
  reporter is in the emission path today (sink → `android.util.Log` only); named here so it isn't
  forgotten by whoever adds one.
- **Deterministic same-id → same-token is intended**, not a leak — it is the (debug-only) correlation
  property. Preimage recovery of the `conn_id` from an 8-char token is infeasible.

## Related

- [Static-key fingerprint](static-key-fingerprint.md) — the digest-then-truncate sibling
  `redactConnId` mirrors; note the **different, deliberate** width (correlation ≥ 4 bytes here vs.
  authentication ≥ 8 bytes there)
- [ADR 0004 — vendor `noise-java`](../decisions/0004-vendor-noise-java-crypto.md) — source of the
  vendored `Blake2sMessageDigest`; no new dependency
- [Relay WebSocket transport](relay-ws-transport.md), [Noise session pump](noise-session-pump.md),
  [Relay reconnect supervisor](relay-reconnect-supervisor.md),
  [Relay repository coordinator](relay-repository-coordinator.md) — the four "Emits no logs" files
  this facility is *ready to* instrument but does **not** touch in #521 (adoption is a future ticket)
- [Paired server store](paired-server-store.md) — home of the `PairedServer.toString`
  never-log-secret comment this facility's contract generalizes
- Ticket: [#521](../codebase/521.md) — implementation notes. Split from #500; sibling #522 (transport
  frame-reject tests)
