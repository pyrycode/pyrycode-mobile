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

\#521 shipped the facility with **zero adoption** — there was no observed need yet for standing
diagnostic logging (Evidence-Based Fix Selection); it was *ready-to-use, not pre-installed*.
[#1039](https://github.com/pyrycode/pyrycode-mobile/issues/1039) adopted it at the two points a live
e2e connection-drop investigation needed: [`OkHttpRelayTransport`](relay-ws-transport.md) now writes one
`event=transport_end` line per connection end, and [`NoiseSessionPump`](noise-session-pump.md) one
`event=pump_teardown` line per teardown — see § Adopted call sites below for the fixed labels.
[`RelayConnectionSupervisor`](relay-reconnect-supervisor.md) and
[`RelayRepositoryCoordinator`](relay-repository-coordinator.md) still keep the **"Emits no logs"**
contract: the transport line already carries the `Down` code the supervisor sees.

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

Adopted widely since #521 as the app's general debug-event logger, not only for connection diagnosis —
`RelayLog.d` calls now live across the ViewModels, `di/`, and several `data/repository/` transfers,
including [`DebugBundleTransfer`](relay-debug-bundle-transfer.md) and the [attachment
upload](attachment-upload.md)'s `AttachmentUploadTransfer` (#829). Two of the four relay-transport files
this ticket named as future adoption targets — [`OkHttpRelayTransport`](relay-ws-transport.md) and
[`NoiseSessionPump`](noise-session-pump.md) — adopted it in #1039 (§ Adopted call sites below);
[`RelayConnectionSupervisor`](relay-reconnect-supervisor.md) and
[`RelayRepositoryCoordinator`](relay-repository-coordinator.md) still emit no logs.

## Adopted call sites (#1039)

`OkHttpRelayTransport.terminate` — the CAS every connection end funnels into — writes exactly one line
before it sends the terminal `Down`:

`event=transport_end end=<label>[ code=<n>][ peer_closing=<n>][ cause=<SimpleClassName>]`

| `end` | Source |
|---|---|
| `local_close` | the app's own `close()` |
| `peer_close` | a clean `onClosed` |
| `failure` | `onFailure` (dial refused, TLS, or the peer dropping TCP before the close handshake finishes) |
| `protocol_violation` | a local reject of a malformed / oversized / binary relay frame |
| `invalid_request` | a malformed stored `relayUrl` or header-injection attempt caught in `connect()` |

`code` is the peer's close code, the `onFailure` HTTP status when there is one, or the local close code a
protocol-violation reject sent. `peer_closing` carries the code `onClosing` recorded when the end was not
itself a clean `peer_close` — without it, a relay `1011` close followed by the peer dropping TCP would
log as a bare `failure`, indistinguishable from an ordinary network blip.

`NoiseSessionPump.teardown` — the CAS every pump ending funnels into — writes exactly one line before it
sets `Closed`:

`event=pump_teardown trigger=<label>[ cause=<SimpleClassName>]`

| `trigger` | Funnel |
|---|---|
| `session_create_failed` | `sessionFactory.create()` threw |
| `handshake_deadline` | no frame arrived before the `noise_resp` deadline |
| `handshake_transport_down` | the transport went `Down` before any frame arrived |
| `handshake_wrong_first_frame` | the first inbound frame was not `noise_resp` |
| `handshake_resp_rejected` | `readResp` / base64 threw (MAC failure, malformed `hello_ack`, bad base64) |
| `open_decrypt_failed` | a `noise_msg`'s base64 decode or AEAD decrypt threw |
| `open_parse_failed` | the decrypted plaintext failed to parse as an `Envelope` |
| `open_unexpected_frame_type` | an open-state frame was neither `noise_msg` nor `noise_resp` |
| `open_unexpected_noise_resp` | a `noise_resp` arrived with no re-key in flight |
| `rekey_resp_rejected` | the re-key `readRekeyResp` threw |
| `open_frame_failed` | any other exception out of the open-frame collector |
| `rekey_deadline` | the bounded re-key response watchdog fired |
| `transport_down` | the transport's `inbound` completed cleanly |
| `close` | the app's own `close()` |

In both lines, `cause` is the exception's class name, never its message (a message can carry frame
content); neither line ever carries the peer's close reason text, the relay URL, the server id, or the
token. Level is `i` for `local_close` / `peer_close` and for a `null`-cause pump teardown, `w` otherwise.
The pump's own `transport.close()` inside `teardown` then shows up in the transport's line as a local
`1000` close; the pump line immediately before it is what explains that local close.

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

## Message trail — a separate, release-kept facility (`MessageTrail`, #1564)

`MessageTrail` (`data/diagnostics/MessageTrail.kt`) is **not** a `RelayLog` adoption. It is a distinct
facility that records, for every message this device sends through `MessageCommands.sendMessage`, one
timestamped line per state the phone itself observes, keyed by `message_id`. It exists because the daemon
can prove only that a message never arrived; only the phone can say whether it kept the message, failed to
send it, or sent it on a connection that then dropped.

**Kept in release builds, on purpose (decided 2026-10-02, Juhana).** The Play test-track release build is
the only build where these bugs show up, and `RelayLog`'s `BuildConfig.DEBUG` gate is by construction — see
§ How it works above. Flipping that gate on would release every other `RelayLog` line, so the trail is a
second, separate channel instead, and `RelayLog`'s gate is untouched. Because this project runs unit tests
only in the debug variant (no `testReleaseUnitTest` task) and `BuildConfig.DEBUG` is a compile-time constant
Kotlin inlines away, the release proof is a source check, not a release-build test run: nothing in
`MessageTrail.kt`, `MessageCommands.kt`, `ThreadProjection.kt`, `RelayRequests.kt` or the trail's `AppModule`
binding reads or names `BuildConfig`, and a test asserts the trail still writes with `RelayLog.enabled` held
at `false`.

It is safe in release the same way `RelayLog`'s redaction is safe — by taking no free-form text at all,
rather than by gating emission:

- **States:** `sent` (the `send_message` frame was handed to the open connection; carries the connection
  token), `acknowledged` (the daemon's `ack` arrived), `queued` (a `queue_state` snapshot carried the
  `message_id`), `delivered` (the message left the daemon's queue, or came back as a pushed, delivered
  `message`), `failed`, `dropped`. A state already reached for a `message_id` is never logged twice.
- **Failure reasons:** `not_connected` (not sent at all), `daemon_error` with `code=<the daemon's error
  code>` (never its message), `torn_down` (the connection tore down before the reply, e.g. `failAllPending`'s
  `PENDING_REQUEST_TORN_DOWN`). `dropped` always carries `reason=user_dropped` (a confirmed
  `dropQueuedMessage`, which is a user action, not a failure).
- **Line format:** `<ISO-8601 instant> id=<uuid> state=<state>[ conn=<token>][ reason=<reason>][
  code=<code>]`.
- **Never logs:** message text, attachment names, the relay host, the pairing token, a full `conn_id`, or
  the daemon's error message. The API has no text parameter at all — every value is shape-checked before it
  can appear: `message_id` must match this app's lowercase-UUID mint shape or the call records nothing;
  the connection token must match `RelayLog.redactConnId`'s `^[0-9a-f]{8}$` shape or the line reads
  `conn=none`; an error code must match `^[a-z0-9_.]{1,64}$` or the line reads `code=unknown`.
- **Connection token:** the same `RelayLog.redactConnId` 8-hex BLAKE2s token used elsewhere in this
  document, read from `PumpState.Open.connId` by `RelayRepositoryCoordinator` and threaded down to
  `MessageCommands` — the full `conn_id` never leaves the coordinator.

**Getting it off the phone.** Lines reach logcat under the tag `PyryMessageTrail` immediately (`adb logcat
-s PyryMessageTrail`), but logcat rolls over within hours and a release build can't be read with `run-as`.
So the trail also writes to a bounded file in the app-specific external files directory, survives process
death and app restart, and is capped at 512 KiB — an append that would pass the cap first rewrites the file
to the newest whole lines that fit in half of it (oldest lines dropped first), through a `.tmp` file and a
rename. Pull it with:

```
adb pull /sdcard/Android/data/de.pyryco.mobile/files/message-trail.log
```

Confirmed on the managed SDK 33 emulators: the app-specific external directory is `drwxrws--- <app>
ext_data_rw`, and the `adb shell` user is in group `ext_data_rw`, so `adb pull` can read it without root. An
in-app "save trail" affordance is out of scope; it would be a separate, Figma-backed UI ticket.

**The send path never waits on this.** `record` is non-suspending (a synchronized per-message state check,
a `logcat` call, and a `trySend` on a `Channel(1024, DROP_OLDEST)`); the actual file write happens on one
owned writer coroutine, so a slow or failing disk can delay or drop a line but never a send. A failed file
write (missing external storage, a directory in the way, a full disk) loses that line and nothing else;
`sendMessage`'s own exception contract is unchanged by the trail.

**Known gap: the trail is blind across a reconnect.** `queued`, `delivered` and `dropped` are gated on
`ThreadProjection.mintedMessageIds`, a per-connection, in-memory ledger (one `ThreadProjection` per
connection, since each connection gets its own `RemoteConversationRepository`). If a message is sent on
connection A and A drops before the daemon's reply, then on connection B a `queue_state` naming that
message, its drain, or a pushed `message` delivering it records nothing — the id isn't in B's ledger. The
trail's last line for that message then stays `queued` or `failed reason=torn_down` even though the phone
later saw it delivered, which is exactly the reconnect case this trail exists to diagnose. Read a trail that
ends on `queued` or `torn_down` with this gap in mind rather than as proof the message never arrived; cross-
check the daemon's own journal. Fixing it would mean having the process-wide `MessageTrail` itself track
reached states across connections rather than relying on a connection-scoped ledger to gate `queued` /
`delivered` — left as a follow-up, not done in #1564.

See [ADR 0008](../decisions/0008-separate-release-kept-message-trail.md) for why this is a second facility
beside `RelayLog` rather than an adoption of it.

## Related

- [Message trail](#message-trail--a-separate-release-kept-facility-messagetrail-1564) (`data/diagnostics/MessageTrail.kt`, #1564) — a separate, release-kept per-message-state log; see above
- [Static-key fingerprint](static-key-fingerprint.md) — the digest-then-truncate sibling
  `redactConnId` mirrors; note the **different, deliberate** width (correlation ≥ 4 bytes here vs.
  authentication ≥ 8 bytes there)
- [ADR 0004 — vendor `noise-java`](../decisions/0004-vendor-noise-java-crypto.md) — source of the
  vendored `Blake2sMessageDigest`; no new dependency
- [Relay WebSocket transport](relay-ws-transport.md), [Noise session pump](noise-session-pump.md) —
  adopted this facility in #1039; see § Adopted call sites above for the fixed labels each writes, and
  each doc's own Logging section
- [Relay reconnect supervisor](relay-reconnect-supervisor.md),
  [Relay repository coordinator](relay-repository-coordinator.md) — still emit no logs; the transport
  line already carries the `Down` code the supervisor sees
- [Paired server store](paired-server-store.md) — home of the `PairedServer.toString`
  never-log-secret comment this facility's contract generalizes
- [Attachment upload](attachment-upload.md) ([#829](https://github.com/pyrycode/pyrycode-mobile/issues/829)) —
  a `data/repository/` adopter whose unit tests needed the `sink` capturing seam this doc's Testing
  section describes, because the upload's `RelayLog.d` calls run inside a `backgroundScope` coroutine
  where the JVM `"not mocked"` throw silently kills the coroutine instead of failing the test
- Ticket: [#521](../codebase/521.md) — implementation notes. Split from #500; sibling #522 (transport
  frame-reject tests)
